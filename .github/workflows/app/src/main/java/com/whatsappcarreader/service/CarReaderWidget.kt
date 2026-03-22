package com.whatsappcarreader.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.whatsappcarreader.R
import com.whatsappcarreader.util.PrefsManager

/**
 * Home screen widget with:
 *  ┌─────────────────────────────────┐
 *  │ 🚗 WhatsApp Car Reader          │
 *  │ ⚪ לא מחובר לרכב               │
 *  │ [▶ הפעל]  [🎤 השב]  [⏭ דלג]  │
 *  └─────────────────────────────────┘
 *
 * Tapping [הפעל] → toggles the service
 * Tapping [השב]  → triggers voice reply
 * Tapping [דלג]  → skips current message
 *
 * The widget updates automatically whenever the service broadcasts a status change.
 */
class CarReaderWidget : AppWidgetProvider() {

    companion object {
        /**
         * Called by CarReaderForegroundService to refresh all widget instances.
         */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, CarReaderWidget::class.java)
            )
            if (ids.isNotEmpty()) {
                val intent = Intent(context, CarReaderWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        // Also react to service status broadcasts
        if (intent.action == CarReaderForegroundService.ACTION_STATUS) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, CarReaderWidget::class.java)
            )
            val prefs = PrefsManager(context)
            val isActive = intent.getBooleanExtra(CarReaderForegroundService.EXTRA_ACTIVE, false)
            val carConnected = intent.getBooleanExtra(CarReaderForegroundService.EXTRA_CAR_CONNECTED, false)
            val isPaused = intent.getBooleanExtra(CarReaderForegroundService.EXTRA_PAUSED, false)
            val isReplying = intent.getBooleanExtra(CarReaderForegroundService.EXTRA_REPLYING, false)
            val lastSender = intent.getStringExtra(CarReaderForegroundService.EXTRA_LAST_SENDER) ?: ""
            val lastText = intent.getStringExtra(CarReaderForegroundService.EXTRA_LAST_TEXT) ?: ""
            val queueSize = intent.getIntExtra(CarReaderForegroundService.EXTRA_QUEUE_SIZE, 0)

            for (id in ids) {
                updateWidgetWithState(
                    context, manager, id,
                    isActive, carConnected, isPaused, isReplying,
                    lastSender, lastText, queueSize
                )
            }
        }
    }

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int
    ) {
        updateWidgetWithState(
            context, manager, widgetId,
            isActive = false, carConnected = false, isPaused = false,
            isReplying = false, lastSender = "", lastText = "", queueSize = 0
        )
    }

    private fun updateWidgetWithState(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        isActive: Boolean,
        carConnected: Boolean,
        isPaused: Boolean,
        isReplying: Boolean,
        lastSender: String,
        lastText: String,
        queueSize: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_car_reader)

        // ── Status line ──────────────────────────────────────────────────────
        val statusText = when {
            isReplying -> "🎤 מאזין..."
            !isActive -> "כבוי"
            !carConnected -> "⚪ לא מחובר לרכב"
            isPaused -> "⏸ מושהה"
            queueSize > 0 -> "📨 $queueSize הודעות"
            lastSender.isNotEmpty() -> "$lastSender: ${lastText.take(20)}..."
            else -> "🟢 מאזין"
        }
        views.setTextViewText(R.id.tvWidgetStatus, statusText)

        // ── Play/Pause button ────────────────────────────────────────────────
        val playPauseText = when {
            !isActive -> "▶ הפעל"
            isPaused   -> "▶ המשך"
            else       -> "⏸ השהה"
        }
        views.setTextViewText(R.id.btnWidgetPlayPause, playPauseText)

        val playPauseIntent = if (!isActive) {
            // Start service
            Intent(context, CarReaderForegroundService::class.java).apply {
                action = CarReaderForegroundService.ACTION_START
            }.let { PendingIntent.getService(context, 10, it, PendingIntent.FLAG_IMMUTABLE) }
        } else {
            // Toggle pause
            Intent(context, CarReaderForegroundService::class.java).apply {
                action = CarReaderForegroundService.ACTION_PAUSE_RESUME
            }.let { PendingIntent.getService(context, 11, it, PendingIntent.FLAG_IMMUTABLE) }
        }
        views.setOnClickPendingIntent(R.id.btnWidgetPlayPause, playPauseIntent)

        // ── Reply button ─────────────────────────────────────────────────────
        val replyIntent = Intent(context, CarReaderForegroundService::class.java).apply {
            action = CarReaderForegroundService.ACTION_REPLY_VOICE
        }
        views.setOnClickPendingIntent(
            R.id.btnWidgetReply,
            PendingIntent.getService(context, 12, replyIntent, PendingIntent.FLAG_IMMUTABLE)
        )

        // Dim reply button if no last message
        views.setFloat(R.id.btnWidgetReply, "setAlpha", if (lastSender.isNotEmpty()) 1f else 0.4f)

        // ── Skip button ──────────────────────────────────────────────────────
        val skipIntent = Intent(context, CarReaderForegroundService::class.java).apply {
            action = CarReaderForegroundService.ACTION_SKIP
        }
        views.setOnClickPendingIntent(
            R.id.btnWidgetSkip,
            PendingIntent.getService(context, 13, skipIntent, PendingIntent.FLAG_IMMUTABLE)
        )

        // ── Re-read button ───────────────────────────────────────────────────
        val rereadIntent = Intent(context, CarReaderForegroundService::class.java).apply {
            action = CarReaderForegroundService.ACTION_REREAD
        }
        views.setOnClickPendingIntent(
            R.id.btnWidgetReread,
            PendingIntent.getService(context, 14, rereadIntent, PendingIntent.FLAG_IMMUTABLE)
        )

        // ── Whole widget tap → open app ───────────────────────────────────────
        val openAppIntent = Intent(context, com.whatsappcarreader.ui.MainActivity::class.java)
        views.setOnClickPendingIntent(
            R.id.tvWidgetTitle,
            PendingIntent.getActivity(context, 15, openAppIntent, PendingIntent.FLAG_IMMUTABLE)
        )

        // Active state background tint
        views.setInt(
            R.id.widgetRoot, "setBackgroundResource",
            if (isActive && !isPaused) R.drawable.widget_bg_active else R.drawable.widget_bg_idle
        )

        manager.updateAppWidget(widgetId, views)
    }
}
