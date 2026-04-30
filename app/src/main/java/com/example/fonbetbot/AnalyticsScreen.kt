// AnalyticsScreen.kt - ПОЛНЫЙ ЭКРАН АНАЛИТИКИ С ВКЛАДКАМИ, ИМПОРТОМ, ПРОСМОТРОМ И РЕДАКТИРОВАНИЕМ БД
package com.example.fonbetbot

import android.content.ContentValues
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log

// ==================== МОДЕЛИ ДАННЫХ ====================

data class AnalyticsSummary(
    val totalExpresses: Int = 0,
    val wonExpresses: Int = 0,
    val lostExpresses: Int = 0,
    val activeExpresses: Int = 0,
    val cancelledExpresses: Int = 0,
    val totalProfit: Double = 0.0,
    val totalLoss: Double = 0.0,
    val roi: Double = 0.0,
    val winRate: Double = 0.0,
    val avgOdds: Double = 0.0,
    val bestOdds: Double = 0.0,
    val worstOdds: Double = 0.0,
    val totalBetsAmount: Double = 0.0,
    val netProfit: Double = 0.0,
    val profitPercent: Double = 0.0,
    val wonAmount: Double = 0.0,
    val lostAmount: Double = 0.0
)

data class TypeAnalytics(
    val typeName: String,
    val typeId: Int,
    val totalBets: Int = 0,
    val wonBets: Int = 0,
    val lostBets: Int = 0,
    val winRate: Double = 0.0,
    val profit: Double = 0.0
)

data class DailyStats(
    val date: String,
    val expressesCount: Int = 0,
    val wonCount: Int = 0,
    val lostCount: Int = 0,
    val profit: Double = 0.0,
    val winRate: Double = 0.0
)

data class MatchAnalytics(
    val teamName: String,
    val totalAppearances: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val winRate: Double = 0.0
)

data class PieSlice(
    val label: String,
    val count: Int,
    val percentage: Float,
    val color: Color,
    val amount: Double = 0.0
)

data class HourlyStats(
    val hour: Int,
    val totalExpresses: Int = 0,
    val wonExpresses: Int = 0,
    val lostExpresses: Int = 0,
    val winRate: Double = 0.0,
    val totalProfit: Double = 0.0
)

// Модели для таблиц БД
data class ExpressDbItem(
    val id: Long,
    val idExp: Int,
    val kfall: Double,
    val sumbet: Double,
    val stsAll: Int,
    val ct: Long,
    val strategy: String,
    val eventsCount: Int,
    val potentialWin: Double,
    val profitLoss: Double
)

data class MatchDbItem(
    val id: Long,
    val expressId: Long,
    val mId: Int,
    val homeTeam: String,
    val awayTeam: String,
    val startOdds: Double,
    val betType: Int,
    val status: Int,
    val homeScore: Int,
    val awayScore: Int,
    val matchTime: Int,
    val isFinalized: Int
)

// ==================== ФУНКЦИЯ ЗАГРУЗКИ ДАННЫХ БД ====================

private fun loadDatabaseData(
    dbHelper: DatabaseHelper,
    callback: (List<ExpressDbItem>, Map<Long, List<MatchDbItem>>) -> Unit
) {
    try {
        val db = dbHelper.readableDatabase
        
        // Загружаем экспрессы
        val expresses = mutableListOf<ExpressDbItem>()
        val expressCursor = db.rawQuery("""
            SELECT id, id_exp, kfall, sumbet, sts_all, ct, strategy, events_count, potential_win, profit_loss
            FROM express_bets ORDER BY ct DESC
        """, null)
        
        while (expressCursor.moveToNext()) {
            expresses.add(ExpressDbItem(
                id = expressCursor.getLong(0),
                idExp = expressCursor.getInt(1),
                kfall = expressCursor.getDouble(2),
                sumbet = expressCursor.getDouble(3),
                stsAll = expressCursor.getInt(4),
                ct = expressCursor.getLong(5),
                strategy = expressCursor.getString(6) ?: "",
                eventsCount = expressCursor.getInt(7),
                potentialWin = expressCursor.getDouble(8),
                profitLoss = expressCursor.getDouble(9)
            ))
        }
        expressCursor.close()
        
        // Загружаем все матчи
        val matchesMap = mutableMapOf<Long, MutableList<MatchDbItem>>()
        val matchesCursor = db.rawQuery("""
            SELECT id, express_id, m_id, COALESCE(home_team, ''), COALESCE(away_team, ''), 
                   start_odds, bet_type, status, home_score, away_score, match_time, is_finalized
            FROM express_events ORDER BY id ASC
        """, null)
        
        while (matchesCursor.moveToNext()) {
            val expressId = matchesCursor.getLong(1)
            val match = MatchDbItem(
                id = matchesCursor.getLong(0),
                expressId = expressId,
                mId = matchesCursor.getInt(2),
                homeTeam = matchesCursor.getString(3) ?: "",
                awayTeam = matchesCursor.getString(4) ?: "",
                startOdds = matchesCursor.getDouble(5),
                betType = matchesCursor.getInt(6),
                status = matchesCursor.getInt(7),
                homeScore = matchesCursor.getInt(8),
                awayScore = matchesCursor.getInt(9),
                matchTime = matchesCursor.getInt(10),
                isFinalized = matchesCursor.getInt(11)
            )
            
            matchesMap.getOrPut(expressId) { mutableListOf() }.add(match)
        }
        matchesCursor.close()
        
        callback(expresses, matchesMap)
    } catch (e: Exception) {
        Log.e("loadDatabaseData", "Ошибка: ${e.message}")
        callback(emptyList(), emptyMap())
    }
}

