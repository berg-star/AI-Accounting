package com.example.aiaccounting

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiaccounting.data.local.*
import com.example.aiaccounting.data.report.MonthlyReportFileCache
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.ui.*
import com.example.aiaccounting.ui.report.MonthlyReportViewModel
import com.example.aiaccounting.ui.theme.AIAccountingTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class MonthlyReportUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var ledger: LedgerViewModel
    private lateinit var monthly: MonthlyReportViewModel
    private lateinit var disk: MonthlyReportFileCache
    private lateinit var folder: File
    private val month = YearMonth.of(2026,10)
    private var clock = LocalDate.of(2026,10,15)
    private var behavior = "normal"
    private var calls = 0
    private var writeFails = false
    private var credential: String? = "isolated-test-credential"
    private var dark by mutableStateOf(false)
    private var scale by mutableFloatStateOf(1f)
    private val keys = object : ApiKeyStore {
        override fun read() = credential
        override fun write(key: String) { credential = key }
        override fun clear() { credential = null }
    }
    private val cache = object : MonthlyReportCache {
        override fun read(month: YearMonth) = disk.read(month)
        override fun write(report: SavedMonthlyReport) { if(writeFails) throw java.io.IOException("Synthetic disk failure"); disk.write(report) }
        override fun clear() = disk.clear()
    }
    private val service = MonthlyReportGenerator { report ->
        calls++
        when(behavior) {
            "wait" -> awaitCancellation()
            "fail" -> throw AiFailure("AI 服务暂不可用，请重试")
            "invalid" -> listOf(ReportConclusion(ReportClaimKind.OVERVIEW),ReportConclusion(ReportClaimKind.CATEGORY,99999))
            else -> buildList {
                add(ReportConclusion(ReportClaimKind.OVERVIEW))
                if(report.categories.isNotEmpty()) add(ReportConclusion(ReportClaimKind.CATEGORY,report.categories.first().id))
                if(report.largest.isNotEmpty()) add(ReportConclusion(ReportClaimKind.TOP_EXPENSE,report.largest.first().id))
                report.comparison?.comparison?.categories?.firstOrNull { it.name=="交通" && it.delta!=0L }?.let { add(ReportConclusion(ReportClaimKind.CATEGORY_CHANGE,it.categoryId)) }
            }
        }
    }
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        folder = File(context.cacheDir,"monthly-report-test-${System.nanoTime()}").apply { mkdirs() }
        disk = MonthlyReportFileCache(folder)
        db = Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        repo = LedgerRepository(db)
        runBlocking {
            repo.initialize()
            val cats = repo.snapshot().categories
            val food = cats.single { it.name=="餐饮" }.id; val traffic = cats.single { it.name=="交通" }.id
            val home = cats.single { it.name=="居住" }.id; val salary = cats.single { it.name=="工资" }.id
            repo.saveEntry(EntryDraft(amount="1800",categoryId=home,date="2026-10-01",note="本期房租"))
            repo.saveEntry(EntryDraft(amount="40",categoryId=food,date="2026-10-03",note="米饭套餐"))
            repo.saveEntry(EntryDraft(amount="20",categoryId=food,date="2026-10-04",note="牛肉面"))
            repo.saveEntry(EntryDraft(amount="6",categoryId=traffic,date="2026-10-05",note="本期地铁"))
            repo.saveEntry(EntryDraft(kind=EntryKind.INCOME,amount="5000",categoryId=salary,date="2026-10-02",note="本期工资"))
            repo.saveEntry(EntryDraft(amount="9999",categoryId=food,date="2026-10-20",note="未来记录"))
            repo.saveEntry(EntryDraft(amount="1800",categoryId=home,date="2026-09-01",note="对照房租"))
            repo.saveEntry(EntryDraft(amount="40",categoryId=food,date="2026-09-03",note="对照午饭"))
            repo.saveEntry(EntryDraft(amount="12",categoryId=traffic,date="2026-09-05",note="对照地铁"))
        }
        compose.setContent {
            ledger = androidx.lifecycle.viewmodel.compose.viewModel(factory=androidx.lifecycle.viewmodel.viewModelFactory {
                initializer { LedgerViewModel(repo,SavedStateHandle(mapOf("month" to month.toString())),apiKeys=keys) }
            })
            monthly = androidx.lifecycle.viewmodel.compose.viewModel(factory=androidx.lifecycle.viewmodel.viewModelFactory {
                initializer { MonthlyReportViewModel(repo,SavedStateHandle(mapOf("reportMonth" to month.toString())),service,keys,cache,{ clock }) }
            })
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
                AIAccountingTheme(darkTheme=dark) { AccountingApp(ledger,monthly=monthly) }
            }
        }
        compose.waitUntil(15_000) { ::ledger.isInitialized && !ledger.state.value.loading }
        compose.onNodeWithText("统计",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("open-report").performClick()
        ready()
    }
    @After fun cleanup() { db.close(); folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete() }
    private fun ready(expected: YearMonth = month) = compose.waitUntil(10_000) {
        monthly.state.value.month==expected && monthly.state.value.report?.month==expected && !monthly.state.value.loadingCache
    }
    private fun scroll(m: SemanticsMatcher) = compose.onNodeWithTag("report-list").performScrollToNode(m)
    private fun generate() { scroll(hasTestTag("report-generate")); compose.onNodeWithTag("report-generate").performClick() }
    private fun saved() = compose.waitUntil(10_000) { !monthly.state.value.generating && monthly.state.value.saved!=null }

    @Test fun offlineOverviewAndCategoryEvidenceNeedNoKeyAndExcludeFutureEntries() {
        credential = null
        assertEquals(0,calls)
        scroll(hasTestTag("report-expense")); compose.onNodeWithTag("report-expense").assertTextEquals("支出 ¥ 1,866.00")
        scroll(hasTestTag("report-income")); compose.onNodeWithTag("report-income").assertTextEquals("收入 ¥ 5,000.00")
        assertEquals("2026-10-15",monthly.state.value.report!!.to)
        capture("report-local-overview")
        val food = monthly.state.value.report!!.categories.single { it.name=="餐饮" }
        scroll(hasTestTag("report-category-${food.id}")); capture("report-categories")
        compose.onNodeWithTag("report-category-${food.id}").performClick()
        compose.onNodeWithTag("report-evidence-title").assertTextEquals("结论依据 · 2 笔")
        capture("report-category-evidence")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        generate()
        compose.waitUntil(5_000) { monthly.state.value.error!=null }
        assertEquals(0,calls); assertNull(disk.read(month)); assertEquals(186600L,monthly.state.value.report!!.expense)
    }

    @Test fun savedReportReopensOfflineAndEditingBaselineKeepsReportMonthAndInvalidatesSummary() {
        generate(); saved()
        assertEquals(1,calls)
        assertEquals(monthly.state.value.saved,disk.read(month))
        lateinit var restored: MonthlyReportViewModel
        val store = ViewModelStore()
        compose.runOnIdle {
            restored = MonthlyReportViewModel(repo,SavedStateHandle(mapOf("reportMonth" to month.toString())),service,keys,MonthlyReportFileCache(folder),{ clock })
            store.put("restored",restored)
        }
        compose.waitUntil(10_000) { restored.state.value.saved!=null }
        assertEquals(1,calls); compose.runOnIdle { store.clear() }
        scroll(hasTestTag("report-claim-3")); capture("report-ai-summary")
        compose.onNodeWithTag("report-claim-3").performClick()
        compose.onNodeWithTag("report-evidence-title").assertTextEquals("结论依据 · 2 笔")
        val baseline = monthly.state.value.report!!.comparison!!.rows.single { it.note=="对照地铁" }
        compose.onNodeWithTag("report-evidence-list").performScrollToNode(hasTestTag("report-evidence-${baseline.id}"))
        capture("report-comparison-evidence")
        compose.onNodeWithTag("report-evidence-${baseline.id}").performClick()
        val amount = hasSetTextAction() and hasText("金额（元）")
        compose.onNode(hasScrollAction()).performScrollToNode(amount)
        compose.onNode(amount).performTextReplacement("10")
        compose.onNodeWithText("保存这笔记录").performClick()
        compose.waitUntil(10_000) { ledger.state.value.page==Page.REPORT && monthly.state.value.stale && !monthly.state.value.loadingCache }
        assertEquals(month,ledger.state.value.month); assertEquals(month,monthly.state.value.month)
        assertEquals(1,calls); assertNull(monthly.state.value.saved)
        assertEquals(-400L,monthly.state.value.report!!.comparison!!.comparison!!.categories.single { it.name=="交通" }.delta)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("记录已保存").fetchSemanticsNodes().isEmpty() }
        scroll(hasTestTag("report-stale")); capture("report-outdated")
        generate(); saved(); assertEquals(2,calls)
        compose.runOnIdle { dark=true; scale=1.5f }
        scroll(hasTestTag("report-claim-3")); capture("report-dark-large-font")
    }

    @Test fun cancellationInvalidEvidenceAndSaveFailureKeepLocalStatisticsAndDoNotPersistFalseSuccess() {
        behavior="wait"; generate()
        compose.waitUntil(5_000) { monthly.state.value.generating }
        scroll(hasTestTag("report-cancel")); compose.onNodeWithTag("report-cancel").performClick()
        assertFalse(monthly.state.value.generating); assertNull(disk.read(month))
        behavior="fail"; generate()
        compose.waitUntil(5_000) { monthly.state.value.error!=null }
        assertNull(monthly.state.value.saved)
        behavior="invalid"; generate()
        compose.waitUntil(5_000) { !monthly.state.value.generating && monthly.state.value.error!=null }
        assertNull(disk.read(month))
        behavior="normal"; writeFails=true; generate()
        compose.waitUntil(5_000) { !monthly.state.value.generating && monthly.state.value.error!=null }
        assertNull(monthly.state.value.saved); assertNull(disk.read(month))
        assertEquals(186600L,monthly.state.value.report!!.expense)
        capture("report-generation-error")
        writeFails=false; generate(); saved()
    }

    @Test fun monthChangesCancelRequestsAndDateOrRestoreChangesInvalidateSavedConclusions() {
        scroll(hasContentDescription("下个月")); compose.onNodeWithContentDescription("下个月").performClick(); ready(month.plusMonths(1))
        assertFalse(monthly.state.value.report!!.available)
        scroll(hasTestTag("report-generate")); compose.onNodeWithTag("report-generate").assertIsNotEnabled()
        scroll(hasContentDescription("上个月")); compose.onNodeWithContentDescription("上个月").performClick(); ready()
        scroll(hasContentDescription("上个月")); compose.onNodeWithContentDescription("上个月").performClick(); ready(month.minusMonths(1))
        assertEquals("2026-09-30",monthly.state.value.report!!.to)
        behavior="wait"; generate(); compose.waitUntil(5_000) { monthly.state.value.generating }
        scroll(hasContentDescription("下个月")); compose.onNodeWithContentDescription("下个月").performClick(); ready()
        assertFalse(monthly.state.value.generating); assertNull(disk.read(month.minusMonths(1)))
        behavior="normal"; generate(); saved()
        val priorCalls=calls
        compose.runOnIdle { clock=LocalDate.of(2026,10,20); monthly.refreshDate() }
        compose.waitUntil(10_000) { monthly.state.value.report?.to=="2026-10-20" && !monthly.state.value.loadingCache }
        assertTrue(monthly.state.value.stale); assertNull(monthly.state.value.saved); assertEquals(priorCalls,calls)
        assertEquals(1186500L,monthly.state.value.report!!.expense)
        compose.runOnIdle { monthly.clearSavedReports(1) }; ready()
        assertNull(disk.read(month)); assertNull(monthly.state.value.saved); assertEquals(priorCalls,calls)
        generate(); saved()
        compose.runOnIdle { monthly.clearSavedReports(1) }
        assertNotNull(monthly.state.value.saved); assertNotNull(disk.read(month))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: folder.absolutePath
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output,"$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
