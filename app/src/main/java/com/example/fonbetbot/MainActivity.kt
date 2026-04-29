// MainActivity.kt - РЕДИЗАЙН В СТИЛЕ BYBIT
package com.example.fonbetbot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// ==================== ЦВЕТОВАЯ ПАЛИТРА BYBIT ====================

object BybitColors {
    val Background = Color(0xFF0B0E11)          // Основной фон
    val Surface = Color(0xFF1E2329)             // Карточки
    val SurfaceLight = Color(0xFF2B3139)        // Вторичные карточки
    val Yellow = Color(0xFFF0B90B)              // Акцентный жёлтый (Bybit)
    val YellowLight = Color(0xFFF8D33A)         // Светлый жёлтый
    val Green = Color(0xFF0ECB81)               // Прибыль/успех
    val Red = Color(0xFFF6465D)                 // Убыток/ошибка
    val TextPrimary = Color(0xFFEAECEF)         // Основной текст
    val TextSecondary = Color(0xFF848E9C)       // Вторичный текст
    val TextTertiary = Color(0xFF5E6673)        // Третичный текст
    val Divider = Color(0xFF2B3139)             // Разделители
    val Blue = Color(0xFF3772FF)                // Для графиков/ссылок
}

// ==================== ОСНОВНАЯ АКТИВНОСТЬ ====================

class MainActivity : ComponentActivity() {
    
    private lateinit var dbHelper: DatabaseHelper
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Требуются разрешения для работы бота", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
            }
        }
        
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
        
        dbHelper = DatabaseHelper(this)
        
        setContent {
            BybitTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BybitColors.Background
                ) {
                    FonbetBotApp(dbHelper)
                }
            }
        }
    }
}

// ==================== ТЕМА BYBIT ====================

@Composable
fun BybitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BybitColors.Background,
            surface = BybitColors.Surface,
            primary = BybitColors.Yellow,
            secondary = BybitColors.YellowLight,
            onBackground = BybitColors.TextPrimary,
            onSurface = BybitColors.TextPrimary,
            onPrimary = Color.Black,
            error = BybitColors.Red,
            onError = Color.White,
            outline = BybitColors.Divider
        ),
        content = content
    )
}

// ==================== МОДЕЛЬ НАВИГАЦИИ ====================

enum class BottomNavItem(
    val label: String,
    val icon: @Composable () -> Unit
) {
    HOME("Главная", { Icon(Icons.Default.Home, null, tint = BybitColors.TextPrimary) }),
    BETS("Экспрессы", { Icon(Icons.Default.ListAlt, null, tint = BybitColors.TextPrimary) }),
    STATS("Статистика", { Icon(Icons.Default.BarChart, null, tint = BybitColors.TextPrimary) }),
    PROFILE("Аккаунт", { Icon(Icons.Default.Person, null, tint = BybitColors.TextPrimary) })
}

// ==================== ГЛАВНЫЙ КОМПОНЕНТ ====================

