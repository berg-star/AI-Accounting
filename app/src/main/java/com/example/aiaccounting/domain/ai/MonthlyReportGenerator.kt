package com.example.aiaccounting.domain.ai

import com.example.aiaccounting.domain.model.*
import java.time.YearMonth

fun interface MonthlyReportGenerator {
    suspend fun generate(report: MonthlyReport): List<ReportConclusion>
}

data class SavedMonthlyReport(val month: YearMonth, val fingerprint: String, val generatedAt: String, val conclusions: List<ReportConclusion>)

interface MonthlyReportCache {
    fun read(month: YearMonth): SavedMonthlyReport?
    fun write(report: SavedMonthlyReport)
    fun clear()
}

data class MonthlyReportState(
    val month: YearMonth = YearMonth.now(),
    val report: MonthlyReport? = null,
    val saved: SavedMonthlyReport? = null,
    val stale: Boolean = false,
    val loadingCache: Boolean = true,
    val generating: Boolean = false,
    val error: String? = null,
)