// ==================== ОСНОВНОЙ ЭКРАН АНАЛИТИКИ ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    dbHelper: DatabaseHelper,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Данные
    var analyticsSummary by remember { mutableStateOf<AnalyticsSummary?>(null) }
    var typeAnalytics by remember { mutableStateOf<List<TypeAnalytics>>(emptyList()) }
    var dailyStats by remember { mutableStateOf<List<DailyStats>>(emptyList()) }
    var matchAnalytics by remember { mutableStateOf<List<MatchAnalytics>>(emptyList()) }
    var pieSlices by remember { mutableStateOf<List<PieSlice>>(emptyList()) }
    var hourlyStats by remember { mutableStateOf<List<HourlyStats>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    // UI состояния
    var selectedMainTab by remember { mutableStateOf(0) } // 0 - Основная, 1 - Пай чарт, 2 - База данных
    var selectedPeriod by remember { mutableStateOf(0) }
    
    // Импорт
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var showImportSection by remember { mutableStateOf(false) }
    
    // База данных
    var databaseExpresses by remember { mutableStateOf<List<ExpressDbItem>>(emptyList()) }
    var databaseMatches by remember { mutableStateOf<Map<Long, List<MatchDbItem>>>(emptyMap()) }
    var expandedDbExpressIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedDbTab by remember { mutableStateOf(0) } // 0 - Экспрессы, 1 - Все матчи
    var showEditDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<Any?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }
    
    // Удаление
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Any?>(null) }

    val mainTabs = listOf("📊 Основная", "🥧 Пай чарт", "🗄 БД")
    val periodTitles = listOf("Сегодня", "Неделя", "Месяц", "Всё время")

    // Лаунчер для импорта матчей
    val dataFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isImporting = true
            importResult = null
            scope.launch(Dispatchers.IO) {
                try {
                    val importer = ExcelImporter(dbHelper)
                    val result = importer.importMatches(context, it)
                    withContext(Dispatchers.Main) {
                        importResult = "✅ Матчи: ${result.successCount} импортировано" +
                            if (result.errorCount > 0) ", ${result.errorCount} ошибок" else ""
                        isImporting = false
                        if (result.errors.isNotEmpty()) {
                            importResult += "\n⚠️ ${result.errors.take(3).joinToString("\n")}"
                        }
                        refreshTrigger++
                        loadDatabaseData(dbHelper) { expresses, matches ->
                            databaseExpresses = expresses
                            databaseMatches = matches
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        importResult = "❌ Ошибка: ${e.message}"
                        isImporting = false
                    }
                }
            }
        }
    }
    
    // Лаунчер для импорта экспрессов
    val expFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isImporting = true
            importResult = null
            scope.launch(Dispatchers.IO) {
                try {
                    val importer = ExcelImporter(dbHelper)
                    val result = importer.importExpresses(context, it)
                    withContext(Dispatchers.Main) {
                        importResult = "✅ Экспрессы: ${result.successCount} импортировано" +
                            if (result.errorCount > 0) ", ${result.errorCount} ошибок" else ""
                        isImporting = false
                        refreshTrigger++
                        loadDatabaseData(dbHelper) { expresses, matches ->
                            databaseExpresses = expresses
                            databaseMatches = matches
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        importResult = "❌ Ошибка: ${e.message}"
                        isImporting = false
                    }
                }
            }
        }
    }

    // Загрузка данных аналитики
    LaunchedEffect(isLoading, refreshTrigger, selectedPeriod) {
        try {
            isLoading = true
            withContext(Dispatchers.IO) {
                val allExpresses = dbHelper.getAllExpresses()
                val allMatches = dbHelper.getAllMatches()
                val filteredExpresses = filterByPeriod(allExpresses, selectedPeriod)
                
                withContext(Dispatchers.Main) {
                    analyticsSummary = calculateSummary(filteredExpresses)
                    typeAnalytics = calculateTypeAnalytics(filteredExpresses, allMatches)
                    dailyStats = calculateDailyStats(allExpresses, selectedPeriod)
                    matchAnalytics = calculateMatchAnalytics(filteredExpresses, allMatches)
                    pieSlices = calculatePieSlices(filteredExpresses)
                    hourlyStats = calculateHourlyStats(filteredExpresses)
                }
            }
            
            // Загружаем данные БД
            loadDatabaseData(dbHelper) { expresses, matches ->
                databaseExpresses = expresses
                databaseMatches = matches
            }
        } catch (e: Exception) {
            Log.e("AnalyticsScreen", "❌ Ошибка загрузки: ${e.message}")
        }
        isLoading = false
    }

    Scaffold(
        containerColor = BybitColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("📊 Аналитика", color = BybitColors.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад", tint = BybitColors.TextPrimary)
                    }
                },
                actions = {
                    // Кнопка обновления
                    IconButton(onClick = { refreshTrigger++ }) {
                        Icon(Icons.Default.Refresh, "Обновить", tint = BybitColors.Yellow)
                    }
                    // Кнопка импорта
                    IconButton(onClick = { showImportSection = !showImportSection }) {
                        Icon(
                            Icons.Default.FileUpload,
                            "Импорт",
                            tint = if (showImportSection) BybitColors.Yellow else BybitColors.TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BybitColors.Surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Секция импорта
            if (showImportSection) {
                ImportDataCard(
                    isImporting = isImporting,
                    importResult = importResult,
                    onImportMatches = {
                        dataFileLauncher.launch(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        )
                    },
                    onImportExpresses = {
                        expFileLauncher.launch(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        )
                    }
                )
            }
            
            // Главные вкладки
            TabRow(
                selectedTabIndex = selectedMainTab,
                containerColor = BybitColors.Surface,
                contentColor = BybitColors.Yellow
            ) {
                mainTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedMainTab == index,
                        onClick = { selectedMainTab = index },
                        text = {
                            Text(
                                title,
                                fontSize = 14.sp,
                                fontWeight = if (selectedMainTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedMainTab == index) BybitColors.Yellow else BybitColors.TextTertiary
                            )
                        }
                    )
                }
            }
            
            // Переключатель периода (только для вкладок аналитики)
            if (selectedMainTab != 2) {
                TabRow(
                    selectedTabIndex = selectedPeriod,
                    containerColor = BybitColors.Surface,
                    contentColor = BybitColors.Yellow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    periodTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedPeriod == index,
                            onClick = { selectedPeriod = index },
                            text = {
                                Text(
                                    title,
                                    fontSize = 12.sp,
                                    color = if (selectedPeriod == index) BybitColors.Yellow else BybitColors.TextTertiary
                                )
                            }
                        )
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = BybitColors.Yellow)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Загрузка...", color = BybitColors.TextSecondary)
                    }
                }
            } else {
                // Контент в зависимости от вкладки
                when (selectedMainTab) {
                    0 -> MainAnalyticsTab(
                        analyticsSummary = analyticsSummary,
                        typeAnalytics = typeAnalytics,
                        dailyStats = dailyStats,
                        matchAnalytics = matchAnalytics
                    )
                    1 -> PieChartTab(
                        pieSlices = pieSlices,
                        hourlyStats = hourlyStats,
                        analyticsSummary = analyticsSummary
                    )
                    2 -> DatabaseViewerTab(
                        expresses = databaseExpresses,
                        matches = databaseMatches,
                        expandedExpressIds = expandedDbExpressIds,
                        selectedSubTab = selectedDbTab,
                        onToggleExpand = { expressId ->
                            expandedDbExpressIds = if (expandedDbExpressIds.contains(expressId)) {
                                expandedDbExpressIds - expressId
                            } else {
                                expandedDbExpressIds + expressId
                            }
                        },
                        onSubTabChange = { selectedDbTab = it },
                        onEditItem = { item ->
                            editingItem = item
                            showEditDialog = true
                        },
                        onDeleteItem = { item ->
                            deleteTarget = item
                            showDeleteDialog = true
                        },
                        dbHelper = dbHelper,
                        onDataChanged = {
                            refreshTrigger++
                        }
                    )
                }
            }
        }
    }
    
    // Диалог редактирования
    if (showEditDialog && editingItem != null) {
        when (editingItem) {
            is ExpressDbItem -> {
                EditExpressDialog(
                    express = editingItem as ExpressDbItem,
                    dbHelper = dbHelper,
                    onDismiss = {
                        showEditDialog = false
                        editingItem = null
                    },
                    onSave = {
                        refreshTrigger++
                        showEditDialog = false
                        editingItem = null
                        loadDatabaseData(dbHelper) { expresses, matches ->
                            databaseExpresses = expresses
                            databaseMatches = matches
                        }
                    }
                )
            }
            is MatchDbItem -> {
                EditMatchDialog(
                    match = editingItem as MatchDbItem,
                    dbHelper = dbHelper,
                    onDismiss = {
                        showEditDialog = false
                        editingItem = null
                    },
                    onSave = {
                        refreshTrigger++
                        showEditDialog = false
                        editingItem = null
                        loadDatabaseData(dbHelper) { expresses, matches ->
                            databaseExpresses = expresses
                            databaseMatches = matches
                        }
                    }
                )
            }
        }
    }
    
    // Диалог удаления
    if (showDeleteDialog && deleteTarget != null) {
        DeleteConfirmDialog(
            target = deleteTarget,
            dbHelper = dbHelper,
            onDismiss = {
                showDeleteDialog = false
                deleteTarget = null
            },
            onConfirmed = {
                showDeleteDialog = false
                deleteTarget = null
                refreshTrigger++
                loadDatabaseData(dbHelper) { expresses, matches ->
                    databaseExpresses = expresses
                    databaseMatches = matches
                }
            }
        )
    }
}

