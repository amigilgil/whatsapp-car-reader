package com.whatsappcarreader.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("car_reader_prefs", Context.MODE_PRIVATE)

    var elevenLabsApiKey: String
        get() = prefs.getString("elevenlabs_api_key", "") ?: ""
        set(v) = prefs.edit { putString("elevenlabs_api_key", v) }

    var googleTtsApiKey: String
        get() = prefs.getString("google_tts_api_key", "") ?: ""
        set(v) = prefs.edit { putString("google_tts_api_key", v) }

    var carBluetoothDeviceName: String
        get() = prefs.getString("car_bt_name", "") ?: ""
        set(v) = prefs.edit { putString("car_bt_name", v) }

    var carBluetoothDeviceAddress: String
        get() = prefs.getString("car_bt_address", "") ?: ""
        set(v) = prefs.edit { putString("car_bt_address", v) }

    var readOnlyWhenCarConnected: Boolean
        get() = prefs.getBoolean("read_only_car", true)
        set(v) = prefs.edit { putBoolean("read_only_car", v) }

    var announceGroupName: Boolean
        get() = prefs.getBoolean("announce_group", true)
        set(v) = prefs.edit { putBoolean("announce_group", v) }

    var announceSenderName: Boolean
        get() = prefs.getBoolean("announce_sender", true)
        set(v) = prefs.edit { putBoolean("announce_sender", v) }

    var pauseBetweenMessages: Long
        get() = prefs.getLong("pause_between", 800L)
        set(v) = prefs.edit { putLong("pause_between", v) }

    var readingSpeed: Float
        get() = prefs.getFloat("reading_speed", 1.0f)
        set(v) = prefs.edit { putFloat("reading_speed", v) }

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean("auto_start_boot", true)
        set(v) = prefs.edit { putBoolean("auto_start_boot", v) }

    var serviceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", true)
        set(v) = prefs.edit { putBoolean("service_enabled", v) }

    var hasSeenOnboarding: Boolean
        get() = prefs.getBoolean("seen_onboarding", false)
        set(v) = prefs.edit { putBoolean("seen_onboarding", v) }
}

// ─── BootReceiver ─────────────────────────────────────────────────────────────

package com.whatsappcarreader.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.whatsappcarreader.service.CarReaderForegroundService
import com.whatsappcarreader.util.PrefsManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = PrefsManager(context)
        if (prefs.autoStartOnBoot && prefs.serviceEnabled) {
            val serviceIntent = Intent(context, CarReaderForegroundService::class.java).apply {
                action = CarReaderForegroundService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
