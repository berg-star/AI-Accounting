package com.example.aiaccounting.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class CategoryChange(
    val categoryId: Long,
    val name: String,
    val current: Long,
    val baseline: Long,
    val currentCount: Int,
    val baselineCount: Int,
) {
    val delta get() = current - baseline
}

data class SpendingComparison(
    val current: Long,
    val baseline: Long,
    val currentCount: Int,
    val baselineCount: Int,
    val currentDays: Long,
    val baselineDays: Long,
    val categories: List<CategoryChange>,
) {
    val delta get() = current - baseline
    // A zero denominator is not a 100% increase. Keep it explicit in both UI and API data.
    val percent: String? get() = if (baseline == 0L) null else BigDecimal.valueOf(delta).multiply(BigDecimal(100))
        .divide(BigDecimal.valueOf(baseline), 1, RoundingMode.HALF_UP).toPlainString()

    companion object {
        fun from(result: QueryResult): SpendingComparison {
            val q = result.query
            q.validateRanges()
            val current = result.included.filter { q.inCurrentPeriod(it.date) }
            val baseline = result.included.filter { q.inBaselinePeriod(it.date) }
            val currentGroups = current.groupBy { it.categoryId }
            val baselineGroups = baseline.groupBy { it.categoryId }
            val categories = (currentGroups.keys + baselineGroups.keys).map { id ->
                val a = currentGroups[id].orEmpty(); val b = baselineGroups[id].orEmpty()
                CategoryChange(id, result.categoryNames[id].orEmpty(), a.sumOf { it.cents }, b.sumOf { it.cents }, a.size, b.size)
            }.sortedWith(compareByDescending<CategoryChange> { abs(it.delta) }.thenBy { it.categoryId })
            fun days(from: String?, to: String?) = ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)) + 1
            return SpendingComparison(current.sumOf { it.cents }, baseline.sumOf { it.cents }, current.size, baseline.size,
                days(q.fromDate, q.toDate), days(q.baselineFromDate, q.baselineToDate), categories)
        }
    }
}
