// MainActivity.kt — ПОЛНАЯ ВЕРСИЯ С ОНЛАЙН-ОБНОВЛЕНИЕМ СЧЕТОВ (ИСПРАВЛЕНО)
package com.example.fonbetbot

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
    private lateinit var database: AppDatabase
    private lateinit var analyticsEngine: AnalyticsEngine
    
    private var allExpressResults = listOf<ExpressResult>()
    
    private val PAGE_SIZE = 30
    private var currentPage = 0
    private var isLoading = false
    private var allPagesLoaded = false
    
    private val expandedExpressIds = mutableSetOf<Int>()
    
    private val LIVE_HOURS = 3L
    
    // Автообновление счетов
    private val apiClient = ApiClient()
    private var scoreUpdateJob: Job? = null
    
    private val logLines = mutableListOf<String>()
    private val MAX_LOG_LINES = 500
    
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
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        scrollView = findViewById(R.id.scroll_view)
        layoutDetailTable = findViewById(R.id.layout_detail_table)
        btnAnalytics = findViewById(R.id.btn_analytics)
        tvDetailTitle = findViewById(R.id.tv_detail_title)
        layoutDetailContent = findViewById(R.id.layout_detail_content)
        tvLogs = findViewById(R.id.tv_logs)
        scrollLogs = findViewById(R.id.scroll_logs)
        btnClearLogs = findViewById(R.id.btn_clear_logs)
        
        database = AppDatabase.getDatabase(this)
        analyticsEngine = AnalyticsEngine(database)
        
        btnAnalytics.setOnClickListener {
            addLog("Переход в Аналитику")
            startActivity(Intent(this, AnalyticsActivity::class.java))
        }
        
        btnClearLogs.setOnClickListener { clearLogs() }
        
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
    }
    
    override fun onResume() {
        super.onResume()
        addLog("onResume: обновление данных")
        refreshData()
        startScoreUpdates()
    }
    
    override fun onPause() {
        super.onPause()
        stopScoreUpdates()
    }
    
    // ========== ОНЛАЙН-ОБНОВЛЕНИЕ СЧЕТОВ ==========
    
    private fun startScoreUpdates() {
        stopScoreUpdates()
        scoreUpdateJob = lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                updateLiveMatchScores()
            }
        }
        addLog("🔄 Автообновление счетов запущено (интервал 30с)")
    }
    
    private fun stopScoreUpdates() {
        scoreUpdateJob?.cancel()
        scoreUpdateJob = null
    }
    
    private suspend fun updateLiveMatchScores() {
        val liveExpresses = allExpressResults.filter { isLive(it) }
        if (liveExpresses.isEmpty()) return
        
        val liveMatchIds = liveExpresses
            .flatMap { it.matches }
            .map { it.matchId }
            .distinct()
        
        if (liveMatchIds.isEmpty()) return
        
        var updatedCount = 0
        var finishedCount = 0
        
        for (matchId in liveMatchIds) {
            withContext(Dispatchers.IO) {
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        apiClient.getMatchScore(
                            matchId = matchId.toInt(),
                            onSuccess = { factors ->
                                if (factors != null && factors.score1 >= 0 && factors.score2 >= 0) {
                                    launch(Dispatchers.Main) {
                                        updateMatchScoreInTable(matchId, factors.score1, factors.score2, factors.matchTime)
                                    }
                                    updatedCount++
                                } else {
                                    launch(Dispatchers.Main) { markMatchAsFinished(matchId) }
                                    finishedCount++
                                }
                                continuation.resume(Unit) {}
                            },
                            onError = { error ->
                                launch(Dispatchers.Main) { markMatchAsFinished(matchId) }
                                finishedCount++
                                continuation.resume(Unit) {}
                            }
                        )
                    }
                    delay(200)
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка счета m_id=$matchId: ${e.message}")
                }
            }
        }
        
        if (updatedCount > 0) addLog("✅ Обновлено счетов: $updatedCount")
        if (finishedCount > 0) addLog("🏁 Завершено матчей: $finishedCount")
    }
    
    private fun updateMatchScoreInTable(matchId: Long, sh: Int, sa: Int, matchTime: Int) {
        allExpressResults = allExpressResults.map { express ->
            val matchIndex = express.matches.indexOfFirst { it.matchId == matchId }
            if (matchIndex >= 0) {
                val updatedMatches = express.matches.toMutableList()
                updatedMatches[matchIndex] = updatedMatches[matchIndex].copy(sh = sh, sa = sa)
                express.copy(matches = updatedMatches)
            } else express
        }
        
        for (i in 0 until layoutDetailContent.childCount) {
            val child = layoutDetailContent.getChildAt(i)
            if (child.tag == "match_$matchId" && child is LinearLayout) {
                for (j in 0 until child.childCount) {
                    val tv = child.getChildAt(j) as? TextView ?: continue
                    if (tv.text.toString().matches(Regex("\\d+:\\d+.*"))) {
                        tv.text = if (matchTime > 0) "$sh:$sa ($matchTime')" else "$sh:$sa"
                        break
                    }
                }
            }
        }
    }
    
    private fun markMatchAsFinished(matchId: Long) {
        allExpressResults = allExpressResults.map { express ->
            val matchIndex = express.matches.indexOfFirst { it.matchId == matchId }
            if (matchIndex >= 0) {
                val match = express.matches[matchIndex]
                val isWin = when (match.type) {
                    924 -> match.sh >= match.sa
                    927 -> match.sh + 1 > match.sa
                    928 -> match.sa + 1 >= match.sh
                    else -> match.sh >= match.sa
                }
                val updatedMatches = express.matches.toMutableList()
                updatedMatches[matchIndex] = match.copy(isWin = isWin)
                val allWin = updatedMatches.all { it.isWin }
                express.copy(matches = updatedMatches, isWin = allWin)
            } else express
        }
        
        // Обновляем info-строки
        for (i in 0 until layoutDetailContent.childCount) {
            val child = layoutDetailContent.getChildAt(i)
            if (child.tag == "match_info_$matchId" && child is LinearLayout && child.childCount > 0) {
                val tv = child.getChildAt(0) as? TextView ?: continue
                for (express in allExpressResults) {
                    val match = express.matches.find { it.matchId == matchId }
                    if (match != null) {
                        val mc = if (match.isWin) COLOR_GREEN else COLOR_RED
                        val resultText = if (match.isWin) "✓ Зашел" else "✗ Мимо"
                        tv.text = "${match.liganame.take(40)} | $resultText"
                        tv.setTextColor(Color.parseColor(mc))
                        break
                    }
                }
            }
        }
        
        // Обновляем строку экспресса если все матчи завершены
        for (express in allExpressResults) {
            if (express.matches.any { it.matchId == matchId } && !isLive(express)) {
                updateExpressRowAppearance(express.expId)
            }
        }
    }
    
    private fun updateExpressRowAppearance(expId: Int) {
        val expressRow = layoutDetailContent.findViewWithTag<LinearLayout>("express_$expId") ?: return
        val express = allExpressResults.find { it.expId == expId } ?: return
        
        if (expressRow.childCount >= 5) {
            val rowBg = getRowBackground(express)
            expressRow.setBackgroundColor(Color.parseColor(rowBg))
            
            val statusTv = expressRow.getChildAt(2) as? TextView
            statusTv?.let {
                it.text = if (express.isWin) "ВЫИГРЫШ" else "ПРОИГРЫШ"
                it.setTextColor(Color.parseColor(if (express.isWin) COLOR_GREEN else COLOR_RED))
            }
            
            val completedTv = expressRow.getChildAt(4) as? TextView
            completedTv?.let {
                it.text = "Да"
                it.setTextColor(Color.parseColor("#848E9C"))
            }
        }
    }
    
    // ========== ЛОГИ ==========
    
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
    
    // ========== ВСПОМОГАТЕЛЬНЫЕ ==========
    
    private fun isLive(express: ExpressResult): Boolean {
        val now = LocalDateTime.now()
        return ChronoUnit.HOURS.between(express.dateTime, now) < LIVE_HOURS
    }
    
    private fun getRowBackground(express: ExpressResult): String {
        return if (isLive(express)) COLOR_LIVE_BG
        else if (express.isWin) "#0A2317" else "#2B0F14"
    }
    
    private fun getStatusColor(express: ExpressResult): String {
        return if (isLive(express)) COLOR_GOLD
        else if (express.isWin) COLOR_GREEN else COLOR_RED
    }
    
    // ========== ПАГИНАЦИЯ ==========
    
    private fun resetPagination() {
        currentPage = 0
        allPagesLoaded = false
        expandedExpressIds.clear()
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
                    tvDetailTitle.text = "Нет данных. Импортируйте файлы в Аналитике."
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
                
                if (currentPage * PAGE_SIZE >= allExpressResults.size) {
                    allPagesLoaded = true
                    val liveCount = allExpressResults.count { isLive(it) }
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
    
    // ========== ТАБЛИЦА ==========
    
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
        
        val live = isLive(express)
        val itemBg = getRowBackground(express)
        val itemColor = getStatusColor(express)
        
        val statusText = when {
            live -> "⚡ АКТИВЕН"
            express.isWin -> "ВЫИГРЫШ"
            else -> "ПРОИГРЫШ"
        }
        
        val dateTimeStr = "${express.dateTime.format(DateTimeFormatter.ofPattern("dd.MM"))} ${express.dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        val kfStr = if (express.totalStartKf > 0) String.format("%.2f", express.totalStartKf) else "-"
        val completedText = if (live) "Нет" else "Да"
        val completedColor = if (live) COLOR_GOLD else "#848E9C"
        val expanded = express.expId in expandedExpressIds
        val idPrefix = if (expanded) "▼" else "▶"
        
        row.addView(dataTv("$idPrefix #${express.expId}", 70, itemBg, itemColor))
        row.addView(dataTv(dateTimeStr, 130, itemBg, COLOR_TEXT_PRIMARY))
        row.addView(dataTv(statusText, 100, itemBg, itemColor, bold = live))
        row.addView(dataTv(kfStr, 60, itemBg, "#F0B90B", bold = true))
        row.addView(dataTv(completedText, 80, itemBg, completedColor))
        
        return row
    }
    
       private fun buildMatchDetailRows(express: ExpressResult): List<View> {
    val views = mutableListOf<View>()
    val totalWidth = 440

    views.add(TextView(this).apply {
        text = "Матчи экспресса #${express.expId}"
        textSize = 10f
        setPadding(dp(8), dp(6), dp(8), dp(6))
        gravity = Gravity.START
        setBackgroundColor(Color.parseColor(COLOR_MATCH_HEADER_BG))
        setTextColor(Color.parseColor("#F0B90B"))
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
        tag = "match_header_${express.expId}"
    })

    views.add(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
        setBackgroundColor(Color.parseColor(COLOR_MATCH_HEADER_BG))
        tag = "match_subheader_${express.expId}"
        addView(matchHeaderTv("Команда 1", 100))
        addView(matchHeaderTv("Команда 2", 100))
        addView(matchHeaderTv("Счет", 80))
        addView(matchHeaderTv("Кэф", 50))
        addView(matchHeaderTv("Тип", 110))
    })

    for (match in express.matches) {
        val typeShort: String = when (match.type) {
            924 -> "1X"
            927 -> "Ф1(+1.5)"
            928 -> "Ф2(+1.5)"
            else -> "Т${match.type}"
        }
        val scoreText: String = "${match.sh}:${match.sa}"
        val kfText: String = String.format("%.2f", match.startkf)
        val mc: String = if (match.isWin) COLOR_GREEN else COLOR_RED
        val resultText: String = if (match.isWin) "✓ Зашел" else "✗ Мимо"
        val ligaInfoText: String = "${match.liganame.take(40)} | $resultText"

        views.add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor(COLOR_MATCH_BG))
            tag = "match_${match.matchId}"

            addView(matchDataTv(match.home.take(14), 100, COLOR_TEXT_PRIMARY, 10f, false))
            addView(matchDataTv(match.away.take(14), 100, COLOR_TEXT_PRIMARY, 10f, false))
            addView(matchDataTv(scoreText, 80, "#EAECEF", 11f, true))
            addView(matchDataTv(kfText, 50, "#F0B90B", 10f, false))
            addView(matchDataTv(typeShort, 110, "#848E9C", 10f, false))
        })

        views.add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor("#11161C"))
            tag = "match_info_${match.matchId}"
            addView(matchDataTv(ligaInfoText, totalWidth, mc, 9f, false))
        })
    }

    views.add(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(totalWidth), dp(2))
        setBackgroundColor(Color.parseColor(COLOR_GRID))
        tag = "sep_${express.expId}"
    })

    return views
}
    // ========== РАСКРЫТИЕ/СКРЫТИЕ ==========
    
    private fun toggleExpress(express: ExpressResult) {
        if (express.expId in expandedExpressIds) {
            expandedExpressIds.remove(express.expId)
            removeMatchRows(express.expId)
            updateExpressRowIndicator(express.expId, false)
        } else {
            expandedExpressIds.add(express.expId)
            insertMatchRows(express)
            updateExpressRowIndicator(express.expId, true)
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
        buildMatchDetailRows(express).forEachIndexed { i, v ->
            layoutDetailContent.addView(v, index + 1 + i)
        }
    }
    
    private fun removeMatchRows(expId: Int) {
        val toRemove = mutableListOf<View>()
        for (i in 0 until layoutDetailContent.childCount) {
            val tag = layoutDetailContent.getChildAt(i).tag as? String ?: continue
            if (tag.startsWith("match_header_$expId") ||
                tag.startsWith("match_subheader_$expId") ||
                tag.startsWith("match_") ||
                tag.startsWith("match_info_") ||
                tag == "sep_$expId") toRemove.add(layoutDetailContent.getChildAt(i))
        }
        toRemove.forEach { layoutDetailContent.removeView(it) }
    }
    
    private fun appendExpressRows(expresses: List<ExpressResult>) {
        if (expresses.isEmpty()) return
        layoutDetailTable.visibility = View.VISIBLE
        if (currentPage == 0) layoutDetailContent.addView(buildHeaderRow())
        expresses.forEach { express ->
            layoutDetailContent.addView(buildExpressRow(express))
            if (express.expId in expandedExpressIds) insertMatchRows(express)
        }
    }
    
    // ========== ФАБРИКИ ==========
    
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
    
    // matchDataTv(text: String, w: Int, color: String, size: Float, bold: Boolean = false)
    private fun matchDataTv(text: String, w: Int, color: String, size: Float = 10f, bold: Boolean = false) = TextView(this).apply {
        this.text = text; this.textSize = size; setPadding(dp(4), dp(3), dp(4), dp(3))
        gravity = Gravity.CENTER; setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor(color))
        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(w), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    
    // ========== ЗАГРУЗКА ==========
    
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
                
                val analytics = withContext(Dispatchers.IO) { analyticsEngine.calculateAnalytics() }
                allExpressResults = ((analytics["allExpresses"] as? List<ExpressResult>) ?: emptyList())
                    .sortedByDescending { it.dateTime }
                
                resetPagination()
                loadExpressPage()
                startScoreUpdates()
                
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
                    val analytics = withContext(Dispatchers.IO) { analyticsEngine.calculateAnalytics() }
                    val newResults = ((analytics["allExpresses"] as? List<ExpressResult>) ?: emptyList())
                        .sortedByDescending { it.dateTime }
                    if (newResults.isNotEmpty()) {
                        allExpressResults = newResults
                        resetPagination()
                        loadExpressPage()
                    }
                }
            } catch (e: Exception) {
                addLog("Ошибка refreshData: ${e.message}")
            }
        }
    }
}