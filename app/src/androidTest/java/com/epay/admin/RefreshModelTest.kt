package com.epay.admin

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RefreshModelTest {
    @get:Rule val app=createComposeRule()
    private data class Read(val route:String,val args:JSONObject,val result:CompletableDeferred<JSONObject> = CompletableDeferred())
    private class Fixture : Source {
        val reads=mutableListOf<Read>()
        override suspend fun call(route:String,data:JSONObject,post:Boolean,key:String?):JSONObject {
            check(!post)
            if(route in listOf("menu","groups"))return obj()
            val read=Read(route,JSONObject(data.toString()));reads.add(read)
            // Deliberately allow a stale response to exercise generation protection.
            return withContext(NonCancellable){read.result.await()}
        }
    }
    private fun result(page:Int,id:String)=obj("page" to page,"rows" to JSONArray().put(obj("id" to id)))

    @Test fun refreshKeepsFiltersReplacesPagesAndIgnoresDuplicateRefresh() {
        val fixture=Fixture();lateinit var model:AdminModel
        app.runOnIdle { model=AdminModel(ApplicationProvider.getApplicationContext<Application>(),fixture);fixture.reads.last().result.complete(obj()) }
        app.runOnIdle { model.go("orders","订单",obj("q" to "测试","status" to "1","from" to "2026-10-01","page" to 1)) }
        app.runOnIdle { fixture.reads.last().result.complete(result(1,"old-1")) }
        app.runOnIdle { model.load(true) }
        app.runOnIdle { assertEquals(2,fixture.reads.last().args.getInt("page"));fixture.reads.last().result.complete(result(2,"old-2")) }
        app.runOnIdle { assertEquals(2,model.content.rows().size);model.refresh();model.refresh();assertTrue(model.refreshing);assertEquals(4,fixture.reads.size) }
        app.runOnIdle {
            val read=fixture.reads.last();assertEquals("测试",read.args.s("q"));assertEquals("1",read.args.s("status"));assertEquals("2026-10-01",read.args.s("from"));assertEquals(1,read.args.getInt("page"))
            read.result.complete(result(1,"new-1"))
        }
        app.runOnIdle { assertFalse(model.refreshing);assertFalse(model.loading);assertEquals(listOf("new-1"),model.content.rows().map{it.s("id")});model.clearSession() }
    }

    @Test fun oldCancelledRequestCannotClearNewLoadingOrReplacePageAndErrorCanRetry() {
        val fixture=Fixture();lateinit var model:AdminModel
        app.runOnIdle { model=AdminModel(ApplicationProvider.getApplicationContext<Application>(),fixture);model.go("orders","订单") }
        app.runOnIdle { fixture.reads.first().result.complete(obj("stale" to true)) }
        app.runOnIdle { assertTrue(model.loading);assertFalse(model.content.has("stale"));fixture.reads.last().result.complete(result(1,"first")) }
        app.runOnIdle { model.refresh() }
        app.runOnIdle { fixture.reads.last().result.completeExceptionally(ApiFailure("网络中断")) }
        app.runOnIdle { assertFalse(model.refreshing);assertFalse(model.loading);assertEquals("网络中断",model.error);assertEquals("first",model.content.rows().single().s("id"));model.refresh() }
        app.runOnIdle { fixture.reads.last().result.complete(result(1,"retry")) }
        app.runOnIdle { assertNull(model.error);assertEquals("retry",model.content.rows().single().s("id"));model.go("merchant-new","新增商户");val count=fixture.reads.size;model.refresh();assertEquals(count,fixture.reads.size);assertFalse(model.refreshing);model.clearSession() }
    }
}
