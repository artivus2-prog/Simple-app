// DashboardActivity.kt
package com.example.fonbetbot

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*

class DashboardActivity : AppCompatActivity() {
    
    private lateinit var scrollView: ScrollView
    private lateinit var tvStats: TextView
    private lateinit var pieChart: PieChart
    private lateinit var barChartDay: BarChart
    private lateinit var barChartHour: BarChart
    private lateinit var barChartLeague: HorizontalBarChart
    private lateinit var btnRefresh: Button
    private lateinit var btnBack: Button
    private lateinit var btnPrevWeek: Button
    private lateinit var btnNextWeek: Button
    private lateinit var btnDateRange: Button
    private lateinit var tvWeekInfo: TextView
    private lateinit var tvDateRangeInfo: TextView
    private lateinit var layoutWeekNav: LinearLayout
    private lateinit var spinnerLeague: Spinner
    private lateinit var layoutDetailTable: LinearLayout
    private lateinit var tvDetailTitle: TextView
    private lateinit var layoutDetailContent: LinearLayout
    private lateinit var database: AppDatabase
    private lateinit var analyticsEngine: AnalyticsEngine
    
    private var allWeekStats = listOf<WeekStats>()
    private var currentWeekIndex = -1
    private var allLeagueStats = listOf<LeagueStats>()
    private var allExpressResults = listOf<ExpressResult>()
    private var filteredExpressResults = listOf<ExpressResult>()
    private var selectedLeague: String? = null
    
    // Период
    private var dateStart: LocalDate? = null
    private var dateEnd: LocalDate? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        
        scrollView = findViewById(R.id.scroll_view)
        tvStats = findViewById(R.id.tv_stats)
        pieChart = findViewById(R.id.pie_chart)
        barChartDay = findViewById(R.id.bar_chart_day)
        barChartHour = findViewById(R.id.bar_chart_hour)
        barChartLeague = findViewById(R.id.bar_chart_league)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnBack = findViewById(R.id.btn_back)
        btnPrevWeek = findViewById(R.id.btn_prev_week)
        btnNextWeek = findViewById(R.id.btn_next_week)
        btnDateRange = findViewById(R.id.btn_date_range)
        tvWeekInfo = findViewById(R.id.tv_week_info)
        tvDateRangeInfo = findViewById(R.id.tv_date_range_info)
        layoutWeekNav = findViewById(R.id.layout_week_nav)
        spinnerLeague = findViewById(R.id.spinner_league)
        layoutDetailTable = findViewById(R.id.layout_detail_table)
        tvDetailTitle = findViewById(R.id.tv_detail_title)
        layoutDetailContent = findViewById(R.id.layout_detail_content)
        
        database = AppDatabase.getDatabase(this)
        analyticsEngine = AnalyticsEngine(database)
        
        btnRefresh.setOnClickListener { loadAnalytics() }
        btnBack.setOnClickListener { finish() }
        btnPrevWeek.setOnClickListener { navigateWeek(-1) }
        btnNextWeek.setOnClickListener { navigateWeek(1) }
        
        btnDateRange.setOnClickListener { showDateRangePicker() }
        
