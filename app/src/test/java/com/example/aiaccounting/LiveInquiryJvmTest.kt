package com.example.aiaccounting

import com.example.aiaccounting.data.local.CategoryEntity
import com.example.aiaccounting.data.local.EntryEntity
import com.example.aiaccounting.data.remote.SiliconFlowCompletion
import com.example.aiaccounting.data.remote.SiliconFlowLedgerInquiry
import com.example.aiaccounting.data.remote.SiliconFlowTextAccounting
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.ai.AiCategory
import com.example.aiaccounting.domain.ai.ApiKeyStore
import com.example.aiaccounting.domain.model.EntryKind
import com.example.aiaccounting.domain.model.QueryMetric
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Opt-in host-side acceptance test. Never reads a device database or device credential. */
class LiveInquiryJvmTest {
    @Test fun realTextAccountingQueryAndReviewWithSyntheticData() = runBlocking {
        assumeTrue("Paid API tests require explicit Gradle opt-in", System.getenv("AI_ACCOUNTING_LIVE_INQUIRY") == "true")
        val keyPath = System.getenv("AI_ACCOUNTING_LIVE_KEY_FILE").orEmpty()
        check(keyPath.isNotBlank()) { "Provide a local credential file path" }
        val credential = File(keyPath).readText(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        check(credential.isNotBlank() && credential.none { it.isWhitespace() }) { "The local credential must be one nonempty line" }
        val keys = object : ApiKeyStore {
            override fun read() = credential
            override fun write(key: String) = error("Read-only test credential")
            override fun clear() = error("Read-only test credential")
        }
        val service = SiliconFlowLedgerInquiry(SiliconFlowCompletion(keys))
        val today = LocalDate.now()
        val last = YearMonth.from(today).minusMonths(1)
        val categories = listOf(
            CategoryEntity(1, EntryKind.EXPENSE, "交通", "transport", "blue", 0),
            CategoryEntity(2, EntryKind.EXPENSE, "餐饮", "food", "orange", 1),
            CategoryEntity(3, EntryKind.EXPENSE, "居住", "home", "blue", 2),
        )
        val catalog = categories.map { AiCategory(it.id, it.kind, it.name, false) }
        val data = LedgerSnapshot(categories, listOf(
            EntryEntity(1, EntryKind.EXPENSE, 600, 1, today.toString(), "地铁去公司", 1, 1),
            EntryEntity(2, EntryKind.EXPENSE, 1800, 1, today.toString(), "地铁站旁买早餐豆浆油条", 1, 1),
            EntryEntity(3, EntryKind.EXPENSE, 200000, 3, last.atDay(2).toString(), "上个月房租", 1, 1),
            EntryEntity(4, EntryKind.EXPENSE, 1500, 1, last.atDay(3).toString(), "打车回家", 1, 1),
        ))
        val question = "这个月坐地铁花了多少？"
        val query = timed("metro-plan") { service.understand(question, catalog, today) }
        assertEquals(QueryMetric.SUM, query.metric)
        assertEquals(EntryKind.EXPENSE, query.kind)
        assertEquals(YearMonth.from(today).atDay(1).toString(), query.fromDate)
        assertEquals(YearMonth.from(today).atEndOfMonth().toString(), query.toDate)
        assertTrue(query.keywords.any { it.contains("地铁") })
        val result = query.execute(data)
        assertEquals(setOf(1L, 2L), result.rows.map { it.id }.toSet())
        assertEquals(2400L, result.expense)
        val review = timed("metro-review") { service.review(question, result, result.rows) }
        assertTrue("Station breakfast must be flagged", review.flags.any { it.entryId == 2L })
        assertFalse("A clear metro ride must not be flagged", review.flags.any { it.entryId == 1L })
        assertEquals(2400L, result.expense)
        assertEquals(600L, query.execute(data, setOf(2)).expense)

        val maxQuestion = "上个月最大的一笔支出是什么？"
        val maximum = timed("maximum-plan") { service.understand(maxQuestion, catalog, today) }
        assertEquals(QueryMetric.MAX, maximum.metric)
        assertEquals(last.atDay(1).toString(), maximum.fromDate)
        assertEquals(last.atEndOfMonth().toString(), maximum.toDate)
        val maximumResult = maximum.execute(data)
        assertEquals(listOf(3L, 4L), maximumResult.rows.map { it.id })
        assertEquals(3L, maximumResult.featured.single().id)
        val maximumReview = timed("maximum-review") { service.review(maxQuestion, maximumResult, maximumResult.rows) }
        assertTrue("A lower-ranked candidate is valid: ${maximumReview.flags}", maximumReview.flags.isEmpty())
        assertNull(maximumReview.scopeWarning)

        val drafts = timed("detailed-text-accounting") {
            SiliconFlowTextAccounting(keys).parse("早上在小巷早餐店吃豆浆油条12元，坐公交去医院2元", catalog, today)
        }
        assertEquals(2, drafts.size)
        val breakfast = drafts.single { it.categoryId == 2L }
        assertTrue(breakfast.note.contains("小巷早餐店") && breakfast.note.contains("豆浆") && breakfast.note.contains("油条"))
        val bus = drafts.single { it.categoryId == 1L }
        assertTrue(bus.note.contains("公交") && bus.note.contains("医院"))
        assertEquals(4, data.entries.size)
    }

    private suspend fun <T> timed(phase: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        return block().also { println("LIVE_INQUIRY $phase elapsedMs=${(System.nanoTime() - start) / 1_000_000} $it") }
    }
}
