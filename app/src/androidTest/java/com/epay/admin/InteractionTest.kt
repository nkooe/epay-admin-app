package com.epay.admin

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InteractionTest {
    @get:Rule val app=createComposeRule()
    private class Fixture:Source {
        val writes=mutableListOf<Pair<String,JSONObject>>()
        var listReads=0
        var merchantApiInfo=obj()
        var orderStatus=1
        var orderDeleted=false
        override suspend fun call(route:String,data:JSONObject,post:Boolean,key:String?):JSONObject {
            if(post){writes.add(route to JSONObject(data.toString()));return when(route){
                "profile/totp/prepare"->obj("secret" to "JBSWY3DPEHPK3PXP","uri" to "otpauth://totp/Test?secret=JBSWY3DPEHPK3PXP","setup_token" to "fixture-token","expires_in" to 600)
                "profile/totp/bind","profile/totp/disable"->obj("reauthenticate" to true)
                "channels/test"->throw ApiFailure("测试渠道响应，不会创建真实订单")
                "actions/run"->{if(data.s("action")=="order-mark-unpaid")orderStatus=0;if(data.s("action")=="order-delete")orderDeleted=true;obj("message" to "操作成功")}
                else->obj()
            }}
            return when(route){
                "orders","resources"->{listReads++;obj("rows" to if(orderDeleted)JSONArray()else JSONArray().put(obj("trade_no" to "fixture-order","money" to "1.23")),"total" to if(orderDeleted)0 else 1)}
                "orders/detail"->obj("trade_no" to data.s("trade_no"),"name" to "测试订单","money" to "1.23","realmoney" to "1.23","refundmoney" to "0","status" to orderStatus,"addtime" to "2026-10-20 10:00:00")
                "actions/schema"->if(data.s("action")=="manual-fill")obj("action" to "manual-fill","name" to "手工补单","note" to "仅在渠道已收款并核实金额后操作。","fields" to JSONArray().put(obj("key" to "trade_no","label" to "平台订单号","type" to "text","required" to true,"value" to data.s("trade_no"))).put(obj("key" to "paypwd","label" to "支付密码","type" to "text","secret" to true,"required" to true,"value" to "")))else obj("action" to data.s("action"),"fields" to JSONArray())
                "channels"->obj("rows" to JSONArray().put(obj("id" to "42","name" to "测试通道","type_name" to "支付宝","status" to 1)))
                "profile"->obj("totp" to true)
                "management/schema"->obj("resource" to "merchant-api","id" to "1000","api_info" to merchantApiInfo,
                    "fields" to JSONArray().put(obj("key" to "keytype","label" to "API 签名模式","type" to "select","value" to "0")).put(obj("key" to "publickey","label" to "修改商户公钥","type" to "textarea","value" to "")))
                else->obj()
            }
        }
    }
    private fun model(fixture:Fixture):AdminModel {
        lateinit var model:AdminModel
        app.runOnIdle{model=AdminModel(ApplicationProvider.getApplicationContext<Application>(),fixture)}
        return model
    }
    private fun captureApi(name:String) {
        if(InstrumentationRegistry.getArguments().getString("captureApi")!="true")return
        app.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val file=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"api-$name.png")
        file.outputStream().use{instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun allListExportsRequireConfirmationAndCancelDoesNoWork() {
        val fixture=Fixture();val model=model(fixture)
        app.runOnIdle{model.go("orders","订单")}
        app.setContent{AdminTheme{Column{ExportConfirmation(model);NativeListPage(model)}}}
        for(resource in listOf("orders","transfers")) {
            app.runOnIdle{if(resource!="orders")model.go("resource","付款记录",obj("resource" to resource))}
            app.onNodeWithContentDescription("页面操作").performClick()
            app.onNodeWithText("导出 CSV").performClick()
            val reads=fixture.listReads
            app.onNodeWithText("确认导出？").assertIsDisplayed()
            app.runOnIdle{assertNull(model.exportReady);assertEquals(reads,fixture.listReads)}
            app.onNodeWithText("取消").performClick()
            app.runOnIdle{assertEquals(reads,fixture.listReads);assertNull(model.exportReady)}
            app.onNodeWithContentDescription("页面操作").performClick()
            app.onNodeWithText("导出 CSV").performClick()
            app.onNodeWithText("确认").performClick()
            app.runOnIdle{assertEquals(reads+1,fixture.listReads);assertNotNull(model.exportReady);model.clearExport()}
        }
        app.runOnIdle{model.selectMenu(obj("route" to "export:orders","title" to "导出订单"));assertTrue(model.exportConfirmation)}
        app.onNodeWithText("确认导出？").assertIsDisplayed()
        app.onNodeWithText("取消").performClick()
        app.runOnIdle{model.clearSession()}
    }

    @Test fun channelButtonsFilterOrdersAndPaymentWaitsForExplicitClick() {
        val fixture=Fixture();val model=model(fixture)
        app.runOnIdle{model.go("channels","支付通道",root=true)}
        app.setContent{AdminTheme{NativeListPage(model)}}
        app.onNodeWithText("测试支付").assertDoesNotExist()
        app.onNodeWithText("订单记录").assertDoesNotExist()
        app.onNodeWithContentDescription("通道操作").performClick()
        listOf("基本设置","密钥配置","测试支付","订单记录","停用通道").forEach{app.onNodeWithText(it).assertIsDisplayed()}
        app.onNodeWithText("授权、门店与证书").assertDoesNotExist()
        app.onNodeWithText("订单记录").performClick()
        app.runOnIdle{assertEquals("orders",model.current.route);assertEquals("42",model.current.args.s("channel"));assertTrue(model.canGoBack);model.back();assertFalse(model.canGoBack)}
        app.onNodeWithContentDescription("通道操作").performClick()
        app.onNodeWithText("测试支付").performClick()
        app.onNodeWithContentDescription("支付金额（元）").assertIsDisplayed()
        app.runOnIdle{assertTrue(fixture.writes.isEmpty())}
        app.onNodeWithContentDescription("支付金额（元）").performTextReplacement("0")
        app.onNodeWithText("支付",useUnmergedTree=true).performClick()
        app.onNodeWithText("请输入大于零、最多两位小数的金额").assertIsDisplayed()
        app.runOnIdle{assertTrue(fixture.writes.isEmpty())}
        app.onNodeWithContentDescription("支付金额（元）").performTextReplacement("1.23")
        app.onNodeWithText("支付",useUnmergedTree=true).performClick()
        app.runOnIdle{assertEquals("channels/test",fixture.writes.single().first);assertEquals("42",fixture.writes.single().second.s("id"));assertEquals("1.23",fixture.writes.single().second.s("money"));model.clearSession()}
    }

    @Test fun orderStatusChangesImmediatelyWithoutReasonOrConfirmation(){
        val fixture=Fixture();val model=model(fixture)
        app.runOnIdle{model.go("order","订单详情",obj("trade_no" to "fixture-order"))}
        app.setContent{AdminTheme{AdminApp(model)}}
        app.waitUntil(10000){app.onAllNodesWithText("订单管理").fetchSemanticsNodes().isNotEmpty()}
        app.onNodeWithText("操作").performClick()
        app.onNodeWithText("改未完成").performClick()
        app.waitUntil(10000){fixture.writes.any{it.first=="actions/run"}}
        val write=fixture.writes.single{it.first=="actions/run"}.second
        val values=write.getJSONObject("values")
        assertEquals("order-mark-unpaid",write.s("action"));assertEquals("fixture-order",values.s("trade_no"));assertEquals("1",values.s("expected_status"))
        assertFalse(values.has("reason"));assertFalse(values.has("paypwd"))
        app.onNodeWithText("确认").assertDoesNotExist()
        app.runOnIdle{assertEquals(0,fixture.orderStatus);model.clearSession()}
    }

    @Test fun unpaidOrderDeletesOnlyAfterOneConfirmationAndReturnsToList(){
        val fixture=Fixture();fixture.orderStatus=0;val model=model(fixture)
        app.runOnIdle{model.go("orders","订单",root=true);model.go("order","订单详情",obj("trade_no" to "fixture-order"))}
        app.setContent{AdminTheme{AdminApp(model)}}
        app.waitUntil(10000){app.onAllNodesWithText("订单管理").fetchSemanticsNodes().isNotEmpty()}
        app.onNodeWithText("操作").performClick()
        app.onNodeWithText("关闭未付订单").assertDoesNotExist()
        app.onNodeWithText("删除订单").performClick()
        app.onNodeWithText("删除订单？").assertIsDisplayed()
        app.onAllNodes(isDialog()).assertCountEquals(1)
        app.runOnIdle{assertTrue(fixture.writes.isEmpty())}
        app.onNodeWithText("取消").performClick()
        app.runOnIdle{assertTrue(fixture.writes.isEmpty())}
        app.onNodeWithText("操作").performClick();app.onNodeWithText("删除订单").performClick()
        app.onNodeWithText("删除").performClick()
        app.waitUntil(10000){fixture.orderDeleted&&model.current.route=="orders"&&!model.loading}
        app.runOnIdle{
            val write=fixture.writes.single();assertEquals("actions/run",write.first);assertEquals("order-delete",write.second.s("action"))
            assertEquals("fixture-order",write.second.getJSONObject("values").s("trade_no"));assertEquals(1,write.second.getJSONObject("values").length())
            assertTrue(model.content.rows().isEmpty());model.clearSession()
        }
        app.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun manualFillDropsVerificationNoteAndSubmitsDirectly(){
        val fixture=Fixture();fixture.orderStatus=0;val model=model(fixture)
        app.runOnIdle{model.go("order","订单详情",obj("trade_no" to "fixture-order"))}
        app.setContent{AdminTheme{AdminApp(model)}}
        app.waitUntil(10000){app.onAllNodesWithText("订单管理").fetchSemanticsNodes().isNotEmpty()}
        app.onNodeWithText("操作").performClick();app.onNodeWithText("手工补单").performClick()
        app.waitUntil(10000){app.onAllNodesWithText("手工补单").fetchSemanticsNodes().isNotEmpty()}
        app.onNodeWithText("核实说明").assertDoesNotExist()
        app.onNodeWithContentDescription("支付密码 *").performTextInput("fixture-payment-password")
        app.onNode(hasText("手工补单") and hasClickAction()).performClick()
        app.waitUntil(10000){fixture.writes.any{it.first=="actions/run"}}
        val write=fixture.writes.single{it.first=="actions/run"}.second
        val values=write.getJSONObject("values")
        assertEquals("manual-fill",write.s("action"));assertEquals("fixture-order",values.s("trade_no"));assertEquals("fixture-payment-password",values.s("paypwd"));assertFalse(values.has("evidence"))
        app.onNodeWithText("确认").assertDoesNotExist()
        app.runOnIdle{model.clearSession()}
    }

    @Test fun totpResetPreparesBeforeBindingAndCloseRequiresPassword() {
        val fixture=Fixture();val model=model(fixture)
        app.runOnIdle{model.go("totp","动态口令管理")}
        app.setContent{AdminTheme{TotpPage(model)}}
        app.onNodeWithText("关闭动态口令").performClick()
        app.onNodeWithText("确认关闭").performClick()
        app.onNodeWithText("请输入管理员密码").assertIsDisplayed()
        app.runOnIdle{assertTrue(fixture.writes.isEmpty())}
        app.onNodeWithText("取消").performClick()
        app.onNodeWithText("重置绑定").performClick()
        app.onNodeWithContentDescription("管理员密码").performTextInput("fixture-password")
        app.onNodeWithText("下一步").performClick()
        app.onNodeWithContentDescription("新的动态口令").performScrollTo().performTextInput("123456")
        app.runOnIdle{assertEquals(listOf("profile/totp/prepare"),fixture.writes.map{it.first});assertTrue(model.signedIn)}
        app.onNodeWithText("完成绑定").performScrollTo().performClick()
        app.runOnIdle{assertEquals(listOf("profile/totp/prepare","profile/totp/bind"),fixture.writes.map{it.first});assertFalse(model.signedIn)}
    }

    @Test fun merchantApiCopiesFullValuesAndExpandsPublicKeys() {
        val fixture=Fixture();val model=model(fixture)
        val platformKey="-----BEGIN PUBLIC KEY-----\nPLATFORM-FIXTURE-LINE-ONE\nPLATFORM-FIXTURE-LINE-TWO\nPLATFORM-FIXTURE-LINE-THREE\n-----END PUBLIC KEY-----"
        val merchantKey=platformKey.replace("PLATFORM","MERCHANT")
        fixture.merchantApiInfo=obj("uid" to "1000","keytype" to "0","apiurl" to "https://example.test/pay/","md5_key" to "fixture-md5-key","platform_public_key" to platformKey,"merchant_public_key" to merchantKey)
        lateinit var clipboard:ClipboardManager
        app.runOnIdle{model.edit("merchant-api","API 密钥","1000")}
        app.setContent{AdminTheme{clipboard=LocalClipboardManager.current;AdminApp(model)}}
        app.onNodeWithText("保存修改").assertDoesNotExist()
        app.onNodeWithText("修改商户公钥").assertDoesNotExist()
        app.onNodeWithText("平台公钥").assertDoesNotExist()
        app.onNodeWithText("商户私钥").assertDoesNotExist()
        captureApi("md5")
        listOf("接口地址" to "https://example.test/pay/","商户 ID" to "1000","商户 MD5 密钥" to "fixture-md5-key").forEach{(label,value)->
            app.onNodeWithContentDescription("复制$label").performScrollTo().performClick()
            app.runOnIdle{assertEquals(value,clipboard.getText()?.text)}
        }
        app.onNodeWithText("密钥类型").performScrollTo().performClick()
        app.onNodeWithText("RSA 密钥").performClick()
        app.onNodeWithText("商户 MD5 密钥").assertDoesNotExist()
        captureApi("rsa")
        listOf("平台公钥" to platformKey,"商户公钥" to merchantKey).forEach{(label,value)->
            app.onNodeWithContentDescription("复制$label").performScrollTo().performClick()
            app.runOnIdle{assertEquals(value,clipboard.getText()?.text)}
        }
        app.onNodeWithText(platformKey).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已收起")).performClick()
        app.onNodeWithText(platformKey).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已展开"))
        app.runOnIdle{assertTrue("Viewing and copying must never write",fixture.writes.isEmpty());model.clearSession()}
    }

    @Test fun merchantApiDefaultsToRsaAndEmptyPublicKeyCannotBeCopied() {
        val fixture=Fixture();val model=model(fixture)
        fixture.merchantApiInfo=obj("uid" to "1000","keytype" to "1","md5_key" to "fixture-md5-key","platform_public_key" to "platform-fixture","merchant_public_key" to "")
        app.runOnIdle{model.edit("merchant-api","API 密钥","1000")}
        app.setContent{AdminTheme{NativeForm(model)}}
        app.onNodeWithText("RSA 密钥").assertIsDisplayed()
        app.onNodeWithText("商户 MD5 密钥").assertDoesNotExist()
        app.onNodeWithText("尚未生成").performScrollTo().assertIsDisplayed()
        app.onNodeWithContentDescription("复制商户公钥").assertIsNotEnabled()
        app.onNodeWithContentDescription("复制商户私钥").assertDoesNotExist()
        app.onNodeWithText("保存修改").assertDoesNotExist()
        app.onNodeWithText("密钥类型").performScrollTo().performClick()
        app.onNodeWithText("MD5 密钥").performClick()
        app.onNodeWithText("fixture-md5-key").assertIsDisplayed()
        app.onNodeWithText("商户公钥").assertDoesNotExist()
        app.runOnIdle{assertTrue(fixture.writes.isEmpty());model.clearSession()}
    }
}