// ==================== ВКЛАДКА "ОСНОВНАЯ" ====================

@Composable
fun MainAnalyticsTab(
    analyticsSummary: AnalyticsSummary?,
    typeAnalytics: List<TypeAnalytics>,
    dailyStats: List<DailyStats>,
    matchAnalytics: List<MatchAnalytics>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Общая статистика
        item {
            analyticsSummary?.let { summary ->
                SummaryCard(summary = summary)
            }
        }

        // Винрейт
        item {
            analyticsSummary?.let { summary ->
                WinRateCard(summary = summary)
            }
        }

        // График прибыли
        item {
            if (dailyStats.isNotEmpty()) {
                ProfitChartCard(dailyStats = dailyStats)
            }
        }

        // Статистика по типам
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "🎯 Статистика по типам ставок",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BybitColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (typeAnalytics.isEmpty()) {
                        Text(
                            "Нет данных",
                            fontSize = 13.sp,
                            color = BybitColors.TextTertiary,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        typeAnalytics.forEach { type ->
                            TypeAnalyticsRow(type = type)
                            if (type != typeAnalytics.last()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = BybitColors.Divider, thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }

        // Топ команд
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "⚽ Топ команд по выигрышам",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BybitColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (matchAnalytics.isEmpty()) {
                        Text("Нет данных", fontSize = 13.sp, color = BybitColors.TextTertiary,
                            modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        matchAnalytics
                            .sortedByDescending { it.winRate }
                            .take(10)
                            .forEach { match ->
                                MatchAnalyticsRow(match = match)
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                    }
                }
            }
        }

        // Ключевые метрики
        item {
            analyticsSummary?.let { summary ->
                KeyMetricsCard(summary = summary)
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ==================== ВКЛАДКА "ПАЙ ЧАРТ" ====================

@Composable
fun PieChartTab(
    pieSlices: List<PieSlice>,
    hourlyStats: List<HourlyStats>,
    analyticsSummary: AnalyticsSummary?
) {
    var selectedPieSubTab by remember { mutableStateOf(0) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Подвкладки
        TabRow(
            selectedTabIndex = selectedPieSubTab,
            containerColor = BybitColors.Surface,
            contentColor = BybitColors.Yellow
        ) {
            Tab(
                selected = selectedPieSubTab == 0,
                onClick = { selectedPieSubTab = 0 },
                text = { Text("🥧 Круговая", fontSize = 13.sp) }
            )
            Tab(
                selected = selectedPieSubTab == 1,
                onClick = { selectedPieSubTab = 1 },
                text = { Text("🕐 По часам", fontSize = 13.sp) }
            )
        }
        
        when (selectedPieSubTab) {
            0 -> PieChartContent(pieSlices = pieSlices, summary = analyticsSummary)
            1 -> HourlyStatsContent(hourlyStats = hourlyStats)
        }
    }
}

// ==================== КОНТЕНТ: КРУГОВАЯ ДИАГРАММА ====================

@Composable
fun PieChartContent(
    pieSlices: List<PieSlice>,
    summary: AnalyticsSummary?
) {
    val sliceColors = listOf(
        Color(0xFF0ECB81),
        Color(0xFFF6465D),
        Color(0xFFF0B90B),
        Color(0xFF848E9C)
    )
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Круговая диаграмма
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Распределение экспрессов",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = BybitColors.TextPrimary
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    if (pieSlices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Нет данных", fontSize = 14.sp, color = BybitColors.TextTertiary)
                        }
                    } else {
                        // Пончик-диаграмма
                        Box(
                            modifier = Modifier.size(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                var startAngle = -90f
                                pieSlices.forEachIndexed { index, slice ->
                                    val sweepAngle = slice.percentage * 3.6f
                                    drawArc(
                                        color = if (index < sliceColors.size) sliceColors[index] else Color.Gray,
                                        startAngle = startAngle,
                                        sweepAngle = sweepAngle,
                                        useCenter = true,
                                        size = Size(size.width, size.height)
                                    )
                                    startAngle += sweepAngle
                                }
                                
                                drawCircle(
                                    color = BybitColors.Surface,
                                    radius = size.minDimension * 0.32f
                                )
                            }
                            
                            // Текст в центре
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${summary?.totalExpresses ?: 0}",
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BybitColors.TextPrimary
                                )
                                Text(
                                    "всего",
                                    fontSize = 14.sp,
                                    color = BybitColors.TextSecondary
                                )
                                Text(
                                    "${String.format("%.1f", summary?.winRate ?: 0.0)}%",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if ((summary?.winRate ?: 0.0) >= 50) BybitColors.Green else BybitColors.Red
                                )
                                Text(
                                    "win rate",
                                    fontSize = 11.sp,
                                    color = BybitColors.TextTertiary
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Легенда
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pieSlices.forEachIndexed { index, slice ->
                                val color = if (index < sliceColors.size) sliceColors[index] else Color.Gray
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clip(CircleShape)
                                                .background(color)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                slice.label,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = BybitColors.TextPrimary
                                            )
                                            if (slice.amount > 0) {
                                                Text(
                                                    "${String.format("%.0f", slice.amount)} ₽",
                                                    fontSize = 11.sp,
                                                    color = BybitColors.TextTertiary
                                                )
                                            }
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "${slice.count}",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BybitColors.TextPrimary
                                        )
                                        Text(
                                            "${String.format("%.1f", slice.percentage)}%",
                                            fontSize = 12.sp,
                                            color = BybitColors.TextTertiary
                                        )
                                    }
                                }
                                
                                if (index < pieSlices.size - 1) {
                                    HorizontalDivider(
                                        color = BybitColors.Divider.copy(alpha = 0.3f),
                                        thickness = 0.5.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ==================== КОНТЕНТ: ПО ЧАСАМ ====================

@Composable
fun HourlyStatsContent(hourlyStats: List<HourlyStats>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Инфо-карточка
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "🕐 Активность по часам суток",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BybitColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Показывает в какое время суток экспрессы чаще выигрывают",
                        fontSize = 11.sp,
                        color = BybitColors.TextTertiary
                    )
                }
            }
        }

        // Баровый график
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "📊 Количество экспрессов по часам",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BybitColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (hourlyStats.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Нет данных", color = BybitColors.TextTertiary)
                        }
                    } else {
                        val maxCount = hourlyStats.maxOf { it.totalExpresses }.toFloat()
                        
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            items(hourlyStats) { stat ->
                                Column(
                                    modifier = Modifier.width(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val barHeight = if (maxCount > 0) {
                                        (stat.totalExpresses / maxCount * 60).coerceAtLeast(2f)
                                    } else 2f
                                    
                                    Spacer(modifier = Modifier.height((60 - barHeight).dp))
                                    
                                    Box(
                                        modifier = Modifier
                                            .width(16.dp)
                                            .height(barHeight.dp)
                                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                            .background(
                                                when {
                                                    stat.winRate >= 70 -> BybitColors.Green
                                                    stat.winRate >= 50 -> BybitColors.Yellow
                                                    stat.totalExpresses > 0 -> BybitColors.Red.copy(alpha = 0.5f)
                                                    else -> BybitColors.SurfaceLight
                                                }
                                            )
                                    )
                                    
                                    Spacer(modifier = Modifier.height(4.dp))
                                    
                                    Text(
                                        "${stat.hour}",
                                        fontSize = 8.sp,
                                        color = BybitColors.TextTertiary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "0  2  4  6  8  10  12  14  16  18  20  22",
                            fontSize = 8.sp,
                            color = BybitColors.TextTertiary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = BybitColors.Divider)
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Детальная таблица
                        hourlyStats
                            .filter { it.totalExpresses > 0 }
                            .sortedByDescending { it.winRate }
                            .take(12)
                            .forEach { stat ->
                                HourlyStatsRow(stat = stat)
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                    }
                }
            }
        }

        // Топ-3 лучших часа
        item {
            val topHours = hourlyStats
                .filter { it.totalExpresses >= 3 }
                .sortedByDescending { it.winRate }
                .take(3)
            
            if (topHours.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "🏆 Лучшие часы для ставок",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = BybitColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        topHours.forEachIndexed { index, stat ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (index) {
                                                0 -> Color(0xFFFFD700)
                                                1 -> Color(0xFFC0C0C0)
                                                else -> Color(0xFFCD7F32)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "${index + 1}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(12.dp))
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${stat.hour}:00 - ${stat.hour + 1}:00",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = BybitColors.TextPrimary
                                    )
                                    Text(
                                        "${stat.totalExpresses} экспр. | ${stat.wonExpresses} выигр.",
                                        fontSize = 11.sp,
                                        color = BybitColors.TextTertiary
                                    )
                                }
                                
                                Text(
                                    "${String.format("%.1f", stat.winRate)}%",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (stat.winRate >= 70) BybitColors.Green else BybitColors.Yellow
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ==================== НОВАЯ ВКЛАДКА "БАЗА ДАННЫХ" ====================

@Composable
fun DatabaseViewerTab(
    expresses: List<ExpressDbItem>,
    matches: Map<Long, List<MatchDbItem>>,
    expandedExpressIds: Set<Long>,
    selectedSubTab: Int,
    onToggleExpand: (Long) -> Unit,
    onSubTabChange: (Int) -> Unit,
    onEditItem: (Any) -> Unit,
    onDeleteItem: (Any) -> Unit,
    dbHelper: DatabaseHelper,
    onDataChanged: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Подвкладки
        TabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = BybitColors.Surface,
            contentColor = BybitColors.Yellow
        ) {
            Tab(
                selected = selectedSubTab == 0,
                onClick = { onSubTabChange(0) },
                text = { Text("🎯 Экспрессы (${expresses.size})", fontSize = 13.sp) }
            )
            Tab(
                selected = selectedSubTab == 1,
                onClick = { onSubTabChange(1) },
                text = { 
                    val totalMatches = matches.values.sumOf { it.size }
                    Text("⚽ Матчи ($totalMatches)", fontSize = 13.sp) 
                }
            )
        }
        
        when (selectedSubTab) {
            0 -> ExpressesListView(
                expresses = expresses,
                matches = matches,
                expandedExpressIds = expandedExpressIds,
                onToggleExpand = onToggleExpand,
                onEditItem = onEditItem,
                onDeleteItem = onDeleteItem,
                dbHelper = dbHelper,
                onDataChanged = onDataChanged
            )
            1 -> AllMatchesListView(
                matches = matches,
                onEditItem = onEditItem
            )
        }
    }
}

// ==================== СПИСОК ЭКСПРЕССОВ ====================

@Composable
fun ExpressesListView(
    expresses: List<ExpressDbItem>,
    matches: Map<Long, List<MatchDbItem>>,
    expandedExpressIds: Set<Long>,
    onToggleExpand: (Long) -> Unit,
    onEditItem: (Any) -> Unit,
    onDeleteItem: (Any) -> Unit,
    dbHelper: DatabaseHelper,
    onDataChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    if (expresses.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🗄 База данных пуста", fontSize = 16.sp, color = BybitColors.TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Импортируйте Excel файлы", fontSize = 13.sp, color = BybitColors.TextTertiary)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Экспрессов: ${expresses.size}",
                        fontSize = 13.sp,
                        color = BybitColors.TextSecondary
                    )
                    Text(
                        "Нажмите для деталей | ✏️ для редактирования",
                        fontSize = 10.sp,
                        color = BybitColors.TextTertiary
                    )
                }
            }
            
            items(expresses) { express ->
                val isExpanded = expandedExpressIds.contains(express.id)
                val expressMatches = matches[express.id] ?: emptyList()
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleExpand(express.id) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = BybitColors.SurfaceLight)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Заголовок экспресса
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "#${express.idExp}",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BybitColors.TextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    StatusBadge(status = express.stsAll)
                                }
                                
                                Text(
                                    "Ставка: ${express.sumbet.toInt()} ₽ | Кэф: ${String.format("%.2f", express.kfall)}",
                                    fontSize = 12.sp,
                                    color = BybitColors.TextSecondary
                                )
                                
                                if (express.strategy.isNotEmpty()) {
                                    Text(
                                        express.strategy,
                                        fontSize = 11.sp,
                                        color = BybitColors.TextTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            
                            Row {
                                // Кнопка удаления
                                IconButton(
                                    onClick = { onDeleteItem(express) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, "Удалить", tint = BybitColors.Red, modifier = Modifier.size(18.dp))
                                }
                                
                                // Кнопка редактирования
                                IconButton(
                                    onClick = { onEditItem(express) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Edit, "Редактировать", tint = BybitColors.Blue, modifier = Modifier.size(18.dp))
                                }
                                
                                // Кнопка раскрытия
                                Icon(
                                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    null,
                                    tint = BybitColors.TextSecondary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        
                        // Детали при раскрытии
                        if (isExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = BybitColors.Divider)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Потенц. выигрыш:", fontSize = 11.sp, color = BybitColors.TextTertiary)
                                Text(
                                    "${String.format("%.2f", express.potentialWin)} ₽",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BybitColors.Green
                                )
                            }
                            
                            if (express.profitLoss != 0.0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Прибыль/убыток:", fontSize = 11.sp, color = BybitColors.TextTertiary)
                                    Text(
                                        "${if (express.profitLoss > 0) "+" else ""}${String.format("%.2f", express.profitLoss)} ₽",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (express.profitLoss > 0) BybitColors.Green else BybitColors.Red
                                    )
                                }
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Создан:", fontSize = 11.sp, color = BybitColors.TextTertiary)
                                Text(
                                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                                        .format(Date(express.ct * 1000)),
                                    fontSize = 11.sp,
                                    color = BybitColors.TextSecondary
                                )
                            }
                            
                            // Список матчей экспресса
                            if (expressMatches.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Матчи (${expressMatches.size}):",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BybitColors.TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                expressMatches.forEach { match ->
                                    MatchRow(match = match, onEdit = { onEditItem(match) })
                                    if (match != expressMatches.last()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== ВСЕ МАТЧИ ====================

@Composable
fun AllMatchesListView(
    matches: Map<Long, List<MatchDbItem>>,
    onEditItem: (Any) -> Unit
) {
    val allMatches = matches.values.flatten()
    
    if (allMatches.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Нет матчей в базе", color = BybitColors.TextTertiary)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Всего матчей: ${allMatches.size}",
                        fontSize = 13.sp,
                        color = BybitColors.TextSecondary
                    )
                    Text(
                        "✏️ Нажмите для редактирования",
                        fontSize = 10.sp,
                        color = BybitColors.TextTertiary
                    )
                }
            }
            
            items(allMatches.sortedByDescending { it.id }) { match ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditItem(match) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = BybitColors.SurfaceLight)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (match.homeTeam.isNotEmpty()) "${match.homeTeam} vs ${match.awayTeam}" else "Матч #${match.mId}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BybitColors.TextPrimary
                                )
                                Text(
                                    "Экспресс #${match.expressId} | ${typeName(match.betType)} | Кэф: ${String.format("%.2f", match.startOdds)}",
                                    fontSize = 11.sp,
                                    color = BybitColors.TextTertiary
                                )
                            }
                            
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = when {
                                    match.isFinalized == 1 && match.status == 2 -> BybitColors.Green.copy(alpha = 0.2f)
                                    match.isFinalized == 1 && match.status == 1 -> BybitColors.Red.copy(alpha = 0.2f)
                                    else -> BybitColors.Yellow.copy(alpha = 0.2f)
                                }
                            ) {
                                Text(
                                    when {
                                        match.isFinalized == 1 && match.status == 2 -> "Выиграл"
                                        match.isFinalized == 1 && match.status == 1 -> "Проиграл"
                                        else -> "Активен"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        match.isFinalized == 1 && match.status == 2 -> BybitColors.Green
                                        match.isFinalized == 1 && match.status == 1 -> BybitColors.Red
                                        else -> BybitColors.Yellow
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Счёт: ${match.homeScore}:${match.awayScore}" + 
                                if (match.matchTime > 0) " · ${match.matchTime}'" else "",
                                fontSize = 11.sp,
                                color = BybitColors.TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== СТРОКА МАТЧА ====================

@Composable
fun MatchRow(match: MatchDbItem, onEdit: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        shape = RoundedCornerShape(6.dp),
        color = BybitColors.Surface.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (match.homeTeam.isNotEmpty()) "${match.homeTeam} vs ${match.awayTeam}" else "Матч #${match.mId}",
                    fontSize = 12.sp,
                    color = BybitColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    Text(
                        "${match.homeScore}:${match.awayScore}",
                        fontSize = 11.sp,
                        color = BybitColors.TextSecondary
                    )
                    if (match.matchTime > 0) {
                        Text(
                            " · ${match.matchTime}'",
                            fontSize = 11.sp,
                            color = BybitColors.TextTertiary
                        )
                    }
                    Text(
                        " · ${typeName(match.betType)}",
                        fontSize = 11.sp,
                        color = BybitColors.Yellow
                    )
                }
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when {
                        match.isFinalized == 1 && match.status == 2 -> BybitColors.Green.copy(alpha = 0.2f)
                        match.isFinalized == 1 && match.status == 1 -> BybitColors.Red.copy(alpha = 0.2f)
                        else -> BybitColors.Yellow.copy(alpha = 0.2f)
                    }
                ) {
                    Text(
                        when {
                            match.isFinalized == 1 && match.status == 2 -> "✅"
                            match.isFinalized == 1 && match.status == 1 -> "❌"
                            else -> "🔄"
                        },
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "×${String.format("%.2f", match.startOdds)}",
                    fontSize = 11.sp,
                    color = BybitColors.TextSecondary
                )
            }
        }
    }
}

