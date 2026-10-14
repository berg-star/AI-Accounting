package com.example.aiaccounting.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aiaccounting.data.local.EntryEntity
import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.ui.LedgerUiState
import com.example.aiaccounting.ui.common.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HomeScreen(state: LedgerUiState, onMoveMonth: (Long) -> Unit, onMonth: (YearMonth) -> Unit, onEdit: (EntryEntity) -> Unit, onClearFilter: () -> Unit) {
    val rows = state.data.monthly(state.month, state.filter)
    val categories = state.data.categories.associateBy { it.id }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
        item { MonthSelector(state.month,onMoveMonth,onMonth) }
        item { MonthlySummary(state.data.totals(state.month));Spacer(Modifier.height(16.dp)) }
        if (state.filter != null) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(categories[state.filter]?.name ?: "分类明细", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearFilter) { Text("全部记录") }
            }
        }
        if (rows.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryGlyph("study","blue",Modifier.size(56.dp))
                Text(if (state.filter == null) "这个月还没有记录" else "这个分类还没有记录", style = MaterialTheme.typography.titleMedium)
                Text("点“记一笔”，记下你的日常花销", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        rows.groupBy { it.date }.forEach { (date, dayRows) ->
            item(key = "day-$date") {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp,bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    val day = LocalDate.parse(date)
                    Text("${day.monthValue}月${day.dayOfMonth}日", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("支出 ${Money.format(dayRows.filter { it.kind == EntryKind.EXPENSE }.sumOf { it.cents })}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val income = dayRows.filter { it.kind == EntryKind.INCOME }.sumOf { it.cents }
                        if (income > 0) Text("收入 ${Money.format(income)}", style = MaterialTheme.typography.labelMedium, color = categoryColor("green"))
                    }
                }
            }
            dayRows.forEachIndexed { index, entry ->
                item(key = "entry-${entry.id}") {
                    val c = categories[entry.categoryId]
                    val shape = RoundedCornerShape(topStart = if(index==0) 18.dp else 0.dp, topEnd = if(index==0) 18.dp else 0.dp, bottomStart = if(index==dayRows.lastIndex) 18.dp else 0.dp, bottomEnd = if(index==dayRows.lastIndex) 18.dp else 0.dp)
                    Surface(color = MaterialTheme.colorScheme.surface, shape = shape) {
                        Column {
                            Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { onEdit(entry) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CategoryGlyph(c?.icon ?: "other",c?.color ?: "blue")
                                Column(Modifier.weight(1f)) {
                                    Text(c?.name ?: "分类", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    if (entry.note.isNotEmpty()) Text(entry.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${if(entry.kind==EntryKind.INCOME) "+" else "−"}${Money.format(entry.cents)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = if(entry.kind==EntryKind.INCOME) categoryColor("green") else MaterialTheme.colorScheme.onSurface, modifier = Modifier.widthIn(max = 145.dp))
                            }
                            if (index < dayRows.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
