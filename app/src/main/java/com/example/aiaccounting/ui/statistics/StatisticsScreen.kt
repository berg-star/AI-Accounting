package com.example.aiaccounting.ui.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.ui.LedgerUiState
import com.example.aiaccounting.ui.common.*
import java.time.YearMonth
import java.util.Locale

@Composable
fun StatisticsScreen(state: LedgerUiState, onMove: (Long) -> Unit, onMonth: (YearMonth) -> Unit, onKind: (EntryKind) -> Unit, onCategory: (Long) -> Unit, onReport: (() -> Unit)? = null) {
    val rows=state.data.monthly(state.month).filter { it.kind==state.statisticsKind }
    val total=rows.sumOf { it.cents }
    val ranks=state.data.categories.filter { it.kind==state.statisticsKind }.map { c -> c to rows.filter { it.categoryId==c.id }.sumOf { it.cents } }.filter { it.second>0 }.sortedByDescending { it.second }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=96.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { MonthSelector(state.month,onMove,onMonth);KindSelector(state.statisticsKind,!state.busy,onKind) }
        if (onReport != null) item { OutlinedButton(onClick = onReport, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("open-report")) { Text("查看月度报告") } }
        item { Card(shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("本月${state.statisticsKind.label}",color=MaterialTheme.colorScheme.onSurfaceVariant);Text("¥ ${Money.format(total)}",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Medium);Text("${rows.size} 笔记录 · 人民币",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } } }
        item { Text("分类排行",style=MaterialTheme.typography.titleMedium) }
        if(ranks.isEmpty()) item { Text("这个月还没有${state.statisticsKind.label}记录",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth().padding(vertical=48.dp)) }
        ranks.forEach { (c,cents) -> item(key=c.id) {
            Card(onClick={onCategory(c.id)},enabled=!state.busy,shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) { CategoryGlyph(c.icon,c.color);Text(c.name+if(!c.active) "（已停用）" else "",modifier=Modifier.weight(1f));Text(Money.format(cents),fontWeight=FontWeight.Medium,modifier=Modifier.widthIn(max=140.dp)) }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("${rows.count { it.categoryId==c.id }} 笔",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(String.format(Locale.CHINA,"%.1f%%",100.0*cents/total),style=MaterialTheme.typography.bodySmall) }
                    LinearProgressIndicator(progress={cents.toFloat()/total},modifier=Modifier.fillMaxWidth().height(6.dp),color=categoryColor(c.color),trackColor=MaterialTheme.colorScheme.surfaceContainerHighest,gapSize=0.dp,drawStopIndicator={})
                }
            }
        } }
    }
}
