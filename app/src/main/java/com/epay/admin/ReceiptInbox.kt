package com.epay.admin

import org.json.JSONArray
import org.json.JSONObject

/** Bounded history for the one receipt card. Cursor persistence remains independent. */
internal class ReceiptInbox(saved: JSONObject = JSONObject()) {
    companion object { const val RETAINED = 50; const val VISIBLE = 6 }
    private val entries = mutableListOf<JSONObject>()
    var total: Long = saved.optLong("total").coerceAtLeast(0); private set
    val latest: JSONObject? get() = entries.firstOrNull()
    val visible: List<JSONObject> get() = entries.take(VISIBLE)
    init {
        val rows = saved.optJSONArray("rows") ?: JSONArray()
        for (index in 0 until minOf(rows.length(), RETAINED)) {
            rows.optJSONObject(index)?.takeIf { valid(it) }?.let { entries.add(JSONObject(it.toString())) }
        }
        entries.sortByDescending { it.optLong("id") }
        total = maxOf(total, entries.size.toLong())
    }
    private fun valid(event: JSONObject) = event.optLong("id") > 0 && event.optString("trade_no").isNotBlank() && event.optString("body").isNotBlank()
    fun contains(event: JSONObject) = valid(event) && entries.any { it.optLong("id") == event.optLong("id") || it.optString("trade_no") == event.optString("trade_no") }
    fun add(event: JSONObject): Boolean {
        if (!valid(event) || contains(event)) return false
        entries.add(JSONObject().put("id",event.optLong("id")).put("trade_no",event.optString("trade_no"))
            .put("body",event.optString("body").take(1024)).put("created",event.optLong("created")))
        entries.sortByDescending { it.optLong("id") }
        while (entries.size > RETAINED) entries.removeAt(entries.lastIndex)
        if (total < Long.MAX_VALUE) total++
        return true
    }
    fun clear() { entries.clear(); total = 0 }
    fun save(): JSONObject = JSONObject().put("total", total).put("rows", JSONArray(entries))
}
