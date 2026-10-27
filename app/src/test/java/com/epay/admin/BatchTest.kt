package com.epay.admin

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BatchTest {
    @Test fun timeoutStopsBeforeNextFinancialRequest() = runBlocking {
        val calls=mutableListOf<JSONObject>();val keys=mutableListOf<String>()
        val source=object:Source {
            override suspend fun call(route:String,data:JSONObject,post:Boolean,key:String?):JSONObject {
                assertEquals("actions/run",route);assertTrue(post);calls.add(data);keys.add(key!!)
                if(calls.size==2)throw ApiFailure("渠道结果待核实",409)
                return obj("message" to "已完成")
            }
        }
        val values=obj("paypwd" to "fixture-only")
        val result=executeBatch(source,"settle-pay","id",listOf("1","2","3"),values)
        assertEquals(2,calls.size);assertEquals(2,keys.distinct().size)
        assertEquals("1",calls[0].getJSONObject("values").s("id"))
        assertEquals("2",calls[1].getJSONObject("values").s("id"))
        assertFalse(values.has("id"));assertTrue(result.optBoolean("stopped"))
        assertEquals(listOf("done","failed","skipped"),result.rows("results").map{it.s("status")})
    }
    @Test fun expiredLoginStopsWithoutSendingNotificationsAfterwards() = runBlocking {
        var calls=0
        val source=object:Source {
            override suspend fun call(route:String,data:JSONObject,post:Boolean,key:String?):JSONObject {
                calls++;assertEquals("orders/notify",route);assertEquals("a",data.s("trade_no"))
                throw ApiFailure("会话过期",401)
            }
        }
        val result=executeBatch(source,"order-notify","trade_no",listOf("a","b"),obj())
        assertEquals(1,calls);assertTrue(result.optBoolean("auth_expired"))
        assertEquals("skipped",result.rows("results")[1].s("status"))
    }
    @Test fun merchantRejectingCallbackIsARecordedFailure() = runBlocking {
        var calls=0
        val source=object:Source {
            override suspend fun call(route:String,data:JSONObject,post:Boolean,key:String?):JSONObject {
                calls++
                return obj("message" to if(calls==1)"商户未返回 success"else"商户已确认","delivered" to (calls!=1))
            }
        }
        val result=executeBatch(source,"order-notify","trade_no",listOf("a","b"),obj())
        assertEquals(2,calls)
        assertEquals(listOf("failed","done"),result.rows("results").map{it.s("status")})
    }
}
