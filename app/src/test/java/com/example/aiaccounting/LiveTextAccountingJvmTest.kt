package com.example.aiaccounting

import com.example.aiaccounting.data.remote.AiProtocol
import com.example.aiaccounting.data.remote.SiliconFlowCompletion
import com.example.aiaccounting.domain.ai.AiCategory
import com.example.aiaccounting.domain.ai.AiFailure
import com.example.aiaccounting.domain.ai.ApiKeyStore
import com.example.aiaccounting.domain.model.EntryKind
import com.example.aiaccounting.domain.model.Money
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Opt-in real requests for extraction and note summaries; no database or device access. */
class LiveTextAccountingJvmTest {
    @Test fun expensesKeepDatesAndSummarizeNotesWithoutInventingDetails() = runBlocking {
        assumeTrue("Live API tests require explicit opt-in", System.getenv("AI_ACCOUNTING_LIVE_INQUIRY") == "true")
        val keyPath = System.getenv("AI_ACCOUNTING_LIVE_KEY_FILE").orEmpty()
        check(keyPath.isNotBlank()) { "Provide a local credential file path" }
        val credential = File(keyPath).readText(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        check(credential.isNotBlank() && credential.none { it.isWhitespace() }) { "Invalid credential file format" }
        val keys = object : ApiKeyStore {
            override fun read() = credential
            override fun write(key: String) = error("Read-only test credential")
            override fun clear() = error("Read-only test credential")
        }
        val completion = SiliconFlowCompletion(keys)
        val categories = listOf(
            AiCategory(11, EntryKind.EXPENSE, "餐饮", false),
            AiCategory(22, EntryKind.EXPENSE, "交通", false),
            AiCategory(33, EntryKind.EXPENSE, "其他支出", true),
            AiCategory(44, EntryKind.INCOME, "其他收入", true),
            AiCategory(55, EntryKind.EXPENSE, "购物", false),
        )
        val today = LocalDate.now()
        val yesterday = today.minusDays(1).toString()
        val historical = today.minusMonths(2).withDayOfMonth(15)
        data class Expected(val cents: Long, val date: String, val detail: String, val category: Long?, val note: String? = null)
        data class Case(val label: String, val text: String, val expected: List<Expected>)
        val cases = listOf(
            Case("reported-fee", "昨天交了3.5元团费", listOf(Expected(350, yesterday, "团费", 33))),
            Case("fee-today", "交了3.5元团费", listOf(Expected(350, today.toString(), "团费", 33))),
            Case("unfamiliar-fee", "昨天缴了8元会费", listOf(Expected(800, yesterday, "会费", 33))),
            Case("historical-fee", "${historical.year}年${historical.monthValue}月${historical.dayOfMonth}日交了12元会费",
                listOf(Expected(1200, historical.toString(), "会费", 33))),
            Case("mixed-dates", "昨天交了3.5元团费，今天午饭12元", listOf(
                Expected(350, yesterday, "团费", 33), Expected(1200, today.toString(), "午饭", 11))),
            Case("future-fee", "明天准备交3.5元团费", emptyList()),
            Case("hypothetical-fee", "如果交3.5元团费怎么办", emptyList()),
            Case("transfer", "昨天给同学转账3.5元", emptyList()),
            Case("refund", "昨天退回3.5元团费", emptyList()),
            Case("loan", "昨天借给同学3.5元", emptyList()),
            Case("dumpling-summary", "中午吃饺子花了14.8元",
                listOf(Expected(1480, today.toString(), "饺子", 11, "午餐·饺子"))),
            Case("merchant-summary", "去赵一鸣买零食花了20元",
                listOf(Expected(2000, today.toString(), "零食", null, "赵一鸣·零食"))),
            Case("no-invented-merchant", "买零食花了8元",
                listOf(Expected(800, today.toString(), "零食", null, "零食"))),
            Case("no-invented-meal", "吃饺子花了14.8元",
                listOf(Expected(1480, today.toString(), "饺子", 11, "饺子"))),
            Case("route-number", "坐地铁9号线去公司花了6元",
                listOf(Expected(600, today.toString(), "地铁", 22, "地铁9号线·公司"))),
            Case("quantity-number", "去便利店买2瓶500毫升矿泉水花了4元",
                listOf(Expected(400, today.toString(), "矿泉水", null, "便利店·2瓶500毫升矿泉水"))),
            Case("separate-notes", "昨天中午在老街面馆吃牛肉面28元，今天去赵一鸣买零食20元", listOf(
                Expected(2800, yesterday, "牛肉面", 11, "午餐·老街面馆·牛肉面"),
                Expected(2000, today.toString(), "零食", null, "赵一鸣·零食"))),
            Case("breakfast-and-bus", "早上在小巷早餐店吃豆浆油条12元，坐公交去医院2元", listOf(
                Expected(1200, today.toString(), "豆浆", 11, "早餐·小巷早餐店·豆浆油条"),
                Expected(200, today.toString(), "公交", 22, "公交·医院"))),
            // These merchants and phrasings do not appear in the prompt examples.
            Case("unseen-dinner", "昨天晚上在东门小馆吃炒饭花了16元，挺好吃的",
                listOf(Expected(1600, yesterday, "炒饭", 11, "晚餐·东门小馆·炒饭"))),
            Case("unseen-shop", "昨天在校园超市买笔记本花了9元",
                listOf(Expected(900, yesterday, "笔记本", 55, "校园超市·笔记本"))),
        )
        val failures = mutableListOf<String>()
        for (case in cases) {
            try {
                val started = System.nanoTime()
                val raw = completion.complete(AiProtocol.request(case.text, categories, today))
                val envelope = JSONObject(raw)
                val choices = envelope.getJSONArray("choices")
                assertEquals("${case.label}: exactly one answer", 1, choices.length())
                val choice = choices.getJSONObject(0)
                assertEquals("${case.label}: complete answer", "stop", choice.getString("finish_reason"))
                val message = choice.getJSONObject("message")
                val reasoning = message.opt("reasoning_content").takeUnless { it == null || it == JSONObject.NULL }?.toString().orEmpty()
                assertTrue("${case.label}: thinking must remain disabled", reasoning.isBlank())
                val reasoningTokens = envelope.optJSONObject("usage")?.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens", 0) ?: 0
                assertEquals("${case.label}: no reasoning tokens", 0, reasoningTokens)
                if (case.expected.isEmpty()) {
                    try {
                        AiProtocol.response(raw, categories)
                        fail("${case.label}: must not invent an expense")
                    } catch (e: AiFailure) {
                        assertEquals("${case.label}: only a valid empty response is expected",
                            "没有识别到明确的账目，请写出用途和金额，例如：午饭 28，打车 15", e.message)
                    }
                } else {
                    val drafts = AiProtocol.response(raw, categories)
                    assertEquals("${case.label}: entry count", case.expected.size, drafts.size)
                    for (expected in case.expected) {
                        val matches = drafts.filter {
                            val detailMatches = it.note.contains(expected.detail) || (expected.detail == "午饭" && it.note.contains("午餐"))
                            detailMatches && it.date == expected.date
                        }
                        assertEquals("${case.label}: detail and date ($drafts)", 1, matches.size)
                        val draft = matches.single()
                        assertEquals(EntryKind.EXPENSE, draft.kind)
                        assertEquals(expected.cents, Money.parse(draft.amount))
                        expected.category?.let { assertEquals(it, draft.categoryId) }
                        expected.note?.let { assertEquals("${case.label}: standardized note", it, draft.note) }
                        assertNull(draft.id)
                    }
                    println("LIVE_TEXT_NOTES ${case.label} ${drafts.map { it.note }}")
                }
                println("LIVE_TEXT_ACCOUNTING ${case.label} model=${com.example.aiaccounting.domain.ai.AiLimits.MODEL} elapsedMs=${(System.nanoTime()-started)/1_000_000} passed=true reasoningTokens=$reasoningTokens")
            } catch (e: AssertionError) {
                failures += "${case.label}: ${e.message}"
            } catch (e: AiFailure) {
                failures += "${case.label}: ${e.message}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
