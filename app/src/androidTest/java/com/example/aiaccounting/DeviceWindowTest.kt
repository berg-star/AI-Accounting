package com.example.aiaccounting

import android.graphics.Bitmap
import android.graphics.Rect
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the real activity/window and native keyboard without saving any test record. */
@RunWith(AndroidJUnit4::class)
class DeviceWindowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if(node==null)return null
        if(predicate(node))return node
        for(i in 0 until node.childCount) { find(node.getChild(i),predicate)?.let { return it } }
        return null
    }
    private fun waitFor(message:String,condition:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+10_000
        while(SystemClock.uptimeMillis()<deadline) { if(condition())return;SystemClock.sleep(50) }
        fail(message)
    }
    private fun textNode(text:String):AccessibilityNodeInfo? {
        val root=instrumentation.uiAutomation.rootInActiveWindow
        if(root?.packageName?.toString()!="com.example.aiaccounting")return null
        return find(root) { it.text?.toString()==text || it.contentDescription?.toString()==text }
    }
    private fun tap(node:AccessibilityNodeInfo) {
        val bounds=Rect();node.getBoundsInScreen(bounds)
        val now=SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0))
        instrumentation.sendPointerSync(MotionEvent.obtain(now,now+50,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name:String) {
        assertEquals("com.example.aiaccounting",instrumentation.uiAutomation.rootInActiveWindow.packageName.toString())
        val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val file=File(output,"$name.png");file.parentFile!!.mkdirs()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion=30)
    @Test fun nativeKeyboardDoesNotCoverSaveButton() {
        assumeTrue(Build.VERSION.SDK_INT>=30)
        val automation=instrumentation.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply {
            flags=flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("Real app did not show add-entry button") {
                val found=textNode("记一笔")!=null
                if(!found) {
                    val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    if(output!=null) {
                        var description="rootPackage=${automation.rootInActiveWindow?.packageName}"
                        scenario.onActivity { activity ->
                            description+="; lifecycle=${activity.lifecycle.currentState}"
                            runCatching { androidx.lifecycle.ViewModelProvider(activity)[com.example.aiaccounting.ui.LedgerViewModel::class.java] }.getOrNull()?.let { vm -> description+="; page=${vm.state.value.page}; loading=${vm.state.value.loading}; error=${vm.state.value.error}" }
                        }
                        File(output,"window-readiness.txt").apply { parentFile!!.mkdirs();writeText(description) }
                    }
                }
                found
            }
            screenshot("real-home")
            listOf(Triple("记一笔", "保存这笔记录", "real-keyboard"),
                Triple("AI 记一笔", "识别账目", "real-ai-keyboard"),
                Triple("用一句话查账", "开始查账", "real-inquiry-keyboard")).forEach { (entryPoint, action, capture) ->
            if(entryPoint=="用一句话查账") {
                tap(textNode("搜索账目")!!)
                waitFor("Inquiry entry point did not appear") { textNode(entryPoint)!=null }
            }
            tap(textNode(entryPoint)!!)
            var field:AccessibilityNodeInfo?=null
            waitFor("Editable field not found") {
                field=find(instrumentation.uiAutomation.rootInActiveWindow) { it.packageName?.toString()=="com.example.aiaccounting"&&it.isEditable }
                field!=null
            }
            tap(field!!)
            var visible=false
            var keyboardTop=0
            waitFor("Native keyboard did not open") {
                scenario.onActivity { activity ->
                    val decor=activity.window.decorView
                    val insets=decor.rootWindowInsets
                    visible=insets?.isVisible(WindowInsets.Type.ime())==true
                    val location=IntArray(2);decor.getLocationOnScreen(location)
                    keyboardTop=location[1]+decor.height-(insets?.getInsets(WindowInsets.Type.ime())?.bottom?:0)
                }
                visible
            }
            // IME visibility changes before its animation and Compose layout finish.
            // Re-read both geometries until the unobscured layout is stable.
            var previousGeometry:Pair<Int,Int>?=null
            var stableSamples=0
            waitFor("$action button is covered by native keyboard") {
                scenario.onActivity { activity ->
                    val decor=activity.window.decorView
                    val insets=decor.rootWindowInsets
                    visible=insets?.isVisible(WindowInsets.Type.ime())==true
                    val location=IntArray(2);decor.getLocationOnScreen(location)
                    keyboardTop=location[1]+decor.height-(insets?.getInsets(WindowInsets.Type.ime())?.bottom?:0)
                }
                val bounds=Rect()
                textNode(action)?.getBoundsInScreen(bounds)
                val geometry=keyboardTop to bounds.bottom
                val clear=visible&&bounds.height()>0&&bounds.top>=0&&bounds.bottom<=keyboardTop+2
                stableSamples=if(clear&&geometry==previousGeometry)stableSamples+1 else 0
                previousGeometry=geometry
                stableSamples>=3
            }
            screenshot(capture)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("Keyboard did not close") {
                scenario.onActivity { visible=it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true };!visible
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            if(entryPoint=="用一句话查账") {
                waitFor("Inquiry should return to search") { textNode("用一句话查账")!=null }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
            waitFor("Empty draft should return to home") { textNode("记一笔")!=null }
            }
        }
    }
}
