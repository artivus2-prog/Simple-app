// SettingsActivity.kt
package com.example.fonbetbot

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var prefs: SharedPreferences
    
    private lateinit var betAmountEditText: EditText
    private lateinit var checkIntervalEditText: EditText
    private lateinit var autoStartSwitch: SwitchCompat
    private lateinit var notificationsSwitch: SwitchCompat
    private lateinit var soundSwitch: SwitchCompat
    private lateinit var dbSizeText: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        dbHelper = DatabaseHelper(this)
        prefs = getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        
        initViews()
        loadSettings()
        updateDbSize()
        
        setupClickListeners()
    }
    
    private fun initViews() {
        betAmountEditText = findViewById(R.id.betAmountEditText)
        checkIntervalEditText = findViewById(R.id.checkIntervalEditText)
        autoStartSwitch = findViewById(R.id.autoStartSwitch)
        notificationsSwitch = findViewById(R.id.notificationsSwitch)
        soundSwitch = findViewById(R.id.soundSwitch)
        dbSizeText = findViewById(R.id.dbSizeText)
    }
    
    private fun loadSettings() {
        betAmountEditText.setText(prefs.getString("bet_amount", "100"))
        checkIntervalEditText.setText(prefs.getString("check_interval", "5"))
        autoStartSwitch.isChecked = prefs.getBoolean("auto_start", true)
        notificationsSwitch.isChecked = prefs.getBoolean("notifications", true)
        soundSwitch.isChecked = prefs.getBoolean("sound", false)
    }
    
    private fun saveSettings() {
        prefs.edit()
            .putString("bet_amount", betAmountEditText.text.toString())
            .putString("check_interval", checkIntervalEditText.text.toString())
            .putBoolean("auto_start", autoStartSwitch.isChecked)
            .putBoolean("notifications", notificationsSwitch.isChecked)
            .putBoolean("sound", soundSwitch.isChecked)
            .apply()
        
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateDbSize() {
        val size = dbHelper.getDatabaseSize(this)
        dbSizeText.text = size
    }
    
    private fun setupClickListeners() {
        findViewById<Button>(R.id.saveButton).setOnClickListener {
            saveSettings()
            finish()
        }
        
        findViewById<Button>(R.id.cancelButton).setOnClickListener {
            finish()
        }
        
        findViewById<Button>(R.id.dbStatsButton).setOnClickListener {
            showDatabaseStats()
        }
        
        findViewById<Button>(R.id.cleanupLogsButton).setOnClickListener {
            cleanupOldLogs()
        }
        
        findViewById<Button>(R.id.clearAllButton).setOnClickListener {
            showClearAllDialog()
        }
    }
    
    private fun showDatabaseStats() {
        val stats = dbHelper.getTableStats()
        
        val message = buildString {
            append("Записей в таблицах:\n\n")
            stats.forEach { (table, count) ->
                append("• ${table.replace("_", " ")}: $count\n")
            }
            append("\nОбщий размер: ${dbHelper.getDatabaseSize(this@SettingsActivity)}")
        }
        
        AlertDialog.Builder(this)
            .setTitle("📊 Статистика базы данных")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun cleanupOldLogs() {
        val deleted = dbHelper.cleanupOldLogs(30)
        Toast.makeText(this, "Удалено $deleted старых записей", Toast.LENGTH_SHORT).show()
        updateDbSize()
    }
    
    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Очистка данных")
            .setMessage("""
                Вы уверены, что хотите удалить ВСЕ данные?
                
                Будут удалены:
                • Данные авторизации
                • История баланса
                • Логи работы
                • Статистика
                
                Это действие нельзя отменить!
            """.trimIndent())
            .setPositiveButton("Да, удалить всё") { _, _ ->
                clearAllData()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    private fun clearAllData() {
        val progressDialog = ProgressDialog(this)
        progressDialog.setMessage("Очистка данных...")
        progressDialog.setCancelable(false)
        progressDialog.show()
        
        CoroutineScope(Dispatchers.IO).launch {
            val success = dbHelper.clearAllData(this@SettingsActivity)
            
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                
                val message = if (success) {
                    // Также очищаем настройки
                    prefs.edit().clear().apply()
                    loadSettings()
                    updateDbSize()
                    "Все данные успешно удалены"
                } else {
                    "Ошибка при удалении данных"
                }
                
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(if (success) "✅ Готово" else "❌ Ошибка")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ ->
                        if (success) {
                            // Возвращаемся на главный экран
                            finish()
                        }
                    }
                    .show()
            }
        }
    }
}

// Простой ProgressDialog
class ProgressDialog(context: Context) : AlertDialog(context) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ProgressBar(context).apply {
            isIndeterminate = true
            setPadding(32, 32, 32, 32)
        })
        window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
}