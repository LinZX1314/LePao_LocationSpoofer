package com.kdzw8p.mqa.v71f.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import com.kdzw8p.mqa.v71f.ui.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kdzw8p.mqa.v71f.ui.theme.AccentBlue
import com.kdzw8p.mqa.v71f.ui.theme.AppColors
import com.kdzw8p.mqa.v71f.ui.theme.noRippleClickable
import com.kdzw8p.mqa.v71f.ui.components.RemoteAvatar
import com.kdzw8p.mqa.v71f.viewmodel.LepaoProfileRow

/**
 * 多用户管理（功能页）：表格化展示每个乐跑用户。
 *
 * 列：头像+名称 / 备注 / 跑步进度 / 登录方式 / 操作
 * - 头像：名称首字母圆角色块（本地配置无真实头像）
 * - 跑步进度：已跑次数 / 目标次数，可在详情里编辑
 * - 登录方式：ID / 微信 / 扫码 / Token，详情里可切换并绑定会话
 * - 操作：切换（写回坐标+hook组并重发配置）/ 详情 / 删除
 */
@Composable
fun LepaoUserTable(
    rows: List<LepaoProfileRow>,
    isDark: Boolean,
    activeName: String = "",
    onSwitch: (String) -> Unit,
    onShowDetail: (LepaoProfileRow) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.lepao_local_profiles),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "+ 新建",
                    fontSize = 12.sp,
                    color = AccentBlue,
                    modifier = Modifier.noRippleClickable(onAdd)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.lepao_profile_source_warning),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = AppColors.textSecondary(isDark)
            )
            Spacer(Modifier.height(10.dp))

            // 表头
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("用户", modifier = Modifier.weight(1.5f), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = AppColors.textSecondary(isDark))
                Text("备注", modifier = Modifier.weight(1.1f), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = AppColors.textSecondary(isDark))
                Text(stringResource(R.string.lepao_server_status), modifier = Modifier.weight(0.9f), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = AppColors.textSecondary(isDark))
                Text(stringResource(R.string.lepao_declared_method), modifier = Modifier.weight(0.7f), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = AppColors.textSecondary(isDark))
                Text("操作", modifier = Modifier.weight(1.2f), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = AppColors.textSecondary(isDark))
            }
            HorizontalDivider(color = AppColors.textSecondary(isDark).copy(alpha = 0.2f))

            if (rows.isEmpty()) {
                Text(
                    "还没有用户，点右上角「+ 新建」添加。",
                    fontSize = 11.sp,
                    color = AppColors.textSecondary(isDark),
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            } else {
                Column {
                    rows.forEach { row ->
                        val isActive = activeName.isNotBlank() && row.name == activeName
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isActive) AccentBlue.copy(alpha = 0.12f) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .noRippleClickable { onShowDetail(row) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 头像 + 名称
                            Row(
                                modifier = Modifier.weight(1.5f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RemoteAvatar(
                                    url = row.avatarUrl,
                                    fallbackText = row.name,
                                    size = 26.dp,
                                    fallbackColor = avatarColor(row.name, isDark)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(
                                        row.name,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1
                                    )
                                    if (isActive) {
                                        Text("当前账号", fontSize = 9.sp, color = AccentBlue)
                                    } else if (row.hasSession) {
                                        Text(
                                            stringResource(R.string.lepao_session_unverified),
                                            fontSize = 9.sp,
                                            color = AccentBlue
                                        )
                                    }
                                }
                            }
                            Text(
                                row.remark.ifBlank { "—" },
                                modifier = Modifier.weight(1.1f),
                                fontSize = 10.sp,
                                color = AppColors.textSecondary(isDark),
                                maxLines = 1
                            )
                            Text(
                                row.progressText,
                                modifier = Modifier.weight(0.9f),
                                fontSize = 10.sp,
                                color = AppColors.textSecondary(isDark),
                                maxLines = 1
                            )
                            Text(
                                row.loginModeLabel,
                                modifier = Modifier.weight(0.7f),
                                fontSize = 10.sp,
                                color = AccentBlue,
                                maxLines = 1
                            )
                            Row(
                                modifier = Modifier.weight(1.2f),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    "切换此账号",
                                    fontSize = 10.sp,
                                    color = AccentBlue,
                                    modifier = Modifier.noRippleClickable { onSwitch(row.name) }
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "详情",
                                    fontSize = 10.sp,
                                    color = AppColors.textSecondary(isDark),
                                    modifier = Modifier.noRippleClickable { onShowDetail(row) }
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "删",
                                    fontSize = 10.sp,
                                    color = Color(0xFFCF222E),
                                    modifier = Modifier.noRippleClickable { onDelete(row.name) }
                                )
                            }
                        }
                        HorizontalDivider(color = AppColors.textSecondary(isDark).copy(alpha = 0.1f))
                    }
                }
            }
        }
    }
}

/** 名称固定映射到一组柔和色调，保证同一用户每次颜色一致 */
private fun avatarColor(name: String, isDark: Boolean): Color {
    val palette = if (isDark) listOf(
        Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFEC4899),
        Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF06B6D4)
    ) else listOf(
        Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFFDB2777),
        Color(0xFFD97706), Color(0xFF059669), Color(0xFF0891B2)
    )
    return palette[Math.floorMod(name.hashCode(), palette.size)]
}

/**
 * 用户详情弹窗：完整数据 + 编辑。
 * 可改：名称、备注、跑步次数、目标次数、登录方式，并把会话 JSON 绑定到该用户。
 */
