package com.epay.admin

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal object ReceiptNotificationPrefs {
    private const val FILE = "receipt_notifications"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("enabled", enabled).apply() }
    fun deviceId(context: Context): String = prefs(context).getString("device_id", null) ?: UUID.randomUUID().toString().lowercase().also {
        prefs(context).edit().putString("device_id", it).apply()
    }
    private fun cursorKey(base: String) = "cursor:" + MessageDigest.getInstance("SHA-256").digest(base.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    fun hasCursor(context: Context, base: String) = prefs(context).contains(cursorKey(base))
    fun cursor(context: Context, base: String) = prefs(context).getLong(cursorKey(base), 0L)
    @Synchronized fun advance(context: Context, base: String, id: Long) {
        val key = cursorKey(base)
        prefs(context).edit().putLong(key, maxOf(id, prefs(context).getLong(key, 0L))).apply()
    }
    fun initializeCursor(context: Context, base: String, id: Long) = advance(context, base, id)
}

internal object ReceiptNotificationCenter {
    const val CHANNEL = "payment_receipts"
    const val ID = 41001
    const val STOP = "com.epay.admin.STOP_RECEIPT_CONNECTION"
    const val CLEAR = "com.epay.admin.CLEAR_RECEIPT_RECORDS"
    private const val STORE = "receipt_inbox_v1"
    private var state = "连接中"
    @Volatile var foreground = false; private set

    fun permissionGranted(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
        return channel?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"收款通知",NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "管理员收款明细与后台连接状态"
        })
    }
    private fun prefs(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private fun inbox(context: Context): ReceiptInbox {
        val session = SessionVault(context).read()
        val scope = session?.let { it.s("base") + "|" + it.s("session_id") }.orEmpty()
        val stored = prefs(context)
        if (stored.getString("scope",null) != scope) stored.edit().clear().putString("scope",scope).apply()
        val json = runCatching { JSONObject(stored.getString("inbox","{}") ?: "{}") }.getOrDefault(JSONObject())
        return ReceiptInbox(json)
    }
    private fun save(context: Context, inbox: ReceiptInbox) { check(prefs(context).edit().putString("inbox",inbox.save().toString()).commit()) }
    private fun open(context: Context, trade: String? = null, orders: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (trade != null) intent.putExtra(MainActivity.EXTRA_NOTIFICATION_TRADE_NO,trade)
        if (orders) intent.putExtra(MainActivity.EXTRA_NOTIFICATION_ORDERS,true)
        intent.data = Uri.parse(if(orders) "epay-admin://orders/paid" else if(trade!=null) "epay-admin://order/${Uri.encode(trade)}" else "epay-admin://receipts/status")
        return PendingIntent.getActivity(context,ID,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun build(context: Context, inbox: ReceiptInbox, alert: Boolean = false): android.app.Notification {
        ensureChannel(context)
        val latest = inbox.latest
        val text = latest?.let { "${inbox.total} 笔收款 · ${it.s("body")}" } ?: when(state) {
            "运行中" -> "等待收款"
            "连接中" -> "正在连接平台…"
            "重连中" -> "网络中断，正在自动重连"
            "登录失效" -> "请打开 App 重新登录"
            else -> "收款提醒已停止"
        }
        val builder = NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("收款通知 · $state").setContentText(text).setContentIntent(open(context,latest?.s("trade_no")))
            .setOngoing(foreground).setAutoCancel(false).setOnlyAlertOnce(!alert).setSilent(!alert)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setNumber(inbox.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).setShowWhen(latest!=null)
        latest?.let { builder.setWhen(it.optLong("created").takeIf { time -> time>0 }?.times(1000) ?: System.currentTimeMillis()) }
        if (latest != null) {
            val style = NotificationCompat.InboxStyle().setBigContentTitle("收款通知 · $state")
            val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault())
            inbox.visible.forEach { row ->
                val at = time.format(java.time.Instant.ofEpochSecond(row.optLong("created").coerceAtLeast(0)))
                style.addLine("$at  ${row.s("body")}")
            }
            style.setSummaryText("累计 ${inbox.total} 笔 · 最近 ${inbox.visible.size} 笔")
            builder.setStyle(style).addAction(0,"查看订单",open(context,orders=true))
            val clear = PendingIntent.getBroadcast(context,ID+2,Intent(context,ReceiptNotificationActionReceiver::class.java).setAction(CLEAR),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0,"清空记录",clear)
        }
        if (foreground) {
            val stop = PendingIntent.getService(context,ID+1,Intent(context,ReceiptConnectionService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0,"停止提醒",stop)
        }
        return builder.build()
    }
    @Synchronized fun begin(context: Context): android.app.Notification {
        foreground = true; state = "连接中"
        return build(context,inbox(context))
    }
    @Synchronized fun status(context: Context, value: String) {
        if (state == value) return
        state = value
        notify(context,build(context,inbox(context)))
    }
    @Synchronized fun end(context: Context) {
        foreground = false
        if (state != "登录失效") state = if(ReceiptNotificationPrefs.enabled(context)) "连接中断" else "已停止"
        val inbox = inbox(context)
        if (inbox.latest != null || state == "登录失效") notify(context,build(context,inbox))
        else context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
    @Synchronized fun clearRecords(context: Context) {
        val inbox = inbox(context); inbox.clear(); save(context,inbox)
        if(foreground) notify(context,build(context,inbox)) else context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
    @Synchronized fun reset(context: Context) {
        prefs(context).edit().clear().apply()
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
    @SuppressLint("MissingPermission") // 每次投递前检查通知权限。
    private fun notify(context: Context, notification: android.app.Notification): Boolean = try {
        NotificationManagerCompat.from(context).notify(ID,notification)
        true
    } catch (_: SecurityException) { false }

    @Synchronized fun post(context: Context, base: String, event: JSONObject): Boolean {
        if (!permissionGranted(context)) return false
        if (SessionVault(context).read()?.s("base") != base) return false
        val inbox = inbox(context)

        if (!inbox.add(event)) return inbox.contains(event)
        if (!foreground) state = "后台检查"
        if (!notify(context,build(context,inbox,alert=true))) return false
        save(context,inbox)
        return true
    }
}

class ReceiptNotificationActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ReceiptNotificationCenter.CLEAR) ReceiptNotificationCenter.clearRecords(context)
    }
}

/** 收款事件按编号投递，游标只在系统接受通知后推进。 */
internal object ReceiptNotificationDelivery {
    @Synchronized fun deliver(context: Context, base: String, event: JSONObject) {
        val id = event.optLong("id")
        if (ReceiptNotificationPrefs.enabled(context) && id > ReceiptNotificationPrefs.cursor(context, base) && ReceiptNotificationCenter.post(context, base, event)) ReceiptNotificationPrefs.advance(context, base, id)
    }
}