data class AuthData(
    val fsid: String,
    val deviceId: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FonbetBotApp(dbHelper: DatabaseHelper) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE) }
    
    var currentScreen by remember { mutableStateOf("main") }
    var selectedNavItem by remember { mutableStateOf(BottomNavItem.HOME) }
    var authData by remember { mutableStateOf<AuthData?>(null) }
    
    LaunchedEffect(Unit) {
        val fsid = prefs.getString("fsid", "") ?: ""
        val deviceId = prefs.getString("device_id", "") ?: ""
        
        if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
            authData = AuthData(fsid, deviceId)
            
            try {
                val userId = dbHelper.saveUser(fsid, deviceId)
                dbHelper.addLog(userId, "info", "Приложение запущено")
            } catch (e: Exception) {}
        }
    }
    
    fun saveAuthData(fsid: String, deviceId: String) {
        prefs.edit()
            .putString("fsid", fsid)
            .putString("device_id", deviceId)
            .apply()
        authData = AuthData(fsid, deviceId)
        
        try {
            val userId = dbHelper.saveUser(fsid, deviceId)
            dbHelper.addLog(userId, "info", "Пользователь авторизован")
        } catch (e: Exception) {}
    }
    
    fun clearAuthData() {
        prefs.edit().clear().apply()
        authData = null
    }
    
    when (currentScreen) {
        "main" -> BybitMainScreen(
            authData = authData,
            selectedNavItem = selectedNavItem,
            onNavItemSelected = { selectedNavItem = it },
            onNavigateToWebAuth = { currentScreen = "webAuth" },
            onNavigateToSettings = { currentScreen = "settings" },
            onLogout = {
                currentScreen = "main"
                clearAuthData()
            },
            dbHelper = dbHelper
        )
        "webAuth" -> WebViewAuthScreen(
            onAuthSuccess = { fsid, deviceId ->
                saveAuthData(fsid, deviceId)
                currentScreen = "main"
            },
            onBack = { currentScreen = "main" }
        )
        "settings" -> SettingsScreen(
            onBack = { currentScreen = "main" },
            onSave = { currentScreen = "main" },
            dbHelper = dbHelper
        )
        "stats" -> StatsScreen(
            onBack = { currentScreen = "main" },
            dbHelper = dbHelper,
            authData = authData
        )
        "history" -> HistoryScreen(
            onBack = { currentScreen = "main" },
            dbHelper = dbHelper,
            authData = authData
        )
    }
}

