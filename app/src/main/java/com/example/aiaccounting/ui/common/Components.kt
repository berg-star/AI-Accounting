package com.example.aiaccounting.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aiaccounting.domain.model.*
import java.time.YearMonth

@Composable
fun categoryColor(key: String): Color {
    val dark = isSystemInDarkTheme()
    return when (key) {
        "green" -> if (dark) Color(0xFF94CEB6) else Color(0xFF287354)
        "orange" -> if (dark) Color(0xFFE1B28C) else Color(0xFF9B5C2E)
        "purple" -> if (dark) Color(0xFFC3AADF) else Color(0xFF785A9D)
        else -> MaterialTheme.colorScheme.primary
    }
}

@Composable
fun CategoryGlyph(icon: String, color: String, modifier: Modifier = Modifier) {
    val tint = categoryColor(color)
    Surface(modifier.size(42.dp), color = tint.copy(alpha = .11f), shape = RoundedCornerShape(14.dp)) {
        Canvas(Modifier.padding(9.dp)) {
            withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
                fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x1,y1), Offset(x2,y2), 1.7f)
                fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 2f) = drawRoundRect(tint, Offset(x,y), Size(w,h), CornerRadius(radius), style = Stroke(1.7f))
                when (icon) {
                    "food" -> { line(5f,3f,5f,10f);line(8f,3f,8f,10f);line(2f,3f,2f,10f);line(2f,10f,8f,10f);line(5f,10f,5f,21f);box(15f,3f,5f,9f);line(17.5f,12f,17.5f,21f) }
                    "transport" -> { box(5f,3f,14f,15f,4f);box(7f,5f,10f,6f);drawCircle(tint,1f,Offset(8f,14f));drawCircle(tint,1f,Offset(16f,14f));line(8f,18f,5f,22f);line(16f,18f,19f,22f) }
                    "shopping" -> { box(4f,7f,16f,14f);box(8f,2f,8f,8f,4f) }
                    "home" -> { val p=Path().apply {moveTo(2f,11f);lineTo(12f,3f);lineTo(22f,11f)};drawPath(p,tint,style=Stroke(1.7f));box(5f,10f,14f,11f);box(10f,15f,4f,6f,0f) }
                    "fun" -> { box(3f,7f,18f,12f,4f);line(6f,13f,12f,13f);line(9f,10f,9f,16f);drawCircle(tint,1f,Offset(16f,11f));drawCircle(tint,1f,Offset(18f,15f)) }
                    "health" -> { box(3f,3f,18f,18f,6f);line(12f,6f,12f,18f);line(6f,12f,18f,12f) }
                    "study" -> { box(3f,4f,18f,16f);line(12f,4f,12f,21f);line(6f,8f,9f,8f);line(15f,8f,18f,8f) }
                    "coffee" -> { box(3f,7f,14f,13f,3f);box(17f,9f,5f,7f);line(6f,2f,6f,4f);line(11f,2f,11f,4f);line(2f,23f,20f,23f) }
                    "work" -> { box(2f,7f,20f,14f);box(8f,3f,8f,4f);line(2f,13f,22f,13f);line(12f,11f,12f,16f) }
                    "gift" -> { box(3f,9f,18f,12f);line(12f,9f,12f,21f);line(3f,13f,21f,13f);box(6f,3f,6f,6f,3f);box(12f,3f,6f,6f,3f) }
                    "wallet" -> { box(2f,5f,20f,16f);box(15f,10f,7f,6f);drawCircle(tint,1f,Offset(18f,13f)) }
                    else -> { listOf(5f,12f,19f).forEach { drawCircle(tint,1.6f,Offset(it,12f)) } }
                }
            }
        }
    }
}

@Composable
fun KindSelector(selected: EntryKind, enabled: Boolean = true, onChange: (EntryKind) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        EntryKind.entries.forEachIndexed { index, kind ->
            SegmentedButton(selected = selected == kind, onClick = { onChange(kind) }, enabled = enabled, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(kind.label) }
        }
    }
}

@Composable
fun MonthSelector(month: YearMonth, onMove: (Long) -> Unit, onSelect: (YearMonth) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var year by remember { mutableStateOf(month.year.toString()) }
    var number by remember { mutableStateOf(month.monthValue.toString()) }
    var error by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IconButton(onClick = { onMove(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上个月") }
        TextButton(onClick = { year = month.year.toString();number = month.monthValue.toString();error = false;choosing = true }) { Text("${month.year}年${month.monthValue}月", fontWeight = FontWeight.Medium) }
        IconButton(onClick = { onMove(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下个月") }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("选择月份") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(year, { year = it.take(4) }, label = { Text("年份") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            OutlinedTextField(number, { number = it.take(2) }, label = { Text("月份") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            if (error) ErrorText("年份需为 1900–9999，月份需为 1–12")
        }
    }, confirmButton = { TextButton(onClick = { val y=year.toIntOrNull();val n=number.toIntOrNull();if(y != null && y in 1900..9999 && n != null && n in 1..12) { onSelect(YearMonth.of(y,n));choosing=false } else error=true }) { Text("确定") } }, dismissButton = { TextButton(onClick = { choosing=false }) { Text("取消") } })
}

@Composable
fun MonthlySummary(totals: MonthlyTotals) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本月支出", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("¥ ${Money.format(totals.expense)}", fontSize = if (totals.expense < 100_000_000_00L) 34.sp else 25.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SummaryValue("本月收入",totals.income,Modifier.weight(1f))
                SummaryValue("净收支",totals.net,Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryValue(label: String, cents: Long, modifier: Modifier) {
    Column(modifier) { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(Money.format(cents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) }
}

@Composable
fun ErrorText(message: String) { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

@Composable
fun SelectedCheck() { Icon(Icons.Default.Check, contentDescription = "已选中", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
