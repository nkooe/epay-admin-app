package com.epay.admin

import android.app.Application
import android.app.Notification
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ReceiptNotificationUiTest {
    @get:Rule val app = createComposeRule()

    @Test fun backgroundAndForegroundReuseSessionWithoutRestoringStaleTokens() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val vault = SessionVault(context)
        val base = platformUrl("https://example.com/")
        val stored = obj("session_id" to "receipt-test-session", "access_token" to "old")
        val foreground = NetworkSource.forStoredSession(base, vault, stored)
        foreground.restore(obj("session_id" to "receipt-test-session", "access_token" to "rotated"))
        assertSame(foreground, NetworkSource.forStoredSession(base, vault, stored))
        assertNotSame(foreground, NetworkSource.forStoredSession(base, vault, obj("session_id" to "another-session")))
        assertNotSame(foreground, NetworkSource.forStoredSession(platformUrl("https://other.example.com/"), vault, stored))
    }

    @Test fun notificationTapWaitsUntilBusyActionFinishes() {
        val gate = CompletableDeferred<Unit>()
        val source = object : Source {
            override suspend fun call(route: String, data: JSONObject, post: Boolean, key: String?): JSONObject {
                if (post && route == "profile/save") gate.await()
                return if (route == "orders/detail") obj("trade_no" to data.s("trade_no"), "status" to 1) else obj()
            }
        }
        lateinit var model: AdminModel
        app.runOnIdle { model = AdminModel(ApplicationProvider.getApplicationContext<Application>(), source) }
        val opened = AtomicInteger()
        app.runOnIdle { model.save("profile/save", obj()) }
        app.waitUntil(10_000) { model.busy }

        app.setContent { AdminTheme { AdminApp(model, "notification-trade") { opened.incrementAndGet() } } }
        app.runOnIdle {
            assertEquals("home", model.current.route)
            assertEquals(0, opened.get())
        }

        gate.complete(Unit)
        app.waitUntil(10_000) { model.current.route == "order" && opened.get() == 1 }
        app.runOnIdle {
            assertEquals("notification-trade", model.current.args.s("trade_no"))
            model.clearSession()
        }
    }

    @Test fun mergedNotificationHasOneTitleAndRetainsAllThreeOrderLinesAcrossStatusChanges() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        ReceiptNotificationCenter.reset(context)
        ReceiptNotificationPrefs.setEnabled(context,true)
        try {
            ReceiptNotificationCenter.begin(context)
            val history = ReceiptInbox()
            (1L..3L).forEach { history.add(obj("id" to it,"trade_no" to "merged-order-$it","body" to "merchant-$it","created" to 1_792_510_000L+it)) }
            context.getSharedPreferences("receipt_inbox_v1",Application.MODE_PRIVATE).edit().putString("inbox",history.save().toString()).commit()
            val card = ReceiptNotificationCenter.begin(context)
            assertEquals("收款通知 · 连接中",card.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(3,card.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.size)
            assertEquals(3,card.number)
            assertTrue(card.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(3,card.actions.size)
            ReceiptNotificationCenter.clearRecords(context)
            val cleared = ReceiptNotificationCenter.begin(context)
            assertEquals(0,cleared.number)
            assertEquals(1,cleared.actions.size)
        } finally {
            ReceiptNotificationPrefs.setEnabled(context,false)
            ReceiptNotificationCenter.end(context)
            ReceiptNotificationCenter.reset(context)
        }
    }

    @Test fun allOrdersNotificationActionOpensThePaidOrderList() {
        val source = object : Source {
            override suspend fun call(route: String, data: JSONObject, post: Boolean, key: String?) = obj()
        }
        lateinit var model: AdminModel
        app.runOnIdle { model=AdminModel(ApplicationProvider.getApplicationContext<Application>(),source) }
        val opened=AtomicInteger()
        app.setContent { AdminTheme { AdminApp(model,notificationOrders=true){opened.incrementAndGet()} } }
        app.waitUntil(10_000) { model.current.route=="orders" && opened.get()==1 }
        app.runOnIdle {
            assertEquals("1",model.current.args.s("status"))
            model.clearSession()
        }
    }
}