// ==================== ГЛАВНЫЙ ЭКРАН BYBIT ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BybitMainScreen(
    authData: AuthData?,
    selectedNavItem: BottomNavItem,
    onNavItemSelected: (BottomNavItem) -> Unit,
    onNavigateToWebAuth: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    dbHelper: DatabaseHelper
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE) }
    
    var isBotRunning by remember { mutableStateOf(BotForegroundService.isRunning) }
    var balance by remember { mutableStateOf(0.0) }
    var profitLoss by remember { mutableStateOf(0.0) }
    var profitPercent by remember { mutableStateOf(0.0) }
    val logs = remember { mutableStateListOf<String>() }
    var showExitDialog by remember { mutableStateOf(false) }
    var isLoadingBalance by remember { mutableStateOf(false) }
    
    var activeExpresses by remember { mutableStateOf<List<ExpressInfo>>(emptyList()) }
    var matchesByExpress by remember { mutableStateOf<Map<Long, List<MatchInfo>>>(emptyMap()) }
    var expandedExpressIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    
    val maxActiveExpresses = prefs.getInt("max_active_expresses", 5)
    
    fun loadActiveExpresses() {
        scope.launch(Dispatchers.IO) {
            try {
                val allExpresses = dbHelper.getAllExpresses()
                val currentTime = System.currentTimeMillis() / 1000
                val twoHoursInSeconds = 2 * 60 * 60
                
                val filtered = allExpresses.filter { express ->
                    express.stsAll in listOf(0, 1, 2) &&
                    (currentTime - express.createdAt) <= twoHoursInSeconds
                }.sortedByDescending { it.createdAt }
                
                val matchesMap = mutableMapOf<Long, List<MatchInfo>>()
                filtered.forEach { express ->
                    matchesMap[express.id] = dbHelper.getMatchesByExpressId(express.id)
                }
                
                withContext(Dispatchers.Main) {
                    activeExpresses = filtered
                    matchesByExpress = matchesMap
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    logs.add(0, "[${getCurrentTime()}] ❌ Ошибка загрузки: ${e.message}")
                }
            }
        }
    }
    
    fun fetchBalanceFromApi() {
        if (authData == null) {
            logs.add(0, "[${getCurrentTime()}] ❌ Нет данных авторизации")
            return
        }
        
        isLoadingBalance = true
        
        val apiClient = ApiClient()
        apiClient.getSaldo(
            cookies = emptyMap(),
            fsid = authData.fsid,
            deviceId = authData.deviceId,
            onSuccess = { sessionInfo: ApiClient.SessionInfo? ->
                isLoadingBalance = false
                if (sessionInfo != null && sessionInfo.saldo != null) {
                    val saldo = sessionInfo.saldo
                    balance = saldo
                    logs.add(0, "[${getCurrentTime()}] 💰 Баланс обновлён: %.2f ₽".format(saldo))
                    
                    scope.launch {
                        try {
                            val user = dbHelper.getUser(authData.fsid, authData.deviceId)
                            user?.let {
                                dbHelper.saveBalance(it.id, saldo)
                            }
                        } catch (e: Exception) {}
                    }
                }
            },
            onError = { error: String ->
                isLoadingBalance = false
                logs.add(0, "[${getCurrentTime()}] ❌ Ошибка API: $error")
            }
        )
    }
    
    // Инициализация данных
    LaunchedEffect(authData, isBotRunning) {
        authData?.let {
            try {
                val user = dbHelper.getUser(it.fsid, it.deviceId)
                user?.let { userId ->
                    val stats = dbHelper.getBalanceStats(userId.id)
                    if (stats.currentBalance > 0) {
                        balance = stats.currentBalance
                    }
                }
            } catch (e: Exception) {}
        }
        
        if (isBotRunning && authData != null) {
            fetchBalanceFromApi()
        }
    }
    
    LaunchedEffect(isBotRunning) {
        loadActiveExpresses()
    }
    
    // Периодическое обновление
    LaunchedEffect(isBotRunning) {
        if (isBotRunning) {
            while (true) {
                delay(5000)
                loadActiveExpresses()
            }
        }
    }
    
    LaunchedEffect(Unit) {
        isBotRunning = BotForegroundService.isRunning
        if (isBotRunning) {
            logs.add(0, "[${getCurrentTime()}] 🔄 Подключено к работающему боту")
            if (BotForegroundService.lastBalance > 0) {
                balance = BotForegroundService.lastBalance
            }
        }
        loadActiveExpresses()
    }
    
    // Обработка P&L
    LaunchedEffect(balance) {
        val initialBalance = prefs.getFloat("initial_balance", 0f).toDouble()
        if (initialBalance > 0 && balance > 0) {
            profitLoss = balance - initialBalance
            profitPercent = (profitLoss / initialBalance) * 100
        } else if (balance > 0) {
            prefs.edit().putFloat("initial_balance", balance.toFloat()).apply()
        }
    }
    
    // Подписка на обновления из сервиса
    DisposableEffect(Unit) {
        BotForegroundService.onBalanceUpdate = { newBalance ->
            kotlinx.coroutines.MainScope().launch {
                balance = newBalance
            }
        }
        BotForegroundService.onLogUpdate = { log ->
            kotlinx.coroutines.MainScope().launch {
                logs.add(0, log)
                if (logs.size > 200) {
                    repeat(logs.size - 200) { logs.removeLast() }
                }
            }
        }
        BotForegroundService.onBetsUpdate = { bets ->
            kotlinx.coroutines.MainScope().launch {
                loadActiveExpresses()
            }
        }
        BotForegroundService.onScoresUpdate = { message ->
            kotlinx.coroutines.MainScope().launch {
                logs.add(0, message)
                loadActiveExpresses()
            }
        }
        BotForegroundService.authData = authData
        
        onDispose {
            BotForegroundService.onBalanceUpdate = null
            BotForegroundService.onLogUpdate = null
            BotForegroundService.onBetsUpdate = null
            BotForegroundService.onScoresUpdate = null
        }
    }
    
    fun startBot() {
        if (authData == null) {
            logs.add(0, "[${getCurrentTime()}] ❌ Нет данных авторизации")
            return
        }
        
        BotForegroundService.authData = authData
        
        val serviceIntent = Intent(context, BotForegroundService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        isBotRunning = true
        logs.add(0, "[${getCurrentTime()}] 🚀 Бот запущен в фоне")
        fetchBalanceFromApi()
    }
    
    fun stopBot() {
        val stopIntent = Intent(context, BotForegroundService::class.java).apply {
            action = BotForegroundService.ACTION_STOP
        }
        context.startService(stopIntent)
        
        isBotRunning = false
        logs.add(0, "[${getCurrentTime()}] ⏹ Бот остановлен")
    }
    
    BackHandler(enabled = isBotRunning) {
        showExitDialog = true
    }
    
    Scaffold(
        containerColor = BybitColors.Background,
        bottomBar = {
            BybitBottomNavigation(
                selectedItem = selectedNavItem,
                onItemSelected = onNavItemSelected,
                isBotRunning = isBotRunning,
                onStartStopBot = { if (isBotRunning) stopBot() else startBot() }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Основной контент в зависимости от выбранной вкладки
            when (selectedNavItem) {
                BottomNavItem.HOME -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Верхняя панель с балансом
                        item {
                            BalanceHeaderCard(
                                balance = balance,
                                profitLoss = profitLoss,
                                profitPercent = profitPercent,
                                isBotRunning = isBotRunning,
                                isLoadingBalance = isLoadingBalance,
                                authData = authData,
                                onRefresh = { fetchBalanceFromApi() }
                            )
                        }
                        
                        // Панель действий (Депозит, P2P и т.д.)
                        item {
                            ActionButtonsRow(
                                onStartStopBot = { if (isBotRunning) stopBot() else startBot() },
                                isBotRunning = isBotRunning,
                                onNavigateToAuth = onNavigateToWebAuth,
                                onNavigateToSettings = onNavigateToSettings
                            )
                        }
                        
                        // Промо-карточка (информационная)
                        item {
                            PromoCard()
                        }
                        
                        // Активные экспрессы
                        item {
                            ActiveExpressesSection(
                                expresses = activeExpresses,
                                matchesByExpress = matchesByExpress,
                                expandedExpressIds = expandedExpressIds,
                                onToggleExpand = { expressId ->
                                    expandedExpressIds = if (expandedExpressIds.contains(expressId)) {
                                        expandedExpressIds - expressId
                                    } else {
                                        expandedExpressIds + expressId
                                    }
                                },
                                maxActiveExpresses = maxActiveExpresses,
                                isBotRunning = isBotRunning,
                                onRefresh = { loadActiveExpresses() }
                            )
                        }
                        
                        // Логи
                        item {
                            LogsSection(
                                logs = logs,
                                onClear = {
                                    logs.clear()
                                    logs.add("[${getCurrentTime()}] 🗑 Логи очищены")
                                }
                            )
                        }
                    }
                }
                
                BottomNavItem.BETS -> {
                    ActiveExpressesFullScreen(
                        expresses = activeExpresses,
                        matchesByExpress = matchesByExpress,
                        expandedExpressIds = expandedExpressIds,
                        onToggleExpand = { expressId ->
                            expandedExpressIds = if (expandedExpressIds.contains(expressId)) {
                                expandedExpressIds - expressId
                            } else {
                                expandedExpressIds + expressId
                            }
                        },
                        maxActiveExpresses = maxActiveExpresses
                    )
                }
                
                BottomNavItem.STATS -> {
                    StatsContent(
                        dbHelper = dbHelper,
                        authData = authData,
                        onNavigateToHistory = { /* реализовать навигацию */ }
                    )
                }
                
                BottomNavItem.PROFILE -> {
                    ProfileContent(
                        authData = authData,
                        dbHelper = dbHelper,
                        onLogout = {
                            onLogout()
                            onNavItemSelected(BottomNavItem.HOME)
                        }
                    )
                }
            }
        }
        
        // Диалог остановки бота
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                containerColor = BybitColors.Surface,
                titleContentColor = BybitColors.TextPrimary,
                textContentColor = BybitColors.TextSecondary,
                title = { Text("⚠️ Остановить бота?") },
                text = { Text("Бот работает в фоновом режиме.\n\nОстановить бота и выйти?") },
                confirmButton = {
                    Button(
                        onClick = {
                            showExitDialog = false
                            stopBot()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BybitColors.Red)
                    ) {
                        Text("Остановить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text("Отмена", color = BybitColors.TextSecondary)
                    }
                }
            )
        }
    }
}

