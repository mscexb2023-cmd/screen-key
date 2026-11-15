package ru.mscexb.screenkey

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(KeyService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)
        val frontalBuds = findViewById<Switch>(R.id.frontalBudsSwitch)
        val bootSwitch = findViewById<Switch>(R.id.bootSwitch)
        val grant = findViewById<Button>(R.id.grantOverlay)
        val start = findViewById<Button>(R.id.startBtn)
        val stop = findViewById<Button>(R.id.stopBtn)

        when (prefs.getInt(KeyService.KEY_MODE, KeyService.MODE_KEY)) {
            KeyService.MODE_INVERTED -> modeGroup.check(R.id.modeInverted)
            KeyService.MODE_FRONTAL -> modeGroup.check(R.id.modeFrontal)
            else -> modeGroup.check(R.id.modeKey)
        }
        frontalBuds.isChecked = prefs.getBoolean(KeyService.KEY_FRONTAL_NEED_EARBUDS, true)
        frontalBuds.visibility =
            if (prefs.getInt(KeyService.KEY_MODE, KeyService.MODE_KEY) == KeyService.MODE_FRONTAL)
                View.VISIBLE else View.GONE
        bootSwitch.isChecked = prefs.getBoolean(KeyService.KEY_AUTOSTART, true)

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.modeInverted -> KeyService.MODE_INVERTED
                R.id.modeFrontal -> KeyService.MODE_FRONTAL
                else -> KeyService.MODE_KEY
            }
            prefs.edit().putInt(KeyService.KEY_MODE, mode).apply()
            frontalBuds.visibility = if (mode == KeyService.MODE_FRONTAL) View.VISIBLE else View.GONE
            if (mode == KeyService.MODE_FRONTAL &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
            }
            // Служба подхватит новый режим: перезапуск не нужен, но пинганём её.
            KeyService.start(this)
            updateStatus(status)
        }

        frontalBuds.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KeyService.KEY_FRONTAL_NEED_EARBUDS, checked).apply()
            KeyService.start(this)
            updateStatus(status)
        }

        bootSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KeyService.KEY_AUTOSTART, checked).apply()
        }

        grant.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        start.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
            KeyService.start(this)
            Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
        }

        stop.setOnClickListener {
            stopService(Intent(this, KeyService::class.java))
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.testBtn).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            KeyService.start(this, KeyService.ACTION_TEST)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus(findViewById(R.id.status))
    }

    private fun updateStatus(status: TextView) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val connected = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type in intArrayOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET
            ) }
        val mode = prefs.getInt(KeyService.KEY_MODE, KeyService.MODE_KEY)
        val modeName = getString(
            when (mode) {
                KeyService.MODE_INVERTED -> R.string.mode_name_inverted
                KeyService.MODE_FRONTAL -> R.string.mode_name_frontal
                else -> R.string.mode_name_key
            }
        )
        val base = getString(
            R.string.status_template,
            modeName,
            getString(if (Settings.canDrawOverlays(this)) R.string.yes else R.string.no),
            getString(if (connected) R.string.yes else R.string.no)
        )
        status.text = if (mode == KeyService.MODE_FRONTAL) base + "\n" + getString(R.string.frontal_note) else base
        findViewById<View>(R.id.startBtn).visibility =
            if (Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE
    }
}
