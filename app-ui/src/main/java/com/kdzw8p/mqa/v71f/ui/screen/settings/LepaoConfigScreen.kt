package com.kdzw8p.mqa.v71f.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kdzw8p.mqa.v71f.data.model.AppState
import com.kdzw8p.mqa.v71f.ui.components.ModernSettingsInput
import com.kdzw8p.mqa.v71f.ui.theme.AccentBlue
import com.kdzw8p.mqa.v71f.ui.theme.AppColors
import com.kdzw8p.mqa.v71f.ui.theme.noRippleClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 乐跑（com.lptiyu.tanke）专用配置页。
 *
 * 这里控制的是「往乐跑进程里装哪些环境类 hook」，属于减少暴露面：
 * 乐跑只用高德定位，基站 / 蓝牙 / 计步传感器这几组 hook 对它没有功能收益，
 * 装上只会扩大被环境检测扫到的面，所以允许逐组关掉。
 *
 */
private const val TANKE_PKG = "com.lptiyu.tanke"

/** 检测层接管开关用的配置 key，与 Xposed 端 LepaoDetectionHooker 一一对应 */

private data class HookGroup(
    val key: String,
    val title: String,
    val subtitle: String
)

/**
 * 乐跑侧的检测层，按「谁在查、怎么查、实测结果」三层信息列出。
 * 内容来自报告_环境检测与Hook冲突.md、附录_壳层Hook检测清单.md 与三轮只读探针实测；
 * 未实测的层在这里也写明是推断，不与实测结论混用。
 */
private data class DetectionLayer(
    val title: String,
    val how: String,
    val status: String,
    val hit: Boolean
)

private val DETECTION_LAYERS = listOf(
    DetectionLayer(
        "服务端作弊应用黑名单",
        "服务端下发 android_cheats 包名列表；命中后 BeforeRunFragment 弹「手机系统环境异常」并删除该应用目录。实测未下发，未触发。",
        "未触发",
        false
    ),
    DetectionLayer(
        "本地已安装应用扫描",
        "l2.s() 遍历已安装应用的 requestedPermissions，找声明 ACCESS_MOCK_LOCATION 的应用。R7VqzN 清单里正有这条权限。开关打开时会命中。",
        "依赖开关",
        true
    ),
    DetectionLayer(
        "RootBeer Root 检测",
        "jg.c 共 24 个探针：su 路径、test-keys、RootCloak、busybox、/proc 挂载等。挂满探针后实测全程零调用。",
        "零调用",
        false
    ),
    DetectionLayer(
        "Xposed 安装器检测",
        "kwad cF/cG 查 de.robv.android.xposed.installer 与 com.saurik.substrate 两个包名。实测零调用；LSPosed 本身不在这两个包名内。",
        "零调用",
        false
    ),
    DetectionLayer(
        "壳层 ArtMethod 扫描",
        "libDexHelper 的 isHooked 解析 /proc/self/maps，比对 dexposed、frida_agent_main、adbi_hook 等符号。黑名单里没有 LSPosed。",
        "不命中",
        false
    )
)

/**
 * 检测层接管清单：每一条对应 Xposed 端 LepaoDetectionHooker 里一个 detect_* 开关，
 * 打开后对应检测函数被 hook 成固定返回 false（关闭则恢复原逻辑）。改动后重启乐跑生效。
 */