// ==================== НИЖНЯЯ ПАНЕЛЬ НАВИГАЦИИ ====================

@Composable
fun BybitBottomNavigation(
    selectedItem: BottomNavItem,
    onItemSelected: (BottomNavItem) -> Unit,
    isBotRunning: Boolean,
    onStartStopBot: () -> Unit
) {
    Surface(
        color = BybitColors.Surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItem.entries.take(4).forEach { item ->
                val isSelected = selectedItem == item
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onItemSelected(item) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Иконка с цветовой индикацией
                    Box(
                        modifier = Modifier
                            .size(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when (item) {
                            BottomNavItem.HOME -> Icon(
                                Icons.Default.Home,
                                null,
                                tint = if (isSelected) BybitColors.Yellow else BybitColors.TextTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                            BottomNavItem.BETS -> Icon(
                                Icons.Default.ListAlt,
                                null,
                                tint = if (isSelected) BybitColors.Yellow else BybitColors.TextTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                            BottomNavItem.STATS -> Icon(
                                Icons.Default.BarChart,
                                null,
                                tint = if (isSelected) BybitColors.Yellow else BybitColors.TextTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                            BottomNavItem.PROFILE -> Icon(
                                Icons.Default.Person,
                                null,
                                tint = if (isSelected) BybitColors.Yellow else BybitColors.TextTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) BybitColors.Yellow else BybitColors.TextTertiary
                    )
                }
            }
            
            // Кнопка бота
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .clickable { onStartStopBot() }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isBotRunning) BybitColors.Red else BybitColors.Green
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isBotRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(2.dp))
                
                Text(
                    text = if (isBotRunning) "Стоп" else "Старт",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BybitColors.TextSecondary
                )
            }
        }
    }
}

