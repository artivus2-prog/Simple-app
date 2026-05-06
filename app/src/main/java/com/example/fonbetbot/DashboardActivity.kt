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
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var layoutDetailTable: View
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
    private var dateStart: LocalDate? = null
    private var dateEnd: LocalDate? = null
    private var isWeekFiltered = false
    
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
    }
    
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
        
        initChartStyles()
        
        btnRefresh.setOnClickListener { loadAnalytics() }
        btnBack.setOnClickListener { finish() }
        btnPrevWeek.setOnClickListener { navigateWeek(-1) }
        btnNextWeek.setOnClickListener { navigateWeek(1) }
        btnDateRange.setOnClickListener { showDateRangePicker() }
        
        spinnerLeague.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLeague = if (position > 0) allLeagueStats[position - 1].liganame else null
                isWeekFiltered = false
                applyFilters()
                refreshAllCharts()
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
        
        val legend = chart.legend
        legend.textColor = Color.parseColor(COLOR_TEXT_PRIMARY)
        legend.textSize = 12f
        legend.verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
        legend.horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
        legend.orientation = Legend.LegendOrientation.HORIZONTAL
        legend.setDrawInside(false)
        
        chart.setCenterTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
        chart.setCenterTextSize(16f)
        //chart.setCenterTextTypeface(null, android.graphics.Typeface.BOLD)
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
                    isWeekFiltered = false
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
        filteredExpressResults = filtered
        Log.d(TAG, "applyFilters: filtered count = ${filteredExpressResults.size}, all = ${allExpressResults.size}")
    }
    
    private fun refreshAllCharts() {
        val data = if (isWeekFiltered && currentWeekIndex in allWeekStats.indices)
            filteredExpressResults.filter { it.yearWeek == allWeekStats[currentWeekIndex].yearWeek }
        else filteredExpressResults
        
        Log.d(TAG, "refreshAllCharts: data count = ${data.size}, isWeekFiltered = $isWeekFiltered")
        refreshChartsForFiltered(data)
        
        val leagueData = if (isWeekFiltered && currentWeekIndex in allWeekStats.indices) {
            filteredExpressResults.filter { it.yearWeek == allWeekStats[currentWeekIndex].yearWeek }
        } else {
            filteredExpressResults
        }
        val leagueMatches = leagueData.flatMap { it.matches }
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
        
        updateSummaryText(filtered, rate)
    }
    
    private fun updateSummaryText(filtered: List<ExpressResult>, winRate: Double) {
        val total = filtered.size
        val wins = filtered.count { it.isWin }
        val losses = total - wins
        
        tvStats.text = buildString {
            val periodText = when {
                isWeekFiltered && currentWeekIndex in allWeekStats.indices -> {
                    val wk = allWeekStats[currentWeekIndex]
                    "${wk.yearWeek} (${wk.startDate}-${wk.endDate})"
                }
                dateStart != null && dateEnd != null -> "${dateStart!!.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} - ${dateEnd!!.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}"
                else -> "Весь период"
            }
            appendLine("=== $periodText${if (selectedLeague != null) " | $selectedLeague" else ""} ===")
            appendLine("Всего: $total | ✓ $wins | ✗ $losses | ${String.format("%.1f", winRate)}%")
            appendLine()
            
            if (total > 0) {
                val dayNames = mapOf(
                    DayOfWeek.MONDAY to "Пн", DayOfWeek.TUESDAY to "Вт",
                    DayOfWeek.WEDNESDAY to "Ср", DayOfWeek.THURSDAY to "Чт",
                    DayOfWeek.FRIDAY to "Пт", DayOfWeek.SATURDAY to "Сб",
                    DayOfWeek.SUNDAY to "Вс"
                )
                appendLine("--- ПО ДНЯМ ---")
                for (d in DayOfWeek.entries) {
                    val ex = filtered.filter { it.dateTime.dayOfWeek == d }
                    val wd = ex.count { it.isWin }; val td = ex.size
                    if (td > 0) appendLine("${dayNames[d]}: $wd/$td (${String.format("%.1f", (wd.toDouble() / td) * 100)}%)")
                }
                appendLine()
                
                appendLine("--- ПО ЧАСАМ ---")
                for (h in 0..23) {
                    val ex = filtered.filter { it.dateTime.hour == h }
                    val wh = ex.count { it.isWin }; val th = ex.size
                    if (th > 0) appendLine("${String.format("%02d", h)}:00: $wh/$th (${String.format("%.1f", (wh.toDouble() / th) * 100)}%)")
                }
                appendLine()
                
                val monthNames = mapOf(
                    1 to "Янв", 2 to "Фев", 3 to "Мар", 4 to "Апр",
                    5 to "Май", 6 to "Июн", 7 to "Июл", 8 to "Авг",
                    9 to "Сен", 10 to "Окт", 11 to "Ноя", 12 to "Дек"
                )
                appendLine("--- ПО МЕСЯЦАМ ---")
                for ((m, ex) in filtered.groupBy { it.dateTime.month }.toSortedMap(compareBy { it.value })) {
                    val wm = ex.count { it.isWin }; val tm = ex.size
                    appendLine("${monthNames[m.value]}: $wm/$tm (${String.format("%.1f", if (tm > 0) (wm.toDouble() / tm) * 100 else 0.0)}%)")
                }
            }
        }
    }
    
    private fun navigateWeek(direction: Int) {
        if (allWeekStats.isEmpty()) return
        currentWeekIndex = (currentWeekIndex + direction).coerceIn(0, allWeekStats.size - 1)
        isWeekFiltered = true
        updateWeekDisplay()
        refreshAllCharts()
        showExpressesForWeek(allWeekStats[currentWeekIndex].yearWeek)
    }
    
    private fun updateWeekDisplay() {
        if (currentWeekIndex in allWeekStats.indices) {
            val w = allWeekStats[currentWeekIndex]
            tvWeekInfo.text = "${w.yearWeek} (${w.startDate} - ${w.endDate}): ${w.wins}/${w.total} (${String.format("%.1f", w.rate)}%)"
            btnPrevWeek.isEnabled = currentWeekIndex > 0
            btnNextWeek.isEnabled = currentWeekIndex < allWeekStats.size - 1
        }
    }
    
    private fun showExpressesForDay(day: DayOfWeek) {
        val dayNames = mapOf(
            DayOfWeek.MONDAY to "Пн", DayOfWeek.TUESDAY to "Вт",
            DayOfWeek.WEDNESDAY to "Ср", DayOfWeek.THURSDAY to "Чт",
            DayOfWeek.FRIDAY to "Пт", DayOfWeek.SATURDAY to "Сб",
            DayOfWeek.SUNDAY to "Вс"
        )
        val data = if (isWeekFiltered && currentWeekIndex in allWeekStats.indices)
            filteredExpressResults.filter { it.yearWeek == allWeekStats[currentWeekIndex].yearWeek && it.dateTime.dayOfWeek == day }
        else filteredExpressResults.filter { it.dateTime.dayOfWeek == day }
        updateDetailTable(data, "День: ${dayNames[day]}")
    }
    
    private fun showExpressesForHour(hour: Int) {
        val data = if (isWeekFiltered && currentWeekIndex in allWeekStats.indices)
            filteredExpressResults.filter { it.yearWeek == allWeekStats[currentWeekIndex].yearWeek && it.dateTime.hour == hour }
        else filteredExpressResults.filter { it.dateTime.hour == hour }
        updateDetailTable(data, "Час: ${String.format("%02d", hour)}:00")
    }
    
    private fun showExpressesForWeek(yearWeek: String) {
        updateDetailTable(filteredExpressResults.filter { it.yearWeek == yearWeek }, "Неделя: $yearWeek")
    }
    
    private fun updateDetailTable(expresses: List<ExpressResult>, title: String) {
        layoutDetailTable.visibility = View.VISIBLE
        tvDetailTitle.text = "$title (${expresses.size})"
        layoutDetailContent.removeAllViews()
        
        if (expresses.isEmpty()) {
            layoutDetailContent.addView(tv("Нет данных", 14f, Gravity.CENTER, pad = 16))
            return
        }
        
        val table = TableLayout(this).apply {
            layoutParams = TableLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isStretchAllColumns = false
        }
        
        expresses.take(30).forEach { express ->
            val expHeaderRow = TableRow(this)
            val expBg = if (express.isWin) "#0A2317" else "#2B0F14"
            val expColor = if (express.isWin) "#03A66D" else "#CF304A"
            val expStatus = if (express.isWin) "✓ ВЫИГРЫШ" else "✗ ПРОИГРЫШ"
            val dateStr = express.dateTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            val timeStr = express.dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
            
            expHeaderRow.addView(tv("Экспресс #${express.expId} | $dateStr в $timeStr | $expStatus",
                12f, Gravity.START, bold = true, color = expColor, bg = expBg, pad = 8))
            table.addView(expHeaderRow)
            
            val infoRow = TableRow(this)
            val dayOfWeek = when (express.dateTime.dayOfWeek) {
                DayOfWeek.MONDAY -> "Понедельник"
                DayOfWeek.TUESDAY -> "Вторник"
                DayOfWeek.WEDNESDAY -> "Среда"
                DayOfWeek.THURSDAY -> "Четверг"
                DayOfWeek.FRIDAY -> "Пятница"
                DayOfWeek.SATURDAY -> "Суббота"
                DayOfWeek.SUNDAY -> "Воскресенье"
            }
            infoRow.addView(tv("$dayOfWeek | ${express.matches.size} матча(ей) | Неделя: ${express.yearWeek}${if (express.isReplaced) " | Замененный" else ""}",
                10f, Gravity.START, color = "#848E9C", bg = "#161A1E", pad = 8))
            table.addView(infoRow)
            
            val mhRow = TableRow(this)
            mhRow.addView(tv("m_id", 10f, Gravity.CENTER, bold = true, bg = "#2B3139", color = "#EAECEF", pad = 4))
            mhRow.addView(tv("Лига", 10f, Gravity.START, bold = true, bg = "#2B3139", color = "#EAECEF", pad = 4))
            mhRow.addView(tv("Команда 1", 10f, Gravity.START, bold = true, bg = "#2B3139", color = "#EAECEF", pad = 4))
            mhRow.addView(tv("Команда 2", 10f, Gravity.START, bold = true, bg = "#2B3139", color = "#EAECEF", pad = 4))
            mhRow.addView(tv("Счет", 10f, Gravity.CENTER, bold = true, bg = "#2B3139", color = "#EAECEF", pad = 4))
            mhRow.addView(tv("Тип ставки", 10f, Gravity.CENTER, bold = true, bg = "#2B3139", color = "#EAECEF", pad = 4))
            mhRow.addView(tv("Итог", 10f, Gravity.CENTER, bold = true, bg = "#2B3139", color = "#EAECEF", pad = 4))
            table.addView(mhRow)
            
            express.matches.forEach { match ->
                val mr = TableRow(this)
                val mc = if (match.isWin) "#03A66D" else "#CF304A"
                val typeFull = when (match.type) {
                    924 -> "1Х (хозяева не проиграли)"
                    927 -> "Фора 1 (+1.5)"
                    928 -> "Фора 2 (+1.5)"
                    else -> "Тип ${match.type}"
                }
                
                mr.addView(tv("${match.matchId}", 10f, Gravity.CENTER, pad = 4))
                mr.addView(tv(match.liganame.take(30), 10f, Gravity.START, pad = 4))
                mr.addView(tv(match.home.take(22), 10f, Gravity.START, pad = 4))
                mr.addView(tv(match.away.take(22), 10f, Gravity.START, pad = 4))
                mr.addView(tv("${match.sh}:${match.sa}", 10f, Gravity.CENTER, pad = 4))
                mr.addView(tv(typeFull, 10f, Gravity.CENTER, pad = 4))
                mr.addView(tv(if (match.isWin) "✓ Зашел" else "✗ Мимо", 10f, Gravity.CENTER, color = mc, pad = 4))
                table.addView(mr)
            }
            
            val sep = TableRow(this)
            val sepView = View(this).apply {
                layoutParams = TableRow.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#2B3139"))
            }
            sep.addView(sepView)
            for (i in 1..6) sep.addView(View(this))
            table.addView(sep)
        }
        
        if (expresses.size > 30) {
            table.addView(tv("... и еще ${expresses.size - 30} экспрессов", 12f, Gravity.CENTER, color = "#5E6673", pad = 8))
        }
        
        layoutDetailContent.addView(table)
    }
    
    private fun tv(text: String, size: Float, gravity: Int = Gravity.START, color: String? = null, bold: Boolean = false, bg: String? = null, pad: Int = 2): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(pad, 4, pad, 4)
            this.gravity = gravity
            if (color != null) setTextColor(Color.parseColor(color))
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            if (bg != null) setBackgroundColor(Color.parseColor(bg))
        }
    }
    
    private fun loadAnalytics() {
        lifecycleScope.launch {
            try {
                tvStats.text = "Загрузка..."
                btnRefresh.isEnabled = false
                btnRefresh.text = "Загрузка..."
                
                val analytics = withContext(Dispatchers.IO) { analyticsEngine.calculateAnalytics() }
                
                Log.d(TAG, "analytics keys: ${analytics.keys}")
                
                allExpressResults = (analytics["allExpresses"] as? List<ExpressResult>) ?: emptyList()
                Log.d(TAG, "allExpresses count = ${allExpressResults.size}")
                
                isWeekFiltered = false
                applyFilters()
                
                allWeekStats = (analytics["byWeek"] as? List<WeekStats>) ?: emptyList()
                if (allWeekStats.isNotEmpty()) {
                    currentWeekIndex = allWeekStats.size - 1
                    updateWeekDisplay()
                    layoutWeekNav.visibility = View.VISIBLE
                } else {
                    layoutWeekNav.visibility = View.GONE
                }
                
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
                tvStats.text = "Ошибка: ${e.message}"
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
            }
        }
    }
    
    private fun setupPieChart(t: AnalyticsSummary) {
        Log.d(TAG, "setupPieChart: total=${t.totalExpress}, win=${t.winExpress}, lose=${t.loseExpress}")
        
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
            valueTypeface = null
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
        val dn = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
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
        val labels = mutableListOf<String>()
        val colors = mutableListOf<Int>()
        
        for ((hour, stats) in h) {
            val w = stats.first
            val t = stats.second
            val r = if (t > 0) (w.toFloat() / t) * 100 else 0f
            entries.add(BarEntry(hour.toFloat(), r))
            labels.add(String.format("%02d", hour))
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
        val labels = mutableListOf<String>()
        
        ls.forEachIndexed { i, lg ->
            entries.add(BarEntry(i.toFloat(), lg.rate.toFloat()))
            labels.add(lg.liganame.take(25))
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