package com.epay.admin

import java.io.IOException
import org.json.JSONObject

/** Incremental, bounded SSE frames; comments and unknown event types are ignored. */
internal class ReceiptEventParser(private val receive: (String, JSONObject) -> Unit) {
    private var event = "message"
    private val data = StringBuilder()
    fun line(line: String) {
        if (line.length > 16_384 || data.length + line.length > 32_768) throw IOException("收款事件过大")
        if (line.isEmpty()) {
            val type = event
            val payload = data.toString()
            event = "message"; data.setLength(0)
            if (type in setOf("receipt", "ready", "heartbeat", "error", "end") && payload.isNotBlank()) {
                val parsed = try { JSONObject(payload) } catch (_: Exception) { throw IOException("收款事件格式错误") }
                receive(type, parsed)
            }
        } else if (!line.startsWith(":")) {
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
            }
        }
    }
}
