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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity() {
    
    private lateinit var scrollView: ScrollView
    private lateinit var layoutDetailContent: LinearLayout
    private lateinit var btnAnalytics: Button
    private lateinit var tvDetailTitle: TextView
    private lateinit var layoutDetailTable: View
    private lateinit var database: AppDatabase
    private lateinit var analyticsEngine: AnalyticsEngine
    
    private var allExpressResults = listOf<ExpressResult>()
    
    // Пагинация
    private val PAGE_SIZE = 30
    private var currentPage = 0
    private var isLoading = false
    private var allPagesLoaded = false
    
    // Раскрытые экспрессы
    private val expandedExpressIds = mutableSetOf<Int>()
    
    private val LIVE_HOURS = 2L
    
    companion object {
        private const val TAG = "MainActivity"
        private const val COLOR_BG = "#0B0E11"
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
        tvDetailTitle = findViewById(R.id.tv_detail_title)
        layoutDetailContent = findViewById(R.id.layout_detail_content)
        btnAnalytics = findViewById(R.id.btn_analytics)
        
        database = AppDatabase.getDatabase(this)
        analyticsEngine = AnalyticsEngine(database)
        
        btnAnalytics.setOnClickListener {
            val intent = Intent(this, AnalyticsActivity::class.java)
            startActivity(intent)
        }
        
        // Подгрузка при скролле
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            if (!isLoading && !allPagesLoaded) {
                val scrollViewChild = scrollView.getChildAt(0) as? ViewGroup
                if (scrollViewChild != null) {
                    val scrollY = scrollView.scrollY
                    val totalHeight = scrollViewChild.height - scrollView.height
                    if (totalHeight > 0 && scrollY >= totalHeight - 200) {
                        loadNextPage()
                    }
                }
            }
        }
        
        loadData()
    }
    
    private fun isLive(express: ExpressResult): Boolean {
        val now = LocalDateTime.now()
        val ageHours = ChronoUnit.HOURS.between(express.dateTime, now)
        return ageHours < LIVE_HOURS
    }
    
    private fun getRowBackground(express: ExpressResult): String {
        return if (isLive(express)) COLOR_LIVE_BG else {
            if (express.isWin) "#0A2317" else "#2B0F14"
        }
    }
    
    private fun getStatusColor(express: ExpressResult): String {
        return if (isLive(express)) COLOR_GOLD else {
            if (express.isWin) COLOR_GREEN else COLOR_RED
        }
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
                    if (start >= allExpressResults.size) return@withContext emptyList<ExpressResult>()
                    allExpressResults.subList(start, end).toList()
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
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки страницы", e)
            } finally {
                isLoading = false
            }
        }
    }
    
    private fun loadNextPage() {
        if (!isLoading && !allPagesLoaded) loadExpressPage()
    }
    
    // ========== ПОСТРОЕНИЕ ТАБЛИЦЫ ==========
    
    private fun buildHeaderRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
        
        val dateStr = express.dateTime.format(DateTimeFormatter.ofPattern("dd.MM"))
        val timeStr = express.dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        val dateTimeStr = "$dateStr $timeStr"
        
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
        
        val matchHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor(COLOR_MATCH_HEADER_BG))
            tag = "match_header_${express.expId}"
        }
        
        matchHeader.addView(matchHeaderTv("m_id", 85))
        matchHeader.addView(matchHeaderTv("Команда 1", 120))
        matchHeader.addView(matchHeaderTv("Команда 2", 120))
        matchHeader.addView(matchHeaderTv("Счет", 55))
        matchHeader.addView(matchHeaderTv("Тип ставки", 110))
        matchHeader.addView(matchHeaderTv("Лига", 110))
        matchHeader.addView(matchHeaderTv("Итог", 65))
        
        views.add(matchHeader)
        
        for (match in express.matches) {
            val matchRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(Color.parseColor(COLOR_MATCH_BG))
                tag = "match_${match.matchId}"
            }
            
            val mc = if (match.isWin) COLOR_GREEN else COLOR_RED
            val typeFull = when (match.type) {
                924 -> "1Х (не проиграли)"
                927 -> "Фора 1 (+1.5)"
                928 -> "Фора 2 (+1.5)"
                else -> "Тип ${match.type}"
            }
            
            matchRow.addView(matchDataTv("${match.matchId}", 85, mc, 9f))
            matchRow.addView(matchDataTv(match.home.take(16), 120, COLOR_TEXT_PRIMARY, 9f))
            matchRow.addView(matchDataTv(match.away.take(16), 120, COLOR_TEXT_PRIMARY, 9f))
            matchRow.addView(matchDataTv("${match.sh}:${match.sa}", 55, "#EAECEF", 10f, bold = true))
            matchRow.addView(matchDataTv(typeFull, 110, "#848E9C", 9f))
            matchRow.addView(matchDataTv(match.liganame.take(15), 110, "#848E9C", 9f))
            matchRow.addView(matchDataTv(if (match.isWin) "✓ Зашел" else "✗ Мимо", 65, mc, 10f, bold = true))
            
            views.add(matchRow)
        }
        
        val separator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
            setBackgroundColor(Color.parseColor(COLOR_GRID))
            tag = "sep_${express.expId}"
        }
        views.add(separator)
        
        return views
    }
    
    // ========== РАСКРЫТИЕ/СКРЫТИЕ ==========
    
    private fun toggleExpress(express: ExpressResult) {
        val isExpanded = express.expId in expandedExpressIds
        
        if (isExpanded) {
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
        val expressRow = layoutDetailContent.findViewWithTag<LinearLayout>("express_$expId")
        if (expressRow != null && expressRow.childCount > 0) {
            val firstChild = expressRow.getChildAt(0) as? TextView
            firstChild?.let {
                val text = it.text.toString()
                val newText = if (expanded) text.replace("▶", "▼") else text.replace("▼", "▶")
                it.text = newText
            }
        }
    }
    
    private fun insertMatchRows(express: ExpressResult) {
        val expressRow = layoutDetailContent.findViewWithTag<LinearLayout>("express_${express.expId}")
        if (expressRow == null) return
        
        val index = layoutDetailContent.indexOfChild(expressRow)
        if (index == -1) return
        
        val matchViews = buildMatchDetailRows(express)
        var insertIndex = index + 1
        
        for (view in matchViews) {
            layoutDetailContent.addView(view, insertIndex)
            insertIndex++
        }
    }
    
    private fun removeMatchRows(expId: Int) {
        val toRemove = mutableListOf<View>()
        
        for (i in 0 until layoutDetailContent.childCount) {
            val child = layoutDetailContent.getChildAt(i)
            val tag = child.tag as? String ?: continue
            if (tag.startsWith("match_header_$expId") || 
                tag.startsWith("match_") ||
                tag == "sep_$expId") {
                toRemove.add(child)
            }
        }
        
        for (view in toRemove) {
            layoutDetailContent.removeView(view)
        }
    }
    
    private fun appendExpressRows(expresses: List<ExpressResult>) {
        if (expresses.isEmpty()) return
        
        layoutDetailTable.visibility = View.VISIBLE
        
        if (currentPage == 0) {
            tvDetailTitle.text = "Экспрессы (загружено ${expresses.size})"
            layoutDetailContent.addView(buildHeaderRow())
        }
        
        for (express in expresses) {
            layoutDetailContent.addView(buildExpressRow(express))
            if (express.expId in expandedExpressIds) {
                insertMatchRows(express)
            }
        }
    }
    
    // ========== ФАБРИКИ ==========
    
    private fun headerTv(text: String, widthDp: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setPadding(dp(4), dp(6), dp(4), dp(6))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#2B3139"))
            setTextColor(Color.parseColor("#EAECEF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    
    private fun matchHeaderTv(text: String, widthDp: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 10f
            setPadding(dp(4), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(COLOR_MATCH_HEADER_BG))
            setTextColor(Color.parseColor("#5E6673"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    
    private fun dataTv(text: String, widthDp: Int, bg: String, color: String, bold: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 10f
            setPadding(dp(4), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(bg))
            setTextColor(Color.parseColor(color))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    
    private fun matchDataTv(text: String, widthDp: Int, color: String, size: Float, bold: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(dp(4), dp(3), dp(4), dp(3))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(COLOR_MATCH_BG))
            setTextColor(Color.parseColor(color))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    
    // ========== ЗАГРУЗКА ==========
    
    private fun loadData() {
        lifecycleScope.launch {
            try {
                tvDetailTitle.text = "Загрузка..."
                
                val analytics = withContext(Dispatchers.IO) { analyticsEngine.calculateAnalytics() }
                
                allExpressResults = ((analytics["allExpresses"] as? List<ExpressResult>) ?: emptyList())
                    .sortedByDescending { it.dateTime }
                
                resetPagination()
                loadExpressPage()
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки", e)
                tvDetailTitle.text = "Ошибка загрузки"
            }
        }
    }
}