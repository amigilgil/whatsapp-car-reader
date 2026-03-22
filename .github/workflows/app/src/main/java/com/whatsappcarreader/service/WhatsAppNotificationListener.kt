package com.whatsappcarreader.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Notification listener — two jobs:
 *  1. Backup message reading when Accessibility misses something
 *  2. Captures the notification RemoteInput reply action for hands-free replies
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WA_NotifListener"
        private val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val recentKeys = mutableListOf<String>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in WA_PACKAGES) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (text.isBlank() || isMedia(text)) return

        val key = "${sbn.key}|${text.take(40)}"
        if (key in recentKeys) return
        recentKeys.add(key)
        if (recentKeys.size > 100) recentKeys.removeAt(0)

        // Grab the RemoteInput "Reply" action from the notification
        val replyAction = sbn.notification?.actions?.firstOrNull { a ->
            a.remoteInputs?.isNotEmpty() == true
        }

        val isGroup = title.contains(":")
        val sender = if (isGroup) title.substringBefore(":").trim() else title.trim()

        Log.i(TAG, "Notification: $sender — replyAction=${replyAction != null}")

        val intent = Intent(WhatsAppAccessibilityService.ACTION_NEW_MESSAGE).apply {
            setPackage(packageName)
            putExtra("sender_key",  sender.lowercase().replace(" ", "_"))
            putExtra("sender_name", sender)
            putExtra("text",        text)
            putExtra("chat_name",   title.trim())
            putExtra("is_group",    isGroup)
            if (replyAction != null) putExtra("notif_reply_action", replyAction)
        }
        sendBroadcast(intent)
    }

    private fun isMedia(t: String) = listOf(
        "📷","🎵","🎤","📹","📄","Photo","Video","Audio",
        "תמונה","וידאו","אודיו","הודעה קולית","Sticker"
    ).any { t.contains(it) }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
