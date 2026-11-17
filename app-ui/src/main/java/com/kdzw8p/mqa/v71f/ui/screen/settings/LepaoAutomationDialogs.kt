package com.kdzw8p.mqa.v71f.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kdzw8p.mqa.v71f.viewmodel.cancelLepaoAutomation
import com.kdzw8p.mqa.v71f.viewmodel.dismissLepaoAutomation
import com.kdzw8p.mqa.v71f.viewmodel.lepaoAutomationState

/**
 * 一键跑/批量跑的两个窗口：
 * - 确认框：显示将跑的账号与目标公里，开始前最后确认；
 * - 状态框：当前第几个/共几个、阶段（切号/开始路线/等待开跑/跑步中/完成/超时）、
 *   进度条与每个账号的阶段列表；结束后停在「全部完成」等用户关闭。
 */
/** 一键跑确认框的参数：男女目标公里分开，按账号性别自动选。 */
data class LepaoAutomationConfirm(
    val names: List<String>,
    val kmMale: String,
    val kmFemale: String,
    /** 每个账号今日已跑次数：-1 = 今日未确认，0 = 今日未跑，>0 = 已跑。仅批量跑用于跳过判定。 */
    val todayRunCounts: Map<String, Int> = emptyMap()
)

@Composable
fun LepaoAutomationDialogs(
    confirm: LepaoAutomationConfirm?,
    onDismissConfirm: () -> Unit,
    onStart: (List<String>, Double, Double) -> Unit
) {
    val automation by lepaoAutomationState.collectAsState()

    if (confirm != null) {
        var maleText by remember(confirm) { mutableStateOf(confirm.kmMale.ifBlank { "1.8" }) }
        var femaleText by remember(confirm) { mutableStateOf(confirm.kmFemale.ifBlank { "1.5" }) }
        // 批量跑默认跳过今日已跑：避免重复刷同一天的成绩，单账号时该开关不显示。
        var skipTodayDone by remember(confirm) { mutableStateOf(true) }
        val batch = confirm.names.size > 1
        val doneToday = confirm.names.count { (confirm.todayRunCounts[it] ?: -1) > 0 }
        val targets = if (batch && skipTodayDone) {
            confirm.names.filter { (confirm.todayRunCounts[it] ?: -1) <= 0 }
        } else confirm.names
        AlertDialog(
            onDismissRequest = onDismissConfirm,
            confirmButton = {
                TextButton(
                    enabled = targets.isNotEmpty(),
                    onClick = {
                        val male = maleText.toDoubleOrNull()
                        val female = femaleText.toDoubleOrNull()
                        if (male != null && male > 0.0 && female != null && female > 0.0) {
                            onStart(targets, male, female)
                        }
                    }
                ) { Text("开始") }
            },
            dismissButton = { TextButton(onClick = onDismissConfirm) { Text("取消") } },
            title = {
                Text(
                    if (confirm.names.size > 1) "批量跑 ${confirm.names.size} 个账号"
                    else "一键跑 ${confirm.names.first()}"
                )
            },
            text = {
                Column {
                    Text("到达目标公里后自动结束并上传；按账号身份性别自动选长度", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = maleText,
                        onValueChange = { maleText = it },
                        singleLine = true,
                        label = { Text("男生目标（公里）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = femaleText,
                        onValueChange = { femaleText = it },
                        singleLine = true,
                        label = { Text("女生目标（公里）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (batch) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = skipTodayDone, onCheckedChange = { skipTodayDone = it })
                            Text("自动跳过今日已经跑（$doneToday 个）", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (targets.isEmpty()) "今日全部已跑，没有需要执行的账号"
                        else targets.joinToString("、"),
                        fontSize = 11.sp
                    )
                }
            }
        )
    }

    automation?.let { st ->
        AlertDialog(
            onDismissRequest = { if (st.finished) dismissLepaoAutomation() },
            confirmButton = {
                TextButton(onClick = {
                    if (st.finished) dismissLepaoAutomation() else cancelLepaoAutomation()
                }) { Text(if (st.finished) "关闭" else "取消") }
            },
            title = { Text(if (st.total > 1) "批量跑进度" else "一键跑进度") },
            text = {
                Column {
                    Text(
                        "${minOf(st.index + 1, st.total)}/${st.total}  ${st.account}  ·  ${st.phase}",
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (st.total == 0) 0f
                            else (st.index.toFloat() / st.total).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    st.items.forEach { item ->
                        Text("${item.name}：${item.phase}", fontSize = 11.sp)
                    }
                }
            }
        )
    }
}
