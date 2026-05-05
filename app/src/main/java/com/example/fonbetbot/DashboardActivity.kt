// DashboardActivity.kt
package com.example.fonbetbot

import android.graphics.Color
import android.os.Bundle
import android.view.View
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.util.*

class DashboardActivity : AppCompatActivity() {
    
    private lateinit var scrollView: ScrollView
    private lateinit var tvStats: TextView
    private lateinit var pieChart: PieChart
    private lateinit var barChartDay: BarChart
    private lateinit var barChartHour: BarChart
    private lateinit var barChartType: BarChart
    private lateinit var barChartMixed: HorizontalBarChart
    private lateinit var barChartLeague: HorizontalBarChart
    private lateinit var btnRefresh: Button
    private lateinit var btnBack: Button
    private lateinit var btnPrevWeek: Button
    private lateinit var btnNextWeek: Button
    private lateinit var tvWeekInfo: TextView
    private lateinit var spinnerLeague: Spinner
    private lateinit var database: AppDatabase
    private lateinit var analyticsEngine: AnalyticsEngine
    
    private var allWeekStats = listOf<WeekStats>()
    private var currentWeekIndex = -1
    private var allLeagueStats = listOf<LeagueStats>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        
        scrollView = findViewById(R.id.scroll_view)
        tvStats = findViewById(R.id.tv_stats)
        pieChart = findViewById(R.id.pie_chart)
        barChartDay = findViewById(R.id.bar_chart_day)
        barChartHour = findViewById(R.id.bar_chart_hour)
        barChartType = findViewById(R.id.bar_chart_type)
        barChartMixed = findViewById(R.id.bar_chart_mixed)
        barChartLeague = findViewById(R.id.bar_chart_league)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnBack = findViewById(R.id.btn_back)
        btnPrevWeek = findViewById(R.id.btn_prev_week)
        btnNextWeek = findViewById(R.id.btn_next_week)
        tvWeekInfo = findViewById(R.id.tv_week_info)
        spinnerLeague = findViewById(R.id.spinner_league)
        
        database = AppDatabase.getDatabase(this)
        analyticsEngine = AnalyticsEngine(database)
        
        btnRefresh.setOnClickListener { loadAnalytics() }
        btnBack.setOnClickListener { finish() }
        btnPrevWeek.setOnClickListener { navigateWeek(-1) }
        btnNextWeek.setOnClickListener { navigateWeek(1) }
        
