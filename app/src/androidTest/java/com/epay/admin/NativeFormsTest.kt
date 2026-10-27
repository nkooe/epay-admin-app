package com.epay.admin

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class NativeFormsTest {
    @get:Rule val app=createComposeRule()

    private fun captureCompactUi(name:String) {
        app.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        // Compose idleness does not include the Android dialog window fade.
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val file=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"compact-$name.png")
        file.outputStream().use{instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun orderMenuIsCompactAndEveryActionRunsOnce() {
        val clicked=mutableListOf<String>()
        val titles=listOf("订单退款","冻结订单","重新通知商户","改未完成")
        app.setContent{AdminTheme{Column(Modifier.width(300.dp).padding(16.dp)){
            Group{KeyValue("订单金额","¥1.00");Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("订单管理",Modifier.weight(1f))
                MoreActions(titles.map{title->UiAction(title,run={clicked.add(title)})},label="操作")
            }}
        }}}
        app.onNodeWithText("操作").performClick()
        val items=titles.map{app.onNodeWithTag("menu-item-$it").getUnclippedBoundsInRoot()}
        items.forEach{assertEquals(36f,(it.bottom-it.top).value,.6f)}
        assertEquals(36f,(items[1].top-items[0].top).value,.6f)
        assertEquals(36f,(items[2].top-items[1].top).value,.6f)
        assertEquals(36f,(items[3].top-items[2].top).value,.6f)
        val popup=app.onNodeWithTag("action-menu").getUnclippedBoundsInRoot()
        assertEquals(4f,(items.first().top-popup.top).value,.6f)
        assertEquals(4f,(popup.bottom-items.last().bottom).value,.6f)
        assertEquals(152f,(popup.bottom-popup.top).value,.6f)
        captureCompactUi("order-menu")
        titles.forEachIndexed{index,title->
            if(index>0)app.onNodeWithText("操作").performClick()
            app.onNodeWithText(title).performClick()
            app.runOnIdle{assertEquals(titles.take(index+1),clicked)}
            app.onNodeWithTag("menu-item-$title").assertDoesNotExist()
        }
    }

    @Test fun merchantMenuIsCompactAndEveryActionRunsOnce() {
        assertMenuActions("merchant-menu","管理",listOf("子通道管理","自定义支付参数","API 密钥","余额加扣款","重设登录密码","重置 API 密钥","生成 RSA 密钥","删除无业务商户"))
    }

    @Test fun channelMenuIsCompactAndEveryActionRunsOnce() {
        assertMenuActions("channel-menu","通道操作",listOf("基本设置","密钥配置","测试支付","订单记录","停用通道"),iconOnly=true)
    }

    private fun assertMenuActions(name:String,label:String,titles:List<String>,iconOnly:Boolean=false) {
        val clicked=mutableListOf<String>()
        app.setContent{AdminTheme{Column(Modifier.width(300.dp).padding(16.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(iconOnly)"支付通道"else"商户业务",Modifier.weight(1f));MoreActions(titles.map{title->UiAction(title,destructive=title.startsWith("删除"),run={clicked.add(title)})},label=label,iconOnly=iconOnly)}
        }}}
        if(iconOnly)app.onNodeWithContentDescription(label).performClick() else app.onNodeWithText(label).performClick()
        val items=titles.map{app.onNodeWithTag("menu-item-$it").getUnclippedBoundsInRoot()}
        items.zipWithNext().forEach{(first,next)->assertEquals(36f,(next.top-first.top).value,.6f)}
        assertTrue(items.all{(it.bottom-it.top).value>=36f})
        captureCompactUi(name)
        titles.forEachIndexed{index,title->
            if(index>0){if(iconOnly)app.onNodeWithContentDescription(label).performClick() else app.onNodeWithText(label).performClick()}
            app.onNodeWithText(title).performClick()
            app.runOnIdle{assertEquals(titles.take(index+1),clicked)}
            app.onNodeWithTag("action-menu").assertDoesNotExist()
        }
    }

    @Test fun menuDismissesOnBackOutsideTapAndBusyState() {
        val enabled=mutableStateOf(true)
        var clicks=0
        app.setContent{AdminTheme{Box(Modifier.fillMaxSize()){
            Box(Modifier.align(Alignment.TopEnd)){MoreActions(listOf(UiAction("查看记录"){clicks++}),enabled.value,label="管理")}
        }}}
        app.onNodeWithText("管理").performClick()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        app.onNodeWithTag("action-menu").assertIsDisplayed()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val backTime=android.os.SystemClock.uptimeMillis()
        listOf(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.ACTION_UP).forEach{action->
            instrumentation.uiAutomation.injectInputEvent(android.view.KeyEvent(backTime,android.os.SystemClock.uptimeMillis(),action,android.view.KeyEvent.KEYCODE_BACK,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,android.view.KeyEvent.FLAG_FROM_SYSTEM,android.view.InputDevice.SOURCE_KEYBOARD),true)
        }
        app.onNodeWithTag("action-menu").assertDoesNotExist()
        app.onNodeWithText("管理").performClick()
        app.onNodeWithTag("action-menu").assertIsDisplayed()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val metrics=instrumentation.targetContext.resources.displayMetrics
        val now=android.os.SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP).forEach{action->
            val event=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,16f,metrics.heightPixels*.7f,0)
            instrumentation.uiAutomation.injectInputEvent(event,true)
            event.recycle()
        }
        app.waitUntil(3000){app.onAllNodesWithTag("action-menu").fetchSemanticsNodes().isEmpty()}
        app.onNodeWithTag("action-menu").assertDoesNotExist()
        app.onNodeWithText("管理").performClick()
        app.runOnIdle{enabled.value=false}
        app.onNodeWithTag("action-menu").assertDoesNotExist()
        app.onNodeWithText("管理").assertIsNotEnabled()
        app.runOnIdle{assertEquals(0,clicks)}
    }

    @Test fun bottomAnchoredMenuAndScrollableOverflowRemainUsable() {
        val titles=mutableStateOf(listOf("基本设置","密钥配置","测试支付","订单记录","停用通道"))
        var selected=""
        app.setContent{AdminTheme{Box(Modifier.fillMaxSize().padding(16.dp).testTag("menu-host")){
            Box(Modifier.align(Alignment.BottomEnd)){MoreActions(titles.value.map{title->UiAction(title,run={selected=title})},label="通道操作",iconOnly=true)}
        }}}
        app.onNodeWithContentDescription("通道操作").performClick()
        app.onNodeWithText("基本设置").assertIsDisplayed()
        app.onNodeWithText("停用通道").assertIsDisplayed()
        captureCompactUi("bottom-menu")
        app.onNodeWithText("停用通道").performClick()
        app.runOnIdle{assertEquals("停用通道",selected);titles.value=(1..40).map{"操作项目 $it"}}
        app.onNodeWithContentDescription("通道操作").performClick()
        app.onNodeWithText("操作项目 40").performScrollTo().assertIsDisplayed()
        captureCompactUi("long-menu")
        app.onNodeWithText("操作项目 40").performClick()
        app.onNodeWithTag("action-menu").assertDoesNotExist()
        app.runOnIdle{assertEquals("操作项目 40",selected)}
    }

    @Test fun formRowsShareCompactSpacingAndRemainEditable() {
        val value=mutableStateOf("");var selected=0
        app.setContent{AdminTheme{Column(Modifier.width(300.dp).padding(16.dp)){Group{
            CompactInput("通道名称",value.value,{value.value=it})
            SelectionRow("启用状态","启用"){selected++}
            ActionLine("订单记录"){selected++}
            CollapsibleSection("高级配置"){Text("配置内容")}
            PrimaryAction("保存修改"){selected++}
        }}}}
        app.onNodeWithText("启用状态").assertHeightIsEqualTo(40.dp)
        app.onNodeWithText("订单记录").assertHeightIsEqualTo(40.dp)
        app.onNodeWithText("高级配置").assertHeightIsEqualTo(40.dp)
        captureCompactUi("form")
        app.onNodeWithContentDescription("通道名称").performTextInput("紧凑通道")
        app.onNodeWithText("启用状态").performClick()
        app.onNodeWithText("订单记录").performClick()
        app.onNodeWithText("高级配置").performClick()
        app.onNodeWithText("配置内容").assertIsDisplayed()
        app.onNodeWithText("保存修改").performClick()
        app.runOnIdle{assertEquals("紧凑通道",value.value);assertEquals(3,selected)}
    }

    @Test fun longMenuLabelsGrowWithLargeTextAndRemainClickable() {
        var clicks=0
        val title="查看当前商户全部支付通道的订单记录"
        app.setContent{AdminTheme{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)){
                MoreActions(listOf(UiAction(title){clicks++},UiAction("返回列表"){clicks++}),label="操作")
            }
        }}
        app.onNodeWithText("操作").performClick()
        val item=app.onNodeWithTag("menu-item-$title").getUnclippedBoundsInRoot()
        val label=app.onNodeWithText(title,useUnmergedTree=true).getUnclippedBoundsInRoot()
        assertTrue(item.bottom-item.top>36.dp)
        assertTrue(label.top>=item.top&&label.bottom<=item.bottom)
        app.onNodeWithText(title).performClick()
        app.onNodeWithText("操作").performClick()
        app.onNodeWithText("返回列表").performClick()
        app.runOnIdle{assertEquals(2,clicks)}
    }

    @Test fun dialogButtonsStayAdjacentAndLargeTextIsUsable() {
        var confirmed=0;var cancelled=0
        app.setContent{AdminTheme{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) {
                ConfirmDialog("删除订单？","订单号：2026102000000000001\n删除后不可恢复。",false,{cancelled++},confirmLabel="删除",destructive=true){confirmed++}
            }
        }}
        val cancel=app.onNode(hasText("取消") and hasClickAction()).getUnclippedBoundsInRoot()
        val confirm=app.onNode(hasText("删除",substring=false) and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue((confirm.left-cancel.right).value in 0f..8f)
        app.onNodeWithText("删除后不可恢复。",substring=true).assertIsDisplayed()
        captureCompactUi("dialog")
        app.onNodeWithText("删除",substring=false).performClick()
        app.runOnIdle{assertEquals(1,confirmed);assertEquals(0,cancelled)}
    }

    @Test fun pluginCheckboxAndNativeQrAreUsable() {
        val value=mutableStateOf("[]")
        val field=obj("key" to "flags","label" to "支付能力","type" to "checkbox","options" to obj("a" to "扫码支付","b" to "手机支付"))
        app.setContent { AdminTheme { androidx.compose.foundation.layout.Column {
            FieldEditor(field,value.value,true){value.value=it}
            NativeQrCode("https://example.test/authorization?id=fixture")
        } } }
        app.onNodeWithText("扫码支付").performClick()
        app.runOnIdle { assertEquals("[\"a\"]",value.value) }
        app.onNodeWithText("手机支付").performClick()
        app.runOnIdle { assertEquals(2,org.json.JSONArray(value.value).length()) }
        app.waitUntil(10000){app.onAllNodesWithContentDescription("链接二维码").fetchSemanticsNodes().isNotEmpty()}
        app.onNodeWithContentDescription("链接二维码").assertIsDisplayed()
    }

    @Test fun compactFormRemainsEditableAndActionsReachableWithLargeText() {
        val text=mutableStateOf("")
        val selected=mutableStateOf("0")
        var refreshes=0
        app.setContent { AdminTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) {
                Column(Modifier.width(280.dp)) {
                    CompactInput("通道名称",text.value,{text.value=it})
                    FieldEditor(org.json.JSONObject("""{"type":"select","label":"启用状态","options":["停用","启用"]}"""),selected.value,true){selected.value=it}
                    MoreActions(listOf(UiAction("刷新列表"){refreshes++}),label="页面操作",iconOnly=true)
                }
            }
        } }
        app.onNodeWithContentDescription("通道名称").performTextInput("新通道")
        app.runOnIdle { assertEquals("新通道",text.value) }
        app.onNodeWithText("停用").performClick()
        app.onNodeWithText("启用").performClick()
        app.runOnIdle { assertEquals("1",selected.value) }
        app.onNodeWithText("刷新列表").assertDoesNotExist()
        app.onNodeWithContentDescription("页面操作").performClick()
        app.onNodeWithText("刷新列表").assertIsDisplayed().performClick()
        app.runOnIdle { assertEquals(1,refreshes) }
    }
}
