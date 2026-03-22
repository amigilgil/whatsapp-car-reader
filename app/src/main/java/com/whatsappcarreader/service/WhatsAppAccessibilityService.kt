package com.whatsappcarreader.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.whatsappcarreader.model.IncomingMessage
import com.whatsappcarreader.util.PrefsManager

/**
 * Accessibility Service that listens to WhatsApp notifications and window events.
 *
 * Strategy:
 *  - Primary: TYPE_NOTIFICATION_STATE_CHANGED — catches notification banners.
 *             This gives us sender + message text reliably without needing
 *             notification permission hacks.
 *  - Secondary: TYPE_WINDOW_CONTENT_CHANGED — for in-app messages when WA is open.
 *             We read the chat window's message list.
 *
 * The service broadcasts IncomingMessage objects via LocalBroadcastManager
 * to CarReaderForegroundService which handles TTS and playback.
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WA_Accessibility"
        const val WA_PACKAGE = "com.whatsapp"
        const val WA_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        const val ACTION_NEW_MESSAGE = "com.whatsappcarreader.NEW_MESSAGE"
        const val EXTRA_MESSAGE = "message_json"

        // Notification text node IDs in WhatsApp
        private const val NOTIF_TITLE_ID = "android:id/title"
        private const val NOTIF_TEXT_ID  = "android:id/text"

        // Last seen message to avoid duplicates
        private var lastMessageKey = ""
    }

    private lateinit var prefs: PrefsManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = PrefsManager(this)
        Log.i(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // Triggered when WhatsApp shows a notification (message arrives)
                handleNotificationEvent(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Triggered when WhatsApp is open and new message arrives in-view
                if (event.packageName?.toString() in listOf(WA_PACKAGE, WA_BUSINESS_PACKAGE)) {
                    handleWindowContentEvent(event)
                }
            }
            else -> { /* ignore */ }
        }
    }

    // ─── Notification parsing ─────────────────────────────────────────────────

    private fun handleNotificationEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in listOf(WA_PACKAGE, WA_BUSINESS_PACKAGE)) return

        val parcelable = event.parcelableData ?: return

        // Extract from notification text
        val texts = event.text
        if (texts.size < 2) return

        // texts[0] = sender or "Group: Sender"
        // texts[1] = message body
        val rawTitle = texts[0]?.toString() ?: return
        val body = texts[1]?.toString() ?: return

        if (body.isBlank() || body == "...") return

        // Deduplicate
        val msgKey = "$rawTitle|$body"
        if (msgKey == lastMessageKey) return
        lastMessageKey = msgKey

        // Parse group vs individual
        val isGroup = rawTitle.contains("@") || rawTitle.contains(":")
        val (sender, chat) = if (isGroup && rawTitle.contains(": ")) {
            val parts = rawTitle.split(": ", limit = 2)
            Pair(parts[0].trim(), parts[1].trim())
        } else {
            Pair(rawTitle.trim(), rawTitle.trim())
        }

        // Skip media notifications
        if (body.startsWith("📷") || body.startsWith("🎵") || body.startsWith("🎤")
            || body.contains("Photo") || body.contains("Audio") || body.contains("Video")
            || body.contains("תמונה") || body.contains("קבצי מדיה")) {
            Log.d(TAG, "Skipping media message from $sender")
            return
        }

        Log.i(TAG, "New message from $sender: '${body.take(50)}'")
        dispatchMessage(
            IncomingMessage(
                senderKey = normalizeKey(sender),
                senderDisplayName = sender,
                text = body,
                chatName = chat,
                isGroup = isGroup
            )
        )
    }

    // ─── Window content parsing (WhatsApp open) ───────────────────────────────

    private var lastWindowMessageText = ""

    private fun handleWindowContentEvent(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return

        // Look for the latest message bubble
        val messageNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByViewIdContaining(root, "message_text", messageNodes)

        if (messageNodes.isEmpty()) {
            root.recycle()
            return
        }

        // Get the last (most recent) text message node
        val lastNode = messageNodes.lastOrNull() ?: run {
            root.recycle()
            return
        }
        val text = lastNode.text?.toString() ?: run {
            root.recycle()
            return
        }

        if (text == lastWindowMessageText || text.isBlank()) {
            root.recycle()
            return
        }
        lastWindowMessageText = text

        // Try to find sender (in group chats it's nearby)
        val sender = findNearestSenderName(lastNode) ?: "Unknown"

        root.recycle()

        // Dispatch only if different from notification we already handled
        if ("$sender|$text" == lastMessageKey) return

        Log.d(TAG, "In-window message from $sender: '${text.take(50)}'")
        dispatchMessage(
            IncomingMessage(
                senderKey = normalizeKey(sender),
                senderDisplayName = sender,
                text = text,
                chatName = sender,
                isGroup = false
            )
        )
    }

    private fun findNodesByViewIdContaining(
        node: AccessibilityNodeInfo,
        idPart: String,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        val id = node.viewIdResourceName ?: ""
        if (idPart in id && node.text != null) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findNodesByViewIdContaining(it, idPart, result) }
        }
    }

    private fun findNearestSenderName(messageNode: AccessibilityNodeInfo): String? {
        // Walk up to parent, then look for a sibling that contains sender name
        val parent = messageNode.parent ?: return null
        for (i in 0 until parent.childCount) {
            val sibling = parent.getChild(i) ?: continue
            val id = sibling.viewIdResourceName ?: ""
            if ("sender" in id || "name" in id) {
                return sibling.text?.toString()
            }
        }
        return null
    }

    // ─── Dispatch ─────────────────────────────────────────────────────────────

    private fun dispatchMessage(message: IncomingMessage) {
        val intent = Intent(ACTION_NEW_MESSAGE).apply {
            setPackage(packageName)
            putExtra("sender_key", message.senderKey)
            putExtra("sender_name", message.senderDisplayName)
            putExtra("text", message.text)
            putExtra("chat_name", message.chatName)
            putExtra("is_group", message.isGroup)
        }
        sendBroadcast(intent)
    }

    private fun normalizeKey(name: String): String =
        name.lowercase().replace(" ", "_").replace("+", "").replace("-", "")

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility Service destroyed")
    }
}
