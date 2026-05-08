// MainActivity.kt — ПОЛНАЯ ВЕРСИЯ С ИСПРАВЛЕНИЯМИ
// getBets добавляет только НОВЫЕ экспрессы
// ScoreUpdateService обновляет счёт через getMatchScore
// Матч активен: curtime 1..125
// Экспресс завершён: есть проигравший, или все матчи завершены и CT > 2ч
// Исправлен дубляж матчей при повторном раскрытии экспресса
// Добавлено подтверждение выхода по кнопке Назад
// Экран не гаснет при открытом приложении
// ИСПРАВЛЕНО: загрузка данных из БД при раскрытии экспресса
package com.example.fonbetbot

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity() {
    
    private lateinit var scrollView: ScrollView
    private lateinit var layoutDetailContent: LinearLayout
    private lateinit var btnAnalytics: Button
    private lateinit var tvDetailTitle: TextView
    private lateinit var layoutDetailTable: View
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var btnClearLogs: Button
    private lateinit var switchFilter: Switch
    private lateinit var tvFilterLabel: TextView
    private lateinit var database: AppDatabase
    private lateinit var analyticsEngine: AnalyticsEngine
    private lateinit var btnSettings: Button
    private var allExpressResults = listOf<ExpressResult>()
    private var allExpressResultsCache = listOf<ExpressResult>()
    
    private lateinit var apiClient: ApiClient
    private lateinit var dataManager: DataManager
    private lateinit var settingsPrefs: SharedPreferences
    private var betRequestJob: Job? = null
    private var isGettingBets = false
    
    private val PAGE_SIZE = 30
    private var currentPage = 0
    private var isLoading = false
    private var allPagesLoaded = false
    
    private val expandedExpressIds = mutableSetOf<Int>()
    
    private val LIVE_HOURS = 2L
    
    private var savedExpandedIds: Set<Int>? = null
    private var savedScrollY: Int = 0
    private var showOnlyLive: Boolean = false
    private var scoreServiceIntent: Intent? = null
    
    private val logLines = mutableListOf<String>()
    private val MAX_LOG_LINES = 500
    
    private val matchMinutesMap = mutableMapOf<Long, Int>()
    
    private var backPressedTime: Long = 0
    
    companion object {
        private const val TAG = "MainActivity"
        private const val COLOR_GREEN = "#03A66D"
        private const val COLOR_RED = "#CF304A"
        private const val COLOR_GOLD = "#F0B90B"
        private const val COLOR_TEXT_PRIMARY = "#EAECEF"
        private const val COLOR_LIVE_BG = "#2B2510"
        private const val COLOR_MATCH_BG = "#161A1E"
        private const val COLOR_MATCH_HEADER_BG = "#1A1F26"
        private const val COLOR_GRID = "#2B3139"
        
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
        const val KEY_MAX_ACTIVE_EXP = "max_active_exp"
        const val KEY_MAX_MATCHES = "max_matches"
        const val KEY_TIMEOUT = "timeout"
        
        const val CLIENT_ID = 18845703L
        const val DEFAULT_TIMEOUT = 60
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Не даём экрану гаснуть пока приложение открыто
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        requestBackgroundPermissions()
        scrollView = findViewById(R.id.scroll_view)
        layoutDetailTable = findViewById(R.id.layout_detail_table)
        btnAnalytics = findViewById(R.id.btn_analytics)
        tvDetailTitle = findViewById(R.id.tv_detail_title)
        layoutDetailContent = findViewById(R.id.layout_detail_content)
        tvLogs = findViewById(R.id.tv_logs)
        scrollLogs = findViewById(R.id.scroll_logs)
        btnClearLogs = findViewById(R.id.btn_clear_logs)
        switchFilter = findViewById(R.id.switch_filter)
        tvFilterLabel = findViewById(R.id.tv_filter_label)
        btnSettings = findViewById(R.id.btn_settings)
        
        apiClient = ApiClient()
        settingsPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        database = AppDatabase.getDatabase(this)
        analyticsEngine = AnalyticsEngine(database)
        dataManager = DataManager(database)
        
        btnAnalytics.setOnClickListener {
            addLog("Переход в Аналитику")
            startActivity(Intent(this, AnalyticsActivity::class.java))
        }
        
        btnClearLogs.setOnClickListener { clearLogs() }
        
        switchFilter.setOnCheckedChangeListener { _, isChecked ->
            showOnlyLive = isChecked
            tvFilterLabel.text = if (isChecked) "Активные" else "Все"
            addLog(if (isChecked) "🔍 Показываем только активные экспрессы" else "🔍 Показываем все экспрессы")
            applyFilterAndReload()
        }
        
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            if (!isLoading && !allPagesLoaded) {
                val child = scrollView.getChildAt(0) as? ViewGroup ?: return@addOnScrollChangedListener
                val scrollY = scrollView.scrollY
                val totalHeight = child.height - scrollView.height
                if (totalHeight > 0 && scrollY >= totalHeight - 200) loadNextPage()
            }
        }
        
        addLog("MainActivity создана")
        loadData()
        startScoreService()
        startBetPolling()
    }
    
    override fun onResume() {
        super.onResume()
        addLog("onResume: обновление данных из БД")
        refreshData()
        ScoreUpdateService.onLogUpdate = { msg -> addLog(msg) }
        ScoreUpdateService.onScoreUpdated = { mId, sh, sa, minute ->
            runOnUiThread {
                updateMatchDisplay(mId, sh, sa, minute)
            }
        }
        
        if (betRequestJob?.isActive != true) {
            startBetPolling()
        }
    }
    
    override fun onPause() {
        super.onPause()
        savedExpandedIds = expandedExpressIds.toSet()
        savedScrollY = scrollView.scrollY
        addLog("💾 Сохранено ${savedExpandedIds?.size ?: 0} раскрытых экспрессов")
        ScoreUpdateService.onLogUpdate = null
        ScoreUpdateService.onScoreUpdated = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        betRequestJob?.cancel()
        scoreServiceIntent?.let { stopService(it) }
        // Убираем флаг при уничтожении
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    
    override fun onBackPressed() {
        if (backPressedTime + 2000 > System.currentTimeMillis()) {
            // Повторное нажатие в течение 2 секунд — выходим
            super.onBackPressed()
            finishAffinity()
        } else {
            // Первое нажатие — показываем подсказку
            backPressedTime = System.currentTimeMillis()
            Toast.makeText(this, "Нажмите ещё раз для выхода", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ==================== АВТООПРОС GETBETS ====================
    
    private fun startBetPolling() {
        betRequestJob?.cancel()
        betRequestJob = lifecycleScope.launch {
            while (isActive) {
                val timeoutSeconds = settingsPrefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT)
                addLog("⏰ Следующий запрос getBets через ${timeoutSeconds}с")
                
                delay(timeoutSeconds * 1000L)
                
                if (!isGettingBets) {
                    requestBets()
                } else {
                    addLog("⏳ Предыдущий запрос ещё выполняется, пропускаем цикл")
                }
            }
        }
    }
    
    private suspend fun requestBets() {
        isGettingBets = true
        
        try {
            val maxActive = settingsPrefs.getInt(KEY_MAX_ACTIVE_EXP, 5)
            val activeCount = withContext(Dispatchers.IO) {
                allExpressResultsCache.count { !isExpressFinished(it) }
            }
            
            addLog("📊 Активных экспрессов: $activeCount/$maxActive")
            
            if (activeCount >= maxActive) {
                addLog("⚠️ Достигнут лимит активных экспрессов ($maxActive). Ожидание завершения...")
                return
            }
            
            val settings = buildBetSettings()
            addLog("📡 Отправка запроса getBets...")
            addLog("   Матчей макс: ${settings.maxMatchesPerExpress}, Кэф мин: ${settings.allMinKef}")
            
            val bets = withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<List<ApiClient.BetData>> { continuation ->
                    apiClient.getBets(
                        userId = CLIENT_ID,
                        settings = settings,
                        onSuccess = { bets -> continuation.resume(bets) {} },
                        onError = { error -> continuation.resumeWith(Result.failure(Exception(error))) }
                    )
                }
            }
            
            addLog("📥 Получено матчей от сервера: ${bets.size}")
            
            if (bets.isEmpty()) {
                addLog("ℹ️ Сервер не вернул новых ставок")
                return
            }
            
            val duplicateCount = withContext(Dispatchers.IO) {
                dataManager.countDuplicates(bets)
            }
            
            if (duplicateCount > 0) {
                addLog("🔄 Найдено дубликатов: $duplicateCount/${bets.size} — экспресс пропущен")
                addLog("ℹ️ Счёт обновляется через ScoreUpdateService (другое API)")
                return
            }
            
            addLog("✅ Все ${bets.size} матчей новые, импортируем...")
            
            val importResult = withContext(Dispatchers.IO) {
                dataManager.importBets(bets)
            }
            
            addLog("✅ Импортирован ${importResult.newExpressCount} экспресс с ${importResult.newMatchCount} матчами")
            addLog("   Новые ID экспрессов: ${importResult.newExpIds}")
            
            ScoreUpdateService.requestFullRecalc = true
            refreshData()
            
        } catch (e: Exception) {
            addLog("❌ Ошибка getBets: ${e.message}")
            Log.e(TAG, "Ошибка getBets", e)
        } finally {
            isGettingBets = false
        }
    }
    
    private fun buildBetSettings(): ApiClient.BetSettings {
        val maxMatches = settingsPrefs.getInt(KEY_MAX_MATCHES, 2)
        val allMinKef = settingsPrefs.getFloat(KEY_ALL_MIN_KEF, 1.67f).toDouble()
        val maxActive = settingsPrefs.getInt(KEY_MAX_ACTIVE_EXP, 5)
        
        val type924Settings = ApiClient.TypeSettings(
            name = "1х/футбол",
            minBet = settingsPrefs.getFloat(KEY_TYPE_924_MIN, 1.15f).toDouble(),
            maxBet = settingsPrefs.getFloat(KEY_TYPE_924_MAX, 1.50f).toDouble(),
            monitorStart = settingsPrefs.getInt(KEY_TYPE_924_MONITOR_START, 80),
            monitorEnd = settingsPrefs.getInt(KEY_TYPE_924_MONITOR_END, 100)
        )
        
        val type927Settings = ApiClient.TypeSettings(
            name = "ф1(+1.5)/футбол",
            minBet = settingsPrefs.getFloat(KEY_TYPE_927_MIN, 1.15f).toDouble(),
            maxBet = settingsPrefs.getFloat(KEY_TYPE_927_MAX, 1.50f).toDouble(),
            monitorStart = settingsPrefs.getInt(KEY_TYPE_927_MONITOR_START, 1),
            monitorEnd = settingsPrefs.getInt(KEY_TYPE_927_MONITOR_END, 45)
        )
        
        val type928Settings = ApiClient.TypeSettings(
            name = "ф2(+1.5)/футбол",
            minBet = settingsPrefs.getFloat(KEY_TYPE_928_MIN, 1.15f).toDouble(),
            maxBet = settingsPrefs.getFloat(KEY_TYPE_928_MAX, 1.50f).toDouble(),
            monitorStart = settingsPrefs.getInt(KEY_TYPE_928_MONITOR_START, 1),
            monitorEnd = settingsPrefs.getInt(KEY_TYPE_928_MONITOR_END, 45)
        )
        
        return ApiClient.BetSettings(
            maxMatchesPerExpress = maxMatches,
            multiply = 2,
            allMinKef = allMinKef,
            maxActiveExpresses = maxActive,
            types = mapOf(
                924 to type924Settings,
                927 to type927Settings,
                928 to type928Settings
            )
        )
    }
    
    // ==================== ФОНОВЫЙ СЕРВИС ====================
    
    private fun requestBackgroundPermissions() {
        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }
    
    private fun startScoreService() {
        scoreServiceIntent = Intent(this, ScoreUpdateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(scoreServiceIntent!!)
        } else {
            startService(scoreServiceIntent!!)
        }
        ScoreUpdateService.onLogUpdate = { msg -> addLog(msg) }
        addLog("🔄 Фоновое обновление счетов запущено")
    }
    
    // ==================== ОБНОВЛЕНИЕ СЧЁТА В UI ====================
    
    private fun updateMatchDisplay(matchId: Long, sh: Int, sa: Int, minute: Int) {
        matchMinutesMap[matchId] = minute
        
        val scoreView = layoutDetailContent.findViewWithTag<TextView>("match_score_$matchId")
        if (scoreView != null) {
            val scoreText = if (minute > 0) {
                "$sh:$sa ($minute')"
            } else {
                "$sh:$sa"
            }
            scoreView.text = scoreText
            
            if (minute in 1..125) {
                scoreView.setTextColor(Color.parseColor("#F0B90B"))
            } else {
                scoreView.setTextColor(Color.parseColor("#EAECEF"))
            }
        }
        
        allExpressResultsCache = allExpressResultsCache.map { express ->
            express.copy(
                matches = express.matches.map { match ->
                    if (match.matchId == matchId) match.copy(sh = sh, sa = sa) else match
                }
            )
        }
        
        allExpressResults = allExpressResults.map { express ->
            express.copy(
                matches = express.matches.map { match ->
                    if (match.matchId == matchId) match.copy(sh = sh, sa = sa) else match
                }
            )
        }
    }
    
    // ==================== ФИЛЬТР ====================
    
    private fun applyFilterAndReload() {
        lifecycleScope.launch {
            try {
                if (allExpressResultsCache.isEmpty()) return@launch
                
                allExpressResults = if (showOnlyLive) {
                    allExpressResultsCache.filter { !isExpressFinished(it) }
                } else {
                    allExpressResultsCache
                }
                
                addLog("📊 Найдено: ${allExpressResults.size} экспрессов")
                
                resetPagination()
                loadExpressPage()
            } catch (e: Exception) {
                addLog("Ошибка фильтрации: ${e.message}")
            }
        }
    }
    
    // ==================== ПРОВЕРКА ЗАВЕРШЁННОСТИ ====================
    
    private fun isExpressFinished(express: ExpressResult): Boolean {
        if (express.matches.any { match ->
            val isWin = when (match.type) {
                924 -> match.sh >= match.sa
                927 -> (match.sh + 1.5) > match.sa
                928 -> match.sh < match.sa + 1.5
                else -> match.sh >= match.sa
            }
            !isWin && match.curtime > 0
        }) return true

        if (!isLive(express)) return true

        if (express.matches.any { it.curtime in 1..125 }) return false

        val allHaveScore = express.matches.all { it.curtime > 0 }
        if (allHaveScore) return true
        
        return false
    }
    
    private fun isLive(express: ExpressResult): Boolean {
        val now = LocalDateTime.now()
        return ChronoUnit.HOURS.between(express.dateTime, now) < LIVE_HOURS
    }
    
    // ==================== ПАГИНАЦИЯ ====================
    
    private fun resetPagination() {
        currentPage = 0
        allPagesLoaded = false
        layoutDetailContent.removeAllViews()
    }
    
    private fun loadExpressPage() {
        if (isLoading || allPagesLoaded) return
        isLoading = true
        
        lifecycleScope.launch {
            try {
                val pageData = withContext(Dispatchers.IO) {
                    val start = currentPage * PAGE_SIZE
                    val end = minOf(start + PAGE_SIZE, allExpressResults.size)
                    if (start >= allExpressResults.size) emptyList()
                    else allExpressResults.subList(start, end).toList()
                }
                
                if (pageData.isEmpty() && currentPage == 0 && allExpressResults.isEmpty()) {
                    tvDetailTitle.text = if (showOnlyLive) "Нет активных экспрессов" else "Нет данных. Импортируйте файлы в Аналитике."
                    layoutDetailTable.visibility = View.VISIBLE
                    isLoading = false
                    return@launch
                }
                
                if (pageData.isEmpty()) {
                    allPagesLoaded = true
                    isLoading = false
                    return@launch
                }
                
                appendExpressRows(pageData)
                currentPage++
                
                if (currentPage == 1 && savedScrollY > 0) {
                    scrollView.post { scrollView.scrollTo(0, savedScrollY) }
                }
                
                if (currentPage * PAGE_SIZE >= allExpressResults.size) {
                    allPagesLoaded = true
                    val liveCount = allExpressResultsCache.count { !isExpressFinished(it) }
                    tvDetailTitle.text = "Экспрессы (${allExpressResults.size}) | Активных: $liveCount"
                } else {
                    tvDetailTitle.text = "Экспрессы (${currentPage * PAGE_SIZE} из ${allExpressResults.size})"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки", e)
            } finally {
                isLoading = false
            }
        }
    }
    
    private fun loadNextPage() {
        if (!isLoading && !allPagesLoaded) loadExpressPage()
    }
    
    // ==================== ТАБЛИЦА ====================
    
    private fun buildHeaderRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(headerTv("ID", 70))
            addView(headerTv("Время", 130))
            addView(headerTv("Статус", 100))
            addView(headerTv("Кэф", 60))
            addView(headerTv("Завершён", 80))
        }
    }
    
    private fun buildExpressRow(express: ExpressResult): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tag = "express_${express.expId}"
            setOnClickListener { toggleExpress(express) }
            isClickable = true
            isFocusable = true
        }
        
        val finished = isExpressFinished(express)
        val itemBg = if (finished) {
            if (express.isWin) "#0A2317" else "#2B0F14"
        } else {
            COLOR_LIVE_BG
        }
        val itemColor = if (finished) {
            if (express.isWin) COLOR_GREEN else COLOR_RED
        } else {
            COLOR_GOLD
        }
        
        val statusText = when {
            !finished -> "⚡ АКТИВЕН"
            express.isWin -> "ВЫИГРЫШ"
            else -> "ПРОИГРЫШ"
        }
        
        val dateTimeStr = "${express.dateTime.format(DateTimeFormatter.ofPattern("dd.MM"))} ${express.dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        val kfStr = if (express.totalStartKf > 0) String.format("%.2f", express.totalStartKf) else "-"
        val completedText = if (finished) "Да" else "Нет"
        val completedColor = if (finished) "#848E9C" else COLOR_GOLD
        val expanded = express.expId in expandedExpressIds
        val idPrefix = if (expanded) "▼" else "▶"
        
        row.addView(dataTv("$idPrefix #${express.expId}", 70, itemBg, itemColor))
        
        val dateTv = dataTv(dateTimeStr, 130, itemBg, COLOR_TEXT_PRIMARY)
        dateTv.tag = "date_${express.expId}"
        dateTv.setOnLongClickListener {
            showEditExpDateTimeDialog(express)
            true
        }
        row.addView(dateTv)
        
        row.addView(dataTv(statusText, 100, itemBg, itemColor, bold = !finished))
        row.addView(dataTv(kfStr, 60, itemBg, "#F0B90B", bold = true))
        row.addView(dataTv(completedText, 80, itemBg, completedColor))
        
        return row
    }
    
    // ==================== ДИАЛОГ РЕДАКТИРОВАНИЯ CT ====================
    
    private fun showEditExpDateTimeDialog(express: ExpressResult) {
        lifecycleScope.launch {
            val currentCt = withContext(Dispatchers.IO) {
                database.expDao().getAllExp().find { it.id_exp == express.expId }?.ct ?: ""
            }
            
            val formattedDate = try {
                val numericValue = currentCt.trim().toDoubleOrNull()
                if (numericValue != null && numericValue > 40000) {
                    val baseDate = LocalDateTime.of(1899, 12, 30, 0, 0)
                    val days = numericValue.toLong()
                    val fraction = numericValue - days
                    val totalSeconds = (fraction * 24 * 60 * 60).toLong()
                    val hours = totalSeconds / 3600
                    val minutes = (totalSeconds % 3600) / 60
                    val seconds = totalSeconds % 60
                    
                    baseDate.plusDays(days)
                        .plusHours(hours)
                        .plusMinutes(minutes)
                        .plusSeconds(seconds)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                } else {
                    currentCt
                }
            } catch (e: Exception) {
                currentCt
            }
            
            val editText = EditText(this@MainActivity).apply {
                setText(formattedDate)
                hint = "yyyy-MM-dd HH:mm:ss"
                setTextColor(Color.parseColor("#EAECEF"))
                setHintTextColor(Color.parseColor("#848E9C"))
                setBackgroundColor(Color.parseColor("#2B3139"))
                setPadding(32, 16, 32, 16)
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT
                isSingleLine = true
            }
            
            AlertDialog.Builder(this@MainActivity)
                .setTitle("CT экспресса #${express.expId}")
                .setMessage("Формат: yyyy-MM-dd HH:mm:ss")
                .setView(editText)
                .setPositiveButton("Сохранить") { _, _ ->
                    val newCt = editText.text.toString().trim()
                    if (newCt.isNotBlank()) {
                        updateExpDateTime(express.expId, newCt)
                    } else {
                        Toast.makeText(this@MainActivity, "Введите дату", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }
    
    private fun updateExpDateTime(expId: Int, newCt: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val allExp = database.expDao().getAllExp()
                val updated = allExp.map {
                    if (it.id_exp == expId) it.copy(ct = newCt) else it
                }
                database.expDao().deleteAll()
                database.expDao().insertAll(updated)
            }
            withContext(Dispatchers.Main) {
                addLog("✏ CT экспресса #$expId обновлён: $newCt")
                ScoreUpdateService.requestFullRecalc = true
                refreshData()
            }
        }
    }
    
    // ==================== ДИАЛОГ РЕДАКТИРОВАНИЯ m_id ====================
    
    private fun showEditMatchIdDialog(match: MatchResult, expId: Int) {
        val editText = EditText(this).apply {
            setText("${match.matchId}")
            hint = "m_id"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor("#EAECEF"))
            setHintTextColor(Color.parseColor("#848E9C"))
            setBackgroundColor(Color.parseColor("#2B3139"))
            setPadding(32, 16, 32, 16)
            textSize = 16f
        }
        
        AlertDialog.Builder(this)
            .setTitle("Редактировать m_id")
            .setMessage("Матч: ${match.home} vs ${match.away}")
            .setView(editText)
            .setPositiveButton("Сохранить") { _, _ ->
                val newMId = editText.text.toString().trim().toLongOrNull()
                if (newMId != null && newMId > 0) {
                    updateMatchId(match.matchId, newMId)
                } else {
                    Toast.makeText(this, "Введите корректный m_id", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    private fun updateMatchId(oldMId: Long, newMId: Long) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val allData = database.dataDao().getAllData()
                val updated = allData.map {
                    if (it.m_id == oldMId) it.copy(m_id = newMId) else it
                }
                database.dataDao().deleteAll()
                database.dataDao().insertAll(updated)
            }
            withContext(Dispatchers.Main) {
                addLog("✏ m_id изменён: $oldMId → $newMId")
                ScoreUpdateService.requestFullRecalc = true
                refreshData()
            }
        }
    }
    
    // ==================== ДИАЛОГ РЕДАКТИРОВАНИЯ СЧЁТА ====================
    
    private fun showEditMatchScoreDialog(match: MatchResult, expId: Int) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        
        val etScore = EditText(this).apply {
            hint = "Счёт sh:sa"
            setText("${match.sh}:${match.sa}")
            setTextColor(Color.parseColor("#EAECEF"))
            setHintTextColor(Color.parseColor("#848E9C"))
            setBackgroundColor(Color.parseColor("#2B3139"))
            setPadding(32, 16, 32, 16)
            textSize = 16f
        }
        
        val etMinute = EditText(this).apply {
            hint = "Минута (curtime)"
            setText("${match.curtime}")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor("#EAECEF"))
            setHintTextColor(Color.parseColor("#848E9C"))
            setBackgroundColor(Color.parseColor("#2B3139"))
            setPadding(32, 16, 32, 16)
            textSize = 16f
        }
        
        layout.addView(TextView(this).apply {
            text = "Счёт:"
            setTextColor(Color.parseColor("#848E9C"))
            textSize = 12f
            setPadding(0, 0, 0, 8)
        })
        layout.addView(etScore)
        layout.addView(TextView(this).apply {
            text = "Минута:"
            setTextColor(Color.parseColor("#848E9C"))
            textSize = 12f
            setPadding(0, 16, 0, 8)
        })
        layout.addView(etMinute)
        
        AlertDialog.Builder(this)
            .setTitle("Матч #${match.matchId} — ${match.home} vs ${match.away}")
            .setView(layout)
            .setPositiveButton("Сохранить") { _, _ ->
                val parts = etScore.text.toString().trim().split(":")
                val sh = parts.getOrNull(0)?.toIntOrNull() ?: match.sh
                val sa = parts.getOrNull(1)?.toIntOrNull() ?: match.sa
                val minute = etMinute.text.toString().trim().toIntOrNull() ?: match.curtime
                updateMatchScore(match.matchId, sh, sa, minute)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    private fun updateMatchScore(matchId: Long, sh: Int, sa: Int, curtime: Int) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val allData = database.dataDao().getAllData()
                val updated = allData.map {
                    if (it.m_id == matchId) it.copy(sh = sh, sa = sa, curtime = curtime) else it
                }
                database.dataDao().deleteAll()
                database.dataDao().insertAll(updated)
            }
            withContext(Dispatchers.Main) {
                addLog("✏ Матч $matchId обновлён: $sh:$sa ($curtime')")
                ScoreUpdateService.requestFullRecalc = true
                refreshData()
            }
        }
    }
    
    // ==================== ДЕТАЛИЗАЦИЯ МАТЧЕЙ (ИСПРАВЛЕНО) ====================
    
    private fun buildMatchDetailRows(express: ExpressResult): List<View> {
        val views = mutableListOf<View>()
        val totalWidth = 500
        val expId = express.expId
        
        views.add(TextView(this).apply {
            text = "Матчи экспресса #$expId"
            textSize = 10f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = Gravity.START
            setBackgroundColor(Color.parseColor(COLOR_MATCH_HEADER_BG))
            setTextColor(Color.parseColor("#F0B90B"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            tag = "match_header_$expId"
        })
        
        views.add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor(COLOR_MATCH_HEADER_BG))
            tag = "match_subheader_$expId"
            addView(matchHeaderTv("m_id", 60))
            addView(matchHeaderTv("Команда 1", 90))
            addView(matchHeaderTv("Команда 2", 90))
            addView(matchHeaderTv("Счёт", 80))
            addView(matchHeaderTv("Кэф", 50))
            addView(matchHeaderTv("Тип", 90))
        })
        
        for ((matchIndex, match) in express.matches.withIndex()) {
            val typeShort: String = when (match.type) {
                924 -> "1X"
                927 -> "Ф1(+1.5)"
                928 -> "Ф2(+1.5)"
                else -> "Т${match.type}"
            }
            
            // ИСПРАВЛЕНО: используем реальные данные матча, а не кеш
            val currentMinute = match.curtime
            val sh = match.sh
            val sa = match.sa
            
            val hasScoreData = currentMinute > 0
            val scoreText: String = if (hasScoreData) {
                if (currentMinute in 1..125) {
                    "$sh:$sa ($currentMinute')"
                } else {
                    "$sh:$sa"
                }
            } else {
                "—"
            }
            
            val kfText: String = String.format("%.2f", match.startkf)
            
            // ИСПРАВЛЕНО: правильная логика определения победы для всех типов
            val isWinCorrect = when (match.type) {
                924 -> sh >= sa
                927 -> (sh + 1.5) > sa
                928 -> sh < sa + 1.5
                else -> sh >= sa
            }
            
            val hasScore = sh > 0 || sa > 0 || currentMinute > 0
            val mc: String = if (hasScore) {
                if (isWinCorrect) COLOR_GREEN else COLOR_RED
            } else {
                "#848E9C"
            }
            
            val resultText: String = if (hasScore) {
                if (isWinCorrect) "✓ Зашел" else "✗ Мимо"
            } else {
                "Ожидание"
            }
            
            val ligaInfoText: String = "#${match.matchId} | ${match.liganame.take(35)} | $resultText"
            
            views.add(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(Color.parseColor(COLOR_MATCH_BG))
                tag = "match_${expId}_${match.matchId}_$matchIndex"
                
                addView(matchDataTv("${match.matchId}", 60, "#848E9C", 9f, false).apply {
                    setOnLongClickListener {
                        showEditMatchIdDialog(match, expId)
                        true
                    }
                })
                
                addView(matchDataTv(match.home.take(13), 90, COLOR_TEXT_PRIMARY, 10f, false))
                addView(matchDataTv(match.away.take(13), 90, COLOR_TEXT_PRIMARY, 10f, false))
                
                val scoreTv = matchDataTv(scoreText, 80, 
                    if (currentMinute in 1..125) "#F0B90B" else "#EAECEF", 
                    11f, true).apply {
                    tag = "match_score_${match.matchId}"
                    setOnLongClickListener {
                        showEditMatchScoreDialog(match, expId)
                        true
                    }
                }
                addView(scoreTv)
                
                addView(matchDataTv(kfText, 50, "#F0B90B", 10f, false))
                addView(matchDataTv(typeShort, 90, "#848E9C", 10f, false))
            })
            
            views.add(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(Color.parseColor("#11161C"))
                tag = "match_info_${expId}_${match.matchId}_$matchIndex"
                addView(matchDataTv(ligaInfoText, totalWidth, mc, 9f, false))
            })
        }
        
        views.add(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(totalWidth), dp(2))
            setBackgroundColor(Color.parseColor(COLOR_GRID))
            tag = "sep_$expId"
        })
        
        return views
    }
    
    // ==================== РАСКРЫТИЕ/СКРЫТИЕ (ИСПРАВЛЕНО) ====================
    
    private fun toggleExpress(express: ExpressResult) {
        if (express.expId in expandedExpressIds) {
            expandedExpressIds.remove(express.expId)
            removeMatchRows(express.expId)
            updateExpressRowIndicator(express.expId, false)
        } else {
            expandedExpressIds.add(express.expId)
            
            // ИСПРАВЛЕНО: загружаем свежие данные из БД
            lifecycleScope.launch {
                try {
                    val freshData = withContext(Dispatchers.IO) {
                        database.dataDao().getAllData().filter { it.id_exp == express.expId }
                    }
                    
                    if (freshData.isNotEmpty()) {
                        // Создаём обновлённый ExpressResult с актуальными данными из БД
                        val matchResults = freshData.map { match ->
                            val isWin = when (match.type) {
                                924 -> match.sh >= match.sa
                                927 -> (match.sh + 1.5) > match.sa
                                928 -> match.sh < match.sa + 1.5
                                else -> match.sh >= match.sa
                            }
                            
                            MatchResult(
                                matchId = match.m_id,
                                home = match.home,
                                away = match.away,
                                sh = match.sh,
                                sa = match.sa,
                                type = match.type,
                                isWin = isWin,
                                liganame = match.liganame,
                                startkf = match.startkf,
                                curtime = match.curtime
                            )
                        }
                        
                        val freshExpress = express.copy(
                            matches = matchResults,
                            isWin = matchResults.all { it.isWin && it.curtime > 0 }
                        )
                        
                        // Обновляем кеш
                        allExpressResultsCache = allExpressResultsCache.map { 
                            if (it.expId == express.expId) freshExpress else it 
                        }
                        allExpressResults = allExpressResults.map { 
                            if (it.expId == express.expId) freshExpress else it 
                        }
                        
                        // Показываем обновлённые строки
                        withContext(Dispatchers.Main) {
                            removeMatchRows(express.expId)
                            insertMatchRows(freshExpress)
                        }
                    } else {
                        // Если данных нет, используем кеш
                        withContext(Dispatchers.Main) {
                            insertMatchRows(express)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка загрузки данных из БД: ${e.message}")
                    // Fallback: используем кеш
                    withContext(Dispatchers.Main) {
                        insertMatchRows(express)
                    }
                }
            }
        }
    }
    
    private fun updateExpressRowIndicator(expId: Int, expanded: Boolean) {
        val row = layoutDetailContent.findViewWithTag<LinearLayout>("express_$expId") ?: return
        val tv = row.getChildAt(0) as? TextView ?: return
        tv.text = tv.text.toString().let { if (expanded) it.replace("▶", "▼") else it.replace("▼", "▶") }
    }
    
    private fun insertMatchRows(express: ExpressResult) {
        val row = layoutDetailContent.findViewWithTag<LinearLayout>("express_${express.expId}") ?: return
        val index = layoutDetailContent.indexOfChild(row)
        if (index == -1) return
        
        removeMatchRows(express.expId)
        
        val updatedIndex = layoutDetailContent.indexOfChild(row)
        if (updatedIndex == -1) return
        
        buildMatchDetailRows(express).forEachIndexed { i, v ->
            layoutDetailContent.addView(v, updatedIndex + 1 + i)
        }
    }
    
    private fun removeMatchRows(expId: Int) {
        val toRemove = mutableListOf<View>()
        for (i in 0 until layoutDetailContent.childCount) {
            val child = layoutDetailContent.getChildAt(i)
            val tag = child.tag as? String ?: continue
            
            if (tag.startsWith("match_header_$expId") ||
                tag.startsWith("match_subheader_$expId") ||
                tag.startsWith("match_${expId}_") ||
                tag.startsWith("match_info_${expId}_") ||
                tag == "sep_$expId") {
                toRemove.add(child)
            }
        }
        toRemove.forEach { layoutDetailContent.removeView(it) }
    }
    
    private fun appendExpressRows(expresses: List<ExpressResult>) {
        if (expresses.isEmpty()) return
        layoutDetailTable.visibility = View.VISIBLE
        if (currentPage == 0) layoutDetailContent.addView(buildHeaderRow())
        expresses.forEach { express ->
            layoutDetailContent.addView(buildExpressRow(express))
            if (express.expId in expandedExpressIds) {
                // ИСПРАВЛЕНО: при восстановлении раскрытых экспрессов загружаем из БД
                lifecycleScope.launch {
                    val freshData = withContext(Dispatchers.IO) {
                        database.dataDao().getAllData().filter { it.id_exp == express.expId }
                    }
                    if (freshData.isNotEmpty()) {
                        val matchResults = freshData.map { match ->
                            MatchResult(
                                matchId = match.m_id,
                                home = match.home,
                                away = match.away,
                                sh = match.sh,
                                sa = match.sa,
                                type = match.type,
                                isWin = false,
                                liganame = match.liganame,
                                startkf = match.startkf,
                                curtime = match.curtime
                            )
                        }
                        val fresh = express.copy(matches = matchResults)
                        insertMatchRows(fresh)
                    } else {
                        insertMatchRows(express)
                    }
                }
            }
        }
    }
    
    // ==================== ФАБРИКИ VIEW ====================
    
    private fun headerTv(text: String, w: Int) = TextView(this).apply {
        this.text = text; textSize = 11f; setPadding(dp(4), dp(6), dp(4), dp(6))
        gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#2B3139"))
        setTextColor(Color.parseColor("#EAECEF")); setTypeface(null, android.graphics.Typeface.BOLD)
        maxLines = 2; layoutParams = LinearLayout.LayoutParams(dp(w), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    
    private fun matchHeaderTv(text: String, w: Int) = TextView(this).apply {
        this.text = text; textSize = 10f; setPadding(dp(4), dp(4), dp(4), dp(4))
        gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor(COLOR_MATCH_HEADER_BG))
        setTextColor(Color.parseColor("#5E6673")); setTypeface(null, android.graphics.Typeface.BOLD)
        maxLines = 1; layoutParams = LinearLayout.LayoutParams(dp(w), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    
    private fun dataTv(text: String, w: Int, bg: String, color: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = 10f; setPadding(dp(4), dp(4), dp(4), dp(4))
        gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor(bg)); setTextColor(Color.parseColor(color))
        maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(w), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    
    private fun matchDataTv(text: String, w: Int, color: String, size: Float, bold: Boolean) = TextView(this).apply {
        this.text = text; this.textSize = size; setPadding(dp(4), dp(3), dp(4), dp(3))
        gravity = Gravity.CENTER; setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor(color))
        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(w), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    
    // ==================== ЛОГИ ====================
    
    fun addLog(message: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val logEntry = "$timestamp | $message"
        Log.d(TAG, message)
        synchronized(logLines) {
            logLines.add(logEntry)
            if (logLines.size > MAX_LOG_LINES) logLines.removeAt(0)
        }
        runOnUiThread {
            updateLogView()
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }
    }
    
    private fun clearLogs() {
        synchronized(logLines) {
            logLines.clear()
            logLines.add("${LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))} | Логи очищены")
        }
        updateLogView()
    }
    
    private fun updateLogView() {
        synchronized(logLines) { tvLogs.text = logLines.joinToString("\n") }
    }
    
    // ==================== ЗАГРУЗКА ДАННЫХ (ИСПРАВЛЕНО) ====================
    
    private fun loadData() {
        lifecycleScope.launch {
            try {
                tvDetailTitle.text = "Загрузка..."
                
                val expCount = withContext(Dispatchers.IO) { database.expDao().getAllExp().size }
                val dataCount = withContext(Dispatchers.IO) { database.dataDao().getAllData().size }
                
                if (expCount == 0 || dataCount == 0) {
                    tvDetailTitle.text = "Нет данных. Импортируйте файлы в Аналитике."
                    layoutDetailTable.visibility = View.VISIBLE
                    return@launch
                }
                
                // Принудительный пересчёт перед загрузкой
                ScoreUpdateService.requestFullRecalc = true
                
                val analytics = withContext(Dispatchers.IO) { 
                    delay(500) // Ждём пересчёта
                    analyticsEngine.calculateAnalytics() 
                }
                
                allExpressResultsCache = ((analytics["allExpresses"] as? List<ExpressResult>) ?: emptyList())
                    .sortedByDescending { it.dateTime }
                
                allExpressResults = if (showOnlyLive) {
                    allExpressResultsCache.filter { !isExpressFinished(it) }
                } else {
                    allExpressResultsCache
                }
                
                if (savedExpandedIds != null) {
                    expandedExpressIds.addAll(savedExpandedIds!!)
                }
                
                resetPagination()
                loadExpressPage()
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка", e)
                tvDetailTitle.text = "Ошибка: ${e.message}"
            }
        }
    }
    
    private fun refreshData() {
        lifecycleScope.launch {
            try {
                val expCount = withContext(Dispatchers.IO) { database.expDao().getAllExp().size }
                val dataCount = withContext(Dispatchers.IO) { database.dataDao().getAllData().size }
                
                if (expCount > 0 && dataCount > 0) {
                    // Принудительно пересчитываем аналитику из БД
                    ScoreUpdateService.requestFullRecalc = true
                    
                    val analytics = withContext(Dispatchers.IO) { 
                        delay(500) // Ждём пересчёта
                        analyticsEngine.calculateAnalytics() 
                    }
                    
                    allExpressResultsCache = ((analytics["allExpresses"] as? List<ExpressResult>) ?: emptyList())
                        .sortedByDescending { it.dateTime }
                    
                    allExpressResults = if (showOnlyLive) {
                        allExpressResultsCache.filter { !isExpressFinished(it) }
                    } else {
                        allExpressResultsCache
                    }
                    
                    val restoredIds = savedExpandedIds ?: emptySet()
                    expandedExpressIds.clear()
                    expandedExpressIds.addAll(restoredIds)
                    
                    resetPagination()
                    loadExpressPage()
                    
                    if (restoredIds.isNotEmpty()) {
                        addLog("📂 Восстановлено ${restoredIds.size} раскрытых экспрессов")
                    }
                }
            } catch (e: Exception) {
                addLog("Ошибка refreshData: ${e.message}")
            }
        }
    }
}