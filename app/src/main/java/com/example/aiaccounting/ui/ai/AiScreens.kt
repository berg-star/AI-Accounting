package com.example.aiaccounting.ui.ai

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.aiaccounting.domain.ai.AiLimits
import com.example.aiaccounting.domain.model.EntryKind
import com.example.aiaccounting.domain.model.Money
import com.example.aiaccounting.ui.LedgerUiState

@Composable
fun AiInputScreen(s: LedgerUiState, onChange: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("把今天的花销说清楚", style = MaterialTheme.typography.titleLarge)
                    Text("分类自动选，细节留在备注里。识别后看一眼，正确就一键入账。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedTextField(value = s.aiText, onValueChange = onChange, enabled = !s.busy,
                modifier = Modifier.fillMaxWidth().testTag("ai-input"), label = { Text("写下用途和金额") },
                placeholder = { Text("中午在老街面馆吃牛肉面 28 元，坐地铁去公司 6 元，今天收到兼职工资 300 元") },
                minLines = 5, maxLines = 10, shape = RoundedCornerShape(18.dp),
                supportingText = { Text("${s.aiText.length}/${AiLimits.INPUT_LENGTH} · 一次最多 ${AiLimits.ENTRIES} 笔") })
        }
        item { Text("吃了什么、店名、坐什么车、买了什么，都可以一起写。金额不明确时不会猜测；目前只处理人民币收入和支出。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        item {
            Text("点击识别后，本次文字、当前日期和可用分类将发送至硅基流动。历史账目不会发送。AI 结果需你确认后才会入账。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(if (s.keyConfigured) "API Key 已配置" else "还没有 Key？点右上角“AI 设置”填写。",
                modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun AiReviewScreen(s: LedgerUiState, onEdit: (Int) -> Unit, onRemove: (Int) -> Unit, onRestart: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("识别到 ${s.aiDrafts.size} 笔，核对一下", style = MaterialTheme.typography.titleLarge)
            Text("正确就全部入账；点卡片可修改，移除只会删掉本次草稿。", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsIndexed(s.aiDrafts) { index, draft ->
            val category = s.data.categories.find { it.id == draft.categoryId }
            Card(onClick = { onEdit(index) }, enabled = !s.busy, shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().testTag("ai-draft-$index")) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${draft.kind.label} · ${category?.name ?: "请选择分类"}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${if (draft.kind == EntryKind.INCOME) "+" else "−"} ${Money.format(Money.parse(draft.amount) ?: 0)}", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(draft.note.ifBlank { "未提供具体描述" }, style = MaterialTheme.typography.bodyLarge)
                    Text(draft.date, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (category?.fallback == true || category?.active != true) Text("请核对分类", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onEdit(index) }, enabled = !s.busy, modifier = Modifier.testTag("ai-edit-$index")) { Text("修改") }
                        TextButton(onClick = { onRemove(index) }, enabled = !s.busy, modifier = Modifier.testTag("ai-remove-$index")) { Text("移除") }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = onRestart, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) { Text("修改原文，重新识别") } }
        item { Text("返回明细会保留这批草稿，可从“AI 记一笔”继续。未确认的草稿不计入统计。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
    else -> null
}

@Composable
fun AiSettingsScreen(s: LedgerUiState, onSave: (String) -> Unit, onClear: () -> Unit) {
    // Credentials deliberately never use rememberSaveable or SavedStateHandle.
    var input by remember { mutableStateOf("") }
    LaunchedEffect(s.keyRevision) { input = "" }
    val window = LocalContext.current.activity()?.window
    DisposableEffect(window) {
        val wasSecure = window != null && window.attributes.flags.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (window != null && !wasSecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("硅基流动", style = MaterialTheme.typography.titleLarge)
                    Text("${AiLimits.MODEL}\n纯文字 · 深度思考已关闭")
                    Text(if (s.keyConfigured) "API Key 已保存在本机" else "尚未配置 API Key", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            OutlinedTextField(input, { input = it.take(512) }, enabled = !s.busy,
                modifier = Modifier.fillMaxWidth().testTag("api-key-input"),
                label = { Text(if (s.keyConfigured) "替换 API Key" else "API Key") },
                supportingText = { Text("从硅基流动控制台获取，不需要发到聊天里。保存不会验证额度或调用模型。") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(18.dp))
        }
        item { Button(onClick = { onSave(input) }, enabled = !s.busy && input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("加密保存 Key") } }
        if (s.keyConfigured) item { OutlinedButton(onClick = onClear, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) { Text("移除本机 Key") } }
        item { Text("Key 使用 Android Keystore 加密保护，不写入源码、账本或导出备份。只有你主动识别文字时才调用接口。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}
