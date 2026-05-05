// DashboardActivity.kt
package com.example.fonbetbot

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Pair
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
    private var dateStart: LocalDate? = null
    private var dateEnd: LocalDate? = null
    private var isWeekFiltered = false
    
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
        
        // При выборе лиги: сбрасываем только фильтр недели, сохраняем период календаря
        spinnerLeague.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLeague = if (position > 0) allLeagueStats[position - 1].liganame else null
                isWeekFiltered = false  // Сбрасываем только неделю
                applyFilters()  // Применяем: даты (если есть) + лига
                refreshAllCharts()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        barChartDay.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                e?.let { entry ->
                    val dayIndex = entry.x.toInt()
                    if (dayIndex in 0..6) showExpressesForDay(DayOfWeek.entries[dayIndex])
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
    
    private fun showDateRangePicker() {
        val selection = if (dateStart != null && dateEnd != null) {
            Pair(dateStart!!.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                dateEnd!!.atTime(23,59,59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        } else null
        
        MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Выберите период")
            .setSelection(selection)
            .build()
            .apply {
                addOnPositiveButtonClickListener { sel ->
                    dateStart = Instant.ofEpochMilli(sel.first).atZone(ZoneId.systemDefault()).toLocalDate()
                    dateEnd = Instant.ofEpochMilli(sel.second).atZone(ZoneId.systemDefault()).toLocalDate()
                    isWeekFiltered = false  // Сбрасываем фильтр недели
                    updateDateRangeInfo()
                    applyFilters()  // Применяем: новый период + лига (если выбрана)
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
    
    // applyFilters: сначала фильтр по датам, потом по лиге
    private fun applyFilters() {
        var filtered = allExpressResults
        
        // Фильтр по датам (если выбран период)
        if (dateStart != null && dateEnd != null) {
            val start = dateStart!!.atStartOfDay()
            val end = dateEnd!!.atTime(23, 59, 59)
            filtered = filtered.filter { express ->
                val d = express.dateTime
                !d.isBefore(start) && !d.isAfter(end)
            }
        }
        
        // Фильтр по лиге (если выбрана)
        if (selectedLeague != null) {
            filtered = filtered.filter { express ->
                express.matches.any { match -> match.liganame == selectedLeague }
            }
        }
        
        filteredExpressResults = filtered
    }
    
    private fun refreshAllCharts() {
        val data = if (isWeekFiltered && currentWeekIndex in allWeekStats.indices) {
            filteredExpressResults.filter { it.yearWeek == allWeekStats[currentWeekIndex].yearWeek }
        } else {
            filteredExpressResults
        }
        refreshChartsForFiltered(data)
    }
    
    private fun refreshChartsForFiltered(filtered: List<ExpressResult>) {
        val t = filtered.size
        val w = filtered.count { it.isWin }
        val l = t - w
        val r = if (t > 0) (w.toDouble() / t) * 100 else 0.0
        setupPieChart(AnalyticsSummary(t, w, l, r, ""))
        
        val bd = LinkedHashMap<DayOfWeek, Pair<Int, Int>>()
        for (d in DayOfWeek.entries) {
            val ex = filtered.filter { it.dateTime.dayOfWeek == d }
            bd[d] = Pair(ex.count { it.isWin }, ex.size)
        }
        setupDayBarChart(bd)
        
        val bh = TreeMap<Int, Pair<Int, Int>>()
        for (h in 0..23) {
            val ex = filtered.filter { it.dateTime.hour == h }
            if (ex.isNotEmpty()) bh[h] = Pair(ex.count { it.isWin }, ex.size)
        }
        setupHourBarChart(bh)
        
        updateSummaryText(filtered, r)
    }
    
    private fun updateSummaryText(filtered: List<ExpressResult>, winRate: Double) {
        val t = filtered.size
        val w = filtered.count { it.isWin }
        val l = t - w
        
        tvStats.text = buildString {
            val pt = when {
                isWeekFiltered && currentWeekIndex in allWeekStats.indices -> {
                    val wk = allWeekStats[currentWeekIndex]
                    "${wk.yearWeek} (${wk.startDate}-${wk.endDate})"
                }
                dateStart != null && dateEnd != null -> {
                    "${dateStart!!.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} - ${dateEnd!!.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}"
                }
                else -> "Весь период"
            }
            
            val leagueInfo = if (selectedLeague != null) " | Лига: $selectedLeague" else ""
            appendLine("=== $pt$leagueInfo ===")
            appendLine("Всего: $t | ✓ $w | ✗ $l | ${String.format("%.1f", winRate)}%")
            appendLine()
            
            if (t > 0) {
                val dn = mapOf(
                    DayOfWeek.MONDAY to "Пн", DayOfWeek.TUESDAY to "Вт",
                    DayOfWeek.WEDNESDAY to "Ср", DayOfWeek.THURSDAY to "Чт",
                    DayOfWeek.FRIDAY to "Пт", DayOfWeek.SATURDAY to "Сб",
                    DayOfWeek.SUNDAY to "Вс"
                )
                appendLine("--- ПО ДНЯМ ---")
                for (d in DayOfWeek.entries) {
                    val ex = filtered.filter { it.dateTime.dayOfWeek == d }
                    val wd = ex.count { it.isWin }
                    val td = ex.size
                    if (td > 0) appendLine("${dn[d]}: $wd/$td (${String.format("%.1f", (wd.toDouble() / td) * 100)}%)")
                }
                appendLine()
                
                appendLine("--- ПО ЧАСАМ ---")
                for (h in 0..23) {
                    val ex = filtered.filter { it.dateTime.hour == h }
                    val wh = ex.count { it.isWin }
                    val th = ex.size
                    if (th > 0) appendLine("${String.format("%02d", h)}:00: $wh/$th (${String.format("%.1f", (wh.toDouble() / th) * 100)}%)")
                }
                appendLine()
                
                val mn = mapOf(
                    1 to "Янв", 2 to "Фев", 3 to "Мар", 4 to "Апр",
                    5 to "Май", 6 to "Июн", 7 to "Июл", 8 to "Авг",
                    9 to "Сен", 10 to "Окт", 11 to "Ноя", 12 to "Дек"
                )
                appendLine("--- ПО МЕСЯЦАМ ---")
                for ((m, ex) in filtered.groupBy { it.dateTime.month }.toSortedMap(compareBy { it.value })) {
                    val wm = ex.count { it.isWin }
                    val tm = ex.size
                    appendLine("${mn[m.value]}: $wm/$tm (${String.format("%.1f", if (tm > 0) (wm.toDouble() / tm) * 100 else 0.0)}%)")
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
        val dn = mapOf(
            DayOfWeek.MONDAY to "Пн", DayOfWeek.TUESDAY to "Вт",
            DayOfWeek.WEDNESDAY to "Ср", DayOfWeek.THURSDAY to "Чт",
            DayOfWeek.FRIDAY to "Пт", DayOfWeek.SATURDAY to "Сб",
            DayOfWeek.SUNDAY to "Вс"
        )
        val data = if (isWeekFiltered && currentWeekIndex in allWeekStats.indices)
            filteredExpressResults.filter { it.yearWeek == allWeekStats[currentWeekIndex].yearWeek && it.dateTime.dayOfWeek == day }
        else filteredExpressResults.filter { it.dateTime.dayOfWeek == day }
        updateDetailTable(data, "День: ${dn[day]}")
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
            isStretchAllColumns = true
        }
        
        val hr = TableRow(this)
        listOf("ID", "Ст.", "Дата", "М", "Счет", "Тип").forEach { h ->
            hr.addView(tv(h, 11f, Gravity.CENTER, bold = true, bg = "#E0E0E0"))
        }
        table.addView(hr)
        
        expresses.take(30).forEach { ex ->
            val r = TableRow(this)
            r.addView(tv("#${ex.expId}", 11f, Gravity.CENTER))
            r.addView(tv(if (ex.isWin) "✓" else "✗", 11f, Gravity.CENTER, color = if (ex.isWin) "#4CAF50" else "#F44336"))
            r.addView(tv(ex.dateTime.format(DateTimeFormatter.ofPattern("dd.MM HH:mm")), 10f, Gravity.CENTER))
            r.addView(tv("${ex.matches.size}м", 10f, Gravity.CENTER))
            r.addView(tv(ex.matches.joinToString(" ") { "${it.sh}-${it.sa}" }, 10f))
            r.addView(tv(ex.matches.map { it.type }.distinct().joinToString(","), 10f, Gravity.CENTER))
            table.addView(r)
            
            ex.matches.forEach { m ->
                val mr = TableRow(this)
                mr.addView(tv(" └", 10f, pad = 8))
                mr.addView(tv(if (m.isWin) "✓" else "✗", 10f, color = if (m.isWin) "#81C784" else "#EF9A9A"))
                mr.addView(tv("${m.home.take(15)} v ${m.away.take(15)}", 10f))
                mr.addView(tv("${m.sh}:${m.sa}", 10f, Gravity.CENTER))
                mr.addView(tv(when (m.type) { 924 -> "Т"; 927 -> "Ф1"; 928 -> "Ф2"; else -> "${m.type}" }, 10f, Gravity.CENTER))
                mr.addView(tv(m.liganame.take(20), 9f))
                table.addView(mr)
            }
        }
        layoutDetailContent.addView(table)
    }
    
    private fun tv(text: String, size: Float, gravity: Int = Gravity.START, color: String? = null, bold: Boolean = false, bg: String? = null, pad: Int = 2): TextView {
        return TextView(this).apply {
            this.text = text; textSize = size; setPadding(pad, 2, 2, 2); this.gravity = gravity
            if (color != null) setTextColor(Color.parseColor(color))
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            if (bg != null) setBackgroundColor(Color.parseColor(bg))
        }
    }
    
    private fun loadAnalytics() {
        lifecycleScope.launch {
            try {
                tvStats.text = "Загрузка..."; btnRefresh.isEnabled = false; btnRefresh.text = "Загрузка..."
                
                val analytics = withContext(Dispatchers.IO) { analyticsEngine.calculateAnalytics() }
                
                allExpressResults = (analytics["allExpresses"] as? List<ExpressResult>) ?: emptyList()
                isWeekFiltered = false
                applyFilters()
                
                allWeekStats = (analytics["byWeek"] as? List<WeekStats>) ?: emptyList()
                if (allWeekStats.isNotEmpty()) {
                    currentWeekIndex = allWeekStats.size - 1
                    updateWeekDisplay()
                    layoutWeekNav.visibility = View.VISIBLE
                } else layoutWeekNav.visibility = View.GONE
                
                allLeagueStats = (analytics["byLeague"] as? List<LeagueStats>) ?: emptyList()
                spinnerLeague.adapter = ArrayAdapter(
                    this@DashboardActivity, android.R.layout.simple_spinner_item,
                    listOf("Все лиги") + allLeagueStats.map { "${it.liganame} (${it.total})" }
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                
                refreshAllCharts()
                if (allLeagueStats.isNotEmpty()) setupLeagueBarChart(allLeagueStats.take(15))
                
                btnRefresh.isEnabled = true; btnRefresh.text = "Обновить аналитику"
            } catch (e: Exception) {
                tvStats.text = "Ошибка: ${e.message}"
                btnRefresh.isEnabled = true; btnRefresh.text = "Обновить аналитику"
            }
        }
    }
    
    // ==================== ГРАФИКИ ====================
    
    private fun setupPieChart(t: AnalyticsSummary) {
        if (t.winExpress == 0 && t.loseExpress == 0) { pieChart.clear(); pieChart.centerText = "Нет данных"; return }
        val e = mutableListOf<PieEntry>()
        if (t.winExpress > 0) e.add(PieEntry(t.winExpress.toFloat(), "Выигрыши"))
        if (t.loseExpress > 0) e.add(PieEntry(t.loseExpress.toFloat(), "Проигрыши"))
        if (e.isEmpty()) return
        pieChart.data = PieData(PieDataSet(e, "").apply {
            colors = listOf(Color.parseColor("#4CAF50"), Color.parseColor("#F44336"))
            valueTextSize = 14f; sliceSpace = 3f
        }).apply { setValueFormatter(PercentFormatter(pieChart)) }
        pieChart.description.isEnabled = false
        pieChart.centerText = "${String.format("%.1f", t.winRate)}%"
        pieChart.setCenterTextSize(16f); pieChart.setUsePercentValues(true)
        pieChart.isDrawHoleEnabled = true; pieChart.holeRadius = 55f
        pieChart.legend.isEnabled = true; pieChart.animateY(1000); pieChart.invalidate()
    }
    
    private fun setupDayBarChart(d: Map<DayOfWeek, Pair<Int, Int>>) {
        val dn = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        val e = mutableListOf<BarEntry>(); val c = mutableListOf<Int>()
        DayOfWeek.entries.forEachIndexed { i, day ->
            val (w, t) = d[day] ?: Pair(0, 0)
            val r = if (t > 0) (w.toFloat() / t) * 100 else 0f
            e.add(BarEntry(i.toFloat(), r))
            c.add(if (t == 0) Color.GRAY else if (r >= 50) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
        }
        barChartDay.data = BarData(BarDataSet(e, "").apply { colors = c; valueTextSize = 12f }).apply { barWidth = 0.6f }
        barChartDay.description.isEnabled = false
        barChartDay.xAxis.apply { valueFormatter = IndexAxisValueFormatter(dn); position = XAxis.XAxisPosition.BOTTOM; granularity = 1f }
        barChartDay.axisLeft.axisMinimum = 0f; barChartDay.axisLeft.axisMaximum = 100f; barChartDay.axisRight.isEnabled = false
        barChartDay.animateY(1000); barChartDay.invalidate()
    }
    
    private fun setupHourBarChart(h: TreeMap<Int, Pair<Int, Int>>) {
        val e = mutableListOf<BarEntry>(); val l = mutableListOf<String>(); val c = mutableListOf<Int>()
        for ((hour, s) in h) {
            val (w, t) = s; val r = if (t > 0) (w.toFloat() / t) * 100 else 0f
            e.add(BarEntry(hour.toFloat(), r)); l.add(String.format("%02d", hour))
            c.add(if (t == 0) Color.GRAY else if (r >= 50) Color.parseColor("#2196F3") else Color.parseColor("#FF9800"))
        }
        barChartHour.data = BarData(BarDataSet(e, "").apply { colors = c; valueTextSize = 10f }).apply { barWidth = 0.7f }
        barChartHour.description.isEnabled = false
        barChartHour.xAxis.apply { valueFormatter = IndexAxisValueFormatter(l); position = XAxis.XAxisPosition.BOTTOM; granularity = 1f; labelRotationAngle = -45f }
        barChartHour.axisLeft.axisMinimum = 0f; barChartHour.axisLeft.axisMaximum = 100f; barChartHour.axisRight.isEnabled = false
        barChartHour.animateY(1000); barChartHour.invalidate()
    }
    
    private fun setupLeagueBarChart(ls: List<LeagueStats>) {
        val e = mutableListOf<BarEntry>(); val l = mutableListOf<String>()
        ls.forEachIndexed { i, lg -> e.add(BarEntry(i.toFloat(), lg.rate.toFloat())); l.add(lg.liganame.take(25)) }
        barChartLeague.data = BarData(BarDataSet(e, "").apply { colors = listOf(Color.parseColor("#3F51B5")); valueTextSize = 12f }).apply { barWidth = 0.7f }
        barChartLeague.description.isEnabled = false
        barChartLeague.xAxis.apply { valueFormatter = IndexAxisValueFormatter(l); position = XAxis.XAxisPosition.BOTTOM; granularity = 1f; labelRotationAngle = -30f }
        barChartLeague.axisLeft.axisMinimum = 0f; barChartLeague.axisLeft.axisMaximum = 100f; barChartLeague.axisRight.isEnabled = false
        barChartLeague.animateY(1000); barChartLeague.invalidate()
    }
}