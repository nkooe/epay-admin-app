package com.epay.admin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldOptionsTest {
    @Test fun phpNumericAndNamedOptionsKeepTheirSubmittedKeys() {
        val numeric=JSONObject("""{"options":["停用","启用"]}""").fieldOptions()
        assertEquals("停用",numeric.s("0"))
        assertEquals("启用",numeric.s("1"))
        val named=JSONObject("""{"options":{"2":"微信支付","7":"支付宝"}}""").fieldOptions()
        assertEquals("微信支付",named.s("2"))
        assertEquals("支付宝",named.s("7"))
        assertEquals(0,JSONObject().fieldOptions().length())
    }
}
