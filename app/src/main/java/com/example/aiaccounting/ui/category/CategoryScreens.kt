package com.example.aiaccounting.ui.category

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.aiaccounting.data.local.CategoryEntity
import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.ui.LedgerUiState
import com.example.aiaccounting.ui.common.*

@Composable
fun CategoryScreen(state: LedgerUiState, onKind: (EntryKind) -> Unit, onEdit: (CategoryEntity) -> Unit, onSorting: () -> Unit, onArchived: () -> Unit, onMove: (Long, Int) -> Unit, onAdd: () -> Unit) {
    val active=state.data.categories.filter { it.kind==state.categoryKind&&it.active }
    val archived=state.data.categories.filter { it.kind==state.categoryKind&&!it.active }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { KindSelector(state.categoryKind,!state.busy,onKind) }
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) { Text("我的${state.categoryKind.label}分类",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));TextButton(onClick=onSorting,enabled=!state.busy){Text(if(state.sorting) "完成排序" else "调整顺序")} } }
        active.forEachIndexed { index,c -> item(key=c.id) { CategoryRow(c,state.data.usage(c.id),state.sorting,!state.busy,index>0,index<active.lastIndex,{onEdit(c)},{onMove(c.id,-1)},{onMove(c.id,1)}) } }
        item { TextButton(onClick=onArchived,modifier=Modifier.fillMaxWidth(),enabled=!state.busy){Text(if(state.showArchived) "收起已停用分类（${archived.size}）" else "已停用分类（${archived.size}）")} }
        if(state.showArchived) {
            if(archived.isEmpty()) item { Text("没有已停用分类",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(12.dp)) }
            archived.forEachIndexed { index,c -> item(key=c.id) { CategoryRow(c,state.data.usage(c.id),state.sorting,!state.busy,index>0,index<archived.lastIndex,{onEdit(c)},{onMove(c.id,-1)},{onMove(c.id,1)}) } }
        }
        item { FilledTonalButton(onClick=onAdd,enabled=!state.busy,modifier=Modifier.fillMaxWidth().padding(top=12.dp)){Text("新增分类")} }
    }
}

@Composable
private fun CategoryRow(c: CategoryEntity, usage: Int, sorting: Boolean, enabled: Boolean, canUp: Boolean, canDown: Boolean, onEdit: () -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),shape=RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onEdit,enabled=enabled,modifier=Modifier.weight(1f),contentPadding=PaddingValues(12.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    CategoryGlyph(c.icon,c.color)
                    Column(Modifier.weight(1f)) { Text(c.name,color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.bodyLarge);Text("$usage 笔历史记录${if(c.fallback) " · 兜底分类" else ""}${if(!c.active) " · 已停用" else ""}",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall) }
                }
            }
            if(sorting) {
                IconButton(onClick=onUp,enabled=enabled&&canUp){Icon(Icons.Default.KeyboardArrowUp,"上移${c.name}")}
                IconButton(onClick=onDown,enabled=enabled&&canDown){Icon(Icons.Default.KeyboardArrowDown,"下移${c.name}")}
            }
        }
    }
}

@Composable
fun CategoryEditor(state: LedgerUiState, onChange: ((CategoryDraft) -> CategoryDraft) -> Unit, onActive: () -> Unit, onDelete: () -> Unit) {
    val d=state.category
    val existing=state.data.categories.find { it.id==d.id }
    val labels=listOf("餐饮","交通","购物","居住","娱乐","医疗","学习","咖啡","工作","礼物","钱包","其他")
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { OutlinedTextField(d.name,{ value -> onChange { it.copy(name=value.take(12)) } },modifier=Modifier.fillMaxWidth(),label={Text("分类名称")},placeholder={Text("例如：咖啡")},singleLine=true,enabled=!state.busy,supportingText={Text("${d.name.length}/12 · ${d.kind.label}分类")}) }
        item { Text("选择图标",style=MaterialTheme.typography.titleMedium) }
        categoryIcons.chunked(4).forEach { row -> item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach { icon ->
                    val selected=d.icon==icon
                    OutlinedCard(onClick={onChange { it.copy(icon=icon) }},enabled=!state.busy,modifier=Modifier.weight(1f).semantics { this.selected=selected },shape=RoundedCornerShape(14.dp),colors=CardDefaults.outlinedCardColors(containerColor=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(vertical=10.dp,horizontal=2.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) { CategoryGlyph(icon,d.color);Text(labels[categoryIcons.indexOf(icon)],style=MaterialTheme.typography.labelMedium);if(selected)SelectedCheck() else Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        } }
        item {
            Text("颜色",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(bottom=8.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                categoryColors.forEachIndexed { index,color ->
                    OutlinedButton(onClick={onChange { it.copy(color=color) }},enabled=!state.busy,modifier=Modifier.weight(1f).semantics { selected=d.color==color },contentPadding=PaddingValues(horizontal=2.dp,vertical=10.dp),border=BorderStroke(1.dp,if(d.color==color)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Text(listOf("蓝","绿","橙","紫")[index],color=categoryColor(color));if(d.color==color)SelectedCheck()
                    }
                }
            }
        }
        if(existing!=null) item {
            Text("${state.data.usage(existing.id)} 笔历史记录${if(existing.fallback) " · 兜底分类需保留" else ""}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(!existing.fallback) {
                TextButton(onClick=onActive,enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text(if(existing.active) "停用分类" else "恢复分类") }
                TextButton(onClick=onDelete,enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text("删除分类",color=MaterialTheme.colorScheme.error) }
                Text("停用后不用于新记账，历史明细与统计仍保留。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
