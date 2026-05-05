// AnalyticsActivity.kt
package com.example.fonbetbot

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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
        private const val TAG = "AnalyticsActivity"
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
                fetchBetsAndUpdateScores()
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
                val expCount: Int = withContext(Dispatchers.IO) { database.expDao().getAllExp().size }
                val dataCount: Int = withContext(Dispatchers.IO) { database.dataDao().getAllData().size }
                
                tvAnalytics.text = buildString {
                    appendLine("Статус базы данных:")
                    appendLine("Записей в таблице exp: $expCount")
                    appendLine("Записей в таблице data: $dataCount")
                    appendLine()
                    if (expCount > 0 && dataCount > 0) {
                        appendLine("Данные загружены успешно.")
                        appendLine("Нажмите \"Дашборд\" для просмотра аналитики.")
                        appendLine()
                        appendLine("Кнопка \"Загрузить с сервера\":")
                        appendLine("- Получает матчи (1-4 шт)")
                        appendLine("- Обновляет счета")
                        appendLine("- Собирает в экспресс")
                        appendLine("Долгое нажатие - авто (30 сек)")
                    } else {
                        appendLine("Импортируйте файлы Excel:")
                        appendLine("1. processed_exp10.xlsx")
                        appendLine("2. processed_data10.xlsx")
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
                tvAnalytics.text = "Данные очищены."
                Toast.makeText(this@AnalyticsActivity, "Все данные удалены", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@AnalyticsActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // ==================== ЗАГРУЗКА С СЕРВЕРА ====================
    
    private fun fetchBetsAndUpdateScores() {
        lifecycleScope.launch {
            try {
                updateTicker("Загрузка с сервера...", "#FFC107")
                btnFetchBets.isEnabled = false
                btnFetchBets.text = "Загрузка..."
                
                val bets: List<BetData> = withContext(Dispatchers.IO) {
                    fetchBetsFromApi()
                }
                
                if (bets.isEmpty()) {
                    updateTicker("Сервер вернул пустой список", "#9E9E9E")
                    btnFetchBets.isEnabled = true
                    btnFetchBets.text = if (isAutoFetching) "Стоп авто" else "Загрузить с сервера"
                    return@launch
                }
                
                updateTicker("Получено ${bets.size} матчей. Обновляем счета...", "#FFC107")
                
                // Обновляем счета
                val updatedBets: List<BetData> = withContext(Dispatchers.IO) {
                    bets.map { bet: BetData ->
                        val score: Pair<Int, Int>? = fetchMatchScore(bet.m_id.toInt())
                        if (score != null) {
                            bet.copy(sh = score.first, sa = score.second)
                        } else {
                            bet
                        }
                    }
                }
                
                // Импортируем
                val result: DataManager.ImportResult = withContext(Dispatchers.IO) {
                    dataManager.importBets(updatedBets)
                }
                
                when {
                    result.newExpressCount > 0 -> {
                        updateTicker("✅ Новых экспрессов: ${result.newExpressCount}, матчей: ${result.newMatchCount}", "#4CAF50")
                    }
                    result.skippedCount > 0 -> {
                        updateTicker("ℹ️ Все матчи уже в базе", "#2196F3")
                    }
                    else -> {
                        updateTicker("Нет новых данных", "#9E9E9E")
                    }
                }
                
                loadInfo()
                btnFetchBets.isEnabled = true
                btnFetchBets.text = if (isAutoFetching) "Стоп авто" else "Загрузить с сервера"
                
            } catch (e: Exception) {
                updateTicker("Ошибка: ${e.message}", "#F44336")
                btnFetchBets.isEnabled = true
                btnFetchBets.text = if (isAutoFetching) "Стоп авто" else "Загрузить с сервера"
            }
        }
    }
    
    private fun fetchBetsFromApi(): List<BetData> {
        return try {
            val url = URL("http://95.183.11.203:5000/api/getBets")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            
            val jsonBody = JSONObject().apply {
                put("user_id", 0)
                put("settings", JSONObject())
            }
            
            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                val json = JSONObject(response)
                val dataArray: JSONArray = if (json.has("data")) {
                    json.getJSONArray("data")
                } else {
                    JSONArray()
                }
                
                val bets = mutableListOf<BetData>()
                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.getJSONObject(i)
                    bets.add(BetData(
                        sport = obj.optString("sport", ""),
                        id_exp = obj.optInt("id_exp", 0),
                        m_id = obj.optLong("m_id", 0),
                        id_liga = obj.optInt("id_liga", 0),
                        liganame = obj.optString("liganame", ""),
                        home = obj.optString("home", ""),
                        away = obj.optString("away", ""),
                        comand1id = obj.optLong("comand1id", 0),
                        comand2id = obj.optLong("comand2id", 0),
                        curtime = obj.optInt("curtime", 0),
                        sh = obj.optInt("sh", 0),
                        sa = obj.optInt("sa", 0),
                        startkf = obj.optDouble("startkf", 0.0),
                        lastkf = obj.optDouble("lastkf", 0.0),
                        type = obj.optInt("type", 924),
                        sts = obj.optInt("sts", 1),
                        url = obj.optString("url", ""),
                        uzh = obj.optDouble("uzh", 0.0),
                        tbtype = obj.optInt("tbtype", 0)
                    ))
                }
                bets
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запроса: ${e.message}")
            emptyList()
        }
    }
    
    private fun fetchMatchScore(matchId: Int): Pair<Int, Int>? {
        return try {
            val url = URL("https://lne-lb61-w.bk6bba-resources.com/ma/events/event?lang=ru&version=75079419261&eventId=$matchId&scopeMarket=1600")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                val json = JSONObject(response)
                var sh = 0
                var sa = 0
                
                if (json.has("liveEventInfos")) {
                    val liveEventInfos = json.getJSONArray("liveEventInfos")
                    if (liveEventInfos.length() > 0) {
                        val liveEventInfo = liveEventInfos.getJSONObject(0)
                        if (liveEventInfo.has("scores")) {
                            val scores = liveEventInfo.getJSONArray("scores")
                            if (scores.length() > 0) {
                                val mainScore = scores.getJSONArray(0)
                                if (mainScore.length() > 0) {
                                    val scoreObj = mainScore.getJSONObject(0)
                                    sh = scoreObj.optInt("c1", 0)
                                    sa = scoreObj.optInt("c2", 0)
                                }
                            }
                        }
                    }
                }
                Pair(sh, sa)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения счета: ${e.message}")
            null
        }
    }
    
    private val fetchRunnable = object : Runnable {
        override fun run() {
            if (isAutoFetching) {
                fetchBetsAndUpdateScores()
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
            updateTicker("Автообновление (30 сек)", "#4CAF50")
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