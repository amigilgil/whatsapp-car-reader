package com.whatsappcarreader

import android.app.Application
import android.content.Intent
import com.whatsappcarreader.service.CarReaderForegroundService
import com.whatsappcarreader.util.PrefsManager

class WhatsAppCarReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = PrefsManager(this)
        if (prefs.serviceEnabled && prefs.autoStartOnBoot) {
            startForegroundService(
                Intent(this, CarReaderForegroundService::class.java).apply {
                    action = CarReaderForegroundService.ACTION_START
                }
            )
        }
    }
}
