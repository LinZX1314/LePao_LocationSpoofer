package com.kdzw8p.mqa.v71f.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.kdzw8p.mqa.v71f.ui.R
import com.kdzw8p.mqa.v71f.utils.LepaoRunData
import com.kdzw8p.mqa.v71f.utils.LepaoRunHistory
import com.kdzw8p.mqa.v71f.viewmodel.LepaoProfileRow
import com.kdzw8p.mqa.v71f.viewmodel.MainViewModel
import com.kdzw8p.mqa.v71f.viewmodel.fetchLepaoAccountHistory
import com.kdzw8p.mqa.v71f.viewmodel.fetchLepaoAccountRecord
import com.kdzw8p.mqa.v71f.viewmodel.refreshLepaoProfileStats
import com.kdzw8p.mqa.v71f.viewmodel.refreshLepaoRules
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** 页面可见时定期刷新当前档案；每个响应按会话和请求代次隔离。 */
@Composable
internal fun LepaoAccountRunData(viewModel: MainViewModel, row: LepaoProfileRow) {
    val context = LocalContext.current
    var active by remember(row.name, row.session) { mutableStateOf(true) }
    var statsBusy by remember(row.name, row.session) { mutableStateOf(false) }
    var ruleBusy by remember(row.name, row.session) { mutableStateOf(false) }
    var ruleMessage by remember(row.name, row.session) { mutableStateOf("") }
    var statsMessage by remember(row.name, row.session) { mutableStateOf("") }
    var yearInput by remember(row.name) { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR).toString()) }
    var loadedYear by remember(row.name, row.session) { mutableStateOf("") }
    var records by remember(row.name, row.session) { mutableStateOf(emptyList<LepaoRunHistory.Record>()) }
    var page by remember(row.name, row.session) { mutableStateOf(0) }
    var total by remember(row.name, row.session) { mutableStateOf("") }
    var hasMore by remember(row.name, row.session) { mutableStateOf(true) }
    var busy by remember(row.name, row.session) { mutableStateOf(false) }
    var requestGeneration by remember(row.name, row.session) { mutableStateOf(0) }
    var message by remember(row.name, row.session) { mutableStateOf("") }
    var fetchedAt by remember(row.name, row.session) { mutableStateOf(0L) }
    var detailRecord by remember(row.name, row.session) { mutableStateOf<LepaoRunHistory.Record?>(null) }
    var detailJson by remember(row.name, row.session) { mutableStateOf("") }
    var detailError by remember(row.name, row.session) { mutableStateOf("") }
    var detailBusy by remember(row.name, row.session) { mutableStateOf(false) }
    var detailFetchedAt by remember(row.name, row.session) { mutableStateOf(0L) }
    val missing = stringResource(R.string.lepao_data_missing)
    val rule = remember(row.runRuleJson) { LepaoRunData.objectOrNull(row.runRuleJson) }
    val stats = remember(row.statsJson) { LepaoRunData.objectOrNull(row.statsJson) }

    fun time(ms: Long): String = if (ms <= 0) missing else
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

    fun refreshStats() {
        if (statsBusy) return
        statsBusy = true
        viewModel.refreshLepaoProfileStats(row.name) { result ->
            if (active) { statsBusy = false; statsMessage = result }
        }
    }

    fun history(reset: Boolean) {
        if (busy) return
        val year = if (reset) yearInput.trim() else loadedYear
        if (!year.matches(Regex("[0-9]{4}"))) {
            message = context.getString(R.string.lepao_data_bad_year)
            return
        }
        busy = true
        val nextPage = if (reset) 1 else page + 1
        val generation = ++requestGeneration
        message = context.getString(R.string.lepao_data_loading)
        if (reset) { detailRecord = null; detailJson = ""; detailError = ""; detailBusy = false }
        viewModel.fetchLepaoAccountHistory(row.name, year, nextPage) { outcome ->
            if (active && generation == requestGeneration) {
                busy = false
                when (outcome) {
                    is LepaoRunHistory.Outcome.Success -> {
                        val previous = if (reset) emptyList() else records
                        val combined = (previous + outcome.page.records).distinctBy { it.year to it.recordId }
                        val appended = combined.size > previous.size
                        records = combined
                        loadedYear = year
                        page = nextPage
                        total = outcome.page.totalNum
                        fetchedAt = System.currentTimeMillis()
                        hasMore = outcome.page.records.isNotEmpty() && appended &&
                            (total.toIntOrNull()?.let { records.size < it } ?: true)
                        message = context.getString(R.string.lepao_data_history_count, records.size, total.ifBlank { missing })
                    }
                    is LepaoRunHistory.Outcome.Failure -> {
                        message = context.getString(R.string.lepao_data_failed, outcome.message)
                    }
                }
            }
        }
    }

    fun detail(record: LepaoRunHistory.Record) {
        if (detailBusy) return
        detailRecord = record
        detailJson = ""
        detailError = ""
        detailBusy = true
        val generation = requestGeneration
        viewModel.fetchLepaoAccountRecord(row.name, record) { outcome ->
            if (active && detailRecord == record && generation == requestGeneration) {
                detailBusy = false
                when (outcome) {
                    is LepaoRunHistory.DetailOutcome.Success -> {
                        detailJson = outcome.rawJson
                        detailFetchedAt = outcome.fetchedAt
                    }
                    is LepaoRunHistory.DetailOutcome.Failure -> detailError = outcome.message
                }
            }
        }
    }

    DisposableEffect(row.name, row.session) { onDispose { active = false } }
    LaunchedEffect(row.name, row.session) {
        if (row.hasSession) {
            history(reset = true)
            while (true) {
                refreshStats()
                delay(60_000)
            }
        }
    }

    Column {
        HorizontalDivider()
        Text(stringResource(R.string.lepao_data_refresh_policy), fontSize = 11.sp)
        Text(stringResource(R.string.lepao_data_server_time, time(row.statsFetchedAt)), fontSize = 11.sp)
        Text(stringResource(R.string.lepao_data_rule_time, time(row.ruleCapturedAt)), fontSize = 11.sp)
        if (statsMessage.isNotBlank()) Text(statsMessage, fontSize = 11.sp)
        if (row.statsError.isNotBlank()) Text(stringResource(R.string.lepao_data_failed, row.statsError), fontSize = 11.sp)
        TextButton(enabled = !statsBusy, onClick = { refreshStats() }) {
            Text(stringResource(if (statsBusy) R.string.lepao_data_loading else R.string.lepao_data_refresh))
        }
        Text(stringResource(R.string.lepao_data_run_time), fontSize = 12.sp)
        Text(stringResource(R.string.lepao_rules_host_notice), fontSize = 11.sp)
        TextButton(enabled = row.hasSession && !ruleBusy, onClick = {
            ruleBusy = true
            ruleMessage = ""
            viewModel.refreshLepaoRules(row.name) { result ->
                if (active) { ruleBusy = false; ruleMessage = result }
            }
        }) { Text(stringResource(if (ruleBusy) R.string.lepao_data_loading else R.string.lepao_rules_read)) }
        if (ruleMessage.isNotBlank()) Text(ruleMessage, fontSize = 11.sp)
        if (row.ruleError.isNotBlank()) Text(stringResource(R.string.lepao_rules_failed, row.ruleError), fontSize = 11.sp)
        if (row.ruleAttemptAt > 0L) Text(stringResource(R.string.lepao_rules_attempt, time(row.ruleAttemptAt)), fontSize = 11.sp)
        if (row.ruleSource.isNotBlank()) Text(stringResource(R.string.lepao_rules_source, row.ruleSource), fontSize = 11.sp)
        listOf("running_time", "run_start_time", "run_end_time", "week_time", "term_date", "term_start_time", "term_end_time", "time_rule_arr").forEach { key ->
            if (rule?.has(key) == true) LepaoRunFields(key, rule.opt(key))
        }
        if (rule == null || listOf("running_time", "run_start_time", "run_end_time", "week_time", "term_date", "term_start_time", "term_end_time", "time_rule_arr").none { rule.has(it) }) Text(missing, fontSize = 11.sp)
        Text(stringResource(R.string.lepao_data_judgement_notice), fontSize = 11.sp)
        if (rule != null) LepaoRunFields(stringResource(R.string.lepao_data_all_rules), rule)
        if (stats != null) LepaoRunFields(stringResource(R.string.lepao_data_all_stats), stats)
        HorizontalDivider()
        Text(stringResource(R.string.lepao_data_history), fontSize = 13.sp)
        OutlinedTextField(
            value = yearInput, onValueChange = { yearInput = it }, enabled = !busy,
            label = { Text(stringResource(R.string.lepao_data_year)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row {
            TextButton(enabled = !busy, onClick = { history(reset = true) }) { Text(stringResource(R.string.lepao_data_refresh_history)) }
            TextButton(enabled = !busy && hasMore && page > 0, onClick = { history(reset = false) }) { Text(stringResource(R.string.lepao_data_more)) }
        }
        if (loadedYear.isNotBlank()) Text("$loadedYear · ${time(fetchedAt)}", fontSize = 11.sp)
        if (message.isNotBlank()) Text(message, fontSize = 11.sp)
        records.forEach { record ->
            Text("${record.name} · ${record.startTime}", fontSize = 12.sp)
            Text("distance=${record.distance} km · rate=${record.pace} · used_time=${record.usedTime} s", fontSize = 10.sp)
            TextButton(enabled = !detailBusy, onClick = { detail(record) }) {
                Text(stringResource(R.string.lepao_data_record_detail, record.recordId.toString()))
            }
        }
        if (detailRecord != null) {
            HorizontalDivider()
            Text(stringResource(R.string.lepao_data_record_detail, detailRecord!!.recordId.toString()))
            if (detailBusy) Text(stringResource(R.string.lepao_data_loading))
            if (detailError.isNotBlank()) Text(stringResource(R.string.lepao_data_failed, detailError))
            val root = remember(detailJson) { LepaoRunData.objectOrNull(detailJson) }
            val body = root?.optJSONObject("detail") ?: root
            if (body != null) {
                Text(stringResource(R.string.lepao_data_detail_time, time(detailFetchedAt)), fontSize = 11.sp)
                listOf("run_zone_name", "distance", "start_time", "end_time", "time", "record_status", "record_failed_reason", "complain_check_status", "step_num", "log_num", "capture_num", "zfb_face_verify_num").forEach { key ->
                    LepaoRunFields(key, if (body.has(key)) body.opt(key) else missing)
                }
                Text(stringResource(R.string.lepao_data_judgement_notice), fontSize = 11.sp)
                LepaoRunFields(stringResource(R.string.lepao_data_all_detail), root)
            }
            val summary = remember(detailRecord) { LepaoRunData.objectOrNull(detailRecord!!.rawJson) }
            if (summary != null) LepaoRunFields(stringResource(R.string.lepao_data_all_summary), summary)
        }
    }
}
