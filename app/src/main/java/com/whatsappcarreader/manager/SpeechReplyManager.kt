package com.whatsappcarreader.manager

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale

/**
 * Manages voice replies to WhatsApp messages.
 *
 * Flow:
 *  1. User presses "Reply" button / volume key
 *  2. We start Android's SpeechRecognizer (offline-capable, FREE)
 *  3. Recognized text is shown to user for confirmation
 *  4. On confirm → send via WhatsApp RemoteInput API (notification action)
 *     Fallback → open WhatsApp chat with pre-filled text
 */
class SpeechReplyManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechReplyManager"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // ─── Speech Recognition ───────────────────────────────────────────────────

    /**
     * Start listening and return the recognized text.
     * Uses the device's default STT engine (Google STT or offline).
     */
    suspend fun listenForReply(
        onPartialResult: (String) -> Unit = {}
    ): SttResult = suspendCancellableCoroutine { cont ->

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            cont.resume(SttResult.Error("זיהוי דיבור לא זמין במכשיר זה")) {}
            return@suspendCancellableCoroutine
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")          // Hebrew first
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                Log.d(TAG, "Ready for speech")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                onPartialResult(partial)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    Log.i(TAG, "STT result: '$text'")
                    cont.resume(SttResult.Success(text)) {}
                } else {
                    cont.resume(SttResult.Error("לא זוהה דיבור")) {}
                }
            }

            override fun onError(error: Int) {
                isListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "שגיאת מיקרופון"
                    SpeechRecognizer.ERROR_NO_MATCH -> "לא נמצאה התאמה — נסה שוב"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "לא זוהה דיבור"
                    SpeechRecognizer.ERROR_NETWORK -> "בעיית רשת — נסה במצב לא מקוון"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "זיהוי דיבור עסוק"
                    else -> "שגיאה: $error"
                }
                Log.w(TAG, "STT error: $msg ($error)")
                cont.resume(SttResult.Error(msg)) {}
            }

            override fun onBeginningOfSpeech() { Log.d(TAG, "Speech started") }
            override fun onEndOfSpeech() { Log.d(TAG, "Speech ended") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)

        cont.invokeOnCancellation {
            stopListening()
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
    }

    fun isCurrentlyListening() = isListening

    // ─── Send Reply ───────────────────────────────────────────────────────────

    /**
     * Send a text reply to a WhatsApp contact.
     * Uses the WhatsApp URI scheme to pre-fill the message.
     * The user still needs to press Send (required by WhatsApp security policy).
     *
     * For fully hands-free, we use the Notification RemoteInput approach
     * (see sendViaNotificationAction below) which doesn't require opening WhatsApp.
     */
    fun sendViaWhatsAppUri(phoneNumber: String, message: String): Boolean {
        return try {
            // Clean phone number — remove non-digits except leading +
            val cleanPhone = phoneNumber.filter { it.isDigit() || it == '+' }
                .let { if (it.startsWith("+")) it else "+972${it.trimStart('0')}" }

            val uri = Uri.parse("https://wa.me/${cleanPhone.trimStart('+')}?text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "WhatsApp not installed or URI failed", e)
            false
        }
    }

    /**
     * Sends reply directly via the WhatsApp notification RemoteInput.
     * This is the FULLY HANDS-FREE approach — no need to open WhatsApp.
     * Requires the notification's pending intent from the last message.
     *
     * @param replyAction The RemoteInput action from the notification
     * @param message The text to send
     */
    fun sendViaNotificationAction(
        replyAction: android.app.Notification.Action?,
        message: String
    ): Boolean {
        if (replyAction == null) {
            Log.w(TAG, "No reply action available")
            return false
        }

        return try {
            val remoteInput = replyAction.remoteInputs?.firstOrNull() ?: return false
            val resultData = Intent().apply {
                val bundle = Bundle()
                bundle.putCharSequence(remoteInput.resultKey, message)
                android.app.RemoteInput.addResultsToIntent(replyAction.remoteInputs, this, bundle)
            }
            replyAction.actionIntent.send(context, 0, resultData)
            Log.i(TAG, "Reply sent via notification action: '$message'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send via notification action", e)
            false
        }
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}

// ─── Result sealed class ──────────────────────────────────────────────────────

sealed class SttResult {
    data class Success(val text: String) : SttResult()
    data class Error(val message: String) : SttResult()
}
