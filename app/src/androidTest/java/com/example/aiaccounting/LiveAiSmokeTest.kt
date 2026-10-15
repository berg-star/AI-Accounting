package com.example.aiaccounting

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Paid network calls are opt-in. Normal emulator/phone regression skips this test before reading any key. */
@RunWith(AndroidJUnit4::class)
class LiveAiSmokeTest {
    @Test fun realModelExtractsDetailsAndRelativeDatesWithoutInventingEntries() = runBlocking {
        assumeTrue("Live API calls require explicit allowLiveAi=true", InstrumentationRegistry.getArguments().getString("allowLiveAi") == "true")
        val app = ApplicationProvider.getApplicationContext<AccountingApplication>()
        assertFalse("请先在手机 App 的 AI 设置中保存 Key", app.apiKeys.read().isNullOrBlank())
        // Synthetic categories and text only; never open the user's ledger or save any drafts.
        val categories = listOf(
            AiCategory(1, EntryKind.EXPENSE, "餐饮", false),
            AiCategory(2, EntryKind.EXPENSE, "交通", false),
            AiCategory(3, EntryKind.INCOME, "工资", false),
            AiCategory(4, EntryKind.EXPENSE, "其他支出", true),
            AiCategory(5, EntryKind.INCOME, "其他收入", true),
        )
        val today = LocalDate.now()
        var start = System.nanoTime()
        val first = app.textAccounting.parse("今天中午在老街面馆吃牛肉面28元，坐地铁去公司6元。", categories, today)
        report("food-and-transport", start, first)
        assertEquals(2, first.size)
        val food = first.single { Money.parse(it.amount) == 2800L }
        val ride = first.single { Money.parse(it.amount) == 600L }
        assertEquals(1L, food.categoryId); assertEquals(2L, ride.categoryId)
        assertTrue(food.note.contains("牛肉面") && food.note.contains("老街面馆"))
        assertTrue(ride.note.contains("地铁") && ride.note.contains("公司"))
        assertTrue(first.all { it.kind == EntryKind.EXPENSE && it.date == today.toString() })

        start = System.nanoTime()
        val second = app.textAccounting.parse("昨天收到十月工资8000元，今天坐公交车去图书馆花了2元。", categories, today)
        report("income-relative-date-and-bus", start, second)
        assertEquals(2, second.size)
        val salary = second.single { it.kind == EntryKind.INCOME }
        val bus = second.single { it.kind == EntryKind.EXPENSE }
        assertEquals(800000L, Money.parse(salary.amount)); assertEquals(3L, salary.categoryId)
        assertEquals(today.minusDays(1).toString(), salary.date)
        assertTrue(salary.note.contains("工资"))
        assertEquals(200L, Money.parse(bus.amount)); assertEquals(2L, bus.categoryId)
        assertEquals(today.toString(), bus.date)
        assertTrue(bus.note.contains("公交") && bus.note.contains("图书馆"))

        start = System.nanoTime()
        try {
            app.textAccounting.parse("午饭花了一些钱，金额记不清了。打算明天买一杯20元的咖啡，还没有买。", categories, today)
            fail("Ambiguous amounts and future plans must not produce entries")
        } catch (e: AiFailure) {
            assertTrue("Expected an empty-ledger result, not a network failure: ${e.message}", e.message?.contains("没有识别到明确的账目") == true)
        }
        report("ambiguous-and-future-plan-rejected", start, emptyList())
    }

    private fun report(name: String, start: Long, drafts: List<EntryDraft>) {
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val safeResults = drafts.joinToString("; ") { "${it.kind} ${it.amount} category=${it.categoryId} date=${it.date} note=${it.note}" }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nLIVE_AI $name elapsedMs=$elapsed $safeResults\n")
        })
    }
}
