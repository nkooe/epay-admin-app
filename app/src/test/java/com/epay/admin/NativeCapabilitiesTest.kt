package com.epay.admin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class NativeCapabilitiesTest {
    @Test fun monitorConfigurationMatchesPcAndMobileProtocol(){
        val encoded=monitorConfiguration("https://pay.example.com/site/","1000","fixture-密钥")
        val data=JSONObject(String(Base64.getDecoder().decode(encoded),Charsets.UTF_8))
        assertEquals(setOf("site","pid","key"),data.keys().asSequence().toSet())
        assertEquals("https://pay.example.com/site",data.s("site"))
        assertEquals("1000",data.s("pid"));assertEquals("fixture-密钥",data.s("key"))
    }
    @Test fun configurationRejectsMissingOrInvalidMerchant(){
        for(pid in listOf("","0","abc","-1"))assertTrue(runCatching{monitorConfiguration("https://pay.example.com",pid,"key")}.isFailure)
        assertTrue(runCatching{monitorConfiguration("https://pay.example.com","1000","")}.isFailure)
    }
    @Test fun monitorFailureBackoffCapsAtThirtySeconds(){
        assertEquals(5000L,monitorPollDelayMs(0));assertEquals(5000L,monitorPollDelayMs(1))
        assertEquals(10000L,monitorPollDelayMs(2));assertEquals(20000L,monitorPollDelayMs(3));assertEquals(30000L,monitorPollDelayMs(4))
    }
    @Test fun modesAndCodeTypesApplyTogether(){
        val field=obj("show_when" to obj("pay_mode" to JSONArray(listOf("self")),"qrcode_type" to JSONArray(listOf("qrcode","image"))))
        assertTrue(fieldVisible(field,mapOf("pay_mode" to "self","qrcode_type" to "image")))
        assertFalse(fieldVisible(field,mapOf("pay_mode" to "receipt","qrcode_type" to "image")))
        assertFalse(fieldVisible(field,mapOf("pay_mode" to "self","qrcode_type" to "pid")))
        assertTrue(fieldVisible(obj(),emptyMap()))
    }
}
