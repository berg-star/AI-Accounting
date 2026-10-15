package com.example.aiaccounting.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.aiaccounting.ui.LedgerUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BackupPreviewScreen(s: LedgerUiState) {
    val summary = s.backupPreview ?: return
    val time = runCatching { Instant.parse(summary.exportedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrDefault(summary.exportedAt)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("备份已通过校验", style = MaterialTheme.typography.titleLarge)
                    Text("导出时间：$time")
                    Text("${summary.categories} 个分类\n${summary.records} 笔有效记录\n${summary.deletedRecords} 笔已删除记录")
                }
            }
        }
        item {
            Text("恢复将替换当前账本", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text("当前有 ${s.data.categories.size} 个分类、${s.data.entries.count { it.deletedAt == null }} 笔有效记录。确认后，分类和记录将以这份备份为准，已删除记录仍保持删除状态。", modifier = Modifier.padding(top = 8.dp))
        }
        item { Text("替换前会自动保存一份原账本，可在设置中“导出恢复前备份”。它只保留最近一次恢复之前的账本，请及时导出妥善保存。API Key 不包含在备份里，恢复不会改变本机 Key。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("未确认的记账草稿和搜索条件会清除。返回即可取消，现有账本不会改变。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}
