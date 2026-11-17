package com.kdzw8p.mqa.v71f.xposed.hooks

import android.app.Application
import android.database.Cursor
import com.kdzw8p.mqa.v71f.xposed.utils.XposedBridge
import com.kdzw8p.mqa.v71f.xposed.utils.XposedHelpers
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 4.2.3 的记录 DAO 只写 42 列；遗留数据库中的三个额外必填列会使原始保存失败。
 * 仅修复实测的 47 列结构，不修改记录内容、核验结果、到点条件或当前模型字段约束。
 * 原表在同一事务内改名保留为备份；不匹配的数据库保持原样并报告错误。
 */
internal object LepaoRecordSchemaCompatibility {
    private const val TABLE = "LOCAL_DIRECTION_RUN_RECORD"
    private const val BACKUP = "LSP_RECORD_SCHEMA_BACKUP_423_V1"
    private val installed = AtomicBoolean(false)
    private val lock = Any()
    private val checked = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val reportedFailures = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val legacyRequired = setOf("RANDOM_FACE", "CURRENT_RANDOM_FACE_INDEX", "IS_TIMEOUT_ZFB")
    private val legacyNullable = setOf("RANDOM_FACE_LIST", "POINT_TYPE_STR")
    private val daoColumns = listOf(
        "_id", "SCHOOL_ID", "RUN_ZONE_ID", "END_TIME", "DISTANCE", "CURRENT_SUM_DISTANCE",
        "DEFAULT_DISTANCE", "FILE_NAME", "TERM_ID", "USED_TIME", "LOG_POINTS", "RUN_CHECK", "UID",
        "IS_UPLOAD", "RUN_ZONE_NAME", "STEP_NUM", "SHAKE_NUM", "TYPE", "PER_STEP", "IS_RUNNING_AREA_VALID",
        "VALID_LAT_LNG_COUNT", "TOTAL_LAT_LNG_COUNT", "PATH", "SENSOR_PATH", "TRACK_IMG", "RECORD_STR",
        "SESSION_ID", "FACE_AUTH_STATUS", "NEED_CHECK_GEE_TEST", "NEED_CHECK_GEE_DP", "NEED_CHECK_GEE_DEVICE",
        "CHECK_TYPE", "RANDOM_CHECK", "IS_RANDOM_CHECK", "FACE_VERIFY_ID", "START_FACE_VERIFY",
        "MIDDLE_FACE_VERIFY", "STOP_FACE_VERIFY", "DEVICE_STATUS", "GEE_TOKEN", "FACE_VERIFY_STATUS", "SHOW_TYPE"
    )
    private val columns = daoColumns.toMutableList().apply {
        add(indexOf("IS_RANDOM_CHECK"), "RANDOM_FACE")
        add(indexOf("IS_RANDOM_CHECK"), "RANDOM_FACE_LIST")
        add(indexOf("IS_RANDOM_CHECK"), "CURRENT_RANDOM_FACE_INDEX")
        add(indexOf("SHOW_TYPE"), "IS_TIMEOUT_ZFB")
        add("POINT_TYPE_STR")
    }
    private val textColumns = setOf(
        "FILE_NAME", "LOG_POINTS", "RUN_CHECK", "RUN_ZONE_NAME", "PER_STEP", "PATH", "SENSOR_PATH",
        "TRACK_IMG", "RECORD_STR", "SESSION_ID", "FACE_VERIFY_ID", "DEVICE_STATUS", "GEE_TOKEN",
        "RANDOM_FACE_LIST", "POINT_TYPE_STR"
    )
    private val realColumns = setOf("DISTANCE", "CURRENT_SUM_DISTANCE", "DEFAULT_DISTANCE", "RANDOM_CHECK", "RANDOM_FACE")
    private data class Column(val name: String, val type: String, val required: Boolean, val default: String?, val pk: Int)

