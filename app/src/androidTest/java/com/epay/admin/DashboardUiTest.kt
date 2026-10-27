package com.epay.admin

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DashboardUiTest {
    @get:Rule val app=createComposeRule()

    private fun capture(name:String){
        app.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val file=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"ui-refresh-$name.png")
        val bitmap=app.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun metricSwitchRetainsDateAndReadsCorrectValuesAfterRefresh(){
        val points=mutableStateOf(listOf(
            obj("day" to "2026-10-18","amount" to "2.80","orders" to 8),
            obj("day" to "2026-10-19","amount" to "0","orders" to 0),
            obj("day" to "2026-10-20","amount" to "6.50","orders" to 13)))
        app.setContent{AdminTheme{Column(Modifier.width(360.dp)){Group{TrendChart(points.value)}}}}
        app.onNodeWithTag("trend-selected-value").assertTextEquals("¥6.50")
        app.onNodeWithText("10-18").performClick()
        app.onNodeWithTag("trend-selected-value").assertTextEquals("¥2.80")
        app.onNodeWithTag("trend-metric-ORDERS").performClick().assertIsSelected()
        app.onNodeWithTag("trend-selected-value").assertTextEquals("8 笔")
        app.onNodeWithText("按创建日期统计全部订单，含未支付订单").assertIsDisplayed()
        app.onNodeWithTag("trend-canvas").assertContentDescriptionEquals("订单笔数趋势。2026-10-18，8 笔；2026-10-19，0 笔；2026-10-20，13 笔")
        capture("count-trend")
        // A reload keeps the chosen metric while selecting the latest returned day.
        app.runOnIdle{points.value=points.value.map{obj("day" to it.s("day"),"amount" to it.s("amount"),"orders" to it.optInt("orders")+1)}}
        app.onNodeWithTag("trend-metric-ORDERS").assertIsSelected()
        app.onNodeWithTag("trend-selected-value").assertTextEquals("14 笔")
        app.onNodeWithTag("trend-metric-AMOUNT").performClick().assertIsSelected()
        app.onNodeWithTag("trend-selected-value").assertTextEquals("¥6.50")
        app.onNodeWithText("10-19").performClick()
        app.onNodeWithTag("trend-selected-value").assertTextEquals("¥0.00")
    }

    @Test fun thirtyDatesCanBeScrolledAndSelectedWithLargeText(){
        app.setContent{AdminTheme{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)){
                Column(Modifier.width(320.dp)){Group{TrendChart((1..30).map{obj("day" to "2026-09-${it.toString().padStart(2,'0')}","amount" to it.toString(),"orders" to it)})}}
            }
        }}
        app.onNodeWithTag("trend-metric-ORDERS").performClick()
        app.onNodeWithText("09-01").performScrollTo().performClick()
        app.onNodeWithTag("trend-selected-value").assertTextEquals("1 笔")
        app.onNodeWithText("09-30").performScrollTo().performClick()
        app.onNodeWithTag("trend-selected-value").assertTextEquals("30 笔")
        capture("large-trend")
    }

    private fun order()=obj("trade_no" to "2026102000000000001","merchant_name" to "测试商户","name" to "商品标题","money" to "1234.56","status" to 1,"addtime" to "2026-10-20 12:30:00")

    @Test fun homeAndOrdersShowNewComponentsAndKeepDetailNavigation(){
        lateinit var model:AdminModel
        val fixture=object:Source{
            override suspend fun call(route:String,data:JSONObject,post:Boolean,key:String?):JSONObject=when(route){
                "dashboard"->obj("amount" to "1234.56","previous_amount" to "1100.00","paid" to 12,"success_rate" to "80.0","profit" to "12.34","updated_at" to "2026-10-20 12:30:00",
                    "amount_basis" to "按创建日期统计原始成交金额",
                    "trend" to JSONArray((14..20).map{obj("day" to "2026-10-$it","amount" to listOf("100","300","90","400","250","600","1234.56")[it-14],"orders" to it-12)}),"recent" to JSONArray().put(order()))
                "orders"->obj("total" to 1,"rows" to JSONArray().put(order()))
                "orders/detail"->order().put("realmoney","1234.56").put("refundmoney","0")
                else->obj()
            }
        }
        app.runOnIdle{model=AdminModel(ApplicationProvider.getApplicationContext<Application>(),fixture)}
        app.setContent{AdminTheme{AdminApp(model)}}
        app.waitUntil(10000){!model.loading&&model.content.has("trend")}
        app.onNodeWithTag("trend-metric-ORDERS").performScrollTo().performClick()
        app.onNodeWithTag("trend-selected-value").assertTextEquals("8 笔")
        app.onNodeWithText("按创建日期统计原始成交金额").assertDoesNotExist()
        capture("home")
        app.onNodeWithText("订单",substring=false).performClick()
        app.onNodeWithTag("order-row-2026102000000000001").assertIsDisplayed()
        capture("orders")
        app.onNodeWithTag("order-row-2026102000000000001").performClick()
        app.waitUntil(10000){!model.loading&&model.current.route=="order"}
        app.onNodeWithText("订单详情").assertIsDisplayed()
        app.runOnIdle{model.clearSession()}
    }

    @Test fun orderHasTwoCompactLinesAndOpensOnce(){
        var clicks=0
        app.setContent{AdminTheme{Column(Modifier.width(336.dp)){CompactOrderRow(order()){clicks++}}}}
        val row=app.onNodeWithTag("order-row-2026102000000000001").getUnclippedBoundsInRoot()
        val primary=app.onNodeWithTag("order-primary",useUnmergedTree=true).getUnclippedBoundsInRoot()
        val number=app.onNodeWithTag("order-number",useUnmergedTree=true).getUnclippedBoundsInRoot()
        val time=app.onNodeWithTag("order-time",useUnmergedTree=true).getUnclippedBoundsInRoot()
        assertTrue((row.bottom-row.top).value in 53f..57f)
        assertTrue(number.top>=primary.bottom)
        assertEquals(number.top.value,time.top.value,.6f)
        app.onNodeWithText("测试商户",useUnmergedTree=true).assertIsDisplayed()
        app.onNodeWithText("已支付",useUnmergedTree=true).assertIsDisplayed()
        app.onNodeWithTag("order-money",useUnmergedTree=true).assertTextEquals("¥1,234.56")
        capture("order-row")
        app.onNodeWithTag("order-row-2026102000000000001").performClick()
        app.runOnIdle{assertEquals(1,clicks)}
    }

    @Test fun longOrderAndAmountRemainReadableAtLargeFontSize(){
        app.setContent{AdminTheme{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)){
                Column(Modifier.width(280.dp)){CompactOrderRow(order().put("merchant_name","").put("uid","1000").put("money","99999999.99")) {}}
            }
        }}
        app.onNodeWithText("商户 #1000",useUnmergedTree=true).assertIsDisplayed()
        app.onNodeWithTag("order-number",useUnmergedTree=true).assertTextEquals("2026102000000000001").assertIsDisplayed()
        app.onNodeWithTag("order-time",useUnmergedTree=true).assertTextEquals("2026-10-20 12:30:00").assertIsDisplayed()
        app.onNodeWithTag("order-money",useUnmergedTree=true).assertTextEquals("¥99,999,999.99").assertIsDisplayed()
        capture("large-order-row")
    }
}
