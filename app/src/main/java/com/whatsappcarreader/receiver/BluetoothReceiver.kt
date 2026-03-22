package com.whatsappcarreader.receiver

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.whatsappcarreader.service.CarReaderForegroundService
import com.whatsappcarreader.util.PrefsManager

/**
 * Detects when the phone connects/disconnects from a Bluetooth A2DP device
 * (i.e., the car's audio system).
 *
 * If the connected device matches the configured car Bluetooth name/address,
 * we start/stop the reading service accordingly.
 */
class BluetoothReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BluetoothReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PrefsManager(context)

        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val deviceName = try { device?.name } catch (e: SecurityException) { null }
        val deviceAddress = device?.address

        Log.d(TAG, "BT event: ${intent.action} | device: $deviceName ($deviceAddress)")

        when (intent.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED
                )
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (isCarDevice(prefs, deviceName, deviceAddress)) {
                            Log.i(TAG, "Car connected: $deviceName")
                            notifyServiceCarConnected(context, deviceName ?: "הרכב")
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (isCarDevice(prefs, deviceName, deviceAddress)) {
                            Log.i(TAG, "Car disconnected: $deviceName")
                            notifyServiceCarDisconnected(context)
                        }
                    }
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (isCarDevice(prefs, deviceName, deviceAddress)) {
                    Log.i(TAG, "ACL connected to car: $deviceName")
                    // A2DP event will follow; this is just a heads-up
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (isCarDevice(prefs, deviceName, deviceAddress)) {
                    notifyServiceCarDisconnected(context)
                }
            }
        }
    }

    private fun isCarDevice(prefs: PrefsManager, name: String?, address: String?): Boolean {
        // If user hasn't configured a specific car device, accept ANY A2DP connection
        val configuredName = prefs.carBluetoothDeviceName
        val configuredAddr = prefs.carBluetoothDeviceAddress

        if (configuredName.isBlank() && configuredAddr.isBlank()) {
            return true  // Accept any BT device
        }
        return name?.contains(configuredName, ignoreCase = true) == true
                || address == configuredAddr
    }

    private fun notifyServiceCarConnected(context: Context, deviceName: String) {
        val serviceIntent = Intent(context, CarReaderForegroundService::class.java).apply {
            action = CarReaderForegroundService.ACTION_START
            putExtra("car_device_name", deviceName)
        }
        context.startForegroundService(serviceIntent)
    }

    private fun notifyServiceCarDisconnected(context: Context) {
        // Don't stop the service, just update the state
        val intent = Intent("com.whatsappcarreader.CAR_DISCONNECTED").apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}
