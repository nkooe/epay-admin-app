package com.epay.admin

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeCapabilitiesUiTest {
    @get:Rule val app=createComposeRule()
    private class Fixture(val mode:String):Source {
        val writes=mutableListOf<JSONObject>()
        var reads=0
        var failMonitorAfterFirst=false
        var keyDelay=0L
        var keyCompleted=false
        val keyReads=mutableListOf<String>()
        var schemaReads=0
        override suspend fun call(route:String,data:JSONObject,post:Boolean,key:String?):JSONObject {
            if(post){writes.add(JSONObject(data.toString()));return when(data.s("action")){
                "receipt-shops"->obj("ticket" to "shop-ticket","rows" to JSONArray().put(obj("selection" to "0","shop_id" to "123","shop_name" to "门店甲","account_name" to "收款账号甲")))
                else->obj("message" to "已完成")
            }}
            if(route=="plugins/schema"){schemaReads++;return obj("id" to "901","plugin" to "jktongypc","name" to "PC 微信","available" to true,"site" to "https://pay.example.com","revision" to "fixture-revision","config_generator" to (mode=="generator"),"monitor" to (mode=="monitor"||mode=="monitor-fail"),"receipt_shops" to (mode=="receipt"),"automatic_config" to (mode=="automatic"),"login_url" to "https://example.test/alipayopen/login","shared_session" to (mode=="shared"))}
            if(route=="merchants/monitor-options")return obj("rows" to JSONArray().put(obj("uid" to "1000","username" to "商户甲")))
            if(route=="merchants/monitor-key"){keyReads.add(data.s("uid"));kotlinx.coroutines.delay(keyDelay);keyCompleted=true;return obj("key" to "fixture-read-key")}
            if(route=="plugins/monitor"){
                reads++
                if(failMonitorAfterFirst&&reads>1)throw ApiFailure("诊断接口暂时不可用",503)
                return obj("online" to (mode=="monitor-fail"),"channel_enabled" to true,"heartbeat_enabled" to true,"merchant_id" to 1000,"timeout" to 65,"reason" to "心跳已超时","last_seen" to 1700000000,"server_time" to 1700000100,"reported" to obj("last_seen" to 1700000000,"values" to obj("receipt_sid" to if(data.s("reveal")=="1")"fixture-sid-visible"else"已上报（已隐藏）")))
            }
            if(route=="channels/config")return obj("id" to "901","revision" to "fixture-revision","fields" to JSONArray()
                .put(obj("key" to "pay_mode","label" to "收款方式","type" to "select","value" to "self","options" to obj("self" to "固定码","receipt" to "收款单")))
                .put(obj("key" to "qrcode","label" to "固定收款地址","type" to "text","value" to "preserve-code","show_when" to obj("pay_mode" to JSONArray(listOf("self")))))
                .put(obj("key" to "receipt_sid","label" to "收款单 SID","type" to "text","secret" to true,"value" to "","show_when" to obj("pay_mode" to JSONArray(listOf("receipt"))))))
            return obj()
        }
    }
    private fun model(fixture:Fixture,route:String="plugin"):AdminModel {
        lateinit var result:AdminModel
        app.runOnIdle{result=AdminModel(ApplicationProvider.getApplicationContext<Application>(),fixture);result.go(route,"插件管理",obj("id" to "901"))}
        return result
    }
    private fun screenshot(name:String){
        val context=ApplicationProvider.getApplicationContext<Application>()
        File(context.filesDir,"native-$name.png").outputStream().use{app.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun configurationGeneratesQrLocallyWithoutSendingMerchantKey(){
        val fixture=Fixture("generator");val model=model(fixture)
        app.setContent{AdminTheme{PluginPage(model)}}
        app.onNodeWithContentDescription("商户号").performTextInput("1000")
        app.onNodeWithContentDescription("商户 V1 MD5 密钥").performTextInput("fixture-key")
        app.onNodeWithText("生成配置和二维码").performScrollTo().performClick()
        app.waitUntil(10000){app.onAllNodesWithContentDescription("监控配置二维码").fetchSemanticsNodes().isNotEmpty()}
        app.onNodeWithText("复制监控配置").assertExists()
        app.onNodeWithContentDescription("监控配置二维码").performScrollTo();screenshot("configuration")
        app.runOnIdle{assertTrue(fixture.writes.isEmpty());model.clearSession()}
    }
    @Test fun merchantChangeDiscardsPendingKeyAndClearsPreviousKey(){
        val fixture=Fixture("generator").apply{keyDelay=1500};val model=model(fixture)
        app.setContent{AdminTheme{PluginPage(model)}}
        app.onNodeWithContentDescription("商户号").performTextInput("1000")
        app.onNodeWithText("读取所选商户密钥").performScrollTo().performClick()
        app.waitUntil(10000){fixture.keyReads.isNotEmpty()}
        app.onNodeWithContentDescription("商户号").performTextReplacement("1001")
        app.waitUntil(10000){fixture.keyCompleted};app.waitForIdle()
        app.onNodeWithText("生成配置和二维码").performScrollTo().performClick()
        app.onNodeWithText("请填写商户 V1 MD5 密钥").assertExists()
        app.onNodeWithContentDescription("商户 V1 MD5 密钥").performTextInput("manual-key")
        app.onNodeWithContentDescription("商户号").performTextReplacement("1002")
        app.onNodeWithText("生成配置和二维码").performScrollTo().performClick()
        app.onNodeWithText("请填写商户 V1 MD5 密钥").assertExists()
        app.runOnIdle{assertEquals(listOf("1000"),fixture.keyReads);assertTrue(fixture.writes.isEmpty());model.clearSession()}
    }
    @Test fun sharedSessionSyncRequiresConfirmationAndRefreshesSchema(){
        val fixture=Fixture("shared");val model=model(fixture)
        app.setContent{AdminTheme{PluginPage(model)}}
        app.onNodeWithText("同步共享 AppSession").performScrollTo().performClick()
        app.runOnIdle{assertTrue(fixture.writes.isEmpty())}
        app.onNodeWithText("确认").performClick()
        app.waitUntil(10000){fixture.schemaReads>=2}
        app.runOnIdle{val write=fixture.writes.single();assertEquals("shared-session-sync",write.s("action"));assertEquals("fixture-revision",write.s("revision"));model.clearSession()}
    }
    @Test fun diagnosticsReadStatusAndRevealOnlyAfterTap(){
        val fixture=Fixture("monitor");val model=model(fixture)
        app.setContent{AdminTheme{PluginPage(model)}}
        app.waitUntil(10000){app.onAllNodesWithText("心跳已超时").fetchSemanticsNodes().isNotEmpty()}
        app.onNodeWithText("支付离线").assertExists();screenshot("diagnostics")
        app.onNodeWithText("fixture-sid-visible").assertDoesNotExist()
        app.onNodeWithText("显示上报 SID").performScrollTo().performClick()
        app.waitUntil(10000){app.onAllNodesWithText("fixture-sid-visible").fetchSemanticsNodes().isNotEmpty()}
        app.runOnIdle{assertTrue(fixture.reads>=2);assertTrue(fixture.writes.isEmpty());model.clearSession()}
    }
    @Test fun failedDiagnosticReadClearsPreviouslyOnlineState(){
        val fixture=Fixture("monitor-fail").apply{failMonitorAfterFirst=true};val model=model(fixture)
        app.setContent{AdminTheme{PluginPage(model)}}
        app.waitUntil(10000){app.onAllNodesWithText("支付在线").fetchSemanticsNodes().isNotEmpty()}
        app.waitUntil(12000){app.onAllNodesWithText("读取失败：诊断接口暂时不可用").fetchSemanticsNodes().isNotEmpty()}
        app.onNodeWithText("支付在线").assertDoesNotExist()
        app.runOnIdle{model.clearSession()}
    }
    @Test fun receiptPickerBindsServerSelectionInsteadOfEditableAccountReference(){
        val fixture=Fixture("receipt");val model=model(fixture)
        app.setContent{AdminTheme{PluginPage(model)}}
        app.onNodeWithText("获取收款单店铺").performClick()
        app.onNodeWithText("请选择").performClick()
        app.onNodeWithText("门店甲 · 123 / 收款账号甲").performClick()
        app.onNodeWithText("绑定收款单店铺").performScrollTo().performClick()
        app.runOnIdle{assertEquals(1,fixture.writes.size)}
        app.onNodeWithText("确认").performClick()
        app.runOnIdle{assertEquals("receipt-bind",fixture.writes.last().s("action"));assertEquals("0",fixture.writes.last().s("selection"));assertEquals("shop-ticket",fixture.writes.last().s("ticket"));model.clearSession()}
    }
    @Test fun dynamicFormSendsOnlyVisibleFieldsAndCurrentRevision(){
        val fixture=Fixture("form");val model=model(fixture,"channel")
        app.setContent{AdminTheme{NativeForm(model)}}
        app.onNodeWithText("固定收款地址").assertExists()
        app.onNodeWithText("收款单 SID").assertDoesNotExist()
        app.onNodeWithText("固定码").performClick();app.onNodeWithText("收款单").performClick()
        app.onNodeWithText("固定收款地址").assertDoesNotExist()
        app.onNodeWithContentDescription("收款单 SID").performTextInput("new-fixture-sid")
        screenshot("dynamic-form")
        app.onNodeWithText("保存修改").performClick();app.onNodeWithText("确认").performClick()
        app.runOnIdle{val save=fixture.writes.single();assertEquals("fixture-revision",save.s("revision"));assertEquals("new-fixture-sid",save.getJSONObject("values").s("receipt_sid"));assertFalse(save.getJSONObject("values").has("qrcode"));model.clearSession()}
    }
    @Test fun importUsesRevisionAndExplicitConfirmation(){
        val fixture=Fixture("automatic");val model=model(fixture)
        app.setContent{AdminTheme{PluginPage(model)}}
        app.onNodeWithContentDescription("应用数据同步地址").performTextInput("https://example.test/one-time")
        app.onNodeWithText("获取并导入应用信息").performScrollTo().performClick()
        app.runOnIdle{assertTrue(fixture.writes.isEmpty())}
        app.onNodeWithText("确认").performClick()
        app.runOnIdle{val save=fixture.writes.single();assertEquals("app-import",save.s("action"));assertEquals("fixture-revision",save.s("revision"));assertEquals("https://example.test/one-time",save.s("url"));model.clearSession()}
    }
}
