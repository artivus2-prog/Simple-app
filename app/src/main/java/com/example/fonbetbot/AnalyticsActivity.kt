// AnalyticsActivity.kt
package com.example.fonbetbot

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnalyticsActivity : AppCompatActivity() {
    
    private lateinit var tvAnalytics: TextView
    private lateinit var btnImportExp: Button
    private lateinit var btnImportData: Button
    private lateinit var btnDashboard: Button
    private lateinit var btnClearData: Button
    private lateinit var btnFetchBets: Button
    private lateinit var tvTicker: TextView
    private lateinit var tvStatusLight: View
    private lateinit var database: AppDatabase
    private lateinit var excelReader: ExcelReader
    private lateinit var dataManager: DataManager
    
    private var isAutoFetching = false
    private val handler = Handler(Looper.getMainLooper())
    private val autoFetchInterval = 30000L
    
    companion object {
        private const val PICK_EXP_FILE = 1
        private const val PICK_DATA_FILE = 2
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analytics)
        
        tvAnalytics = findViewById(R.id.tv_analytics)
        btnImportExp = findViewById(R.id.btn_import_exp)
        btnImportData = findViewById(R.id.btn_import_data)
        btnDashboard = findViewById(R.id.btn_dashboard)
        btnClearData = findViewById(R.id.btn_clear_data)
        btnFetchBets = findViewById(R.id.btn_fetch_bets)
        tvTicker = findViewById(R.id.tv_ticker)
        tvStatusLight = findViewById(R.id.tv_status_light)
        
        database = AppDatabase.getDatabase(this)
        excelReader = ExcelReader(this)
        dataManager = DataManager(database)
        
        btnImportExp.setOnClickListener { openFilePicker(PICK_EXP_FILE) }
        btnImportData.setOnClickListener { openFilePicker(PICK_DATA_FILE) }
        
        btnDashboard.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        
        btnClearData.setOnClickListener { clearAllData() }
        
        btnFetchBets.setOnClickListener {
            if (isAutoFetching) {
                stopAutoFetch()
            } else {
                fetchBetsFromServer()
            }
        }
        
        btnFetchBets.setOnLongClickListener {
            toggleAutoFetch()
            true
        }
        
        loadInfo()
    }
    
    private fun openFilePicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        startActivityForResult(intent, requestCode)
    }
    
    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (resultCode == Activity.RESULT_OK && data?.data != null) {
            val uri = data.data!!
            
            lifecycleScope.launch {
                try {
                    tvAnalytics.text = "Загрузка файла..."
                    
                    withContext(Dispatchers.IO) {
                        when (requestCode) {
                            PICK_EXP_FILE -> {
                                val expData = excelReader.readExpData(uri)
                                database.expDao().deleteAll()
                                database.expDao().insertAll(expData)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@AnalyticsActivity, "Загружено ${expData.size} записей exp", Toast.LENGTH_SHORT).show()
                                }
                            }
                            PICK_DATA_FILE -> {
                                val dataList = excelReader.readDataData(uri)
                                database.dataDao().deleteAll()
                                database.dataDao().insertAll(dataList)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@AnalyticsActivity, "Загружено ${dataList.size} записей data", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    
                    loadInfo()
                    
                } catch (e: Exception) {
                    Toast.makeText(this@AnalyticsActivity, "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show()
                    tvAnalytics.text = "Ошибка загрузки:\n${e.message}"
                }
            }
        }
    }
    
    private fun loadInfo() {
        lifecycleScope.launch {
            try {
                val expCount = withContext(Dispatchers.IO) { database.expDao().getAllExp().size }
                val dataCount = withContext(Dispatchers.IO) { database.dataDao().getAllData().size }
                
                tvAnalytics.text = buildString {
                    appendLine("Статус базы данных:")
                    appendLine("Записей в таблице exp: $expCount")
                    appendLine("Записей в таблице data: $dataCount")
                    appendLine()
                    if (expCount > 0 && dataCount > 0) {
                        appendLine("Данные загружены успешно.")
                        appendLine("Нажмите \"Дашборд\" для просмотра аналитики.")
                        appendLine()
                        appendLine("Используйте кнопку \"Загрузить с сервера\"")
                        appendLine("для получения новых данных.")
                        appendLine("Долгое нажатие - автообновление каждые 30 сек.")
                    } else {
                        appendLine("Импортируйте файлы Excel:")
                        appendLine("1. processed_exp10.xlsx (кнопка \"Импорт Exp\")")
                        appendLine("2. processed_data10.xlsx (кнопка \"Импорт Data\")")
                    }
                }
                
            } catch (e: Exception) {
                tvAnalytics.text = "Ошибка загрузки информации:\n${e.message}"
            }
        }
    }
    
    private fun clearAllData() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.expDao().deleteAll()
                    database.dataDao().deleteAll()
                }
                
                tvAnalytics.text = "Данные очищены. Импортируйте файлы заново."
                Toast.makeText(this@AnalyticsActivity, "Все данные удалены", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Toast.makeText(this@AnalyticsActivity, "Ошибка очистки: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // ============ МЕТОДЫ ДЛЯ РАБОТЫ С API ============
    
    private fun fetchBetsFromServer() {
        lifecycleScope.launch {
            try {
                updateTicker("Загрузка данных с сервера...", "#FFC107")
                btnFetchBets.isEnabled = false
                btnFetchBets.text = "Загрузка..."
                
                val bets = withContext(Dispatchers.IO) {
                    NetworkModule.apiService.getBets()
                }
                
                if (bets.isNotEmpty()) {
                    val result = withContext(Dispatchers.IO) {
                        dataManager.importBets(bets)
                    }
                    
                    val message = when {
                        result.newExpressCount > 0 -> {
                            updateTicker("✅ Новых экспрессов: ${result.newExpressCount}, матчей: ${result.newMatchCount}" +
                                    if (result.skippedCount > 0) ", пропущено: ${result.skippedCount}" else "", "#4CAF50")
                        }
                        result.skippedCount > 0 -> {
                            updateTicker("ℹ️ Все матчи уже в базе (получено: ${result.totalReceived})", "#2196F3")
                        }
                        else -> {
                            updateTicker("Нет новых данных", "#9E9E9E")
                        }
                    }
                    
                    loadInfo()
                } else {
                    updateTicker("Сервер вернул пустой список", "#9E9E9E")
                }
                
                btnFetchBets.isEnabled = true
                btnFetchBets.text = if (isAutoFetching) "Стоп авто" else "Загрузить с сервера"
                
            } catch (e: Exception) {
                updateTicker("Ошибка: ${e.message}", "#F44336")
                btnFetchBets.isEnabled = true
                btnFetchBets.text = if (isAutoFetching) "Стоп авто" else "Загрузить с сервера"
                Toast.makeText(this@AnalyticsActivity, "Ошибка соединения: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private val fetchRunnable = object : Runnable {
        override fun run() {
            if (isAutoFetching) {
                fetchBetsFromServer()
                handler.postDelayed(this, autoFetchInterval)
            }
        }
    }
    
    private fun toggleAutoFetch() {
        isAutoFetching = !isAutoFetching
        if (isAutoFetching) {
            btnFetchBets.text = "Стоп авто"
            btnFetchBets.setBackgroundColor(Color.parseColor("#F44336"))
            handler.post(fetchRunnable)
            updateTicker("Автообновление запущено (каждые 30 сек)", "#4CAF50")
            Toast.makeText(this, "Автообновление запущено", Toast.LENGTH_SHORT).show()
        } else {
            stopAutoFetch()
        }
    }
    
    private fun stopAutoFetch() {
        isAutoFetching = false
        btnFetchBets.text = "Загрузить с сервера"
        btnFetchBets.setBackgroundColor(Color.parseColor("#2196F3"))
        handler.removeCallbacks(fetchRunnable)
        updateTicker("Автообновление остановлено", "#9E9E9E")
    }
    
    private fun updateTicker(message: String, colorHex: String) {
        tvTicker.text = message
        tvStatusLight.setBackgroundColor(Color.parseColor(colorHex))
    }
    
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(fetchRunnable)
        isAutoFetching = false
    }
}