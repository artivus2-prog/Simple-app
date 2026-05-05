// DashboardActivity.kt
package com.example.fonbetbot

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
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
    private lateinit var btnRefresh: Button
    private lateinit var btnBack: Button
    private lateinit var database: AppDatabase
    private lateinit var analyticsEngine: AnalyticsEngine
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        
        scrollView = findViewById(R.id.scroll_view)
        tvStats = findViewById(R.id.tv_stats)
        pieChart = findViewById(R.id.pie_chart)
        barChartDay = findViewById(R.id.bar_chart_day)
        barChartHour = findViewById(R.id.bar_chart_hour)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnBack = findViewById(R.id.btn_back)
        
        database = AppDatabase.getDatabase(this)
        analyticsEngine = AnalyticsEngine(database)
        
        btnRefresh.setOnClickListener { loadAnalytics() }
        btnBack.setOnClickListener { finish() }
        
        loadAnalytics()
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
                
                val total = analytics["total"] as? AnalyticsSummary
                
                val fullText = buildString {
                    if (total != null) {
                        append(total.details)
                        
                        // По дням недели
                        val dayStats = analytics["byDayOfWeek"] as? Map<DayOfWeek, Pair<Int, Int>>
                        if (dayStats != null) {
                            appendLine("=== ПО ДНЯМ НЕДЕЛИ ===")
                            val dayNames = mapOf(
                                DayOfWeek.MONDAY to "Пн", DayOfWeek.TUESDAY to "Вт",
                                DayOfWeek.WEDNESDAY to "Ср", DayOfWeek.THURSDAY to "Чт",
                                DayOfWeek.FRIDAY to "Пт", DayOfWeek.SATURDAY to "Сб",
                                DayOfWeek.SUNDAY to "Вс"
                            )
                            for ((day, stats) in dayStats) {
                                val (wins, totalDay) = stats
                                val rate = if (totalDay > 0) (wins.toDouble() / totalDay) * 100 else 0.0
                                appendLine("${dayNames[day]}: $wins/$totalDay (${String.format("%.1f", rate)}%)")
                            }
                            appendLine()
                            setupDayBarChart(dayStats)
                        }
                        
                        // По часам
                        val hourStats = analytics["byHour"] as? TreeMap<Int, Pair<Int, Int>>
                        if (hourStats != null) {
                            appendLine("=== ПО ЧАСАМ ===")
                            for ((hour, stats) in hourStats) {
                                val (wins, totalHour) = stats
                                if (totalHour > 0) {
                                    val rate = (wins.toDouble() / totalHour) * 100
                                    appendLine("${String.format("%02d", hour)}:00: $wins/$totalHour (${String.format("%.1f", rate)}%)")
                                }
                            }
                            appendLine()
                            setupHourBarChart(hourStats)
                        }
                        
                        // По месяцам
                        val monthStats = analytics["byMonth"] as? Map<String, Pair<Int, Int>>
                        if (monthStats != null) {
                            appendLine("=== ПО МЕСЯЦАМ ===")
                            for ((month, stats) in monthStats) {
                                val (wins, totalMonth) = stats
                                val rate = if (totalMonth > 0) (wins.toDouble() / totalMonth) * 100 else 0.0
                                appendLine("$month: $wins/$totalMonth (${String.format("%.1f", rate)}%)")
                            }
                            appendLine()
                        }
                        
                        // По типам
                        val typeStats = analytics["byType"] as? Map<Int, Triple<Int, Int, Double>>
                        if (typeStats != null) {
                            appendLine("=== ПО ТИПАМ СТАВОК ===")
                            val typeNames = mapOf(
                                924 to "Победа/Тотал (sh>=sa)",
                                927 to "Фора 1 +1.5 (sh+1>sa)",
                                928 to "Фора 2 +1.5 (sa+1>=sh)"
                            )
                            for ((type, stats) in typeStats) {
                                val (wins, totalType, rate) = stats
                                appendLine("${typeNames[type]}: $wins/$totalType (${String.format("%.1f", rate)}%)")
                            }
                            appendLine()
                        }
                        
                        // Смешанные типы
                        val mixedStats = analytics["mixedTypes"] as? List<MixedTypeStats>
                        if (mixedStats != null) {
                            appendLine("=== СМЕШАННЫЕ ТИПЫ ЭКСПРЕССОВ ===")
                            for (mix in mixedStats) {
                                appendLine("${mix.typeCombination}: ${mix.wins}/${mix.total} (${String.format("%.1f", mix.rate)}%)")
                            }
                            appendLine()
                        }
                    }
                }
                
                tvStats.text = fullText.toString()
                
                if (total != null) {
                    setupPieChart(total)
                }
                
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
                
            } catch (e: Exception) {
                tvStats.text = "Ошибка при расчете аналитики: ${e.message}"
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
            }
        }
    }
    
    private fun setupPieChart(total: AnalyticsSummary) {
        if (total.winExpress == 0 && total.loseExpress == 0) {
            pieChart.clear()
            pieChart.centerText = "Нет данных"
            return
        }
        
        val entries = mutableListOf<PieEntry>()
        if (total.winExpress > 0) entries.add(PieEntry(total.winExpress.toFloat(), "Выигрыши"))
        if (total.loseExpress > 0) entries.add(PieEntry(total.loseExpress.toFloat(), "Проигрыши"))
        
        if (entries.isEmpty()) return
        
        val dataSet = PieDataSet(entries, "")
        dataSet.colors = listOf(Color.parseColor("#4CAF50"), Color.parseColor("#F44336"))
        dataSet.valueTextSize = 14f
        dataSet.sliceSpace = 3f
        
        pieChart.data = PieData(dataSet).apply { setValueFormatter(PercentFormatter(pieChart)) }
        pieChart.description.isEnabled = false
        pieChart.centerText = "Проходимость\n${String.format("%.1f", total.winRate)}%"
        pieChart.setCenterTextSize(16f)
        pieChart.setUsePercentValues(true)
        pieChart.isDrawHoleEnabled = true
        pieChart.holeRadius = 55f
        pieChart.legend.isEnabled = true
        pieChart.animateY(1000)
        pieChart.invalidate()
    }
    
    private fun setupDayBarChart(dayStats: Map<DayOfWeek, Pair<Int, Int>>) {
        val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        val entries = mutableListOf<BarEntry>()
        val colors = mutableListOf<Int>()
        
        DayOfWeek.entries.forEachIndexed { index, day ->
            val (wins, total) = dayStats[day] ?: Pair(0, 0)
            val rate = if (total > 0) (wins.toFloat() / total) * 100 else 0f
            entries.add(BarEntry(index.toFloat(), rate))
            colors.add(
                when {
                    total == 0 -> Color.GRAY
                    rate >= 50 -> Color.parseColor("#4CAF50")
                    else -> Color.parseColor("#F44336")
                }
            )
        }
        
        val dataSet = BarDataSet(entries, "").apply { this.colors = colors; valueTextSize = 12f }
        barChartDay.data = BarData(dataSet).apply { barWidth = 0.6f }
        barChartDay.description.isEnabled = false
        barChartDay.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(dayNames)
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
        }
        barChartDay.axisLeft.axisMinimum = 0f
        barChartDay.axisLeft.axisMaximum = 100f
        barChartDay.axisRight.isEnabled = false
        barChartDay.animateY(1000)
        barChartDay.invalidate()
    }
    
    private fun setupHourBarChart(hourStats: TreeMap<Int, Pair<Int, Int>>) {
        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()
        val colors = mutableListOf<Int>()
        
        for ((hour, stats) in hourStats) {
            val (wins, total) = stats
            val rate = if (total > 0) (wins.toFloat() / total) * 100 else 0f
            entries.add(BarEntry(hour.toFloat(), rate))
            labels.add(String.format("%02d", hour))
            colors.add(
                when {
                    total == 0 -> Color.GRAY
                    rate >= 50 -> Color.parseColor("#2196F3")
                    else -> Color.parseColor("#FF9800")
                }
            )
        }
        
        val dataSet = BarDataSet(entries, "").apply { this.colors = colors; valueTextSize = 10f }
        barChartHour.data = BarData(dataSet).apply { barWidth = 0.7f }
        barChartHour.description.isEnabled = false
        barChartHour.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            labelRotationAngle = -45f
        }
        barChartHour.axisLeft.axisMinimum = 0f
        barChartHour.axisLeft.axisMaximum = 100f
        barChartHour.axisRight.isEnabled = false
        barChartHour.animateY(1000)
        barChartHour.invalidate()
    }
}