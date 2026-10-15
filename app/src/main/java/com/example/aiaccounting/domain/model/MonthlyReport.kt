package com.example.aiaccounting.domain.model

import com.example.aiaccounting.data.local.EntryEntity
import com.example.aiaccounting.data.repository.LedgerSnapshot
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth

enum class ReportClaimKind { OVERVIEW, CATEGORY, TOP_EXPENSE, CATEGORY_CHANGE }
data class ReportConclusion(val kind: ReportClaimKind, val id: Long? = null)
data class ReportCategory(val id: Long, val name: String, val cents: Long, val count: Int, val percent: String)

data class MonthlyReport(
    val month: YearMonth,
    val from: String,
    val to: String,
    val available: Boolean,
    val rows: List<EntryEntity>,
    val categoryNames: Map<Long, String>,
    val expense: Long,
    val income: Long,
    val categories: List<ReportCategory>,
    val largest: List<EntryEntity>,
    val comparison: QueryResult?,
    val fingerprint: String,
) {
    val net get() = income - expense

    fun validateConclusions(claims: List<ReportConclusion>) {
        require(available && claims.size in 1..5 && claims.distinct().size == claims.size)
        require(claims.count { it.kind == ReportClaimKind.OVERVIEW } == 1)
        claims.forEach { claim ->
            require(when (claim.kind) {
                ReportClaimKind.OVERVIEW -> claim.id == null
                ReportClaimKind.CATEGORY -> categories.any { it.id == claim.id }
                ReportClaimKind.TOP_EXPENSE -> largest.any { it.id == claim.id }
                ReportClaimKind.CATEGORY_CHANGE -> comparison?.comparison?.categories?.any { it.categoryId == claim.id && it.delta != 0L } == true
            }) { "报告结论缺少有效依据" }
        }
        if (largest.isNotEmpty()) {
            require(claims.any { it.kind == ReportClaimKind.TOP_EXPENSE && it.id == largest.first().id })
            require(claims.any { it.kind == ReportClaimKind.CATEGORY && it.id == categories.first().id })
        }
    }

    fun evidence(claim: ReportConclusion): List<EntryEntity> = when (claim.kind) {
        ReportClaimKind.OVERVIEW -> rows
        ReportClaimKind.CATEGORY -> rows.filter { it.kind == EntryKind.EXPENSE && it.categoryId == claim.id }
        ReportClaimKind.TOP_EXPENSE -> largest.filter { it.id == claim.id }
        ReportClaimKind.CATEGORY_CHANGE -> comparison?.rows.orEmpty().filter { it.categoryId == claim.id }
    }

    fun conclusionText(claim: ReportConclusion): String = when (claim.kind) {
        ReportClaimKind.OVERVIEW -> "本期记录 ${rows.size} 笔，收入 ¥ ${Money.format(income)}，支出 ¥ ${Money.format(expense)}，净收支 ¥ ${Money.format(net)}。"
        ReportClaimKind.CATEGORY -> categories.firstOrNull { it.id == claim.id }?.let {
            "${it.name}支出 ¥ ${Money.format(it.cents)}，占支出的 ${it.percent}%，共 ${it.count} 笔${if(it.id == categories.firstOrNull()?.id) "，是支出最多的分类之一" else ""}。"
        }.orEmpty()
        ReportClaimKind.TOP_EXPENSE -> largest.firstOrNull { it.id == claim.id }?.let {
            "${if(it.cents == largest.firstOrNull()?.cents) "最大单笔支出" else "大额支出"}：${it.date} ${it.note.ifBlank { categoryNames[it.categoryId].orEmpty() }}，¥ ${Money.format(it.cents)}。"
        }.orEmpty()
        ReportClaimKind.CATEGORY_CHANGE -> comparison?.comparison?.categories?.firstOrNull { it.categoryId == claim.id }?.let {
            "${it.name}比对照期${if(it.delta > 0) "多花" else "少花"} ¥ ${Money.format(kotlin.math.abs(it.delta))}，笔数 ${it.baselineCount} → ${it.currentCount}（对照 → 本期）。"
        }.orEmpty()
    }

    companion object {
        fun build(month: YearMonth, data: LedgerSnapshot, today: LocalDate): MonthlyReport {
            val available = month <= YearMonth.from(today)
            val end = if (month == YearMonth.from(today)) today else month.atEndOfMonth()
            val from = month.atDay(1).toString(); val to = end.toString()
            val rows = if (!available) emptyList() else data.entries.filter { it.deletedAt == null && it.date >= from && it.date <= to }
                .sortedWith(compareByDescending<EntryEntity> { it.date }.thenByDescending { it.id })
            val expenses = rows.filter { it.kind == EntryKind.EXPENSE }
            val expense = expenses.sumOf { it.cents }; val income = rows.filter { it.kind == EntryKind.INCOME }.sumOf { it.cents }
            val names = data.categories.associate { it.id to it.name }
            val ranks = expenses.groupBy { it.categoryId }.map { (id, entries) ->
                val cents = entries.sumOf { it.cents }
                ReportCategory(id, names[id].orEmpty(), cents, entries.size,
                    BigDecimal.valueOf(cents).multiply(BigDecimal(100)).divide(BigDecimal.valueOf(expense), 1, RoundingMode.HALF_UP).toPlainString())
            }.sortedWith(compareByDescending<ReportCategory> { it.cents }.thenBy { it.id })
            val largest = expenses.sortedWith(compareByDescending<EntryEntity> { it.cents }.thenByDescending { it.date }.thenByDescending { it.id }).take(5)
            val previous = month.minusMonths(1)
            val previousEnd = if (month == YearMonth.from(today)) previous.atDay(minOf(today.dayOfMonth, previous.lengthOfMonth())) else previous.atEndOfMonth()
            val comparison = if (!available) null else LedgerQuery(QueryMetric.COMPARE, from, to, EntryKind.EXPENSE,
                baselineFromDate = previous.atDay(1).toString(), baselineToDate = previousEnd.toString()).execute(data)
            val included = (rows + comparison?.rows.orEmpty()).distinctBy { it.id }.sortedBy { it.id }
            val usedCategories = included.map { it.categoryId }.toSet()
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeUTF("monthly-report-v1"); out.writeUTF(month.toString()); out.writeUTF(to); out.writeBoolean(available)
                included.forEach { e -> out.writeLong(e.id); out.writeUTF(e.kind.name); out.writeLong(e.cents); out.writeLong(e.categoryId); out.writeUTF(e.date); out.writeUTF(e.note) }
                data.categories.filter { it.id in usedCategories }.sortedBy { it.id }.forEach { c -> out.writeLong(c.id); out.writeUTF(c.name); out.writeBoolean(c.active) }
            }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
            return MonthlyReport(month, from, to, available, rows, names, expense, income, ranks, largest, comparison, fingerprint)
        }
    }
}
