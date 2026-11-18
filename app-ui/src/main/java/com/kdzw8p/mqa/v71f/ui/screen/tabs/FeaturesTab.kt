package com.kdzw8p.mqa.v71f.ui.screen.tabs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kdzw8p.mqa.v71f.ui.BuildConfig
import com.kdzw8p.mqa.v71f.ui.R
import com.kdzw8p.mqa.v71f.data.model.AppState
import com.kdzw8p.mqa.v71f.ui.screen.AppCoordinateConfigCard
import com.kdzw8p.mqa.v71f.ui.screen.FooterLinks
import com.kdzw8p.mqa.v71f.ui.screen.ImportExportDataCard
import com.kdzw8p.mqa.v71f.ui.screen.ManageDataCard
import com.kdzw8p.mqa.v71f.ui.screen.ScannerMapCard
import com.kdzw8p.mqa.v71f.ui.screen.SystemHookAppsConfigCard
import com.kdzw8p.mqa.v71f.ui.components.ImportExportSelectionDialog
import com.kdzw8p.mqa.v71f.data.model.ImportExportCounts
import com.kdzw8p.mqa.v71f.data.model.ImportExportSelection
import com.kdzw8p.mqa.v71f.ui.theme.AccentBlue
import com.kdzw8p.mqa.v71f.ui.theme.AppColors
import com.kdzw8p.mqa.v71f.ui.theme.noRippleClickable
import com.kdzw8p.mqa.v71f.ui.components.RemoteAvatar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.kdzw8p.mqa.v71f.utils.WeChatQrLogin
import com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin
import com.kdzw8p.mqa.v71f.utils.LepaoRunHistory
import com.kdzw8p.mqa.v71f.utils.LepaoSchoolDirectory
import com.kdzw8p.mqa.v71f.viewmodel.lepaoActiveAccountName
import com.kdzw8p.mqa.v71f.viewmodel.refreshLepaoProfileStats
import com.kdzw8p.mqa.v71f.viewmodel.switchLepaoAccount
import com.kdzw8p.mqa.v71f.viewmodel.cachedLepaoRunRecords
import com.kdzw8p.mqa.v71f.viewmodel.cachedLepaoSchools
import com.kdzw8p.mqa.v71f.viewmodel.importWxAndLogin
import com.kdzw8p.mqa.v71f.viewmodel.directLoginLepao
import com.kdzw8p.mqa.v71f.viewmodel.exchangeWeChatCode
import kotlinx.coroutines.launch
import com.kdzw8p.mqa.v71f.viewmodel.readLepaoSessionCapture
import com.kdzw8p.mqa.v71f.viewmodel.readLepaoWxAuthCapture
import com.kdzw8p.mqa.v71f.viewmodel.refreshWeChatToken
import com.kdzw8p.mqa.v71f.viewmodel.fetchLepaoRunHistory
import com.kdzw8p.mqa.v71f.viewmodel.fetchLepaoRunDetail
import com.kdzw8p.mqa.v71f.viewmodel.fetchLepaoSchools
import com.kdzw8p.mqa.v71f.viewmodel.saveLepaoSession
import com.kdzw8p.mqa.v71f.viewmodel.getLastLepaoSchoolId
import com.kdzw8p.mqa.v71f.viewmodel.exportLepaoBackup
import com.kdzw8p.mqa.v71f.viewmodel.importLepaoBackup
import com.kdzw8p.mqa.v71f.viewmodel.rememberLepaoSchoolId
import com.kdzw8p.mqa.v71f.viewmodel.setLepaoProfileFace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kdzw8p.mqa.v71f.viewmodel.MainViewModel
import com.kdzw8p.mqa.v71f.viewmodel.applyImportPackage
import com.kdzw8p.mqa.v71f.viewmodel.collectExportCounts
import com.kdzw8p.mqa.v71f.viewmodel.exportEnvironmentData
import com.kdzw8p.mqa.v71f.viewmodel.parseImportPackage
import com.kdzw8p.mqa.v71f.viewmodel.lepaoProfileRows
import com.kdzw8p.mqa.v71f.viewmodel.runLepaoAutomation
import com.kdzw8p.mqa.v71f.viewmodel.getLepaoRunKmMale
import com.kdzw8p.mqa.v71f.viewmodel.getLepaoRunKmFemale
import com.kdzw8p.mqa.v71f.viewmodel.getLepaoMinPaceMale
import com.kdzw8p.mqa.v71f.viewmodel.getLepaoMinPaceFemale
import com.kdzw8p.mqa.v71f.viewmodel.setLepaoMinPace
import com.kdzw8p.mqa.v71f.viewmodel.setLepaoRunKm
import com.kdzw8p.mqa.v71f.ui.screen.settings.LepaoAutomationConfirm
import com.kdzw8p.mqa.v71f.viewmodel.acceptLepaoUserExport
import com.kdzw8p.mqa.v71f.viewmodel.readDeviceLepaoUser
import com.kdzw8p.mqa.v71f.viewmodel.importDeviceLepaoUser

import com.kdzw8p.mqa.v71f.viewmodel.applyLepaoProfile
import com.kdzw8p.mqa.v71f.viewmodel.deleteLepaoProfile
import com.kdzw8p.mqa.v71f.viewmodel.saveLepaoProfile
import com.kdzw8p.mqa.v71f.viewmodel.patchLepaoProfile
import com.kdzw8p.mqa.v71f.viewmodel.LepaoProfileRow

