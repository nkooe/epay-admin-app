package com.epay.admin

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.io.IOException

/** User-visible, opt-in foreground connection. Never starts secretly from a worker. */
class ReceiptConnectionService : Service() {
    companion object {
        fun start(context: Context) { ContextCompat.startForegroundService(context,Intent(context,ReceiptConnectionService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context,ReceiptConnectionService::class.java)) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnect = Channel<Unit>(Channel.CONFLATED)
    private var connection: Job? = null
    private var stopping = false
    private var listenerRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { reconnect.trySend(Unit) }
    }
    private fun status(text: String) { ReceiptNotificationCenter.status(this,text) }
    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(this,ReceiptNotificationCenter.ID,ReceiptNotificationCenter.begin(this),if(Build.VERSION.SDK_INT>=34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (AppIntegrity.problem(this) != null) { stopSelf();return }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        listenerRegistered = true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AppIntegrity.problem(this) != null) { stopSelf();return START_NOT_STICKY }
        if (intent?.action == ReceiptNotificationCenter.STOP) {
            if (!stopping) {
                stopping = true
                ReceiptNotificationPrefs.setEnabled(this,false);ReceiptNotificationWork.cancel(this)
                connection?.cancel();status("关闭中")
                scope.launch {
                    withTimeoutOrNull(5_000) {
                        try {
                            val vault = SessionVault(this@ReceiptConnectionService)
                            vault.read()?.let { stored ->
                                NetworkSource.forStoredSession(platformUrl(stored.s("base")),vault,stored).call("notifications/device",obj("device_id" to ReceiptNotificationPrefs.deviceId(this@ReceiptConnectionService),"enabled" to 0),true,UUID.randomUUID().toString())
                            }
                        } catch (e: Exception) { if (e is CancellationException) throw e }
                    }
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }
        if (stopping || !ReceiptNotificationPrefs.enabled(this) || !ReceiptNotificationCenter.permissionGranted(this)) { stopSelf();return START_NOT_STICKY }
        if (connection?.isActive != true) connection = scope.launch { maintainConnection() }
        return START_STICKY
    }
    private suspend fun maintainConnection() {
        var retry = 1_000L
        while (scope.isActive && ReceiptNotificationPrefs.enabled(this) && !stopping) {
            if (!ReceiptNotificationCenter.permissionGranted(this)) { stopSelf();return }
            val vault = SessionVault(this)
            val stored = try { vault.read() } catch (_: ApiFailure) { stopSelf();return }
            if (stored == null) { stopSelf();return }
            val base = platformUrl(stored.s("base"))
            if (!ReceiptNotificationPrefs.hasCursor(this,base.toString())) { stopSelf();return }
            val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"epay:receipt-connection")
            try {
                wake.acquire(45_000)
                val source = NetworkSource.forStoredSession(base,vault,stored)
                var rotated = false
                source.streamReceipts(obj("device_id" to ReceiptNotificationPrefs.deviceId(this),"after_id" to ReceiptNotificationPrefs.cursor(this,base.toString()).toString())) { type,event ->
                    when (type) {
                        "ready" -> { retry = 1_000;status("运行中") }
                        "receipt" -> ReceiptNotificationDelivery.deliver(this,base.toString(),event)
                        "end" -> rotated = true
                    }
                }
                if (!rotated) throw IOException("收款连接提前结束")
                // A normal server rotation reconnects immediately with the saved cursor.
                retry = 1_000
                continue
            } catch (e: CancellationException) { throw e }
            catch (e: ApiFailure) {
                if (e.status == 401) {
                    status("登录失效")
                    stopSelf();return
                }
                if (e.status == 403) { ReceiptNotificationPrefs.setEnabled(this,false);ReceiptNotificationWork.cancel(this);stopSelf();return }
                status("重连中")
            } catch (_: Exception) { status("重连中") }
            finally { if (wake.isHeld) wake.release() }
            withTimeoutOrNull(retry) { reconnect.receive() }
            retry = (retry * 2).coerceAtMost(60_000)
        }
        stopSelf()
    }
    override fun onDestroy() {
        connection?.cancel();scope.cancel();reconnect.close()
        if (listenerRegistered) getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE)
        ReceiptNotificationCenter.end(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