// ==================== БЕЙДЖ СТАТУСА ====================

@Composable
fun StatusBadge(status: Int) {
    val (text, color) = when (status) {
        2 -> "Выиграл ✅" to BybitColors.Green
        1 -> "Проиграл ❌" to BybitColors.Red
        -1 -> "Заменён 🔄" to BybitColors.Yellow
        0 -> "Активен 🟢" to BybitColors.Yellow
        else -> "Завершён" to BybitColors.TextTertiary}
    
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ==================== ДИАЛОГ РЕДАКТИРОВАНИЯ ЭКСПРЕССА ====================

@Composable
fun EditExpressDialog(
    express: ExpressDbItem,
    dbHelper: DatabaseHelper,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    var editKfall by remember { mutableStateOf(express.kfall.toString()) }
    var editSumbet by remember { mutableStateOf(express.sumbet.toString()) }
    var editStsAll by remember { mutableStateOf(express.stsAll.toString()) }
    var editPotentialWin by remember { mutableStateOf(express.potentialWin.toString()) }
    var editProfitLoss by remember { mutableStateOf(express.profitLoss.toString()) }
    var editStrategy by remember { mutableStateOf(express.strategy) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BybitColors.Surface,
        titleContentColor = BybitColors.TextPrimary,
        textContentColor = BybitColors.TextSecondary,
        title = { Text("✏️ Редактировать экспресс #${express.idExp}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = editKfall,
                    onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) editKfall = it },
                    label = { Text("Коэффициент") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = bybitTextFieldColors()
                )
                
                OutlinedTextField(
                    value = editSumbet,
                    onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) editSumbet = it },
                    label = { Text("Сумма ставки (₽)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = bybitTextFieldColors()
                )
                
                OutlinedTextField(
                    value = editStsAll,
                    onValueChange = { editStsAll = it },
                    label = { Text("Статус (0=активен, 1=проиграл, 2=выиграл)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = bybitTextFieldColors()
                )
                
                OutlinedTextField(
                    value = editPotentialWin,
                    onValueChange = { if (it.matches(Regex("^-?\\d*\\.?\\d*$"))) editPotentialWin = it },
                    label = { Text("Потенциальный выигрыш (₽)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = bybitTextFieldColors()
                )
                
                OutlinedTextField(
                    value = editProfitLoss,
                    onValueChange = { if (it.matches(Regex("^-?\\d*\\.?\\d*$"))) editProfitLoss = it },
                    label = { Text("Прибыль/убыток (₽)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = bybitTextFieldColors()
                )
                
                OutlinedTextField(
                    value = editStrategy,
                    onValueChange = { editStrategy = it },
                    label = { Text("Стратегия") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = bybitTextFieldColors()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val db = dbHelper.writableDatabase
                    val values = ContentValues().apply {
                        put("kfall", editKfall.toDoubleOrNull() ?: express.kfall)
                        put("sumbet", editSumbet.toDoubleOrNull() ?: express.sumbet)
                        put("sts_all", editStsAll.toIntOrNull() ?: express.stsAll)
                        put("potential_win", editPotentialWin.toDoubleOrNull() ?: express.potentialWin)
                        put("profit_loss", editProfitLoss.toDoubleOrNull() ?: express.profitLoss)
                        put("strategy", editStrategy)
                        put("updated_at", System.currentTimeMillis() / 1000)
                    }
                    db.update("express_bets", values, "id = ?", arrayOf(express.id.toString()))
                    Toast.makeText(context, "✅ Экспресс #${express.idExp} обновлён", Toast.LENGTH_SHORT).show()
                    onSave()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BybitColors.Yellow)
            ) {
                Text("💾 Сохранить", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = BybitColors.TextSecondary)
            }
        }
    )
}

// ==================== ДИАЛОГ РЕДАКТИРОВАНИЯ МАТЧА ====================

@Composable
fun EditMatchDialog(
    match: MatchDbItem,
    dbHelper: DatabaseHelper,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    var editHomeScore by remember { mutableStateOf(match.homeScore.toString()) }
    var editAwayScore by remember { mutableStateOf(match.awayScore.toString()) }
    var editMatchTime by remember { mutableStateOf(match.matchTime.toString()) }
    var editStatus by remember { mutableStateOf(match.status.toString()) }
    var editIsFinalized by remember { mutableStateOf(match.isFinalized == 1) }
    var editOdds by remember { mutableStateOf(match.startOdds.toString()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BybitColors.Surface,
        titleContentColor = BybitColors.TextPrimary,
        textContentColor = BybitColors.TextSecondary,
        title = { 
            Text("✏️ Редактировать матч #${match.mId}") 
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (match.homeTeam.isNotEmpty()) "${match.homeTeam} vs ${match.awayTeam}" else "Матч #${match.mId}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = BybitColors.TextPrimary
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = editHomeScore,
                        onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) editHomeScore = it },
                        label = { Text("Хозяева") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = bybitTextFieldColors()
                    )
                    
                    OutlinedTextField(
                        value = editAwayScore,
                        onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) editAwayScore = it },
                        label = { Text("Гости") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = bybitTextFieldColors()
                    )
                }
                
                OutlinedTextField(
                    value = editMatchTime,
                    onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) editMatchTime = it },
                    label = { Text("Минута матча") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = bybitTextFieldColors()
                )
                
                OutlinedTextField(
                    value = editOdds,
                    onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) editOdds = it },
                    label = { Text("Коэффициент") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = bybitTextFieldColors()
                )
                
                OutlinedTextField(
                    value = editStatus,
                    onValueChange = { editStatus = it },
                    label = { Text("Статус (0=активен, 1=проиграл, 2=выиграл)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = bybitTextFieldColors()
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Матч завершён:", color = BybitColors.TextPrimary)
                    Switch(
                        checked = editIsFinalized,
                        onCheckedChange = { editIsFinalized = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BybitColors.Yellow,
                            checkedTrackColor = BybitColors.Yellow.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val db = dbHelper.writableDatabase
                    val values = ContentValues().apply {
                        put("home_score", editHomeScore.toIntOrNull() ?: match.homeScore)
                        put("away_score", editAwayScore.toIntOrNull() ?: match.awayScore)
                        put("match_time", editMatchTime.toIntOrNull() ?: match.matchTime)
                        put("start_odds", editOdds.toDoubleOrNull() ?: match.startOdds)
                        put("status", editStatus.toIntOrNull() ?: match.status)
                        put("is_finalized", if (editIsFinalized) 1 else 0)
                        put("updated_at", System.currentTimeMillis() / 1000)
                    }
                    db.update("express_events", values, "id = ?", arrayOf(match.id.toString()))
                    Toast.makeText(context, "✅ Матч #${match.mId} обновлён", Toast.LENGTH_SHORT).show()
                    onSave()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BybitColors.Yellow)
            ) {
                Text("💾 Сохранить", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = BybitColors.TextSecondary)
            }
        }
    )
}

// ==================== ДИАЛОГ ПОДТВЕРЖДЕНИЯ УДАЛЕНИЯ ====================

@Composable
fun DeleteConfirmDialog(
    target: Any?,
    dbHelper: DatabaseHelper,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    val context = LocalContext.current
    
    val (title, message) = when (target) {
        is ExpressDbItem -> {
            "Удалить экспресс #${target.idExp}?" to 
            "Будут удалены все связанные матчи. Это действие нельзя отменить."
        }
        is MatchDbItem -> {
            "Удалить матч #${target.mId}?" to 
            "Матч будет удалён из экспресса."
        }
        else -> "Подтверждение" to "Вы уверены?"
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BybitColors.Surface,
        titleContentColor = BybitColors.TextPrimary,
        textContentColor = BybitColors.TextSecondary,
        title = { Text("🗑 $title") },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = {
                    val db = dbHelper.writableDatabase
                    when (target) {
                        is ExpressDbItem -> {
                            db.delete("express_events", "express_id = ?", arrayOf(target.id.toString()))
                            db.delete("express_bets", "id = ?", arrayOf(target.id.toString()))
                            Toast.makeText(context, "🗑 Экспресс #${target.idExp} удалён", Toast.LENGTH_SHORT).show()
                        }
                        is MatchDbItem -> {
                            db.delete("express_events", "id = ?", arrayOf(target.id.toString()))
                            Toast.makeText(context, "🗑 Матч #${target.mId} удалён", Toast.LENGTH_SHORT).show()
                        }
                    }
                    onConfirmed()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BybitColors.Red)
            ) {
                Text("Удалить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = BybitColors.TextSecondary)
            }
        }
    )
}

// ==================== КАРТОЧКА ИМПОРТА ====================

@Composable
fun ImportDataCard(
    isImporting: Boolean,
    importResult: String?,
    onImportMatches: () -> Unit,
    onImportExpresses: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FileUpload, null, tint = BybitColors.Yellow, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Импорт данных", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = BybitColors.TextPrimary)
                }
                if (isImporting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = BybitColors.Yellow)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportMatches,
                    modifier = Modifier.weight(1f),
                    enabled = !isImporting,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BybitColors.Blue.copy(alpha = 0.8f))
                ) {
                    Icon(Icons.Default.TableChart, null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Матчи (data)", fontSize = 12.sp, color = Color.White)
                }
                
                Button(
                    onClick = onImportExpresses,
                    modifier = Modifier.weight(1f),
                    enabled = !isImporting,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BybitColors.Yellow)
                ) {
                    Icon(Icons.Default.ListAlt, null, modifier = Modifier.size(18.dp), tint = Color.Black)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Экспрессы (exp)", fontSize = 12.sp, color = Color.Black)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("Выберите .xlsx файл для импорта в базу данных", fontSize = 11.sp, color = BybitColors.TextTertiary)
            
            if (importResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (importResult.startsWith("✅")) BybitColors.Green.copy(alpha = 0.15f)
                    else BybitColors.Red.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = importResult,
                        fontSize = 11.sp,
                        color = if (importResult.startsWith("✅")) BybitColors.Green else BybitColors.Red,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

// ==================== КАРТОЧКИ ОСНОВНОЙ СТАТИСТИКИ ====================

@Composable
fun SummaryCard(summary: AnalyticsSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("📈 Общая статистика", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = BybitColors.TextPrimary)
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(value = "${summary.totalExpresses}", label = "Экспрессов", color = BybitColors.TextPrimary)
                StatItem(value = "${summary.wonExpresses}", label = "Выигрыши", color = BybitColors.Green)
                StatItem(value = "${summary.lostExpresses}", label = "Проигрыши", color = BybitColors.Red)
                StatItem(value = "${summary.activeExpresses}", label = "Активные", color = BybitColors.Yellow)
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = BybitColors.Divider)
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Чистая прибыль", fontSize = 13.sp, color = BybitColors.TextSecondary)
                    Text(
                        "${if (summary.netProfit >= 0) "+" else ""}${String.format("%.2f", summary.netProfit)} ₽",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        color = if (summary.netProfit >= 0) BybitColors.Green else BybitColors.Red
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("ROI", fontSize = 13.sp, color = BybitColors.TextSecondary)
                    Text(
                        "${if (summary.roi >= 0) "+" else ""}${String.format("%.2f", summary.roi)}%",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        color = if (summary.roi >= 0) BybitColors.Green else BybitColors.Red
                    )
                }
            }
        }
    }
}

