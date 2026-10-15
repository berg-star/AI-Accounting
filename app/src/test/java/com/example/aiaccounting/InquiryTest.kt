package com.example.aiaccounting

import com.example.aiaccounting.data.local.*
import com.example.aiaccounting.data.remote.*
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import java.time.LocalDate

class InquiryTest {
    private val categories = listOf(
        CategoryEntity(1, EntryKind.EXPENSE, "交通", "transport", "blue", 0, active = false),
        CategoryEntity(2, EntryKind.INCOME, "工资", "work", "green", 0),
    )
    private val catalog = categories.map { AiCategory(it.id, it.kind, it.name, false) }
    private val today = LocalDate.of(2024, 3, 1)
    private fun entry(id: Long, amount: Long, date: String, note: String, kind: EntryKind = EntryKind.EXPENSE, deleted: Long? = null) =
        EntryEntity(id, kind, amount, if(kind == EntryKind.EXPENSE) 1 else 2, date, note, 1, 1, deleted)
    private val data = LedgerSnapshot(categories, listOf(
        entry(1, 600, "2024-02-29", "地铁去公司"), entry(2, 600, "2024-02-28", "地铁回家"),
        entry(3, 1800, "2024-03-01", "地铁站旁买早餐"), entry(4, 800000, "2024-03-01", "工资", EntryKind.INCOME),
        entry(5, 9999, "2024-03-01", "删除的地铁记录", deleted = 5), entry(6, 500, "2024-03-01", "公交去公园"),
    ))
    private fun body(period: String = "THIS_MONTH", metric: String = "SUM") = JSONObject()
        .put("needs_clarification", false).put("clarification", "").put("metric", metric).put("period", period)
        .put("days", JSONObject.NULL).put("from", JSONObject.NULL).put("to", JSONObject.NULL).put("kind", "EXPENSE")
        .put("category_ids", JSONArray()).put("keywords", JSONArray()).put("min_amount", JSONObject.NULL).put("max_amount", JSONObject.NULL).put("limit", 1)
    private fun envelope(body: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("content", body.toString())))).toString()
    private fun parse(body: JSONObject) = InquiryProtocol.plan(envelope(body), catalog, today)

    @Test fun calendarBoundariesResolveLocallyIncludingLeapYearAndYearChange() {
        assertEquals("2024-02-01", parse(body("LAST_MONTH")).fromDate)
        assertEquals("2024-02-29", parse(body("LAST_MONTH")).toDate)
        assertEquals("2024-02-26", parse(body("THIS_WEEK")).fromDate)
        assertEquals("2024-03-03", parse(body("THIS_WEEK")).toDate)
        assertEquals("2024-02-24", parse(body("LAST_DAYS").put("days", 7)).fromDate)
        assertEquals("2024-03-01", parse(body("TODAY")).fromDate)
        assertEquals("2024-02-29", parse(body("YESTERDAY")).toDate)
        val january = InquiryProtocol.plan(envelope(body("LAST_MONTH")), catalog, LocalDate.of(2026,1,5))
        assertEquals("2025-12-01", january.fromDate); assertEquals("2025-12-31", january.toDate)
        assertNull(parse(body("ALL")).fromDate)
        val custom = parse(body("CUSTOM").put("from", "2024-02-29").put("to", "2024-03-01"))
        assertEquals(3, custom.execute(data).rows.size)
    }

    @Test fun exactLocalResultsIncludeArchivedCategoriesExcludeDeletedAndRetainEveryCandidate() {
        val query = parse(body("ALL").put("keywords", JSONArray(listOf("地铁"))))
        val result = query.execute(data)
        assertEquals(listOf(3L,1L,2L), result.rows.map { it.id })
        assertEquals(3000L, result.expense)
        val adjusted = query.execute(data, setOf(3, 999))
        assertEquals(1200L, adjusted.expense)
        assertEquals(3, adjusted.rows.size)
        assertEquals(setOf(3L), adjusted.excludedIds)
        assertEquals(6, data.entries.size)
        assertNull(data.entries.single { it.id == 3L }.deletedAt)
    }

    @Test fun maximumTiesRankingAndBothKindsAreComputedWithoutModelTotals() {
        val max = parse(body("LAST_MONTH", "MAX")).execute(data)
        assertEquals(listOf(1L,2L), max.featured.map { it.id })
        val top = parse(body("ALL", "TOP").put("limit", 2)).execute(data)
        assertEquals(listOf(3L,1L), top.featured.map { it.id })
        assertEquals(4, top.rows.size)
        val both = parse(body("ALL").put("kind", JSONObject.NULL)).execute(data)
        assertEquals(3500L, both.expense); assertEquals(800000L, both.income)
        val bounded = parse(body("ALL").put("min_amount", "6.00").put("max_amount", "6.00")).execute(data)
        assertEquals(setOf(1L,2L), bounded.rows.map { it.id }.toSet())
        assertTrue(parse(body("ALL").put("keywords", JSONArray(listOf("不存在")))).execute(data).rows.isEmpty())
    }

    @Test fun invalidPlansNeverFallBackToBroaderQueries() {
        val bad = listOf(
            body().put("metric", "SQL"), body().put("period", "RECENT"), body().put("kind", "TRANSFER"),
            body().put("category_ids", JSONArray(listOf(999))), body().put("category_ids", JSONArray(listOf(2))),
            body().put("category_ids", JSONArray(listOf("1"))), body().put("keywords", JSONArray(listOf(""))),
            body("CUSTOM").put("from", "2024-02-30").put("to", "2024-03-01"),
            body("CUSTOM").put("from", "2024-03-01").put("to", "2024-02-01"),
            body("LAST_DAYS").put("days", 0), body("LAST_DAYS").put("days", "7"),
            body().put("min_amount", "2").put("max_amount", "1"), body().put("min_amount", 1),
            body().put("limit", 2), body("ALL", "TOP").put("limit", 21),
        )
        bad.forEach { value -> try { parse(value); fail("Invalid plan accepted: $value") } catch (_: AiFailure) { } }
        try { parse(JSONObject().put("needs_clarification", true).put("clarification", "请明确最近是多少天")); fail() }
        catch (e: AiFailure) { assertTrue(e.message!!.startsWith("请补充问题")) }
        try { InquiryProtocol.plan(envelope(body()) + " trailing", catalog, today); fail() } catch (_: AiFailure) { }
    }

    @Test fun reviewRejectsInventedIdsDuplicateFlagsAndTruncatedOutput() {
        val batch = listOf(data.entries.first())
        fun review(vararg ids: Long) = JSONObject().put("scope_warning", JSONObject.NULL).put("flags", JSONArray(ids.map { JSONObject().put("id", it).put("reason", "用途可能不符") }))
        assertEquals(1L, InquiryProtocol.review(envelope(review(1)), batch).flags.single().entryId)
        val invalid = listOf(review(999), review(1,1), review(1).put("scope_warning", "字".repeat(201)),
            review(1).put("flags", JSONArray().put(JSONObject().put("id", 1).put("reason", ""))))
        invalid.forEach { value -> try { InquiryProtocol.review(envelope(value), batch); fail() } catch (_: AiFailure) { } }
        try { InquiryProtocol.review(envelope(review()).replace("\"stop\"", "\"length\""), batch); fail() } catch (_: AiFailure) { }
    }

    @Test fun networkRequestsUseSameModelAndOnlySendLedgerRowsDuringExplicitReview() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(envelope(body("ALL").put("keywords", JSONArray(listOf("地铁"))))))
            server.enqueue(MockResponse().setBody(envelope(JSONObject().put("scope_warning", JSONObject.NULL).put("flags", JSONArray()))))
            val keys = object : ApiKeyStore {
                override fun read() = "isolated-test-credential"
                override fun write(key: String) = Unit
                override fun clear() = Unit
            }
            val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient()).build().create(SiliconFlowApi::class.java)
            val service = SiliconFlowLedgerInquiry(SiliconFlowCompletion(keys, api))
            val query = service.understand("所有历史的地铁支出", catalog, today)
            val first = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(AiLimits.MODEL, first.getString("model")); assertFalse(first.getBoolean("enable_thinking"))
            assertFalse(first.toString().contains("地铁去公司"))
            val result = query.execute(data)
            val batch = result.rows.take(1)
            service.review("所有历史的地铁支出", result, batch)
            val second = JSONObject(server.takeRequest().body.readUtf8())
            val payload = JSONObject(second.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals(1, payload.getJSONArray("batch").length())
            assertEquals(batch.single().note, payload.getJSONArray("batch").getJSONObject(0).getString("note"))
            assertFalse(payload.toString().contains("工资"))
            assertEquals("30.00", payload.getString("local_expense"))
        } finally { server.shutdown() }
    }

    @Test fun rankedReviewDistinguishesAllCandidatesFromTheLocallySelectedAnswer() {
        val ranked = parse(body("ALL", "MAX")).execute(data)
        val payload = JSONObject(InquiryProtocol.reviewRequest("所有历史最大的一笔支出", ranked, ranked.rows)
            .getJSONArray("messages").getJSONObject(1).getString("content"))
        val rows = payload.getJSONArray("batch")
        assertEquals(4, rows.length())
        val selected = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getBoolean("selected_for_answer") }
        assertEquals(listOf(3L), selected.map { it.getLong("id") })
        val excluded = ranked.copy(excludedIds = setOf(3))
        val adjusted = JSONObject(InquiryProtocol.reviewRequest("所有历史最大的一笔支出", excluded, excluded.rows)
            .getJSONArray("messages").getJSONObject(1).getString("content")).getJSONArray("batch")
        assertEquals(setOf(1L, 2L), (0 until adjusted.length()).map { adjusted.getJSONObject(it) }
            .filter { it.getBoolean("selected_for_answer") }.map { it.getLong("id") }.toSet())
        assertFalse(adjusted.getJSONObject(0).getBoolean("included"))
    }
}