private val DETECTION_ITEMS = listOf(
    HookGroup(
        "detect_q0_emulator",
        "q0 模拟器判定",
        "com.lptiyu.tanke.utils.q0 的 a/b/c/d：qemu 管道、/proc/tty/drivers 的 goldfish、155552155xx 模拟器号码、VMP 入口。返回改为 false"
    ),
    HookGroup(
        "detect_q0_paths",
        "qemu 设备节点",
        "/dev/socket/qemud 与 /dev/qemu_pipe 的 File.exists 固定返回 false（仅这两个路径，其它文件读取不受影响）"
    ),
    HookGroup(
        "detect_avd_maps",
        "AvdUtils 地图/堆栈",
        "com.lptiyu.tanke.utils.i：/proc/self/maps 找 Substrate、XposedBridge.jar，堆栈找 Xposed/Substrate 特征。返回改为 false"
    ),
    HookGroup(
        "detect_mock_app_scan",
        "Mock 定位应用扫描",
        "l2.s() 遍历已安装应用找 ACCESS_MOCK_LOCATION 声明者（R7VqzN 自身会命中）。返回改为 false"
    ),
    HookGroup(
        "detect_kwad_emulator",
        "kwad Build 特征",
        "com.kwad.sdk.utils.s 的模拟器组：Build.MODEL/HARDWARE/FINGERPRINT 里的 Genymotion、Andy、nox、goldfish 等 + ro.kernel.qemu。返回改为 false"
    ),
    HookGroup(
        "detect_kwad_root",
        "kwad Root 特征",
        "kwad s 的 root 组：/system/app/Superuser.apk、su 二进制、which su、/data/su_test。返回改为 false"
    ),
    HookGroup(
        "detect_kwad_xposed",
        "kwad Xposed 特征",
        "kwad s 的 Xposed 组：de.robv.android.xposed.installer / com.saurik.substrate 包名、堆栈与 /proc/self/maps。返回改为 false"
    ),
    HookGroup(
        "detect_kwad_aggregate",
        "kwad 上报汇总位",
        "com.kwad.sdk.k.a.a.cF：所有子检查的唯一出口，返回 false 后整份环境上报（isRoot/isXPosed/isEmulator…）清零"
    ),
    HookGroup(
        "detect_rootbeer",
        "RootBeer 探针",
        "jg.c 的全部 24 个 boolean 探针：su 路径、test-keys、busybox、magisk、/proc 挂载 rw、RootCloak 等。返回改为 false"
    ),
    HookGroup(
        "detect_rootbeer_native",
        "RootBeer native",
        "com.scottyab.rootbeer.RootBeerNative：checkForRoot 固定返回 0，setLogDebugMessages 返回 true，覆盖 VMP 绕过 jg.c 直接调 native 的路径"
    ),
    HookGroup(
        "detect_jvmti",
        "JDWP 开关",
        "com.kwad.sdk.crash.utils.JvmtiHelper：enableJdwp / disableJdwp / avoidDeoptForReleaseBuild / unloadAgent 全部置空，SDK 无法读取或改写调试端口状态"
    ),
    HookGroup(
        "detect_system_properties",
        "系统属性",
        "android.os.SystemProperties.get：ro.kernel.qemu→空、ro.secure→1、ro.debuggable→0、ro.build.tags→release-keys，其它属性原样放行"
    ),
    HookGroup(
        "detect_cheat_blacklist",
        "服务端黑名单拆除",
        "BeforeRunFragment.Y2 执行前把 BeforeRunResponse.android_cheats 置空：服务端下发多少包名都不再触发「手机系统环境异常」。时段校验（I3）保留"
    ),
    HookGroup(
        "detect_device_spoof",
        "设备身份伪装",
        "仅在检测到模拟器环境时才把 Build 字段与 ro.* 属性改写成三星 S20 指纹；普通手机保留真实指纹不写假值，避免与 native 侧属性交叉校验产生不一致点"
    ),
    HookGroup(
        "detect_maps_filter",
        "maps 扫描过滤",
        "Java 层读 /proc/<pid>/maps 的行级过滤：含 dexposed、frida、XposedBridge、lsposed、magisk 等 token 的映射行被抹掉后喂给 FileInputStream/FileReader。覆盖 i.java 与 kwad s\$e；壳层 native 黑名单本身不含 LSPosed"
    ),
    HookGroup(
        "block_ad_screens",
        "广告屏拦截",
        "startActivity 目标为广告 SDK 界面（kwad / 字节 Pangle / 益方 / 倍孜）时直接取消：插屏、激励视频、开屏页不再弹出；只拦界面拉起，底层广告请求原样保留"
    ),
    HookGroup(
        "force_close_ads",
        "关内置广告位",
        "RunApplication.getInstance 返回后把 6 个 close*Ad 强制开、isShowAd 强制关：WebView 底部广告容器与开屏/结算页 NativeAd 全部走「不显示」分支；与广告屏拦截互补"
    ),
    HookGroup(
        "session_inject",
        "账号会话注入",
        "把页面下方填的 user_id + token 直接写进乐跑 MMKV 登录态（等价已登录，跳过微信/小程序授权）。改内容后重启乐跑生效；留空则不注入"
    )
)

