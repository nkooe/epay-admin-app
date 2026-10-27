package com.epay.admin

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MenuDrawerTest {
    @get:Rule val app=createComposeRule()

    @Test fun onlyOneGroupExpandsAndClosingResetsIt() {
        val open=mutableStateOf(true)
        var selected=""
        val groups=listOf(
            obj("title" to "收款订单","items" to JSONArray(listOf(obj("title" to "订单管理","route" to "orders","available" to true)))),
            obj("title" to "商户管理","items" to JSONArray(listOf(obj("title" to "用户列表","route" to "merchants","available" to true))))
        )
        app.setContent { AdminTheme { AdminMenuDrawer("平台管理","home",groups,false,open.value,{}, {selected=it.s("route")}, {}) } }

        app.onNodeWithText("收款订单").assertIsDisplayed()
        app.onNodeWithText("商户管理").assertIsDisplayed()
        app.onNodeWithText("订单管理").assertDoesNotExist()
        app.onNodeWithText("用户列表").assertDoesNotExist()

        app.onNodeWithText("收款订单").performClick()
        app.onNodeWithText("订单管理").assertIsDisplayed()
        app.onNodeWithText("用户列表").assertDoesNotExist()
        app.onNodeWithText("商户管理").performClick()
        app.onNodeWithText("订单管理").assertDoesNotExist()
        app.onNodeWithText("用户列表").assertIsDisplayed().performClick()
        app.runOnIdle { assertEquals("merchants",selected) }
        app.onNodeWithText("商户管理").performClick()
        app.onNodeWithText("用户列表").assertDoesNotExist()

        app.onNodeWithText("收款订单").performClick()
        app.runOnIdle { open.value=false }
        app.waitForIdle()
        app.runOnIdle { open.value=true }
        app.onNodeWithText("订单管理").assertDoesNotExist()
        app.onNodeWithText("用户列表").assertDoesNotExist()
    }
}
