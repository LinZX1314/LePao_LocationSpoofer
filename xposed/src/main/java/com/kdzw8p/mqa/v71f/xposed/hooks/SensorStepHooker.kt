@file:Suppress(
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "DEPRECATION",
    "NAME_SHADOWING",
    "FunctionName",
    "PrivatePropertyName",
    "SpellCheckingInspection",
    "RedundantUnitReturnType",
    "RemoveRedundantQualifierName",
    "OPT_IN_USAGE",
    "unused",
    "UnusedImport"
)

package com.kdzw8p.mqa.v71f.xposed.hooks

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kdzw8p.mqa.v71f.utils.GaitTemplate
import com.kdzw8p.mqa.v71f.xposed.LocationHooker
import com.kdzw8p.mqa.v71f.xposed.utils.RouteEngine
import com.kdzw8p.mqa.v71f.xposed.utils.XposedHelpers
import com.kdzw8p.mqa.v71f.xposed.utils.isDataStepSyncEnabled
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object SensorStepHooker {

    data class CapturedSensorListener(
        val listener: Any,
        val sensor: Sensor?,
        val handler: Handler?
    )

    val capturedListeners = CopyOnWriteArrayList<CapturedSensorListener>()
    private val hookedListenerClasses = ConcurrentHashMap<Class<*>, Boolean>()

    // 真实计步器自开机以来单调递增：换会话 / 摇杆每次写配置导致 start_timestamp 变化时，
    // 把已累计的步数接续为新的起点，而不是回到初始值
    private const val INITIAL_BOOT_STEPS = 2350.0
    @Volatile private var stepBase: Double = INITIAL_BOOT_STEPS
    @Volatile private var lastStepsFloat: Double = INITIAL_BOOT_STEPS
    @Volatile private var lastStepInitTime: Long = 0L

    // 步检测器按累计步数阈值发事件，不能每次轮询固定发一步（那会把步频锁成 60 SPM）。
    private val detectorLock = Any()
    private var detectorSession: Long = Long.MIN_VALUE
    private var nextDetectorStep = 0L
    private var detectorGeneration = 0L
    private val syntheticDetectorEvents = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<SensorEvent, Boolean>())
    )

    private val noiseRng = java.util.Random()
    @Volatile private var cachedTemplateSource: String? = null
    @Volatile private var cachedTemplate: GaitTemplate? = null

    // 缓存虚拟 Sensor 实例
    private var mockStepCounterSensor: Sensor? = null
    private var mockStepDetectorSensor: Sensor? = null
    private var mockAccelerometerSensor: Sensor? = null

    // 跟踪 handle 到 sensor type 的映射
    private val handleToTypeMap = ConcurrentHashMap<Int, Int>()

    fun hookSensorStepSimulation(classLoader: ClassLoader) {
        val targetClasses = listOf(
            "android.hardware.SensorManager",
            "android.hardware.SystemSensorManager"
        )

        for (className in targetClasses) {
            val managerClass = XposedHelpers.findClassIfExists(className, classLoader) ?: continue

            // 1. Hook getDefaultSensor: 当设备无物理计步传感器时注入虚拟 Sensor
            try {
                XposedHelpers.hookAllMethods(managerClass, "getDefaultSensor") { chain, _ ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    val type = (chain.args.firstOrNull() as? Int) ?: return@hookAllMethods result
                    if (result == null && (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR)) {
                        return@hookAllMethods getOrCreateMockSensor(type, classLoader)
                    }
                    return@hookAllMethods result
                }
            } catch (_: Throwable) {}

            // 2. Hook getSensorList
            try {
                XposedHelpers.hookAllMethods(managerClass, "getSensorList") { chain, _ ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    val type = (chain.args.firstOrNull() as? Int) ?: return@hookAllMethods result
                    if (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) {
                        if (result is List<*> && result.isEmpty()) {
                            val mock = getOrCreateMockSensor(type, classLoader)
                            if (mock != null) return@hookAllMethods listOf(mock)
                        }
                    }
                    return@hookAllMethods result
                }
            } catch (_: Throwable) {}

            // 3. Hook registerListener / registerListenerImpl
            val regMethods = listOf("registerListener", "registerListenerImpl")
            for (methodName in regMethods) {
                try {
                    XposedHelpers.hookAllMethods(managerClass, methodName) { chain, _ ->
                        val args = chain.args
                        var listener: Any? = null
                        var sensor: Sensor? = null
                        var handler: Handler? = null

                        for (arg in args) {
                            if (arg != null) {
                                if (arg is SensorEventListener || LocationHooker.hasTypeByName(arg.javaClass, "android.hardware.SensorEventListener")) {
                                    listener = arg
                                } else if (arg is Sensor) {
                                    sensor = arg
                                } else if (arg is Handler) {
                                    handler = arg
                                }
                            }
                        }

                        if (listener != null) {
                            val targetSensor = sensor ?: getOrCreateMockSensor(Sensor.TYPE_STEP_COUNTER, classLoader)
                            if (targetSensor != null && (targetSensor.type == Sensor.TYPE_STEP_COUNTER || targetSensor.type == Sensor.TYPE_STEP_DETECTOR || targetSensor.type == Sensor.TYPE_ACCELEROMETER)) {
                                val entry = CapturedSensorListener(listener, targetSensor, handler)
                                if (!capturedListeners.any { it.listener === listener && it.sensor?.type == targetSensor.type }) {
                                    capturedListeners.add(entry)
                                }

                                val handle = getSensorHandle(targetSensor)
                                if (handle != null) {
                                    handleToTypeMap[handle] = targetSensor.type
                                }

                                // 动态 Hook 该 Listener 的具体实现类中的 onSensorChanged 方法
                                hookConcreteListenerClass(listener.javaClass, classLoader)
                            }
                        }

                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                } catch (_: Throwable) {}
            }

            // 4. Hook unregisterListener / unregisterListenerImpl
            val unregMethods = listOf("unregisterListener", "unregisterListenerImpl")
            for (methodName in unregMethods) {
                try {
                    XposedHelpers.hookAllMethods(managerClass, methodName) { chain, _ ->
                        val args = chain.args
                        val listener = args.firstOrNull { it != null && (it is SensorEventListener || LocationHooker.hasTypeByName(it.javaClass, "android.hardware.SensorEventListener")) }
                        if (listener != null) {
                            capturedListeners.removeAll { it.listener === listener }
                        }
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                } catch (_: Throwable) {}
            }
        }

        // 5. Hook SystemSensorManager$SensorEventQueue.dispatchSensorEvent 底层原生分发接口
        hookSensorEventQueue(classLoader)
    }

    private fun hookSensorEventQueue(classLoader: ClassLoader) {
        try {
            val eventQueueClass = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
                ?: XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$BaseEventQueue", classLoader)
                ?: return

            XposedHelpers.hookAllMethods(eventQueueClass, "dispatchSensorEvent") { chain, _ ->
                val config = (XposedHelpers.module as? LocationHooker)?.readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("enable_step_simulation", true)) {
                    val handle = chain.args.firstOrNull() as? Int
                    val values = chain.args.getOrNull(1) as? FloatArray

                    if (handle != null && values != null) {
                        val sensorType = handleToTypeMap[handle]
                        val speed = config.optDouble("speed_m_s", 0.0)

                        if (sensorType == Sensor.TYPE_STEP_COUNTER) {
                            // 仅对明确判定的计步器传感器进行步数计算，严禁误判光感/距离传感器
                            val curSteps = calculateCurrentSteps(config)
                            values[0] = curSteps.toFloat()
                        } else if (sensorType == Sensor.TYPE_STEP_DETECTOR) {
                            // 原生硬件节拍由我们自己的累计步数事件替代，不能再计入一步。
                            values[0] = 0.0f
                            return@hookAllMethods null
                        } else if (sensorType == Sensor.TYPE_ACCELEROMETER && values.size >= 3) {
                            // 加速度计传感器：仅在运动中(speed > 0.1)叠加生理跑步/步行震动特征，静态时绝不篡改
                            // 隐藏开关 data_step_sync（默认开）：关掉后不叠加合成加速度波形，只保留真实传感器读数
                            if (speed > 0.1 && (XposedHelpers.module as? LocationHooker)?.isDataStepSyncEnabled() != false) {
                                applySyntheticVibration(values, config, speed)
                            }
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    internal fun saveReloadState(): Array<Any?> = arrayOf(
        capturedListeners.map { arrayOf<Any?>(it.listener, it.sensor, it.handler) }.toTypedArray(),
        stepBase, lastStepsFloat, lastStepInitTime,
        mockStepCounterSensor, mockStepDetectorSensor, mockAccelerometerSensor,
        handleToTypeMap.map { arrayOf(it.key, it.value) }.toTypedArray()
    )

    internal fun clearReloadState() { capturedListeners.clear(); hookedListenerClasses.clear() }

    internal fun restoreReloadState(value: Any?) {
        val state = value as? Array<*> ?: return
        (state.getOrNull(0) as? Array<*>)?.filterIsInstance<Array<*>>()?.forEach {
            capturedListeners.add(CapturedSensorListener(it[0]!!, it[1] as? Sensor, it[2] as? Handler))
            hookConcreteListenerClass(it[0]!!.javaClass, it[0]!!.javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
        }
        stepBase = state[1] as Double
        lastStepsFloat = state[2] as Double
        lastStepInitTime = state[3] as Long
        mockStepCounterSensor = state[4] as? Sensor
        mockStepDetectorSensor = state[5] as? Sensor
        mockAccelerometerSensor = state[6] as? Sensor
        (state[7] as? Array<*>)?.filterIsInstance<Array<*>>()?.forEach {
            handleToTypeMap[it[0] as Int] = it[1] as Int
        }
        synchronized(detectorLock) {
            detectorSession = Long.MIN_VALUE
            detectorGeneration++
        }
    }

    /**
     * 动态 Hook 具体的 Listener 实现类（如 Keep 的 StepListener）
     */
    private fun hookConcreteListenerClass(clazz: Class<*>, classLoader: ClassLoader) {
        if (hookedListenerClasses.putIfAbsent(clazz, true) == null) {
            try {
                XposedHelpers.hookAllMethods(clazz, "onSensorChanged") { chain, _ ->
                    val event = chain.args.firstOrNull() as? SensorEvent
                    if (event != null && event.sensor != null) {
                        val config = (XposedHelpers.module as? LocationHooker)?.readConfig()
                        if (config != null && config.optBoolean("active", false) && config.optBoolean("enable_step_simulation", true)) {
                            val speed = config.optDouble("speed_m_s", 0.0)
                            if (event.sensor.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
                                val curSteps = calculateCurrentSteps(config)
                                event.values[0] = curSteps.toFloat()
                                event.timestamp = SystemClock.elapsedRealtimeNanos()
                            } else if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR && event.values.isNotEmpty()) {
                                // 合成事件按自己的步频放行，原生事件不额外计步。
                                if (!syntheticDetectorEvents.contains(event)) return@hookAllMethods null
                                event.timestamp = SystemClock.elapsedRealtimeNanos()
                            } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && event.values.size >= 3 && speed > 0.1) {
                                // 隐藏开关 data_step_sync（默认开）：关掉后传感器事件不叠加合成波形
                                if ((XposedHelpers.module as? LocationHooker)?.isDataStepSyncEnabled() != false) {
                                    applySyntheticVibration(event.values, config, speed)
                                    event.timestamp = SystemClock.elapsedRealtimeNanos()
                                }
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            } catch (_: Throwable) {}
        }
    }

    private fun applySyntheticVibration(values: FloatArray, config: JSONObject, speed: Double) {
        val now = System.currentTimeMillis()
        val stepsFloat = calculateCurrentStepsFloat(config, now)
        val motionSpeed = RouteEngine.calculateCurrentPosition(config, now).speed.toDouble().takeIf { it > 0.0 } ?: speed
        val session = RouteEngine.realismSession(config)
        val elapsed = ((now - config.optLong("start_timestamp", now)).coerceAtLeast(0L)) / 1000.0
        // 降速块内逐步力度与噪声一起收敛：人放慢时步子更轻、抖动更小
        val synthetic = session.accelerometer(
            stepsFloat, motionSpeed, gaitTemplate(config), noiseRng, session.slowdownAt(elapsed)
        )
        values[0] = synthetic[0]
        values[1] = synthetic[1]
        values[2] = synthetic[2]
    }

    /** 用户录制的步态模板（配置里的编码字符串），按字符串内容缓存解码结果 */
    private fun gaitTemplate(config: JSONObject): GaitTemplate? {
        val source = config.optString("gait_template", "")
        if (source != cachedTemplateSource) {
            cachedTemplate = GaitTemplate.decode(source)
            cachedTemplateSource = source
        }
        return cachedTemplate
    }

    /**
     * 计算当前仿真总步数
     */
    fun calculateCurrentSteps(config: JSONObject, now: Long = System.currentTimeMillis()): Long =
        calculateCurrentStepsFloat(config, now).toLong()

    /** 连续的累计步数：整数部分是步数，小数部分是当前这一步的相位，供加速度波形对齐 */
    private fun calculateCurrentStepsFloat(config: JSONObject, now: Long): Double {
        advanceStepSession(config, now)
        val speed = config.optDouble("speed_m_s", 0.0)
        if (speed <= 0.05) return lastStepsFloat
        val steps = stepsAt(config, now, stepBase)
        lastStepsFloat = steps
        return steps
    }

    /** 配置变化时延续当前累计步数，同时换用新的曲线基准，避免步数重置或时间基准错位。 */
    private fun advanceStepSession(config: JSONObject, now: Long) {
        val startTime = config.optLong("start_timestamp", now)
        if (lastStepInitTime != startTime) {
            val oldBase = stepBase
            stepBase = stepsAt(config, now, oldBase)
            lastStepInitTime = startTime
        }
    }

    /** 纯函数：不推进 lastStepsFloat，供步检测器计算下一个整数步阈值何时到达。 */
    private fun stepsAt(config: JSONObject, now: Long, base: Double): Double {
        val startTime = config.optLong("start_timestamp", now)
        val speed = config.optDouble("speed_m_s", 0.0)
        if (speed <= 0.05) return 0.0
        val elapsedSec = ((now - startTime).coerceAtLeast(0L)) / 1000.0
        val baseCadence = if (config.optBoolean("is_auto_cadence", false)) {
            calculateAutoCadence(speed)
        } else {
            config.optInt("step_cadence_spm", 90).coerceIn(60, 240)
        }
        return base + RouteEngine.realismSession(config, startTime)
            .steps(baseCadence.toDouble(), elapsedSec)
    }

    /** 求某个整数步阈值在累计步数曲线上的到达时刻；轮询只负责补排程，不决定步频。 */
    private fun stepTimeMs(config: JSONObject, now: Long, targetStep: Long): Long {
        val base = stepBase
        val startTime = config.optLong("start_timestamp", now)
        val currentSteps = stepsAt(config, now, base)
        var low = startTime.toDouble()
        var high = now.toDouble()
        if (currentSteps < targetStep) {
            low = now.toDouble()
            high = now + 1_000.0
            var guard = 0
            while (stepsAt(config, high.toLong(), base) < targetStep && guard++ < 4) {
                high = now + (high - now) * 2
            }
            if (stepsAt(config, high.toLong(), base) < targetStep) return now + 5_000L
        }
        repeat(20) {
            val mid = (low + high) / 2
            if (stepsAt(config, mid.toLong(), base) >= targetStep) high = mid else low = mid
        }
        return high.toLong()
    }

    /**
     * 智能根据速度自动计算匹配的生理步频 (SPM, 步/分钟)
     */
    fun calculateAutoCadence(speedMs: Double): Int {
        return when {
            speedMs <= 0.8 -> 95 // 慢走
            speedMs <= 1.5 -> (100 + (speedMs - 0.8) / 0.7 * 25).toInt() // 快走 100~125 SPM
            speedMs <= 2.8 -> (135 + (speedMs - 1.5) / 1.3 * 25).toInt() // 慢跑 135~160 SPM
            speedMs <= 4.0 -> (160 + (speedMs - 2.8) / 1.2 * 20).toInt() // 匀速跑 160~180 SPM
            speedMs <= 5.5 -> (180 + (speedMs - 4.0) / 1.5 * 15).toInt() // 快跑 180~195 SPM
            else -> 200 // 冲刺 200 SPM
        }
    }

    /**
     * 由 ConfigPoller 每秒主动向监听器推送计步事件（仅在运动路线模拟中生效）
     */
    fun dispatchStepEvents(config: JSONObject, classLoader: ClassLoader) {
        if (!config.optBoolean("active", false)) return
        val enableStep = config.optBoolean("enable_step_simulation", true)
        if (!enableStep) return

        val speed = config.optDouble("speed_m_s", 0.0)
        // 静态定位时绝不主动推送步频和加速度事件，保证主线程和人脸识别传感器纯净
        if (speed <= 0.05) return

        val now = System.currentTimeMillis()
        val totalSteps = calculateCurrentSteps(config, now)

        val listeners = capturedListeners.toList()
        if (listeners.isEmpty()) return

        // 步检测器由整组监听器共享一张时间表，不能进入下面的逐监听器循环后再消费阈值。
        val mainHandler = Handler(Looper.getMainLooper())
        scheduleDetectorSteps(config, now, totalSteps.toDouble(), listeners).forEach { (delayMs, task) ->
            if (delayMs <= 1L) mainHandler.post(task) else mainHandler.postDelayed(task, delayMs)
        }

        for (entry in listeners) {
            val listener = entry.listener
            val sensor = entry.sensor ?: continue
            val targetHandler = entry.handler ?: Handler(Looper.getMainLooper())

            when (sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    val event = createSensorEvent(sensor, floatArrayOf(totalSteps.toFloat()))
                    if (event != null) {
                        targetHandler.post {
                            try {
                                if (listener is SensorEventListener) {
                                    listener.onSensorChanged(event)
                                } else {
                                    XposedHelpers.callMethod(listener, "onSensorChanged", event)
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                Sensor.TYPE_STEP_DETECTOR -> Unit // 已在循环前统一排程，所有监听器都收到每一步
                Sensor.TYPE_ACCELEROMETER -> {
                    if (speed > 0.1 && (XposedHelpers.module as? LocationHooker)?.isDataStepSyncEnabled() != false) {
                        val rawValues = floatArrayOf(0f, 0f, 9.8f)
                        applySyntheticVibration(rawValues, config, speed)
                        val event = createSensorEvent(sensor, rawValues)
                        if (event != null) {
                            targetHandler.post {
                                try {
                                    if (listener is SensorEventListener) {
                                        listener.onSensorChanged(event)
                                    } else {
                                        XposedHelpers.callMethod(listener, "onSensorChanged", event)
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 按累计步数曲线的整数阈值排程。每次只排未来 2 秒，轮询只负责续排，不决定实际步频。
     * 同一阈值给每个计步监听器各发一个事件，注销、停跑和换会话都不会补发旧事件。
     */
    private fun scheduleDetectorSteps(
        config: JSONObject,
        now: Long,
        steps: Double,
        listeners: List<CapturedSensorListener>
    ): List<Pair<Long, Runnable>> {
        val entries = listeners.filter { it.sensor?.type == Sensor.TYPE_STEP_DETECTOR }
        if (entries.isEmpty()) return emptyList()
        val startTime = config.optLong("start_timestamp", now)
        val times = ArrayList<Long>()
        val generation: Long
        synchronized(detectorLock) {
            if (detectorSession != startTime) {
                detectorSession = startTime
                nextDetectorStep = kotlin.math.floor(steps).toLong() + 1
                detectorGeneration++
            }
            generation = detectorGeneration
            var guard = 0
            while (guard++ < 16) {
                val targetTime = stepTimeMs(config, now, nextDetectorStep)
                if (targetTime > now + 2_000L) break
                times += targetTime
                nextDetectorStep++
            }
        }
        return times.map { targetTime ->
            (targetTime - now).coerceAtLeast(1L) to Runnable {
                val valid = synchronized(detectorLock) {
                    generation == detectorGeneration && detectorSession == startTime
                }
                if (!valid) return@Runnable
                val active = (XposedHelpers.module as? LocationHooker)?.readConfig()?.let {
                    it.optBoolean("active", false) && it.optBoolean("enable_step_simulation", true) &&
                        it.optDouble("speed_m_s", 0.0) > 0.05
                } == true
                if (!active) return@Runnable
                for (entry in entries) {
                    val sensor = entry.sensor ?: continue
                    val event = createSensorEvent(sensor, floatArrayOf(1.0f)) ?: continue
                    val deliver = Runnable {
                        syntheticDetectorEvents += event
                        try {
                            val registered = capturedListeners.any {
                                it.listener === entry.listener && it.sensor?.type == Sensor.TYPE_STEP_DETECTOR
                            }
                            if (!registered) return@Runnable
                            if (entry.listener is SensorEventListener) entry.listener.onSensorChanged(event)
                            else XposedHelpers.callMethod(entry.listener, "onSensorChanged", event)
                        } catch (_: Throwable) {
                        } finally {
                            syntheticDetectorEvents -= event
                        }
                    }
                    val handler = entry.handler
                    if (handler == null || handler.looper == Looper.myLooper()) deliver.run()
                    else handler.post(deliver)
                }
            }
        }
    }

    private fun getSensorHandle(sensor: Sensor): Int? {
        return try {
            val handleField = Sensor::class.java.getDeclaredField("mHandle")
            handleField.isAccessible = true
            handleField.getInt(sensor)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getOrCreateMockSensor(type: Int, classLoader: ClassLoader): Sensor? {
        if (type == Sensor.TYPE_STEP_COUNTER && mockStepCounterSensor != null) return mockStepCounterSensor
        if (type == Sensor.TYPE_STEP_DETECTOR && mockStepDetectorSensor != null) return mockStepDetectorSensor
        if (type == Sensor.TYPE_ACCELEROMETER && mockAccelerometerSensor != null) return mockAccelerometerSensor

        try {
            val sensorClass = Class.forName("android.hardware.Sensor", false, classLoader)
            val constructor: Constructor<*> = sensorClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val sensor = constructor.newInstance() as Sensor

            setSensorField(sensor, "mType", type)
            setSensorField(sensor, "mName", when(type) {
                Sensor.TYPE_STEP_COUNTER -> "Step Counter Sensor"
                Sensor.TYPE_STEP_DETECTOR -> "Step Detector Sensor"
                else -> "Accelerometer Sensor"
            })
            setSensorField(sensor, "mVendor", "Android")
            setSensorField(sensor, "mVersion", 1)
            setSensorField(sensor, "mResolution", 1.0f)
            setSensorField(sensor, "mPower", 0.05f)

            when (type) {
                Sensor.TYPE_STEP_COUNTER -> mockStepCounterSensor = sensor
                Sensor.TYPE_STEP_DETECTOR -> mockStepDetectorSensor = sensor
                Sensor.TYPE_ACCELEROMETER -> mockAccelerometerSensor = sensor
            }
            return sensor
        } catch (_: Throwable) {
            return null
        }
    }

    private fun setSensorField(sensor: Sensor, fieldName: String, value: Any) {
        try {
            val field: Field = Sensor::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(sensor, value)
        } catch (_: Throwable) {}
    }

    private fun createSensorEvent(sensor: Sensor, values: FloatArray): SensorEvent? {
        try {
            val eventConstructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            eventConstructor.isAccessible = true
            val event = eventConstructor.newInstance(values.size) as SensorEvent
            System.arraycopy(values, 0, event.values, 0, values.size)

            val sensorField = SensorEvent::class.java.getDeclaredField("sensor")
            sensorField.isAccessible = true
            sensorField.set(event, sensor)

            val timestampField = SensorEvent::class.java.getDeclaredField("timestamp")
            timestampField.isAccessible = true
            timestampField.setLong(event, SystemClock.elapsedRealtimeNanos())

            val accuracyField = SensorEvent::class.java.getDeclaredField("accuracy")
            accuracyField.isAccessible = true
            accuracyField.setInt(event, 3) // SENSOR_STATUS_ACCURACY_HIGH

            return event
        } catch (_: Throwable) {
            try {
                // Fallback 1: search any constructors
                val constructors = SensorEvent::class.java.declaredConstructors
                for (ctor in constructors) {
                    ctor.isAccessible = true
                    val paramTypes = ctor.parameterTypes
                    val args = arrayOfNulls<Any>(paramTypes.size)
                    for (i in args.indices) {
                        if (paramTypes[i] == Int::class.javaPrimitiveType) args[i] = values.size
                    }
                    val event = ctor.newInstance(*args) as SensorEvent
                    System.arraycopy(values, 0, event.values, 0, values.size)
                    try {
                        val sf = SensorEvent::class.java.getDeclaredField("sensor")
                        sf.isAccessible = true
                        sf.set(event, sensor)
                        val tf = SensorEvent::class.java.getDeclaredField("timestamp")
                        tf.isAccessible = true
                        tf.setLong(event, SystemClock.elapsedRealtimeNanos())
                        val af = SensorEvent::class.java.getDeclaredField("accuracy")
                        af.isAccessible = true
                        af.setInt(event, 3)
                    } catch (_: Throwable) {}
                    return event
                }
            } catch (_: Throwable) {}

            try {
                // Fallback 2: sun.misc.Unsafe.allocateInstance
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
                theUnsafeField.isAccessible = true
                val unsafe = theUnsafeField.get(null)
                val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                val event = allocateMethod.invoke(unsafe, SensorEvent::class.java) as SensorEvent

                val valuesField = SensorEvent::class.java.getDeclaredField("values")
                valuesField.isAccessible = true
                val arr = FloatArray(values.size)
                System.arraycopy(values, 0, arr, 0, values.size)
                valuesField.set(event, arr)

                val sf = SensorEvent::class.java.getDeclaredField("sensor")
                sf.isAccessible = true
                sf.set(event, sensor)

                val tf = SensorEvent::class.java.getDeclaredField("timestamp")
                tf.isAccessible = true
                tf.setLong(event, SystemClock.elapsedRealtimeNanos())

                val af = SensorEvent::class.java.getDeclaredField("accuracy")
                af.isAccessible = true
                af.setInt(event, 3)
                return event
            } catch (_: Throwable) {}

            return null
        }
    }
}