@Composable
fun WinRateCard(summary: AnalyticsSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🎯 Процент побед", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = BybitColors.TextPrimary)
            Spacer(modifier = Modifier.height(16.dp))

            Box(modifier = Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 20.dp,
                    color = BybitColors.Red.copy(alpha = 0.3f),
                    trackColor = BybitColors.Green.copy(alpha = 0.3f)
                )

                CircularProgressIndicator(
                    progress = { summary.winRate.toFloat() / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 20.dp,
                    color = BybitColors.Green,
                    trackColor = Color.Transparent
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${String.format("%.1f", summary.winRate)}%", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = BybitColors.TextPrimary)
                    Text("${summary.wonExpresses}/${summary.totalExpresses}", fontSize = 13.sp, color = BybitColors.TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                LegendItem(color = BybitColors.Green, label = "Выигрыши (${summary.wonExpresses})")
                LegendItem(color = BybitColors.Red, label = "Проигрыши (${summary.lostExpresses})")
            }
        }
    }
}

@Composable
fun ProfitChartCard(dailyStats: List<DailyStats>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("💰 Динамика прибыли", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = BybitColors.TextPrimary)
            Spacer(modifier = Modifier.height(16.dp))

            if (dailyStats.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    Text("Нет данных", color = BybitColors.TextTertiary)
                }
            } else {
                val maxProfit = dailyStats.maxOf { it.profit }
                val minProfit = dailyStats.minOf { it.profit }
                val range = maxOf(maxProfit - minProfit, 1.0)

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        dailyStats.takeLast(7).forEach { stat ->
                            val barHeight = if (range > 0) {
                                ((stat.profit - minProfit) / range * 100).toInt().coerceIn(5, 120)
                            } else 5

                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text(
                                    "${if (stat.profit >= 0) "+" else ""}${String.format("%.0f", stat.profit)}",
                                    fontSize = 9.sp,
                                    color = if (stat.profit >= 0) BybitColors.Green else BybitColors.Red
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(20.dp).height(barHeight.dp)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(if (stat.profit >= 0) BybitColors.Green else BybitColors.Red)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(stat.date.takeLast(5), fontSize = 9.sp, color = BybitColors.TextTertiary)
                            }
                        }
                    }
                }

                val totalProfit = dailyStats.sumOf { it.profit }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = BybitColors.Divider)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Итого за период:", fontSize = 13.sp, color = BybitColors.TextSecondary)
                    Text(
                        "${if (totalProfit >= 0) "+" else ""}${String.format("%.2f", totalProfit)} ₽",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = if (totalProfit >= 0) BybitColors.Green else BybitColors.Red
                    )
                }
            }
        }
    }
}

