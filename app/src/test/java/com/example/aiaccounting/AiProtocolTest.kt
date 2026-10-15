package com.example.aiaccounting

import com.example.aiaccounting.data.remote.AiProtocol
import com.example.aiaccounting.data.remote.SiliconFlowApi
import com.example.aiaccounting.data.remote.SiliconFlowTextAccounting
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.EntryKind
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
import java.util.concurrent.TimeUnit

class AiProtocolTest {
    private val categories = listOf(
        AiCategory(1, EntryKind.EXPENSE, "餐饮", false), AiCategory(2, EntryKind.EXPENSE, "交通", false),
        AiCategory(3, EntryKind.EXPENSE, "其他支出", true), AiCategory(4, EntryKind.INCOME, "工资", false),
        AiCategory(5, EntryKind.INCOME, "其他收入", true),
    )
    private fun row(kind: String = "EXPENSE", amount: String = "28.00", category: Long = 1, date: String = "2026-10-15", note: String = "午餐：老街面馆牛肉面") =
        JSONObject().put("kind", kind).put("amount", amount).put("category_id", category).put("date", date).put("note", note)
    private fun envelope(rows: List<JSONObject>, finish: String = "stop") = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", finish).put("message", JSONObject().put("content", JSONObject().put("entries", JSONArray(rows)).toString())))).toString()

    @Test fun preciseNotesAndMoneySurviveAndUnknownCategoryUsesMatchingFallback() {
        val rows = AiProtocol.response(envelope(listOf(row(), row(amount = "6", category = 2, note = "地铁去公司"),
            row(kind = "INCOME", amount = "300", category = 1, note = "兼职：网站维护"))), categories)
        assertEquals(listOf("午餐：老街面馆牛肉面", "地铁去公司", "兼职：网站维护"), rows.map { it.note })
        assertEquals(listOf(1L, 2L, 5L), rows.map { it.categoryId })
        assertTrue(rows.all { it.id == null })
        assertEquals("28.00", rows.first().amount)
    }

    @Test fun incompleteOrInvalidModelOutputRejectsTheEntireBatch() {
        val invalid = listOf(
            envelope(listOf(row(), row(amount = "-1"))), envelope(listOf(row(amount = "1.001"))),
            envelope(listOf(row(date = "2026-02-30"))), envelope(listOf(row(kind = "TRANSFER"))),
            envelope(listOf(row(note = "字".repeat(121)))), envelope(listOf(row()), "length"),
            envelope(emptyList()), envelope(List(21) { row() }), "<think>analysis</think>" + envelope(listOf(row())),
            envelope(listOf(row())) + " trailing text",
        )
        invalid.forEach { raw ->
            try { AiProtocol.response(raw, categories); fail("Invalid output accepted") } catch (_: AiFailure) { }
        }
    }

    @Test fun actualRetrofitRequestDisablesThinkingAndOnlySendsCurrentTextAndCategories() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(envelope(listOf(row()))).setHeader("Content-Type", "application/json"))
            val service = service(server)
            val input = "中午在老街面馆吃牛肉面28"
            assertEquals(1, service.parse(input, categories, LocalDate.of(2026, 10, 15)).size)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/chat/completions", request.path)
            assertEquals("Bearer local-test-credential", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(AiLimits.MODEL, body.getString("model"))
            assertFalse(body.getBoolean("enable_thinking")); assertFalse(body.getBoolean("stream"))
            assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
            assertEquals(2, body.getJSONArray("messages").length())
            assertEquals(input, body.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("2026-10-15"))
            assertFalse(body.toString().contains("local-test-credential"))
        } finally { server.shutdown() }
    }

    @Test fun unauthorizedAndMalformedResponsesProduceSafeActionableErrors() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive-provider-error-body"))
            server.enqueue(MockResponse().setBody("invalid JSON"))
            val service = service(server)
            repeat(2) { index ->
                try { service.parse("午饭28", categories, LocalDate.now()); fail("Request should fail") }
                catch (e: AiFailure) {
                    if (index == 0) assertTrue(e.message!!.contains("API Key"))
                    assertFalse(e.message!!.contains("sensitive-provider-error-body"))
                }
            }
        } finally { server.shutdown() }
    }

    private fun service(server: MockWebServer): SiliconFlowTextAccounting {
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()).build().create(SiliconFlowApi::class.java)
        val keys = object : ApiKeyStore {
            override fun read() = "local-test-credential"
            override fun write(key: String) = Unit
            override fun clear() = Unit
        }
        return SiliconFlowTextAccounting(keys, api)
    }
}
