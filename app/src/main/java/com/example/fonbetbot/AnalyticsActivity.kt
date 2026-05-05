// AnalyticsActivity.kt
package com.example.fonbetbot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var database: AppDatabase
    private lateinit var excelReader: ExcelReader
    
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
        
        database = AppDatabase.getDatabase(this)
        excelReader = ExcelReader(this)
        
        btnImportExp.setOnClickListener {
            openFilePicker(PICK_EXP_FILE)
        }
        
        btnImportData.setOnClickListener {
            openFilePicker(PICK_DATA_FILE)
        }
        
        btnDashboard.setOnClickListener {
            val intent = Intent(this, DashboardActivity::class.java)
            startActivity(intent)
        }
        
        btnClearData.setOnClickListener {
            clearAllData()
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
                                    Toast.makeText(
                                        this@AnalyticsActivity,
                                        "Загружено ${expData.size} записей exp",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            PICK_DATA_FILE -> {
                                val dataList = excelReader.readDataData(uri)
                                database.dataDao().deleteAll()
                                database.dataDao().insertAll(dataList)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@AnalyticsActivity,
                                        "Загружено ${dataList.size} записей data",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    
                    loadInfo()
                    
                } catch (e: Exception) {
                    Toast.makeText(
                        this@AnalyticsActivity,
                        "Ошибка загрузки: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    tvAnalytics.text = "Ошибка загрузки:\n${e.message}"
                }
            }
        }
    }
    
    private fun loadInfo() {
        lifecycleScope.launch {
            try {
                val expCount = withContext(Dispatchers.IO) {
                    database.expDao().getAllExp().size
                }
                val dataCount = withContext(Dispatchers.IO) {
                    database.dataDao().getAllData().size
                }
                
                tvAnalytics.text = buildString {
                    appendLine("Статус базы данных:")
                    appendLine("Записей в таблице exp: $expCount")
                    appendLine("Записей в таблице data: $dataCount")
                    appendLine()
                    if (expCount > 0 && dataCount > 0) {
                        appendLine("Данные загружены успешно.")
                        appendLine("Нажмите \"Дашборд\" для просмотра аналитики.")
                    } else {
                        appendLine("Импортируйте файлы Excel:")
                        appendLine("1. exp10.xlsx (кнопка \"Импорт Exp\")")
                        appendLine("2. data10.xlsx (кнопка \"Импорт Data\")")
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
                Toast.makeText(
                    this@AnalyticsActivity,
                    "Все данные удалены",
                    Toast.LENGTH_SHORT
                ).show()
                
            } catch (e: Exception) {
                Toast.makeText(
                    this@AnalyticsActivity,
                    "Ошибка очистки: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}