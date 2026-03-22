package com.whatsappcarreader.service

import android.app.*
import android.content.*
import android.media.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.whatsappcarreader.R
import com.whatsappcarreader.manager.*
import com.whatsappcarreader.model.IncomingMessage
import com.whatsappcarreader.ui.MainActivity
import com.whatsappcarreader.util.PrefsManager
import kotlinx.coroutines.*

/**
 * Main foreground service.
 *
 * Responsibilities:
 *  - Receives WhatsApp messages via broadcast
 *  - Queues and reads them aloud via FreeTtsManager (per-contact voice)
 *  - Listens for voice reply via SpeechReplyManager → sends back to same chat
 *  - Manages audio focus (ducks music, restores after)
 *  - Exposes actions: START, STOP, PAUSE_RESUME, SKIP, REPLY_VOICE, REREAD
 *  - Broadcasts status updates to MainActivity and Widget
 */
class CarReaderForegroundService : Service() {

    companion object {
        private const val TAG = "CarReaderService"
        private const val CHANNEL_ID = "car_reader_v2"
        private const val NOTIF_ID = 1001

        const val ACTION_START        = "com.whatsappcarreader.START"
        const val ACTION_STOP         = "com.whatsappcarreader.STOP"
        const val ACTION_PAUSE_RESUME = "com.whatsappcarreader.PAUSE_RESUME"
        const val ACTION_SKIP         = "com.whatsappcarreader.SKIP"
        const val ACTION_REPLY_VOICE  = "com.whatsappcarreader.REPLY_VOICE"
        const val ACTION_REREAD       = "com.whatsappcarreader.REREAD"
        const val ACTION_STATUS       = "com.whatsappcarreader.STATUS"

        // Status broadcast extras
        const val EXTRA_ACTIVE          = "active"
        const val EXTRA_CAR_CONNECTED   = "car_connected"
        const val EXTRA_PAUSED          = "paused"
        const val EXTRA_REPLYING        = "replying"
        const val EXTRA_LAST_SENDER     = "last_sender"
        const val EXTRA_LAST_TEXT       = "last_text"
        const val EXTRA_QUEUE_SIZE      = "queue_size"
    }

    private lateinit var prefs: PrefsManager
    private lateinit var tts: FreeTtsManager
    private lateinit var speechReply: SpeechReplyManager
    private lateinit var audioManager: AudioManager

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // State
    var isCarConnected = false; private set
    var isPaused = false;       private set
    var isReplying = false;     private set
    var isActive = false;       private set

    // Queue
    private val queue = ArrayDeque<IncomingMessage>()
    private var processingJob: Job? = null

    // Last message (for re-read & reply)
    private var lastMsg: IncomingMessage? = null
    private var lastNotifReplyAction: Notification.Action? = null

    // AudioFocus
    private lateinit var focusRequest: AudioFocusRequest
    private var hasFocus = false

    // ─── Message Receiver ─────────────────────────────────────────────────────