@Composable
fun FeaturesTab(
    viewModel: MainViewModel,
    uiState: AppState,
    tabBarHeight: Dp = 90.dp,
    onNavigateToCoordinate: () -> Unit,
    onNavigateToScanner: () -> Unit,
    onNavigateToManageData: () -> Unit,
    onNavigateToSystemHookApps: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current

    // 多用户表格：列表存可观察状态，增删改后刷新
    var userRows by remember { mutableStateOf<List<LepaoProfileRow>>(viewModel.lepaoProfileRows()) }
    // 切换进行中的账号名：非空时所有切换按钮禁用，目标行显示 spinner
    var switchingName by remember { mutableStateOf<String?>(null) }
    // 切换/登录/重登滚动日志（LepaoBridge 200 行）
    var bridgeLog by remember { mutableStateOf("") }
    // 档案和宿主回执均可能在页面之外更新；监听持久化结果而不是只等按钮回调。
    DisposableEffect(viewModel, context) {
        val profiles = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val bridge = context.getSharedPreferences("lepao_bridge", android.content.Context.MODE_PRIVATE)
        val profileListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "lepao_profiles" || key == "lepao_active_account") userRows = viewModel.lepaoProfileRows()
        }
        val bridgeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "json") bridge.getString("json", null)?.let { viewModel.acceptLepaoUserExport(it) }
        }
        profiles.registerOnSharedPreferenceChangeListener(profileListener)
        bridge.registerOnSharedPreferenceChangeListener(bridgeListener)
        onDispose {
            profiles.unregisterOnSharedPreferenceChangeListener(profileListener)
            bridge.unregisterOnSharedPreferenceChangeListener(bridgeListener)
        }
    }
    // ViewModel 主动刷新时同步日志和当前标记。
    val uiStateForRows by viewModel.uiState.collectAsState()
    LaunchedEffect(uiStateForRows.lepaoProfilesVersion) {
        userRows = viewModel.lepaoProfileRows()
        bridgeLog = com.kdzw8p.mqa.v71f.utils.LepaoBridge.log(context)
    }
    LaunchedEffect(Unit) {
        bridgeLog = com.kdzw8p.mqa.v71f.utils.LepaoBridge.log(context)
    }
    var editingRow by remember { mutableStateOf<com.kdzw8p.mqa.v71f.viewmodel.LepaoProfileRow?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    var remarkAccount by remember { mutableStateOf<LepaoProfileRow?>(null) }
    var remarkInput by remember { mutableStateOf("") }
    remarkAccount?.let { row ->
        AlertDialog(
            onDismissRequest = { remarkAccount = null },
            containerColor = AppColors.cardBackground(isDark),
            title = { Text(stringResource(R.string.lepao_remark_title)) },
            text = {
                Column {
                    Text(row.name, fontSize = 12.sp)
                    OutlinedTextField(
                        value = remarkInput, onValueChange = { remarkInput = it },
                        label = { Text(stringResource(R.string.lepao_remark_label)) },
                        modifier = Modifier.fillMaxWidth(), maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.patchLepaoProfile(row.name, remark = remarkInput.trim())
                    userRows = viewModel.lepaoProfileRows()
                    remarkAccount = null
                }) { Text(stringResource(R.string.lepao_remark_save)) }
            },
            dismissButton = { TextButton(onClick = { remarkAccount = null }) { Text(stringResource(R.string.lepao_remark_cancel)) } }
        )
    }
    // 用户表格行内设置拍照/扫脸图：记录目标账号，选图后按账号保存
    var facePickTarget by remember { mutableStateOf<String?>(null) }
    val faceScope = rememberCoroutineScope()
    val faceContext = LocalContext.current
    val facePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = facePickTarget
        facePickTarget = null
        if (uri != null && target != null) {
            faceScope.launch {
                val encoded = withContext(Dispatchers.IO) {
                    runCatching { encodeFacePhoto(faceContext, uri) }.getOrNull()
                }
                if (encoded != null) {
                    viewModel.setLepaoProfileFace(target, encoded)
                    userRows = viewModel.lepaoProfileRows()
                } else {
                    Toast.makeText(faceContext, "图片读取失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 乐跑档案全量备份（profiles + accounts + 当前会话），与账号导入共用同一个设置存储
    val backupScope = rememberCoroutineScope()
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        backupScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = viewModel.exportLepaoBackup()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    true
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) "乐跑档案已导出" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        backupScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() } ?: return@runCatching false
                    if (text.length > 2_000_000) return@runCatching false
                    viewModel.importLepaoBackup(text)
                }.getOrDefault(false)
            }
            userRows = viewModel.lepaoProfileRows()
            Toast.makeText(
                context,
                if (ok) "乐跑档案已导入" else "导入失败：不是本工具的备份文件",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 导出：先让用户选内容，再拉起系统选择器（SAF 必须在拉起前就知道要写什么）
    var exportCounts by remember { mutableStateOf<ImportExportCounts?>(null) }
    var exportSelection by remember { mutableStateOf(ImportExportSelection()) }
    // 导入：先解析出文件里有什么，再让用户勾选要应用的部分
    var pendingImportPackage by remember {
        mutableStateOf<com.kdzw8p.mqa.v71f.data.db.R7VqzNDataPackage?>(null)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            viewModel.exportEnvironmentData(it, exportSelection) { success ->
                Toast.makeText(
                    context,
                    context.getString(if (success) R.string.export_success else R.string.export_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.parseImportPackage(it) { pkg ->
                if (pkg == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_parse_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    pendingImportPackage = pkg
                }
            }
        }
    }

    exportCounts?.let { counts ->
        ImportExportSelectionDialog(
            isExport = true,
            counts = counts,
            onConfirm = { selection ->
                exportSelection = selection
                exportCounts = null
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                exportLauncher.launch("environment_data_$stamp.json")
            },
            onDismiss = { exportCounts = null }
        )
    }

    pendingImportPackage?.let { pkg ->
        ImportExportSelectionDialog(
            isExport = false,
            counts = ImportExportCounts(
                locations = pkg.locations.size + pkg.collectionRoutes.size,
                savedLocations = pkg.savedLocations.size,
                savedRoutes = pkg.savedRoutes.size,
                appCoordinateSystems = pkg.appCoordinateSystems.size,
                settings = if (pkg.settings != null) 1 else 0,
                apiKeys = if (pkg.apiKeys != null) 1 else 0
            ),
            onConfirm = { selection ->
                pendingImportPackage = null
                viewModel.applyImportPackage(pkg, selection) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_merge_success),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { pendingImportPackage = null }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AccentBlue.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Extension, null, tint = AccentBlue)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.features_tab_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.features_tab_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = tabBarHeight + 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 0. 乐跑多用户管理（表格）
                item {
                    Column {
                        // 设备用户读取入口
                        var deviceUser by remember { mutableStateOf<com.kdzw8p.mqa.v71f.utils.LepaoUserReader.LepaoUser?>(null) }
                        var showDeviceDialog by remember { mutableStateOf(false) }
                        var deviceReadBusy by remember { mutableStateOf(false) }
                        var deviceReadFailure by remember { mutableStateOf<com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure?>(null) }
                        var importName by remember { mutableStateOf("") }
                        val bridgeScope = rememberCoroutineScope()

                        fun readDeviceUser(showDialog: Boolean = true) {
                            if (deviceReadBusy) return
                            deviceReadBusy = true
                            deviceUser = null
                            deviceReadFailure = null
                            if (showDialog) showDeviceDialog = true
                            context.sendBroadcast(
                                android.content.Intent("com.kdzw8p.mqa.v71f.REQUEST_USER")
                                    .setPackage("com.lptiyu.tanke")
                            )
                            bridgeScope.launch {
                                kotlinx.coroutines.delay(800)
                                viewModel.readDeviceLepaoUser { result ->
                                    deviceReadBusy = false
                                    deviceUser = result.user
                                    deviceReadFailure = result.failure
                                    importName = result.user?.let { current -> current.nickname.ifBlank { current.userId } }.orEmpty()
                                    result.user?.let { viewModel.rememberLepaoSchoolId(it.schoolId) }
                                    userRows = viewModel.lepaoProfileRows()
                                }
                            }
                        }
                        var listRefreshBusy by remember { mutableStateOf(false) }
                        var runConfirm by remember { mutableStateOf<LepaoAutomationConfirm?>(null) }
                        fun refreshAccountList() {
                            if (listRefreshBusy) return
                            listRefreshBusy = true
                            bridgeScope.launch {
                                try {
                                    kotlinx.coroutines.coroutineScope {
                                        viewModel.lepaoProfileRows().filter { it.hasSession }.forEach { account ->
                                            launch {
                                                viewModel.refreshLepaoProfileStats(account.name).join()
                                                userRows = viewModel.lepaoProfileRows()
                                            }
                                        }
                                    }
                                } finally {
                                    listRefreshBusy = false
                                }
                            }
                        }
                        LaunchedEffect(Unit) {
                            readDeviceUser(showDialog = false)
                            refreshAccountList()
                        }
                        // 一键/批量跑：每跑完一个账号刷新该账号，全部结束再刷整张表
                        val automationState by com.kdzw8p.mqa.v71f.viewmodel.lepaoAutomationState.collectAsState()
                        LaunchedEffect(automationState?.finished, automationState?.index) {
                            val st = automationState ?: return@LaunchedEffect
                            if (st.finished) refreshAccountList()
                            else if (st.account.isNotBlank()) viewModel.refreshLepaoProfileStats(st.account)
                        }
                        com.kdzw8p.mqa.v71f.ui.screen.settings.LepaoAccountList(
                            rows = userRows,
                            isDark = isDark,
                            activeName = viewModel.lepaoActiveAccountName().takeIf { it.isNotBlank() }
                                ?: ((uiStateForRows.deviceUser ?: deviceUser)?.let { it.nickname.ifBlank { it.userId } }).orEmpty(),
                            onSwitch = { name ->
                                if (switchingName == null) {
                                    switchingName = name
                                    viewModel.switchLepaoAccount(name) { msg ->
                                        switchingName = null
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            switchingName = switchingName.orEmpty(),
                            onShowDetail = { row -> editingRow = row },
                            onDelete = { name ->
                                viewModel.deleteLepaoProfile(name)
                                userRows = viewModel.lepaoProfileRows()
                            },
                            onAdd = { showNewDialog = true },
                            onRefresh = { refreshAccountList() },
                            onEditRemark = { row -> remarkInput = row.remark; remarkAccount = row },
                            onRunAccount = { name ->
                                runConfirm = LepaoAutomationConfirm(
                                    listOf(name), viewModel.getLepaoRunKmMale(), viewModel.getLepaoRunKmFemale(),
                                    paceMale = viewModel.getLepaoMinPaceMale(),
                                    paceFemale = viewModel.getLepaoMinPaceFemale()
                                )
                            },
                            onRunAll = {
                                val rows = viewModel.lepaoProfileRows()
                                val names = rows.filter { it.hasSession }.map { it.name }
                                if (names.isNotEmpty()) {
                                    // 今日已跑次数按当天记录统计；null（未确认）当 0 处理，仍然跑。
                                    val today = java.text.SimpleDateFormat(
                                        "yyyy-MM-dd", java.util.Locale.US
                                    ).format(java.util.Date())
                                    runConfirm = LepaoAutomationConfirm(
                                        names,
                                        viewModel.getLepaoRunKmMale(),
                                        viewModel.getLepaoRunKmFemale(),
                                        rows.associate { it.name to (it.runsOn(today) ?: -1) },
                                        paceMale = viewModel.getLepaoMinPaceMale(),
                                        paceFemale = viewModel.getLepaoMinPaceFemale()
                                    )
                                }
                            },
                            refreshing = listRefreshBusy
                        )

                        com.kdzw8p.mqa.v71f.ui.screen.settings.LepaoAutomationDialogs(
                            confirm = runConfirm,
                            onDismissConfirm = { runConfirm = null },
                            onStart = { names, male, female, paceMale, paceFemale ->
                                runConfirm = null
                                viewModel.setLepaoRunKm(male.toString(), female.toString())
                                viewModel.setLepaoMinPace(paceMale.toString(), paceFemale.toString())
                                viewModel.runLepaoAutomation(names, male, female)
                            }
                        )

                        Row {
                            TextButton(onClick = {
                                backupImportLauncher.launch(arrayOf("application/json", "text/plain"))
                            }) { Text("导入乐跑档案", fontSize = 12.sp) }
                            TextButton(onClick = {
                                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                    .format(java.util.Date())
                                backupExportLauncher.launch("lepao_backup_$stamp.json")
                            }) { Text("导出乐跑档案", fontSize = 12.sp) }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 切换/登录日志：[切换]/[重登]/[切前验] 与乐跑回执都在这，免 adb 查
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "切换 / 登录日志",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        bridgeLog = com.kdzw8p.mqa.v71f.utils.LepaoBridge.log(context)
                                    }) { Text("刷新", fontSize = 12.sp) }
                                    TextButton(onClick = {
                                        com.kdzw8p.mqa.v71f.utils.LepaoBridge.clearLog(context)
                                        bridgeLog = ""
                                    }) { Text("清空", fontSize = 12.sp) }
                                }
                                if (bridgeLog.isBlank()) {
                                    Text(
                                        "暂无日志。切换、导入、自动重登的每一步会记在这里。",
                                        fontSize = 10.sp,
                                        color = AppColors.textSecondary(isDark)
                                    )
                                } else {
                                    Text(
                                        bridgeLog,
                                        fontSize = 9.sp,
                                        lineHeight = 13.sp,
                                        color = AppColors.textSecondary(isDark),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 220.dp)
                                            .verticalScroll(rememberScrollState())
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 读取设备当前乐跑用户
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    "当前设备乐跑用户",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(com.kdzw8p.mqa.v71f.ui.R.string.lepao_device_snapshot_warning),
                                    fontSize = 10.sp,
                                    color = AppColors.textSecondary(isDark)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(com.kdzw8p.mqa.v71f.ui.R.string.lepao_read_local_snapshot),
                                    fontSize = 12.sp,
                                    color = AccentBlue,
                                    modifier = Modifier.noRippleClickable { readDeviceUser() }
                                )
                            }
                        }

                        if (showDeviceDialog) {
                            AlertDialog(
                                onDismissRequest = { showDeviceDialog = false },
                                containerColor = AppColors.cardBackground(isDark),
                                title = { Text("设备乐跑用户", fontSize = 15.sp) },
                                text = {
                                    val u = deviceUser
                                    if (u == null) {
                                        Text(
                                            stringResource(when {
                                                deviceReadBusy -> R.string.lepao_read_busy
                                                deviceReadFailure == com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.ROOT_UNAVAILABLE -> R.string.lepao_read_root_failed
                                                deviceReadFailure == com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.FILE_UNREADABLE -> R.string.lepao_read_file_failed
                                                deviceReadFailure == com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.TIMEOUT -> R.string.lepao_read_timeout
                                                deviceReadFailure == com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.CHANGING -> R.string.lepao_read_changing
                                                deviceReadFailure == com.kdzw8p.mqa.v71f.utils.LepaoUserReader.Failure.NO_PROFILE -> R.string.lepao_read_no_profile
                                                else -> R.string.lepao_read_format_failed
                                            }),
                                            fontSize = 11.sp,
                                            color = AppColors.textSecondary(isDark)
                                        )
                                    } else {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RemoteAvatar(
                                                    url = u.avatarUrl,
                                                    fallbackText = u.nickname.ifBlank { u.userId },
                                                    size = 52.dp
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Column {
                                                    Text(u.nickname.ifBlank { "（未设置昵称）" }, fontSize = 14.sp)
                                                    if (u.venueName.isNotBlank() || u.venueId > 0L) {
                                                        Text(
                                                            "场地：" + u.venueName.ifBlank { "#${u.venueId}" },
                                                            fontSize = 10.sp,
                                                            color = AppColors.textSecondary(isDark)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            InfoRow("用户 ID", u.userId, isDark)
                                            InfoRow("昵称", u.nickname, isDark)
                                            InfoRow("手机", u.phone, isDark)
                                            InfoRow("学校", u.school, isDark)
                                            InfoRow("学号", u.studentNo, isDark)
                                            InfoRow("角色", roleLabel(u.role), isDark)
                                            InfoRow(
                                                "跑步次数",
                                                if (u.runCount > 0 || u.totalRunCount > 0) {
                                                    if (u.totalRunCount > 0) "${u.runCount} / ${u.totalRunCount}" else u.runCount.toString()
                                                } else "—",
                                                isDark
                                            )
                                            InfoRow(
                                                "累计里程",
                                                if (u.totalDistanceM > 0.0) String.format(java.util.Locale.US, "%.2f km", u.totalDistanceM / 1000.0) else "—",
                                                isDark
                                            )
                                            if (u.runScore.isNotBlank()) InfoRow("学分", u.runScore, isDark)
                                            InfoRow("当前场地", u.venueName.ifBlank { if (u.venueId > 0L) "场地 #${u.venueId}" else "—" }, isDark)
                                            if (u.runCount == 0 && u.totalDistanceM == 0.0) {
                                                Text(
                                                    "未拿到服务端统计：先打开一次乐跑刷新登录态，再回到这里重新读取",
                                                    fontSize = 10.sp,
                                                    color = AppColors.textSecondary(isDark)
                                                )
                                            }
                                            if (u.faceImage.isNotBlank()) InfoRow("人脸照片", "已登记", isDark)
                                            Text(stringResource(R.string.lepao_read_profile_only), fontSize = 11.sp)
                                            InfoRow(stringResource(R.string.lepao_read_captured_at), java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(u.capturedAt)), isDark)
                                            Spacer(Modifier.height(10.dp))
                                            OutlinedTextField(
                                                value = importName,
                                                onValueChange = { importName = it },
                                                label = { Text("导入为（名称）", fontSize = 11.sp) },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(enabled = !deviceReadBusy && deviceUser != null, onClick = {
                                        deviceUser?.let { current ->
                                            viewModel.importDeviceLepaoUser(importName.trim(), current)
                                            userRows = viewModel.lepaoProfileRows()
                                        }
                                        showDeviceDialog = false
                                    }) { Text("导入为多用户") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeviceDialog = false }) { Text("关闭") }
                                }
                            )
                        }
                    }
                }

                // 2. 微信 / 学校直登
                item {
                    DirectLoginCard(viewModel, isDark)
                }

                // 3. 历史跑场（Run2/allRecordList）
                item {
                    RunHistoryCard(viewModel, isDark)
                }

                // 4. 采集本地数据
                item {
                    ScannerMapCard(
                        uiState = uiState,
                        isDark = isDark,
                        onClick = onNavigateToScanner
                    )
                }

                // 2. 管理本地数据
                item {
                    ManageDataCard(
                        isDark = isDark,
                        onClick = onNavigateToManageData
                    )
                }

                // 3. 配置应用坐标系
                item {
                    AppCoordinateConfigCard(
                        isDark = isDark,
                        onClick = onNavigateToCoordinate
                    )
                }

                // 4. 系统级模拟（全局 / 指定应用），仅全局方案
                if (BuildConfig.GLOBAL_SCHEME) {
                    item {
                        SystemHookAppsConfigCard(
                            uiState = uiState,
                            isDark = isDark,
                            onClick = onNavigateToSystemHookApps
                        )
                    }
                }

                // 5. 导入与导出数据
                item {
                    ImportExportDataCard(
                        isDark = isDark,
                        onImportClick = {
                            importLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        onExportClick = {
                            // 先查各分类数量，弹出选择对话框；实际拉起 SAF 在对话框确认之后。
                            // 文件名在那里再带上时间戳，保证每次导出都是新文件名：
                            // 固定用 environment_data.json 时第二次导出必然撞名，而部分 ROM 的
                            // DocumentsProvider 处理重名的方式是另建一个 xxx_1.json 空占位文件、
                            // 却把返回的 Uri 指向原来那个旧文件，导致"旧备份被覆盖 + 多出 0B 空文件"（issue #57）。
                            viewModel.collectExportCounts { counts -> exportCounts = counts }
                        }
                    )
                }
            }
        }
    }

    // 切换进行中 loading 弹窗：从广播发出到回执到达，等待可见且挡住后续点击
    if (switchingName != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            containerColor = AppColors.cardBackground(isDark),
            title = { Text("切换账号", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = AccentBlue
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("正在写回「$switchingName」登录态，等乐跑回执…", fontSize = 12.sp)
                }
            },
            confirmButton = { }
        )
    }

    // 详情弹窗
    editingRow?.let { row ->
        val currentRow = userRows.firstOrNull { it.name == row.name } ?: row
        LaunchedEffect(row.name, row.session) {
            viewModel.refreshLepaoProfileStats(row.name).join()
        }
        com.kdzw8p.mqa.v71f.ui.screen.settings.LepaoAccountDetailDialog(
            row = currentRow,
            isDark = isDark,
            onDismiss = { editingRow = null },
            onSwitch = { name ->
                if (switchingName == null) {
                    editingRow = null
                    switchingName = name
                    viewModel.switchLepaoAccount(name) { msg ->
                        switchingName = null
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    // 新建用户弹窗（先建空用户，再到详情里编辑）
    if (showNewDialog) {
        var newName by remember { mutableStateOf("") }
        var newRemark by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            containerColor = AppColors.cardBackground(isDark),
            title = { Text("新建乐跑用户", fontSize = 15.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("名称", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newRemark,
                        onValueChange = { newRemark = it },
                        label = { Text("备注（可选）", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val nm = newName.trim()
                    if (nm.isNotBlank()) {
                        viewModel.saveLepaoProfile(nm, newRemark.trim())
                        userRows = viewModel.lepaoProfileRows()
                        showNewDialog = false
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 直登入口：把学校 app_token / 短信验证码 / 微信凭证换成乐跑 token，并写入会话注入 */
@Composable
private fun DirectLoginCard(viewModel: MainViewModel, isDark: Boolean) {
    var show by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.lepao_social_login_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.lepao_social_login_description),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = AppColors.textSecondary(isDark)
            )
            TextButton(onClick = { show = true }) { Text("打开直登", fontSize = 12.sp) }
        }
    }
    if (show) DirectLoginDialog(viewModel, isDark) { show = false }
}

private enum class DirectLoginMode(val label: String) {
    WECHAT("微信凭证"),
    QQ("QQ"),
    TOKEN("ID + Token"),
    APP_TOKEN("学校通道")
}

/** 生成二维码位图（登录会话 / 微信授权码共用） */
private fun qrCodeBitmap(content: String): android.graphics.Bitmap {
    val size = 640
    val matrix = com.google.zxing.qrcode.QRCodeWriter()
        .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bmp
}

@Composable
private fun DirectLoginDialog(viewModel: MainViewModel, isDark: Boolean, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(DirectLoginMode.WECHAT) }
    var schoolId by remember { mutableStateOf(viewModel.getLastLepaoSchoolId()) }
    var schoolName by remember { mutableStateOf("") }
    var showSchoolPicker by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var appToken by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf("") }
    var accessToken by remember { mutableStateOf("") }
    var tokenUid by remember { mutableStateOf("") }
    var tokenValue by remember { mutableStateOf("") }
    var tokenRefresh by remember { mutableStateOf("") }
    var tokenExpire by remember { mutableStateOf("") }
    var refreshToken by remember { mutableStateOf("") }
    var wxPayload by remember { mutableStateOf("") }
    var wxBusy by remember { mutableStateOf(false) }
    // 本次凭据已导入且回执 ok：确认按钮不再重复导入（重复导入会用已消费凭据再请求，踢掉当前登录态）
    var wxImportedOk by remember { mutableStateOf(false) }
    var showWxQr by remember { mutableStateOf(false) }
    var showWxMobileQr by remember { mutableStateOf(false) }
    var showBridgeLog by remember { mutableStateOf(false) }
    var bridgeLog by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var showQr by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val obj = org.json.JSONObject(text)
                val uid = obj.optString("uid").ifBlank { obj.optString("user_id") }
                val token = obj.optString("token").ifBlank { obj.optString("access_token") }
                tokenUid = uid
                tokenValue = token
                tokenRefresh = obj.optString("refresh_token")
                tokenExpire = obj.optString("refresh_expire")
                message = if (uid.isNotBlank() && token.isNotBlank()) {
                    "已导入凭证，点「写入会话」生效"
                } else {
                    "凭证里缺少 uid / token"
                }
            }.onFailure { message = "读取失败：${it.message}" }
        }
    }
    // 扫码即导入：授权码解析成功后自动发起导入+登录（用户要求 2026-10-24）
    val startWxImport: () -> Unit = {
        if (openId.isNotBlank() && accessToken.isNotBlank() && !wxBusy && !wxImportedOk) {
            wxBusy = true
            message = ""
            viewModel.importWxAndLogin(
                openid = openId.trim(),
                accessToken = accessToken.trim(),
                refreshToken = refreshToken.trim(),
                nick = "",
                avatar = "",
                schoolId = schoolId,
                onSuccess = {
                    wxImportedOk = true
                    wxBusy = false
                    showWxMobileQr = false
                    Toast.makeText(context, R.string.lepao_social_complete, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) { text, done ->
                message = text
                if (done) {
                    wxBusy = false
                }
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text.isNullOrBlank()) {
            message = "没有读到二维码内容"
        } else {
            val raw = if (text.startsWith("LS1:")) {
                runCatching {
                    String(android.util.Base64.decode(text.removePrefix("LS1:"), android.util.Base64.DEFAULT))
                }.getOrDefault(text)
            } else text
            if (mode == DirectLoginMode.WECHAT) {
                when (val outcome = LepaoDirectLogin.parseWeChatAuthPayload(raw)) {
                    is LepaoDirectLogin.WeChatCodeOutcome.Success -> {
                        openId = outcome.tokens.openId
                        accessToken = outcome.tokens.accessToken
                        refreshToken = outcome.tokens.refreshToken
                        wxImportedOk = false
                        message = "已导入微信授权码，自动开始导入并登录"
                        startWxImport()
                    }
                    is LepaoDirectLogin.WeChatCodeOutcome.Failure ->
                        message = "授权码解析失败：${outcome.message}"
                }
            } else {
                val obj = runCatching { org.json.JSONObject(raw) }.getOrNull()
                val uid = obj?.optString("user_id").orEmpty().ifBlank { obj?.optString("uid").orEmpty() }
                val token = obj?.optString("token").orEmpty()
                    .ifBlank { obj?.optString("access_token").orEmpty() }
                    .ifBlank { obj?.optString("user_access_token").orEmpty() }
                if (uid.isNotBlank() && token.isNotBlank()) {
                                tokenUid = uid
                                tokenValue = token
                                tokenRefresh = obj?.optString("refresh_token").orEmpty()
                                tokenExpire = obj?.optString("refresh_expire").orEmpty()
                                message = "已读取二维码，点「写入会话」生效"
                            } else {
                    message = "二维码里没有 user_id + token"
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy && !wxBusy) onDismiss() },
        containerColor = AppColors.cardBackground(isDark),
        title = { Text("乐跑直登", fontSize = 15.sp) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row {
                    DirectLoginMode.entries.forEach { key ->
                        FilterChip(
                            selected = mode == key,
                            enabled = !busy && !wxBusy,
                            onClick = { mode = key; message = "" },
                            label = { Text(if (key == DirectLoginMode.QQ) stringResource(R.string.lepao_qq_credentials) else key.label, fontSize = 10.sp) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (mode == DirectLoginMode.APP_TOKEN) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = schoolId,
                            onValueChange = { schoolId = it.filter(Char::isDigit) },
                            label = {
                                Text(
                                    if (schoolName.isBlank()) "school_id" else "$schoolName（$schoolId）",
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showSchoolPicker = true }) { Text("选择", fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                when (mode) {
                    DirectLoginMode.TOKEN -> {
                        OutlinedTextField(
                            value = tokenUid,
                            onValueChange = { tokenUid = it.filter(Char::isDigit) },
                            label = { Text("乐跑 user_id", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tokenValue,
                            onValueChange = { tokenValue = it.trim() },
                            label = { Text("access_token", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "把别人发来的 user_id + access_token 粘进来，直接写会话注入；这是唯一不依赖服务端登录接口的方式。" +
                                "只有 uid 没有 token 无法鉴权，服务端不认。",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = AppColors.textSecondary(isDark)
                        )
                        Row {
                            TextButton(onClick = {
                                val options = ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setPrompt("扫描乐跑登录二维码")
                                    setBeepEnabled(false)
                                    setOrientationLocked(false)
                                }
                                scanLauncher.launch(options)
                            }) { Text("扫码导入", fontSize = 12.sp, maxLines = 1) }
                            TextButton(
                                enabled = tokenUid.isNotBlank() && tokenValue.isNotBlank(),
                                onClick = { showQr = true }
                            ) { Text("生成二维码", fontSize = 12.sp, maxLines = 1) }
                        }
                        Row {
                            TextButton(onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            }) { Text("导入 lepao3 凭证.json", fontSize = 12.sp, maxLines = 1) }
                            TextButton(onClick = {
                                viewModel.readLepaoSessionCapture { capture ->
                                    if (capture == null) {
                                        message = "没有读取到登录态：先在乐跑里登录一次（会写 ls_token_capture.json）"
                                    } else {
                                        tokenUid = capture.uid
                                        tokenValue = capture.accessToken
                                        tokenRefresh = capture.refreshToken
                                        tokenExpire = capture.refreshExpire
                                        message = "已读取本机登录态，点「写入会话」生效"
                                    }
                                }
                            }) { Text("读取本机登录态", fontSize = 12.sp, maxLines = 1) }
                        }
                    }

                    DirectLoginMode.QQ -> {
                        QqLoginPanel(viewModel, schoolId, isDark) { wxBusy = it }
                    }

                    DirectLoginMode.APP_TOKEN -> {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it.filter(Char::isDigit) },
                            label = { Text("手机号（可选）", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = appToken,
                            onValueChange = { appToken = it.trim() },
                            label = { Text("学校 app_token（不是学号）", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "短信验证码通道服务端已停用：实测 Login/getCodeV300 返回「该功能已停用」，所以没有发送按钮。" +
                                "学校通道需要学校门户签发的 app_token；学号在资料里是 auth_num/user_num。",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = AppColors.textSecondary(isDark)
                        )
                    }

                    DirectLoginMode.WECHAT -> {
                        OutlinedTextField(
                            value = openId,
                            onValueChange = { openId = it.trim(); wxImportedOk = false },
                            label = { Text("微信 openid", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = accessToken,
                            onValueChange = { accessToken = it.trim(); wxImportedOk = false },
                            label = { Text("微信 accesstoken", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = refreshToken,
                                onValueChange = { refreshToken = it.trim() },
                                label = { Text("微信 refresh_token（可选，30 天续航）", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                enabled = !wxBusy && refreshToken.isNotBlank(),
                                onClick = {
                                    wxBusy = true
                                    message = ""
                                    viewModel.refreshWeChatToken(refreshToken) { outcome ->
                                        wxBusy = false
                                        when (outcome) {
                                            is LepaoDirectLogin.WeChatCodeOutcome.Success -> {
                                                openId = outcome.tokens.openId
                                                accessToken = outcome.tokens.accessToken
                                                refreshToken = outcome.tokens.refreshToken
                                                message = "微信凭证已刷新（access_token 2 小时内有效）"
                                            }
                                            is LepaoDirectLogin.WeChatCodeOutcome.Failure ->
                                                message = "刷新失败：${outcome.message}"
                                        }
                                    }
                                }
                            ) { Text(if (wxBusy) "刷新中…" else "刷新凭证", fontSize = 12.sp, maxLines = 1) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = wxPayload,
                                onValueChange = { wxPayload = it },
                                label = { Text("授权码（lepao-wx:// 或 JSON，可粘贴）", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                enabled = !wxBusy && wxPayload.isNotBlank(),
                                onClick = {
                                    when (val outcome = LepaoDirectLogin.parseWeChatAuthPayload(wxPayload)) {
                                        is LepaoDirectLogin.WeChatCodeOutcome.Success -> {
                                            openId = outcome.tokens.openId
                                            accessToken = outcome.tokens.accessToken
                                            refreshToken = outcome.tokens.refreshToken
                                            wxPayload = ""
                                            wxImportedOk = false
                                            startWxImport()
                                        }
                                        is LepaoDirectLogin.WeChatCodeOutcome.Failure ->
                                            message = "授权码解析失败：${outcome.message}"
                                    }
                                }
                            ) { Text("解析", fontSize = 12.sp) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val options = ScanOptions().apply {
                                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        setPrompt("扫描微信授权码")
                                        setBeepEnabled(false)
                                        setOrientationLocked(false)
                                    }
                                    scanLauncher.launch(options)
                                }
                            ) { Text("扫码导入", fontSize = 12.sp, maxLines = 1) }
                        }
                        Row {
                            TextButton(
                                modifier = Modifier.weight(1f),
                                enabled = openId.isNotBlank() && accessToken.isNotBlank(),
                                onClick = { showWxQr = true }
                            ) { Text("授权码二维码", fontSize = 12.sp, maxLines = 1) }
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { showWxMobileQr = true }) {
                            Text("微信扫码登录（移动应用，无需本机微信）", fontSize = 12.sp, maxLines = 1)
                        }
                        TextButton(
                            enabled = openId.isNotBlank() && accessToken.isNotBlank() && !wxBusy && !wxImportedOk,
                            onClick = { startWxImport() }
                        ) {
                            Text(if (wxBusy) "导入中…" else "一键导入并登录乐跑", fontSize = 12.sp, maxLines = 1)
                        }
                        TextButton(onClick = {
                            bridgeLog = com.kdzw8p.mqa.v71f.utils.LepaoBridge.log(context)
                                .ifBlank { "还没有日志" }
                            showBridgeLog = true
                        }) {
                            Text("查看导入日志", fontSize = 12.sp, maxLines = 1)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.lepao_social_instructions),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = AppColors.textSecondary(isDark)
                        )
                        if (showBridgeLog) {
                            AlertDialog(
                                onDismissRequest = { showBridgeLog = false },
                                title = { Text("导入日志", fontSize = 14.sp) },
                                text = {
                                    Column(
                                        Modifier
                                            .heightIn(max = 360.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(
                                            bridgeLog,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp,
                                            color = AppColors.textSecondary(isDark)
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        bridgeLog = com.kdzw8p.mqa.v71f.utils.LepaoBridge.log(context)
                                            .ifBlank { "还没有日志" }
                                    }) { Text("刷新", fontSize = 12.sp) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showBridgeLog = false }) { Text("关闭", fontSize = 12.sp) }
                                }
                            )
                        }
                    }
                }

                if (message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, fontSize = 11.sp, color = AppColors.textSecondary(isDark))
                }
            }
        },
        confirmButton = {
            if (mode != DirectLoginMode.QQ) TextButton(
                enabled = !busy && !wxBusy,
                onClick = {
                    busy = true
                    message = ""
                    if (mode == DirectLoginMode.TOKEN) {
                        busy = false
                        if (tokenUid.isBlank() || tokenValue.isBlank()) {
                            message = "user_id 和 access_token 都要填"
                        } else {
                            val session = org.json.JSONObject()
                                .put("user_id", tokenUid.trim())
                                .put("token", tokenValue.trim())
                                .put("access_token", tokenValue.trim())
                                .put("user_access_token", tokenValue.trim())
                                .apply {
                                    if (tokenRefresh.isNotBlank()) put("refresh_token", tokenRefresh.trim())
                                    if (tokenExpire.isNotBlank()) put("refresh_expire", tokenExpire.trim())
                                }
                            viewModel.saveLepaoSession(session.toString())
                            message = "已写入会话注入；重启乐跑生效"
                        }
                        return@TextButton
                    }
                    if (mode == DirectLoginMode.WECHAT) {
                        if (wxImportedOk) {
                            busy = false
                            message = "这份凭据已导入并登录成功；重复导入会用同一微信凭据再请求，可能踢掉当前登录态。关闭弹窗即完成。"
                            return@TextButton
                        }
                        if (openId.isBlank() || accessToken.isBlank()) {
                            busy = false
                            message = "先扫码或填 openid / accesstoken"
                        } else {
                            busy = false
                            startWxImport()
                        }
                        return@TextButton
                    }
                    viewModel.directLoginLepao(
                        mode = if (mode == DirectLoginMode.WECHAT) {
                            LepaoDirectLogin.Mode.WECHAT
                        } else {
                            LepaoDirectLogin.Mode.APP_TOKEN
                        },
                        schoolId = schoolId,
                        phone = phone,
                        appToken = appToken,
                        openId = openId,
                        accessToken = accessToken,
                        refreshToken = refreshToken
                    ) { outcome ->
                        busy = false
                        message = when (outcome) {
                            is LepaoDirectLogin.Outcome.Success -> {
                                outcome.refreshedWeChat?.let { refreshed ->
                                    openId = refreshed.openId
                                    accessToken = refreshed.accessToken
                                    refreshToken = refreshed.refreshToken
                                }
                                "登录成功：uid ${outcome.result.userId}，已写入会话注入；重启乐跑生效" +
                                    if (outcome.refreshedWeChat != null) "（微信凭证已用 refresh_token 刷新）" else ""
                            }
                            is LepaoDirectLogin.Outcome.Failure -> "登录失败：${outcome.message}"
                        }
                    }
                }
            ) { Text(if (busy) "登录中…" else if (mode == DirectLoginMode.TOKEN) "写入会话" else if (mode == DirectLoginMode.WECHAT) if (wxImportedOk) "已导入并登录" else "导入并登录" else "登录并写入") }
        },
        dismissButton = {
            TextButton(enabled = !busy && !wxBusy, onClick = onDismiss) { Text("关闭") }
        }
    )

    if (showSchoolPicker) {
        SchoolPickerDialog(
            viewModel = viewModel,
            isDark = isDark,
            onPick = { school ->
                schoolId = school.id.toString()
                schoolName = school.name
                showSchoolPicker = false
            },
            onDismiss = { showSchoolPicker = false }
        )
    }

    val qrBitmap = remember(showQr, tokenUid, tokenValue) {
        if (!showQr || tokenUid.isBlank() || tokenValue.isBlank()) {
            null
        } else runCatching {
            val content = org.json.JSONObject()
                .put("user_id", tokenUid.trim())
                .put("token", tokenValue.trim())
                .put("access_token", tokenValue.trim())
                .apply {
                    if (tokenRefresh.isNotBlank()) put("refresh_token", tokenRefresh.trim())
                    if (tokenExpire.isNotBlank()) put("refresh_expire", tokenExpire.trim())
                }
                .toString()
            val size = 640
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bmp
        }.getOrNull()
    }
    if (showQr) {
        AlertDialog(
            onDismissRequest = { showQr = false },
            containerColor = AppColors.cardBackground(isDark),
            title = { Text("登录二维码", fontSize = 15.sp) },
            text = {
                Column {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(260.dp)
                        )
                    }
                    Text(
                        "对方用本模块「扫码导入」扫描这张二维码，就能拿到 user_id + token；只包含登录凭据，不要发给无关的人。",
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = AppColors.textSecondary(isDark)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text("关闭") } }
        )
    }

    val wxQrBitmap = remember(showWxQr, openId, accessToken, refreshToken) {
        if (!showWxQr || openId.isBlank() || accessToken.isBlank()) {
            null
        } else runCatching {
            qrCodeBitmap(
                LepaoDirectLogin.weChatAuthUri(
                    LepaoDirectLogin.WeChatTokens(openId.trim(), accessToken.trim(), refreshToken.trim())
                )
            )
        }.getOrNull()
    }
    if (showWxQr) {
        AlertDialog(
            onDismissRequest = { showWxQr = false },
            containerColor = AppColors.cardBackground(isDark),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("微信授权码二维码", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        showWxQr = false
                        scanLauncher.launch(ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("扫描微信授权码")
                            setBeepEnabled(false)
                        })
                    }) { Text("扫描", fontSize = 12.sp) }
                }
            },
            text = {
                Column {
                    if (wxQrBitmap != null) {
                        Image(
                            bitmap = wxQrBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(260.dp)
                        )
                    }
                    Text(
                        "在没装微信的设备上：本模块「乐跑直登 → 微信凭证 → 扫码导入/粘贴授权码」后点「登录并写入」。" +
                            "授权码等于微信登录凭据，不要发给无关的人。",
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = AppColors.textSecondary(isDark)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showWxQr = false }) { Text("关闭") } }
        )
    }

    if (showWxMobileQr) {
        WeChatQrLoginDialog(
            isDark = isDark,
            onCode = { code ->
                showWxMobileQr = false
                wxBusy = true
                viewModel.exchangeWeChatCode(code) { outcome ->
                    when (outcome) {
                        is LepaoDirectLogin.WeChatCodeOutcome.Success -> {
                            openId = outcome.tokens.openId
                            accessToken = outcome.tokens.accessToken
                            refreshToken = outcome.tokens.refreshToken
                            wxImportedOk = false
                            wxBusy = false
                            startWxImport()
                        }
                        is LepaoDirectLogin.WeChatCodeOutcome.Failure -> {
                            wxBusy = false
                            message = "换票失败：${outcome.message}"
                        }
                    }
                }
            },
            onDismiss = { showWxMobileQr = false }
        )
    }
}

@Composable
private fun WeChatQrLoginDialog(
    isDark: Boolean,
    onCode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var qr by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    LaunchedEffect(Unit) {
        WeChatQrLogin.start(object : WeChatQrLogin.Callback {
            override fun onQr(bitmap: android.graphics.Bitmap) {
                qr = bitmap
                status = "请用微信扫描二维码"
            }
            override fun onScanned() {
                status = "已扫码，请在手机上点「允许」"
            }
            override fun onCode(code: String) {
                onCode(code)
            }
            override fun onError(message: String) {
                status = message
            }
        })
    }
    DisposableEffect(Unit) { onDispose { WeChatQrLogin.stop() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.cardBackground(isDark),
        title = { Text("微信扫码登录", fontSize = 15.sp) },
        text = {
            Column {
                if (qr != null) {
                    Image(qr!!.asImageBitmap(), null, Modifier.size(260.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(status, fontSize = 11.sp, color = AppColors.textSecondary(isDark))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 学校列表：School/nearbySchool 返回的 all_school_list 本地搜索，选中后回填 school_id。 */
@Composable
private fun SchoolPickerDialog(
    viewModel: MainViewModel,
    isDark: Boolean,
    onPick: (LepaoSchoolDirectory.School) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var schools by remember { mutableStateOf(viewModel.cachedLepaoSchools()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val filtered = remember(query, schools) {
        if (query.isBlank()) schools else schools.filter { it.name.contains(query, ignoreCase = true) }
    }

    fun refresh() {
        if (busy) return
        busy = true
        message = ""
        viewModel.fetchLepaoSchools { outcome ->
            busy = false
            when (outcome) {
                is LepaoSchoolDirectory.Outcome.Success -> {
                    schools = outcome.schools
                    message = "共 ${outcome.schools.size} 所"
                }
                is LepaoSchoolDirectory.Outcome.Failure -> message = outcome.message
            }
        }
    }

    // 打开就自动拉一次；输入框是本地过滤，输入即搜，不需要再点按钮
    LaunchedEffect(Unit) { if (schools.isEmpty()) refresh() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.cardBackground(isDark),
        title = { Text("选择学校", fontSize = 15.sp) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索学校名称", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(enabled = !busy, onClick = { refresh() }) {
                        Text(if (busy) "获取中…" else "刷新", fontSize = 12.sp)
                    }
                }
                if (message.isNotBlank()) {
                    Text(message, fontSize = 10.sp, color = AppColors.textSecondary(isDark), maxLines = 1)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(filtered.take(300)) { school ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(school) }
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(school.name, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            if (school.nearby) {
                                Text("附近", fontSize = 9.sp, color = AccentBlue)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(school.id.toString(), fontSize = 10.sp, color = AppColors.textSecondary(isDark))
                        }
                        HorizontalDivider(color = AppColors.textSecondary(isDark).copy(alpha = 0.12f))
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "点右上「刷新」拉取学校；输入名称会即时过滤",
                                fontSize = 11.sp,
                                color = AppColors.textSecondary(isDark),
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 当前页摘要；完整分页记录与判定详情在账号详情中按绑定会话读取。 */
@Composable
private fun RunHistoryCard(viewModel: MainViewModel, isDark: Boolean) {
    // 当前激活账号变了就重建列表：旧账号的缓存记录不能在新会话下被点开
    val historyAccount = viewModel.lepaoActiveAccountName()
    var records by remember(historyAccount) { mutableStateOf(viewModel.cachedLepaoRunRecords()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<LepaoRunHistory.Record?>(null) }
    var detailBusy by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf("") }
    var detailResult by remember { mutableStateOf<com.kdzw8p.mqa.v71f.utils.LepaoRunTrack.Result?>(null) }

    selected?.let { rec ->
        RunPathPreviewDialog(
            title = rec.name, busy = detailBusy, error = detailError, result = detailResult,
            fallbackDistance = rec.distance, fallbackPace = rec.pace,
            fallbackDate = com.kdzw8p.mqa.v71f.utils.LepaoRunTrack.formatDate(
                rec.startTime.toLongOrNull()?.let { if (it > 100_000_000_000L) it / 1000L else it } ?: 0L
            ),
            isDark = isDark, onDismiss = { selected = null; detailResult = null; detailError = "" }
        )
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "历史路径",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "点击记录预览点位、配速、步频与速度（当前设备登录态，只读）",
                        fontSize = 10.sp,
                        color = AppColors.textSecondary(isDark)
                    )
                }
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    message = ""
                    viewModel.fetchLepaoRunHistory { outcome ->
                        busy = false
                        when (outcome) {
                            is LepaoRunHistory.Outcome.Success -> {
                                records = outcome.page.records
                                message = "共 ${outcome.page.totalNum.ifBlank { outcome.page.records.size.toString() }} 条"
                            }
                            is LepaoRunHistory.Outcome.Failure -> message = outcome.message
                        }
                    }
                }) { Text(if (busy) "刷新中…" else "刷新", fontSize = 12.sp) }
            }
            if (records.isEmpty()) {
                Text(
                    "暂无记录，点「刷新」拉取；需先在乐跑登录过",
                    fontSize = 10.sp,
                    color = AppColors.textSecondary(isDark),
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                records.forEach { record ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp).clickable {
                            selected = record
                            detailBusy = true
                            detailError = ""
                            detailResult = null
                            viewModel.fetchLepaoRunDetail(record) { outcome ->
                                if (selected != record) return@fetchLepaoRunDetail
                                detailBusy = false
                                when (outcome) {
                                    is LepaoRunHistory.DetailOutcome.Success -> {
                                        detailResult = com.kdzw8p.mqa.v71f.utils.LepaoRunTrack.parse(
                                            outcome.rawJson, record.rawJson, outcome.fileText
                                        )
                                        if (detailResult == null) detailError = "详情解析失败"
                                    }
                                    is LepaoRunHistory.DetailOutcome.Failure -> detailError = outcome.message
                                }
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            record.name.ifBlank { "未命名跑场" },
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        Text(
                            buildString {
                                if (record.distance.isNotBlank()) append(record.distance)
                                if (record.pace.isNotBlank()) {
                                    if (isNotEmpty()) append(" · ")
                                    append(record.pace)
                                }
                            },
                            fontSize = 10.sp,
                            color = AccentBlue,
                            maxLines = 1
                        )
                    }
                    if (record.startTime.isNotBlank()) {
                        val sec = record.startTime.toLongOrNull()?.let { if (it > 100_000_000_000L) it / 1000L else it } ?: 0L
                        Text(
                            com.kdzw8p.mqa.v71f.utils.LepaoRunTrack.formatDate(sec).ifBlank { record.startTime },
                            fontSize = 9.sp, color = AppColors.textSecondary(isDark), maxLines = 1
                        )
                    }
                    HorizontalDivider(color = AppColors.textSecondary(isDark).copy(alpha = 0.1f))
                }
            }
            if (message.isNotBlank()) {
                Text(message, fontSize = 10.sp, color = AppColors.textSecondary(isDark), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 选中的图片压到最长边 720、JPEG 85，控制下发体积 */
private fun encodeFacePhoto(context: android.content.Context, uri: android.net.Uri): String? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 720) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    var bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val maxSide = maxOf(bitmap.width, bitmap.height)
    if (maxSide > 720) {
        val scale = 720f / maxSide
        val scaled = android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        bitmap = scaled
    }
    val out = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
    bitmap.recycle()
    return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
}

/** 服务端 role：1=学生，2=教师，3=管理员；未知值原样展示 */
private fun roleLabel(role: String): String = when (role) {
    "1" -> "学生"
    "2" -> "教师"
    "3" -> "管理员"
    "4" -> "校友"
    else -> role.ifBlank { "—" }
}

@androidx.compose.runtime.Composable
private fun InfoRow(label: String, value: String, isDark: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 10.sp, color = AppColors.textSecondary(isDark), modifier = Modifier.width(56.dp))
        Text(
            value.ifBlank { "—" },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 2
        )
    }
}
