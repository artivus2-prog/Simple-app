// DashboardActivity.kt — ИСПРАВЛЕННАЯ ВЕРСИЯ (без статусов, только данные)
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
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

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
    
    private var allExpressResults = listOf<ExpressResultSimplified>()
    private var filteredExpressResults = listOf<ExpressResultSimplified>()
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
    
    // Простые data-классы для графиков
    private data class LeagueStatsSimple(
        val liganame: String,
        val total: Int,
        val rate: Double
    )
    
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
        
        // Скрываем лишние элементы
        findViewById<TextView>(R.id.tv_stats).visibility = View.GONE
        findViewById<LinearLayout>(R.id.layout_week_nav).visibility = View.GONE
        findViewById<View>(R.id.layout_detail_table).visibility = View.GONE
        
        database = AppDatabase.getDatabase(this)
        analyticsEngine = AnalyticsEngine(database)
        
        initChartStyles()
        
        btnRefresh.setOnClickListener { loadAnalytics() }
        btnBack.setOnClickListener { finish() }
        btnDateRange.setOnClickListener { showDateRangePicker() }
        
        spinnerLeague.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLeague = if (position > 0) {
                    (spinnerLeague.adapter.getItem(position) as String).split(" (")[0]
                } else null
                applyFilters()
                refreshAllCharts()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        updateDateRangeInfo()
        loadAnalytics()
    }
    
    // ========== СТИЛИ ГРАФИКОВ ==========
    
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
        chart.setExtraOffsets(0f, 0f, 0f, 8f)
        
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
        }
        
        chart.axisLeft.apply {
            textColor = Color.parseColor("#EAECEF")
            textSize = 9f
            setDrawGridLines(false)
            setDrawAxisLine(true)
            axisLineColor = Color.parseColor(COLOR_GRID)
            setLabelCount(15, true)
        }
        
        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = false
        chart.extraLeftOffset = 10f
        chart.extraRightOffset = 10f
    }
    
    // ========== ФИЛЬТРЫ ==========
    
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
            filtered = filtered.filter { e -> !e.dateTime.isBefore(start) && !e.dateTime.isAfter(end) }
        }
        if (selectedLeague != null) {
            filtered = filtered.filter { e -> e.matches.any { m -> m.liganame == selectedLeague } }
        }
        filteredExpressResults = filtered.sortedByDescending { it.dateTime }
    }
    
    private fun refreshAllCharts() {
        if (filteredExpressResults.isEmpty()) {
            pieChart.clear()
            pieChart.centerText = "Нет данных"
            pieChart.invalidate()
            barChartDay.clear()
            barChartDay.invalidate()
            barChartHour.clear()
            barChartHour.invalidate()
            barChartLeague.clear()
            barChartLeague.invalidate()
            return
        }
        
        refreshChartsForFiltered(filteredExpressResults)
        
        // Собираем статистику по лигам
        val allMatches = filteredExpressResults.flatMap { it.matches }
        val leagueMap = allMatches.groupBy { it.liganame }
        val leagueStatsList = leagueMap.map { (liga, matches) ->
            LeagueStatsSimple(liga, matches.size, 0.0)
        }.sortedByDescending { it.total }.take(15)
        
        if (leagueStatsList.isNotEmpty()) {
            setupLeagueBarChart(leagueStatsList)
        }
    }
    
    private fun refreshChartsForFiltered(filtered: List<ExpressResultSimplified>) {
        val total = filtered.size
        if (total == 0) return
        
        // Круговая диаграмма: просто показываем общее количество
        setupPieChart(total)
        
        // По дням недели
        val byDay = LinkedHashMap<DayOfWeek, Int>()
        for (d in DayOfWeek.entries) {
            byDay[d] = filtered.count { it.dateTime.dayOfWeek == d }
        }
        setupDayBarChart(byDay)
        
        // По часам
        val byHour = TreeMap<Int, Int>()
        for (h in 0..23) {
            val count = filtered.count { it.dateTime.hour == h }
            if (count > 0) byHour[h] = count
        }
        if (byHour.isNotEmpty()) {
            setupHourBarChart(byHour)
        }
    }
    
    // ========== ЗАГРУЗКА ==========
    
    private fun loadAnalytics() {
        lifecycleScope.launch {
            try {
                btnRefresh.isEnabled = false
                btnRefresh.text = "Загрузка..."
                
                val analytics = withContext(Dispatchers.IO) { analyticsEngine.calculateAnalytics() }
                
                allExpressResults = (analytics["allExpresses"] as? List<ExpressResultSimplified>) ?: emptyList()
                applyFilters()
                
                // Заполняем спиннер лиг
                val allLeagues = allExpressResults
                    .flatMap { it.matches }
                    .map { it.liganame }
                    .distinct()
                    .sorted()
                
                spinnerLeague.adapter = ArrayAdapter(
                    this@DashboardActivity,
                    android.R.layout.simple_spinner_item,
                    listOf("Все лиги") + allLeagues
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                
                refreshAllCharts()
                
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить"
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки аналитики", e)
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить"
            }
        }
    }
    
    // ========== ГРАФИКИ ==========
    
    private fun setupPieChart(total: Int) {
        pieChart.clear()
        pieChart.data = PieData(
            PieDataSet(listOf(PieEntry(total.toFloat(), "Экспрессы")), "").apply {
                colors = listOf(Color.parseColor(COLOR_GREEN))
                valueTextSize = 14f
                valueTextColor = Color.parseColor(COLOR_TEXT_PRIMARY)
            }
        )
        pieChart.centerText = "$total\nэкспрессов"
        pieChart.animateY(800)
        pieChart.invalidate()
    }
    
    private fun setupDayBarChart(dayData: Map<DayOfWeek, Int>) {
        val entries = mutableListOf<BarEntry>()
        val colors = mutableListOf<Int>()
        val labels = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        
        DayOfWeek.entries.forEachIndexed { i, day ->
            val count = dayData[day] ?: 0
            entries.add(BarEntry(i.toFloat(), count.toFloat()))
            colors.add(if (count > 0) Color.parseColor(COLOR_GREEN) else Color.parseColor("#3A4048"))
        }
        
        barChartDay.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        barChartDay.xAxis.labelCount = 7
        
        barChartDay.data = BarData(
            BarDataSet(entries, "").apply {
                this.colors = colors
                valueTextSize = 10f
                valueTextColor = Color.parseColor(COLOR_TEXT_PRIMARY)
            }
        ).apply { barWidth = 0.65f }
        
        barChartDay.animateY(800)
        barChartDay.invalidate()
    }
    
    private fun setupHourBarChart(hourData: TreeMap<Int, Int>) {
        val entries = mutableListOf<BarEntry>()
        val colors = mutableListOf<Int>()
        
        for ((hour, count) in hourData) {
            entries.add(BarEntry(hour.toFloat(), count.toFloat()))
            colors.add(Color.parseColor(COLOR_GREEN))
        }
        
        barChartHour.data = BarData(
            BarDataSet(entries, "").apply {
                this.colors = colors
                valueTextSize = 9f
                valueTextColor = Color.parseColor(COLOR_TEXT_PRIMARY)
            }
        ).apply { barWidth = 0.7f }
        
        barChartHour.animateY(800)
        barChartHour.invalidate()
    }
    
    private fun setupLeagueBarChart(leagueStats: List<LeagueStatsSimple>) {
        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()
        
        leagueStats.forEachIndexed { i, lg ->
            entries.add(BarEntry(i.toFloat(), lg.total.toFloat()))
            labels.add(lg.liganame.take(20))
        }
        
        val gradientColors = mutableListOf<Int>()
        for (i in leagueStats.indices) {
            val ratio = i.toFloat() / maxOf(leagueStats.size - 1, 1)
            val r = (3 + (240 - 3) * ratio).toInt()
            val g = (166 + (184 - 166) * (1 - ratio)).toInt()
            val b = (109 + (11 - 109) * (1 - ratio)).toInt()
            gradientColors.add(Color.rgb(r, g, b))
        }
        
        barChartLeague.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            textSize = 9f
            textColor = Color.parseColor("#EAECEF")
            granularity = 1f
            labelCount = labels.size
        }
        
        barChartLeague.data = BarData(
            BarDataSet(entries, "").apply {
                colors = gradientColors
                valueTextSize = 9f
                valueTextColor = Color.parseColor("#EAECEF")
            }
        ).apply { barWidth = 0.7f }
        
        barChartLeague.animateY(800)
        barChartLeague.invalidate()
    }
}