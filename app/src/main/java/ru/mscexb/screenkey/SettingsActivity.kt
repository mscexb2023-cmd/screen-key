package ru.mscexb.screenkey

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

/**
 * Экран «Параметры»: пользователь редактирует пороги фронтального замка
 * и тексты шторки. Изменения применяются к работающей защите сразу.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etYaw: EditText
    private lateinit var etRoll: EditText
    private lateinit var etCenter: EditText
    private lateinit var etPass: EditText
    private lateinit var etFail: EditText
    private lateinit var etTitle: EditText
    private lateinit var etHintKey: EditText
    private lateinit var etHintInverted: EditText
    private lateinit var etHintFrontal: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences(KeyService.PREFS, MODE_PRIVATE)

        etYaw = findViewById(R.id.etYaw)
        etRoll = findViewById(R.id.etRoll)
        etCenter = findViewById(R.id.etCenter)
        etPass = findViewById(R.id.etPass)
        etFail = findViewById(R.id.etFail)
        etTitle = findViewById(R.id.etTitle)
        etHintKey = findViewById(R.id.etHintKey)
        etHintInverted = findViewById(R.id.etHintInverted)
        etHintFrontal = findViewById(R.id.etHintFrontal)

        load()

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnDefaults).setOnClickListener {
            resetToDefaults()
            load()
            KeyService.start(this)
            Toast.makeText(this, R.string.toast_defaults, Toast.LENGTH_SHORT).show()
        }
    }

    private fun load() {
        etYaw.setText(prefs.getInt(KeyService.KEY_YAW, KeyService.DEFAULT_YAW).toString())
        etRoll.setText(prefs.getInt(KeyService.KEY_ROLL, KeyService.DEFAULT_ROLL).toString())
        etCenter.setText(prefs.getInt(KeyService.KEY_CENTER, KeyService.DEFAULT_CENTER).toString())
        etPass.setText(prefs.getInt(KeyService.KEY_PASS, KeyService.DEFAULT_PASS).toString())
        etFail.setText(prefs.getInt(KeyService.KEY_FAIL, KeyService.DEFAULT_FAIL).toString())
        etTitle.setText(prefs.getString(KeyService.KEY_OV_TITLE, ""))
        etHintKey.setText(prefs.getString(KeyService.KEY_OV_HINT_KEY, ""))
        etHintInverted.setText(prefs.getString(KeyService.KEY_OV_HINT_INV, ""))
        etHintFrontal.setText(prefs.getString(KeyService.KEY_OV_HINT_FRONTAL, ""))
    }

    private fun save() {
        val yaw = etYaw.text.toString().trim().toIntOrNull()
        val roll = etRoll.text.toString().trim().toIntOrNull()
        val center = etCenter.text.toString().trim().toIntOrNull()
        val pass = etPass.text.toString().trim().toIntOrNull()
        val fail = etFail.text.toString().trim().toIntOrNull()

        val valid = yaw != null && roll != null && center != null && pass != null && fail != null &&
            yaw in 5..40 && roll in 5..45 && center in 20..100 && pass in 1..20 && fail in 1..20
        if (!valid) {
            Toast.makeText(this, R.string.toast_invalid, Toast.LENGTH_LONG).show()
            return
        }

        prefs.edit()
            .putInt(KeyService.KEY_YAW, yaw!!)
            .putInt(KeyService.KEY_ROLL, roll!!)
            .putInt(KeyService.KEY_CENTER, center!!)
            .putInt(KeyService.KEY_PASS, pass!!)
            .putInt(KeyService.KEY_FAIL, fail!!)
            .putString(KeyService.KEY_OV_TITLE, etTitle.text.toString().trim())
            .putString(KeyService.KEY_OV_HINT_KEY, etHintKey.text.toString().trim())
            .putString(KeyService.KEY_OV_HINT_INV, etHintInverted.text.toString().trim())
            .putString(KeyService.KEY_OV_HINT_FRONTAL, etHintFrontal.text.toString().trim())
            .apply()

        KeyService.start(this) // применить к работающей защите
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    private fun resetToDefaults() {
        prefs.edit()
            .putInt(KeyService.KEY_YAW, KeyService.DEFAULT_YAW)
            .putInt(KeyService.KEY_ROLL, KeyService.DEFAULT_ROLL)
            .putInt(KeyService.KEY_CENTER, KeyService.DEFAULT_CENTER)
            .putInt(KeyService.KEY_PASS, KeyService.DEFAULT_PASS)
            .putInt(KeyService.KEY_FAIL, KeyService.DEFAULT_FAIL)
            .putString(KeyService.KEY_OV_TITLE, "")
            .putString(KeyService.KEY_OV_HINT_KEY, "")
            .putString(KeyService.KEY_OV_HINT_INV, "")
            .putString(KeyService.KEY_OV_HINT_FRONTAL, "")
            .apply()
    }
}