// ==================== КАРТОЧКА БАЛАНСА ====================

@Composable
fun BalanceHeaderCard(
    balance: Double,
    profitLoss: Double,
    profitPercent: Double,
    isBotRunning: Boolean,
    isLoadingBalance: Boolean,
    authData: AuthData?,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Статус бота и заголовок
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isBotRunning) BybitColors.Green else BybitColors.Red
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBotRunning) "Бот активен" else "Бот остановлен",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = BybitColors.TextSecondary
                    )
                }
                
                // Кнопка обновления
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(32.dp),
                    enabled = authData != null
                ) {
                    if (isLoadingBalance) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = BybitColors.Yellow
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            "Обновить",
                            tint = if (authData != null) BybitColors.Yellow else BybitColors.TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Название "актива"
            Text(
                text = "Общие активы",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = BybitColors.TextSecondary
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Баланс крупным шрифтом
            Text(
                text = if (balance > 0) String.format("%.2f ₽", balance) else "0.00 ₽",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = BybitColors.TextPrimary,
                fontFamily = FontFamily.Default
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // P&L
            if (profitLoss != 0.0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "P&L за сегодня ",
                        fontSize = 13.sp,
                        color = BybitColors.TextSecondary
                    )
                    Text(
                        text = "${if (profitLoss > 0) "+" else ""}${String.format("%.2f", profitLoss)} ₽",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (profitLoss > 0) BybitColors.Green else BybitColors.Red
                    )
                    Text(
                        text = " (${if (profitPercent > 0) "+" else ""}${String.format("%.2f", profitPercent)}%)",
                        fontSize = 13.sp,
                        color = if (profitPercent > 0) BybitColors.Green else BybitColors.Red
                    )
                }
            } else {
                Text(
                    text = "P&L за сегодня 0.00 ₽ (0.00%)",
                    fontSize = 13.sp,
                    color = BybitColors.TextSecondary
                )
            }
            
            if (authData == null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "⚠️ Требуется авторизация",
                    fontSize = 12.sp,
                    color = BybitColors.Red
                )
            }
        }
    }
}

// ==================== ПАНЕЛЬ ДЕЙСТВИЙ (как в Bybit) ====================

