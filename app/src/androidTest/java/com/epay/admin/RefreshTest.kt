package com.epay.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RefreshTest {
    @get:Rule val app=createComposeRule()

    @Test fun shortDragDoesNotRefreshAndLongDragRefreshesOnlyOnceUntilFinished() {
        var calls=0
        val refreshing=mutableStateOf(false)
        app.setContent { AdminTheme { RefreshContainer(refreshing.value,!refreshing.value,{calls++;refreshing.value=true}) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { Text("短内容") }
        } } }
        val node=app.onNodeWithTag("pull-refresh")
        node.performTouchInput { swipe(Offset(centerX,40f),Offset(centerX,75f),400) }
        app.runOnIdle { assertEquals(0,calls) }
        node.performTouchInput { swipe(Offset(centerX,60f),Offset(centerX,height*.85f),600) }
        app.runOnIdle { assertEquals(1,calls) }
        node.performTouchInput { swipe(Offset(centerX,60f),Offset(centerX,height*.85f),600) }
        app.runOnIdle { assertEquals(1,calls);refreshing.value=false }
        app.waitForIdle()
        node.performTouchInput { swipe(Offset(centerX,60f),Offset(centerX,height*.85f),600) }
        app.runOnIdle { assertEquals(2,calls) }
    }

    @Test fun disabledFormDoesNotRefreshOrReplaceInput() {
        var calls=0
        val input=mutableStateOf("未保存内容")
        app.setContent { AdminTheme { RefreshContainer(false,false,{calls++}) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { CompactInput("名称",input.value,{input.value=it}) }
        } } }
        app.onNodeWithTag("pull-refresh").performTouchInput { swipe(Offset(centerX,150f),Offset(centerX,height*.85f),600) }
        app.runOnIdle { assertEquals(0,calls);assertEquals("未保存内容",input.value) }
    }

    @Test fun scrollingDownInMiddleDoesNotRefresh() {
        var calls=0
        app.setContent { AdminTheme { RefreshContainer(false,true,{calls++}) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { repeat(100){Text("记录 $it",Modifier.height(48.dp))} }
        } } }
        val node=app.onNodeWithTag("pull-refresh")
        node.performTouchInput { swipeUp() }
        node.performTouchInput { swipe(Offset(centerX,80f),Offset(centerX,280f),500) }
        app.runOnIdle { assertEquals(0,calls) }
    }
}
