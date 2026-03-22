package com.whatsappcarreader.manager

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale

/**
 * FREE TTS Manager using Android's built-in TextToSpeech engine.
 *
 * Strategy for unique voices per contact (all FREE, no API needed):
 *  - Android TTS supports multiple Voice objects (from Google TTS engine)
 *  - Each has different pitch, speed, and sometimes distinct voice personas
 *  - We assign a stable voice to each contact based on hash of their name
 *  - Additionally we vary pitch/speed slightly per contact for distinction
 *
 * Voice pool from Google TTS (he-IL + en-US fallback):
 *   he-IL-language   — Hebrew variants (if installed)
 *   en-US-language   — English fallback voices
 *   Each contact gets: a specific voice + unique pitch (0.8–1.2) + rate (0.9–1.1)
 *
 * For voice cloning WITHOUT a paid API, we use:
 *   - Pitch variation: lower = "deeper/masculine", higher = "lighter/feminine"
 *   - The first letter + length of the name determines the pitch bucket
 *   - Result: each person sounds noticeably different from others
 */
class FreeTtsManager(private val context: Context) {

    companion object {
        private const val TAG = "FreeTtsManager"
        private const val LANG_HE = "he"
        private const val LANG_EN = "en"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var availableVoices: List<Voice> = emptyList()
    private var hebrewVoices: List<Voice> = emptyList()

    // Per-contact voice assignment: contactKey → VoiceProfile
    private val voiceProfiles = mutableMapOf<String, ContactVoiceProfile>()

    private var onReadyCallback: (() -> Unit)? = null

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                loadAvailableVoices()
                Log.i(TAG, "TTS ready. Voices: ${availableVoices.size}, Hebrew: ${hebrewVoices.size}")
                onReadyCallback?.invoke()
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun onReady(callback: () -> Unit) {
        if (isReady) callback() else onReadyCallback = callback
    }

    // ─── Voice Discovery ──────────────────────────────────────────────────────

    private fun loadAvailableVoices() {
        availableVoices = tts?.voices?.toList() ?: emptyList()

        // Hebrew voices (best quality for Israeli users)
        hebrewVoices = availableVoices.filter { voice ->
            voice.locale.language == LANG_HE && !voice.isNetworkConnectionRequired
        }.sortedBy { it.quality }.reversed()  // best quality first

        // Fallback to English if no Hebrew
        if (hebrewVoices.isEmpty()) {
            hebrewVoices = availableVoices.filter { voice ->
                voice.locale.language == LANG_EN && !voice.isNetworkConnectionRequired
            }.sortedBy { it.quality }.reversed()
        }

        Log.d(TAG, "Hebrew voices: ${hebrewVoices.map { it.name }}")
    }

    // ─── Contact Voice Assignment ─────────────────────────────────────────────

    /**
     * Gets or creates a stable voice profile for a contact.
     * The profile is determined by the contact's key (hash) — always the same for the same person.
     */
    fun getOrCreateProfile(contactKey: String, displayName: String): ContactVoiceProfile {
        voiceProfiles[contactKey]?.let { return it }

        val hash = Math.abs(contactKey.hashCode())

        // Pick a voice from pool (or cycle through available)
        val voice = if (hebrewVoices.isNotEmpty()) {
            hebrewVoices[hash % hebrewVoices.size]
        } else {
            null
        }

        // Vary pitch and speed based on hash for distinctiveness
        // Pitch: 0.75 (deep) to 1.35 (high) — 12 distinct buckets
        val pitchBucket = hash % 12
        val pitch = 0.75f + pitchBucket * 0.05f

        // Speed: 0.88 to 1.12
        val rateBucket = (hash / 12) % 8
        val rate = 0.88f + rateBucket * 0.03f

        val profile = ContactVoiceProfile(
            contactKey = contactKey,
            displayName = displayName,
            voice = voice,
            pitch = pitch,
            speechRate = rate,
            colorIndex = hash % 9
        )

        voiceProfiles[contactKey] = profile
        Log.d(TAG, "Created voice profile for $displayName: pitch=${"%.2f".format(pitch)}, rate=${"%.2f".format(rate)}, voice=${voice?.name ?: "default"}")
        return profile
    }

    // ─── Synthesis ────────────────────────────────────────────────────────────

    /**
     * Speaks text in the voice assigned to a specific contact.
     * Suspends until speech is complete.
     */
    suspend fun speakAsContact(
        text: String,
        contactKey: String,
        displayName: String
    ): Boolean {
        if (!isReady || tts == null) {
            Log.w(TAG, "TTS not ready")
            return false
        }

        val profile = getOrCreateProfile(contactKey, displayName)
        return speakWithProfile(text, profile)
    }

    /**
     * Speaks an announcement (sender name, etc.) in a neutral voice.
     */
    suspend fun speakAnnouncement(text: String): Boolean {
        if (!isReady || tts == null) return false
        return speakWithPitch(text, pitch = 1.0f, rate = 1.0f)
    }

    private suspend fun speakWithProfile(text: String, profile: ContactVoiceProfile): Boolean {
        return suspendCancellableCoroutine { cont ->
            val utteranceId = "utt_${System.currentTimeMillis()}"

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(true) {}
                }
                override fun onError(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(false) {}
                }
            })

            // Apply voice settings
            profile.voice?.let { tts?.voice = it }
                ?: tts?.setLanguage(Locale("he", "IL"))
                    .let { if (it == TextToSpeech.LANG_MISSING_DATA || it == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.ENGLISH)
                    }}

            tts?.setPitch(profile.pitch)
            tts?.setSpeechRate(profile.speechRate)

            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                if (cont.isActive) cont.resume(false) {}
            }

            cont.invokeOnCancellation { tts?.stop() }
        }
    }

    private suspend fun speakWithPitch(text: String, pitch: Float, rate: Float): Boolean {
        return speakWithProfile(text, ContactVoiceProfile(
            contactKey = "__announcement__",
            displayName = "System",
            voice = null,
            pitch = pitch,
            speechRate = rate,
            colorIndex = 0
        ))
    }

    // ─── Controls ─────────────────────────────────────────────────────────────

    fun stop() { tts?.stop() }

    fun getVoiceDescription(contactKey: String, displayName: String): String {
        val profile = getOrCreateProfile(contactKey, displayName)
        return buildString {
            append(when {
                profile.pitch < 0.9f -> "קול עמוק"
                profile.pitch > 1.15f -> "קול גבוה"
                else -> "קול בינוני"
            })
            append(" • ")
            append(when {
                profile.speechRate < 0.95f -> "קצב איטי"
                profile.speechRate > 1.05f -> "קצב מהיר"
                else -> "קצב רגיל"
            })
            if (profile.voice != null) append(" • עברית")
        }
    }

    fun isAvailable() = isReady

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

// ─── Data class ───────────────────────────────────────────────────────────────

data class ContactVoiceProfile(
    val contactKey: String,
    val displayName: String,
    val voice: Voice?,           // null = use language default
    val pitch: Float,            // 0.75–1.35
    val speechRate: Float,       // 0.88–1.12
    val colorIndex: Int          // for UI avatar color
)