private val HOOK_GROUPS = listOf(    HookGroup(
        "connectivity",
        "网络状态层 hook",
        "接管 getActiveNetworkInfo / NetworkCapabilities。关掉后乐跑看到真实网络状态"
    ),
    HookGroup(
        "cell",
        "基站环境 hook",
        "接管 TelephonyManager 的运营商 / 信号 / 小区信息。乐跑走高德定位，通常用不到"
    ),
    HookGroup(
        "bluetooth",
        "蓝牙环境 hook",
        "接管 BluetoothLeScanner 扫描结果。乐跑不使用蓝牙定位"
    ),
    HookGroup(
        "sensor_step",
        "计步传感器 hook",
        "接管 TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR。关掉后乐跑拿不到注入的步数"
    )
)

@Composable
fun LepaoConfigScreen(
    uiState: AppState,
    isDark: Boolean = isSystemInDarkTheme(),
    isGroupEnabled: (String) -> Boolean,
    onToggleGroup: (String, Boolean) -> Unit,
    lepaoSession: String = "",
    onSaveLepaoSession: (String) -> Unit,
    lepaoReportVersionCode: String = "",
    lepaoReportVersionName: String = "",
    onSaveLepaoReportVersion: (String, String) -> Unit = { _, _ -> },
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    // 输入草稿与已保存值分离：键盘每敲一下都发布一次框架配置会怗号，这里保存时统一提交
    var versionCodeDraft by remember { mutableStateOf(lepaoReportVersionCode) }
    var versionNameDraft by remember { mutableStateOf(lepaoReportVersionName) }
    LaunchedEffect(lepaoReportVersionCode, lepaoReportVersionName) {
        versionCodeDraft = lepaoReportVersionCode
        versionNameDraft = lepaoReportVersionName
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF22272E) else Color.White)
                        .border(1.dp, AppColors.surface(isDark), CircleShape)
                        .noRippleClickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "乐跑配置",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        TANKE_PKG,
                        fontSize = 11.sp,
                        color = AppColors.textSecondary(isDark)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                // 说明卡片
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Rounded.DirectionsRun,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "这些开关只影响乐跑进程里安装的环境类 hook。减少用不到的 hook 可以缩小被环境检测扫描到的面；" +
                                "定位伪造本身不受影响。改动后需重启乐跑生效。",
                            fontSize = 12.sp,
                            color = AppColors.textSecondary(isDark),
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "版本上报",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.textSecondary(isDark)
                )
                Spacer(Modifier.height(8.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "留空 = 按服务端当前最低版本（233 / 4.3.3）上报；已安装更高版本时保持真实值。" +
                                "填了才用自定义值替换 version / version_name；不要填不可能存在的高版本。",
                            fontSize = 12.sp,
                            color = AppColors.textSecondary(isDark),
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        ModernSettingsInput(
                            label = "versionCode",
                            value = versionCodeDraft,
                            onValueChange = { versionCodeDraft = it },
                            placeholder = "留空 = 真实版本",
                            isDark = isDark
                        )
                        Spacer(Modifier.height(8.dp))
                        ModernSettingsInput(
                            label = "versionName",
                            value = versionNameDraft,
                            onValueChange = { versionNameDraft = it },
                            placeholder = "留空 = 真实版本",
                            isDark = isDark
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { onSaveLepaoReportVersion(versionCodeDraft, versionNameDraft) }
                            ) {
                                Text("保存版本上报")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "环境 hook 分组",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.textSecondary(isDark)
                )
                Spacer(Modifier.height(8.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
                ) {
                    Column {
                        HOOK_GROUPS.forEachIndexed { index, group ->
                            val enabled = isGroupEnabled(group.key)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        group.title,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        group.subtitle,
                                        fontSize = 11.sp,
                                        color = AppColors.textSecondary(isDark),
                                        lineHeight = 15.sp
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { onToggleGroup(group.key, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = AccentBlue
                                    )
                                )
                            }
                            if (index != HOOK_GROUPS.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    color = AppColors.surface(isDark)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 检测层接管：每一项一个开关，对应 Xposed 端的 detect_* hook
                Text(
                    "检测层接管开关",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.textSecondary(isDark)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "打开后对应检测函数被改写成固定返回 false；关闭则恢复乐跑原逻辑。改动后重启乐跑生效。",
                    fontSize = 11.sp,
                    color = AppColors.textSecondary(isDark)
                )
                Spacer(Modifier.height(8.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
                ) {
                    Column {
                        DETECTION_ITEMS.forEachIndexed { index, item ->
                            val enabled = isGroupEnabled(item.key)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        item.subtitle,
                                        fontSize = 11.sp,
                                        color = AppColors.textSecondary(isDark),
                                        lineHeight = 15.sp
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { onToggleGroup(item.key, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = AccentBlue
                                    )
                                )
                            }
                            if (index != DETECTION_ITEMS.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    color = AppColors.surface(isDark)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 检测层参考：只读，逐层列出乐跑/壳在查什么、实测结果如何
                Text(
                    "乐跑的检测层（只读）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.textSecondary(isDark)
                )
                Spacer(Modifier.height(8.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
                ) {
                    Column {
                        DETECTION_LAYERS.forEachIndexed { index, layer ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        layer.title,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        layer.status,
                                        fontSize = 10.sp,
                                        color = if (layer.hit) Color(0xFFCF222E) else Color(0xFF2EA043)
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    layer.how,
                                    fontSize = 11.sp,
                                    color = AppColors.textSecondary(isDark),
                                    lineHeight = 15.sp
                                )
                            }
                            if (index != DETECTION_LAYERS.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    color = AppColors.surface(isDark)
                                )
                            }
                        }
                    }
                }

                // 账号会话注入：单账号 id + token 写进乐跑 MMKV，跳过微信登录
                Text(
                    "账号会话注入（单账号 id 登录）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.textSecondary(isDark)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "填入账号 user_id 与 access_token，点「写入库」后随下次发布配置下发；乐跑启动时自动写入登录态，跳过微信/小程序授权。",
                    fontSize = 11.sp,
                    color = AppColors.textSecondary(isDark)
                )
                Spacer(Modifier.height(8.dp))

                var sessionJson by remember(lepaoSession) { mutableStateOf(lepaoSession) }
                var readingSession by remember { mutableStateOf(false) }
                var sessionReadFailure by remember {
                    mutableStateOf<com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure?>(null)
                }
                val sessionScope = rememberCoroutineScope()
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = sessionJson,
                            onValueChange = { sessionJson = it },
                            label = { Text("会话 JSON（{" +
                                "\"user_id\":\"123\",\"token\":\"abc...\",\"role\":\"0\"}，可粘 PC 端页面生成的那份）", fontSize = 11.sp) },
                            minLines = 2,
                            maxLines = 4,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                onSaveLepaoSession(sessionJson.trim())
                            }) {
                                Text("写入库", fontSize = 13.sp, color = AccentBlue)
                            }
                            TextButton(
                                enabled = !readingSession,
                                onClick = {
                                    readingSession = true
                                    sessionReadFailure = null
                                    sessionScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            com.kdzw8p.mqa.v71f.utils.LepaoUserReader.readSessionResult()
                                        }
                                        val json = result.sessionJson
                                        if (json != null) {
                                            sessionJson = json
                                            onSaveLepaoSession(json)
                                        }
                                        sessionReadFailure = result.failure
                                        readingSession = false
                                    }
                                }
                            ) {
                                Text(
                                    if (readingSession) "读取中…" else "从乐跑直接读取",
                                    fontSize = 13.sp,
                                    color = AccentBlue
                                )
                            }
                        }
                        sessionReadFailure?.let { failure ->
                            Text(
                                when (failure) {
                                    com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.ROOT_UNAVAILABLE ->
                                        "没有拿到 root，无法读取乐跑文件"
                                    com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.FILE_UNREADABLE ->
                                        "读不到乐跑数据文件，先打开一次乐跑再试"
                                    com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.TIMEOUT -> "读取超时，请重试"
                                    com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.CHANGING -> "乐跑正在写数据，稍后重试"
                                    com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.NO_PROFILE -> "乐跑里还没有登录态"
                                    com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.INVALID_FORMAT ->
                                        "乐跑数据格式不认识，可能版本不同"
                                },
                                fontSize = 11.sp,
                                color = Color(0xFFCF222E)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 使用说明：逐项开关的用法说明
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = AppColors.cardBackground(isDark)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "使用说明",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "检测层接管按项开关：打开后对应检测函数被 hook 成固定返回 false，关掉则恢复原逻辑。"
                                + "改动后需重启乐跑进程才生效（hook 在进程加载时安装）。"
                                + "建议配合上方只读检测层逐项对照：逐项打开、确认每一项实际命中什么再决定是否常开。",
                            fontSize = 11.sp,
                            color = AppColors.textSecondary(isDark),
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "当前模拟状态：" + if (uiState.isSpoofingActive) "运行中" else "未开始",
                    fontSize = 11.sp,
                    color = AppColors.textSecondary(isDark)
                )
            }
        }
    }
}
