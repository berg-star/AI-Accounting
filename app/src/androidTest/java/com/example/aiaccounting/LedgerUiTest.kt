package com.example.aiaccounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aiaccounting.data.local.LedgerDatabase
import com.example.aiaccounting.data.repository.LedgerRepository
import com.example.aiaccounting.ui.*
import com.example.aiaccounting.ui.theme.AIAccountingTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import android.graphics.Bitmap

@RunWith(AndroidJUnit4::class)
class LedgerUiTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var db:LedgerDatabase
    private lateinit var vm:LedgerViewModel
    private var dark by mutableStateOf(false)
    private var fontScale by mutableFloatStateOf(1f)
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),LedgerDatabase::class.java).build()
        compose.setContent {
            vm=androidx.lifecycle.viewmodel.compose.viewModel(factory=androidx.lifecycle.viewmodel.viewModelFactory { initializer { LedgerViewModel(LedgerRepository(db),SavedStateHandle()) } })
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale)) {
                AIAccountingTheme(darkTheme=dark) { AccountingApp(vm) }
            }
        }
        compose.waitUntil(15_000) { ::vm.isInitialized && !vm.state.value.loading }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("add-entry").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
        }
    }
    @After fun cleanup() { db.close() }

    private fun capture(name:String) {
        compose.waitForIdle()
        val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir("test-screenshots")!!.absolutePath
        val destination=File(output,"$name.png")
        destination.parentFile!!.mkdirs()
        val image=compose.onRoot().captureToImage().asAndroidBitmap()
        destination.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun entryDraftSurvivesCategoryManagementAndSavesExactlyOnce() {
        capture("home-empty")
        compose.onNodeWithTag("add-entry").performClick()
        compose.onNode(hasSetTextAction() and hasText("金额（元）")).performClick().performTextInput("25.01")
        compose.onNodeWithText("保存这笔记录").assertIsDisplayed()
        capture("entry-keyboard")
        compose.onNodeWithText("管理分类").performScrollTo().performClick()
        capture("categories")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNode(hasSetTextAction() and hasText("金额（元）")).assertTextContains("25.01")
        capture("entry-draft")
        compose.onNodeWithText("保存这笔记录").performClick()
        compose.waitUntil(10_000) { vm.state.value.page==Page.HOME && vm.state.value.data.entries.size==1 }
        compose.onNodeWithText("¥ 25.01").assertIsDisplayed()
        capture("home-record")
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithText("¥ 25.01").assertIsDisplayed()
        capture("statistics")
    }

    @Test fun categoryEditorSettingsDarkThemeAndLargeFontRemainUsable() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("导出完整备份").assertIsDisplayed()
        capture("settings")
        compose.onNodeWithText("分类管理").performClick()
        compose.onNodeWithText("餐饮").performClick()
        compose.onNode(hasSetTextAction() and hasText("分类名称")).performTextClearance()
        compose.onNode(hasSetTextAction() and hasText("分类名称")).performTextInput("吃喝")
        capture("category-editor")
        compose.onNodeWithText("保存分类").performClick()
        compose.waitUntil(10_000) { vm.state.value.page==Page.CATEGORIES && vm.state.value.data.categories.any { it.name=="吃喝" } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("分类已保存").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        compose.onNodeWithText("分类已保存").onParent().onChildren().filter(hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("吃喝").assertIsDisplayed()
        compose.onNodeWithText("吃喝").performClick()
        // On smaller screens LazyColumn has not composed this off-screen item yet.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("停用分类"))
        compose.onNodeWithText("停用分类").assertIsDisplayed().performClick()
        capture("category-stop-result")
        compose.waitUntil(10_000) { vm.state.value.error!=null || (vm.state.value.page==Page.CATEGORIES && vm.state.value.data.categories.none { it.name=="吃喝"&&it.active }) }
        org.junit.Assert.assertNull(vm.state.value.error)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("明细").performClick()
        compose.runOnIdle { dark=true }
        compose.onNodeWithTag("add-entry").assertIsDisplayed()
        capture("home-dark")
        compose.runOnIdle { fontScale=1.5f }
        compose.onNodeWithTag("add-entry").performClick()
        compose.onNode(hasSetTextAction() and hasText("金额（元）")).performClick().performTextInput("9.99")
        compose.onNodeWithText("保存这笔记录").assertIsDisplayed()
        capture("entry-large-font")
    }
}