    private val msgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != WhatsAppAccessibilityService.ACTION_NEW_MESSAGE) return

            val msg = IncomingMessage(
                senderKey         = intent.getStringExtra("sender_key") ?: return,
                senderDisplayName = intent.getStringExtra("sender_name") ?: "Unknown",
                text              = intent.getStringExtra("text") ?: return,
                chatName          = intent.getStringExtra("chat_name") ?: "",
                isGroup           = intent.getBooleanExtra("is_group", false)
            )

            // Store reply action if provided
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lastNotifReplyAction = intent.getParcelableExtra(
                    "notif_reply_action", Notification.Action::class.java
                )
            }

            onNewMessage(msg)
        }
    }

    // Car disconnect receiver
    private val carDisconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == "com.whatsappcarreader.CAR_DISCONNECTED") {
                isCarConnected = false
                updateNotification()
                broadcastStatus()
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        tts = FreeTtsManager(this)
        speechReply = SpeechReplyManager(this)

        initAudioFocus()
        createNotificationChannel()

        registerReceiver(msgReceiver,
            IntentFilter(WhatsAppAccessibilityService.ACTION_NEW_MESSAGE),
            RECEIVER_NOT_EXPORTED)
        registerReceiver(carDisconnectReceiver,
            IntentFilter("com.whatsappcarreader.CAR_DISCONNECTED"),
            RECEIVER_NOT_EXPORTED)

        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isActive = true
                val carName = intent.getStringExtra("car_device_name")
                if (carName != null) {
                    isCarConnected = true
                    Log.i(TAG, "Car connected: $carName")
                }
                startForeground(NOTIF_ID, buildNotification())
                broadcastStatus()
            }
            ACTION_STOP -> {
                isActive = false
                tts.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                broadcastStatus()
            }
            ACTION_PAUSE_RESUME -> {
                isPaused = !isPaused
                if (isPaused) {
                    tts.stop()
                    processingJob?.cancel()
                } else {
                    processQueue()
                }
                updateNotification()
                broadcastStatus()
            }
            ACTION_SKIP -> {
                tts.stop()
                processingJob?.cancel()
                scope.launch {
                    delay(200)
                    processQueue()
                }
            }
            ACTION_REPLY_VOICE -> {
                scope.launch { startVoiceReply() }
            }
            ACTION_REREAD -> {
                lastMsg?.let { msg ->
                    queue.addFirst(msg)
                    if (processingJob?.isActive != true) processQueue()
                }
            }
            else -> {
                isActive = true
                startForeground(NOTIF_ID, buildNotification())
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
        scope.cancel()
        tts.destroy()
        speechReply.destroy()
        abandonFocus()
        unregisterReceiver(msgReceiver)
        unregisterReceiver(carDisconnectReceiver)
        broadcastStatus()
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Message Handling ─────────────────────────────────────────────────────

    private fun onNewMessage(msg: IncomingMessage) {
        if (prefs.readOnlyWhenCarConnected && !isCarConnected) {
            Log.d(TAG, "Ignored — car not connected")
            return
        }
        lastMsg = msg
        if (!isPaused) {
            queue.addLast(msg)
            updateNotification()
            broadcastStatus()
            if (processingJob?.isActive != true) processQueue()
        } else {
            queue.addLast(msg)
            broadcastStatus()
        }
    }

    // ─── Queue Processing ─────────────────────────────────────────────────────

    private fun processQueue() {
        if (isPaused || isReplying || queue.isEmpty()) return

        processingJob = scope.launch {
            while (queue.isNotEmpty() && !isPaused && !isReplying) {
                val msg = queue.removeFirst()
                broadcastStatus()
                readMessage(msg)
                delay(prefs.pauseBetweenMessages)
            }
            updateNotification()
            broadcastStatus()
        }
    }

    private suspend fun readMessage(msg: IncomingMessage) {
        requestFocus()

        // Announcement: "הודעה מ-[שם]" or "הודעה מ-[שם] בקבוצה [קבוצה]"
        if (prefs.announceSenderName) {
            val announcement = buildAnnouncement(msg)
            tts.speakAnnouncement(announcement)
            delay(150)
        }

        // Message body in contact's unique voice
        tts.speakAsContact(msg.text, msg.senderKey, msg.senderDisplayName)

        abandonFocus()
    }

    private fun buildAnnouncement(msg: IncomingMessage): String {
        val sb = StringBuilder()
        if (prefs.announceSenderName) sb.append("הודעה מ${msg.senderDisplayName}")
        if (prefs.announceGroupName && msg.isGroup) sb.append(", בקבוצה ${msg.chatName}")
        if (sb.isNotEmpty()) sb.append(":")
        return sb.toString()
    }

    // ─── Voice Reply ──────────────────────────────────────────────────────────

    private suspend fun startVoiceReply() {
        val replyTo = lastMsg ?: run {
            tts.speakAnnouncement("אין הודעה להשיב עליה")
            return
        }

        isReplying = true
        tts.stop()
        updateNotification()
        broadcastStatus()

        try {
            // Announce we're listening
            tts.speakAnnouncement("מאזין... דבר עכשיו")
            delay(300)

            requestFocus()
            val result = speechReply.listenForReply(
                onPartialResult = { partial ->
                    Log.d(TAG, "STT partial: $partial")
                    broadcastStatus(extraText = partial)
                }
            )
            abandonFocus()

            when (result) {
                is SttResult.Success -> {
                    val text = result.text
                    Log.i(TAG, "Replying to ${replyTo.senderDisplayName}: '$text'")

                    // Confirm aloud
                    tts.speakAnnouncement("שולח: $text")
                    delay(500)

                    // Send via notification RemoteInput (hands-free, no UI)
                    val sent = if (lastNotifReplyAction != null) {
                        speechReply.sendViaNotificationAction(lastNotifReplyAction, text)
                    } else {
                        // Fallback: open WhatsApp with pre-filled message
                        speechReply.sendViaWhatsAppUri(replyTo.senderKey, text)
                    }

                    if (sent) {
                        tts.speakAnnouncement("ההודעה נשלחה")
                    } else {
                        tts.speakAnnouncement("שגיאה בשליחה — נסה שוב")
                    }
                }
                is SttResult.Error -> {
                    tts.speakAnnouncement(result.message)
                }
            }
        } finally {
            isReplying = false
            updateNotification()
            broadcastStatus()
            // Resume queue if there are pending messages
            if (queue.isNotEmpty() && !isPaused) {
                delay(1000)
                processQueue()
            }
        }
    }

    // ─── Audio Focus ──────────────────────────────────────────────────────────

    private fun initAudioFocus() {
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { hasFocus = false; tts.stop() }
                    AudioManager.AUDIOFOCUS_GAIN -> hasFocus = true
                }
            }
            .build()
    }

    private fun requestFocus() {
        if (!hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focusRequest) ==
                       AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        NotificationChannel(CHANNEL_ID, "WhatsApp Car Reader", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "קריאת הודעות בנהיגה"; setShowBadge(false) }
            .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
    }

    private fun buildNotification(): Notification {
        val mainPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        fun servicePi(action: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, CarReaderForegroundService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE)

        val statusLine = when {
            isReplying -> "🎤 מאזין לתשובה..."
            !isCarConnected && prefs.readOnlyWhenCarConnected -> "⚪ ממתין לחיבור לרכב"
            isPaused -> "⏸ מושהה"
            queue.isNotEmpty() -> "📨 ${queue.size} הודעות בתור"
            lastMsg != null -> "✅ ${lastMsg!!.senderDisplayName}: ${lastMsg!!.text.take(25)}..."
            else -> "🟢 מאזין להודעות"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhatsApp Car Reader")
            .setContentText(statusLine)
            .setSmallIcon(R.drawable.ic_car_notification)
            .setContentIntent(mainPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (isPaused) R.drawable.ic_play else R.drawable.ic_pause,
                if (isPaused) "המשך" else "השהה",
                servicePi(ACTION_PAUSE_RESUME, 1)
            )
            .addAction(R.drawable.ic_reply, "השב", servicePi(ACTION_REPLY_VOICE, 2))
            .addAction(R.drawable.ic_skip, "דלג", servicePi(ACTION_SKIP, 3))
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    // ─── Status Broadcast (→ Widget + MainActivity) ───────────────────────────

    fun broadcastStatus(extraText: String? = null) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_ACTIVE, isActive)
            putExtra(EXTRA_CAR_CONNECTED, isCarConnected)
            putExtra(EXTRA_PAUSED, isPaused)
            putExtra(EXTRA_REPLYING, isReplying)
            putExtra(EXTRA_LAST_SENDER, lastMsg?.senderDisplayName ?: "")
            putExtra(EXTRA_LAST_TEXT, extraText ?: lastMsg?.text?.take(40) ?: "")
            putExtra(EXTRA_QUEUE_SIZE, queue.size)
        }
        sendBroadcast(intent)
        // Also update widget
        CarReaderWidget.update(this)
    }
}
