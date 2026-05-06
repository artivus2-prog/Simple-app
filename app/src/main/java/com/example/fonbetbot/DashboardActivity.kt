// DashboardActivity.kt
package com.example.fonbetbot

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*

class DashboardActivity : AppCompatActivity() {
    
    private lateinit var scrollView: ScrollView
    private lateinit var pieChart: PieChart
    private lateinit var barChartDay: BarChart
    private lateinit var barChartHour: BarChart
    private lateinit var barChartLeague: HorizontalBarChart
    private lateinit var btnRefresh: Button
    private lateinit var btnBack: Button
    private lateinit var btnDateRange: Button
    private lateinit var tvDateRangeInfo: TextView
    private lateinit var spinnerLeague: Spinner
    private lateinit var layoutDetailTable: View
    private lateinit var tvDetailTitle: TextView
    private lateinit var layoutDetailContent: LinearLayout
    private lateinit var database: AppDatabase
    private lateinit var analyticsEngine: AnalyticsEngine
    
    private var allLeagueStats = listOf<LeagueStats>()
    private var allExpressResults = listOf<ExpressResult>()
    private var filteredExpressResults = listOf<ExpressResult>()
    private var selectedLeague: String? = null
    private var dateStart: LocalDate? = null
    private var dateEnd: LocalDate? = null
    
    // Пагинация
    private val PAGE_SIZE = 30
    private var currentPage = 0
    private var isLoading = false
    private var allPagesLoaded = false
    
    // Подсветка "живых" экспрессов (не старше N часов)
    private val LIVE_HOURS = 3L
    
