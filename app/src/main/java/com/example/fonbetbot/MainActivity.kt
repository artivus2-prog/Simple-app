// MainActivity.kt - ПОЛНАЯ ВЕРСИЯ: ФИЛЬТР 2 ЧАСА, ВСЕ НАСТРОЙКИ, ЛОГИ ПОД ТАБЛИЦЕЙ
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FonbetBotApp(dbHelper)
                }
            }
        }
    }
}

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
    var authData by remember { mutableStateOf<AuthData?>(null) }
    
    LaunchedEffect(Unit) {
        val fsid = prefs.getString("fsid", "") ?: ""
        val deviceId = prefs.getString("device_id", "") ?: ""
        
        if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
            authData = AuthData(fsid, deviceId)
            
            try {
                val userId = dbHelper.saveUser(fsid, deviceId)
                dbHelper.addLog(userId, "info", "Приложение запущено")
            } catch (e: Exception) {
                // Игнорируем
            }
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
        } catch (e: Exception) {
            // Игнорируем
        }
    }
    
    fun clearAuthData() {
        prefs.edit().clear().apply()
        authData = null
    }
    
    when (currentScreen) {
        "main" -> MainBotScreen(
            authData = authData,
            onNavigateToStats = { currentScreen = "stats" },
            onNavigateToSettings = { currentScreen = "settings" },
            onNavigateToHistory = { currentScreen = "history" },
            onNavigateToProfile = { currentScreen = "profile" },
            onNavigateToWebAuth = { currentScreen = "webAuth" },
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
        "stats" -> StatsScreen(
            onBack = { currentScreen = "main" },
            dbHelper = dbHelper,
            authData = authData
        )
        "settings" -> SettingsScreen(
            onBack = { currentScreen = "main" },
            onSave = { currentScreen = "main" },
            dbHelper = dbHelper
        )
        "history" -> HistoryScreen(
            onBack = { currentScreen = "main" },
            dbHelper = dbHelper,
            authData = authData
        )
        "profile" -> ProfileScreen(
            authData = authData,
            onBack = { currentScreen = "main" },
            dbHelper = dbHelper
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainBotScreen(
    authData: AuthData?,
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToWebAuth: () -> Unit,
    onLogout: () -> Unit,
    dbHelper: DatabaseHelper
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isBotRunning by remember { mutableStateOf(BotForegroundService.isRunning) }
    var balance by remember { mutableStateOf(0.0) }
    val logs = remember { mutableStateListOf<String>() }
    var showMenu by remember { mutableStateOf(false) }
    var isLoadingBalance by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    
    // Данные для дерева активных экспрессов
    var activeExpresses by remember { mutableStateOf<List<ExpressInfo>>(emptyList()) }
    var matchesByExpress by remember { mutableStateOf<Map<Int, List<MatchInfo>>>(emptyMap()) }  // Было Map<Long, List<MatchInfo>>
var expandedExpressIds by remember { mutableStateOf<Set<Int>>(emptySet()) }  // Было Set<Long>
    // Функция загрузки активных экспрессов (не старше 2 часов)
    
    // В MainBotScreen:

// Функция загрузки активных экспрессов
fun loadActiveExpresses() {
    try {
        val allExpresses = dbHelper.getAllExpresses()
        val currentTime = System.currentTimeMillis() / 1000
        val twoHoursInSeconds = 2 * 60 * 60
        
        activeExpresses = allExpresses.filter { express ->
            express.stsAll != -1 &&
            (currentTime - express.createdAt) <= twoHoursInSeconds
        }.sortedByDescending { it.createdAt }
        
        val matchesMap = mutableMapOf<Int, List<MatchInfo>>()
        activeExpresses.forEach { express ->
            matchesMap[express.idExp] = dbHelper.getMatchesByExpId(express.idExp)
        }
        matchesByExpress = matchesMap
        
        android.util.Log.d("MainActivity", "Загружено экспрессов: ${activeExpresses.size} (фильтр: 2 часа)")
        logs.add(0, "[${getCurrentTime()}] 📊 Загружено экспрессов: ${activeExpresses.size}")
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Ошибка загрузки экспрессов: ${e.message}")
        logs.add(0, "[${getCurrentTime()}] ❌ Ошибка загрузки экспрессов: ${e.message}")
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
                    val oldBalance = balance
                    balance = saldo
                    logs.add(0, "[${getCurrentTime()}] 💰 Баланс обновлён: %.2f ₽".format(saldo))
                    
                    scope.launch {
                        try {
                            val user = dbHelper.getUser(authData.fsid, authData.deviceId)
                            user?.let {
                                dbHelper.saveBalance(it.id, saldo)
                                if (saldo > oldBalance && oldBalance > 0) {
                                    dbHelper.addLog(it.id, "profit", "Профит: +%.2f ₽".format(saldo - oldBalance))
                                }
                                if (saldo < oldBalance && oldBalance > 0) {
                                    dbHelper.addLog(it.id, "loss", "Убыток: %.2f ₽".format(saldo - oldBalance))
                                }
                            }
                        } catch (e: Exception) { }
                    }
                }
            },
            onError = { error: String ->
                isLoadingBalance = false
                logs.add(0, "[${getCurrentTime()}] ❌ Ошибка API: $error")
            }
        )
    }
    
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
            } catch (e: Exception) { }
        }
        
        if (isBotRunning && authData != null) {
            fetchBalanceFromApi()
        }
    }
    
    LaunchedEffect(isBotRunning) {
        loadActiveExpresses()
    }
    
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
    
    DisposableEffect(Unit) {
        BotForegroundService.onBalanceUpdate = { newBalance ->
            balance = newBalance
        }
        BotForegroundService.onLogUpdate = { log ->
            logs.add(0, log)
            if (logs.size > 200) {
                repeat(logs.size - 200) { logs.removeLast() }
            }
        }
        BotForegroundService.onBetsUpdate = { bets ->
            loadActiveExpresses()
        }
        BotForegroundService.onScoresUpdate = { message ->
            logs.add(0, message)
            loadActiveExpresses()
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
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        if (isBotRunning) Color(0xFF4CAF50) else Color(0xFFF44336),
                                        shape = RoundedCornerShape(5.dp)
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBotRunning) "Активен" else "Остановлен",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isBotRunning) Color(0xFF4CAF50) else Color(0xFFF44336)
                            )
                        }
                        
                        if (isLoadingBalance) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Text(
                                text = if (balance > 0) "💰 ${String.format("%.0f", balance)} ₽" else "— ₽",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                actions = {
                    if (isBotRunning) {
                        IconButton(onClick = { showExitDialog = true }) {
                            Icon(Icons.Default.Stop, "Остановить бота", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(
                            onClick = { startBot() },
                            enabled = authData != null
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                "Запустить бота",
                                tint = if (authData != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, "Меню")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("👤 Профиль") },
                            onClick = { showMenu = false; onNavigateToProfile() },
                            enabled = !isBotRunning
                        )
                        DropdownMenuItem(
                            text = { Text("📊 Статистика") },
                            onClick = { showMenu = false; onNavigateToStats() },
                            enabled = !isBotRunning
                        )
                        DropdownMenuItem(
                            text = { Text("📜 История") },
                            onClick = { showMenu = false; onNavigateToHistory() },
                            enabled = !isBotRunning
                        )
                        DropdownMenuItem(
                            text = { Text("⚙️ Настройки") },
                            onClick = { showMenu = false; onNavigateToSettings() },
                            enabled = !isBotRunning
                        )
                        
                        if (authData == null) {
                            Divider()
                            DropdownMenuItem(
                                text = { Text("🔐 Авторизоваться") },
                                onClick = { showMenu = false; onNavigateToWebAuth() }
                            )
                        }
                        
                        Divider()
                        DropdownMenuItem(
                            text = { Text("🚪 Выйти") },
                            onClick = {
                                if (!isBotRunning) {
                                    showMenu = false
                                    onLogout()
                                }
                            },
                            enabled = !isBotRunning
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(12.dp)
        ) {
            // Если нет авторизации - показываем кнопку
            if (authData == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "⚠️ Требуется авторизация",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onNavigateToWebAuth,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Text("🔐 Авторизоваться")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // ТАБЛИЦА ЭКСПРЕССОВ + ЛОГИ
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Карточка с экспрессами (большая часть)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.65f),  // 65% под экспрессы
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🎯 Активные экспрессы",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (activeExpresses.isNotEmpty()) {
                                    Text(
                                        text = "${activeExpresses.size}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "шт.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { loadActiveExpresses() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        "Обновить",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        
                        if (activeExpresses.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = if (isBotRunning) "Ожидание экспрессов..." 
                                               else "Запустите бота",
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isBotRunning) "Экспрессы появятся после анализа матчей"
                                               else "Нажмите ▶ для запуска",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Исправленный LazyColumn с экспрессами:
items(activeExpresses, key = { it.idExp }) { express ->
    ActiveExpressCard(
        express = express,
        matches = matchesByExpress[express.idExp] ?: emptyList(),
        isExpanded = expandedExpressIds.contains(express.idExp),
        onToggleExpand = {
            expandedExpressIds = if (expandedExpressIds.contains(express.idExp)) {
                expandedExpressIds - express.idExp
            } else {
                expandedExpressIds + express.idExp
            }
        }
    )
}
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // КАРТОЧКА С ЛОГАМИ (меньшая часть)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.35f),  // 35% под логи
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E1E1E)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "📋 Логи",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${logs.size}",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            
                            Row {
                                // Кнопка автоскролла
                                var autoScroll by remember { mutableStateOf(true) }
                                IconButton(
                                    onClick = { autoScroll = !autoScroll },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        if (autoScroll) Icons.Default.VerticalAlignBottom else Icons.Default.VerticalAlignTop,
                                        "Автоскролл",
                                        tint = if (autoScroll) Color(0xFF4CAF50) else Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                
                                IconButton(
                                    onClick = {
                                        logs.clear()
                                        logs.add("[${getCurrentTime()}] 🗑 Логи очищены")
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        "Очистить логи",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        Divider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        if (logs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Логи появятся здесь...",
                                    color = Color.Gray
                                )
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(logs.take(100)) { log ->
                                    LogItem(log = log)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text("⚠️ Остановить бота?") },
                text = { Text("Бот работает в фоновом режиме.\n\nОстановить бота и выйти?") },
                confirmButton = {
                    Button(
                        onClick = {
                            showExitDialog = false
                            stopBot()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Остановить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}

@Composable
fun ActiveExpressCard(
    express: ExpressInfo,
    matches: List<MatchInfo>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val (statusColor, statusEmoji, statusText) = when (express.stsAll) {
        2 -> Triple(Color(0xFF4CAF50), "✅", "ВЫИГРАЛ")
        1 -> Triple(Color(0xFFF44336), "❌", "ПРОИГРАЛ")
        0 -> Triple(MaterialTheme.colorScheme.primary, "🔄", "АКТИВЕН")
        -1 -> Triple(Color(0xFF9E9E9E), "🔄", "ЗАМЕНЁН")
        else -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, "❓", "НЕИЗВЕСТНО")
    }
    
    val currentTime = System.currentTimeMillis() / 1000
    val ageSeconds = currentTime - express.createdAt
    val ageMinutes = ageSeconds / 60
    val ageText = when {
        ageSeconds < 60 -> "только что"
        ageMinutes < 60 -> "${ageMinutes}мин"
        else -> "${ageMinutes / 60}ч ${ageMinutes % 60}мин"
    }
    
    val strategyDisplay = when {
        express.strategy.contains("1x") -> "1X"
        express.strategy.contains("f1") || express.strategy.contains("ф1") -> "Ф1(+1.5)"
        express.strategy.contains("f2") || express.strategy.contains("ф2") -> "Ф2(+1.5)"
        express.strategy.length > 10 -> express.strategy.take(10) + "..."
        else -> express.strategy
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$statusEmoji #${express.idExp}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(statusText, fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(ageText, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Кэф: ${"%.2f".format(express.kfall)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Ставка: ${express.sumbet.toInt()} ₽", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Матчей: ${express.eventsCount}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${if (express.stsAll == 2) "+" else if (express.stsAll == 1) "–" else ""}${"%.2f".format(express.potentialWin)} ₽",
                        fontSize = 13.sp,
                        color = when {
                            express.stsAll == 2 -> Color(0xFF4CAF50)
                            express.stsAll == 1 -> Color(0xFFF44336)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.Medium
                    )
                    Text(strategyDisplay, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
            
            if (express.stsAll == 1 || express.stsAll == 2) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Результат: ${if (express.profLoss > 0) "+" else ""}${"%.2f".format(express.profLoss)} ₽",
                        fontSize = 12.sp,
                        color = if (express.profLoss > 0) Color(0xFF4CAF50) else Color(0xFFF44336),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Баланс: ${"%.2f".format(express.balans)} ₽",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (express.idExpReplace > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "🔄 Заменён на #${express.idExpReplace}",
                    fontSize = 10.sp,
                    color = Color(0xFFFF9800)
                )
            }
            
            if (isExpanded && matches.isNotEmpty()) {
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                matches.forEach { match ->
                    MatchInExpressRow(match = match)
                    if (match != matches.last()) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun MatchInExpressRow(match: MatchInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (match.homeTeam.isNotEmpty() && match.awayTeam.isNotEmpty())
                        "${match.homeTeam} vs ${match.awayTeam}"
                    else
                        "Матч #${match.mId}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
                if (match.leagueName.isNotEmpty()) {
                    Text(
                        match.leagueName,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
                
                Spacer(modifier = Modifier.height(2.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Счет: ${match.homeScore}-${match.awayScore}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (match.matchTime > 0) {
                        Text("${match.matchTime}'", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(horizontalAlignment = Alignment.End) {
                val matchStatus = when (match.status) {
                    0 -> Triple("🔄", "Активен", MaterialTheme.colorScheme.primary)
                    1 -> Triple("❌", "Не зашёл", Color(0xFFF44336))
                    2 -> Triple("✅", "Зашёл", Color(0xFF4CAF50))
                    else -> Triple("🔄", "Активен", MaterialTheme.colorScheme.primary)
                }
                
                Text(
                    "${matchStatus.first} ${typeName(match.betType)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = matchStatus.third
                )
                
                Text("Кэф: ${"%.2f".format(match.startOdds)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                
                if (match.isFinalized == 1) {
                    Text("Завершен", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun LogItem(log: String) {
    val logColor = when {
        log.contains("❌") || log.contains("Ошибка") || log.contains("ERROR") -> Color(0xFFFF6B6B)
        log.contains("✅") || log.contains("УСПЕШНО") || log.contains("ПРИНЯТА") -> Color(0xFF4CAF50)
        log.contains("⚠️") || log.contains("ВНИМАНИЕ") -> Color(0xFFFFD93D)
        log.contains("💰") || log.contains("Профит") -> Color(0xFF4CAF50)
        log.contains("📉") || log.contains("Убыток") -> Color(0xFFFF6B6B)
        log.contains("📊") || log.contains("Матч") -> Color(0xFF64B5F6)
        log.contains("🎯") || log.contains("Экспресс") -> Color(0xFFCE93D8)
        log.contains("📤") || log.contains("Отправляем") -> Color(0xFFFFB74D)
        log.contains("📥") || log.contains("Получен") -> Color(0xFF81C784)
        log.contains("💾") || log.contains("Сохранен") -> Color(0xFF7986CB)
        log.contains("🔍") || log.contains("Проверка") -> Color(0xFFB0BEC5)
        else -> Color(0xFFE0E0E0)
    }
    
    Text(
        text = log,
        fontSize = 10.sp,
        color = logColor,
        lineHeight = 14.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}

// ==================== ЭКРАНЫ ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authData: AuthData?,
    onBack: () -> Unit,
    dbHelper: DatabaseHelper
) {
    val context = LocalContext.current
    var userStats by remember { mutableStateOf<BalanceStats?>(null) }
    
    LaunchedEffect(authData) {
        authData?.let {
            try {
                val user = dbHelper.getUser(it.fsid, it.deviceId)
                user?.let { userId ->
                    userStats = dbHelper.getBalanceStats(userId.id)
                }
            } catch (e: Exception) { }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("👤 Профиль") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary
                                    )
                                ),
                                shape = RoundedCornerShape(40.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("👤", fontSize = 40.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Пользователь", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Аккаунт Фонбет", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    Divider()
                    
                    userStats?.let { stats ->
                        ProfileInfoRow("Текущий баланс", String.format("%.2f ₽", stats.currentBalance))
                        ProfileInfoRow("Мин за сегодня", String.format("%.2f ₽", stats.todayMin))
                        ProfileInfoRow("Макс за сегодня", String.format("%.2f ₽", stats.todayMax))
                        ProfileInfoRow("Средний за сегодня", String.format("%.2f ₽", stats.todayAvg))
                        ProfileInfoRow("Ошибок сегодня", stats.todayErrors.toString())
                    } ?: run {
                        ProfileInfoRow("Статус", "Загружается...")
                    }
                    
                    if (authData != null) {
                        Divider(modifier = Modifier.padding(vertical = 12.dp))
                        Text("Данные авторизации:", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        Text("FSID: ${authData.fsid}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("DeviceID: ${authData.deviceId}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Auth Data", "FSID: ${authData.fsid}\nDeviceID: ${authData.deviceId}")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Данные скопированы", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📋 Скопировать данные")
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("На главную")
            }
        }
    }
}

@Composable
fun ProfileInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    dbHelper: DatabaseHelper,
    authData: AuthData?
) {
    var stats by remember { mutableStateOf<BalanceStats?>(null) }
    
    LaunchedEffect(authData) {
        authData?.let {
            try {
                val user = dbHelper.getUser(it.fsid, it.deviceId)
                user?.let { userId ->
                    stats = dbHelper.getBalanceStats(userId.id)
                }
            } catch (e: Exception) { }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📊 Статистика") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("📈 За сегодня", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        stats?.let {
                            Text("Текущий баланс: ${String.format("%.2f ₽", it.currentBalance)}")
                            Text("Минимальный: ${String.format("%.2f ₽", it.todayMin)}")
                            Text("Максимальный: ${String.format("%.2f ₽", it.todayMax)}")
                            Text("Средний: ${String.format("%.2f ₽", it.todayAvg)}")
                            Text("Ошибок: ${it.todayErrors}")
                        } ?: run {
                            Text("Нет данных")
                        }
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("💾 База данных", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val tableStats = remember { dbHelper.getTableStats() }
                        tableStats.forEach { (table, count) ->
                            Text("${table.replace("_", " ")}: $count записей")
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Размер: ${dbHelper.getDatabaseSize(LocalContext.current)}")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    dbHelper: DatabaseHelper,
    authData: AuthData?
) {
    var logs by remember { mutableStateOf<List<BotLog>>(emptyList()) }
    
    LaunchedEffect(authData) {
        authData?.let {
            try {
                val user = dbHelper.getUser(it.fsid, it.deviceId)
                user?.let { userId ->
                    logs = dbHelper.getLogs(100).filter { log -> log.userId == userId.id }
                }
            } catch (e: Exception) { }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📜 История") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("История пуста")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                                        .format(Date(log.createdAt * 1000)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(log.message, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                            Text(
                                log.logType.uppercase(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (log.logLevel) {
                                    "ERROR" -> Color(0xFFF44336)
                                    "WARNING" -> Color(0xFFFF9800)
                                    else -> Color(0xFF4CAF50)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSave: () -> Unit,
    dbHelper: DatabaseHelper
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE) }
    
    var maxMatchesPerExpress by remember { mutableStateOf(prefs.getInt("max_matches_per_express", 2).toString()) }
    var multiply by remember { mutableStateOf(prefs.getInt("multiply", 2).toString()) }
    var allMinKef by remember { mutableStateOf(prefs.getFloat("all_min_kef", 1.67f).toDouble().toString()) }
    
    var type924Min by remember { mutableStateOf(prefs.getFloat("type_924_min", 1.15f).toString()) }
    var type924Max by remember { mutableStateOf(prefs.getFloat("type_924_max", 1.35f).toString()) }
    var type924Start by remember { mutableStateOf(prefs.getInt("type_924_start", 80).toString()) }
    var type924End by remember { mutableStateOf(prefs.getInt("type_924_end", 100).toString()) }
    
    var type927Min by remember { mutableStateOf(prefs.getFloat("type_927_min", 1.15f).toString()) }
    var type927Max by remember { mutableStateOf(prefs.getFloat("type_927_max", 1.35f).toString()) }
    var type927Start by remember { mutableStateOf(prefs.getInt("type_927_start", 1).toString()) }
    var type927End by remember { mutableStateOf(prefs.getInt("type_927_end", 45).toString()) }
    
    var type928Min by remember { mutableStateOf(prefs.getFloat("type_928_min", 1.15f).toString()) }
    var type928Max by remember { mutableStateOf(prefs.getFloat("type_928_max", 1.35f).toString()) }
    var type928Start by remember { mutableStateOf(prefs.getInt("type_928_start", 1).toString()) }
    var type928End by remember { mutableStateOf(prefs.getInt("type_928_end", 45).toString()) }
    
    var checkInterval by remember { mutableStateOf(prefs.getString("check_interval", "60") ?: "60") }
    var betAmount by remember { mutableStateOf(prefs.getString("bet_amount", "30") ?: "30") }
    var testMode by remember { mutableStateOf(prefs.getBoolean("test_mode", true)) }
    var testBalance by remember { mutableStateOf(prefs.getString("test_balance", "1000") ?: "1000") }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start", true)) }
    var notifications by remember { mutableStateOf(prefs.getBoolean("notifications", true)) }
    
    var showClearDialog by remember { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    var expandedLevel by remember { mutableStateOf(true) }
    var expandedType924 by remember { mutableStateOf(false) }
    var expandedType927 by remember { mutableStateOf(false) }
    var expandedType928 by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⚙️ Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // LEVEL Настройки
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedLevel = !expandedLevel },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🎯 LEVEL Настройки", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Icon(
                                if (expandedLevel) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null
                            )
                        }
                        
                        if (expandedLevel) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            OutlinedTextField(
                                value = maxMatchesPerExpress,
                                onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) maxMatchesPerExpress = it },
                                label = { Text("Макс. матчей в экспрессе") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = multiply,
                                onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) multiply = it },
                                label = { Text("Множитель (MULTIPLY)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = allMinKef,
                                onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) allMinKef = it },
                                label = { Text("Мин. коэффициент (ALL_MIN_KEF)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                        }
                    }
                }
            }
            
            // Тип 924
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedType924 = !expandedType924 },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("924: 1х/футбол/хоккей", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Icon(
                                if (expandedType924) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null
                            )
                        }
                        
                        if (expandedType924) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = type924Min,
                                    onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) type924Min = it },
                                    label = { Text("Мин. кэф", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                
                                OutlinedTextField(
                                    value = type924Max,
                                    onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) type924Max = it },
                                    label = { Text("Макс. кэф", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = type924Start,
                                    onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) type924Start = it },
                                    label = { Text("Мониторинг с", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                
                                OutlinedTextField(
                                    value = type924End,
                                    onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) type924End = it },
                                    label = { Text("Мониторинг до", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                            
                            Text(
                                "Минуты: ${type924Start} - ${type924End}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
            
            // Тип 927
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedType927 = !expandedType927 },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("927: ф1(+1.5)/футбол/хоккей", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Icon(
                                if (expandedType927) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null
                            )
                        }
                        
                        if (expandedType927) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = type927Min,
                                    onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) type927Min = it },
                                    label = { Text("Мин. кэф", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                
                                OutlinedTextField(
                                    value = type927Max,
                                    onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) type927Max = it },
                                    label = { Text("Макс. кэф", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = type927Start,
                                    onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) type927Start = it },
                                    label = { Text("Мониторинг с", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                
                                OutlinedTextField(
                                    value = type927End,
                                    onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) type927End = it },
                                    label = { Text("Мониторинг до", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                            
                            Text(
                                "Минуты: ${type927Start} - ${type927End}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
            
            // Тип 928
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedType928 = !expandedType928 },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("928: ф2(+1.5)/футбол/хоккей", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Icon(
                                if (expandedType928) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null
                            )
                        }
                        
                        if (expandedType928) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = type928Min,
                                    onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) type928Min = it },
                                    label = { Text("Мин. кэф", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                
                                OutlinedTextField(
                                    value = type928Max,
                                    onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) type928Max = it },
                                    label = { Text("Макс. кэф", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = type928Start,
                                    onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) type928Start = it },
                                    label = { Text("Мониторинг с", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                
                                OutlinedTextField(
                                    value = type928End,
                                    onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) type928End = it },
                                    label = { Text("Мониторинг до", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                            
                            Text(
                                "Минуты: ${type928Start} - ${type928End}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
            
            // Тестовый режим
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (testMode) 
                            MaterialTheme.colorScheme.tertiaryContainer 
                        else 
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "🧪 Тестовый режим",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Switch(
                                checked = testMode,
                                onCheckedChange = { testMode = it }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            if (testMode) 
                                "✅ Ставки не размещаются реально\nБаланс виртуальный"
                            else 
                                "⚠️ Ставки размещаются на реальные деньги!",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        if (testMode) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            OutlinedTextField(
                                value = testBalance,
                                onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) testBalance = it },
                                label = { Text("Виртуальный баланс (₽)") },
                                leadingIcon = { Icon(Icons.Default.AccountBalanceWallet, null) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                }
            }
            
            // Общие настройки
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("🔧 Общие настройки", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                        
                        OutlinedTextField(
                            value = checkInterval,
                            onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) checkInterval = it },
                            label = { Text("Интервал проверки (сек)") },
                            leadingIcon = { Icon(Icons.Default.Timer, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = betAmount,
                            onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) betAmount = it },
                            label = { Text("Сумма ставки (₽)") },
                            leadingIcon = { Icon(Icons.Default.AttachMoney, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Автозапуск бота")
                            Switch(checked = autoStart, onCheckedChange = { autoStart = it })
                        }
                        
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Уведомления")
                            Switch(checked = notifications, onCheckedChange = { notifications = it })
                        }
                    }
                }
            }
            
            // Управление данными
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("💾 Управление данными", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Размер базы данных:")
                            Text(dbHelper.getDatabaseSize(context), fontWeight = FontWeight.Bold)
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        OutlinedButton(
                            onClick = { showStatsDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Info, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Статистика базы данных")
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedButton(
                            onClick = {
                                val deleted = dbHelper.cleanupOldLogs(30)
                                Toast.makeText(context, "Удалено $deleted старых записей", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Очистить старые логи")
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Button(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.DeleteForever, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ОЧИСТИТЬ ВСЕ ДАННЫЕ")
                        }
                    }
                }
            }
            
            // Кнопки сохранить/отмена
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                        Text("Отмена")
                    }
                    
                    Button(
                        onClick = {
                            prefs.edit()
                                .putInt("max_matches_per_express", maxMatchesPerExpress.toIntOrNull() ?: 2)
                                .putInt("multiply", multiply.toIntOrNull() ?: 2)
                                .putFloat("all_min_kef", allMinKef.toFloatOrNull() ?: 1.67f)
                                .putFloat("type_924_min", type924Min.toFloatOrNull() ?: 1.15f)
                                .putFloat("type_924_max", type924Max.toFloatOrNull() ?: 1.35f)
                                .putInt("type_924_start", type924Start.toIntOrNull() ?: 80)
                                .putInt("type_924_end", type924End.toIntOrNull() ?: 100)
                                .putFloat("type_927_min", type927Min.toFloatOrNull() ?: 1.15f)
                                .putFloat("type_927_max", type927Max.toFloatOrNull() ?: 1.35f)
                                .putInt("type_927_start", type927Start.toIntOrNull() ?: 1)
                                .putInt("type_927_end", type927End.toIntOrNull() ?: 45)
                                .putFloat("type_928_min", type928Min.toFloatOrNull() ?: 1.15f)
                                .putFloat("type_928_max", type928Max.toFloatOrNull() ?: 1.35f)
                                .putInt("type_928_start", type928Start.toIntOrNull() ?: 1)
                                .putInt("type_928_end", type928End.toIntOrNull() ?: 45)
                                .putString("check_interval", checkInterval)
                                .putString("bet_amount", betAmount)
                                .putBoolean("test_mode", testMode)
                                .putString("test_balance", testBalance)
                                .putBoolean("auto_start", autoStart)
                                .putBoolean("notifications", notifications)
                                .apply()
                            
                            Toast.makeText(context, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                            onSave()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Сохранить")
                    }
                }
            }
        }
        
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("⚠️ Очистка данных") },
                text = {
                    Text("Вы уверены, что хотите удалить ВСЕ данные?\n\n" +
                         "Будут удалены:\n" +
                         "• Данные авторизации\n" +
                         "• История баланса\n" +
                         "• Логи работы\n" +
                         "• Статистика\n\n" +
                         "Это действие нельзя отменить!")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showClearDialog = false
                            isClearing = true
                            
                            scope.launch {
                                val success = dbHelper.clearAllData(context)
                                isClearing = false
                                
                                val message = if (success) {
                                    context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                                        .edit().clear().apply()
                                    context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                                        .edit().clear().apply()
                                    
                                    "Все данные успешно удалены"
                                } else {
                                    "Ошибка при удалении данных"
                                }
                                
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                
                                if (success) {
                                    onBack()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        if (isClearing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Да, удалить всё")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }, enabled = !isClearing) {
                        Text("Отмена")
                    }
                }
            )
        }
        
        if (showStatsDialog) {
            val stats = remember { dbHelper.getTableStats() }
            
            AlertDialog(
                onDismissRequest = { showStatsDialog = false },
                title = { Text("📊 Статистика базы данных") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Записей в таблицах:", fontWeight = FontWeight.Bold)
                        
                        stats.forEach { (table, count) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(table.replace("_", " ").replaceFirstChar { it.uppercase() })
                                Text(count.toString(), fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Общий размер:", fontWeight = FontWeight.Bold)
                            Text(dbHelper.getDatabaseSize(context), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showStatsDialog = false }) {
                        Text("Закрыть")
                    }
                }
            )
        }
    }
}

// ==================== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ====================

private fun getCurrentTime(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}

@Composable
fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, text, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = text, fontSize = 12.sp)
        }
    }
}

fun typeName(type: Int): String = when (type) {
    924 -> "1X"
    927 -> "Ф1(+1.5)"
    928 -> "Ф2(+1.5)"
    921 -> "П1"
    930 -> "ТБ(0.5)"
    1696 -> "ТБ"
    1793 -> "ТБ"
    1796 -> "ТБ"
    1799 -> "ТБ"
    4241 -> "ОЗ ДА"
    else -> "Тип $type"
}

fun formatTimestamp(timestamp: Long): String {
    if (timestamp == 0L) return "—"
    return SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(timestamp * 1000))
}