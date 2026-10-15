package com.example.aiaccounting

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.example.aiaccounting.data.local.LedgerDatabase
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.domain.ai.*
import com.example.aiaccounting.domain.model.*
import com.example.aiaccounting.ui.*
import com.example.aiaccounting.ui.theme.AIAccountingTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var vm: LedgerViewModel
    private val saved = SavedStateHandle()
    private var behavior = "success"
    private var calls = 0
    private var dark by mutableStateOf(false)
    private var scale by mutableFloatStateOf(1f)
    private val keys = object : ApiKeyStore {
        private var value: String? = "local-test-credential"
        override fun read() = value
        override fun write(key: String) { value = key }
        override fun clear() { value = null }
    }
    private val parser = TextAccounting { _, categories, today ->
        calls++
        when (behavior) {
            "wait" -> awaitCancellation()
            "error" -> throw AiFailure("请求超时，输入已保留，可以重试")
            else -> listOf(
                EntryDraft(amount = "28", categoryId = categories.single { it.name == "餐饮" }.id, date = today.toString(), note = "午餐：老街面馆牛肉面"),
                EntryDraft(amount = "6", categoryId = categories.single { it.name == "交通" }.id, date = today.toString(), note = "地铁去公司"),
            )
        }
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        repo = LedgerRepository(db)
        compose.setContent {
            vm = androidx.lifecycle.viewmodel.compose.viewModel(factory = androidx.lifecycle.viewmodel.viewModelFactory {
                initializer { LedgerViewModel(repo, saved, parser, keys) }
            })
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                AIAccountingTheme(darkTheme = dark) { AccountingApp(vm) }
            }
        }
        compose.waitUntil(15_000) { ::vm.isInitialized && !vm.state.value.loading }
    }
    @After fun cleanup() { db.close() }

    private fun inputAndRecognize() {
        compose.onNodeWithTag("open-ai").performClick()
        compose.onNodeWithTag("ai-input").performTextInput("中午在老街面馆吃牛肉面28，坐地铁去公司6")
        capture("ai-input")
        compose.onNodeWithTag("ai-primary").performClick()
        compose.waitUntil(10_000) { vm.state.value.page == Page.AI_REVIEW }
    }

    @Test fun detailedDraftsStayUnwrittenUntilConfirmedAndEditsAndRemovalAreRespected() {
        inputAndRecognize()
        assertTrue(runBlocking { repo.snapshot().entries.isEmpty() })
        compose.onNodeWithText("午餐：老街面馆牛肉面").assertIsDisplayed()
        capture("ai-review")
        compose.onNodeWithTag("ai-edit-0").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(Page.AI_REVIEW, vm.state.value.page)
        assertNull(vm.state.value.confirmation)
        compose.onNodeWithTag("ai-edit-0").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("金额（元）")).performTextReplacement("30.50")
        compose.onNodeWithText("确认草稿修改").performClick()
        compose.waitUntil(10_000) { vm.state.value.page == Page.AI_REVIEW }
        assertEquals("午餐：老街面馆牛肉面", vm.state.value.aiDrafts.first().note)
        assertTrue(runBlocking { repo.snapshot().entries.isEmpty() })
        compose.onNodeWithTag("ai-remove-1").performScrollTo().performClick()
        assertEquals(1, vm.state.value.aiDrafts.size)
        compose.onNodeWithTag("ai-primary").performClick()
        compose.runOnIdle { vm.saveAiDrafts() }
        compose.waitUntil(10_000) { vm.state.value.page == Page.HOME && vm.state.value.data.entries.size == 1 }
        val record = runBlocking { repo.snapshot().entries.single() }
        assertEquals(3050L, record.cents)
        assertEquals("午餐：老街面馆牛肉面", record.note)
        assertTrue(vm.state.value.aiDrafts.isEmpty()); assertEquals("", vm.state.value.aiText)
        assertEquals(1, calls)
        capture("ai-saved-home")
    }

    @Test fun cancellationAndNetworkErrorRetainTextAndRetryCreatesOnlyDrafts() {
        behavior = "wait"
        compose.onNodeWithTag("open-ai").performClick()
        compose.onNodeWithTag("ai-input").performTextInput("午饭牛肉面28，坐地铁6")
        compose.onNodeWithTag("ai-primary").performClick()
        compose.waitUntil(5_000) { vm.state.value.recognizing }
        compose.onNodeWithText("取消识别").performClick()
        assertFalse(vm.state.value.busy)
        assertEquals("午饭牛肉面28，坐地铁6", vm.state.value.aiText)
        behavior = "error"
        compose.onNodeWithTag("ai-primary").performClick()
        compose.waitUntil(5_000) { !vm.state.value.busy && vm.state.value.error != null }
        compose.onNodeWithText("请求超时，输入已保留，可以重试").assertIsDisplayed()
        capture("ai-network-error")
        behavior = "success"
        compose.onNodeWithTag("ai-primary").performClick()
        compose.waitUntil(10_000) { vm.state.value.page == Page.AI_REVIEW }
        assertTrue(runBlocking { repo.snapshot().entries.isEmpty() })
        assertEquals(2, vm.state.value.aiDrafts.size)
    }

    @Test fun draftsSurviveSavedStateAndLargeFontDarkReviewRemainsUsable() {
        inputAndRecognize()
        compose.runOnIdle { dark = true; scale = 1.5f }
        compose.onNodeWithTag("ai-primary").assertIsDisplayed()
        compose.onNodeWithTag("ai-draft-1").performScrollTo().assertIsDisplayed()
        capture("ai-review-dark-large-font")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("open-ai").performClick()
        assertEquals(Page.AI_REVIEW, vm.state.value.page)
        compose.runOnIdle {
            val restored = LedgerViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), parser, keys)
            assertEquals(Page.AI_REVIEW, restored.state.value.page)
            assertEquals(vm.state.value.aiText, restored.state.value.aiText)
            assertEquals(vm.state.value.aiDrafts, restored.state.value.aiDrafts)
            assertFalse(restored.state.value.busy)
            assertTrue(saved.keys().none { it.contains("credential", true) || it.contains("apiKey", true) })
            // Clear the observer before closing this test's database.
            val store = androidx.lifecycle.ViewModelStore()
            store.put("restored", restored)
            store.clear()
        }
    }

    @Test fun keyCanBeConfiguredAndRemovedThroughUiAndMissingKeyKeepsInput() {
        compose.onNodeWithTag("open-ai").performClick()
        compose.onNodeWithTag("ai-settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("移除本机 Key"))
        compose.onNodeWithText("移除本机 Key").performClick()
        compose.onNodeWithText("移除 Key").performClick()
        compose.waitUntil(5_000) { !vm.state.value.busy && !vm.state.value.keyConfigured }
        assertNull(keys.read())
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("ai-input").performTextInput("午饭牛肉面28")
        compose.onNodeWithTag("ai-primary").performClick()
        assertEquals("午饭牛肉面28", vm.state.value.aiText)
        assertEquals(0, calls)
        assertTrue(vm.state.value.error!!.contains("API Key"))
        compose.onNodeWithTag("ai-settings").performClick()
        compose.onNodeWithTag("api-key-input").performTextInput("replacement-test-credential")
        compose.onNodeWithText("加密保存 Key").performScrollTo().performClick()
        compose.waitUntil(5_000) { !vm.state.value.busy && vm.state.value.keyConfigured }
        assertEquals("replacement-test-credential", keys.read())
        assertEquals("", compose.onNodeWithTag("api-key-input").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals("午饭牛肉面28", vm.state.value.aiText)
        assertTrue(runBlocking { repo.snapshot().entries.isEmpty() })
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir("test-screenshots")!!.absolutePath
        val file = File(output, "$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
