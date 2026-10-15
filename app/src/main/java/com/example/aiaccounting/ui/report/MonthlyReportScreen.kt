package com.example.aiaccounting.ui.report

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.aiaccounting.data.local.EntryEntity
import com.example.aiaccounting.domain.ai.MonthlyReportState
import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.ui.common.MonthSelector
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlyReportScreen(s: MonthlyReportState, onMove: (Long) -> Unit, onMonth: (YearMonth) -> Unit,
                        onGenerate: () -> Unit, onCancel: () -> Unit, onEdit: (EntryEntity) -> Unit) {
    var evidence by remember { mutableStateOf<ReportConclusion?>(null) }
    val report = s.report
    LaunchedEffect(report?.fingerprint) { evidence = null }
    LazyColumn(Modifier.fillMaxSize().testTag("report-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { MonthSelector(s.month, onMove, onMonth) }
        if (report == null) item { CircularProgressIndicator() }
        else {
            item {
                ReportCard {
                    Text("${s.month.monthValue} 月账单", style = MaterialTheme.typography.headlineSmall)
                    Text("${report.from} 至 ${report.to}（含起止日）", style = MaterialTheme.typography.bodySmall)
                    if (!report.available) Text("这个月份尚未开始", modifier = Modifier.testTag("report-future"))
                    Text("支出 ¥ ${Money.format(report.expense)}", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("report-expense"))
                    Text("收入 ¥ ${Money.format(report.income)}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("report-income"))
                    Text("净收支 ¥ ${Money.format(report.net)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("report-net"))
                    Text("${report.rows.size} 笔记录 · 净收支为收入减支出，不是账户余额")
                    TextButton(onClick = { evidence = ReportConclusion(ReportClaimKind.OVERVIEW) }) { Text("查看本期全部账目") }
                }
            }
            item { Text("主要支出类别", style = MaterialTheme.typography.titleMedium) }
            if (report.categories.isEmpty()) item { Text("本期没有支出记录") }
            items(report.categories, key = { "report-category-${it.id}" }) { c ->
                Card(onClick = { evidence = ReportConclusion(ReportClaimKind.CATEGORY, c.id) }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("report-category-${c.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text("¥ ${Money.format(c.cents)} · ${c.percent}% · ${c.count} 笔")
                        LinearProgressIndicator(progress = { c.cents.toFloat() / report.expense }, modifier = Modifier.fillMaxWidth(), gapSize = 0.dp, drawStopIndicator = {})
                        Text("查看分类明细", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { Text("最大的 ${report.largest.size} 笔支出（最多 5 笔）", style = MaterialTheme.typography.titleMedium) }
            if (report.largest.isEmpty()) item { Text("本期没有大额支出可列出") }
            items(report.largest, key = { "report-top-${it.id}" }) { e ->
                Card(onClick = { onEdit(e) }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("report-top-${e.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${e.date} · ${report.categoryNames[e.categoryId]}", style = MaterialTheme.typography.bodySmall)
                        Text("¥ ${Money.format(e.cents)}", style = MaterialTheme.typography.titleLarge)
                        Text(e.note.ifBlank { "未填写备注" })
                        Text("查看原记录", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                ReportCard {
                    Text("AI 月度总结", style = MaterialTheme.typography.titleLarge)
                    Text("生成总结会将本期收支和分类汇总、最大的 5 笔支出（含备注）及对照期分类汇总发送给硅基流动，使用账户额度。Key 不进入报告或备份。", style = MaterialTheme.typography.bodySmall)
                    if (s.stale) Text("账目或报告范围已变化，报告需要更新", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("report-stale"))
                    if (s.loadingCache) Text("正在读取本地报告…")
                    s.saved?.let { saved ->
                        Text("已保存于 " + runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(saved.generatedAt)) }.getOrDefault(saved.generatedAt), style = MaterialTheme.typography.bodySmall)
                    }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("report-error")) }
                    if (s.generating) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在整理有依据的结论…")
                        OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("report-cancel")) { Text("取消生成") }
                    } else Button(onClick = onGenerate, enabled = !s.loadingCache && report.available && report.rows.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("report-generate")) {
                        Text(if(s.saved != null || s.stale) "重新生成 AI 总结" else "生成 AI 总结")
                    }
                    if (report.rows.isEmpty()) Text("先记录本期账目，再生成 AI 总结。")
                    Text("总结保存在本机，重新打开不重复调用。金额和结论依据会在本地校验，账目改动后需手动更新。", style = MaterialTheme.typography.bodySmall)
                }
            }
            s.saved?.conclusions?.forEachIndexed { index, claim -> item(key = "report-claim-$index") {
                Card(onClick = { evidence = claim }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("report-claim-$index")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(report.conclusionText(claim), style = MaterialTheme.typography.bodyLarge)
                        if (claim.kind == ReportClaimKind.CATEGORY_CHANGE) report.comparison?.let { result ->
                            Text("本期 ${report.from} 至 ${report.to}\n对照 ${result.query.baselineFromDate} 至 ${result.query.baselineToDate}", style = MaterialTheme.typography.bodySmall)
                            if (result.comparison!!.currentDays != result.comparison!!.baselineDays) Text("两期天数不同，比较总额，未折算日均。", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("查看依据", color = MaterialTheme.colorScheme.primary)
                    }
                }
            } }
        }
    }
    if (report != null) evidence?.let { claim ->
        val rows = report.evidence(claim)
        ModalBottomSheet(onDismissRequest = { evidence = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
                Text("结论依据 · ${rows.size} 笔", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("report-evidence-title"))
                Text(report.conclusionText(claim), modifier = Modifier.padding(vertical = 10.dp))
                LazyColumn(Modifier.weight(1f).testTag("report-evidence-list"), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (rows.isEmpty()) item { Text("此范围没有记录") }
                    items(rows, key = { it.id }) { e ->
                        Card(onClick = { evidence = null; onEdit(e) }, modifier = Modifier.fillMaxWidth().testTag("report-evidence-${e.id}")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val period = if(claim.kind == ReportClaimKind.CATEGORY_CHANGE) if(report.comparison!!.query.inBaselinePeriod(e.date)) "对照期 · " else "本期 · " else ""
                                Text("$period${e.date} · ${report.categoryNames[e.categoryId]}")
                                Text("${e.kind.label} ¥ ${Money.format(e.cents)}", style = MaterialTheme.typography.titleMedium)
                                Text(e.note.ifBlank { "未填写备注" })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
