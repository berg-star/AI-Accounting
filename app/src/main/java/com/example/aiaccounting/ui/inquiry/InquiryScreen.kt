package com.example.aiaccounting.ui.inquiry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.aiaccounting.data.local.EntryEntity
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*

@Composable
fun InquiryScreen(s: InquiryState, onQuestion: (String) -> Unit, onAsk: () -> Unit, onCancel: () -> Unit,
                  onReview: () -> Unit, onExclude: (Long) -> Unit, onEdit: (EntryEntity) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    var exclusion by remember { mutableStateOf<EntryEntity?>(null) }
    var onlyFlagged by rememberSaveable { mutableStateOf(false) }
    val ask = { keyboard?.hide(); onAsk() }
    val result = s.result
    val listState = rememberLazyListState()
    LaunchedEffect(result?.query, s.askedQuestion) { if(result!=null)listState.animateScrollToItem(2) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("inquiry-list"), state=listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("用一句话查账", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(s.question, onQuestion, enabled = !s.understanding, label = { Text("想查什么？") },
                    placeholder = { Text("这个月坐地铁花了多少？") }, minLines = 2, maxLines = 5,
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("inquiry-question"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { ask() }),
                    supportingText = { Text("${s.question.length}/${InquiryLimits.QUESTION_LENGTH}") })
            }
            if (result == null && !s.understanding) item {
                Column {
                    Text("试着问", style = MaterialTheme.typography.labelLarge)
                    listOf("这个月坐地铁花了多少？", "上个月最大的一笔支出是什么？", "最近七天买咖啡的记录有哪些？").forEach { example ->
                        TextButton(onClick = { onQuestion(example) }) { Text(example) }
                    }
                }
            }
            item {
                Text("理解问题时发送问题、日期和分类。复核时另发送本次命中记录的日期、金额、分类、备注和本次排除状态，不发送其他历史账目。金额始终由手机计算，AI 复核会消耗账户额度。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("inquiry-error")) } }
            if (s.understanding) item { Text("正在理解问题…"); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
            if (result != null) {
                item {
                    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().testTag("inquiry-result")) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(s.askedQuestion, style = MaterialTheme.typography.titleMedium)
                            Text(result.query.metric.label, style = MaterialTheme.typography.labelLarge)
                            val main = when (result.query.metric) {
                                QueryMetric.SUM -> if (result.query.kind != null) "¥ ${Money.format(if (result.query.kind == EntryKind.EXPENSE) result.expense else result.income)}" else "支出 ¥ ${Money.format(result.expense)}\n收入 ¥ ${Money.format(result.income)}"
                                QueryMetric.COUNT, QueryMetric.LIST -> "${result.included.size} 笔记录"
                                QueryMetric.MAX -> result.featured.firstOrNull()?.let { "¥ ${Money.format(it.cents)}" } ?: "没有符合条件的记录"
                                QueryMetric.TOP -> "金额最高的 ${result.featured.size} 笔"
                            }
                            Text(main, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("inquiry-answer"))
                            Text("命中 ${result.rows.size} 笔 · 纳入 ${result.included.size} 笔 · 本次排除 ${result.excludedIds.size} 笔")
                            if (result.query.metric == QueryMetric.MAX && result.featured.size > 1) Text("最高金额并列 ${result.featured.size} 笔，明细已标出。")
                            if (result.query.metric == QueryMetric.TOP) Text("同金额按日期和记录顺序倒序排列，全部候选明细仍列在下方。")
                            if (result.included.isEmpty()) Text("没有找到符合条件且纳入统计的记录。")
                        }
                    }
                }
                item {
                    val q = result.query
                    Text("查询范围", style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(
                        if (q.fromDate == null) "全部历史" else "${q.fromDate} 至 ${q.toDate}（含起止日）",
                        q.kind?.label ?: "全部收支",
                        q.categoryIds.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "分类：") { result.categoryNames[it].orEmpty() },
                        q.keywords.takeIf { it.isNotEmpty() }?.joinToString(" / ", prefix = "备注含任一："),
                        q.minCents?.let { "金额 ≥ ¥ ${Money.format(it)}" }, q.maxCents?.let { "金额 ≤ ¥ ${Money.format(it)}" },
                    ).joinToString("\n"), modifier = Modifier.padding(top = 6.dp).testTag("inquiry-scope"))
                    Text("只统计已记录且符合以上条件的账目，已删除记录不计入。备注没有写明用途的记录可能无法命中。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                item {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("inquiry-review")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("AI 复核", style = MaterialTheme.typography.titleMedium)
                            val status = when (s.reviewStatus) {
                                ReviewStatus.NOT_STARTED -> "尚未复核"
                                ReviewStatus.RUNNING -> "正在复核：${s.checked}/${result.rows.size} 笔，${s.completedBatches}/${s.totalBatches} 批"
                                ReviewStatus.COMPLETE -> when {
                                    s.flags.isNotEmpty() -> "已复核 ${s.checked} 笔，有 ${s.flags.size} 笔待核对"
                                    s.warnings.isNotEmpty() -> "已复核 ${s.checked} 笔，查询范围有待核对"
                                    else -> "已复核 ${s.checked} 笔，未发现明显问题"
                                }
                                ReviewStatus.FAILED -> "复核失败，已完成 ${s.checked}/${result.rows.size} 笔"
                                ReviewStatus.CANCELLED -> "复核已取消，已完成 ${s.checked}/${result.rows.size} 笔"
                                ReviewStatus.OUTDATED -> "结果已更新，需要重新复核"
                            }
                            Text(status, modifier = Modifier.testTag("inquiry-review-status"))
                            if (s.reviewStatus == ReviewStatus.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                            s.reviewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            s.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (s.flags.isNotEmpty()) Text("疑问原因标在对应明细中。非完整复核状态下的提示仅供参考。")
                            if (s.reviewStatus == ReviewStatus.RUNNING) TextButton(onClick = onCancel) { Text("取消复核") }
                            else TextButton(onClick = onReview, modifier = Modifier.testTag("inquiry-review-retry")) { Text("重新复核") }
                            Text("复核只检查本次结果的语义，不保证账本没有漏记或统计范围没有遗漏。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Text("全部命中明细（${result.rows.size} 笔）", style = MaterialTheme.typography.titleMedium)
                    if (s.flags.isNotEmpty()) FilterChip(selected = onlyFlagged, onClick = { onlyFlagged = !onlyFlagged }, label = { Text("只看疑问记录（${s.flags.size}）") })
                }
                val featured = result.featured.map { it.id }.toSet()
                val flags = s.flags.associate { it.entryId to it.reason }
                val rows = if (onlyFlagged && flags.isNotEmpty()) result.rows.filter { it.id in flags } else result.rows
                items(rows, key = { "inquiry-entry-${it.id}" }) { e ->
                    val excluded = e.id in result.excludedIds
                    Card(onClick = { onEdit(e) }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().testTag("inquiry-entry-${e.id}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${e.date} · ${result.categoryNames[e.categoryId]}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${if (e.kind == EntryKind.EXPENSE) "−" else "+"} ¥ ${Money.format(e.cents)}", style = MaterialTheme.typography.titleLarge)
                            Text(e.note.ifBlank { "未填写备注" })
                            if (e.id in featured) Text(if (result.query.metric == QueryMetric.MAX) "最高金额" else "金额前 ${result.query.limit} 笔", color = MaterialTheme.colorScheme.primary)
                            if (excluded) Text("已从本次统计排除 · 原账目保留", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            flags[e.id]?.let { Text("AI 提示：$it", color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = { exclusion = e }, modifier = Modifier.testTag("inquiry-exclude-${e.id}")) { Text(if (excluded) "重新纳入本次统计" else "仅从本次统计排除") }
                        }
                    }
                }
            }
        }
        Surface(shadowElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                if (s.understanding) OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消查账") }
                else Button(onClick = ask, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("inquiry-ask")) { Text("开始查账") }
            }
        }
    }
    exclusion?.let { entry ->
        val excluded = result?.excludedIds?.contains(entry.id) == true
        AlertDialog(onDismissRequest = { exclusion = null }, title = { Text(if (excluded) "重新纳入这笔记录？" else "仅从本次统计排除？") },
            text = { Text("${entry.note.ifBlank { "这笔记录" }}\n账本中的原记录不会改变，当前结果会重新计算，AI 复核需要重新进行。") },
            confirmButton = { TextButton(onClick = { onExclude(entry.id); exclusion = null }) { Text(if (excluded) "确认纳入" else "确认排除") } },
            dismissButton = { TextButton(onClick = { exclusion = null }) { Text("取消") } })
    }
}
