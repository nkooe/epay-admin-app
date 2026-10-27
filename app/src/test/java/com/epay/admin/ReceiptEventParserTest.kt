package com.epay.admin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReceiptEventParserTest {
    @Test fun paymentFramesArriveIndividuallyAndCommentsDoNotNotify() {
        val received = mutableListOf<Pair<String,JSONObject>>()
        val parser = ReceiptEventParser { type,event -> received += type to event }
        listOf(": ping","","event: ready","data: {\"server_time\":1}","","event: receipt","id: 5","data: {\"id\":5,\"trade_no\":\"order-5\"}","").forEach(parser::line)
        assertEquals(listOf("ready","receipt"),received.map { it.first })
        assertEquals("order-5",received.last().second.s("trade_no"))
        parser.line("event: heartbeat");parser.line("data: {\"server_time\":2}");parser.line("")
        assertEquals("heartbeat",received.last().first)
    }
    @Test fun fragmentedMultiLineJsonIsParsedAtFrameBoundary() {
        var received = ""
        val parser = ReceiptEventParser { _,event -> received=event.s("trade_no") }
        listOf("event: receipt","data: {\"id\":5,","data: \"trade_no\":\"long-order\"}").forEach(parser::line)
        assertEquals("",received)
        parser.line("")
        assertEquals("long-order",received)
    }
    @Test fun malformedAndOversizedEventsCloseTheConnection() {
        val parser=ReceiptEventParser { _,_ -> fail("invalid event delivered") }
        parser.line("event: receipt");parser.line("data: invalid")
        assertThrows(IOException::class.java) { parser.line("") }
        assertThrows(IOException::class.java) { parser.line("data: "+"x".repeat(16_385)) }
    }
}