@Composable
fun KeyMetricsCard(summary: AnalyticsSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("🔑 Ключевые метрики", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = BybitColors.TextPrimary)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricItem("Средний кэф", "${String.format("%.2f", summary.avgOdds)}")
                MetricItem("Лучший кэф", "${String.format("%.2f", summary.bestOdds)}")
                MetricItem("Худший кэф", "${String.format("%.2f", summary.worstOdds)}")
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BybitColors.Divider)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricItem("Всего ставок", "${summary.totalBetsAmount.toInt()} ₽")
                MetricItem("Прибыль", "${if (summary.totalProfit >= 0) "+" else ""}${String.format("%.0f", summary.totalProfit)} ₽")
                MetricItem("Убыток", "${String.format("%.0f", summary.totalLoss)} ₽")
            }
        }
    }
}

// ==================== МЕЛКИЕ КОМПОНЕНТЫ ====================

@Composable
fun StatItem(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = BybitColors.TextTertiary)
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, color = BybitColors.TextSecondary)
    }
}

@Composable
fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BybitColors.TextPrimary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = BybitColors.TextTertiary)
    }
}

@Composable
fun TypeAnalyticsRow(type: TypeAnalytics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(type.typeName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = BybitColors.TextPrimary)
            Text("Ставок: ${type.totalBets} | Выигрышей: ${type.wonBets} | Проигрышей: ${type.lostBets}",
                fontSize = 11.sp, color = BybitColors.TextTertiary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${String.format("%.1f", type.winRate)}%", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (type.winRate >= 50) BybitColors.Green else BybitColors.Red)
            Text("${if (type.profit >= 0) "+" else ""}${String.format("%.0f", type.profit)} ₽",
                fontSize = 12.sp, color = if (type.profit >= 0) BybitColors.Green else BybitColors.Red)
        }
    }
}

