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
        
        btnRefresh.setOnClickListener {
            loadAnalytics()
        }
        
        btnBack.setOnClickListener {
            finish()
        }
        
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
                val dayDetails = analytics["dayDetails"] as? StringBuilder
                val hourDetails = analytics["hourDetails"] as? StringBuilder
                val monthDetails = analytics["monthDetails"] as? StringBuilder
                val typeDetails = analytics["typeDetails"] as? StringBuilder
                val detailedInfo = analytics["detailedInfo"] as? StringBuilder
                
                val fullText = buildString {
                    if (total != null) append(total.details)
                    if (dayDetails != null) append(dayDetails)
                    if (hourDetails != null) append(hourDetails)
                    if (monthDetails != null) append(monthDetails)
                    if (typeDetails != null) append(typeDetails)
                    if (detailedInfo != null) {
                        appendLine("=== ПОСЛЕДНИЕ ЭКСПРЕССЫ ===")
                        val lines = detailedInfo.toString().split("\n")
                        append(lines.take(100).joinToString("\n"))
                        if (lines.size > 100) {
                            append("\n... и еще ${lines.size - 100} строк")
                        }
                    }
                }
                
                tvStats.text = fullText.toString()
                
                setupPieChart(total)
                
                val dayStats = analytics["byDayOfWeek"] as? Map<DayOfWeek, Pair<Int, Int>>
                if (dayStats != null) {
                    setupDayBarChart(dayStats)
                }
                
                val hourStats = analytics["byHour"] as? TreeMap<Int, Pair<Int, Int>>
                if (hourStats != null) {
                    setupHourBarChart(hourStats)
                }
                
                btnRefresh.isEnabled = true
                btnRefresh.text = "Обновить аналитику"
                
            } catch (e: Exception) {
                tvStats.text = "Ошибка при расчете аналитики: ${e.message}\n${e.stackTraceToString()}"
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
        if (total.winExpress > 0) {
            entries.add(PieEntry(total.winExpress.toFloat(), "Выигрыши"))
        }
        if (total.loseExpress > 0) {
            entries.add(PieEntry(total.loseExpress.toFloat(), "Проигрыши"))
        }
        
        if (entries.isEmpty()) {
            pieChart.clear()
            return
        }
        
        val dataSet = PieDataSet(entries, "Результаты экспрессов")
        dataSet.colors = listOf(
            Color.parseColor("#4CAF50"),
            Color.parseColor("#F44336")
        )
        dataSet.valueTextColor = Color.BLACK
        dataSet.valueTextSize = 14f
        dataSet.sliceSpace = 3f
        
        val pieData = PieData(dataSet)
        pieData.setValueFormatter(PercentFormatter(pieChart))
        
        pieChart.apply {
            this.data = pieData
            description.isEnabled = true
            description.text = "Общая статистика"
            description.textSize = 14f
            centerText = "Проходимость\n${String.format("%.1f", total.winRate)}%"
            setCenterTextSize(16f)
            setUsePercentValues(true)
            isDrawHoleEnabled = true
            holeRadius = 60f
            transparentCircleRadius = 65f
            setEntryLabelColor(Color.BLACK)
            setEntryLabelTextSize(12f)
            legend.isEnabled = true
            legend.textSize = 14f
            animateY(1000)
            invalidate()
        }
    }
    
    private fun setupDayBarChart(dayStats: Map<DayOfWeek, Pair<Int, Int>>) {
        val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        val entries = mutableListOf<BarEntry>()
        val colors = mutableListOf<Int>()
        
        DayOfWeek.entries.forEachIndexed { index, day ->
            val stats = dayStats[day]
            if (stats != null && stats.second > 0) {
                val (wins, total) = stats
                val rate = (wins.toFloat() / total) * 100
                entries.add(BarEntry(index.toFloat(), rate))
                colors.add(
                    if (rate >= 50) Color.parseColor("#4CAF50")
                    else Color.parseColor("#F44336")
                )
            } else {
                entries.add(BarEntry(index.toFloat(), 0f))
                colors.add(Color.GRAY)
            }
        }
        
        val dataSet = BarDataSet(entries, "Проходимость %")
        dataSet.colors = colors
        dataSet.valueTextColor = Color.BLACK
        dataSet.valueTextSize = 12f
        
        val barData = BarData(dataSet)
        barData.barWidth = 0.6f
        
        barChartDay.apply {
            this.data = barData
            description.isEnabled = true
            description.text = "По дням недели"
            description.textSize = 12f
            xAxis.valueFormatter = IndexAxisValueFormatter(dayNames)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textSize = 12f
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f
            axisLeft.textSize = 10f
            axisRight.isEnabled = false
            legend.isEnabled = false
            setFitBars(true)
            animateY(1000)
            invalidate()
        }
    }
    
    private fun setupHourBarChart(hourStats: TreeMap<Int, Pair<Int, Int>>) {
        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()
        val colors = mutableListOf<Int>()
        
        for (hour in 0..23) {
            labels.add(String.format("%02d", hour))
            val stats = hourStats[hour]
            if (stats != null && stats.second > 0) {
                val (wins, total) = stats
                val rate = (wins.toFloat() / total) * 100
                entries.add(BarEntry(hour.toFloat(), rate))
                colors.add(
                    if (rate >= 50) Color.parseColor("#2196F3")
                    else Color.parseColor("#FF9800")
                )
            } else {
                entries.add(BarEntry(hour.toFloat(), 0f))
                colors.add(Color.GRAY)
            }
        }
        
        val dataSet = BarDataSet(entries, "Проходимость %")
        dataSet.colors = colors
        dataSet.valueTextColor = Color.BLACK
        dataSet.valueTextSize = 10f
        
        val barData = BarData(dataSet)
        barData.barWidth = 0.7f
        
        barChartHour.apply {
            this.data = barData
            description.isEnabled = true
            description.text = "По часам суток"
            description.textSize = 12f
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textSize = 10f
            xAxis.labelRotationAngle = -45f
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f
            axisLeft.textSize = 10f
            axisRight.isEnabled = false
            legend.isEnabled = false
            setFitBars(true)
            animateY(1000)
            invalidate()
        }
    }
}