package com.kdzw8p.mqa.v71f.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.Duration
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.kdzw8p.mqa.v71f.ui.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kdzw8p.mqa.v71f.ui.components.RemoteAvatar
import com.kdzw8p.mqa.v71f.ui.theme.AccentBlue
import com.kdzw8p.mqa.v71f.ui.theme.AppColors
import com.kdzw8p.mqa.v71f.ui.theme.noRippleClickable
import com.kdzw8p.mqa.v71f.viewmodel.LepaoProfileRow

/**
 * 乐跑账号紧凑列表：一行一个账号，头像 + 名称 + 当前标记，
 * 学校/学号与三项统计紧凑排列，不在每个账号内重复状态、配置和操作行。
 */
@Composable
fun LepaoAccountList(
    rows: List<LepaoProfileRow>,
    isDark: Boolean,
    activeName: String = "",
    switchingName: String = "",
    onSwitch: (String) -> Unit,
    onShowDetail: (LepaoProfileRow) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onEditRemark: (LepaoProfileRow) -> Unit,
    onRunAccount: (String) -> Unit = {},
    onRunAll: () -> Unit = {},
    refreshing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val today by produceState(LocalDate.now().toString()) {
        while (true) {
            val now = ZonedDateTime.now()
            value = now.toLocalDate().toString()
            val tomorrow = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            delay(Duration.between(now, tomorrow).toMillis().coerceAtLeast(1L))
        }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.cardBackground(isDark))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "乐跑账号",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    stringResource(if (refreshing) R.string.lepao_accounts_refreshing else R.string.lepao_accounts_refresh),
                    fontSize = 12.sp,
                    color = AccentBlue,
                    modifier = Modifier.noRippleClickable { if (!refreshing) onRefresh() }
                )
                Text(
                    "全跑",
                    fontSize = 12.sp,
                    color = AccentBlue,
                    modifier = Modifier.noRippleClickable(onRunAll)
                )
                Text(
                    "+ 新建",
                    fontSize = 12.sp,
                    color = AccentBlue,
                    modifier = Modifier.noRippleClickable(onAdd)
                )
            }
            Text(
                "扫码登录后自动建档；切换会写回会话并应用该账号配置",
                fontSize = 10.sp,
                color = AppColors.textSecondary(isDark)
            )
            Spacer(Modifier.height(6.dp))

            if (rows.isEmpty()) {
                Text(
                    "还没有账号。先在直登里扫码，或点右上角「+ 新建」。",
                    fontSize = 11.sp,
                    color = AppColors.textSecondary(isDark),
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            }

            if (rows.any { it.refreshFailed }) Text(
                stringResource(R.string.lepao_accounts_cached), fontSize = 9.sp, lineHeight = 12.sp,
                color = AppColors.textSecondary(isDark)
            )
            if (rows.any { it.sessionExpired }) Text(
                stringResource(R.string.lepao_accounts_relogin_hint), fontSize = 9.sp, lineHeight = 12.sp,
                color = AppColors.textSecondary(isDark)
            )
            if (rows.any { it.ruleTimeout }) Text(
                stringResource(R.string.lepao_accounts_rule_timeout_hint), fontSize = 9.sp, lineHeight = 12.sp,
                color = AppColors.textSecondary(isDark)
            )
            rows.forEach { row ->
                val active = row.hasSession && activeName.isNotBlank() && row.name == activeName
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (active) AccentBlue.copy(alpha = 0.10f) else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .noRippleClickable { onShowDetail(row) }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RemoteAvatar(
                            url = row.avatarUrl,
                            fallbackText = row.name,
                            size = 28.dp,
                            fallbackColor = avatarColor(row.name, isDark)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    row.name,
                                    fontSize = 13.sp,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.weight(1f, fill = false),
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1
                                )
                                if (active) {
                                    Spacer(Modifier.width(5.dp))
                                    Text("当前", fontSize = 9.sp, lineHeight = 12.sp, color = AccentBlue)
                                }
                                Spacer(Modifier.width(5.dp))
                                val status = when {
                                    !row.hasSession -> R.string.lepao_accounts_signin
                                    row.sessionExpired -> R.string.lepao_accounts_expired
                                    row.versionBlocked -> R.string.lepao_accounts_version_low
                                    row.ruleTimeout -> R.string.lepao_accounts_rule_timeout
                                    row.refreshFailed -> R.string.lepao_accounts_failed
                                    row.hasCheckedData -> R.string.lepao_accounts_checked
                                    else -> R.string.lepao_accounts_unchecked
                                }
                                Text(
                                    stringResource(status), fontSize = 9.sp, lineHeight = 12.sp,
                                    color = if (!row.hasSession || row.sessionExpired) MaterialTheme.colorScheme.error
                                        else AppColors.textSecondary(isDark), maxLines = 1
                                )
                            }
                            Text(
                                listOfNotNull(
                                    row.school.takeIf { it.isNotBlank() },
                                    row.studentNo.takeIf { it.isNotBlank() }?.let { "学号 $it" }
                                ).joinToString(" · ").ifBlank { row.remark.ifBlank { "—" } },
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = AppColors.textSecondary(isDark),
                                maxLines = 1
                            )
                            if (row.realName.isNotBlank() || row.major.isNotBlank() || row.sexText.isNotBlank()) {
                                Text(
                                    listOfNotNull(
                                        row.realName.takeIf { it.isNotBlank() }?.let { "姓名 $it" },
                                        row.major.takeIf { it.isNotBlank() }?.let { "专业 $it" },
                                        row.sexText.takeIf { it.isNotBlank() }
                                    ).joinToString(" · "),
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    color = AppColors.textSecondary(isDark),
                                    maxLines = 1
                                )
                            }
                        }
                        Icon(
                            Icons.Rounded.PlayCircle, stringResource(R.string.lepao_run_one),
                            modifier = Modifier.size(18.dp).noRippleClickable { onRunAccount(row.name) },
                            tint = if (row.hasSession) AccentBlue else AppColors.textSecondary(isDark)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.lepao_remark_edit),
                            fontSize = 10.sp, lineHeight = 14.sp, color = AccentBlue,
                            modifier = Modifier.padding(start = 6.dp).noRippleClickable { onEditRemark(row) }
                        )
                        Text(
                            "删",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = AppColors.textSecondary(isDark),
                            modifier = Modifier.padding(horizontal = 10.dp).noRippleClickable { onDelete(row.name) }
                        )
                        Text(
                            "切换",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = AccentBlue,
                            modifier = Modifier.noRippleClickable {
                                // 连点由 VM 的 switchInFlight 锁兜底（回 toast），界面不做加载态
                                if (switchingName.isBlank()) onSwitch(row.name)
                            }
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Metric("次数", (row.completedText ?: "—") + if (row.refreshFailed && row.completedText != null) "*" else "", isDark)
                        Metric("目标", (row.targetText ?: "—") + if (row.refreshFailed && row.targetText != null) "*" else "", isDark)
                        Metric("UID", if (com.kdzw8p.mqa.v71f.utils.LepaoRunData.isAccountId(row.userId)) row.userId else stringResource(R.string.lepao_list_guest), isDark)
                        val todayCount = row.runsOn(today)
                        Text(
                            when {
                                todayCount == null -> stringResource(R.string.lepao_today_unknown)
                                todayCount > 0 -> stringResource(R.string.lepao_today_ran, todayCount)
                                else -> stringResource(R.string.lepao_today_not_run)
                            }, fontSize = 10.sp, lineHeight = 14.sp,
                            color = if (todayCount != null && todayCount > 0) Color(0xFF20B978) else AppColors.textSecondary(isDark),
                            modifier = Modifier.align(Alignment.CenterVertically), maxLines = 1
                        )
                    }
                    if (row.remark.isNotBlank() && row.remark != row.school) Text(
                        row.remark, fontSize = 10.sp, lineHeight = 13.sp,
                        color = AppColors.textSecondary(isDark), maxLines = 1
                    )
                }
                HorizontalDivider(color = AppColors.textSecondary(isDark).copy(alpha = 0.12f))
            }
        }
    }
}