@Composable
fun LepaoUserDetailDialog(
    row: LepaoProfileRow,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        newName: String,
        remark: String,
        runCount: Int,
        runTarget: Int,
        loginMode: String,
        session: String
    ) -> Unit,
    onSwitch: (String) -> Unit
) {
    var name by remember(row.name) { mutableStateOf(row.name) }
    var remark by remember(row.name) { mutableStateOf(row.remark) }
    var runCount by remember(row.name) { mutableStateOf(row.runCount.toString()) }
    var runTarget by remember(row.name) { mutableStateOf(row.runTarget.toString()) }
    var loginMode by remember(row.name) { mutableStateOf(row.loginMode) }
    var session by remember(row.name) { mutableStateOf(row.session) }
    val sessionValid = session.isBlank() || runCatching {
        val json = org.json.JSONObject(session)
        json.optString("user_id").isNotBlank() && json.optString("token").isNotBlank()
    }.getOrDefault(false)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.cardBackground(isDark),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    RemoteAvatar(
                        url = row.avatarUrl,
                        fallbackText = row.name,
                        size = 32.dp,
                        fallbackColor = avatarColor(row.name, isDark)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(row.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "创建于 " + formatTime(row.createdAt),
                        fontSize = 10.sp,
                        color = AppColors.textSecondary(isDark)
                    )
                }
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item {
                    if (row.source == "mmkv_readonly") {
                        Text(stringResource(R.string.lepao_read_profile_only), fontSize = 11.sp,
                            color = AppColors.textSecondary(isDark))
                        Text(stringResource(R.string.lepao_profile_identity, row.userId, row.nickname, row.school, row.studentNo),
                            fontSize = 11.sp)
                        Text(stringResource(R.string.lepao_read_captured_at) + ": " + formatTime(row.capturedAt),
                            fontSize = 10.sp, color = AppColors.textSecondary(isDark))
                        Spacer(Modifier.height(8.dp))
                    }
                    if (row.avatarUrl.isNotBlank() || row.serverRunCount > 0 || row.serverTotalRunCount > 0 ||
                        row.totalDistanceM > 0.0 || row.venueText.isNotBlank() || row.faceImage.isNotBlank()
                    ) {
                        FieldLabel("乐跑服务端数据（只读）", isDark)
                        Text(
                            "跑步次数：${row.progressText}　累计：${row.distanceText}" +
                                if (row.runScore.isNotBlank()) "　学分：${row.runScore}" else "",
                            fontSize = 11.sp
                        )
                        if (row.venueText.isNotBlank()) {
                            Text("当前场地：${row.venueText}", fontSize = 11.sp)
                        }
                        if (row.faceImage.isNotBlank()) {
                            Text("人脸照片：已登记", fontSize = 11.sp, color = AppColors.textSecondary(isDark))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    FieldLabel("名称", isDark)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    FieldLabel("备注", isDark)
                    OutlinedTextField(
                        value = remark,
                        onValueChange = { remark = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            FieldLabel(stringResource(R.string.lepao_local_count), isDark)
                            OutlinedTextField(
                                value = runCount,
                                onValueChange = { runCount = it.filter { c -> c.isDigit() } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            FieldLabel(stringResource(R.string.lepao_local_target), isDark)
                            OutlinedTextField(
                                value = runTarget,
                                onValueChange = { runTarget = it.filter { c -> c.isDigit() } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    FieldLabel(stringResource(R.string.lepao_declared_method), isDark)
                    Row {
                        listOf("id" to "ID", "wechat" to "微信", "qrcode" to "扫码", "token" to "Token")
                            .forEach { (key, label) ->
                                FilterChip(
                                    selected = loginMode == key,
                                    onClick = { loginMode = key },
                                    label = { Text(label, fontSize = 10.sp) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                    }
                    Spacer(Modifier.height(8.dp))

                    FieldLabel(stringResource(R.string.lepao_session_json_unverified), isDark)
                    OutlinedTextField(
                        value = session,
                        onValueChange = { session = it },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = !sessionValid,
                        supportingText = { Text(stringResource(R.string.lepao_session_validation_hint)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp)
                    )
                    Spacer(Modifier.height(4.dp))

                    FieldLabel("保存的坐标", isDark)
                    Text(
                        if (row.lat.isNotBlank()) "${row.lat}, ${row.lng}" else "—",
                        fontSize = 11.sp,
                        color = AppColors.textSecondary(isDark)
                    )
                    Spacer(Modifier.height(4.dp))
                    FieldLabel("检测层开关快照", isDark)
                    Text(
                        row.groupsJson,
                        fontSize = 9.sp,
                        color = AppColors.textSecondary(isDark),
                        maxLines = 3
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = sessionValid, onClick = {
                onSave(
                    name.trim().ifBlank { row.name },
                    remark.trim(),
                    runCount.toIntOrNull() ?: 0,
                    runTarget.toIntOrNull() ?: 0,
                    loginMode,
                    session.trim()
                )
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = sessionValid, onClick = {
                    onSave(
                        name.trim().ifBlank { row.name },
                        remark.trim(),
                        runCount.toIntOrNull() ?: 0,
                        runTarget.toIntOrNull() ?: 0,
                        loginMode,
                        session.trim()
                    )
                    onSwitch(name.trim().ifBlank { row.name })
                    onDismiss()
                }) { Text("保存并切换此账号", fontSize = 12.sp) }
                TextButton(onClick = onDismiss) { Text("取消", fontSize = 12.sp) }
            }
        }
    )
}

@Composable
private fun FieldLabel(text: String, isDark: Boolean) {
    Text(
        text,
        fontSize = 10.sp,
        color = AppColors.textSecondary(isDark),
        modifier = Modifier.padding(bottom = 3.dp)
    )
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "—"
    return try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        fmt.format(java.util.Date(ms))
    } catch (_: Throwable) {
        "—"
    }
}
