package com.whatsappcarreader.model

import androidx.room.*

// ─── Contact ──────────────────────────────────────────────────────────────────

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val phoneOrName: String,   // "+972501234567" or display name
    val displayName: String,
    val avatarUri: String? = null,

    // ElevenLabs voice clone ID (null until enough audio samples collected)
    val elevenLabsVoiceId: String? = null,

    // Google TTS fallback voice name (e.g. "he-IL-Wavenet-A")
    val googleTtsVoice: String = "he-IL-Standard-A",

    // How many voice message seconds we've collected for this contact
    val voiceSamplesSeconds: Float = 0f,

    // Whether user has approved cloning for this contact
    val cloningEnabled: Boolean = true,

    // Color for UI display
    val avatarColor: Int = 0xFF25D366.toInt(),

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ─── Voice Sample (raw audio collected from voice messages) ──────────────────

@Entity(tableName = "voice_samples")
data class VoiceSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactKey: String,          // FK → Contact.phoneOrName
    val filePath: String,            // local .ogg/.mp4 file path
    val durationSeconds: Float,
    val uploadedToElevenLabs: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

// ─── Queued Message ──────────────────────────────────────────────────────────

data class IncomingMessage(
    val senderKey: String,           // phone or display name
    val senderDisplayName: String,
    val text: String,
    val chatName: String,            // individual or group name
    val isGroup: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// ─── ElevenLabs API models ────────────────────────────────────────────────────

data class ElevenLabsVoice(
    val voice_id: String,
    val name: String,
    val category: String? = null
)

data class ElevenLabsVoicesResponse(
    val voices: List<ElevenLabsVoice>
)

data class ElevenLabsTtsRequest(
    val text: String,
    val model_id: String = "eleven_multilingual_v2",
    val voice_settings: VoiceSettings = VoiceSettings()
)

data class VoiceSettings(
    val stability: Float = 0.5f,
    val similarity_boost: Float = 0.75f,
    val style: Float = 0.0f,
    val use_speaker_boost: Boolean = true
)

// ─── App Settings ─────────────────────────────────────────────────────────────

data class AppSettings(
    val elevenLabsApiKey: String = "",
    val googleTtsApiKey: String = "",
    val carBluetoothDeviceName: String = "",
    val carBluetoothDeviceAddress: String = "",
    val autoReadOnBluetooth: Boolean = true,
    val announceGroupName: Boolean = true,
    val announceSenderName: Boolean = true,
    val readOnlyWhenCarConnected: Boolean = true,
    val voiceMessageMinSeconds: Float = 5f,  // min audio to start cloning
    val preferElevenLabs: Boolean = true,
    val readingSpeed: Float = 1.0f,
    val pauseBetweenMessages: Long = 800     // ms
)
