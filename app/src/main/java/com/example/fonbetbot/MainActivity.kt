// MainActivity.kt — С ПЕРЕКЛЮЧАТЕЛЕМ АКТИВНЫЕ/ВСЕ
package com.example.fonbetbot

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity() {
    
    private lateinit var scrollView: ScrollView
    private lateinit var layoutDetailContent: LinearLayout
    private lateinit var btnAnalytics: Button
    private lateinit var tvDetailTitle: TextView
    private lateinit var layoutDetailTable: View
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var btnClearLogs: Button
    private lateinit var btnSettings: Button
    private lateinit var switchFilter: Switch
    private lateinit var tvFilterLabel: TextView
    private lateinit var database: AppDatabase
    private lateinit var analyticsEngine: AnalyticsEngine
    
    private var allExpressResults = listOf<ExpressResultSimplified>()
    private var allExpressResultsCache = listOf<ExpressResultSimplified>()
    
    private val PAGE_SIZE = 30
    private var currentPage = 0
    private var isLoading = false
    private var allPagesLoaded = false
    
    private val expandedExpressIds = mutableSetOf<Int>()
    
    private var savedExpandedIds: Set<Int>? = null
    private var savedScrollY: Int = 0
    private var showOnlyActive: Boolean = false
    
    private val logLines = mutableListOf<String>()
    private val MAX_LOG_LINES = 500
    
    // Константа для определения активного статуса (в часах)
    private val ACTIVE_HOURS = 2L
    
    companion object {
        private const val TAG = "MainActivity"
        private const val COLOR_GOLD = "#F0B90B"
        private const val COLOR_GREEN = "#03A66D"
        private const val COLOR_RED = "#CF304A"
        private const val COLOR_TEXT_PRIMARY = "#EAECEF"
        private const val COLOR_TEXT_SECONDARY = "#848E9C"
        private const val COLOR_MATCH_BG = "#161A1E"
        private const val COLOR_MATCH_HEADER_BG = "#1A1F26"
        private const val COLOR_GRID = "#2B3139"
        private const val COLOR_ACTIVE_ROW = "#2B2510"
        private const val COLOR_FINISHED_ROW = "#1E2329"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        scrollView = findViewById(R.id.scroll_view)
        layoutDetailTable = findViewById(R.id.layout_detail_table)
        btnAnalytics = findViewById(R.id.btn_analytics)
        tvDetailTitle = findViewById(R.id.tv_detail_title)
        layoutDetailContent = findViewById(R.id.layout_detail_content)
        tvLogs = findViewById(R.id.tv_logs)
        scrollLogs = findViewById(R.id.scroll_logs)
        btnClearLogs = findViewById(R.id.btn_clear_logs)
        btnSettings = findViewById(R.id.btn_settings)
        switchFilter = findViewById(R.id.switch_filter)
        tvFilterLabel = findViewById(R.id.tv_filter_label)
        
        // Показываем переключатель фильтра
        switchFilter.visibility = View.VISIBLE
        tvFilterLabel.visibility = View.VISIBLE
        
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        database = AppDatabase.getDatabase(this)
        analyticsEngine = AnalyticsEngine(database)
        
        btnAnalytics.setOnClickListener {
            addLog("Переход в Аналитику")
            startActivity(Intent(this, AnalyticsActivity::class.java))
        }
        
        btnClearLogs.setOnClickListener { clearLogs() }
        
        // Переключатель фильтра
        switchFilter.setOnCheckedChangeListener { _, isChecked ->
            showOnlyActive = isChecked
            tvFilterLabel.text = if (isChecked) "Активные" else "Все"
            
            val filterText = if (isChecked) "активные" else "все"
            addLog("🔍 Фильтр: показываем $filterText экспрессы")
            
            applyFilterAndReload()
        }
        
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            if (!isLoading && !allPagesLoaded) {
                val child = scrollView.getChildAt(0) as? ViewGroup ?: return@addOnScrollChangedListener
                val scrollY = scrollView.scrollY
                val totalHeight = child.height - scrollView.height
                if (totalHeight > 0 && scrollY >= totalHeight - 200) loadNextPage()
            }
        }
        
        addLog("MainActivity создана (автостатус по времени, порог: ${ACTIVE_HOURS}ч, фильтр: все)")
        loadData()
    }
    
    override fun onResume() {
        super.onResume()
        refreshData()
    }
    
    override fun onPause() {
        super.onPause()
        savedExpandedIds = expandedExpressIds.toSet()
        savedScrollY = scrollView.scrollY
    }
    
    // ========== ОПРЕДЕЛЕНИЕ СТАТУСА ПО ВРЕМЕНИ ==========
    
    private fun getExpressStatus(express: ExpressResultSimplified): ExpressStatus {
        val now = LocalDateTime.now()
        val hoursSinceCreation = ChronoUnit.HOURS.between(express.dateTime, now)
        
        return if (hoursSinceCreation < ACTIVE_HOURS) {
            ExpressStatus.ACTIVE
        } else {
            ExpressStatus.FINISHED
        }
    }
    
    private fun getStatusText(status: ExpressStatus): String {
        return when (status) {
            ExpressStatus.ACTIVE -> "АКТИВЕН"
            ExpressStatus.FINISHED -> "ЗАВЕРШЁН"
        }
    }
    
    private fun getStatusColor(status: ExpressStatus): String {
        return when (status) {
            ExpressStatus.ACTIVE -> COLOR_GOLD
            ExpressStatus.FINISHED -> COLOR_TEXT_SECONDARY
        }
    }
    
    private fun getRowBackgroundColor(status: ExpressStatus): String {
        return when (status) {
            ExpressStatus.ACTIVE -> COLOR_ACTIVE_ROW
            ExpressStatus.FINISHED -> COLOR_FINISHED_ROW
        }
    }
    
    enum class ExpressStatus {
        ACTIVE,
        FINISHED
    }
    
    // ========== ФИЛЬТР ==========
    
    private fun applyFilterAndReload() {
        lifecycleScope.launch {
            try {
                if (allExpressResultsCache.isEmpty()) return@launch
                
                allExpressResults = if (showOnlyActive) {
                    allExpressResultsCache.filter { getExpressStatus(it) == ExpressStatus.ACTIVE }
                } else {
                    allExpressResultsCache
                }
                
                val filterText = if (showOnlyActive) "активных" else "всех"
                val activeCount = allExpressResultsCache.count { getExpressStatus(it) == ExpressStatus.ACTIVE }
                addLog("📊 Найдено ${allExpressResults.size} $filterText экспрессов (всего активных: $activeCount)")
                
                resetPagination()
                loadExpressPage()
            } catch (e: Exception) {
                addLog("Ошибка фильтрации: ${e.message}")
            }
        }
    }
    
    // ========== ПАГИНАЦИЯ ==========
    
    private fun resetPagination() {
        currentPage = 0
        allPagesLoaded = false
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
                    if (start >= allExpressResults.size) emptyList()
                    else allExpressResults.subList(start, end).toList()
                }
                
                if (pageData.isEmpty() && currentPage == 0 && allExpressResults.isEmpty()) {
                    val message = if (showOnlyActive) "Нет активных экспрессов" else "Нет данных. Импортируйте файлы в Аналитике."
                    tvDetailTitle.text = message
                    layoutDetailTable.visibility = View.VISIBLE
                    isLoading = false
                    return@launch
                }
                
                if (pageData.isEmpty()) {
                    allPagesLoaded = true
                    isLoading = false
                    return@launch
                }
                
                appendExpressRows(pageData)
                currentPage++
                
                if (currentPage == 1 && savedScrollY > 0) {
                    scrollView.post { scrollView.scrollTo(0, savedScrollY) }
                }
                
                // Обновляем заголовок
                updateTitle()
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки", e)
            } finally {
                isLoading = false
            }
        }
    }
    
    private fun updateTitle() {
        val activeCount = allExpressResultsCache.count { getExpressStatus(it) == ExpressStatus.ACTIVE }
        val totalCount = allExpressResultsCache.size
        val shownCount = allExpressResults.size
        
        if (showOnlyActive) {
            tvDetailTitle.text = "Активные экспрессы ($shownCount из $activeCount) | ✏️ Дата/Время/Счёт"
        } else {
            tvDetailTitle.text = "Все экспрессы ($totalCount) | Активных: $activeCount | ✏️ Дата/Время/Счёт"
        }
    }
    
    private fun loadNextPage() {
        if (!isLoading && !allPagesLoaded) loadExpressPage()
    }
    
    // ========== ТАБЛИЦА ==========
    
    private fun buildHeaderRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(headerTv("ID", 60))
            addView(headerTv("Дата ✏️", 75))
            addView(headerTv("Время ✏️", 55))
            addView(headerTv("Коэфф.", 60))
            addView(headerTv("Статус", 80))
            addView(headerTv("Матчи", 110))
        }
    }
    
    private fun buildExpressRow(express: ExpressResultSimplified): View {
        val status = getExpressStatus(express)
        val rowBg = getRowBackgroundColor(status)
        val statusText = getStatusText(status)
        val statusColor = getStatusColor(status)
        
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tag = "express_${express.expId}"
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.parseColor(rowBg))
        }
        
        val expanded = express.expId in expandedExpressIds
        val idPrefix = if (expanded) "▼" else "▶"
        val dateStr = express.dateTime.format(DateTimeFormatter.ofPattern("dd.MM"))
        val timeStr = express.dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        val kfStr = if (express.totalStartKf > 0) String.format("%.2f", express.totalStartKf) else "-"
        
        val matchesShort = express.matches.joinToString(" | ") { 
            "${it.home.take(6)}-${it.away.take(6)} ${it.sh}:${it.sa}" 
        }
        
        // Вычисляем оставшееся время для активных
        val timeInfo = if (status == ExpressStatus.ACTIVE) {
            val now = LocalDateTime.now()
            val minutesUntilFinish = ChronoUnit.MINUTES.between(now, express.dateTime.plusHours(ACTIVE_HOURS))
            if (minutesUntilFinish > 0) " (${minutesUntilFinish}м)" else ""
        } else ""
        
        // ID (кликабельный для раскрытия)
        row.addView(TextView(this).apply {
            text = "$idPrefix #${express.expId}"
            textSize = 10f
            setPadding(dp(4), dp(6), dp(4), dp(6))
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_GOLD))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { toggleExpress(express) }
        })
        
        // Дата (кликабельная для редактирования)
        row.addView(TextView(this).apply {
            text = dateStr
            textSize = 10f
            setPadding(dp(4), dp(6), dp(4), dp(6))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A2A1A"))
            setTextColor(Color.parseColor(COLOR_GREEN))
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(dp(75), ViewGroup.LayoutParams.WRAP_CONTENT)
            tag = "date_${express.expId}"
            setOnClickListener { showDatePicker(express) }
        })
        
        // Время (кликабельное для редактирования)
        row.addView(TextView(this).apply {
            text = timeStr
            textSize = 10f
            setPadding(dp(4), dp(6), dp(4), dp(6))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A2A1A"))
            setTextColor(Color.parseColor(COLOR_GREEN))
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(dp(55), ViewGroup.LayoutParams.WRAP_CONTENT)
            tag = "time_${express.expId}"
            setOnClickListener { showTimePicker(express) }
        })
        
        // Коэффициент
        row.addView(TextView(this).apply {
            text = kfStr
            textSize = 10f
            setPadding(dp(4), dp(6), dp(4), dp(6))
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_GOLD))
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        
        // Статус с оставшимся временем
        row.addView(TextView(this).apply {
            text = if (status == ExpressStatus.ACTIVE) "⚡ $statusText$timeInfo" else statusText
            textSize = 9f
            setPadding(dp(4), dp(6), dp(4), dp(6))
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(statusColor))
            setTypeface(null, if (status == ExpressStatus.ACTIVE) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT)
            tag = "status_${express.expId}"
        })
        
        // Матчи
        row.addView(TextView(this).apply {
            text = matchesShort
            textSize = 10f
            setPadding(dp(4), dp(6), dp(4), dp(6))
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        
        return row
    }
    
    private fun buildMatchDetailRows(express: ExpressResultSimplified): List<View> {
        val views = mutableListOf<View>()
        val totalWidth = 440
        
        val status = getExpressStatus(express)
        val statusText = getStatusText(status)
        val statusColor = getStatusColor(status)
        
        // Заголовок с информацией о статусе и кнопкой удаления
        views.add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor(COLOR_MATCH_HEADER_BG))
            tag = "match_header_${express.expId}"
            
            addView(TextView(this@MainActivity).apply {
                text = "Экспресс #${express.expId} | $statusText"
                textSize = 10f
                setPadding(dp(8), dp(6), dp(4), dp(6))
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(Color.parseColor(statusColor))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            
            // Время создания
            addView(TextView(this@MainActivity).apply {
                text = express.dateTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                textSize = 9f
                setPadding(dp(4), dp(6), dp(4), dp(6))
                gravity = Gravity.CENTER or Gravity.CENTER_VERTICAL
                setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
                layoutParams = LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            
            // Кнопка удаления экспресса
            addView(Button(this@MainActivity).apply {
                text = "✕"
                textSize = 12f
                setTextColor(Color.parseColor(COLOR_RED))
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(4), dp(2), dp(4), dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(28))
                setOnClickListener { showDeleteExpressDialog(express) }
            })
        })
        
        views.add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(Color.parseColor(COLOR_MATCH_HEADER_BG))
            tag = "match_subheader_${express.expId}"
            addView(matchHeaderTv("Команда 1", 90))
            addView(matchHeaderTv("Команда 2", 90))
            addView(matchHeaderTv("Счёт ✏️", 70))
            addView(matchHeaderTv("Коэф.", 50))
            addView(matchHeaderTv("Тип", 100))
            addView(matchHeaderTv("", 40))
        })
        
        for (match in express.matches) {
            val scoreText: String = if (match.sh > 0 || match.sa > 0) {
                "${match.sh}:${match.sa}"
            } else {
                "—"
            }
            
            val kfText: String = String.format("%.2f", match.startkf)
            
            val typeText: String = when (match.type) {
                924 -> "1X"
                927 -> "Ф1(+1.5)"
                928 -> "Ф2(+1.5)"
                else -> "Т${match.type}"
            }
            
            val ligaInfoText: String = match.liganame.take(40)
            
            views.add(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(Color.parseColor(if (match.sh > 0 || match.sa > 0) "#0A2317" else COLOR_MATCH_BG))
                tag = "match_${match.matchId}"
                
                addView(matchDataTv(match.home.take(12), 90, COLOR_TEXT_PRIMARY, 10f, false))
                addView(matchDataTv(match.away.take(12), 90, COLOR_TEXT_PRIMARY, 10f, false))
                
                addView(TextView(this@MainActivity).apply {
                    text = scoreText
                    textSize = 11f
                    setPadding(dp(4), dp(3), dp(4), dp(3))
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#1A2A1A"))
                    setTextColor(Color.parseColor(if (match.sh > 0 || match.sa > 0) COLOR_GREEN else COLOR_TEXT_SECONDARY))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    maxLines = 2
                    layoutParams = LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT)
                    tag = "match_score_${match.matchId}"
                    setOnClickListener { showEditScoreDialog(match) }
                })
                
                addView(matchDataTv(kfText, 50, COLOR_GOLD, 10f, false))
                addView(matchDataTv(typeText, 100, COLOR_TEXT_SECONDARY, 10f, false))
                
                addView(Button(this@MainActivity).apply {
                    text = "✕"
                    textSize = 10f
                    setTextColor(Color.parseColor(COLOR_RED))
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(dp(2), dp(1), dp(2), dp(1))
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(24))
                    setOnClickListener { showDeleteMatchDialog(match) }
                })
            })
            
            views.add(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(totalWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(Color.parseColor("#11161C"))
                tag = "match_info_${match.matchId}"
                addView(matchDataTv(ligaInfoText, totalWidth, COLOR_TEXT_SECONDARY, 9f, false))
            })
        }
        
        views.add(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(totalWidth), dp(2))
            setBackgroundColor(Color.parseColor(COLOR_GRID))
            tag = "sep_${express.expId}"
        })
        
        return views
    }
    
    // ========== ДИАЛОГИ РЕДАКТИРОВАНИЯ ==========
    
    private fun showDatePicker(express: ExpressResultSimplified) {
        val currentDate = express.dateTime.toLocalDate()
        
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val newDate = LocalDate.of(year, month + 1, dayOfMonth)
                val newDateTime = LocalDateTime.of(newDate, express.dateTime.toLocalTime())
                updateExpressDateTime(express.expId, newDateTime)
            },
            currentDate.year,
            currentDate.monthValue - 1,
            currentDate.dayOfMonth
        ).show()
    }
    
    private fun showTimePicker(express: ExpressResultSimplified) {
        val currentTime = express.dateTime.toLocalTime()
        
        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val newTime = LocalTime.of(hourOfDay, minute)
                val newDateTime = LocalDateTime.of(express.dateTime.toLocalDate(), newTime)
                updateExpressDateTime(express.expId, newDateTime)
            },
            currentTime.hour,
            currentTime.minute,
            true
        ).show()
    }
    
    private fun showEditScoreDialog(match: MatchResultSimplified) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            gravity = Gravity.CENTER
        }
        
        val etSh = EditText(this).apply {
            setText(match.sh.toString())
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            setBackgroundColor(Color.parseColor("#2B3139"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(44))
        }
        
        val tvSeparator = TextView(this).apply {
            text = "  :  "
            textSize = 18f
            setTextColor(Color.parseColor(COLOR_GOLD))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        val etSa = EditText(this).apply {
            setText(match.sa.toString())
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            setBackgroundColor(Color.parseColor("#2B3139"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(44))
        }
        
        dialogView.addView(etSh)
        dialogView.addView(tvSeparator)
        dialogView.addView(etSa)
        
        AlertDialog.Builder(this)
            .setTitle("Редактировать счёт\n${match.home} vs ${match.away}")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { dialog, _ ->
                val newSh = etSh.text.toString().toIntOrNull() ?: match.sh
                val newSa = etSa.text.toString().toIntOrNull() ?: match.sa
                updateMatchScore(match.matchId, newSh, newSa)
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    private fun showDeleteMatchDialog(match: MatchResultSimplified) {
        AlertDialog.Builder(this)
            .setTitle("Удалить матч?")
            .setMessage("${match.home} vs ${match.away}\nСчёт: ${match.sh}:${match.sa}")
            .setPositiveButton("Удалить") { dialog, _ ->
                deleteMatch(match.matchId)
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    private fun showDeleteExpressDialog(express: ExpressResultSimplified) {
        AlertDialog.Builder(this)
            .setTitle("Удалить экспресс #${express.expId}?")
            .setMessage("Будут удалены все матчи этого экспресса\nСтатус: ${getStatusText(getExpressStatus(express))}")
            .setPositiveButton("Удалить") { dialog, _ ->
                deleteExpress(express.expId)
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    // ========== ОПЕРАЦИИ С БД ==========
    
    private fun updateExpressDateTime(expId: Int, newDateTime: LocalDateTime) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val allExp = database.expDao().getAllExp()
                    val newCtString = newDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    
                    val updated = allExp.map { 
                        if (it.id_exp == expId) it.copy(ct = newCtString) else it 
                    }
                    database.expDao().deleteAll()
                    database.expDao().insertAll(updated)
                }
                
                val newStatus = if (ChronoUnit.HOURS.between(newDateTime, LocalDateTime.now()) < ACTIVE_HOURS) {
                    "АКТИВЕН"
                } else {
                    "ЗАВЕРШЁН"
                }
                
                addLog("✅ Экспресс #$expId: ${newDateTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))} → $newStatus")
                refreshData()
            } catch (e: Exception) {
                addLog("❌ Ошибка обновления даты/времени: ${e.message}")
            }
        }
    }
    
    private fun updateMatchScore(matchId: Long, newSh: Int, newSa: Int) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val allData = database.dataDao().getAllData()
                    val updated = allData.map { 
                        if (it.m_id == matchId) it.copy(sh = newSh, sa = newSa) else it 
                    }
                    database.dataDao().deleteAll()
                    database.dataDao().insertAll(updated)
                }
                addLog("✅ Счёт матча $matchId обновлён: $newSh:$newSa")
                refreshData()
            } catch (e: Exception) {
                addLog("❌ Ошибка обновления счёта: ${e.message}")
            }
        }
    }
    
    private fun deleteMatch(matchId: Long) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val allData = database.dataDao().getAllData()
                    val updated = allData.filter { it.m_id != matchId }
                    database.dataDao().deleteAll()
                    database.dataDao().insertAll(updated)
                }
                addLog("🗑️ Матч $matchId удалён")
                refreshData()
            } catch (e: Exception) {
                addLog("❌ Ошибка удаления матча: ${e.message}")
            }
        }
    }
    
    private fun deleteExpress(expId: Int) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val allData = database.dataDao().getAllData()
                    val allExp = database.expDao().getAllExp()
                    
                    val updatedData = allData.filter { it.id_exp != expId }
                    val updatedExp = allExp.filter { it.id_exp != expId }
                    
                    database.dataDao().deleteAll()
                    database.dataDao().insertAll(updatedData)
                    database.expDao().deleteAll()
                    database.expDao().insertAll(updatedExp)
                }
                addLog("🗑️ Экспресс #$expId удалён")
                refreshData()
            } catch (e: Exception) {
                addLog("❌ Ошибка удаления экспресса: ${e.message}")
            }
        }
    }
    
    // ========== РАСКРЫТИЕ/СКРЫТИЕ ==========
    
    private fun toggleExpress(express: ExpressResultSimplified) {
        if (express.expId in expandedExpressIds) {
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
        val row = layoutDetailContent.findViewWithTag<LinearLayout>("express_$expId") ?: return
        val tv = row.getChildAt(0) as? TextView ?: return
        tv.text = tv.text.toString().let { if (expanded) it.replace("▶", "▼") else it.replace("▼", "▶") }
    }
    
    private fun insertMatchRows(express: ExpressResultSimplified) {
        val row = layoutDetailContent.findViewWithTag<LinearLayout>("express_${express.expId}") ?: return
        val index = layoutDetailContent.indexOfChild(row)
        if (index == -1) return
        buildMatchDetailRows(express).forEachIndexed { i, v ->
            layoutDetailContent.addView(v, index + 1 + i)
        }
    }
    
    private fun removeMatchRows(expId: Int) {
        val toRemove = mutableListOf<View>()
        for (i in 0 until layoutDetailContent.childCount) {
            val tag = layoutDetailContent.getChildAt(i).tag as? String ?: continue
            if (tag.startsWith("match_header_$expId") ||
                tag.startsWith("match_subheader_$expId") ||
                tag.startsWith("match_") ||
                tag.startsWith("match_info_") ||
                tag == "sep_$expId") toRemove.add(layoutDetailContent.getChildAt(i))
        }
        toRemove.forEach { layoutDetailContent.removeView(it) }
    }
    
    private fun appendExpressRows(expresses: List<ExpressResultSimplified>) {
        if (expresses.isEmpty()) return
        layoutDetailTable.visibility = View.VISIBLE
        if (currentPage == 0) layoutDetailContent.addView(buildHeaderRow())
        expresses.forEach { express ->
            layoutDetailContent.addView(buildExpressRow(express))
            if (express.expId in expandedExpressIds) {
                insertMatchRows(express)
            }
        }
    }
    
    // ========== ФАБРИКИ ==========
    
    private fun headerTv(text: String, w: Int) = TextView(this).apply {
        this.text = text; textSize = 11f; setPadding(dp(4), dp(6), dp(4), dp(6))
        gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#2B3139"))
        setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY)); setTypeface(null, android.graphics.Typeface.BOLD)
        maxLines = 2; layoutParams = LinearLayout.LayoutParams(dp(w), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    
    private fun matchHeaderTv(text: String, w: Int) = TextView(this).apply {
        this.text = text; textSize = 10f; setPadding(dp(4), dp(4), dp(4), dp(4))
        gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor(COLOR_MATCH_HEADER_BG))
        setTextColor(Color.parseColor("#5E6673")); setTypeface(null, android.graphics.Typeface.BOLD)
        maxLines = 1; layoutParams = LinearLayout.LayoutParams(dp(w), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    
    private fun matchDataTv(text: String, w: Int, color: String, size: Float, bold: Boolean) = TextView(this).apply {
        this.text = text; this.textSize = size; setPadding(dp(4), dp(3), dp(4), dp(3))
        gravity = Gravity.CENTER; setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor(color))
        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(w), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    
    // ========== ЛОГИ ==========
    
    private fun addLog(message: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val logEntry = "$timestamp | $message"
        Log.d(TAG, message)
        synchronized(logLines) {
            logLines.add(logEntry)
            if (logLines.size > MAX_LOG_LINES) logLines.removeAt(0)
        }
        runOnUiThread {
            updateLogView()
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }
    }
    
    private fun clearLogs() {
        synchronized(logLines) {
            logLines.clear()
            logLines.add("${LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))} | Логи очищены")
        }
        updateLogView()
    }
    
    private fun updateLogView() {
        synchronized(logLines) { tvLogs.text = logLines.joinToString("\n") }
    }
    
    // ========== ЗАГРУЗКА ==========
    
    private fun loadData() {
        lifecycleScope.launch {
            try {
                tvDetailTitle.text = "Загрузка..."
                
                val expCount = withContext(Dispatchers.IO) { database.expDao().getAllExp().size }
                val dataCount = withContext(Dispatchers.IO) { database.dataDao().getAllData().size }
                
                if (expCount == 0 || dataCount == 0) {
                    tvDetailTitle.text = "Нет данных. Импортируйте файлы в Аналитике."
                    layoutDetailTable.visibility = View.VISIBLE
                    return@launch
                }
                
                val analytics = withContext(Dispatchers.IO) { analyticsEngine.calculateAnalytics() }
                allExpressResultsCache = ((analytics["allExpresses"] as? List<ExpressResultSimplified>) ?: emptyList())
                
                // Применяем фильтр
                allExpressResults = if (showOnlyActive) {
                    allExpressResultsCache.filter { getExpressStatus(it) == ExpressStatus.ACTIVE }
                } else {
                    allExpressResultsCache
                }
                
                if (savedExpandedIds != null) {
                    expandedExpressIds.addAll(savedExpandedIds!!)
                }
                
                resetPagination()
                loadExpressPage()
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка", e)
                tvDetailTitle.text = "Ошибка: ${e.message}"
            }
        }
    }
    
    private fun refreshData() {
        lifecycleScope.launch {
            try {
                val expCount = withContext(Dispatchers.IO) { database.expDao().getAllExp().size }
                val dataCount = withContext(Dispatchers.IO) { database.dataDao().getAllData().size }
                if (expCount > 0 && dataCount > 0) {
                    val analytics = withContext(Dispatchers.IO) { analyticsEngine.calculateAnalytics() }
                    allExpressResultsCache = ((analytics["allExpresses"] as? List<ExpressResultSimplified>) ?: emptyList())
                    
                    // Применяем фильтр
                    allExpressResults = if (showOnlyActive) {
                        allExpressResultsCache.filter { getExpressStatus(it) == ExpressStatus.ACTIVE }
                    } else {
                        allExpressResultsCache
                    }
                    
                    val restoredIds = savedExpandedIds ?: emptySet()
                    expandedExpressIds.clear()
                    expandedExpressIds.addAll(restoredIds)
                    
                    resetPagination()
                    loadExpressPage()
                }
            } catch (e: Exception) {
                addLog("Ошибка refreshData: ${e.message}")
            }
        }
    }
}