@Composable
fun MatchAnalyticsRow(match: MatchAnalytics) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(match.teamName.take(20), fontSize = 13.sp, color = BybitColors.TextPrimary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("${match.totalAppearances} матчей", fontSize = 11.sp, color = BybitColors.TextTertiary)
        }
        Text("${String.format("%.0f", match.winRate)}%", fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = if (match.winRate >= 50) BybitColors.Green else BybitColors.Red)
    }
}

@Composable
fun HourlyStatsRow(stat: HourlyStats) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(8.dp).clip(CircleShape).background(
                    when {
                        stat.winRate >= 70 -> BybitColors.Green
                        stat.winRate >= 50 -> BybitColors.Yellow
                        else -> BybitColors.Red.copy(alpha = 0.5f)
                    }
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("${stat.hour}:00 - ${stat.hour + 1}:00", fontSize = 12.sp, color = BybitColors.TextPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${stat.totalExpresses}", fontSize = 11.sp, color = BybitColors.TextTertiary)
            Text("${String.format("%.0f", stat.winRate)}%", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = when {
                    stat.winRate >= 70 -> BybitColors.Green
                    stat.winRate >= 50 -> BybitColors.Yellow
                    else -> BybitColors.Red
                })
        }
    }
}

// ==================== РАСЧЕТНЫЕ ФУНКЦИИ ====================

private fun filterByPeriod(expresses: List<ExpressInfo>, period: Int): List<ExpressInfo> {
    val now = System.currentTimeMillis() / 1000
    val startTime = when (period) {
        0 -> now - 86400
        1 -> now - 7 * 86400
        2 -> now - 30 * 86400
        else -> 0L
    }
    return expresses.filter { it.createdAt >= startTime }
}

private fun calculateSummary(expresses: List<ExpressInfo>): AnalyticsSummary {
    val totalExpresses = expresses.size
    
    val wonExpresses = expresses.count { it.stsAll == 2 }
    val lostExpresses = expresses.count { it.stsAll in listOf(1, -1) }  // Включаем -1
    val activeExpresses = expresses.count { it.stsAll == 0 }
    val cancelledExpresses = expresses.count { it.stsAll in listOf(-2, -3) }
    
    val totalProfit = expresses.filter { it.stsAll == 2 }.sumOf { it.potentialWin - it.sumbet }
    val totalLoss = expresses.filter { it.stsAll in listOf(1, -1) }.sumOf { it.sumbet }
    val netProfit = totalProfit - totalLoss
    val totalBetsAmount = expresses.sumOf { it.sumbet }
    
    val winRate = if (totalExpresses > 0) (wonExpresses.toDouble() / totalExpresses * 100) else 0.0
    val roi = if (totalBetsAmount > 0) (netProfit / totalBetsAmount * 100) else 0.0
    
    val kfalls = expresses.map { it.kfall }
    val avgOdds = if (kfalls.isNotEmpty()) kfalls.average() else 0.0
    val bestOdds = kfalls.maxOrNull() ?: 0.0
    val worstOdds = kfalls.minOrNull() ?: 0.0

    return AnalyticsSummary(
        totalExpresses = totalExpresses,
        wonExpresses = wonExpresses,
        lostExpresses = lostExpresses,
        activeExpresses = activeExpresses,
        cancelledExpresses = cancelledExpresses,
        totalProfit = totalProfit,
        totalLoss = totalLoss,
        roi = roi,
        winRate = winRate,
        avgOdds = avgOdds,
        bestOdds = bestOdds,
        worstOdds = worstOdds,
        totalBetsAmount = totalBetsAmount,
        netProfit = netProfit
    )
}

