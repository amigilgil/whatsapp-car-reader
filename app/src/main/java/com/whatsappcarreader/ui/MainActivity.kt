package com.whatsappcarreader.ui

import android.content.*
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.whatsappcarreader.R
import com.whatsappcarreader.databinding.ActivityMainBinding
import com.whatsappcarreader.manager.AppDatabase
import com.whatsappcarreader.service.CarReaderForegroundService
import com.whatsappcarreader.util.PrefsManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    private lateinit var db: AppDatabase
    private lateinit var contactsAdapter: ContactsAdapter

    private var isServiceActive = false
    private var isCarConnected = false

    // ─── Status broadcast receiver ────────────────────────────────────────────

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CarReaderForegroundService.ACTION_STATUS_UPDATE) return
            isServiceActive = intent.getBooleanExtra(CarReaderForegroundService.EXTRA_IS_ACTIVE, false)
            isCarConnected = intent.getBooleanExtra("is_car_connected", false)
            val lastMsg = intent.getStringExtra(CarReaderForegroundService.EXTRA_LAST_MESSAGE) ?: ""
            val isPaused = intent.getBooleanExtra("is_paused", false)
            updateDashboard(isServiceActive, isCarConnected, isPaused, lastMsg)
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)
        db = AppDatabase.getInstance(this)

        setupToolbar()
        setupDashboard()
        setupContactsList()
        checkPermissionsOnFirstRun()

        registerReceiver(
            statusReceiver,
            IntentFilter(CarReaderForegroundService.ACTION_STATUS_UPDATE),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupDashboard() {
        // Main toggle
        binding.switchService.setOnCheckedChangeListener { _, checked ->
            prefs.serviceEnabled = checked
            if (checked) startReaderService() else stopReaderService()
        }
        binding.switchService.isChecked = prefs.serviceEnabled

        // Car Bluetooth status card
        binding.cardCarBt.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra("scroll_to", "bluetooth")
            })
        }

        // ElevenLabs setup card
        binding.cardElevenLabs.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra("scroll_to", "api_key")
            })
        }

        // Accessibility permission card
        binding.cardAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // Pause/Resume button
        binding.btnPauseResume.setOnClickListener {
            startService(Intent(this, CarReaderForegroundService::class.java).apply {
                action = CarReaderForegroundService.ACTION_PAUSE_RESUME
            })
        }
    }

    private fun setupContactsList() {
        contactsAdapter = ContactsAdapter { contact ->
            startActivity(Intent(this, ContactVoiceActivity::class.java).apply {
                putExtra("contact_key", contact.phoneOrName)
            })
        }
        binding.rvContacts.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactsAdapter
        }

        db.contactDao().getAllContacts().observe(this) { contacts ->
            contactsAdapter.submitList(contacts)
            binding.tvNoContacts.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            binding.tvContactsCount.text = "${contacts.size} אנשי קשר"
        }
    }

    // ─── UI Updates ───────────────────────────────────────────────────────────

    private fun updateDashboard(
        active: Boolean,
        carConnected: Boolean,
        paused: Boolean,
        lastMsg: String
    ) {
        binding.switchService.isChecked = active

        // Car connection indicator
        binding.tvCarStatus.text = if (carConnected) "🟢 מחובר לרכב" else "⚪ לא מחובר לרכב"
        binding.tvCarStatus.setTextColor(
            getColor(if (carConnected) R.color.wa_green else R.color.text_muted)
        )

        // Last message
        if (lastMsg.isNotEmpty()) {
            binding.tvLastMessage.text = lastMsg
            binding.tvLastMessage.visibility = View.VISIBLE
        }

        // Play/pause button
        binding.btnPauseResume.text = if (paused) "▶ המשך הקראה" else "⏸ השהה"
        binding.btnPauseResume.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun updatePermissionStatus() {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasNotificationAccess = isNotificationListenerEnabled()
        val hasApiKey = prefs.elevenLabsApiKey.isNotBlank()

        binding.cardAccessibility.apply {
            if (hasAccessibility) {
                setCardBackgroundColor(getColor(R.color.surface2))
                binding.tvAccessibilityStatus.text = "✅ מופעל"
            } else {
                setCardBackgroundColor(getColor(R.color.warning_bg))
                binding.tvAccessibilityStatus.text = "⚠️ נדרש הפעלה"
            }
        }

        binding.cardElevenLabs.apply {
            if (hasApiKey) {
                binding.tvElevenLabsStatus.text = "✅ מוגדר"
            } else {
                binding.tvElevenLabsStatus.text = "הוסף מפתח API לשיבוט קול"
            }
        }

        // Show setup banner if something is missing
        binding.bannerSetup.visibility = if (!hasAccessibility || !hasApiKey) View.VISIBLE else View.GONE
    }

    // ─── Service Control ──────────────────────────────────────────────────────

    private fun startReaderService() {
        startForegroundService(
            Intent(this, CarReaderForegroundService::class.java).apply {
                action = CarReaderForegroundService.ACTION_START
            }
        )
    }

    private fun stopReaderService() {
        startService(
            Intent(this, CarReaderForegroundService::class.java).apply {
                action = CarReaderForegroundService.ACTION_STOP
            }
        )
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun checkPermissionsOnFirstRun() {
        if (!prefs.hasSeenOnboarding) {
            prefs.hasSeenOnboarding = true
            showOnboardingDialog()
        }
    }

    private fun showOnboardingDialog() {
        AlertDialog.Builder(this)
            .setTitle("ברוך הבא ל-WhatsApp Car Reader 🚗")
            .setMessage(
                "כדי שהאפליקציה תעבוד, יש לאשר:\n\n" +
                "1️⃣ שירות נגישות — לקריאת הודעות מוואטסאפ\n" +
                "2️⃣ גישה להתראות — לגיבוי\n" +
                "3️⃣ מפתח ElevenLabs — לשיבוט קולות (אופציונלי)\n\n" +
                "נעשה זאת שלב אחר שלב."
            )
            .setPositiveButton("בואו נתחיל!") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("אחר כך", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Snackbar.make(
            binding.root,
            "חפש 'WhatsApp Car Reader' והפעל",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains("com.whatsappcarreader/.service.WhatsAppAccessibilityService")
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return listeners.contains("com.whatsappcarreader")
    }
}
