package com.example.aiaccounting

import com.example.aiaccounting.data.local.*
import com.example.aiaccounting.data.remote.*
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Seven explicitly authorized requests, synthetic data only, no device installation. */
class LiveComparisonJvmTest {
    @Test fun realComparisonUnderstandsPeriodsReviewsBothSidesAndExplainsValidatedChanges() = runBlocking {
        assumeTrue("Paid API tests require explicit Gradle opt-in", System.getenv("AI_ACCOUNTING_LIVE_INQUIRY") == "true")
        val path = System.getenv("AI_ACCOUNTING_LIVE_KEY_FILE").orEmpty()
        check(path.isNotBlank()) { "Provide a local credential file path" }
        val credential = File(path).readText(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        check(credential.isNotBlank() && credential.none { it.isWhitespace() }) { "The local credential must be one nonempty line" }
        val keys = object : ApiKeyStore {
            override fun read() = credential
            override fun write(key: String) = error("Read-only test credential")
            override fun clear() = error("Read-only test credential")
        }
        val service = SiliconFlowLedgerInquiry(SiliconFlowCompletion(keys))
        val today = LocalDate.now()
        val month = YearMonth.from(today); val last = month.minusMonths(1)
        val categories = listOf(CategoryEntity(1, EntryKind.EXPENSE, "餐饮", "food", "orange", 0),
            CategoryEntity(2, EntryKind.EXPENSE, "交通", "transport", "blue", 1))
        val catalog = categories.map { AiCategory(it.id, it.kind, it.name, false) }
        fun entry(id: Long, cents: Long, category: Long, date: String, note: String) = EntryEntity(id, EntryKind.EXPENSE, cents, category, date, note, 1, 1)
        val data = LedgerSnapshot(categories, listOf(
            entry(1, 15000, 1, month.atDay(1).toString(), "本期聚餐"), entry(2, 3000, 2, month.atDay(1).toString(), "本期公交"),
            entry(3, 10000, 1, last.atDay(1).toString(), "对照聚餐"), entry(4, 6000, 2, last.atDay(1).toString(), "对照公交"),
        ))
        val question = "这个月比上个月多花在哪里？"
        val query = timed("comparison-plan") { service.understand(question, catalog, today) }
        assertEquals(QueryMetric.COMPARE, query.metric); assertEquals(EntryKind.EXPENSE, query.kind)
        assertEquals(month.atDay(1).toString(), query.fromDate); assertEquals(today.toString(), query.toDate)
        assertEquals(last.atDay(1).toString(), query.baselineFromDate)
        assertEquals(last.atDay(minOf(today.dayOfMonth, last.lengthOfMonth())).toString(), query.baselineToDate)
        assertTrue(query.categoryIds.isEmpty()); assertTrue(query.keywords.isEmpty())
        val result = query.execute(data)
        assertEquals(2000L, result.comparison!!.delta); assertEquals("12.5", result.comparison!!.percent)
        val review = timed("comparison-review") { service.review(question, result, result.rows) }
        assertTrue("Baseline rows must not be marked unrelated: ${review.flags}", review.flags.isEmpty())
        assertNull(review.scopeWarning)
        val summary = timed("comparison-explanation") { service.explainComparison(question, result) }
        assertEquals(listOf(1L), summary.increased); assertEquals(listOf(2L), summary.decreased); assertFalse(summary.unchanged)

        val metroData = LedgerSnapshot(categories, listOf(
            entry(1, 600, 2, month.atDay(1).toString(), "地铁去公司"),
            entry(2, 1800, 2, month.atDay(1).toString(), "地铁站旁买早餐豆浆油条"),
            entry(3, 800, 2, last.atDay(1).toString(), "地铁回家"),
            entry(4, 99999, 1, month.atDay(1).toString(), "无关的聚餐"),
        ))
        val metroQuestion = "这个月坐地铁比上个月同期多花多少？"
        val metroQuery = timed("metro-comparison-plan") { service.understand(metroQuestion, catalog, today) }
        assertEquals(QueryMetric.COMPARE, metroQuery.metric)
        assertTrue(metroQuery.keywords.any { it.contains("地铁") })
        val metroResult = metroQuery.execute(metroData)
        assertEquals(setOf(1L,2L,3L), metroResult.rows.map { it.id }.toSet())
        assertEquals(1600L, metroResult.comparison!!.delta)
        val metroReview = timed("metro-comparison-review") { service.review(metroQuestion, metroResult, metroResult.rows) }
        assertEquals(setOf(2L), metroReview.flags.map { it.entryId }.toSet())
        val adjusted = metroQuery.execute(metroData, setOf(2))
        assertEquals(-200L, adjusted.comparison!!.delta)
        val adjustedSummary = timed("metro-adjusted-explanation") { service.explainComparison(metroQuestion, adjusted) }
        assertTrue(adjustedSummary.increased.isEmpty()); assertEquals(listOf(2L), adjustedSummary.decreased)
        assertEquals(4, metroData.entries.size)

        val whole = timed("whole-month-plan") { service.understand("这个月整月支出与上个月整月相比，多花了多少？", catalog, today) }
        assertEquals(QueryMetric.COMPARE, whole.metric)
        assertEquals(month.atEndOfMonth().toString(), whole.toDate)
        assertEquals(last.atEndOfMonth().toString(), whole.baselineToDate)
    }

    private suspend fun <T> timed(phase: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        return block().also { println("LIVE_COMPARISON $phase elapsedMs=${(System.nanoTime() - start) / 1_000_000} $it") }
    }
}