@Composable
fun ActionButtonsRow(
    onStartStopBot: () -> Unit,
    isBotRunning: Boolean,
    onNavigateToAuth: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButton(
                icon = { Icon(Icons.Default.AddCircle, null, tint = BybitColors.Yellow, modifier = Modifier.size(28.dp)) },
                label = if (isBotRunning) "Стоп" else "Старт",
                onClick = onStartStopBot
            )
            
            ActionButton(
                icon = { Icon(Icons.Default.SwapHoriz, null, tint = BybitColors.TextSecondary, modifier = Modifier.size(28.dp)) },
                label = "P2P",
                onClick = { /* Заглушка */ }
            )
            
            ActionButton(
                icon = { Icon(Icons.Default.SmartToy, null, tint = BybitColors.TextSecondary, modifier = Modifier.size(28.dp)) },
                label = "Бот",
                onClick = { /* Заглушка */ }
            )
            
            ActionButton(
                icon = { Icon(Icons.Default.CardGiftcard, null, tint = BybitColors.TextSecondary, modifier = Modifier.size(28.dp)) },
                label = "Бонусы",
                onClick = { /* Заглушка */ }
            )
        }
    }
}

@Composable
fun ActionButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable { onClick() }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BybitColors.SurfaceLight),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = BybitColors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

// ==================== ПРОМО-КАРТОЧКА ====================

@Composable
fun PromoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = BybitColors.Surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🚀 Авто-экспрессы",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = BybitColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Умные ставки в реальном времени",
                    fontSize = 13.sp,
                    color = BybitColors.TextSecondary
                )
            }
            
            Icon(
                Icons.Default.TrendingUp,
                null,
                tint = BybitColors.Yellow,
                modifier = Modifier.size(36.dp)
            )
        }
        
        // Индикатор страниц (1/6)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(6) { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == 0) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == 0) BybitColors.Yellow else BybitColors.SurfaceLight
                        )
                )
                if (index < 5) Spacer(modifier = Modifier.width(6.dp))
            }
        }
    }
}

// ==================== СЕКЦИЯ АКТИВНЫХ ЭКСПРЕССОВ ====================

