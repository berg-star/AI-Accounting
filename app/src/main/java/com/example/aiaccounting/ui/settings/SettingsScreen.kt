package com.example.aiaccounting.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(enabled: Boolean, onCategories: () -> Unit, onBackup: () -> Unit, onAi: () -> Unit, onImport: () -> Unit, hasRecovery: Boolean, onRecovery: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Card(shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("我的账本",style=MaterialTheme.typography.titleLarge);Text("人民币 · 数据保存在本机",color=MaterialTheme.colorScheme.onSurfaceVariant) } } }
        item { Card(shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) { Column {
            SettingAction("分类管理",enabled,onCategories)
            HorizontalDivider(Modifier.padding(horizontal=16.dp))
            SettingAction("AI 设置",enabled,onAi)
            HorizontalDivider(Modifier.padding(horizontal=16.dp))
            SettingAction("导出完整备份",enabled,onBackup)
            HorizontalDivider(Modifier.padding(horizontal=16.dp))
            SettingAction("从备份恢复",enabled,onImport)
            if(hasRecovery) {
                HorizontalDivider(Modifier.padding(horizontal=16.dp))
                SettingAction("导出恢复前备份",enabled,onRecovery)
            }
        } } }
        item { Text("备份包含全部分类与记录，包括停用分类和已删除记录。导出的文件包含你的账目，请妥善保存。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("v0.4.0 · 搜索与备份恢复",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("导入后先预览，再确认替换账本。恢复前自动保留一份原账本备份。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp)) }
    }
}

@Composable
private fun SettingAction(title: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick=onClick,enabled=enabled,contentPadding=PaddingValues(16.dp),modifier=Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(title,modifier=Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurface);Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null) } }
}
