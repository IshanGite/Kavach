package com.kavach.app.sense

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kavach.app.container
import com.kavach.core.model.MessageReceived
import com.kavach.core.model.RemoteAccessDetected

/**
 * Reads posted notifications (WhatsApp / Telegram / bank and UPI apps, UPI collect requests) and spots remote-access apps.
 * Plain SMS is handled by SmsReceiver (exact sender). Only text is read; nothing is stored or sent anywhere.
 */
class KavachNotificationListener : NotificationListenerService() {
    private val recent = LinkedHashMap<String, Long>()

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val c = container()
        if (!c.settings.state.value.protectionEnabled) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        c.appConfig.remoteAccessName(pkg)?.let { c.live.emit(RemoteAccessDetected(System.currentTimeMillis(), it)); return }
        if (pkg in SMS_PACKAGES) return
        val watched = pkg in MESSAGING_PACKAGES || c.appConfig.paymentAppName(pkg) != null
        if (!watched) return
        if (sbn.notification.category == Notification.CATEGORY_CALL) return // call UI notifications are handled by the VoIP detector

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (text.isBlank()) return

        val key = pkg + text.hashCode()
        val now = System.currentTimeMillis()
        synchronized(recent) {
            if (now - (recent[key] ?: 0L) < 5_000) return // notifications are often re-posted for updates
            recent[key] = now
            if (recent.size > 50) recent.remove(recent.keys.first())
        }
        c.live.emit(MessageReceived(now, title.ifBlank { pkg }, text))
    }

    /** Is there an ongoing call notification from this app? Used to tell whether a WhatsApp call is still live. */
    fun hasOngoingCall(pkg: String): Boolean = runCatching {
        activeNotifications.any { it.packageName == pkg && it.notification.category == Notification.CATEGORY_CALL }
    }.getOrDefault(false)

    companion object {
        @Volatile var instance: KavachNotificationListener? = null
        private val SMS_PACKAGES = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.messaging")
        private val MESSAGING_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms")
    }
}