    fun install(loader: ClassLoader) {
        val manager = XposedHelpers.findClassIfExists("com.lptiyu.tanke.utils.v", loader) ?: return
        val model = XposedHelpers.findClassIfExists(
            "com.lptiyu.tanke.entity.greendao.LocalDirectionRunRecord", loader
        ) ?: return
        // 不以服务端配置/开关推断模型版本；有新字段的模型绝不执行旧版迁移。
        if (model.declaredFields.any { it.name in setOf("random_face", "randomFace", "current_random_face_index", "is_timeout_zfb") }) return
        if (!installed.compareAndSet(false, true)) return
        for (method in manager.declaredMethods) {
            if (method.name !in setOf("R", "S", "a1", "b1") || method.parameterCount != 1) continue
            if (method.parameterTypes[0] != model && !List::class.java.isAssignableFrom(method.parameterTypes[0])) continue
            XposedHelpers.module.hook(method).intercept { chain ->
                val owner = chain.thisObject
                if (owner != null) ensureCompatible(owner)
                chain.proceed(chain.args.toTypedArray())
            }
        }
        XposedBridge.log("[RecordSchemaCompat] original record save hooks installed")
        // 页面就绪后先完成兼容检查；保存入口仍保留检查以覆盖连接重建。
        try {
            XposedHelpers.callStaticMethod(manager, "E")?.let { ensureCompatible(it) }
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            XposedBridge.log("[RecordSchemaCompat] startup check deferred: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun ensureCompatible(manager: Any) = synchronized(lock) {
        val db = findDatabase(manager) ?: return@synchronized
        if (db in checked || db in reportedFailures) return@synchronized
        try {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application
            @Suppress("DEPRECATION")
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            // 版本上报层会在 PackageInfo 返回处把 4.2.3 伪装成 233；这里必须用改写前的真实值判定。
            val realCode = LepaoUpdateBlocker.realInstalledVersionCode.takeIf { it > 0 }
                ?: info.versionCode
            val realName = LepaoUpdateBlocker.realInstalledVersionName.takeIf { it.isNotBlank() }
                ?: info.versionName
            @Suppress("DEPRECATION")
            check(app.packageName == "com.lptiyu.tanke" && realCode == 223 && realName == "4.2.3") {
                "Unsupported host version; database unchanged"
            }
            val before = readColumns(db)
            // 全新 4.2.3 表及已修复的结构无需迁移。
            if (before.map { it.name } == daoColumns ||
                (before.map { it.name } == columns && before.filter { it.name in legacyRequired }.all { !it.required })) {
                checked.add(db)
                return@synchronized
            }
            check(before.map { it.name } == columns) { "Unexpected columns; database unchanged" }
            for (column in before) {
                val expectedType = when (column.name) {
                    in textColumns -> "TEXT"
                    in realColumns -> "REAL"
                    else -> "INTEGER"
                }
                check(column.type == expectedType && column.default == null &&
                    column.required == (column.name !in textColumns) && column.pk == if (column.name == "_id") 1 else 0) {
                    "Unexpected column contract: ${column.name}; database unchanged"
                }
            }
            val objects = query(db, "SELECT type,name,sql FROM sqlite_master WHERE tbl_name='$TABLE'").use { cursor ->
                buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
            }
            check(objects.size == 1 && objects.single().first == "table") { "Indexes or triggers require a separate migration" }
            val originalSql = requireNotNull(objects.single().third)
            check(!hasIncomingReferences(db)) { "Referenced table requires a separate migration" }
            check(query(db, "PRAGMA foreign_key_list(\"$TABLE\")").use { it.count } == 0) { "Unexpected foreign keys" }
            check(query(db, "SELECT name FROM sqlite_master WHERE name='$BACKUP'").use { it.count } == 0) {
                "Backup already exists; database unchanged"
            }
            var replacementSql = originalSql
            for (name in legacyRequired) {
                val pattern = Regex("\"${Regex.escape(name)}\"\\s+(REAL|INTEGER)\\s+NOT\\s+NULL", RegexOption.IGNORE_CASE)
                check(pattern.findAll(replacementSql).count() == 1) { "Unrecognized legacy constraint: $name" }
                replacementSql = pattern.replace(replacementSql) { "\"$name\" ${it.groupValues[1]}" }
            }
            // 使用数据库现有连接和事务；备份与新表同时提交或同时回滚。
            invoke(db, "beginTransaction")
            try {
                val countBefore = scalarLong(db, "SELECT COUNT(*) FROM \"$TABLE\"")
                exec(db, "ALTER TABLE \"$TABLE\" RENAME TO \"$BACKUP\"")
                exec(db, replacementSql)
                val names = columns.joinToString(",") { "\"$it\"" }
                exec(db, "INSERT INTO \"$TABLE\" ($names) SELECT $names FROM \"$BACKUP\"")
                check(scalarLong(db, "SELECT COUNT(*) FROM \"$TABLE\"") == countBefore) { "Row count mismatch" }
                check(scalarLong(db, "SELECT COUNT(*) FROM (SELECT $names FROM \"$BACKUP\" EXCEPT SELECT $names FROM \"$TABLE\")") == 0L) {
                    "Record values changed"
                }
                val after = readColumns(db)
                check(after == before.map { if (it.name in legacyRequired) it.copy(required = false) else it }) {
                    "Post-migration schema mismatch"
                }
                query(db, "PRAGMA quick_check").use { check(it.moveToFirst() && it.getString(0) == "ok") { "Integrity check failed" } }
                invoke(db, "setTransactionSuccessful")
            } finally {
                invoke(db, "endTransaction")
            }
            checked.add(db)
            XposedBridge.log("[RecordSchemaCompat] migrated 47-column legacy table; original values and backup retained; verification fields unchanged")
        } catch (t: Throwable) {
            reportedFailures.add(db)
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            XposedBridge.log("[RecordSchemaCompat] not applied: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun findDatabase(manager: Any): Any? {
        val queue = ArrayDeque<Any>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        queue.add(manager)
        while (queue.isNotEmpty() && seen.size < 80) {
            val value = queue.removeFirst()
            if (!seen.add(value)) continue
            if (value.javaClass.name == "net.sqlcipher.database.SQLiteDatabase") return value
            var type: Class<*>? = value.javaClass
            while (type != null && type != Any::class.java) {
                for (field in type.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive || field.type == String::class.java) continue
                    val next = runCatching { field.isAccessible = true; field.get(value) }.getOrNull() ?: continue
                    val name = next.javaClass.name
                    if (name.startsWith("ui.") || name.startsWith("ul.") || name.startsWith("zl.") ||
                        name.startsWith("greendao.") || name == "net.sqlcipher.database.SQLiteDatabase") queue.add(next)
                }
                type = type.superclass
            }
        }
        return null
    }

    private fun hasIncomingReferences(db: Any): Boolean {
        val tables = query(db, "SELECT name,sql FROM sqlite_master WHERE type='table' OR type='view'").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    if (name != TABLE && cursor.getString(1)?.contains(TABLE, ignoreCase = true) == true) return true
                    add(name)
                }
            }
        }
        for (name in tables) {
            val escaped = name.replace("\"", "\"\"")
            query(db, "PRAGMA foreign_key_list(\"$escaped\")").use { cursor ->
                while (cursor.moveToNext()) if (cursor.getString(2).equals(TABLE, ignoreCase = true)) return true
            }
        }
        return false
    }

    private fun readColumns(db: Any): List<Column> = query(db, "PRAGMA table_info(\"$TABLE\")").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Column(
                cursor.getString(1), cursor.getString(2).uppercase(), cursor.getInt(3) != 0,
                if (cursor.isNull(4)) null else cursor.getString(4), cursor.getInt(5)
            ))
        }
    }

    private fun query(db: Any, sql: String): Cursor =
        db.javaClass.getMethod("rawQuery", String::class.java, Array<String>::class.java)
            .invoke(db, sql, emptyArray<String>()) as Cursor

    private fun exec(db: Any, sql: String) {
        db.javaClass.getMethod("execSQL", String::class.java).invoke(db, sql)
    }

    private fun scalarLong(db: Any, sql: String): Long = query(db, sql).use {
        check(it.moveToFirst()) { "Missing query result" }
        it.getLong(0)
    }

    private fun invoke(db: Any, method: String) {
        db.javaClass.getMethod(method).invoke(db)
    }
}