/** 详情仅展示服务端跑步次数、目标次数和当前场地。 */
@Composable
fun LepaoAccountDetailDialog(
    row: LepaoProfileRow,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.cardBackground(isDark),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteAvatar(
                    url = row.avatarUrl,
                    fallbackText = row.name,
                    size = 38.dp,
                    fallbackColor = avatarColor(row.name, isDark)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(row.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        row.school.ifBlank { row.remark }.ifBlank { "乐跑账号" },
                        fontSize = 10.sp,
                        color = AppColors.textSecondary(isDark)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                DetailLine("跑步次数", row.completedText ?: stringResource(R.string.lepao_data_missing), isDark)
                DetailLine("目标次数", row.targetText ?: stringResource(R.string.lepao_data_missing), isDark)
                DetailLine("当前场地", row.venueName.ifBlank { "—" }, isDark)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSwitch(row.name)
                onDismiss()
            }) { Text("切换此账号", fontSize = 12.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 12.sp) }
        }
    )
}

@Composable
private fun Metric(label: String, value: String, isDark: Boolean) {
    Column {
        Text(label, fontSize = 9.sp, lineHeight = 12.sp, color = AppColors.textSecondary(isDark))
        Text(
            value,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String, isDark: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            modifier = Modifier.width(76.dp),
            fontSize = 11.sp,
            color = AppColors.textSecondary(isDark)
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

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

