package com.example.aiaccounting

import com.example.aiaccounting.data.local.*
import com.example.aiaccounting.data.remote.InquiryProtocol
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ComparisonTest {
    private val categories = listOf(
        CategoryEntity(1, EntryKind.EXPENSE, "餐饮", "food", "orange", 0),
        CategoryEntity(2, EntryKind.EXPENSE, "交通", "transport", "blue", 1, active = false),
        CategoryEntity(3, EntryKind.INCOME, "工资", "work", "green", 0),
    )
    private val catalog = categories.map { AiCategory(it.id, it.kind, it.name, false) }
    private fun entry(id: Long, cents: Long, category: Long, date: String, note: String = "明确用途", deleted: Long? = null) =
        EntryEntity(id, if(category == 3L) EntryKind.INCOME else EntryKind.EXPENSE, cents, category, date, note, 1, 1, deleted)
    private val data = LedgerSnapshot(categories, listOf(
        entry(1, 15000, 1, "2024-03-01"), entry(2, 3000, 2, "2024-03-15", "地铁去公司"),
        entry(3, 10000, 1, "2024-02-01"), entry(4, 6000, 2, "2024-02-15", "地铁回家"),
        entry(5, 999999, 2, "2024-02-16"), entry(6, 88888, 1, "2024-03-16"),
        entry(7, 999999, 3, "2024-03-15"), entry(8, 999999, 1, "2024-03-15", deleted = 1),
    ))
    private fun plan(period: String = "THIS_MONTH_TO_DATE", baseline: String = "LAST_MONTH_TO_DATE") = JSONObject()
        .put("needs_clarification", false).put("metric", "COMPARE").put("period", period).put("kind", "EXPENSE")
        .put("category_ids", JSONArray()).put("keywords", JSONArray()).put("min_amount", JSONObject.NULL).put("max_amount", JSONObject.NULL).put("limit", 1)
        .put("comparison", JSONObject().put("period", baseline))
    private fun envelope(body: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("content", body.toString())))).toString()
    private fun parse(body: JSONObject = plan(), date: String = "2024-03-15") = InquiryProtocol.plan(envelope(body), catalog, LocalDate.parse(date))
    private fun explain(up: List<Long>, down: List<Long>, unchanged: Boolean = false) = envelope(JSONObject()
        .put("increased_category_ids", JSONArray(up)).put("decreased_category_ids", JSONArray(down)).put("unchanged", unchanged))

    @Test fun sameDayMonthComparisonClampsLeapAndShortMonthsAndResolvesYearChange() {
        val leap = parse(date = "2024-03-31")
        assertEquals("2024-03-31", leap.toDate); assertEquals("2024-02-29", leap.baselineToDate)
        assertEquals(31L, leap.execute(data).comparison!!.currentDays); assertEquals(29L, leap.execute(data).comparison!!.baselineDays)
        assertEquals("2025-02-28", parse(date = "2025-03-31").baselineToDate)
        val january = parse(date = "2026-01-05")
        assertEquals("2025-12-01", january.baselineFromDate); assertEquals("2025-12-05", january.baselineToDate)
        val whole = parse(plan("THIS_MONTH", "LAST_MONTH"))
        assertEquals("2024-03-31", whole.toDate); assertEquals("2024-02-29", whole.baselineToDate)
        assertEquals("2024-02-26", parse(plan("THIS_WEEK", "LAST_WEEK"), "2024-03-04").baselineFromDate)
    }

    @Test fun totalsDeltasCountsCategoriesAndExclusionsAreLocalAndPreserveBothPeriods() {
        val query = parse()
        val result = query.execute(data)
        assertEquals(setOf(1L,2L,3L,4L), result.rows.map { it.id }.toSet())
        val c = result.comparison!!
        assertEquals(18000L, c.current); assertEquals(16000L, c.baseline)
        assertEquals(2000L, c.delta); assertEquals("12.5", c.percent)
        assertEquals(2, c.currentCount); assertEquals(2, c.baselineCount)
        assertEquals(listOf(5000L,-3000L), c.categories.map { it.delta })
        assertEquals(c.delta, c.categories.sumOf { it.delta })
        val adjusted = query.execute(data, setOf(1, 4)).comparison!!
        assertEquals(3000L, adjusted.current); assertEquals(10000L, adjusted.baseline)
        assertEquals(-7000L, adjusted.delta); assertEquals("-70.0", adjusted.percent)
        assertEquals(4, query.execute(data, setOf(1, 4)).rows.size)
        assertEquals(8, data.entries.size); assertNull(data.entries.first().deletedAt)
    }

    @Test fun identicalFiltersApplyToBothPeriodsAndZeroDenominatorNeverInventsPercent() {
        val filtered = parse(plan().put("keywords", JSONArray(listOf("地铁"))).put("min_amount", "20.00")).execute(data)
        assertEquals(setOf(2L,4L), filtered.rows.map { it.id }.toSet())
        assertEquals(-3000L, filtered.comparison!!.delta)
        val onlyCurrent = LedgerSnapshot(categories, data.entries.filter { it.id in setOf(1L,2L) })
        val zeroBaseline = parse().execute(onlyCurrent).comparison!!
        assertNull(zeroBaseline.percent); assertEquals(18000L, zeroBaseline.delta)
        val empty = parse().execute(LedgerSnapshot(categories, emptyList())).comparison!!
        assertEquals(0L, empty.delta); assertNull(empty.percent); assertTrue(empty.categories.isEmpty())
        val onlyBaseline = parse().execute(LedgerSnapshot(categories, data.entries.filter { it.id in setOf(3L,4L) })).comparison!!
        assertEquals("-100.0", onlyBaseline.percent)
    }

    @Test fun overlappingUnboundedMissingAndIncomeComparisonsAreRejectedWithoutFallback() {
        val bad = listOf(plan("ALL"), plan(baseline = "THIS_MONTH_TO_DATE"), plan().put("comparison", JSONObject.NULL),
            plan().put("kind", "INCOME"), plan().put("kind", JSONObject.NULL), plan().put("limit", 2),
            plan().put("comparison", JSONObject().put("period", "CUSTOM").put("from", "2024-02-30").put("to", "2024-03-01")),
            plan().put("metric", "SUM"))
        bad.forEach { value -> try { parse(value); fail("Invalid comparison accepted") } catch (_: AiFailure) { } }
    }

    @Test fun explanationRejectsInventedWrongDirectionDuplicateAndOmittedMainCategories() {
        val result = parse().execute(data)
        val valid = InquiryProtocol.explanation(explain(listOf(1), listOf(2)), result)
        assertEquals(listOf(1L), valid.increased); assertEquals(listOf(2L), valid.decreased)
        val bad = listOf(explain(listOf(999), listOf(2)), explain(listOf(2), listOf(1)), explain(listOf(1,1), listOf(2)),
            explain(emptyList(), listOf(2)), explain(listOf(1), emptyList()), explain(listOf(1), listOf(2), true))
        bad.forEach { value -> try { InquiryProtocol.explanation(value, result); fail("Invalid explanation accepted") } catch (_: AiFailure) { } }
        val empty = parse().execute(LedgerSnapshot(categories, emptyList()))
        assertTrue(InquiryProtocol.explanation(explain(emptyList(), emptyList(), true), empty).unchanged)
        val offset = LedgerSnapshot(categories, listOf(entry(1,15000,1,"2024-03-01"),entry(2,10000,1,"2024-02-01"),
            entry(3,5000,2,"2024-03-01"),entry(4,10000,2,"2024-02-01")))
        val equalTotal = parse().execute(offset)
        assertEquals(0L, equalTotal.comparison!!.delta)
        assertFalse(InquiryProtocol.explanation(explain(listOf(1), listOf(2)), equalTotal).unchanged)
    }

    @Test fun reviewLabelsBothPeriodsAndSummarySendsAggregatesWithoutNotesOrOtherLedgerRows() {
        val result = parse().execute(data)
        val request = InquiryProtocol.reviewRequest("本月比上月多花在哪", result, result.rows)
        val payload = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        val rows = payload.getJSONArray("batch")
        assertEquals(setOf("CURRENT", "BASELINE"), (0 until rows.length()).map { rows.getJSONObject(it).getString("period") }.toSet())
        val totals = payload.getJSONObject("comparison")
        assertEquals("180.00", totals.getString("current_amount")); assertEquals("160.00", totals.getString("baseline_amount"))
        val explanation = InquiryProtocol.explanationRequest("本月比上月多花在哪", result)
        assertFalse(explanation.getBoolean("enable_thinking")); assertEquals(AiLimits.MODEL, explanation.getString("model"))
        val sent = explanation.getJSONArray("messages").getJSONObject(1).getString("content")
        assertFalse(sent.contains("地铁去公司")); assertFalse(sent.contains("999999")); assertFalse(sent.contains("工资"))
        assertTrue(sent.contains("180.00"))
    }

    @Test fun explicitFullMonthsCannotSilentlyUseTheDefaultMonthToDateScope() {
        val question = "这个月整月支出与上个月整月相比，多花了多少？"
        val today = LocalDate.of(2024,3,15)
        InquiryProtocol.validateQuestionScope(question, parse(plan("THIS_MONTH", "LAST_MONTH")), today)
        try { InquiryProtocol.validateQuestionScope(question, parse(), today); fail("Month-to-date substituted for explicit whole months") }
        catch (_: AiFailure) { }
        val request = InquiryProtocol.questionRequest(question, catalog, today)
        val user = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertTrue(user.has("explicit_month_scope"))
        InquiryProtocol.validateQuestionScope("这个月比上个月多花在哪", parse(), today)
        try { parse(plan().put("metric", "SUM").put("comparison", JSONObject.NULL)); fail("Comparison-only month-to-date mode accepted for an ordinary query") }
        catch (_: AiFailure) { }
    }
}
