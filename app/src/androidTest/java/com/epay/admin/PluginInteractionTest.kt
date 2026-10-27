package com.epay.admin

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PluginInteractionTest {
    @get:Rule val app=createComposeRule()
    private class Fixture:Source {
        val writes=mutableListOf<JSONObject>()
        override suspend fun call(route:String,data:JSONObject,post:Boolean,key:String?):JSONObject {
            if(post){writes.add(JSONObject(data.toString()));return when(data.s("action")){
                "qr-start"->obj("ticket" to "fixture-ticket","qrcode" to "https://example.test/auth","qrcode_type" to "text")
                "qr-poll"->obj("state" to "waiting","message" to "等待扫码")
                "stores"->obj("ticket" to "stores-ticket","rows" to JSONArray().put(obj("store_sn" to "s1","store_name" to "门店甲","terminal_sn" to "t1","terminal_name" to "终端甲")))
                else->obj("message" to "操作成功")
            }}
            if(route=="plugins/schema")return obj("id" to "1","name" to "测试通道","plugin" to "fixture","available" to true,"qr_login" to true,"stores" to true)
            return obj()
        }
    }
    private fun model(fixture:Fixture):AdminModel {
        lateinit var result:AdminModel
        app.runOnIdle{result=AdminModel(ApplicationProvider.getApplicationContext<Application>(),fixture);result.go("plugin","插件管理",obj("id" to "1"))}
        return result
    }
    @Test fun authorizationDoesNotStartUntilConfirmedAndQrExportAsksFirst(){
        val fixture=Fixture();val model=model(fixture)
        app.setContent{AdminTheme{PluginPage(model)}}
        app.onNodeWithText("扫码授权").performClick()
        app.onNodeWithText("开始扫码授权？").assertIsDisplayed()
        app.runOnIdle{assertTrue(fixture.writes.isEmpty())}
        app.onNodeWithText("取消").performClick()
        app.runOnIdle{assertTrue(fixture.writes.isEmpty())}
        app.onNodeWithText("扫码授权").performClick();app.onNodeWithText("确认").performClick()
        app.waitUntil(10000){app.onAllNodesWithContentDescription("登录授权二维码").fetchSemanticsNodes().isNotEmpty()}
        app.onNodeWithText("保存二维码").performClick()
        app.onNodeWithText("保存二维码？").assertIsDisplayed()
        app.onNodeWithText("取消").performClick()
        app.runOnIdle{assertEquals("qr-start",fixture.writes.first().s("action"));model.clearSession()}
    }
    @Test fun storeBindingUsesSelectedTerminalAndRequiresConfirmation(){
        val fixture=Fixture();val model=model(fixture)
        app.setContent{AdminTheme{PluginPage(model)}}
        app.onNodeWithText("获取门店和终端").performClick()
        app.onNodeWithText("请选择").performClick();app.onNodeWithText("门店甲").performClick()
        app.onNodeWithText("请选择").performClick();app.onNodeWithText("终端甲").performClick()
        app.onNodeWithText("绑定并同步收款码").performScrollTo().performClick()
        app.onNodeWithText("绑定所选门店？").assertIsDisplayed()
        app.runOnIdle{assertEquals(1,fixture.writes.size)}
        app.onNodeWithText("确认").performClick()
        app.runOnIdle{assertEquals("store-bind",fixture.writes.last().s("action"));assertEquals("s1",fixture.writes.last().s("store_sn"));assertEquals("t1",fixture.writes.last().s("terminal_sn"));model.clearSession()}
    }
}
