package com.example.aiaccounting

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiaccounting.data.local.*
import com.example.aiaccounting.data.repository.LedgerSnapshot
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class LiveInquirySmokeTest {
    @Test fun realQueryAndReviewUseSyntheticLedgerAndFlagKeywordFalsePositive() = runBlocking {
        assumeTrue("Live API calls require explicit allowLiveAi=true", InstrumentationRegistry.getArguments().getString("allowLiveAi") == "true")
        val app = ApplicationProvider.getApplicationContext<AccountingApplication>()
        assertFalse("请先在手机 App 的 AI 设置中保存 Key", app.apiKeys.read().isNullOrBlank())
        val today = LocalDate.now()
        val last = YearMonth.from(today).minusMonths(1)
        val categories = listOf(
            CategoryEntity(1, EntryKind.EXPENSE, "交通", "transport", "blue", 0),
            CategoryEntity(2, EntryKind.EXPENSE, "餐饮", "food", "orange", 1),
            CategoryEntity(3, EntryKind.EXPENSE, "居住", "home", "blue", 2),
        )
        val data = LedgerSnapshot(categories, listOf(
            EntryEntity(1, EntryKind.EXPENSE, 600, 1, today.toString(), "地铁去公司", 1, 1),
            EntryEntity(2, EntryKind.EXPENSE, 1800, 1, today.toString(), "地铁站旁买早餐豆浆油条", 1, 1),
            EntryEntity(3, EntryKind.EXPENSE, 200000, 3, last.atDay(2).toString(), "上个月房租", 1, 1),
            EntryEntity(4, EntryKind.EXPENSE, 1500, 1, last.atDay(3).toString(), "打车回家", 1, 1),
        ))
        val catalog = categories.map { AiCategory(it.id, it.kind, it.name, false) }
        var started = System.nanoTime()
        val question = "这个月坐地铁花了多少？"
        val query = app.ledgerInquiry.understand(question, catalog, today)
        report("metro-plan", started, query.toString())
        assertEquals(QueryMetric.SUM, query.metric); assertEquals(EntryKind.EXPENSE, query.kind)
        assertEquals(YearMonth.from(today).atDay(1).toString(), query.fromDate)
        assertEquals(YearMonth.from(today).atEndOfMonth().toString(), query.toDate)
        assertTrue(query.keywords.any { it.contains("地铁") })
        val result = query.execute(data)
        assertEquals(setOf(1L,2L), result.rows.map { it.id }.toSet()); assertEquals(2400L, result.expense)
        started = System.nanoTime()
        val review = app.ledgerInquiry.review(question, result, result.rows)
        report("metro-review", started, review.toString())
        assertTrue("Expected breakfast near a station to be flagged", review.flags.any { it.entryId == 2L })
        assertFalse("A clear metro ride should not be flagged", review.flags.any { it.entryId == 1L })
        assertEquals(2400L, result.expense)
        assertEquals(600L, query.execute(data, setOf(2)).expense)

        started = System.nanoTime()
        val maxQuestion = "上个月最大的一笔支出是什么？"
        val maximum = app.ledgerInquiry.understand(maxQuestion, catalog, today)
        report("maximum-plan", started, maximum.toString())
        assertEquals(QueryMetric.MAX, maximum.metric)
        assertEquals(last.atDay(1).toString(), maximum.fromDate); assertEquals(last.atEndOfMonth().toString(), maximum.toDate)
        val maximumResult = maximum.execute(data)
        assertEquals(3L, maximumResult.featured.single().id)
        started = System.nanoTime()
        val maximumReview = app.ledgerInquiry.review(maxQuestion, maximumResult, maximumResult.rows)
        report("maximum-review", started, maximumReview.toString())
        assertTrue(maximumReview.flags.isEmpty())
        assertNull(maximumReview.scopeWarning)
        assertEquals(4, data.entries.size)
    }

    private fun report(name: String, start: Long, safeResult: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nLIVE_INQUIRY $name elapsedMs=${(System.nanoTime()-start)/1_000_000} $safeResult\n")
        })
    }
}
