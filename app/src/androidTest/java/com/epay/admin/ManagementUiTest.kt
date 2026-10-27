package com.epay.admin

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** In-memory contracts only; these tests never connect to the payment platform. */
class ManagementUiTest {
    @get:Rule val app=createComposeRule()
    private data class Call(val route:String,val data:JSONObject,val key:String?)
    private class Fixture:Source{
        val writes=mutableListOf<Call>();val reads=mutableListOf<Call>();var deleted=false;var unknown=false;var bound=false
        val task=obj("id" to 1,"revision" to 3,"action" to "notify","name" to "订单通知重试","enabled" to 1,"schedule_type" to "interval","interval_seconds" to 60,"next_run_at" to "2026-10-24 18:30:00","last_status" to "success","locked" to false)
        override suspend fun call(route:String,data:JSONObject,post:Boolean,key:String?):JSONObject{
            if(post){writes.add(Call(route,JSONObject(data.toString()),key));if(route=="tasks/log-delete")deleted=true;if(route=="complaints/action")unknown=true;return if(route=="complaints/check")obj("ok" to false,"message" to "请在支付通道中配置支付宝公钥")else obj("status" to "queued","state" to "unknown","message" to "提交结果待核实")}
            reads.add(Call(route,JSONObject(data.toString()),key))
            return when(route){
                "tasks"->obj("service" to obj("available" to true,"active_state" to "active","pid" to 5494),"worker" to obj("healthy" to true,"display_state" to "running","reason" to "主循环进度正常"),"rows" to JSONArray().put(task),"plugin_services" to JSONArray())
                "tasks/editor"->obj("record" to task,"definitions" to obj(),"plugin_jobs" to JSONArray(),"channels" to JSONArray())
                "tasks/logs"->obj("days" to if(deleted)JSONArray()else JSONArray().put("2026-10-24"),"today" to "2026-10-24","date" to "2026-10-24","text" to if(deleted)""else"18:30 调度器运行正常")
                "complaints"->obj("rows" to JSONArray().put(obj("id" to if(data.optInt("page",1)==2)2 else 1,"title" to "未收到商品","provider" to "wechat","status" to 0,"thirdid" to "test-complaint","money" to "100.00","uid" to 1000)),"has_more" to (data.optInt("page",1)==1),"page" to data.optInt("page",1),"total" to 2,"accounts" to JSONArray())
                "complaints/detail"->obj("record" to obj("id" to 1,"revision" to 7,"provider" to "wechat","status" to 0,"title" to "未收到商品","thirdid" to "fixture-complaint-001","money" to "100.00","created_at" to "2026-10-24 18:20:00"),"account_enabled" to true,"detail" to obj("content" to "请商家协助核实订单物流。","history" to JSONArray(),"problem_type" to "REFUND"),"actions" to if(unknown)JSONArray().put(obj("id" to 9,"state" to "unknown","action" to "reply","message" to "提交结果待核实"))else JSONArray())
                "complaints/schema"->obj("accounts" to if(bound)JSONArray().put(obj("id" to 3,"revision" to 2,"channel" to 12,"channel_name" to "已配置通道","name" to "通道投诉","provider" to "alipay","enabled" to 1,"sync" to obj("state" to "waiting","label" to "等待重试","last_success_at" to "2026-10-25 03:00:00","last_count" to 240,"next_at" to "2026-10-25 03:05:00","processed" to 12,"error" to "temporary timeout")))else JSONArray(),"channels" to JSONArray().put(obj("id" to 12,"name" to "已配置通道","type" to "alipay","bindable" to true,"config_message" to "")))
                else->obj()
            }
        }
    }
    private fun open(route:String,args:JSONObject=obj(),bound:Boolean=false):Pair<AdminModel,Fixture>{
        val fixture=Fixture().apply{this.bound=bound};lateinit var model:AdminModel
        app.runOnIdle{model=AdminModel(ApplicationProvider.getApplicationContext<Application>(),fixture);model.go(route,route,args)}
        app.setContent{AdminTheme{Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){when(model.current.route){
            "tasks"->TasksPage(model);"task-edit"->TaskEditorPage(model);"task-logs"->TaskLogsPage(model)
            "complaints"->ComplaintsPage(model);"complaint"->ComplaintPage(model)
            "complaint-accounts"->ComplaintAccountsPage(model)
        }}}}
        app.waitUntil{!model.loading};return model to fixture
    }
    private fun screenshot(name:String){
        val context=ApplicationProvider.getApplicationContext<Application>()
        val bitmap=app.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun servicePidAndQueuedTaskAreActualContracts(){
        val (model,fixture)=open("tasks")
        app.onNodeWithText("5494").assertExists();screenshot("native-tasks.png")
        app.onAllNodesWithText("更多")[1].performClick();app.onNodeWithText("立即执行").performClick()
        app.runOnIdle{val call=fixture.writes.single();assertEquals("tasks/run",call.route);assertEquals("1",call.data.s("id"));assertEquals(3,call.data.optInt("revision"));assertFalse(call.key.isNullOrBlank());model.clearSession()}
    }
    @Test fun taskSchedulePreservesRevisionAndNullTiming(){
        val (model,fixture)=open("task-edit",obj("id" to 1))
        app.onNodeWithContentDescription("间隔秒数（10–86400）").performTextReplacement("120")
        app.onNodeWithText("保存配置").performScrollTo().performClick()
        app.runOnIdle{val call=fixture.writes.single();assertEquals("tasks/save",call.route);assertEquals(3,call.data.optInt("revision"));assertEquals("120",call.data.s("interval_seconds"));assertTrue(call.data.isNull("daily_at"));assertEquals(0,call.data.getJSONObject("params").length());model.clearSession()}
    }
    @Test fun complaintReplyAndUncertainResultPreventBlindResubmit(){
        val (model,fixture)=open("complaint",obj("id" to 1))
        screenshot("native-complaint.png")
        app.onNodeWithText("更多").performClick();app.onNodeWithText("回复用户").performClick()
        app.onNodeWithContentDescription("处理内容").performTextReplacement("已经联系用户核实")
        app.onNodeWithText("提交").performClick()
        app.runOnIdle{val call=fixture.writes.single();assertEquals("complaints/action",call.route);assertEquals(7,call.data.optInt("revision"));assertFalse(call.data.has("request_id"));assertFalse(call.key.isNullOrBlank())}
        app.onNodeWithText("有操作正在提交或结果待核实。请先刷新上游状态并核对协商记录。").assertExists()
        app.onNodeWithText("更多").performClick();app.onNodeWithText("回复用户").assertDoesNotExist()
        app.runOnIdle{model.clearSession()}
    }
    @Test fun complaintsRefreshKeepsFiltersAndReplacesAccumulatedPages(){
        val (model,fixture)=open("complaints",obj("q" to "test","status" to "0","account_id" to "3","page" to 1))
        app.runOnIdle{model.load(true)};app.waitUntil{!model.loading}
        app.runOnIdle{assertEquals(2,model.content.rows().size);model.refresh()};app.waitUntil{!model.loading}
        app.runOnIdle{assertEquals(1,model.content.rows().size);val call=fixture.reads.last();assertEquals(1,call.data.optInt("page"));assertEquals("0",call.data.s("status"));assertEquals("test",call.data.s("q"));assertEquals("3",call.data.s("account_id"));model.clearSession()}
    }
    @Test fun dateLogDeleteHasExactlyOneConfirmation(){
        val (model,fixture)=open("task-logs")
        app.onNodeWithText("删除当日日志").performClick();app.onNodeWithText("删除").performClick()
        app.runOnIdle{val call=fixture.writes.single();assertEquals("tasks/log-delete",call.route);assertEquals("2026-10-24",call.data.s("date"));assertEquals(1,call.data.length())}
        app.onNodeWithText("当日暂无日志").assertExists();app.runOnIdle{model.clearSession()}
    }
    @Test fun complaintBindingOnlySelectsExistingChannelAndDefaultsEnabled(){
        val (model,fixture)=open("complaint-accounts")
        app.onNodeWithText("新增投诉接口").performClick()
        app.onNodeWithContentDescription("接口名称").assertDoesNotExist()
        app.onNodeWithContentDescription("应用 ID").assertDoesNotExist()
        app.onNodeWithContentDescription("应用私钥").assertDoesNotExist()
        app.onNode(isToggleable()).assertIsOn()
        app.onNodeWithText("请选择").performClick()
        app.onNodeWithText("支付宝 · 已配置通道（12）").performClick()
        app.onNodeWithText("保存").performClick()
        app.waitUntil{fixture.writes.isNotEmpty()}
        app.runOnIdle{val call=fixture.writes.single();assertEquals("complaints/account",call.route);assertEquals("12",call.data.s("channel"));assertEquals(1,call.data.optInt("enabled"));assertFalse(call.data.has("credentials"));assertFalse(call.data.has("name"));assertFalse(call.data.has("provider"));model.clearSession()}
    }
    @Test fun complaintConnectionCanBeCheckedBeforeBinding(){
        val (model,fixture)=open("complaint-accounts")
        app.onNodeWithText("新增投诉接口").performClick();app.onNodeWithText("请选择").performClick()
        app.onNodeWithText("支付宝 · 已配置通道（12）").performClick()
        app.onNodeWithText("检测连接").performClick()
        app.onNodeWithText("请在支付通道中配置支付宝公钥").assertExists()
        app.runOnIdle{val call=fixture.writes.single();assertEquals("complaints/check",call.route);assertEquals("12",call.data.s("channel"));assertFalse(call.key.isNullOrBlank())}
        app.onNodeWithText("通道配置").performClick()
        app.runOnIdle{assertEquals("channel",model.current.route);assertEquals("12",model.current.args.s("id"));model.clearSession()}
    }
    @Test fun complaintSyncStatusAndBackgroundQueueAreVisible(){
        val (model,fixture)=open("complaint-accounts",bound=true)
        app.onNodeWithText("等待重试").performScrollTo().assertExists()
        app.onNodeWithText("2026-10-25 03:00:00 · 240 条").performScrollTo().assertExists()
        app.onNodeWithText("同步原因：temporary timeout").performScrollTo().assertExists()
        screenshot("native-complaint-sync.png")
        app.onNodeWithText("更多").performScrollTo().performClick();app.onNodeWithText("后台同步").performClick()
        app.runOnIdle{val call=fixture.writes.single();assertEquals("complaints/queue",call.route);assertEquals("3",call.data.s("id"));assertFalse(call.key.isNullOrBlank());model.clearSession()}
    }
}
