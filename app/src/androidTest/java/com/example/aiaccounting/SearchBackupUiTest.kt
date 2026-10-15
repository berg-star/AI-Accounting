package com.example.aiaccounting

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import com.example.aiaccounting.data.backup.*
import com.example.aiaccounting.data.local.LedgerDatabase
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.domain.ai.ApiKeyStore
import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.ui.*
import com.example.aiaccounting.ui.theme.AIAccountingTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SearchBackupUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var vm: LedgerViewModel
    private lateinit var recovery: RecoveryBackupStore
    private lateinit var files: File
    private var dark by mutableStateOf(false)
    private var scale by mutableFloatStateOf(1f)
    private val saved = SavedStateHandle()
    private var credential: String? = "isolated-test-credential"
    private val keys = object : ApiKeyStore {
        override fun read() = credential
        override fun write(key: String) { credential = key }
        override fun clear() { credential = null }
    }

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        repo = LedgerRepository(db)
        files = File(context.cacheDir, "search-backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        recovery = RecoveryBackupStore(File(files, "recovery.json"))
        runBlocking {
            repo.initialize()
            val categories = repo.snapshot().categories
            val food = categories.single { it.name == "餐饮" }
            val transport = categories.single { it.name == "交通" }
            val income = categories.first { it.kind == EntryKind.INCOME }
            repo.saveEntry(EntryDraft(amount = "28", categoryId = food.id, date = "2026-09-12", note = "午餐牛肉面"))
            repo.saveEntry(EntryDraft(amount = "6", categoryId = transport.id, note = "地铁去公司"))
            repo.saveEntry(EntryDraft(kind = EntryKind.INCOME, amount = "8000", categoryId = income.id, note = "测试工资"))
            val deleted = repo.saveEntry(EntryDraft(amount = "9", categoryId = food.id, note = "删除的牛肉面"))
            repo.deleteEntry(deleted)
            repo.setCategoryActive(food.id, false)
        }
        compose.setContent {
            vm = androidx.lifecycle.viewmodel.compose.viewModel(factory = androidx.lifecycle.viewmodel.viewModelFactory {
                initializer { LedgerViewModel(repo, saved, apiKeys = keys, recoveryBackups = recovery) }
            })
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                AIAccountingTheme(darkTheme = dark) { AccountingApp(vm) }
            }
        }
        compose.waitUntil(15_000) { ::vm.isInitialized && !vm.state.value.loading }
    }

    @After fun cleanup() { db.close(); files.listFiles()?.forEach { it.delete() }; files.delete() }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNodeWithTag("search-list").performScrollToNode(matcher)
    }

    @Test fun crossMonthSearchFindsArchivedCategoryAndEditingReturnsToRetainedQuery() {
        compose.onNodeWithTag("open-search").performClick()
        compose.onNodeWithTag("search-keyword").performTextInput("牛肉面")
        compose.onNodeWithTag("search-keyword").performImeAction()
        scrollTo(hasTestTag("search-count"))
        compose.onNodeWithTag("search-count").assertTextEquals("找到 1 笔记录")
        val id = vm.state.value.data.entries.single { it.note == "午餐牛肉面" }.id
        scrollTo(hasTestTag("search-entry-$id"))
        capture("search-cross-month")
        compose.onNodeWithTag("search-entry-$id").performClick()
        val noteField = hasSetTextAction() and hasText("备注（选填）")
        compose.onNode(hasScrollAction()).performScrollToNode(noteField)
        compose.onNode(noteField).performTextReplacement("午餐盖饭")
        compose.onNodeWithText("保存这笔记录").performClick()
        compose.waitUntil(10_000) { vm.state.value.page == Page.SEARCH && !vm.state.value.busy }
        assertEquals("牛肉面", vm.state.value.search.keyword)
        scrollTo(hasTestTag("search-count"))
        compose.onNodeWithTag("search-count").assertTextEquals("找到 0 笔记录")
        scrollTo(hasText("清空条件"))
        compose.onNodeWithText("清空条件").performClick()
        scrollTo(hasTestTag("search-count"))
        compose.onNodeWithTag("search-count").assertTextEquals("找到 3 笔记录")
        assertEquals("午餐盖饭", runBlocking { repo.snapshot().entries.single { it.id == id }.note })
    }

    @Test fun combinedFiltersAndSavedStateWorkInLargeFontDarkMode() {
        compose.onNodeWithTag("open-search").performClick()
        compose.onNodeWithText("展开筛选").performClick()
        compose.onNodeWithText("支出", useUnmergedTree = true).performClick()
        scrollTo(hasTestTag("search-min"))
        compose.onNodeWithTag("search-min").performTextInput("10")
        compose.onNodeWithTag("search-max").performTextInput("30")
        // Date/category matching is also exercised with inclusive bounds by the JVM tests.
        compose.runOnIdle { vm.changeSearch { it.copy(fromDate = "2026-09-01", toDate = "2026-09-30") } }
        scrollTo(hasTestTag("search-count"))
        compose.onNodeWithTag("search-count").assertTextEquals("找到 1 笔记录")
        compose.runOnIdle { dark = true; scale = 1.5f }
        scrollTo(hasTestTag("search-count"))
        capture("search-dark-large-font")
        compose.runOnIdle {
            val restored = LedgerViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
            assertEquals(Page.SEARCH, restored.state.value.page)
            assertEquals(vm.state.value.search, restored.state.value.search)
            androidx.lifecycle.ViewModelStore().apply { put("restored", restored); clear() }
        }
        scrollTo(hasTestTag("search-max"))
        compose.onNodeWithTag("search-max").performTextReplacement("5")
        scrollTo(hasText("最低金额不能大于最高金额"))
        compose.onNodeWithText("最低金额不能大于最高金额").assertIsDisplayed()
    }

    @Test fun previewCancelInvalidFileAndConfirmedRestoreKeepKeyAndPreserveExportableOriginal() {
        val before = runBlocking { repo.snapshot() }
        val incomingJson = BackupCodec.encode(before.categories, listOf(before.entries.first().copy(id = 100, note = "备份中的单条记录", deletedAt = null)), "0.3.0")
        compose.runOnIdle {
            vm.navigate(Page.SETTINGS)
            vm.previewBackup { incomingJson.byteInputStream() }
        }
        compose.waitUntil(10_000) { vm.state.value.page == Page.BACKUP_PREVIEW && !vm.state.value.busy }
        assertEquals(before, runBlocking { repo.snapshot() })
        compose.onNodeWithText("备份已通过校验").assertIsDisplayed()
        capture("backup-preview")
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(Page.SETTINGS, vm.state.value.page)
        assertEquals(before, runBlocking { repo.snapshot() })
        compose.runOnIdle { vm.previewBackup { "{\"format\":\"invalid\"}".byteInputStream() } }
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.error != null }
        assertEquals(Page.SETTINGS, vm.state.value.page)
        assertEquals(before, runBlocking { repo.snapshot() })
        compose.runOnIdle {
            vm.changeSearch { it.copy(keyword = "old search") }
            vm.changeAiText("未确认的测试草稿")
            vm.previewBackup { incomingJson.byteInputStream() }
        }
        compose.waitUntil(10_000) { vm.state.value.page == Page.BACKUP_PREVIEW && !vm.state.value.busy }
        compose.runOnIdle { dark = true; scale = 1.5f }
        compose.onNodeWithTag("restore-backup").assertIsDisplayed()
        capture("backup-preview-dark-large-font")
        compose.onNodeWithTag("restore-backup").performClick()
        compose.waitUntil(10_000) { vm.state.value.page == Page.SETTINGS && !vm.state.value.busy }
        assertEquals("备份中的单条记录", runBlocking { repo.snapshot().entries.single().note })
        assertEquals("isolated-test-credential", keys.read())
        assertEquals(SearchFilters(), vm.state.value.search)
        assertEquals("", vm.state.value.aiText)
        assertTrue(vm.state.value.hasRecoveryBackup)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("导出恢复前备份"))
        compose.onNodeWithText("导出恢复前备份").assertIsDisplayed()
        val preserved = BackupCodec.decode(recovery.read())
        assertEquals(before.entries, preserved.entries)
        val exported = File(files, "exported.json")
        compose.runOnIdle { vm.exportBackup(ApplicationProvider.getApplicationContext<Context>().contentResolver, Uri.fromFile(exported), recovery = true) }
        compose.waitUntil(10_000) { !vm.state.value.busy }
        assertNull(vm.state.value.error)
        assertEquals(before.entries, BackupCodec.decode(exported.readText()).entries)
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
