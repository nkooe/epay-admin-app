package com.epay.admin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReceiptInboxTest {
    private fun event(id: Long, trade: String = "order-$id") = JSONObject()
        .put("id",id).put("trade_no",trade).put("body","商户 1000 · 微信支付 · ¥0.10").put("created",1_792_510_000L+id)

    @Test fun identicalAmountsFromDifferentOrdersAccumulateIndividually() {
        val inbox = ReceiptInbox()
        (1L..3L).forEach { assertTrue(inbox.add(event(it))) }
        assertEquals(3L,inbox.total)
        assertEquals(listOf("order-3","order-2","order-1"),inbox.visible.map { it.getString("trade_no") })
    }
    @Test fun replayAndSameOrderDoNotIncreaseCountOrReplaceNewest() {
        val inbox = ReceiptInbox()
        assertTrue(inbox.add(event(1)))
        assertTrue(inbox.add(event(2)))
        assertFalse(inbox.add(event(1)))
        assertFalse(inbox.add(event(3,"order-1")))
        assertEquals(2L,inbox.total)
        assertEquals("order-2",inbox.latest!!.getString("trade_no"))
    }
    @Test fun retainedRowsAreBoundedWithoutLosingTotalAndSurviveReconnectOrRestart() {
        val inbox = ReceiptInbox()
        (1L..75L).forEach { assertTrue(inbox.add(event(it))) }
        val restored = ReceiptInbox(JSONObject(inbox.save().toString()))
        assertEquals(75L,restored.total)
        assertEquals(ReceiptInbox.RETAINED,restored.save().getJSONArray("rows").length())
        assertEquals(6,restored.visible.size)
        assertEquals("order-75",restored.latest!!.getString("trade_no"))
        assertFalse(restored.add(event(75)))
        assertTrue(restored.add(event(76)))
        assertEquals(76L,restored.total)
    }
    @Test fun invalidEventsDoNotHideRealReceiptsAndClearOnlyResetsHistory() {
        val inbox = ReceiptInbox()
        assertFalse(inbox.add(event(0)))
        assertFalse(inbox.add(event(1).put("trade_no","")))
        assertFalse(inbox.add(event(1).put("body","")))
        assertEquals(0L,inbox.total)
        assertTrue(inbox.add(event(2)))
        inbox.clear()
        val restored=ReceiptInbox(inbox.save())
        assertEquals(0L,restored.total)
        assertNull(restored.latest)
        assertTrue(restored.add(event(3)))
        assertEquals(1L,restored.total)
    }
}