        spinnerLeague.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    showLeagueStats(position - 1)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        loadAnalytics()
    }
    
    private fun navigateWeek(direction: Int) {
        if (allWeekStats.isEmpty()) return
        
        currentWeekIndex = (currentWeekIndex + direction).coerceIn(0, allWeekStats.size - 1)
        updateWeekDisplay()
    }
    
    private fun updateWeekDisplay() {
        if (currentWeekIndex in allWeekStats.indices) {
            val week = allWeekStats[currentWeekIndex]
            tvWeekInfo.text = "Неделя ${week.yearWeek} (${week.startDate}-${week.endDate}): ${week.wins}/${week.total} (${String.format("%.1f", week.rate)}%)"
            btnPrevWeek.isEnabled = currentWeekIndex > 0
            btnNextWeek.isEnabled = currentWeekIndex < allWeekStats.size - 1
        }
    }
    
    private fun showLeagueStats(index: Int) {
        if (index in allLeagueStats.indices) {
            val league = allLeagueStats[index]
            Toast.makeText(this, "${league.liganame}: ${league.wins}/${league.total} (${String.format("%.1f", league.rate)}%)", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun loadAnalytics() {
        lifecycleScope.launch {
            try {
                tvStats.text = "Загрузка аналитики..."
                btnRefresh.isEnabled = false
                btnRefresh.text = "Загрузка..."
                
                val analytics = withContext(Dispatchers.IO) {
                    analyticsEngine.calculateAnalytics()
                }
                
                // Общая статистика
                val total = analytics["total"] as? AnalyticsSummary
                
                // Недели
                allWeekStats = (analytics["byWeek"] as? List<WeekStats>) ?: emptyList()
                if (allWeekStats.isNotEmpty()) {
                    currentWeekIndex = allWeekStats.size - 1  // Последняя неделя
                    updateWeekDisplay()
                }
                
                // Лиги
                allLeagueStats = (analytics["topLeagues"] as? List<LeagueStats>) ?: emptyList()
                val leagueNames = listOf("Выберите лигу") + allLeagueStats.map { "${it.liganame} (${it.total})" }
                spinnerLeague.adapter = ArrayAdapter(this@DashboardActivity, android.R.layout.simple_spinner_item, leagueNames)
                
                // Графики
                setupPieChart(total)
                
                val dayStats = analytics["byDayOfWeek"] as? Map<DayOfWeek, Pair<Int, Int>>
                if (dayStats != null) setupDayBarChart(dayStats)
                
                val hourStats = analytics["byHour"] as? TreeMap<Int, Pair<Int, Int>>
                if (hourStats != null) setupHourBarChart(hourStats)
                
                val typeStats = analytics["byType"] as? Map<Int, Triple<Int, Int, Double>>
                if (typeStats != null) setupTypeBarChart(typeStats)
                
                val mixedStats = analytics["mixedTypes"] as? List<MixedTypeStats>
                if (mixedStats != null) setupMixedBarChart(mixedStats)
                
                if (allLeagueStats.isNotEmpty()) setupLeagueBarChart(allLeagueStats)
                
                // Текстовая статистика
                val fullText = buildString {
                    if (total != null) append(total.details)
                    
                    appendLine("=== ПО НЕДЕЛЯМ ===")
                    allWeekStats.forEach { week ->
                        appendLine("${week.yearWeek} (${week.startDate}-${week.endDate}): ${week.wins}/${week.total} (${String.format("%.1f", week.rate)}%)")
                    }
                    appendLine()
                    
                    appendLine("=== ПО ЛИГАМ (Топ-10) ===")
                    allLeagueStats.take(10).forEach { league ->
                        appendLine("${league.liganame}: ${league.wins}/${league.total} (${String.format("%.1f", league.rate)}%)")
                    }
                    appendLine()
                    
                    appendLine("=== СМЕШАННЫЕ ТИПЫ ===")
                    mixedStats?.forEach { mix ->
                        appendLine("${mix.typeCombination}: ${mix.wins}/${mix.total} (${String.format("%.1f", mix.rate)}%)")
                    }
                }
                
                tvStats.text = fullText.toString()
                
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
                
            } catch (e: Exception) {
                tvStats.text = "Ошибка: ${e.message}"
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
            }
        }
    }
    
    private fun setupPieChart(total: AnalyticsSummary?) {
        if (total == null || (total.winExpress == 0 && total.loseExpress == 0)) {
            pieChart.clear()
            pieChart.centerText = "Нет данных"
            return
        }
        
        val entries = mutableListOf<PieEntry>()
        if (total.winExpress > 0) entries.add(PieEntry(total.winExpress.toFloat(), "Выигрыши"))
        if (total.loseExpress > 0) entries.add(PieEntry(total.loseExpress.toFloat(), "Проигрыши"))
        
        val dataSet = PieDataSet(entries, "")
        dataSet.colors = listOf(Color.parseColor("#4CAF50"), Color.parseColor("#F44336"))
        dataSet.valueTextSize = 14f
        dataSet.sliceSpace = 3f
        
        pieChart.apply {
            data = PieData(dataSet).apply { setValueFormatter(PercentFormatter(this@apply)) }
            description.isEnabled = false
            centerText = "Проходимость\n${String.format("%.1f", total.winRate)}%"
            setCenterTextSize(16f)
            setUsePercentValues(true)
            isDrawHoleEnabled = true
            holeRadius = 55f
            legend.isEnabled = true
            animateY(1000)
            invalidate()
        }
    }
    
    private fun setupDayBarChart(dayStats: Map<DayOfWeek, Pair<Int, Int>>) {
        val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        val entries = mutableListOf<BarEntry>()
        val colors = mutableListOf<Int>()
        
        DayOfWeek.entries.forEachIndexed { index, day ->
            val (wins, total) = dayStats[day] ?: Pair(0, 0)
            val rate = if (total > 0) (wins.toFloat() / total) * 100 else 0f
            entries.add(BarEntry(index.toFloat(), rate))
            colors.add(when {
                total == 0 -> Color.GRAY
                rate >= 50 -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#F44336")
            })
        }
        
        barChartDay.apply {
            data = BarData(BarDataSet(entries, "").apply {
                this.colors = colors
                valueTextSize = 12f
            }).apply { barWidth = 0.6f }
            description.isEnabled = false
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(dayNames)
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
            }
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f
            axisRight.isEnabled = false
            legend.isEnabled = false
            animateY(1000)
            invalidate()
        }
    }
    
    private fun setupHourBarChart(hourStats: TreeMap<Int, Pair<Int, Int>>) {
        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()
        val colors = mutableListOf<Int>()
        
        for ((hour, stats) in hourStats) {
            labels.add(String.format("%02d", hour))
            val (wins, total) = stats
            val rate = if (total > 0) (wins.toFloat() / total) * 100 else 0f
            entries.add(BarEntry(hour.toFloat(), rate))
            colors.add(when {
                total == 0 -> Color.GRAY
                rate >= 50 -> Color.parseColor("#2196F3")
                else -> Color.parseColor("#FF9800")
            })
        }
        
        barChartHour.apply {
            data = BarData(BarDataSet(entries, "").apply {
                this.colors = colors
                valueTextSize = 10f
            }).apply { barWidth = 0.7f }
            description.isEnabled = false
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                labelRotationAngle = -45f
            }
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f
            axisRight.isEnabled = false
            legend.isEnabled = false
            animateY(1000)
            invalidate()
        }
    }
    
    private fun setupTypeBarChart(typeStats: Map<Int, Triple<Int, Int, Double>>) {
        val typeNames = mapOf(924 to "Тотал", 927 to "Фора1", 928 to "Фора2")
        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()
        val colors = mutableListOf<Int>()
        val typeColors = mapOf(924 to "#4CAF50", 927 to "#2196F3", 928 to "#FF9800")
        
        var index = 0f
        for (type in listOf(924, 927, 928)) {
            val stats = typeStats[type]
            if (stats != null) {
                val (_, _, rate) = stats
                labels.add(typeNames[type] ?: "Тип $type")
                entries.add(BarEntry(index, rate.toFloat()))
                colors.add(Color.parseColor(typeColors[type] ?: "#9E9E9E"))
                index++
            }
        }
        
        barChartType.apply {
            data = BarData(BarDataSet(entries, "").apply {
                this.colors = colors
                valueTextSize = 14f
            }).apply { barWidth = 0.5f }
            description.isEnabled = false
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
            }
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f
            axisRight.isEnabled = false
            legend.isEnabled = false
            animateY(1000)
            invalidate()
        }
    }
    
    private fun setupMixedBarChart(mixedStats: List<MixedTypeStats>) {
        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()
        
        mixedStats.forEachIndexed { index, mix ->
            entries.add(BarEntry(index.toFloat(), mix.rate.toFloat()))
            labels.add(mix.typeCombination)
        }
        
        val colors = listOf(
            Color.parseColor("#4CAF50"),
            Color.parseColor("#2196F3"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#00BCD4"),
            Color.parseColor("#FF5722"),
            Color.parseColor("#795548")
        )
        
        barChartMixed.apply {
            data = BarData(BarDataSet(entries, "").apply {
                this.colors = colors
                valueTextSize = 12f
            }).apply { barWidth = 0.7f }
            description.isEnabled = false
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                labelRotationAngle = -30f
            }
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f
            axisRight.isEnabled = false
            legend.isEnabled = false
            animateY(1000)
            invalidate()
        }
    }
    
    private fun setupLeagueBarChart(leagueStats: List<LeagueStats>) {
        val topLeagues = leagueStats.take(10)
        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()
        
        topLeagues.forEachIndexed { index, league ->
            entries.add(BarEntry(index.toFloat(), league.rate.toFloat()))
            labels.add(league.liganame.take(20))
        }
        
        barChartLeague.apply {
            data = BarData(BarDataSet(entries, "").apply {
                colors = listOf(Color.parseColor("#3F51B5"))
                valueTextSize = 12f
            }).apply { barWidth = 0.7f }
            description.isEnabled = false
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                labelRotationAngle = -45f
            }
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f
            axisRight.isEnabled = false
            legend.isEnabled = false
            animateY(1000)
            invalidate()
        }
    }
}