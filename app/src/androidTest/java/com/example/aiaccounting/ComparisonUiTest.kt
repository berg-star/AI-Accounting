package com.example.aiaccounting

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiaccounting.data.local.*
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.ui.*
import com.example.aiaccounting.ui.inquiry.InquiryViewModel
import com.example.aiaccounting.ui.theme.AIAccountingTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ComparisonUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var ledger: LedgerViewModel
    private lateinit var inquiry: InquiryViewModel
    private var behavior = "normal"
    private var calls = 0
    private var dark by mutableStateOf(false)
    private var fontScale by mutableFloatStateOf(1f)
    private val keys = object : ApiKeyStore {
        override fun read() = "isolated-test-credential"
        override fun write(key: String) = Unit
        override fun clear() = Unit
    }
    private val service = object : LedgerInquiry {
        override suspend fun understand(question: String, categories: List<AiCategory>, today: LocalDate): LedgerQuery {
            calls++
            return LedgerQuery(QueryMetric.COMPARE, "2026-10-01", "2026-10-15", EntryKind.EXPENSE,
                keywords = if(behavior == "empty") listOf("不存在的记录") else emptyList(),
                baselineFromDate = "2026-09-01", baselineToDate = "2026-09-15")
        }
        override suspend fun review(question: String, result: QueryResult, batch: List<EntryEntity>): QueryReview { calls++; return QueryReview() }
        override suspend fun explainComparison(question: String, result: QueryResult): ComparisonExplanation {
            calls++
            if (behavior == "wait-explanation") awaitCancellation()
            if (behavior == "fail-explanation") throw AiFailure("AI 解读超时，本地对比仍可查看")
            val c = result.comparison!!
            return ComparisonExplanation(c.categories.filter { it.delta > 0 }.take(3).map { it.categoryId },
                c.categories.filter { it.delta < 0 }.take(3).map { it.categoryId }, c.categories.all { it.delta == 0L })
        }
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        repo = LedgerRepository(db)
        runBlocking {
            repo.initialize()
            val food = repo.snapshot().categories.single { it.name == "餐饮" }.id
            val transport = repo.snapshot().categories.single { it.name == "交通" }.id
            repo.saveEntry(EntryDraft(amount = "150", categoryId = food, date = "2026-10-03", note = "本期聚餐"))
            repo.saveEntry(EntryDraft(amount = "30", categoryId = transport, date = "2026-10-02", note = "本期公交"))
            repo.saveEntry(EntryDraft(amount = "100", categoryId = food, date = "2026-09-03", note = "对照聚餐"))
            repo.saveEntry(EntryDraft(amount = "60", categoryId = transport, date = "2026-09-02", note = "对照公交"))
        }
        compose.setContent {
            ledger = androidx.lifecycle.viewmodel.compose.viewModel(factory = androidx.lifecycle.viewmodel.viewModelFactory {
                initializer { LedgerViewModel(repo, SavedStateHandle(), apiKeys = keys) }
            })
            inquiry = androidx.lifecycle.viewmodel.compose.viewModel(factory = androidx.lifecycle.viewmodel.viewModelFactory {
                initializer { InquiryViewModel(repo, SavedStateHandle(), service, keys) }
            })
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AIAccountingTheme(darkTheme = dark) { AccountingApp(ledger, inquiry) }
            }
        }
        compose.waitUntil(15_000) { ::ledger.isInitialized && !ledger.state.value.loading }
        compose.onNodeWithTag("open-search").performClick()
        compose.onNodeWithTag("open-inquiry").performClick()
    }
    @After fun cleanup() { db.close() }
    private fun scroll(matcher: SemanticsMatcher) = compose.onNodeWithTag("inquiry-list").performScrollToNode(matcher)
    private fun ask() {
        scroll(hasTestTag("inquiry-question"))
        compose.onNodeWithTag("inquiry-question").performTextReplacement("这个月比上个月多花在哪里？")
        compose.onNodeWithTag("inquiry-ask").performClick()
    }
    private fun complete() = compose.waitUntil(15_000) { inquiry.state.value.reviewStatus == ReviewStatus.COMPLETE }
    private fun retry() { scroll(hasTestTag("inquiry-review-retry")); compose.onNodeWithTag("inquiry-review-retry").performClick() }

    @Test fun localComparisonIsVisibleBeforeAiAndCategoryLinksFilterBothPeriodsWithoutChangingLedger() {
        behavior = "wait-explanation"; ask()
        compose.waitUntil(10_000) { inquiry.state.value.explaining }
        assertEquals(4, inquiry.state.value.checked)
        assertEquals(ReviewStatus.RUNNING, inquiry.state.value.reviewStatus)
        scroll(hasTestTag("inquiry-answer")); compose.onNodeWithTag("inquiry-answer").assertTextEquals("多花 ¥ 20.00")
        scroll(hasTestTag("comparison-current")); compose.onNodeWithTag("comparison-current").assertTextContains("180.00", substring = true)
        scroll(hasTestTag("comparison-baseline")); compose.onNodeWithTag("comparison-baseline").assertTextContains("160.00", substring = true)
        capture("comparison-local-result")
        scroll(hasText("取消复核")); compose.onNodeWithText("取消复核").performClick()
        behavior = "normal"; retry(); complete()
        scroll(hasTestTag("comparison-explanation")); capture("comparison-ai-explanation")
        val result = inquiry.state.value.result!!
        val food = result.comparison!!.categories.single { it.name == "餐饮" }
        scroll(hasTestTag("comparison-category-${food.categoryId}")); capture("comparison-category-changes")
        compose.onNodeWithTag("comparison-category-${food.categoryId}").performClick()
        compose.onNodeWithTag("comparison-category-filter").assertIsDisplayed()
        compose.onNodeWithTag("comparison-period-1").performClick()
        val current = result.rows.single { it.note == "本期聚餐" }
        scroll(hasTestTag("inquiry-entry-${current.id}")); compose.onNodeWithTag("inquiry-entry-${current.id}").assertIsDisplayed()
        scroll(hasTestTag("comparison-period-2")); compose.onNodeWithTag("comparison-period-2").performClick()
        val baseline = result.rows.single { it.note == "对照聚餐" }
        scroll(hasTestTag("inquiry-entry-${baseline.id}")); capture("comparison-filtered-baseline")
        scroll(hasTestTag("inquiry-exclude-${baseline.id}")); compose.onNodeWithTag("inquiry-exclude-${baseline.id}").performClick()
        compose.onNodeWithText("确认排除").performClick()
        assertEquals(ReviewStatus.OUTDATED, inquiry.state.value.reviewStatus)
        assertNull(inquiry.state.value.explanation)
        assertEquals(12000L, inquiry.state.value.result!!.comparison!!.delta)
        assertEquals(4, runBlocking { repo.snapshot().entries.size })
        assertNull(runBlocking { repo.snapshot().entries.single { it.id == baseline.id }.deletedAt })
        compose.runOnIdle { dark = true; fontScale = 1.5f }
        scroll(hasTestTag("comparison-current")); capture("comparison-dark-large-font")
        compose.onNodeWithTag("inquiry-ask").assertIsDisplayed()
        scroll(hasTestTag("comparison-period-2")); compose.onNodeWithTag("comparison-period-2").assertIsDisplayed()
        capture("comparison-filters-large-font")
    }

    @Test fun failedExplanationDoesNotClaimCompletionAndEditingRecalculatesWithoutPaidReplay() {
        behavior = "fail-explanation"; ask()
        compose.waitUntil(10_000) { inquiry.state.value.reviewStatus == ReviewStatus.FAILED }
        assertEquals(4, inquiry.state.value.checked)
        assertNull(inquiry.state.value.explanation)
        assertEquals(2000L, inquiry.state.value.result!!.comparison!!.delta)
        scroll(hasTestTag("inquiry-review-status")); capture("comparison-failed-explanation")
        behavior = "normal"; retry(); complete()
        val callsBeforeEdit = calls
        val entry = inquiry.state.value.result!!.rows.single { it.note == "本期聚餐" }
        scroll(hasTestTag("inquiry-entry-${entry.id}")); compose.onNodeWithTag("inquiry-entry-${entry.id}").performClick()
        val amount = hasSetTextAction() and hasText("金额（元）")
        compose.onNode(hasScrollAction()).performScrollToNode(amount)
        compose.onNode(amount).performTextReplacement("200")
        compose.onNodeWithText("保存这笔记录").performClick()
        compose.waitUntil(10_000) { ledger.state.value.page == Page.INQUIRY && inquiry.state.value.result?.comparison?.delta == 7000L }
        assertEquals(ReviewStatus.OUTDATED, inquiry.state.value.reviewStatus)
        assertNull(inquiry.state.value.explanation); assertEquals(callsBeforeEdit, calls)
    }

    @Test fun cancellationAndEmptyPeriodsKeepLocalResultsWithoutInventingGrowth() {
        behavior = "wait-explanation"; ask()
        compose.waitUntil(10_000) { inquiry.state.value.explaining }
        scroll(hasText("取消复核")); compose.onNodeWithText("取消复核").performClick()
        assertEquals(ReviewStatus.CANCELLED, inquiry.state.value.reviewStatus)
        assertFalse(inquiry.state.value.explaining); assertNull(inquiry.state.value.explanation)
        assertEquals(18000L, inquiry.state.value.result!!.comparison!!.current)
        behavior = "empty"; ask(); complete()
        assertTrue(inquiry.state.value.explanation!!.unchanged)
        assertEquals(0, inquiry.state.value.checked)
        assertNull(inquiry.state.value.result!!.comparison!!.percent)
        scroll(hasTestTag("comparison-percent")); compose.onNodeWithTag("comparison-percent").assertTextEquals("两期均为 0，无百分比变化")
        capture("comparison-empty-periods")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir("test-screenshots")!!.absolutePath
        File(output, "$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
