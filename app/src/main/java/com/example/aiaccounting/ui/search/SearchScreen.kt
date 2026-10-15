package com.example.aiaccounting.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.aiaccounting.data.local.EntryEntity
import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.ui.LedgerUiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(s: LedgerUiState, onChange: ((SearchFilters) -> SearchFilters) -> Unit, onKind: (EntryKind?) -> Unit, onReset: () -> Unit, onEdit: (EntryEntity) -> Unit, onInquiry: (() -> Unit)? = null) {
    val filters = s.search
    val rows = remember(s.data, filters) { s.data.search(filters) }
    val categories = s.data.categories.associateBy { it.id }
    val keyboard = LocalSoftwareKeyboardController.current
    var expanded by rememberSaveable { mutableStateOf(filters.hasConditions) }
    var categoryMenu by remember { mutableStateOf(false) }
    var dateSide by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("search-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if(onInquiry!=null) item {
            FilledTonalButton(onClick=onInquiry,enabled=!s.busy,modifier=Modifier.fillMaxWidth().testTag("open-inquiry")) { Text("用一句话查账") }
        }
        item {
            OutlinedTextField(filters.keyword, { value -> onChange { it.copy(keyword = value.take(100)) } },
                enabled = !s.busy, singleLine = true, label = { Text("搜索备注或分类") }, placeholder = { Text("例如：牛肉面、地铁、某家店") },
                modifier = Modifier.fillMaxWidth().testTag("search-keyword"), shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }))
            Text("搜索全部历史记录，包括已停用分类；已删除记录不显示。", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起筛选" else if (filters.hasConditions) "筛选条件 · 已设置" else "展开筛选") }
                TextButton(onClick = onReset, enabled = !s.busy) { Text("清空条件") }
            }
        }
        if (expanded) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null, EntryKind.EXPENSE, EntryKind.INCOME).forEach { kind ->
                        FilterChip(selected = filters.kind == kind, onClick = { onKind(kind) }, enabled = !s.busy, label = { Text(kind?.label ?: "全部收支") })
                    }
                }
            }
            item {
                ExposedDropdownMenuBox(expanded = categoryMenu, onExpandedChange = { categoryMenu = it }) {
                    OutlinedTextField(categories[filters.categoryId]?.let { it.name + if (!it.active) "（已停用）" else "" } ?: "全部分类", {}, readOnly = true,
                        enabled = !s.busy, label = { Text("分类") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                    ExposedDropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        DropdownMenuItem(text = { Text("全部分类") }, onClick = { categoryMenu = false; onChange { it.copy(categoryId = null) } })
                        s.data.categories.filter { filters.kind == null || it.kind == filters.kind }.forEach { c ->
                            DropdownMenuItem(text = { Text("${c.kind.label} · ${c.name}${if (!c.active) "（已停用）" else ""}") },
                                onClick = { categoryMenu = false; onChange { it.copy(categoryId = c.id) } })
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("日期范围", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { dateSide = "from" }, modifier = Modifier.weight(1f), enabled = !s.busy) { Text(filters.fromDate.ifBlank { "开始日期" }) }
                        OutlinedButton(onClick = { dateSide = "to" }, modifier = Modifier.weight(1f), enabled = !s.busy) { Text(filters.toDate.ifBlank { "结束日期" }) }
                    }
                    if (filters.fromDate.isNotEmpty() || filters.toDate.isNotEmpty()) TextButton(onClick = { onChange { it.copy(fromDate = "", toDate = "") } }) { Text("不限日期") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(filters.minAmount, { value -> onChange { it.copy(minAmount = value.take(16)) } }, label = { Text("最低金额（元）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f).testTag("search-min"), enabled = !s.busy)
                    OutlinedTextField(filters.maxAmount, { value -> onChange { it.copy(maxAmount = value.take(16)) } }, label = { Text("最高金额（元）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f).testTag("search-max"), enabled = !s.busy)
                }
            }
        }
        val error = filters.error()
        item {
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            else {
                Text("找到 ${rows.size} 笔记录", modifier = Modifier.testTag("search-count"), style = MaterialTheme.typography.titleMedium)
                Text("支出 ¥ ${Money.format(rows.filter { it.kind == EntryKind.EXPENSE }.sumOf { it.cents })}\n收入 ¥ ${Money.format(rows.filter { it.kind == EntryKind.INCOME }.sumOf { it.cents })}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (error == null && rows.isEmpty()) item { Text("没有符合条件的记录，可以换个关键词或放宽筛选范围。", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        rows.groupBy { it.date }.forEach { (date, records) ->
            item(key = "search-date-$date") { Text(date, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            records.forEach { entry ->
                item(key = "search-entry-${entry.id}") {
                    Card(onClick = { onEdit(entry) }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().testTag("search-entry-${entry.id}"),
                        shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(categories[entry.categoryId]?.name ?: "分类", modifier = Modifier.weight(1f))
                                Text("${if (entry.kind == EntryKind.INCOME) "+" else "−"} ${Money.format(entry.cents)}", style = MaterialTheme.typography.titleMedium)
                            }
                            if (entry.note.isNotEmpty()) Text(entry.note, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
    dateSide?.let { side ->
        val date = if (side == "from") filters.fromDate else filters.toDate
        val millis = runCatching { LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        val picker = rememberDatePickerState(initialSelectedDateMillis = millis, yearRange = 1900..2100)
        DatePickerDialog(onDismissRequest = { dateSide = null }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { selected ->
                val value = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate().toString()
                onChange { if (side == "from") it.copy(fromDate = value) else it.copy(toDate = value) }; dateSide = null
            } }, enabled = picker.selectedDateMillis != null) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { dateSide = null }) { Text("取消") } }) { DatePicker(picker) }
    }
}
