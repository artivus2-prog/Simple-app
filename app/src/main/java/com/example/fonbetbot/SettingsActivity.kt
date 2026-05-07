// SettingsActivity.kt
package com.example.fonbetbot

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var etAllMinKef: EditText
    private lateinit var etType924Min: EditText
    private lateinit var etType924Max: EditText
    private lateinit var etType924MonitorStart: EditText
    private lateinit var etType924MonitorEnd: EditText
    private lateinit var etType927Min: EditText
    private lateinit var etType927Max: EditText
    private lateinit var etType927MonitorStart: EditText
    private lateinit var etType927MonitorEnd: EditText
    private lateinit var etType928Min: EditText
    private lateinit var etType928Max: EditText
    private lateinit var etType928MonitorStart: EditText
    private lateinit var etType928MonitorEnd: EditText
    private lateinit var etTimeout: EditText
    private lateinit var etMaxActiveExp: EditText
    private lateinit var etMaxMatches: EditText
    private lateinit var btnSave: Button
    private lateinit var btnReset: Button
    private lateinit var btnBack: Button

    companion object {
        const val PREFS_NAME = "bet_settings"
        const val KEY_ALL_MIN_KEF = "all_min_kef"
        const val KEY_TYPE_924_MIN = "type_924_min"
        const val KEY_TYPE_924_MAX = "type_924_max"
        const val KEY_TYPE_924_MONITOR_START = "type_924_monitor_start"
        const val KEY_TYPE_924_MONITOR_END = "type_924_monitor_end"
        const val KEY_TYPE_927_MIN = "type_927_min"
        const val KEY_TYPE_927_MAX = "type_927_max"
        const val KEY_TYPE_927_MONITOR_START = "type_927_monitor_start"
        const val KEY_TYPE_927_MONITOR_END = "type_927_monitor_end"
        const val KEY_TYPE_928_MIN = "type_928_min"
        const val KEY_TYPE_928_MAX = "type_928_max"
        const val KEY_TYPE_928_MONITOR_START = "type_928_monitor_start"
        const val KEY_TYPE_928_MONITOR_END = "type_928_monitor_end"
        const val KEY_TIMEOUT = "timeout"
        const val KEY_MAX_ACTIVE_EXP = "max_active_exp"
        const val KEY_MAX_MATCHES = "max_matches"

        const val DEFAULT_ALL_MIN_KEF = 1.67
        const val DEFAULT_TYPE_924_MIN = 1.15
        const val DEFAULT_TYPE_924_MAX = 1.50
        const val DEFAULT_TYPE_924_MONITOR_START = 80
        const val DEFAULT_TYPE_924_MONITOR_END = 100
        const val DEFAULT_TYPE_927_MIN = 1.15
        const val DEFAULT_TYPE_927_MAX = 1.50
        const val DEFAULT_TYPE_927_MONITOR_START = 1
        const val DEFAULT_TYPE_927_MONITOR_END = 45
        const val DEFAULT_TYPE_928_MIN = 1.15
        const val DEFAULT_TYPE_928_MAX = 1.50
        const val DEFAULT_TYPE_928_MONITOR_START = 1
        const val DEFAULT_TYPE_928_MONITOR_END = 45
        const val DEFAULT_TIMEOUT = 60
        const val DEFAULT_MAX_ACTIVE_EXP = 5
        const val DEFAULT_MAX_MATCHES = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        etAllMinKef = findViewById(R.id.et_all_min_kef)
        etType924Min = findViewById(R.id.et_type_924_min)
        etType924Max = findViewById(R.id.et_type_924_max)
        etType924MonitorStart = findViewById(R.id.et_type_924_monitor_start)
        etType924MonitorEnd = findViewById(R.id.et_type_924_monitor_end)
        etType927Min = findViewById(R.id.et_type_927_min)
        etType927Max = findViewById(R.id.et_type_927_max)
        etType927MonitorStart = findViewById(R.id.et_type_927_monitor_start)
        etType927MonitorEnd = findViewById(R.id.et_type_927_monitor_end)
        etType928Min = findViewById(R.id.et_type_928_min)
        etType928Max = findViewById(R.id.et_type_928_max)
        etType928MonitorStart = findViewById(R.id.et_type_928_monitor_start)
        etType928MonitorEnd = findViewById(R.id.et_type_928_monitor_end)
        etTimeout = findViewById(R.id.et_timeout)
        etMaxActiveExp = findViewById(R.id.et_max_active_exp)
        etMaxMatches = findViewById(R.id.et_max_matches)
        btnSave = findViewById(R.id.btn_save)
        btnReset = findViewById(R.id.btn_reset)
        btnBack = findViewById(R.id.btn_back)

        loadSettings()

        btnSave.setOnClickListener { saveSettings() }
        btnReset.setOnClickListener { resetToDefaults() }
        btnBack.setOnClickListener { finish() }
    }

    private fun loadSettings() {
        etAllMinKef.setText(prefs.getFloat(KEY_ALL_MIN_KEF, DEFAULT_ALL_MIN_KEF.toFloat()).toString())
        
        etType924Min.setText(prefs.getFloat(KEY_TYPE_924_MIN, DEFAULT_TYPE_924_MIN.toFloat()).toString())
        etType924Max.setText(prefs.getFloat(KEY_TYPE_924_MAX, DEFAULT_TYPE_924_MAX.toFloat()).toString())
        etType924MonitorStart.setText(prefs.getInt(KEY_TYPE_924_MONITOR_START, DEFAULT_TYPE_924_MONITOR_START).toString())
        etType924MonitorEnd.setText(prefs.getInt(KEY_TYPE_924_MONITOR_END, DEFAULT_TYPE_924_MONITOR_END).toString())
        
        etType927Min.setText(prefs.getFloat(KEY_TYPE_927_MIN, DEFAULT_TYPE_927_MIN.toFloat()).toString())
        etType927Max.setText(prefs.getFloat(KEY_TYPE_927_MAX, DEFAULT_TYPE_927_MAX.toFloat()).toString())
        etType927MonitorStart.setText(prefs.getInt(KEY_TYPE_927_MONITOR_START, DEFAULT_TYPE_927_MONITOR_START).toString())
        etType927MonitorEnd.setText(prefs.getInt(KEY_TYPE_927_MONITOR_END, DEFAULT_TYPE_927_MONITOR_END).toString())
        
        etType928Min.setText(prefs.getFloat(KEY_TYPE_928_MIN, DEFAULT_TYPE_928_MIN.toFloat()).toString())
        etType928Max.setText(prefs.getFloat(KEY_TYPE_928_MAX, DEFAULT_TYPE_928_MAX.toFloat()).toString())
        etType928MonitorStart.setText(prefs.getInt(KEY_TYPE_928_MONITOR_START, DEFAULT_TYPE_928_MONITOR_START).toString())
        etType928MonitorEnd.setText(prefs.getInt(KEY_TYPE_928_MONITOR_END, DEFAULT_TYPE_928_MONITOR_END).toString())
        
        etTimeout.setText(prefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT).toString())
        etMaxActiveExp.setText(prefs.getInt(KEY_MAX_ACTIVE_EXP, DEFAULT_MAX_ACTIVE_EXP).toString())
        etMaxMatches.setText(prefs.getInt(KEY_MAX_MATCHES, DEFAULT_MAX_MATCHES).toString())
    }

    private fun saveSettings() {
        try {
            prefs.edit()
                .putFloat(KEY_ALL_MIN_KEF, etAllMinKef.text.toString().toFloat())
                .putFloat(KEY_TYPE_924_MIN, etType924Min.text.toString().toFloat())
                .putFloat(KEY_TYPE_924_MAX, etType924Max.text.toString().toFloat())
                .putInt(KEY_TYPE_924_MONITOR_START, etType924MonitorStart.text.toString().toInt())
                .putInt(KEY_TYPE_924_MONITOR_END, etType924MonitorEnd.text.toString().toInt())
                .putFloat(KEY_TYPE_927_MIN, etType927Min.text.toString().toFloat())
                .putFloat(KEY_TYPE_927_MAX, etType927Max.text.toString().toFloat())
                .putInt(KEY_TYPE_927_MONITOR_START, etType927MonitorStart.text.toString().toInt())
                .putInt(KEY_TYPE_927_MONITOR_END, etType927MonitorEnd.text.toString().toInt())
                .putFloat(KEY_TYPE_928_MIN, etType928Min.text.toString().toFloat())
                .putFloat(KEY_TYPE_928_MAX, etType928Max.text.toString().toFloat())
                .putInt(KEY_TYPE_928_MONITOR_START, etType928MonitorStart.text.toString().toInt())
                .putInt(KEY_TYPE_928_MONITOR_END, etType928MonitorEnd.text.toString().toInt())
                .putInt(KEY_TIMEOUT, etTimeout.text.toString().toInt())
                .putInt(KEY_MAX_ACTIVE_EXP, etMaxActiveExp.text.toString().toInt())
                .putInt(KEY_MAX_MATCHES, etMaxMatches.text.toString().toInt())
                .apply()

            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: проверьте введённые значения", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetToDefaults() {
        etAllMinKef.setText(DEFAULT_ALL_MIN_KEF.toString())
        etType924Min.setText(DEFAULT_TYPE_924_MIN.toString())
        etType924Max.setText(DEFAULT_TYPE_924_MAX.toString())
        etType924MonitorStart.setText(DEFAULT_TYPE_924_MONITOR_START.toString())
        etType924MonitorEnd.setText(DEFAULT_TYPE_924_MONITOR_END.toString())
        etType927Min.setText(DEFAULT_TYPE_927_MIN.toString())
        etType927Max.setText(DEFAULT_TYPE_927_MAX.toString())
        etType927MonitorStart.setText(DEFAULT_TYPE_927_MONITOR_START.toString())
        etType927MonitorEnd.setText(DEFAULT_TYPE_927_MONITOR_END.toString())
        etType928Min.setText(DEFAULT_TYPE_928_MIN.toString())
        etType928Max.setText(DEFAULT_TYPE_928_MAX.toString())
        etType928MonitorStart.setText(DEFAULT_TYPE_928_MONITOR_START.toString())
        etType928MonitorEnd.setText(DEFAULT_TYPE_928_MONITOR_END.toString())
        etTimeout.setText(DEFAULT_TIMEOUT.toString())
        etMaxActiveExp.setText(DEFAULT_MAX_ACTIVE_EXP.toString())
        etMaxMatches.setText(DEFAULT_MAX_MATCHES.toString())
        Toast.makeText(this, "Сброшено на значения по умолчанию", Toast.LENGTH_SHORT).show()
    }
}