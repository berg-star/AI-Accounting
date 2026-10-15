package com.example.aiaccounting.domain.model

import com.example.aiaccounting.data.local.EntryEntity
import com.example.aiaccounting.data.repository.LedgerSnapshot

enum class QueryMetric(val label: String) { SUM("金额合计"), COUNT("笔数统计"), MAX("最大单笔"), TOP("金额排行"), LIST("账目明细"), COMPARE("消费对比") }

data class LedgerQuery(
    val metric: QueryMetric,
    val fromDate: String? = null,
    val toDate: String? = null,
    val kind: EntryKind? = null,
    val categoryIds: Set<Long> = emptySet(),
    val keywords: List<String> = emptyList(),
    val minCents: Long? = null,
    val maxCents: Long? = null,
    val limit: Int = 1,
    val baselineFromDate: String? = null,
    val baselineToDate: String? = null,
) {
    fun validateRanges() {
        require((fromDate == null && toDate == null) || (fromDate != null && toDate != null && canonicalDate(fromDate) && canonicalDate(toDate) && fromDate <= toDate)) { "查询日期范围无效" }
        if (metric == QueryMetric.COMPARE) {
            require(kind == EntryKind.EXPENSE && fromDate != null && toDate != null && limit == 1) { "目前仅支持两个明确时间段的支出对比" }
            require(baselineFromDate != null && baselineToDate != null && canonicalDate(baselineFromDate) && canonicalDate(baselineToDate) && baselineFromDate <= baselineToDate) { "对照日期范围无效" }
            require(toDate < baselineFromDate || baselineToDate < fromDate) { "两个对比时间段不能重叠，请明确范围" }
        } else require(baselineFromDate == null && baselineToDate == null) { "普通查账不能携带对比范围" }
    }

    fun validate(data: LedgerSnapshot) {
        validateRanges()
        require(categoryIds.size <= 20 && categoryIds.all { id -> data.categories.any { it.id == id && (kind == null || it.kind == kind) } }) { "查询分类已改变，请重新提问" }
        require(keywords.size <= 6 && keywords.all { it.isNotBlank() && it.length <= 40 }) { "查询关键词无效" }
        require(minCents == null || minCents in 0..Money.MAX_CENTS)
        require(maxCents == null || maxCents in 0..Money.MAX_CENTS)
        require(minCents == null || maxCents == null || minCents <= maxCents)
        require(limit in 1..20)
    }

    fun execute(data: LedgerSnapshot, excludedIds: Set<Long> = emptySet()): QueryResult {
        validate(data)
        val matching = data.entries.filter { e ->
            e.deletedAt == null && (kind == null || e.kind == kind) &&
                (inCurrentPeriod(e.date) || inBaselinePeriod(e.date)) &&
                (categoryIds.isEmpty() || e.categoryId in categoryIds) &&
                (keywords.isEmpty() || keywords.any { e.note.contains(it, ignoreCase = true) }) &&
                (minCents == null || e.cents >= minCents) && (maxCents == null || e.cents <= maxCents)
        }
        val order = if (metric == QueryMetric.MAX || metric == QueryMetric.TOP)
            compareByDescending<EntryEntity> { it.cents }.thenByDescending { it.date }.thenByDescending { it.id }
        else compareByDescending<EntryEntity> { it.date }.thenByDescending { it.id }
        return QueryResult(this, matching.sortedWith(order), data.categories.associate { it.id to it.name }, excludedIds.intersect(matching.map { it.id }.toSet()))
    }

    fun inCurrentPeriod(date: String) = (fromDate == null || date >= fromDate) && (toDate == null || date <= toDate)
    fun inBaselinePeriod(date: String) = metric == QueryMetric.COMPARE && baselineFromDate != null && baselineToDate != null && date >= baselineFromDate && date <= baselineToDate
}

data class QueryResult(val query: LedgerQuery, val rows: List<EntryEntity>, val categoryNames: Map<Long, String>, val excludedIds: Set<Long> = emptySet()) {
    val included get() = rows.filter { it.id !in excludedIds }
    val expense get() = included.filter { it.kind == EntryKind.EXPENSE }.sumOf { it.cents }
    val income get() = included.filter { it.kind == EntryKind.INCOME }.sumOf { it.cents }
    val comparison get() = if (query.metric == QueryMetric.COMPARE) SpendingComparison.from(this) else null
    val featured get() = when (query.metric) {
        QueryMetric.MAX -> included.firstOrNull()?.let { first -> included.takeWhile { it.cents == first.cents } } ?: emptyList()
        QueryMetric.TOP -> included.take(query.limit)
        else -> emptyList()
    }
}
