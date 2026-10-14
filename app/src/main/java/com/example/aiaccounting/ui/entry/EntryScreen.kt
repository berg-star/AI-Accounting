package com.example.aiaccounting.ui.entry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.ui.LedgerUiState
import com.example.aiaccounting.ui.common.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(state: LedgerUiState, onChange: ((EntryDraft) -> EntryDraft) -> Unit, onKind: (EntryKind) -> Unit, onManage: () -> Unit, onDelete: () -> Unit) {
    val draft = state.entry
    val original = state.data.entries.find { it.id == draft.id }
    val available = state.data.categories.filter { it.kind == draft.kind && (it.active || it.id == original?.categoryId) }
    var chooseDate by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { KindSelector(draft.kind,!state.busy,onKind) }
        item {
            OutlinedTextField(draft.amount, { value -> onChange { it.copy(amount = value.take(16)) } }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text("金额（元）") }, placeholder = { Text("0.00") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp), shape = RoundedCornerShape(18.dp), isError = draft.amount.isNotBlank() && Money.parse(draft.amount) == null)
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("分类",style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onManage, enabled = !state.busy) { Text("管理分类") }
            }
            available.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c ->
                        val selected = c.id == draft.categoryId
                        OutlinedCard(onClick = { onChange { it.copy(categoryId=c.id) } }, enabled = !state.busy, modifier = Modifier.weight(1f).padding(bottom = 6.dp).semantics { this.selected = selected }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.outlinedCardColors(containerColor = if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp,if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.fillMaxWidth().padding(vertical=10.dp,horizontal=2.dp),horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                CategoryGlyph(c.icon,c.color)
                                Text(c.name,style = MaterialTheme.typography.labelMedium)
                                if(selected) SelectedCheck() else Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                    repeat(4-row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        item {
            OutlinedButton(onClick = { chooseDate=true },modifier=Modifier.fillMaxWidth(),enabled=!state.busy) { Text("日期：${draft.date}") }
        }
        item { OutlinedTextField(draft.note,{ value -> onChange { it.copy(note=value.take(120)) } },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,label={Text("备注（选填）")},placeholder={Text("例如：午饭")},minLines=2,maxLines=4,supportingText={Text("${draft.note.length}/120")}) }
        if (draft.id != null) item { TextButton(onClick = onDelete,enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text("删除记录",color=MaterialTheme.colorScheme.error) } }
    }
    if (chooseDate) {
        val millis = runCatching { LocalDate.parse(draft.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        val dateState=rememberDatePickerState(initialSelectedDateMillis=millis,yearRange=1900..2100)
        DatePickerDialog(onDismissRequest={chooseDate=false},confirmButton={TextButton(onClick={dateState.selectedDateMillis?.let { selected -> val date=Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate().toString();onChange { it.copy(date=date) };chooseDate=false }},enabled=dateState.selectedDateMillis!=null){Text("确定")}},dismissButton={TextButton(onClick={chooseDate=false}){Text("取消")}}) { DatePicker(state=dateState,showModeToggle=true) }
    }
}