private fun calculateTypeAnalytics(expresses: List<ExpressInfo>, matches: List<MatchInfo>): List<TypeAnalytics> {
    val typeMap = mutableMapOf<Int, TypeAnalytics>()

    expresses.forEach { express ->
        val expressMatches = matches.filter { it.expressId == express.id }
        expressMatches.forEach { match ->
            val typeId = match.betType
            val typeName = typeName(match.betType)

            val current = typeMap.getOrPut(typeId) { TypeAnalytics(typeName = typeName, typeId = typeId) }
            typeMap[typeId] = current.copy(
                totalBets = current.totalBets + 1,
                wonBets = current.wonBets + if (match.status == 2 && match.isFinalized == 1) 1 else 0,
                lostBets = current.lostBets + if (match.status == 1 && match.isFinalized == 1) 1 else 0,
                profit = current.profit + if (match.status == 2 && match.isFinalized == 1) express.sumbet else 0.0
            )
        }
    }

    return typeMap.values.map { type ->
        type.copy(winRate = if (type.totalBets > 0) (type.wonBets.toDouble() / type.totalBets * 100) else 0.0)
    }
}

private fun calculateDailyStats(expresses: List<ExpressInfo>, period: Int): List<DailyStats> {
    val sdf = SimpleDateFormat("dd.MM", Locale.getDefault())
    val now = System.currentTimeMillis() / 1000
    val days = when (period) { 0 -> 1; 1 -> 7; 2 -> 30; else -> 30 }

    val groupedByDate = expresses
        .filter { it.createdAt >= now - days * 86400 }
        .groupBy { sdf.format(Date(it.createdAt * 1000)) }

    return groupedByDate.map { (date, list) ->
        val wonCount = list.count { it.stsAll == 2 }
        val lostCount = list.count { it.stsAll in listOf(1, -1) }
        val profit = list.filter { it.stsAll == 2 }.sumOf { it.potentialWin - it.sumbet } -
            list.filter { it.stsAll in listOf(1, -1) }.sumOf { it.sumbet }

        DailyStats(
            date = date,
            expressesCount = list.size,
            wonCount = wonCount,
            lostCount = lostCount,
            profit = profit,
            winRate = if (list.isNotEmpty()) (wonCount.toDouble() / list.size * 100) else 0.0
        )
    }.sortedBy { it.date }
}

private fun calculateMatchAnalytics(expresses: List<ExpressInfo>, matches: List<MatchInfo>): List<MatchAnalytics> {
    val teamMap = mutableMapOf<String, MatchAnalytics>()

    expresses.forEach { express ->
        val expressMatches = matches.filter { it.expressId == express.id }
        expressMatches.forEach { match ->
            val teamName = if (match.homeTeam.isNotEmpty()) match.homeTeam else "Команда #${match.mId}"
            val current = teamMap.getOrPut(teamName) { MatchAnalytics(teamName = teamName) }
            teamMap[teamName] = current.copy(
                totalAppearances = current.totalAppearances + 1,
                wins = current.wins + if (match.status == 2 && match.isFinalized == 1) 1 else 0,
                losses = current.losses + if (match.status == 1 && match.isFinalized == 1) 1 else 0,
                winRate = if (current.totalAppearances > 0)
                    ((current.wins + if (match.status == 2 && match.isFinalized == 1) 1 else 0).toDouble() /
                        (current.totalAppearances + 1) * 100)
                else if (match.status == 2 && match.isFinalized == 1) 100.0 else 0.0
            )
        }
    }

    return teamMap.values.toList()
}

private fun calculateHourlyStats(expresses: List<ExpressInfo>): List<HourlyStats> {
    val hoursMap = mutableMapOf<Int, MutableList<ExpressInfo>>()
    
    expresses.forEach { express ->
        // Используем поле ct (created time) вместо createdAt
        val timestamp = express.ct
        
        val hour = if (timestamp > 0) {
            try {
                // ct хранится в секундах (Unix timestamp)
                val date = Date(timestamp * 1000)
                val calendar = Calendar.getInstance().apply { time = date }
                calendar.get(Calendar.HOUR_OF_DAY)
            } catch (e: Exception) {
                // Пробуем альтернативный парсинг
                try {
                    val sdf = SimpleDateFormat("HH", Locale.getDefault())
                    sdf.format(Date(timestamp * 1000)).toInt()
                } catch (e2: Exception) {
                    -1
                }
            }
        } else {
            // Если ct = 0, пробуем createdAt
            try {
                val date = Date(express.createdAt * 1000)
                val calendar = Calendar.getInstance().apply { time = date }
                calendar.get(Calendar.HOUR_OF_DAY)
            } catch (e: Exception) {
                -1
            }
        }
        
        if (hour in 0..23) {
            hoursMap.getOrPut(hour) { mutableListOf() }.add(express)
        } else {
            Log.w("calculateHourlyStats", "Не удалось определить час для экспресса #${express.idExp}, ct=${express.ct}, createdAt=${express.createdAt}")
        }
    }
    
    // Логируем распределение для отладки
    Log.d("calculateHourlyStats", "Распределение по часам:")
    hoursMap.forEach { (hour, list) ->
        Log.d("calculateHourlyStats", "  ${hour}:00 - ${list.size} экспрессов")
    }
    
    return (0..23).map { hour ->
        val list = hoursMap[hour] ?: emptyList()
        val wonCount = list.count { it.stsAll == 2 }
        val lostCount = list.count { it.stsAll in listOf(1, -1) }
        
        HourlyStats(
            hour = hour,
            totalExpresses = list.size,
            wonExpresses = wonCount,
            lostExpresses = lostCount,
            winRate = if (list.isNotEmpty()) (wonCount.toDouble() / list.size * 100) else 0.0,
            totalProfit = list.filter { it.stsAll == 2 }.sumOf { it.potentialWin - it.sumbet } -
                list.filter { it.stsAll in listOf(1, -1) }.sumOf { it.sumbet }
        )
    }
}

private fun calculatePieSlices(expresses: List<ExpressInfo>): List<PieSlice> {
    val wonCount = expresses.count { it.stsAll == 2 }
    val lostCount = expresses.count { it.stsAll in listOf(1, -1) }
    val activeCount = expresses.count { it.stsAll == 0 }
    val otherCount = expresses.size - wonCount - lostCount - activeCount
    val total = expresses.size

    if (total == 0) return emptyList()

    return listOfNotNull(
        PieSlice("✅ Выигрыши", wonCount, wonCount.toFloat() / total * 100,
            Color(0xFF0ECB81), expresses.filter { it.stsAll == 2 }.sumOf { it.potentialWin }
        ).takeIf { wonCount > 0 },
        PieSlice("❌ Проигрыши", lostCount, lostCount.toFloat() / total * 100,
            Color(0xFFF6465D), expresses.filter { it.stsAll in listOf(1, -1) }.sumOf { it.sumbet }
        ).takeIf { lostCount > 0 },
        PieSlice("🔄 Активные", activeCount, activeCount.toFloat() / total * 100,
            Color(0xFFF0B90B), expresses.filter { it.stsAll == 0 }.sumOf { it.sumbet }
        ).takeIf { activeCount > 0 },
        PieSlice("⏹ Другое", otherCount, otherCount.toFloat() / total * 100,
            Color(0xFF848E9C), 0.0
        ).takeIf { otherCount > 0 }
    )
}