    companion object {
        private const val TAG = "DashboardActivity"
        private const val COLOR_BG = "#0B0E11"
        private const val COLOR_CARD = "#1E2329"
        private const val COLOR_GOLD = "#F0B90B"
        private const val COLOR_GREEN = "#03A66D"
        private const val COLOR_RED = "#CF304A"
        private const val COLOR_TEXT_PRIMARY = "#EAECEF"
        private const val COLOR_TEXT_SECONDARY = "#848E9C"
        private const val COLOR_GRID = "#2B3139"
        // Цвет для "живых" экспрессов — приглушённый жёлтый
        private const val COLOR_LIVE_BG = "#2B2510"
        private const val COLOR_LIVE_BORDER = "#F0B90B"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        
        scrollView = findViewById(R.id.scroll_view)
        pieChart = findViewById(R.id.pie_chart)
        barChartDay = findViewById(R.id.bar_chart_day)
        barChartHour = findViewById(R.id.bar_chart_hour)
        barChartLeague = findViewById(R.id.bar_chart_league)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnBack = findViewById(R.id.btn_back)
        btnDateRange = findViewById(R.id.btn_date_range)
        tvDateRangeInfo = findViewById(R.id.tv_date_range_info)
        spinnerLeague = findViewById(R.id.spinner_league)
        layoutDetailTable = findViewById(R.id.layout_detail_table)
        tvDetailTitle = findViewById(R.id.tv_detail_title)
        layoutDetailContent = findViewById(R.id.layout_detail_content)
        
        // Скрываем сводку
        val tvStats = findViewById<TextView>(R.id.tv_stats)
        tvStats.visibility = View.GONE
        val summaryCard = tvStats.parent.parent as? View
        summaryCard?.visibility = View.GONE
        
        // Скрываем навигацию по неделям
        val layoutWeekNav = findViewById<LinearLayout>(R.id.layout_week_nav)
        layoutWeekNav.visibility = View.GONE
        val btnPrevWeek = findViewById<Button>(R.id.btn_prev_week)
        val btnNextWeek = findViewById<Button>(R.id.btn_next_week)
        val tvWeekInfo = findViewById<TextView>(R.id.tv_week_info)
        btnPrevWeek.visibility = View.GONE
        btnNextWeek.visibility = View.GONE
        tvWeekInfo.visibility = View.GONE
        
        database = AppDatabase.getDatabase(this)
        analyticsEngine = AnalyticsEngine(database)
        
        initChartStyles()
        
        btnRefresh.setOnClickListener { loadAnalytics() }
        btnBack.setOnClickListener { finish() }
        btnDateRange.setOnClickListener { showDateRangePicker() }
        
        spinnerLeague.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLeague = if (position > 0) allLeagueStats[position - 1].liganame else null
                applyFilters()
                refreshAllCharts()
                resetPagination()
                loadExpressPage()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        barChartDay.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                e?.let { entry ->
                    if (entry.x.toInt() in 0..6) showExpressesForDay(DayOfWeek.entries[entry.x.toInt()])
                }
            }
            override fun onNothingSelected() {}
        })
        
        barChartHour.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                e?.let { entry -> showExpressesForHour(entry.x.toInt()) }
            }
            override fun onNothingSelected() {}
        })
        
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
        
        updateDateRangeInfo()
        loadAnalytics()
    }
    
    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==========
    
    /**
     * Проверяет, является ли экспресс "живым" (не старше LIVE_HOURS часов от текущего времени)
     */
    private fun isLive(express: ExpressResult): Boolean {
        val now = LocalDateTime.now()
        val ageHours = ChronoUnit.HOURS.between(express.dateTime, now)
        return ageHours < LIVE_HOURS
    }
    
    /**
     * Возвращает цвет фона для экспресса в зависимости от его "живости"
     */
    private fun getRowBackground(express: ExpressResult): String {
        return if (isLive(express)) COLOR_LIVE_BG else {
            if (express.isWin) "#0A2317" else "#2B0F14"
        }
    }
    
    /**
     * Возвращает цвет текста статуса
     */
    private fun getStatusColor(express: ExpressResult): String {
        return if (isLive(express)) COLOR_GOLD else {
            if (express.isWin) COLOR_GREEN else COLOR_RED
        }
    }
    
    private fun initChartStyles() {
        for (chart in listOf(pieChart, barChartDay, barChartHour, barChartLeague)) {
            when (chart) {
                is PieChart -> applyPieChartStyle(chart)
                is BarChart -> applyBarChartStyle(chart)
                is HorizontalBarChart -> applyHorizontalBarChartStyle(chart)
            }
        }
    }
    
    private fun applyPieChartStyle(chart: PieChart) {
        chart.setBackgroundColor(Color.parseColor(COLOR_CARD))
        chart.setNoDataText("Нет данных")
        chart.setNoDataTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
        chart.description.isEnabled = false
        chart.isDrawHoleEnabled = true
        chart.holeRadius = 55f
        chart.setHoleColor(Color.parseColor(COLOR_CARD))
        chart.setTransparentCircleColor(Color.parseColor(COLOR_GRID))
        chart.setTransparentCircleAlpha(60)
        chart.setTransparentCircleRadius(58f)
        
        val legend = chart.legend
        legend.textColor = Color.parseColor(COLOR_TEXT_PRIMARY)
        legend.textSize = 12f
        legend.verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
        legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
        legend.orientation = Legend.LegendOrientation.HORIZONTAL
        legend.setDrawInside(false)
        
        chart.setCenterTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
        chart.setCenterTextSize(16f)
    }
    
    private fun applyBarChartStyle(chart: BarChart) {
        chart.setBackgroundColor(Color.parseColor(COLOR_CARD))
        chart.setNoDataText("Нет данных")
        chart.setNoDataTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
        chart.description.isEnabled = false
        
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = Color.parseColor(COLOR_TEXT_SECONDARY)
            textSize = 11f
            setDrawGridLines(false)
            setDrawAxisLine(true)
            axisLineColor = Color.parseColor(COLOR_GRID)
            axisLineWidth = 1f
            granularity = 1f
        }
        
        chart.axisLeft.apply {
            textColor = Color.parseColor(COLOR_TEXT_SECONDARY)
            textSize = 10f
            axisMinimum = 0f
            axisMaximum = 100f
            setDrawGridLines(true)
            gridColor = Color.parseColor(COLOR_GRID)
            gridLineWidth = 0.5f
            setDrawAxisLine(true)
            axisLineColor = Color.parseColor(COLOR_GRID)
        }
        
        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = false
    }
    
    private fun applyHorizontalBarChartStyle(chart: HorizontalBarChart) {
        chart.setBackgroundColor(Color.parseColor(COLOR_CARD))
        chart.setNoDataText("Нет данных")
        chart.setNoDataTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
        chart.description.isEnabled = false
        
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = Color.parseColor(COLOR_TEXT_SECONDARY)
            textSize = 10f
            setDrawGridLines(true)
            gridColor = Color.parseColor(COLOR_GRID)
            gridLineWidth = 0.5f
            setDrawAxisLine(true)
            axisLineColor = Color.parseColor(COLOR_GRID)
            axisMinimum = 0f
            axisMaximum = 100f
            labelRotationAngle = -30f
        }
        
        chart.axisLeft.apply {
            textColor = Color.parseColor(COLOR_TEXT_PRIMARY)
            textSize = 10f
            setDrawGridLines(false)
            setDrawAxisLine(true)
            axisLineColor = Color.parseColor(COLOR_GRID)
        }
        
        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = false
    }
    
    private fun showDateRangePicker() {
        val selection: androidx.core.util.Pair<Long, Long>? = if (dateStart != null && dateEnd != null) {
            androidx.core.util.Pair(
                dateStart!!.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                dateEnd!!.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            )
        } else null
        
        MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Выберите период")
            .setSelection(selection)
            .build()
            .apply {
                addOnPositiveButtonClickListener { sel ->
                    dateStart = Instant.ofEpochMilli(sel.first).atZone(ZoneId.systemDefault()).toLocalDate()
                    dateEnd = Instant.ofEpochMilli(sel.second).atZone(ZoneId.systemDefault()).toLocalDate()
                    updateDateRangeInfo()
                    applyFilters()
                    refreshAllCharts()
                    resetPagination()
                    loadExpressPage()
                }
                show(supportFragmentManager, "date_range_picker")
            }
    }
    
    private fun updateDateRangeInfo() {
        if (dateStart != null && dateEnd != null) {
            tvDateRangeInfo.text = "${dateStart!!.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} - ${dateEnd!!.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}"
            btnDateRange.text = "📅 ${dateStart!!.format(DateTimeFormatter.ofPattern("dd.MM"))}-${dateEnd!!.format(DateTimeFormatter.ofPattern("dd.MM"))}"
        } else {
            tvDateRangeInfo.text = "Весь период"
            btnDateRange.text = "📅 Весь период"
        }
    }
    
    private fun applyFilters() {
        var filtered = allExpressResults
        if (dateStart != null && dateEnd != null) {
            val start = dateStart!!.atStartOfDay()
            val end = dateEnd!!.atTime(23, 59, 59)
            filtered = filtered.filter { e -> val d = e.dateTime; !d.isBefore(start) && !d.isAfter(end) }
        }
        if (selectedLeague != null) {
            filtered = filtered.filter { e -> e.matches.any { m -> m.liganame == selectedLeague } }
        }
        // Сортируем от новых к старым
        filteredExpressResults = filtered.sortedByDescending { it.dateTime }
        Log.d(TAG, "applyFilters: filtered count = ${filteredExpressResults.size}, all = ${allExpressResults.size}")
    }
    
    private fun refreshAllCharts() {
        val data = filteredExpressResults
        
        Log.d(TAG, "refreshAllCharts: data count = ${data.size}")
        refreshChartsForFiltered(data)
        
        val leagueMatches = data.flatMap { it.matches }
        val leagueMap = leagueMatches.groupBy { it.liganame }
        val leagueStatsList = leagueMap.map { (liga, matches) ->
            val wins = matches.count { it.isWin }
            val total = matches.size
            LeagueStats(liga, total, wins, if (total > 0) (wins.toDouble() / total) * 100 else 0.0)
        }.sortedByDescending { it.total }.take(15)
        
        if (leagueStatsList.isNotEmpty()) {
            setupLeagueBarChart(leagueStatsList)
        } else {
            barChartLeague.clear()
            barChartLeague.invalidate()
        }
    }
    
    private fun refreshChartsForFiltered(filtered: List<ExpressResult>) {
        Log.d(TAG, "refreshChartsForFiltered: filtered count = ${filtered.size}")
        
        val total = filtered.size
        val wins = filtered.count { it.isWin }
        val losses = total - wins
        val rate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
        
        setupPieChart(AnalyticsSummary(total, wins, losses, rate, ""))
        
        val byDay = LinkedHashMap<DayOfWeek, Pair<Int, Int>>()
        for (d in DayOfWeek.entries) {
            val ex = filtered.filter { it.dateTime.dayOfWeek == d }
            byDay[d] = Pair(ex.count { it.isWin }, ex.size)
        }
        setupDayBarChart(byDay)
        
        val byHour = TreeMap<Int, Pair<Int, Int>>()
        for (h in 0..23) {
            val ex = filtered.filter { it.dateTime.hour == h }
            if (ex.isNotEmpty()) byHour[h] = Pair(ex.count { it.isWin }, ex.size)
        }
        if (byHour.isNotEmpty()) {
            setupHourBarChart(byHour)
        } else {
            barChartHour.clear()
            barChartHour.invalidate()
        }
    }
    
    // ========== ПАГИНАЦИЯ ==========
    
    private fun resetPagination() {
        currentPage = 0
        allPagesLoaded = false
        layoutDetailContent.removeAllViews()
        layoutDetailTable.visibility = View.GONE
    }
    
    private fun loadExpressPage() {
        if (isLoading || allPagesLoaded) return
        isLoading = true
        
        lifecycleScope.launch {
            try {
                val pageData = withContext(Dispatchers.IO) {
                    val start = currentPage * PAGE_SIZE
                    val end = minOf(start + PAGE_SIZE, filteredExpressResults.size)
                    
                    if (start >= filteredExpressResults.size) {
                        return@withContext emptyList<ExpressResult>()
                    }
                    
                    // Данные уже отсортированы в applyFilters() от новых к старым
                    filteredExpressResults.subList(start, end).toList()
                }
                
                if (pageData.isEmpty()) {
                    allPagesLoaded = true
                    isLoading = false
                    return@launch
                }
                
                appendExpressRows(pageData)
                currentPage++
                
                if (currentPage * PAGE_SIZE >= filteredExpressResults.size) {
                    allPagesLoaded = true
                    if (filteredExpressResults.size > PAGE_SIZE) {
                        val liveCount = filteredExpressResults.count { isLive(it) }
                        appendEndMessage("Все экспрессы загружены (${filteredExpressResults.size}) | Активных: $liveCount")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки страницы", e)
            } finally {
                isLoading = false
            }
        }
    }
    
    private fun loadNextPage() {
        if (!isLoading && !allPagesLoaded) {
            loadExpressPage()
        }
    }
    
    private fun buildHeaderRow(): LinearLayout {
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        headerRow.addView(headerTv("ID", 55))
        headerRow.addView(headerTv("Дата", 75))
        headerRow.addView(headerTv("Время", 50))
        headerRow.addView(headerTv("Статус", 90))
        headerRow.addView(headerTv("М", 35))
        headerRow.addView(headerTv("Лиги", 180))
        headerRow.addView(headerTv("Счет", 60))
        headerRow.addView(headerTv("Тип", 100))
        headerRow.addView(headerTv("Итог", 55))
        
        return headerRow
    }
    
    private fun buildExpressRow(express: ExpressResult): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
        
        val ligaNames = express.matches.map { it.liganame.take(25) }.distinct().joinToString(", ")
        val scores = express.matches.joinToString(",") { "${it.sh}:${it.sa}" }
        val types = express.matches.map { m ->
            when (m.type) { 924 -> "1X"; 927 -> "Ф1"; 928 -> "Ф2"; else -> "Т${m.type}" }
        }.joinToString(",")
        val results = express.matches.joinToString("") { if (it.isWin) "✓" else "✗" }
        
        row.addView(dataTv("#${express.expId}", 55, itemBg, itemColor))
        row.addView(dataTv(dateStr, 75, itemBg, itemColor))
        row.addView(dataTv(timeStr, 50, itemBg, itemColor))
        row.addView(dataTv(statusText, 90, itemBg, itemColor, bold = live))
        row.addView(dataTv("${express.matches.size}", 35, itemBg, itemColor))
        row.addView(dataTv(ligaNames, 180, itemBg, "#848E9C"))
        row.addView(dataTv(scores, 60, itemBg, itemColor))
        row.addView(dataTv(types, 100, itemBg, "#848E9C"))
        row.addView(dataTv(results, 55, itemBg, itemColor))
        
        return row
    }
    
    private fun appendExpressRows(expresses: List<ExpressResult>) {
        if (expresses.isEmpty()) return
        
        layoutDetailTable.visibility = View.VISIBLE
        
        // Если первая страница — добавляем заголовки
        if (currentPage == 0) {
            tvDetailTitle.text = "Экспрессы (загружено ${expresses.size})"
            layoutDetailContent.addView(buildHeaderRow())
        } else {
            tvDetailTitle.text = "Экспрессы (загружено ${currentPage * PAGE_SIZE + expresses.size})"
        }
        
        // Добавляем строки данных
        for (express in expresses) {
            layoutDetailContent.addView(buildExpressRow(express))
        }
    }
    
    private fun appendEndMessage(text: String) {
        val msgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(8), 0, dp(8))
        }
        msgRow.addView(dataTv(text, ViewGroup.LayoutParams.WRAP_CONTENT, "#0B0E11", "#5E6673"))
        layoutDetailContent.addView(msgRow)
    }
    
    private fun showExpressesForDay(day: DayOfWeek) {
        val dayNames = mapOf(
            DayOfWeek.MONDAY to "Пн", DayOfWeek.TUESDAY to "Вт",
            DayOfWeek.WEDNESDAY to "Ср", DayOfWeek.THURSDAY to "Чт",
            DayOfWeek.FRIDAY to "Пт", DayOfWeek.SATURDAY to "Сб",
            DayOfWeek.SUNDAY to "Вс"
        )
        val filtered = filteredExpressResults.filter { it.dateTime.dayOfWeek == day }
        showFilteredExpresses(filtered, "День: ${dayNames[day]}")
    }
    
    private fun showExpressesForHour(hour: Int) {
        val filtered = filteredExpressResults.filter { it.dateTime.hour == hour }
        showFilteredExpresses(filtered, "Час: ${String.format("%02d", hour)}:00")
    }
    
    private fun showFilteredExpresses(expresses: List<ExpressResult>, title: String) {
        layoutDetailTable.visibility = View.VISIBLE
        tvDetailTitle.text = "$title (${expresses.size})"
        layoutDetailContent.removeAllViews()
        
        if (expresses.isEmpty()) {
            layoutDetailContent.addView(tv("Нет данных", 14f, Gravity.CENTER, pad = 16))
            return
        }
        
        // Заголовки
        layoutDetailContent.addView(buildHeaderRow())
        
        // Сортируем от новых к старым
        val sorted = expresses.sortedByDescending { it.dateTime }
        
        // Все данные для этого фильтра
        for (express in sorted) {
            layoutDetailContent.addView(buildExpressRow(express))
        }
    }
    
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
            layoutParams = LinearLayout.LayoutParams(
                dp(widthDp),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
            layoutParams = LinearLayout.LayoutParams(
                dp(widthDp),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }
    
    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
    
    private fun tv(text: String, size: Float, gravity: Int = Gravity.START, color: String? = null, bold: Boolean = false, bg: String? = null, pad: Int = 2): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(dp(pad), dp(2), dp(pad), dp(2))
            this.gravity = gravity
            if (color != null) setTextColor(Color.parseColor(color))
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            if (bg != null) setBackgroundColor(Color.parseColor(bg))
        }
    }
    
    private fun loadAnalytics() {
        lifecycleScope.launch {
            try {
                btnRefresh.isEnabled = false
                btnRefresh.text = "Загрузка..."
                
                val analytics = withContext(Dispatchers.IO) { analyticsEngine.calculateAnalytics() }
                
                allExpressResults = (analytics["allExpresses"] as? List<ExpressResult>) ?: emptyList()
                Log.d(TAG, "allExpresses count = ${allExpressResults.size}")
                
                applyFilters()
                
                allLeagueStats = (analytics["byLeague"] as? List<LeagueStats>) ?: emptyList()
                spinnerLeague.adapter = ArrayAdapter(
                    this@DashboardActivity,
                    android.R.layout.simple_spinner_item,
                    listOf("Все лиги") + allLeagueStats.map { "${it.liganame} (${it.total})" }
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                
                refreshAllCharts()
                
                // Загружаем первую страницу экспрессов
                resetPagination()
                loadExpressPage()
                
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки аналитики", e)
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
            }
        }
    }
    
    private fun setupPieChart(t: AnalyticsSummary) {
        if (t.totalExpress == 0) {
            pieChart.clear()
            pieChart.centerText = "Нет данных"
            pieChart.invalidate()
            return
        }
        
        val entries = mutableListOf<PieEntry>()
        if (t.winExpress > 0) entries.add(PieEntry(t.winExpress.toFloat(), "Выигрыши"))
        if (t.loseExpress > 0) entries.add(PieEntry(t.loseExpress.toFloat(), "Проигрыши"))
        
        if (entries.isEmpty()) {
            pieChart.clear()
            pieChart.centerText = "Нет данных"
            pieChart.invalidate()
            return
        }
        
        val dataSet = PieDataSet(entries, "").apply {
            colors = listOf(
                Color.parseColor(COLOR_GREEN),
                Color.parseColor(COLOR_RED)
            )
            valueTextSize = 14f
            valueTextColor = Color.parseColor(COLOR_TEXT_PRIMARY)
            sliceSpace = 4f
            selectionShift = 8f
        }
        
        pieChart.data = PieData(dataSet).apply {
            setValueFormatter(PercentFormatter(pieChart))
        }
        pieChart.centerText = "${String.format("%.1f", t.winRate)}%\nпроходимость"
        pieChart.animateY(1200)
        pieChart.invalidate()
    }
    
    private fun setupDayBarChart(d: Map<DayOfWeek, Pair<Int, Int>>) {
        val entries = mutableListOf<BarEntry>()
        val colors = mutableListOf<Int>()
        
        DayOfWeek.entries.forEachIndexed { i, day ->
            val stats = d[day]
            val w = stats?.first ?: 0
            val t = stats?.second ?: 0
            val r = if (t > 0) (w.toFloat() / t) * 100 else 0f
            entries.add(BarEntry(i.toFloat(), r))
            colors.add(
                when {
                    t == 0 -> Color.parseColor("#3A4048")
                    r >= 50 -> Color.parseColor(COLOR_GREEN)
                    else -> Color.parseColor(COLOR_RED)
                }
            )
        }
        
        val dataSet = BarDataSet(entries, "").apply {
            this.colors = colors
            valueTextSize = 11f
            valueTextColor = Color.parseColor(COLOR_TEXT_PRIMARY)
            setDrawValues(true)
        }
        
        barChartDay.data = BarData(dataSet).apply { barWidth = 0.65f }
        barChartDay.animateY(1000)
        barChartDay.invalidate()
    }
    
    private fun setupHourBarChart(h: TreeMap<Int, Pair<Int, Int>>) {
        val entries = mutableListOf<BarEntry>()
        val colors = mutableListOf<Int>()
        
        for ((hour, stats) in h) {
            val w = stats.first
            val t = stats.second
            val r = if (t > 0) (w.toFloat() / t) * 100 else 0f
            entries.add(BarEntry(hour.toFloat(), r))
            colors.add(
                when {
                    t == 0 -> Color.parseColor("#3A4048")
                    r >= 50 -> Color.parseColor(COLOR_GREEN)
                    else -> Color.parseColor(COLOR_RED)
                }
            )
        }
        
        val dataSet = BarDataSet(entries, "").apply {
            this.colors = colors
            valueTextSize = 10f
            valueTextColor = Color.parseColor(COLOR_TEXT_PRIMARY)
            setDrawValues(true)
        }
        
        barChartHour.data = BarData(dataSet).apply { barWidth = 0.7f }
        barChartHour.animateY(1000)
        barChartHour.invalidate()
    }
    
    private fun setupLeagueBarChart(ls: List<LeagueStats>) {
        val entries = mutableListOf<BarEntry>()
        
        ls.forEachIndexed { i, lg ->
            entries.add(BarEntry(i.toFloat(), lg.rate.toFloat()))
        }
        
        val gradientColors = mutableListOf<Int>()
        for (i in ls.indices) {
            val ratio = i.toFloat() / maxOf(ls.size - 1, 1)
            val r = (3 + (240 - 3) * ratio).toInt()
            val g = (166 + (184 - 166) * (1 - ratio)).toInt()
            val b = (109 + (11 - 109) * (1 - ratio)).toInt()
            gradientColors.add(Color.rgb(r, g, b))
        }
        
        val dataSet = BarDataSet(entries, "").apply {
            colors = gradientColors
            valueTextSize = 11f
            valueTextColor = Color.parseColor(COLOR_TEXT_PRIMARY)
            setDrawValues(true)
        }
        
        barChartLeague.data = BarData(dataSet).apply { barWidth = 0.7f }
        barChartLeague.animateY(500)
        barChartLeague.invalidate()
    }
}