package com.epay.admin

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry

class NativeSmokeTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()
    @Test fun liveReadOnlyLogin() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Live check is opt-in; no credentials are embedded",args.getString("platform")!=null)
        lateinit var model:AdminModel
        app.runOnUiThread { model=ViewModelProvider(app.activity)[AdminModel::class.java];model.login(args.getString("platform")!!,args.getString("username")!!,args.getString("password")!!,"","") }
        app.waitUntil(45000){!model.busy}
        if(model.captcha!=null){
            val numbers=Regex("[0-9]+").findAll(model.captcha!!.s("question")).map{it.value.toInt()}.toList()
            app.runOnUiThread{model.login(args.getString("platform")!!,args.getString("username")!!,args.getString("password")!!,(numbers[0]+numbers[1]).toString(),"")}
            app.waitUntil(45000){!model.busy}
        }
        org.junit.Assert.assertTrue("Native HTTPS login must succeed",model.signedIn)
        try {
            app.waitUntil(45000){!model.loading&&model.content.has("trend")}
            org.junit.Assert.assertNull(model.error)
            listOf("orders","merchants","channels","profile").forEach{route->
                app.runOnUiThread{model.go(route,route,root=true)}
                app.waitUntil(45000){!model.loading}
                org.junit.Assert.assertNull("Read $route",model.error)
            }
        } finally {
            app.runOnUiThread{model.logout()}
            app.waitUntil(45000){!model.busy&&!model.signedIn}
        }
    }
    @Test fun signedOutRequiresPlatformLogin() {
        app.runOnUiThread { ViewModelProvider(app.activity)[AdminModel::class.java].clearSession() }
        app.onNodeWithText("平台地址").assertExists()
        app.onNodeWithText("管理员账号").assertExists()
        app.onNodeWithText("管理员密码").assertExists()
        app.onNodeWithText("登录").assertIsNotEnabled()
        app.onAllNodes(hasText("演示",substring=true)).assertCountEquals(0)
        app.onNodeWithContentDescription("全部后台菜单").assertDoesNotExist()
        app.onNodeWithText("今日成交金额（元）").assertDoesNotExist()
    }
}