@Composable
fun ActiveExpressesSection(
    expresses: List<ExpressInfo>,
    matchesByExpress: Map<Long, List<MatchInfo>>,
    expandedExpressIds: Set<Long>,
    onToggleExpand: (Long) -> Unit,
    maxActiveExpresses: Int,
    isBotRunning: Boolean,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Заголовок секции
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🎯 Экспрессы",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BybitColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (expresses.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (expresses.size >= maxActiveExpresses) BybitColors.Red.copy(alpha = 0.2f) else BybitColors.Green.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "${expresses.size}/$maxActiveExpresses",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (expresses.size >= maxActiveExpresses) BybitColors.Red else BybitColors.Green,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        "Обновить",
                        tint = BybitColors.Yellow,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            // Лимит
            if (expresses.isNotEmpty() && expresses.size >= maxActiveExpresses && isBotRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BybitColors.Red.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = "⚠️ Достигнут лимит экспрессов ($maxActiveExpresses). Новые не создаются.",
                        fontSize = 12.sp,
                        color = BybitColors.Red,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Список экспрессов
            if (expresses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isBotRunning) "Ожидание сигналов..." else "Запустите бота",
                        fontSize = 14.sp,
                        color = BybitColors.TextTertiary
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    expresses.take(5).forEach { express ->
                        BybitExpressCard(
                            express = express,
                            matches = matchesByExpress[express.id] ?: emptyList(),
                            isExpanded = expandedExpressIds.contains(express.id),
                            onToggleExpand = { onToggleExpand(express.id) }
                        )
                    }
                    
                    if (expresses.size > 5) {
                        Text(
                            text = "и ещё ${expresses.size - 5}...",
                            fontSize = 12.sp,
                            color = BybitColors.TextTertiary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==================== КАРТОЧКА ЭКСПРЕССА В СТИЛЕ BYBIT ====================

@Composable
fun BybitExpressCard(
    express: ExpressInfo,
    matches: List<MatchInfo>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val currentTime = System.currentTimeMillis() / 1000
    val isFullyCompleted = matches.all { it.isFinalized == 1 }
    
    val (statusColor, statusText) = when {
        express.stsAll == 2 && isFullyCompleted -> BybitColors.Green to "✅ Выигрыш"
        express.stsAll == 1 && isFullyCompleted -> BybitColors.Red to "❌ Проигрыш"
        express.stsAll == 0 -> BybitColors.Yellow to "🔄 В игре"
        else -> BybitColors.TextSecondary to "Ожидание"
    }
    
    val ageSeconds = currentTime - express.createdAt
    val ageText = when {
        ageSeconds < 60 -> "только что"
        ageSeconds < 3600 -> "${ageSeconds / 60}м"
        else -> "${ageSeconds / 3600}ч ${(ageSeconds % 3600) / 60}м"
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.SurfaceLight)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Верхняя строка
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${express.idExp}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = BybitColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "· $ageText",
                        fontSize = 11.sp,
                        color = BybitColors.TextTertiary
                    )
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${express.sumbet.toInt()} ₽",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = BybitColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        tint = BybitColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Статус и кэф
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
                Text(
                    text = "×${"%.2f".format(express.kfall)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BybitColors.Yellow
                )
            }
            
            // Прогресс
            if (!isFullyCompleted && matches.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                val completedCount = matches.count { it.isFinalized == 1 }
                val progress = completedCount.toFloat() / matches.size
                
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = BybitColors.Yellow,
                    trackColor = BybitColors.Divider,
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$completedCount/${matches.size} завершено",
                    fontSize = 10.sp,
                    color = BybitColors.TextTertiary
                )
            }
            
            // Прибыль/убыток
            if (express.stsAll == 1 || express.stsAll == 2) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "${if (express.profLoss > 0) "+" else ""}${"%.2f".format(express.profLoss)} ₽",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (express.profLoss > 0) BybitColors.Green else BybitColors.Red
                    )
                }
            }
            
            // Развёрнутая информация
            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = BybitColors.Divider)
                Spacer(modifier = Modifier.height(8.dp))
                
                matches.forEach { match ->
                    MiniMatchRow(match = match)
                    if (match != matches.last()) Spacer(modifier = Modifier.height(6.dp))
                }
                
                if (express.stsAll == 2) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BybitColors.Green.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = "🏆 Выигрыш: ${"%.2f".format(express.potentialWin)} ₽",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = BybitColors.Green,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MiniMatchRow(match: MatchInfo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (match.homeTeam.isNotEmpty()) "${match.homeTeam} vs ${match.awayTeam}" else "Матч #${match.mId}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = BybitColors.TextPrimary,
                maxLines = 1
            )
            Text(
                text = "${match.homeScore} : ${match.awayScore}${if (match.matchTime > 0) " · ${match.matchTime}'" else ""}",
                fontSize = 11.sp,
                color = BybitColors.TextSecondary
            )
        }
        
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = when {
                match.isFinalized == 1 && match.status == 2 -> BybitColors.Green.copy(alpha = 0.15f)
                match.isFinalized == 1 && match.status == 1 -> BybitColors.Red.copy(alpha = 0.15f)
                else -> BybitColors.Yellow.copy(alpha = 0.15f)
            }
        ) {
            Text(
                text = typeName(match.betType),
                fontSize = 10.sp,
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
}

// ==================== ЭКРАН ЭКСПРЕССОВ (полный) ====================

@Composable
fun ActiveExpressesFullScreen(
    expresses: List<ExpressInfo>,
    matchesByExpress: Map<Long, List<MatchInfo>>,
    expandedExpressIds: Set<Long>,
    onToggleExpand: (Long) -> Unit,
    maxActiveExpresses: Int
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "🎯 Активные экспрессы",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = BybitColors.TextPrimary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        
        if (expresses.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Нет активных экспрессов",
                        fontSize = 14.sp,
                        color = BybitColors.TextTertiary
                    )
                }
            }
        } else {
            items(expresses) { express ->
                BybitExpressCard(
                    express = express,
                    matches = matchesByExpress[express.id] ?: emptyList(),
                    isExpanded = expandedExpressIds.contains(express.id),
                    onToggleExpand = { onToggleExpand(express.id) }
                )
            }
        }
    }
}

// ==================== СЕКЦИЯ ЛОГОВ ====================

