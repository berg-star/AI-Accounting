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

/** Ten opt-in real requests; synthetic data only, no database or device access. */
class LiveTextAccountingJvmTest {
    @Test fun paidFeesKeepDatesAndDetailsWithoutInventingExpenses() = runBlocking {
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
        )
        val today = LocalDate.now()
        val yesterday = today.minusDays(1).toString()
        val historical = today.minusMonths(2).withDayOfMonth(15)
        data class Expected(val cents: Long, val date: String, val detail: String, val category: Long)
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
        )
        for (case in cases) {
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
                    assertEquals("${case.label}: detail and date", 1, matches.size)
                    val draft = matches.single()
                    assertEquals(EntryKind.EXPENSE, draft.kind)
                    assertEquals(expected.cents, Money.parse(draft.amount))
                    assertEquals(expected.category, draft.categoryId)
                    assertNull(draft.id)
                }
            }
            println("LIVE_TEXT_ACCOUNTING ${case.label} model=${com.example.aiaccounting.domain.ai.AiLimits.MODEL} elapsedMs=${(System.nanoTime()-started)/1_000_000} passed=true reasoningTokens=$reasoningTokens")
        }
    }
}
