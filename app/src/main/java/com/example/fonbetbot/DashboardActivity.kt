package com.example.fonbetbot

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
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
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*

class DashboardActivity : AppCompatActivity() {
    
    private lateinit var pieChart: PieChart
    private lateinit var barChartDay: BarChart
    private lateinit var barChartHour: BarChart
    private lateinit var barChartLeague: HorizontalBarChart
    private lateinit var btnRefresh: Button
    private lateinit var btnBack: Button
    private lateinit var btnDateRange: Button
    private lateinit var tvDateRangeInfo: TextView
    private lateinit var spinnerLeague: Spinner
    private lateinit var database: AppDatabase
    private lateinit var analyticsEngine: AnalyticsEngine
    
    private var allLeagueStats = listOf<LeagueStats>()
    private var allExpressResults = listOf<ExpressResult>()
    private var filteredExpressResults = listOf<ExpressResult>()
    private var selectedLeague: String? = null
    private var dateStart: LocalDate? = null
    private var dateEnd: LocalDate? = null
    
    companion object {
        private const val TAG = "DashboardActivity"
        private const val COLOR_CARD = "#1E2329"
        private const val COLOR_GREEN = "#03A66D"
        private const val COLOR_RED = "#CF304A"
        private const val COLOR_TEXT_PRIMARY = "#EAECEF"
        private const val COLOR_TEXT_SECONDARY = "#848E9C"
        private const val COLOR_GRID = "#2B3139"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        
        pieChart = findViewById(R.id.pie_chart)
        barChartDay = findViewById(R.id.bar_chart_day)
        barChartHour = findViewById(R.id.bar_chart_hour)
        barChartLeague = findViewById(R.id.bar_chart_league)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnBack = findViewById(R.id.btn_back)
        btnDateRange = findViewById(R.id.btn_date_range)
        tvDateRangeInfo = findViewById(R.id.tv_date_range_info)
        spinnerLeague = findViewById(R.id.spinner_league)
        
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
        
        // Скрываем таблицу
        val layoutDetailTable = findViewById<View>(R.id.layout_detail_table)
        layoutDetailTable.visibility = View.GONE
        
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
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        updateDateRangeInfo()
        loadAnalytics()
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
        chart.legend.apply {
            textColor = Color.parseColor(COLOR_TEXT_PRIMARY)
            textSize = 12f
            verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
            horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
            orientation = Legend.LegendOrientation.HORIZONTAL
            setDrawInside(false)
        }
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
        filteredExpressResults = filtered.sortedByDescending { it.dateTime }
    }
    
    private fun refreshAllCharts() {
        val data = filteredExpressResults
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
        val total = filtered.size
        val wins = filtered.count { it.isWin }
        val rate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
        
        setupPieChart(AnalyticsSummary(total, wins, total - wins, rate, ""))
        
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
    
    private fun loadAnalytics() {
        lifecycleScope.launch {
            try {
                btnRefresh.isEnabled = false
                btnRefresh.text = "Загрузка..."
                
                val analytics = withContext(Dispatchers.IO) { analyticsEngine.calculateAnalytics() }
                
                allExpressResults = (analytics["allExpresses"] as? List<ExpressResult>) ?: emptyList()
                applyFilters()
                
                allLeagueStats = (analytics["byLeague"] as? List<LeagueStats>) ?: emptyList()
                spinnerLeague.adapter = ArrayAdapter(
                    this@DashboardActivity,
                    android.R.layout.simple_spinner_item,
                    listOf("Все лиги") + allLeagueStats.map { "${it.liganame} (${it.total})" }
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                
                refreshAllCharts()
                
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки аналитики", e)
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
            }
        }
    }
    
    // ========== ГРАФИКИ ==========
    
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
            colors = listOf(Color.parseColor(COLOR_GREEN), Color.parseColor(COLOR_RED))
            valueTextSize = 14f
            valueTextColor = Color.parseColor(COLOR_TEXT_PRIMARY)
            sliceSpace = 4f
            selectionShift = 8f
        }
        
        pieChart.data = PieData(dataSet).apply { setValueFormatter(PercentFormatter(pieChart)) }
        pieChart.centerText = "${String.format("%.1f", t.winRate)}%\nпроходимость"
        pieChart.animateY(1200)
        pieChart.invalidate()
    }
    
    private fun setupDayBarChart(d: Map<DayOfWeek, Pair<Int, Int>>) {
        val entries = mutableListOf<BarEntry>()
        val colors = mutableListOf<Int>()
        
        DayOfWeek.entries.forEachIndexed { i, day ->
            val (w, t) = d[day] ?: (0 to 0)
            val r = if (t > 0) (w.toFloat() / t) * 100 else 0f
            entries.add(BarEntry(i.toFloat(), r))
            colors.add(when {
                t == 0 -> Color.parseColor("#3A4048")
                r >= 50 -> Color.parseColor(COLOR_GREEN)
                else -> Color.parseColor(COLOR_RED)
            })
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
            val (w, t) = stats
            val r = if (t > 0) (w.toFloat() / t) * 100 else 0f
            entries.add(BarEntry(hour.toFloat(), r))
            colors.add(when {
                r >= 50 -> Color.parseColor(COLOR_GREEN)
                else -> Color.parseColor(COLOR_RED)
            })
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
        barChartLeague.animateY(1000)
        barChartLeague.invalidate()
    }
}