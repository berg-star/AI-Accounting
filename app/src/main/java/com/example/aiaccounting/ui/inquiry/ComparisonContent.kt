package com.example.aiaccounting.ui.inquiry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.aiaccounting.domain.ai.ComparisonExplanation
import com.example.aiaccounting.domain.model.*
import kotlin.math.abs

internal fun differenceText(delta: Long) = when {
    delta > 0 -> "多花 ¥ ${Money.format(delta)}"
    delta < 0 -> "少花 ¥ ${Money.format(-delta)}"
    else -> "支出持平"
}

@Composable
internal fun ComparisonAmounts(result: QueryResult, c: SpendingComparison) {
    val q = result.query
    Text("本期 ¥ ${Money.format(c.current)} · ${c.currentCount} 笔", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("comparison-current"))
    Text("${q.fromDate} 至 ${q.toDate}（${c.currentDays} 天）", style = MaterialTheme.typography.bodySmall)
    Text("对照 ¥ ${Money.format(c.baseline)} · ${c.baselineCount} 笔", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("comparison-baseline"))
    Text("${q.baselineFromDate} 至 ${q.baselineToDate}（${c.baselineDays} 天）", style = MaterialTheme.typography.bodySmall)
    val change = when {
        c.baseline == 0L && c.current == 0L -> "两期均为 0，无百分比变化"
        c.baseline == 0L -> "对照期为 0，不计算增长百分比"
        c.delta == 0L -> "变化 0.0%"
        else -> "${if(c.delta > 0) "增加" else "减少"} ${c.percent!!.removePrefix("-")}%"
    }
    Text(change, modifier = Modifier.testTag("comparison-percent"))
    if (c.currentDays != c.baselineDays) Text("两期天数不同，当前比较的是总额，未按天数折算。", color = MaterialTheme.colorScheme.error)
    Text("本月默认截至今天对比上月同期；明确指定整月时按整月。请核对两期日期。", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun ComparisonCategoryCard(row: CategoryChange, onDetails: (Long) -> Unit) {
    Card(onClick = { onDetails(row.categoryId) }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("comparison-category-${row.categoryId}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${row.name} · ${differenceText(row.delta)}", style = MaterialTheme.typography.titleMedium)
            val maximum = maxOf(row.current, row.baseline, 1).toFloat()
            Text("本期 ¥ ${Money.format(row.current)} · ${row.currentCount} 笔", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { row.current / maximum }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            Text("对照 ¥ ${Money.format(row.baseline)} · ${row.baselineCount} 笔", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { row.baseline / maximum }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary)
            Text("查看这类账目", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun ComparisonExplanationCard(c: SpendingComparison, explanation: ComparisonExplanation?, pendingIssues: Boolean, onDetails: (Long) -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("comparison-explanation")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 变化解读", style = MaterialTheme.typography.titleMedium)
            when {
                explanation == null -> Text("本地对比已显示，AI 解读将在明细复核后生成。未完成时不视为通过。")
                explanation.unchanged -> Text(if (c.categories.isEmpty()) "两期都没有纳入统计的支出记录。" else "各分类支出金额均持平。")
                else -> {
                    Text("${differenceText(c.delta)}，由下列分类的增减共同影响：")
                    Text("笔数顺序：对照 → 本期", style = MaterialTheme.typography.bodySmall)
                    for ((title, ids) in listOf("增加较多" to explanation.increased, "减少较多" to explanation.decreased)) {
                        if (ids.isNotEmpty()) Text(title, style = MaterialTheme.typography.labelLarge)
                        ids.forEach { id -> c.categories.firstOrNull { it.categoryId == id }?.let { row ->
                            TextButton(onClick = { onDetails(id) }, contentPadding = PaddingValues(0.dp)) {
                                Text("${row.name}：${if(row.delta > 0) "+" else "−"} ¥ ${Money.format(abs(row.delta))}，${row.baselineCount} → ${row.currentCount} 笔")
                            }
                        } }
                    }
                    Text("AI 选择关注分类，以上金额和笔数均由本机计算；完整分类变化列在下方。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (pendingIssues) Text("存在待核对提示，当前解读仅供参考。", color = MaterialTheme.colorScheme.error)
            Text("记录只能说明已记支出的变化，不能据此判断涨价、浪费或个人生活原因。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
