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
class InquiryUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var ledger: LedgerViewModel
    private lateinit var inquiry: InquiryViewModel
    private val saved = SavedStateHandle()
    private var behavior = "normal"
    private var calls = 0
    private var dark by mutableStateOf(false)
    private var scale by mutableFloatStateOf(1f)
    private var credential: String? = "isolated-test-credential"
    private val keys = object : ApiKeyStore {
        override fun read() = credential
        override fun write(key: String) { credential = key }
        override fun clear() { credential = null }
    }
    private val service = object : LedgerInquiry {
        override suspend fun understand(question: String, categories: List<AiCategory>, today: LocalDate): LedgerQuery {
            calls++
            when (behavior) {
                "wait-plan" -> awaitCancellation()
                "clarify" -> throw AiFailure("请补充问题：请明确最近是多少天")
            }
            return LedgerQuery(QueryMetric.SUM, kind = EntryKind.EXPENSE, keywords = listOf(if(behavior == "empty") "不存在" else "地铁"))
        }
        override suspend fun review(question: String, result: QueryResult, batch: List<EntryEntity>): QueryReview {
            calls++
            val secondBatch = batch.size == 1
            if (behavior == "wait-second" && secondBatch) awaitCancellation()
            if (behavior == "fail-second" && secondBatch) throw AiFailure("请求超时，复核尚未完成")
            return QueryReview(flags = batch.filter { it.note.contains("早餐") && it.id !in result.excludedIds }.map { QueryFlag(it.id, "地铁站旁买早餐属于餐饮，可能不是乘坐地铁的费用") })
        }
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        repo = LedgerRepository(db)
        runBlocking {
            repo.initialize()
            val transport = repo.snapshot().categories.single { it.name == "交通" }
            repeat(20) { i -> repo.saveEntry(EntryDraft(amount = "6", categoryId = transport.id, date = if(i < 10) "2026-09-12" else LocalDate.now().toString(), note = "地铁去公司${i+1}")) }
            repo.saveEntry(EntryDraft(amount = "18", categoryId = transport.id, note = "地铁站旁买早餐"))
            repo.saveEntry(EntryDraft(amount = "5", categoryId = transport.id, note = "公交回家"))
        }
        compose.setContent {
            ledger = androidx.lifecycle.viewmodel.compose.viewModel(factory = androidx.lifecycle.viewmodel.viewModelFactory {
                initializer { LedgerViewModel(repo, SavedStateHandle(), apiKeys = keys) }
            })
            inquiry = androidx.lifecycle.viewmodel.compose.viewModel(factory = androidx.lifecycle.viewmodel.viewModelFactory {
                initializer { InquiryViewModel(repo, saved, service, keys) }
            })
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                AIAccountingTheme(darkTheme = dark) { AccountingApp(ledger, inquiry) }
            }
        }
        compose.waitUntil(15_000) { ::ledger.isInitialized && !ledger.state.value.loading }
        compose.onNodeWithTag("open-search").performClick()
        compose.onNodeWithTag("open-inquiry").performClick()
    }
    @After fun cleanup() { db.close() }

    private fun scroll(matcher: SemanticsMatcher) { compose.onNodeWithTag("inquiry-list").performScrollToNode(matcher) }
    private fun ask() {
        compose.onNodeWithTag("inquiry-question").performTextReplacement("所有历史坐地铁花了多少？")
        compose.onNodeWithTag("inquiry-ask").performClick()
    }
    private fun complete() { compose.waitUntil(15_000) { inquiry.state.value.reviewStatus == ReviewStatus.COMPLETE } }

    @Test fun resultsAreVisibleBeforeReviewCompletesAndPartialFailureNeverClaimsSuccess() {
        behavior = "wait-second"
        ask()
        compose.waitUntil(10_000) { inquiry.state.value.checked == 20 }
        assertEquals(21, inquiry.state.value.result!!.rows.size)
        scroll(hasTestTag("inquiry-answer"))
        compose.onNodeWithTag("inquiry-answer").assertTextEquals("¥ 138.00")
        scroll(hasTestTag("inquiry-review-status"))
        compose.onNodeWithTag("inquiry-review-status").assertTextContains("20/21 笔", substring = true)
        capture("inquiry-partial-review")
        scroll(hasText("取消复核")); compose.onNodeWithText("取消复核").performClick()
        assertEquals(ReviewStatus.CANCELLED, inquiry.state.value.reviewStatus)
        behavior = "fail-second"
        scroll(hasTestTag("inquiry-review-retry")); compose.onNodeWithTag("inquiry-review-retry").performClick()
        compose.waitUntil(10_000) { inquiry.state.value.reviewStatus == ReviewStatus.FAILED }
        assertEquals(20, inquiry.state.value.checked)
        assertEquals(13800L, inquiry.state.value.result!!.expense)
        capture("inquiry-failed-review")
        behavior = "normal"
        scroll(hasTestTag("inquiry-review-retry")); compose.onNodeWithTag("inquiry-review-retry").performClick()
        complete()
        assertEquals(21, inquiry.state.value.checked)
        assertEquals(1, inquiry.state.value.flags.size)
        val bad = inquiry.state.value.result!!.rows.single { it.note.contains("早餐") }
        scroll(hasTestTag("inquiry-exclude-${bad.id}"))
        capture("inquiry-flagged-record")
        compose.onNodeWithTag("inquiry-exclude-${bad.id}").performClick()
        compose.onNodeWithText("确认排除").performClick()
        assertEquals(ReviewStatus.OUTDATED, inquiry.state.value.reviewStatus)
        assertEquals(12000L, inquiry.state.value.result!!.expense)
        assertEquals(21, inquiry.state.value.result!!.rows.size)
        assertEquals(22, runBlocking { repo.snapshot().entries.size })
        assertNull(runBlocking { repo.snapshot().entries.single { it.id == bad.id }.deletedAt })
        scroll(hasTestTag("inquiry-review-retry")); compose.onNodeWithTag("inquiry-review-retry").performClick()
        complete()
        assertTrue(inquiry.state.value.flags.isEmpty())
        assertEquals(12000L, inquiry.state.value.result!!.expense)
    }

    @Test fun editingAnyReturnedRecordRecalculatesAndInvalidatesReviewWithoutAutomaticPaidCalls() {
        ask(); complete()
        val entry = inquiry.state.value.result!!.rows.last()
        val priorCalls = calls
        scroll(hasTestTag("inquiry-entry-${entry.id}"))
        compose.onNodeWithTag("inquiry-entry-${entry.id}").performClick()
        val note = hasSetTextAction() and hasText("备注（选填）")
        compose.onNode(hasScrollAction()).performScrollToNode(note)
        compose.onNode(note).performTextReplacement("公交去公司")
        compose.onNodeWithText("保存这笔记录").performClick()
        compose.waitUntil(10_000) { ledger.state.value.page == Page.INQUIRY && inquiry.state.value.result?.rows?.size == 20 }
        assertEquals(ReviewStatus.OUTDATED, inquiry.state.value.reviewStatus)
        assertEquals(13200L, inquiry.state.value.result!!.expense)
        assertEquals(priorCalls, calls)
        assertEquals("所有历史坐地铁花了多少？", inquiry.state.value.question)
        compose.runOnIdle { dark = true; scale = 1.5f }
        compose.onNodeWithTag("inquiry-ask").assertIsDisplayed()
        scroll(hasTestTag("inquiry-review-status")); capture("inquiry-dark-large-font")
        scroll(hasTestTag("inquiry-answer")); capture("inquiry-local-result")
    }

    @Test fun missingKeyClarificationCancellationAndSavedQuestionDoNotInventResults() {
        credential = null
        ask()
        compose.waitUntil(5_000) { inquiry.state.value.error != null }
        assertEquals(0, calls); assertNull(inquiry.state.value.result)
        credential = "isolated-test-credential"
        behavior = "clarify"
        compose.onNodeWithTag("inquiry-ask").performClick()
        compose.waitUntil(5_000) { inquiry.state.value.error?.startsWith("请补充问题") == true }
        assertNull(inquiry.state.value.result)
        behavior = "wait-plan"
        compose.onNodeWithTag("inquiry-ask").performClick()
        compose.waitUntil(5_000) { inquiry.state.value.understanding }
        compose.onNodeWithText("取消查账").performClick()
        assertFalse(inquiry.state.value.understanding)
        compose.runOnIdle {
            val restored = InquiryViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), service, keys)
            assertEquals(inquiry.state.value.question, restored.state.value.question)
            assertNull(restored.state.value.result)
            assertFalse(restored.state.value.understanding)
            androidx.lifecycle.ViewModelStore().apply { put("restored-inquiry", restored); clear() }
        }
    }

    @Test fun emptyResultsStillReviewScopeWithoutPretendingThereAreRows() {
        behavior = "empty"; ask(); complete()
        assertEquals(0, inquiry.state.value.checked)
        assertEquals(1, inquiry.state.value.completedBatches)
        assertTrue(inquiry.state.value.result!!.rows.isEmpty())
        scroll(hasTestTag("inquiry-answer")); compose.onNodeWithTag("inquiry-answer").assertTextEquals("¥ 0.00")
        scroll(hasTestTag("inquiry-review-status")); capture("inquiry-empty-result")
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
