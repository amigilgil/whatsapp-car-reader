package com.whatsappcarreader.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.whatsappcarreader.R
import com.whatsappcarreader.util.PrefsManager

// ─── Settings Activity ────────────────────────────────────────────────────────

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = PrefsManager(this)

        supportActionBar?.apply {
            title = "הגדרות"
            setDisplayHomeAsUpEnabled(true)
        }

        setupApiKeySection()
        setupBluetoothSection()
        setupReadingSection()
        setupSwitches()
    }

    private fun setupApiKeySection() {
        val etApiKey = findViewById<EditText>(R.id.etElevenLabsKey)
        val btnSave = findViewById<Button>(R.id.btnSaveApiKey)

        etApiKey.setText(prefs.elevenLabsApiKey)
        btnSave.setOnClickListener {
            prefs.elevenLabsApiKey = etApiKey.text.toString().trim()
            Toast.makeText(this, "מפתח נשמר ✅", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBluetoothSection() {
        val btManager = getSystemService(BluetoothManager::class.java)
        val btAdapter: BluetoothAdapter? = btManager?.adapter

        val spinner = findViewById<Spinner>(R.id.spinnerBtDevices)
        val btnRefresh = findViewById<Button>(R.id.btnRefreshBt)

        fun loadDevices() {
            try {
                val paired = btAdapter?.bondedDevices?.toList() ?: emptyList()
                val names = listOf("כל מכשיר Bluetooth") + paired.map { it.name }
                val addresses = listOf("") + paired.map { it.address }

                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter

                // Select current
                val currentAddr = prefs.carBluetoothDeviceAddress
                val idx = addresses.indexOf(currentAddr).takeIf { it >= 0 } ?: 0
                spinner.setSelection(idx)

                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                        prefs.carBluetoothDeviceName = if (pos == 0) "" else names[pos]
                        prefs.carBluetoothDeviceAddress = addresses[pos]
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            } catch (e: SecurityException) {
                Toast.makeText(this, "אין הרשאת Bluetooth", Toast.LENGTH_SHORT).show()
            }
        }

        loadDevices()
        btnRefresh.setOnClickListener { loadDevices() }
    }

    private fun setupReadingSection() {
        val seekSpeed = findViewById<SeekBar>(R.id.seekReadingSpeed)
        val tvSpeed = findViewById<TextView>(R.id.tvSpeedValue)

        seekSpeed.progress = ((prefs.readingSpeed - 0.5f) * 10).toInt()
        tvSpeed.text = "×${prefs.readingSpeed}"

        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = 0.5f + progress * 0.1f
                prefs.readingSpeed = speed
                tvSpeed.text = "×${"%.1f".format(speed)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupSwitches() {
        bindSwitch(R.id.switchReadOnlyCar, prefs.readOnlyWhenCarConnected) {
            prefs.readOnlyWhenCarConnected = it
        }
        bindSwitch(R.id.switchAnnounceSender, prefs.announceSenderName) {
            prefs.announceSenderName = it
        }
        bindSwitch(R.id.switchAnnounceGroup, prefs.announceGroupName) {
            prefs.announceGroupName = it
        }
        bindSwitch(R.id.switchAutoStart, prefs.autoStartOnBoot) {
            prefs.autoStartOnBoot = it
        }
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChanged: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChanged(checked) }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

// ─── Contact Voice Activity ───────────────────────────────────────────────────

class ContactVoiceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_voice)

        val contactKey = intent.getStringExtra("contact_key") ?: run {
            finish()
            return
        }

        supportActionBar?.apply {
            title = "פרופיל קול"
            setDisplayHomeAsUpEnabled(true)
        }

        // TODO: Show voice sample progress, clone status, play preview
        val tvStatus = findViewById<TextView>(R.id.tvCloneStatus)
        tvStatus.text = "טוען פרטי קול..."
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