        spinnerLeague.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLeague = if (position > 0) allLeagueStats[position - 1].liganame else null
                applyFilters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        barChartDay.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                e?.let { entry ->
                    val dayIndex = entry.x.toInt()
                    if (dayIndex in 0..6) {
                        val day = DayOfWeek.entries[dayIndex]
                        showExpressesForDay(day)
                    }
                }
            }
            override fun onNothingSelected() {}
        })
        
        barChartHour.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                e?.let { entry ->
                    val hour = entry.x.toInt()
                    showExpressesForHour(hour)
                }
            }
            override fun onNothingSelected() {}
        })
        
        updateDateRangeInfo()
        loadAnalytics()
    }
    
    private fun showDateRangePicker() {
        val dateRangePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Выберите период")
            .setSelection(
                androidx.core.util.Pair(
                    dateStart?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
                    dateEnd?.atTime(23, 59, 59)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                )
            )
            .build()
        
        dateRangePicker.addOnPositiveButtonClickListener { selection ->
            dateStart = Instant.ofEpochMilli(selection.first)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            dateEnd = Instant.ofEpochMilli(selection.second)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            
            updateDateRangeInfo()
            applyFilters()
            refreshCharts()
        }
        
        dateRangePicker.show(supportFragmentManager, "date_range_picker")
    }
    
    private fun updateDateRangeInfo() {
        if (dateStart != null && dateEnd != null) {
            tvDateRangeInfo.text = "${dateStart!!.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} - ${dateEnd!!.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}"
            btnDateRange.text = "📅 ${dateStart!!.format(DateTimeFormatter.ofPattern("dd.MM"))} - ${dateEnd!!.format(DateTimeFormatter.ofPattern("dd.MM"))}"
        } else {
            tvDateRangeInfo.text = "Весь период"
            btnDateRange.text = "📅 Весь период"
        }
    }
    
    private fun applyFilters() {
        // Применяем фильтр по датам
        var filtered = allExpressResults
        
        if (dateStart != null && dateEnd != null) {
            val startDateTime = dateStart!!.atStartOfDay()
            val endDateTime = dateEnd!!.atTime(23, 59, 59)
            
            filtered = filtered.filter { express ->
                val expressDate = express.dateTime.toLocalDate().atStartOfDay()
                !expressDate.isBefore(startDateTime) && !expressDate.isAfter(endDateTime)
            }
        }
        
        // Применяем фильтр по лиге
        if (selectedLeague != null) {
            filtered = filtered.filter { express ->
                express.matches.any { match -> match.liganame == selectedLeague }
            }
        }
        
        filteredExpressResults = filtered
    }
    
    private fun refreshCharts() {
        val filtered = filteredExpressResults
        
        // Пересчитываем статистику для отфильтрованных данных
        val totalExpress = filtered.size
        val winExpress = filtered.count { it.isWin }
        val loseExpress = totalExpress - winExpress
        val winRate = if (totalExpress > 0) (winExpress.toDouble() / totalExpress) * 100 else 0.0
        
        // Обновляем круговую диаграмму
        setupPieChart(AnalyticsSummary(totalExpress, winExpress, loseExpress, winRate, ""))
        
        // Обновляем гистограмму по дням
        val byDay = filtered.groupBy { it.dateTime.dayOfWeek }
        val dayStats = LinkedHashMap<DayOfWeek, Pair<Int, Int>>()
        for (day in DayOfWeek.entries) {
            val expresses = byDay[day] ?: emptyList()
            dayStats[day] = Pair(expresses.count { it.isWin }, expresses.size)
        }
        setupDayBarChart(dayStats)
        
        // Обновляем гистограмму по часам
        val byHour = filtered.groupBy { it.dateTime.hour }
        val hourStats = TreeMap<Int, Pair<Int, Int>>()
        for (hour in 0..23) {
            val expresses = byHour[hour] ?: emptyList()
            if (expresses.isNotEmpty()) {
                hourStats[hour] = Pair(expresses.count { it.isWin }, expresses.size)
            }
        }
        setupHourBarChart(hourStats)
        
        // Обновляем текстовую сводку
        updateSummaryText(filtered, winRate)
    }
    
    private fun updateSummaryText(filtered: List<ExpressResult>, winRate: Double) {
        val total = filtered.size
        val wins = filtered.count { it.isWin }
        val loses = total - wins
        
        tvStats.text = buildString {
            appendLine("=== ПЕРИОД: ${tvDateRangeInfo.text} ===")
            appendLine("Всего: $total | Выигрыши: $wins | Проигрыши: $loses | Проход: ${String.format("%.1f", winRate)}%")
            appendLine()
            
            if (total > 0) {
                val byDay = filtered.groupBy { it.dateTime.dayOfWeek }
                val dayNames = mapOf(
                    DayOfWeek.MONDAY to "Пн", DayOfWeek.TUESDAY to "Вт",
                    DayOfWeek.WEDNESDAY to "Ср", DayOfWeek.THURSDAY to "Чт",
                    DayOfWeek.FRIDAY to "Пт", DayOfWeek.SATURDAY to "Сб",
                    DayOfWeek.SUNDAY to "Вс"
                )
                appendLine("--- ПО ДНЯМ ---")
                for (day in DayOfWeek.entries) {
                    val expresses = byDay[day] ?: emptyList()
                    val w = expresses.count { it.isWin }
                    val t = expresses.size
                    if (t > 0) {
                        val r = (w.toDouble() / t) * 100
                        appendLine("${dayNames[day]}: $w/$t (${String.format("%.1f", r)}%)")
                    }
                }
                appendLine()
                
                val byHour = filtered.groupBy { it.dateTime.hour }
                appendLine("--- ПО ЧАСАМ ---")
                for (hour in 0..23) {
                    val expresses = byHour[hour] ?: emptyList()
                    val w = expresses.count { it.isWin }
                    val t = expresses.size
                    if (t > 0) {
                        val r = (w.toDouble() / t) * 100
                        appendLine("${String.format("%02d", hour)}:00: $w/$t (${String.format("%.1f", r)}%)")
                    }
                }
                appendLine()
                
                val byMonth = filtered.groupBy { it.dateTime.month }
                val monthNames = mapOf(
                    1 to "Январь", 2 to "Февраль", 3 to "Март", 4 to "Апрель",
                    5 to "Май", 6 to "Июнь", 7 to "Июль", 8 to "Август",
                    9 to "Сентябрь", 10 to "Октябрь", 11 to "Ноябрь", 12 to "Декабрь"
                )
                appendLine("--- ПО МЕСЯЦАМ ---")
                for ((month, expresses) in byMonth.toSortedMap(compareBy { it.value })) {
                    val w = expresses.count { it.isWin }
                    val t = expresses.size
                    val r = if (t > 0) (w.toDouble() / t) * 100 else 0.0
                    appendLine("${monthNames[month.value]}: $w/$t (${String.format("%.1f", r)}%)")
                }
            }
        }
    }
    
    private fun navigateWeek(direction: Int) {
        if (allWeekStats.isEmpty()) return
        currentWeekIndex = (currentWeekIndex + direction).coerceIn(0, allWeekStats.size - 1)
        updateWeekDisplay()
        showExpressesForWeek(allWeekStats[currentWeekIndex].yearWeek)
    }
    
    private fun updateWeekDisplay() {
        if (currentWeekIndex in allWeekStats.indices) {
            val week = allWeekStats[currentWeekIndex]
            tvWeekInfo.text = "${week.yearWeek} (${week.startDate} - ${week.endDate}): ${week.wins}/${week.total} (${String.format("%.1f", week.rate)}%)"
            btnPrevWeek.isEnabled = currentWeekIndex > 0
            btnNextWeek.isEnabled = currentWeekIndex < allWeekStats.size - 1
        }
    }
    
    private fun showExpressesForDay(day: DayOfWeek) {
        val dayNames = mapOf(
            DayOfWeek.MONDAY to "Понедельник", DayOfWeek.TUESDAY to "Вторник",
            DayOfWeek.WEDNESDAY to "Среда", DayOfWeek.THURSDAY to "Четверг",
            DayOfWeek.FRIDAY to "Пятница", DayOfWeek.SATURDAY to "Суббота",
            DayOfWeek.SUNDAY to "Воскресенье"
        )
        val filtered = filteredExpressResults.filter { it.dateTime.dayOfWeek == day }
        updateDetailTable(filtered, "День: ${dayNames[day]}")
    }
    
    private fun showExpressesForHour(hour: Int) {
        val filtered = filteredExpressResults.filter { it.dateTime.hour == hour }
        updateDetailTable(filtered, "Час: ${String.format("%02d", hour)}:00 - ${String.format("%02d", hour)}:59")
    }
    
    private fun showExpressesForWeek(yearWeek: String) {
        val filtered = filteredExpressResults.filter { it.yearWeek == yearWeek }
        updateDetailTable(filtered, "Неделя: $yearWeek")
    }
    
    private fun updateDetailTable(expresses: List<ExpressResult>, title: String) {
        layoutDetailTable.visibility = View.VISIBLE
        tvDetailTitle.text = "$title (${expresses.size} экспрессов)"
        layoutDetailContent.removeAllViews()
        
        if (expresses.isEmpty()) {
            layoutDetailContent.addView(TextView(this).apply {
                text = "Нет данных за выбранный период"
                textSize = 14f
                setPadding(16, 32, 16, 32)
                gravity = Gravity.CENTER
            })
            return
        }
        
        val tableLayout = TableLayout(this).apply {
            layoutParams = TableLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isStretchAllColumns = true
        }
        
        val headerRow = TableRow(this)
        listOf("ID", "Ст.", "Дата", "Матчи", "Счет", "Тип").forEach { header ->
            headerRow.addView(TextView(this).apply {
                text = header
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(2, 6, 2, 6)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            })
        }
        tableLayout.addView(headerRow)
        
        expresses.take(30).forEach { express ->
            val expRow = TableRow(this)
            val statusColor = if (express.isWin) "#4CAF50" else "#F44336"
            val statusText = if (express.isWin) "✓" else "✗"
            
            expRow.addView(TextView(this).apply {
                text = "#${express.expId}"
                textSize = 11f; setPadding(2, 4, 2, 4); gravity = Gravity.CENTER
            })
            expRow.addView(TextView(this).apply {
                text = statusText; textSize = 11f; setPadding(2, 4, 2, 4)
                gravity = Gravity.CENTER; setTextColor(Color.parseColor(statusColor))
            })
            expRow.addView(TextView(this).apply {
                text = express.dateTime.format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
                textSize = 10f; setPadding(2, 4, 2, 4); gravity = Gravity.CENTER
            })
            expRow.addView(TextView(this).apply {
                text = "${express.matches.size}м"; textSize = 10f
                setPadding(2, 4, 2, 4); gravity = Gravity.CENTER
            })
            expRow.addView(TextView(this).apply {
                text = express.matches.joinToString(" ") { "${it.sh}-${it.sa}" }
                textSize = 10f; setPadding(2, 4, 2, 4)
            })
            expRow.addView(TextView(this).apply {
                text = express.matches.map { it.type }.distinct().joinToString(",")
                textSize = 10f; setPadding(2, 4, 2, 4); gravity = Gravity.CENTER
            })
            tableLayout.addView(expRow)
            
            express.matches.forEach { match ->
                val matchRow = TableRow(this)
                val matchColor = if (match.isWin) "#81C784" else "#EF9A9A"
                val matchText = if (match.isWin) "✓" else "✗"
                
                matchRow.addView(TextView(this).apply {
                    text = " └"; textSize = 10f; setPadding(8, 1, 2, 1)
                })
                matchRow.addView(TextView(this).apply {
                    text = matchText; textSize = 10f; setPadding(2, 1, 2, 1)
                    setTextColor(Color.parseColor(matchColor))
                })
                matchRow.addView(TextView(this).apply {
                    text = "${match.home.take(15)} v ${match.away.take(15)}"
                    textSize = 10f; setPadding(2, 1, 2, 1)
                })
                matchRow.addView(TextView(this).apply {
                    text = "${match.sh}:${match.sa}"; textSize = 10f
                    setPadding(2, 1, 2, 1); gravity = Gravity.CENTER
                })
                matchRow.addView(TextView(this).apply {
                    val tn = when(match.type) { 924->"Т" 927->"Ф1" 928->"Ф2" else->"${match.type}" }
                    text = tn; textSize = 10f; setPadding(2, 1, 2, 1); gravity = Gravity.CENTER
                })
                matchRow.addView(TextView(this).apply {
                    text = match.liganame.take(20); textSize = 9f; setPadding(2, 1, 2, 1)
                })
                tableLayout.addView(matchRow)
            }
        }
        
        layoutDetailContent.addView(tableLayout)
    }
    
    private fun loadAnalytics() {
        lifecycleScope.launch {
            try {
                tvStats.text = "Загрузка..."
                btnRefresh.isEnabled = false
                btnRefresh.text = "Загрузка..."
                
                val analytics = withContext(Dispatchers.IO) { analyticsEngine.calculateAnalytics() }
                
                val total = analytics["total"] as? AnalyticsSummary
                allExpressResults = (analytics["allExpresses"] as? List<ExpressResult>) ?: emptyList()
                filteredExpressResults = allExpressResults
                
                allWeekStats = (analytics["byWeek"] as? List<WeekStats>) ?: emptyList()
                if (allWeekStats.isNotEmpty()) {
                    currentWeekIndex = allWeekStats.size - 1
                    updateWeekDisplay()
                    layoutWeekNav.visibility = View.VISIBLE
                } else {
                    layoutWeekNav.visibility = View.GONE
                }
                
                allLeagueStats = (analytics["byLeague"] as? List<LeagueStats>) ?: emptyList()
                val leagueNames = listOf("Все лиги") + allLeagueStats.map { "${it.liganame} (${it.total})" }
                spinnerLeague.adapter = ArrayAdapter(this@DashboardActivity, android.R.layout.simple_spinner_item, leagueNames).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                
                if (total != null) setupPieChart(total)
                
                val dayStats = analytics["byDayOfWeek"] as? Map<DayOfWeek, Pair<Int, Int>>
                if (dayStats != null) setupDayBarChart(dayStats)
                
                val hourStats = analytics["byHour"] as? TreeMap<Int, Pair<Int, Int>>
                if (hourStats != null) setupHourBarChart(hourStats)
                
                if (allLeagueStats.isNotEmpty()) setupLeagueBarChart(allLeagueStats.take(15))
                
                updateSummaryText(allExpressResults, total?.winRate ?: 0.0)
                
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
                
            } catch (e: Exception) {
                tvStats.text = "Ошибка: ${e.message}"
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
            }
        }
    }
    
    // ==================== ГРАФИКИ ====================
    
    private fun setupPieChart(total: AnalyticsSummary) {
        if (total.winExpress == 0 && total.loseExpress == 0) { pieChart.clear(); pieChart.centerText = "Нет данных"; return }
        val entries = mutableListOf<PieEntry>()
        if (total.winExpress > 0) entries.add(PieEntry(total.winExpress.toFloat(), "Выигрыши"))
        if (total.loseExpress > 0) entries.add(PieEntry(total.loseExpress.toFloat(), "Проигрыши"))
        if (entries.isEmpty()) return
        val ds = PieDataSet(entries, "").apply { colors = listOf(Color.parseColor("#4CAF50"), Color.parseColor("#F44336")); valueTextSize = 14f; sliceSpace = 3f }
        pieChart.data = PieData(ds).apply { setValueFormatter(PercentFormatter(pieChart)) }
        pieChart.description.isEnabled = false
        pieChart.centerText = "${String.format("%.1f", total.winRate)}%"
        pieChart.setCenterTextSize(16f); pieChart.setUsePercentValues(true)
        pieChart.isDrawHoleEnabled = true; pieChart.holeRadius = 55f
        pieChart.legend.isEnabled = true; pieChart.animateY(1000); pieChart.invalidate()
    }
    
    private fun setupDayBarChart(dayStats: Map<DayOfWeek, Pair<Int, Int>>) {
        val dn = listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")
        val entries = mutableListOf<BarEntry>(); val colors = mutableListOf<Int>()
        DayOfWeek.entries.forEachIndexed { i, d ->
            val (w, t) = dayStats[d] ?: Pair(0,0)
            val r = if (t>0) (w.toFloat()/t)*100 else 0f
            entries.add(BarEntry(i.toFloat(), r))
            colors.add(if (t==0) Color.GRAY else if (r>=50) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
        }
        barChartDay.data = BarData(BarDataSet(entries,"").apply { this.colors=colors; valueTextSize=12f }).apply { barWidth=0.6f }
        barChartDay.description.isEnabled = false
        barChartDay.xAxis.apply { valueFormatter=IndexAxisValueFormatter(dn); position=XAxis.XAxisPosition.BOTTOM; granularity=1f }
        barChartDay.axisLeft.axisMinimum=0f; barChartDay.axisLeft.axisMaximum=100f; barChartDay.axisRight.isEnabled = false
        barChartDay.animateY(1000); barChartDay.invalidate()
    }
    
    private fun setupHourBarChart(hourStats: TreeMap<Int, Pair<Int, Int>>) {
        val entries = mutableListOf<BarEntry>(); val labels = mutableListOf<String>(); val colors = mutableListOf<Int>()
        for ((h, s) in hourStats) {
            val (w, t) = s; val r = if (t>0) (w.toFloat()/t)*100 else 0f
            entries.add(BarEntry(h.toFloat(), r)); labels.add(String.format("%02d",h))
            colors.add(if (t==0) Color.GRAY else if (r>=50) Color.parseColor("#2196F3") else Color.parseColor("#FF9800"))
        }
        barChartHour.data = BarData(BarDataSet(entries,"").apply { this.colors=colors; valueTextSize=10f }).apply { barWidth=0.7f }
        barChartHour.description.isEnabled = false
        barChartHour.xAxis.apply { valueFormatter=IndexAxisValueFormatter(labels); position=XAxis.XAxisPosition.BOTTOM; granularity=1f; labelRotationAngle=-45f }
        barChartHour.axisLeft.axisMinimum=0f; barChartHour.axisLeft.axisMaximum=100f; barChartHour.axisRight.isEnabled = false
        barChartHour.animateY(1000); barChartHour.invalidate()
    }
    
    private fun setupLeagueBarChart(leagueStats: List<LeagueStats>) {
        val entries = mutableListOf<BarEntry>(); val labels = mutableListOf<String>()
        leagueStats.forEachIndexed { i, l -> entries.add(BarEntry(i.toFloat(), l.rate.toFloat())); labels.add(l.liganame.take(25)) }
        barChartLeague.data = BarData(BarDataSet(entries,"").apply { colors = listOf(Color.parseColor("#3F51B5")); valueTextSize=12f }).apply { barWidth=0.7f }
        barChartLeague.description.isEnabled = false
        barChartLeague.xAxis.apply { valueFormatter=IndexAxisValueFormatter(labels); position=XAxis.XAxisPosition.BOTTOM; granularity=1f; labelRotationAngle=-30f }
        barChartLeague.axisLeft.axisMinimum=0f; barChartLeague.axisLeft.axisMaximum=100f; barChartLeague.axisRight.isEnabled = false
        barChartLeague.animateY(1000); barChartLeague.invalidate()
    }
}