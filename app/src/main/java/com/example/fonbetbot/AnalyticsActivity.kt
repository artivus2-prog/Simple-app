// AnalyticsActivity.kt
package com.example.fonbetbot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AnalyticsActivity : AppCompatActivity() {
    
    private lateinit var tvAnalytics: TextView
    private lateinit var btnImportExp: Button
    private lateinit var btnImportData: Button
    private lateinit var database: AppDatabase
    private lateinit var excelReader: ExcelReader
    
    private val PICK_EXP_FILE = 1
    private val PICK_DATA_FILE = 2
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analytics)
        
        tvAnalytics = findViewById(R.id.tv_analytics)
        btnImportExp = findViewById(R.id.btn_import_exp)
        btnImportData = findViewById(R.id.btn_import_data)
        
        database = AppDatabase.getDatabase(this)
        excelReader = ExcelReader(this)
        
        btnImportExp.setOnClickListener {
            openFilePicker(PICK_EXP_FILE)
        }
        
        btnImportData.setOnClickListener {
            openFilePicker(PICK_DATA_FILE)
        }
        
        // Загружаем данные при запуске
        loadData()
    }
    
    private fun openFilePicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        startActivityForResult(intent, requestCode)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (resultCode == Activity.RESULT_OK && data?.data != null) {
            val uri = data.data!!
            
            lifecycleScope.launch {
                try {
                    when (requestCode) {
                        PICK_EXP_FILE -> {
                            val expData = excelReader.readExpData(uri)
                            database.expDao().deleteAll()
                            database.expDao().insertAll(expData)
                            Toast.makeText(this@AnalyticsActivity, 
                                "Загружено ${expData.size} записей exp", 
                                Toast.LENGTH_SHORT).show()
                        }
                        PICK_DATA_FILE -> {
                            val dataList = excelReader.readDataData(uri)
                            database.dataDao().deleteAll()
                            database.dataDao().insertAll(dataList)
                            Toast.makeText(this@AnalyticsActivity, 
                                "Загружено ${dataList.size} записей data", 
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                    loadData()
                } catch (e: Exception) {
                    Toast.makeText(this@AnalyticsActivity, 
                        "Ошибка: ${e.message}", 
                        Toast.LENGTH_LONG).show()
                    tvAnalytics.text = "Ошибка загрузки: ${e.message}"
                }
            }
        }
    }
    
    private fun loadData() {
        lifecycleScope.launch {
            database.dataDao().getDataWithExp().collect { dataList ->
                if (dataList.isEmpty()) {
                    tvAnalytics.text = "Нет данных. Импортируйте файлы."
                } else {
                    val displayText = buildString {
                        appendLine("ID | Exp | Матч | Счет | KF | Статус | Стратегия")
                        appendLine("-".repeat(80))
                        dataList.take(100).forEach { data ->  // Показываем первые 100
                            appendLine("${data.id} | ${data.id_exp} | ${data.home} vs ${data.away} | ${data.sh}:${data.sa} | ${data.kfall} | ${data.sts} | ${data.strategy}")
                        }
                        if (dataList.size > 100) {
                            appendLine("... и еще ${dataList.size - 100} записей")
                        }
                    }
                    tvAnalytics.text = displayText
                }
            }
        }
    }
}