@Composable
fun LogsSection(
    logs: List<String>,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📋 Логи",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = BybitColors.TextPrimary
                )
                
                IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        "Очистить",
                        tint = BybitColors.TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Логи появятся здесь...",
                        fontSize = 12.sp,
                        color = BybitColors.TextTertiary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs.take(50)) { log ->
                        val logColor = when {
                            log.contains("❌") || log.contains("Ошибка") -> BybitColors.Red
                            log.contains("✅") || log.contains("Профит") -> BybitColors.Green
                            log.contains("⚠️") || log.contains("Лимит") -> BybitColors.Yellow
                            log.contains("💰") -> BybitColors.Yellow
                            else -> BybitColors.TextSecondary
                        }
                        
                        Text(
                            text = log,
                            fontSize = 10.sp,
                            color = logColor,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

// ==================== ЭКРАН СТАТИСТИКИ ====================

@Composable
fun StatsContent(
    dbHelper: DatabaseHelper,
    authData: AuthData?,
    onNavigateToHistory: () -> Unit
) {
    var stats by remember { mutableStateOf<BalanceStats?>(null) }
    
    LaunchedEffect(authData) {
        authData?.let {
            try {
                val user = dbHelper.getUser(it.fsid, it.deviceId)
                user?.let { userId ->
                    stats = dbHelper.getBalanceStats(userId.id)
                }
            } catch (e: Exception) {}
        }
    }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "📊 Статистика",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = BybitColors.TextPrimary
            )
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "За сегодня",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BybitColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    stats?.let {
                        StatRow("Текущий баланс", String.format("%.2f ₽", it.currentBalance), BybitColors.TextPrimary)
                        StatRow("Мин. за сегодня", String.format("%.2f ₽", it.todayMin), BybitColors.TextSecondary)
                        StatRow("Макс. за сегодня", String.format("%.2f ₽", it.todayMax), BybitColors.TextSecondary)
                        StatRow("Средний", String.format("%.2f ₽", it.todayAvg), BybitColors.TextSecondary)
                        StatRow("Ошибок", it.todayErrors.toString(), if (it.todayErrors > 0) BybitColors.Red else BybitColors.Green)
                    } ?: run {
                        Text("Нет данных", color = BybitColors.TextTertiary)
                    }
                }
            }
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "База данных",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BybitColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val tableStats = remember { dbHelper.getTableStats() }
                    tableStats.forEach { (table, count) ->
                        StatRow(table, "$count записей", BybitColors.TextSecondary)
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    StatRow("Размер", dbHelper.getDatabaseSize(LocalContext.current), BybitColors.TextSecondary)
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedButton(
                        onClick = onNavigateToHistory,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BybitColors.Yellow)
                    ) {
                        Text("📜 История логов")
                    }
                }
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = BybitColors.TextPrimary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 13.sp, color = BybitColors.TextSecondary)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

// ==================== ЭКРАН ПРОФИЛЯ ====================

@Composable
fun ProfileContent(
    authData: AuthData?,
    dbHelper: DatabaseHelper,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "👤 Аккаунт",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = BybitColors.TextPrimary
            )
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Аватар
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(BybitColors.Yellow, BybitColors.YellowLight)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("👤", fontSize = 32.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Пользователь",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = BybitColors.TextPrimary
                    )
                    Text(
                        text = "Фонбет аккаунт",
                        fontSize = 13.sp,
                        color = BybitColors.TextSecondary
                    )
                }
            }
        }
        
        if (authData != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Данные авторизации",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BybitColors.TextPrimary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        Text(
                            text = "FSID: ${authData.fsid.take(20)}...",
                            fontSize = 11.sp,
                            color = BybitColors.TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                        
                        Text(
                            text = "DeviceID: ${authData.deviceId.take(20)}...",
                            fontSize = 11.sp,
                            color = BybitColors.TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Auth", "FSID: ${authData.fsid}\nDeviceID: ${authData.deviceId}")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BybitColors.Yellow)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Копировать")
                        }
                    }
                }
            }
        }
        
        item {
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BybitColors.Red),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("🚪 Выйти из аккаунта")
            }
        }
    }
}

// ==================== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ====================

private fun getCurrentTime(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}

fun typeName(type: Int): String = when (type) {
    924 -> "1X"
    927 -> "Ф1(+1.5)"
    928 -> "Ф2(+1.5)"
    else -> "Тип $type"
}