// MainActivity.kt
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var dbHelper: DatabaseHelper
    
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) Toast.makeText(this, "Требуются разрешения для работы бота", Toast.LENGTH_LONG).show()
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permissions.add(android.Manifest.permission.POST_NOTIFICATIONS) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { if (checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC) != android.content.pm.PackageManager.PERMISSION_GRANTED) permissions.add(android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC) }
        if (permissions.isNotEmpty()) requestPermissionLauncher.launch(permissions.toTypedArray())
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
        dbHelper = DatabaseHelper(this)
        setContent {
            MaterialTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { FonbetBotApp(dbHelper) } }
        }
    }
}

data class AuthData(val fsid: String, val deviceId: String)
data class ExpressWithMatches(val express: ExpressInfo, val matches: List<MatchInfo>)

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
            try { val userId = dbHelper.saveUser(fsid, deviceId); dbHelper.addLog(userId, "info", "Приложение запущено") } catch (e: Exception) {}
        }
    }
    
    fun saveAuthData(fsid: String, deviceId: String) {
        prefs.edit().putString("fsid", fsid).putString("device_id", deviceId).apply()
        authData = AuthData(fsid, deviceId)
        try { val userId = dbHelper.saveUser(fsid, deviceId); dbHelper.addLog(userId, "info", "Пользователь авторизован") } catch (e: Exception) {}
    }
    
    fun clearAuthData() { prefs.edit().clear().apply(); authData = null }
    
    BackHandler(enabled = currentScreen != "main") { currentScreen = "main" }
    
    when (currentScreen) {
        "main" -> MainBotScreen(authData = authData, onNavigateToStats = { currentScreen = "stats" }, onNavigateToSettings = { currentScreen = "settings" }, onNavigateToHistory = { currentScreen = "history" }, onNavigateToProfile = { currentScreen = "profile" }, onNavigateToWebAuth = { currentScreen = "webAuth" }, onLogout = { currentScreen = "main"; clearAuthData() }, dbHelper = dbHelper)
        "webAuth" -> WebViewAuthScreen(onAuthSuccess = { fsid, deviceId -> saveAuthData(fsid, deviceId); currentScreen = "main" }, onBack = { currentScreen = "main" })
        "stats" -> StatsScreen(onBack = { currentScreen = "main" }, dbHelper = dbHelper, authData = authData)
        "settings" -> SettingsScreen(onBack = { currentScreen = "main" }, onSave = { currentScreen = "main" }, dbHelper = dbHelper)
        "history" -> HistoryScreen(onBack = { currentScreen = "main" }, dbHelper = dbHelper, authData = authData)
        "profile" -> ProfileScreen(authData = authData, onBack = { currentScreen = "main" }, dbHelper = dbHelper)
        "matches" -> MatchesScreen(onBack = { currentScreen = "main" }, dbHelper = dbHelper)
        "expresses" -> ExpressesScreen(onBack = { currentScreen = "main" }, dbHelper = dbHelper)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainBotScreen(
    authData: AuthData?, onNavigateToStats: () -> Unit, onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit, onNavigateToProfile: () -> Unit, onNavigateToWebAuth: () -> Unit,
    onLogout: () -> Unit, dbHelper: DatabaseHelper
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isBotRunning by remember { mutableStateOf(BotForegroundService.isRunning) }
    var balance by remember { mutableStateOf(0.0) }
    val logs = remember { mutableStateListOf<String>() }
    var showMenu by remember { mutableStateOf(false) }
    var isLoadingBalance by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var activeExpresses by remember { mutableStateOf<List<ExpressWithMatches>>(emptyList()) }
    
    fun loadActiveExpresses() {
        try {
            val expresses = dbHelper.getAllExpresses().filter { it.stsAll in listOf(0, 1) }
            activeExpresses = expresses.map { express -> ExpressWithMatches(express, dbHelper.getMatchesByExpressId(express.id)) }
        } catch (e: Exception) {}
    }
    
    fun fetchBalanceFromApi() {
        if (authData == null) { logs.add(0, "[${getCurrentTime()}] ❌ Нет данных авторизации"); return }
        isLoadingBalance = true
        ApiClient().getSaldo(
            cookies = emptyMap(), fsid = authData.fsid, deviceId = authData.deviceId,
            onSuccess = { sessionInfo ->
                isLoadingBalance = false
                if (sessionInfo != null && sessionInfo.saldo != null) {
                    val saldo = sessionInfo.saldo; val oldBalance = balance; balance = saldo
                    logs.add(0, "[${getCurrentTime()}] 💰 Баланс обновлён: %.2f ₽".format(saldo))
/*scope.launch {
    try {
        val user = dbHelper.getUser(authData.fsid, authData.deviceId)
        user?.let { u ->
            dbHelper.saveBalance(u.id, saldo)
            dbHelper.updateUserInfo(u.id, sessionInfo.clientId, sessionInfo.userName)
            val profit = saldo - oldBalance
            when {
                profit > 0 && oldBalance > 0 -> dbHelper.addLog(u.id, "profit", "Профит: +%.2f ₽".format(profit))
                profit < 0 && oldBalance > 0 -> dbHelper.addLog(u.id, "loss", "Убыток: %.2f ₽".format(-profit))
            }
        }
    } catch (e: Exception) {}
*/
//}
                    }
                }
            },
            onError = { error -> isLoadingBalance = false; logs.add(0, "[${getCurrentTime()}] ❌ Ошибка API: $error") }
        )
    }
    
    LaunchedEffect(authData, isBotRunning) {
        authData?.let {
            try { val user = dbHelper.getUser(it.fsid, it.deviceId); user?.let { userId -> val stats = dbHelper.getBalanceStats(userId.id); if (stats.currentBalance > 0) balance = stats.currentBalance } } catch (e: Exception) {}
        }
        if (isBotRunning && authData != null) fetchBalanceFromApi()
        loadActiveExpresses()
    }
    
    LaunchedEffect(Unit) {
        isBotRunning = BotForegroundService.isRunning
        if (isBotRunning) { logs.add(0, "[${getCurrentTime()}] 🔄 Подключено к работающему боту"); if (BotForegroundService.lastBalance > 0) balance = BotForegroundService.lastBalance }
        loadActiveExpresses()
    }
    
    LaunchedEffect(isBotRunning) { while (isBotRunning) { delay(30_000); loadActiveExpresses() } }
    
    DisposableEffect(Unit) {
        BotForegroundService.onBalanceUpdate = { newBalance ->
            balance = newBalance
            authData?.let { data -> scope.launch { try { val user = dbHelper.getUser(data.fsid, data.deviceId); user?.let { dbHelper.saveBalance(it.id, newBalance, "success") } } catch (e: Exception) {} } }
        }
        BotForegroundService.onLogUpdate = { log -> logs.add(0, log); if (logs.size > 50) logs.removeLast() }
        BotForegroundService.onBetsUpdate = { bets ->
            bets.forEach { logs.add(0, "[${getCurrentTime()}] 🎯 m_id=${it.first} (${it.second})") }
            loadActiveExpresses()
        }
        BotForegroundService.onScoresUpdate = { message -> logs.add(0, message); if (logs.size > 50) logs.removeLast(); loadActiveExpresses() }
        BotForegroundService.authData = authData
        onDispose {}
    }
    
    fun startBot() {
        if (authData == null) { logs.add(0, "[${getCurrentTime()}] ❌ Нет данных авторизации"); return }
        BotForegroundService.authData = authData
        val serviceIntent = Intent(context, BotForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, serviceIntent) else context.startService(serviceIntent)
        isBotRunning = true
        scope.launch { try { val user = dbHelper.getUser(authData.fsid, authData.deviceId); user?.let { dbHelper.startBotSession(it.id, balance); dbHelper.addLog(it.id, "start", "Бот запущен") } } catch (e: Exception) {} }
        logs.add(0, "[${getCurrentTime()}] 🚀 Бот запущен в фоне"); fetchBalanceFromApi()
    }
    
    fun stopBot() {
        val stopIntent = Intent(context, BotForegroundService::class.java).apply { action = BotForegroundService.ACTION_STOP }
        context.startService(stopIntent); isBotRunning = false
        scope.launch { try { authData?.let { data -> val user = dbHelper.getUser(data.fsid, data.deviceId); user?.let { dbHelper.stopBotSession(it.id, "user_stop"); dbHelper.addLog(it.id, "stop", "Бот остановлен") } } } catch (e: Exception) {} }
        logs.add(0, "[${getCurrentTime()}] ⏹ Бот остановлен")
    }
    
    BackHandler(enabled = isBotRunning) { showExitDialog = true }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.size(10.dp).background(color = if (isBotRunning) Color(0xFF4CAF50) else Color(0xFFF44336), shape = RoundedCornerShape(5.dp)))
                        Spacer(modifier = Modifier.width(8.dp))
                        if (isLoadingBalance) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(6.dp)); Text("...", fontSize = 14.sp) }
                        else Text(text = if (balance > 0) "₽ %.2f".format(balance) else "₽ —", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.weight(1f))
                        if (authData != null && !isLoadingBalance) {
                            IconButton(onClick = { fetchBalanceFromApi() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Refresh, "Обновить", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                },
                actions = {
                    if (isBotRunning) { IconButton(onClick = { showExitDialog = true }) { Icon(Icons.Default.Stop, "Остановить") } }
                    IconButton(onClick = { showMenu = !showMenu }) { Icon(Icons.Default.MoreVert, "Меню") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("👤 Профиль") }, onClick = { showMenu = false; onNavigateToProfile() }, enabled = !isBotRunning)
                        DropdownMenuItem(text = { Text("📊 Статистика") }, onClick = { showMenu = false; onNavigateToStats() }, enabled = !isBotRunning)
                        DropdownMenuItem(text = { Text("📜 История") }, onClick = { showMenu = false; onNavigateToHistory() }, enabled = !isBotRunning)
                        DropdownMenuItem(text = { Text("⚙️ Настройки") }, onClick = { showMenu = false; onNavigateToSettings() }, enabled = !isBotRunning)
                        Divider()
                        DropdownMenuItem(text = { Text("🚪 Выйти") }, onClick = { if (!isBotRunning) { showMenu = false; onLogout() } }, enabled = !isBotRunning)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            if (isBotRunning) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🔔", fontSize = 20.sp); Spacer(modifier = Modifier.width(8.dp))
                        Column { Text("Бот работает в фоновом режиме", fontWeight = FontWeight.Bold, fontSize = 14.sp); Text("Сверните приложение. Для выхода остановите бота.", fontSize = 12.sp) }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // Таблица активных экспрессов
            if (activeExpresses.isNotEmpty()) {
                Text("🎯 Активные экспрессы (${activeExpresses.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("#", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                            Text("Лига", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                            Text("Матч", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.8f))
                            Text("Счёт", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                            Text("Тип", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(55.dp))
                            Text("Статус", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(55.dp))
                        }
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 250.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(activeExpresses) { expressWithMatches ->
                                Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🧾 #${expressWithMatches.express.idExp}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Кэф ${"%.2f".format(expressWithMatches.express.kfall)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("${expressWithMatches.express.sumbet.toInt()}₽", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(when (expressWithMatches.express.stsAll) { 0 -> "🔄"; 1 -> "❌"; 2 -> "✅"; else -> "—" }, fontSize = 12.sp)
                                }
                                expressWithMatches.matches.forEach { match ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("${match.mId}", fontSize = 9.sp, modifier = Modifier.width(30.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(match.leagueName.ifEmpty { "—" }.take(12), fontSize = 9.sp, modifier = Modifier.weight(1.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${match.homeTeam.take(6)}–${match.awayTeam.take(6)}", fontSize = 9.sp, modifier = Modifier.weight(1.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${match.homeScore}:${match.awayScore}", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                                        Text(typeName(match.betType), fontSize = 9.sp, modifier = Modifier.width(55.dp), color = MaterialTheme.colorScheme.primary)
                                        Text(when (match.status) { 1 -> "🔄 играет"; 2 -> "✅ зашёл"; else -> "⏳ ждёт" }, fontSize = 9.sp, modifier = Modifier.width(55.dp), color = when (match.status) { 1 -> Color(0xFFFF9800); 2 -> Color(0xFF4CAF50); else -> MaterialTheme.colorScheme.onSurfaceVariant })
                                    }
                                }
                                Divider(modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            Button(
                onClick = { if (isBotRunning) showExitDialog = true else startBot() },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isBotRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                enabled = authData != null || isBotRunning
            ) { Text(text = if (isBotRunning) "🛑 ОСТАНОВИТЬ БОТА" else "▶ ЗАПУСТИТЬ БОТА", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            
            if (!isBotRunning) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionButton(Icons.Default.BarChart, "Статистика", onNavigateToStats, Modifier.weight(1f))
                    QuickActionButton(Icons.Default.History, "История", onNavigateToHistory, Modifier.weight(1f))
                    QuickActionButton(Icons.Default.Settings, "Настройки", onNavigateToSettings, Modifier.weight(1f))
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("📝 Лог событий", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            Card(modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                if (logs.isEmpty()) Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { Text("Запустите бота для отображения логов", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else LazyColumn(modifier = Modifier.padding(12.dp), reverseLayout = true) { items(logs) { log -> Text(text = log, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp)) } }
            }
        }
        
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false }, title = { Text("⚠️ Остановить бота?") },
                text = { Text("Бот работает в фоновом режиме.\n\nОстановить бота и выйти?") },
                confirmButton = { Button(onClick = { showExitDialog = false; stopBot() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Остановить") } },
                dismissButton = { TextButton(onClick = { showExitDialog = false }) { Text("Отмена") } }
            )
        }
    }
}

@Composable
fun QuickActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), onClick = onClick) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, text, modifier = Modifier.size(24.dp)); Spacer(modifier = Modifier.height(4.dp)); Text(text = text, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(authData: AuthData?, onBack: () -> Unit, dbHelper: DatabaseHelper) {
    val context = LocalContext.current
    var userStats by remember { mutableStateOf<BalanceStats?>(null) }
    var userInfo by remember { mutableStateOf<DatabaseHelper.UserFullInfo?>(null) }
    
    LaunchedEffect(authData) {
        authData?.let {
            try {
                userInfo = dbHelper.getUserFullInfo(it.fsid, it.deviceId)
                userInfo?.let { info -> userStats = dbHelper.getBalanceStats(info.id) }
            } catch (e: Exception) {}
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("👤 Профиль") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(80.dp).background(Brush.radialGradient(colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)), shape = RoundedCornerShape(40.dp)), contentAlignment = Alignment.Center) { Text("👤", fontSize = 40.sp) }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(userInfo?.username ?: "Пользователь", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (userInfo != null && userInfo!!.clientId > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Text("ID: ${userInfo!!.clientId}", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Client ID", userInfo!!.clientId.toString()))
                                Toast.makeText(context, "Client ID скопирован: ${userInfo!!.clientId}", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, "Скопировать Client ID", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                        }
                    } else { Spacer(modifier = Modifier.height(4.dp)); Text("Аккаунт Фонбет", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(modifier = Modifier.height(24.dp)); Divider()
                    userStats?.let { stats ->
                        ProfileInfoRow("Текущий баланс", String.format("%.2f ₽", stats.currentBalance))
                        ProfileInfoRow("Мин за сегодня", String.format("%.2f ₽", stats.todayMin))
                        ProfileInfoRow("Макс за сегодня", String.format("%.2f ₽", stats.todayMax))
                        ProfileInfoRow("Средний за сегодня", String.format("%.2f ₽", stats.todayAvg))
                        ProfileInfoRow("Ошибок сегодня", stats.todayErrors.toString())
                    } ?: ProfileInfoRow("Статус", "Загружается...")
                    if (authData != null) {
                        Divider(modifier = Modifier.padding(vertical = 12.dp))
                        Text("Данные авторизации:", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) { Text("FSID:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(authData.fsid, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            IconButton(onClick = { val c = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; c.setPrimaryClip(ClipData.newPlainText("FSID", authData.fsid)); Toast.makeText(context, "FSID скопирован", Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.ContentCopy, "Копировать", modifier = Modifier.size(14.dp)) }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) { Text("DeviceID:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(authData.deviceId, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            IconButton(onClick = { val c = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; c.setPrimaryClip(ClipData.newPlainText("DeviceID", authData.deviceId)); Toast.makeText(context, "DeviceID скопирован", Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.ContentCopy, "Копировать", modifier = Modifier.size(14.dp)) }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clientIdStr = if (userInfo?.clientId != null && userInfo!!.clientId > 0) "Client ID: ${userInfo!!.clientId}\n" else ""
                            clipboard.setPrimaryClip(ClipData.newPlainText("Auth Data", "${clientIdStr}FSID: ${authData.fsid}\nDeviceID: ${authData.deviceId}"))
                            Toast.makeText(context, "Все данные скопированы", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.fillMaxWidth()) { Text("📋 Скопировать все данные") }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("На главную") }
        }
    }
}

@Composable
fun ProfileInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit, dbHelper: DatabaseHelper, authData: AuthData?) {
    var stats by remember { mutableStateOf<BalanceStats?>(null) }
    LaunchedEffect(authData) { authData?.let { try { val user = dbHelper.getUser(it.fsid, it.deviceId); user?.let { userId -> stats = dbHelper.getBalanceStats(userId.id) } } catch (e: Exception) {} } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("📊 Статистика") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("📈 За сегодня", fontWeight = FontWeight.Bold, fontSize = 18.sp); Spacer(modifier = Modifier.height(8.dp))
                        stats?.let { Text("Текущий баланс: ${String.format("%.2f ₽", it.currentBalance)}"); Text("Минимальный: ${String.format("%.2f ₽", it.todayMin)}"); Text("Максимальный: ${String.format("%.2f ₽", it.todayMax)}"); Text("Средний: ${String.format("%.2f ₽", it.todayAvg)}"); Text("Ошибок: ${it.todayErrors}") } ?: Text("Нет данных")
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("💾 База данных", fontWeight = FontWeight.Bold, fontSize = 18.sp); Spacer(modifier = Modifier.height(8.dp))
                        val tableStats = remember { dbHelper.getTableStats() }
                        tableStats.forEach { (table, count) -> Text("${table.replace("_", " ")}: $count записей") }
                        Spacer(modifier = Modifier.height(8.dp)); Text("Размер: ${dbHelper.getDatabaseSize(LocalContext.current)}")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, dbHelper: DatabaseHelper, authData: AuthData?) {
    var logs by remember { mutableStateOf<List<BotLog>>(emptyList()) }
    LaunchedEffect(authData) { authData?.let { try { val user = dbHelper.getUser(it.fsid, it.deviceId); user?.let { userId -> logs = dbHelper.getLogs(100).filter { log -> log.userId == userId.id } } } catch (e: Exception) {} } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("📜 История") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) }
    ) { paddingValues ->
        if (logs.isEmpty()) Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) { Text("История пуста") }
        else LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(logs) { log ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(log.createdAt * 1000)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(log.message, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                        Text(log.logType.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = when (log.logLevel) { "ERROR" -> Color(0xFFF44336); "WARNING" -> Color(0xFFFF9800); else -> Color(0xFF4CAF50) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onSave: () -> Unit, dbHelper: DatabaseHelper) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE) }
    
    var maxMatchesPerExpress by remember { mutableStateOf(prefs.getInt("max_matches_per_express", 2).toString()) }
    var multiply by remember { mutableStateOf(prefs.getInt("multiply", 2).toString()) }
    var allMinKef by remember { mutableStateOf(prefs.getFloat("all_min_kef", 1.67f).toDouble().toString()) }
    var type924Min by remember { mutableStateOf(prefs.getFloat("type_924_min", 1.15f).toString()) }; var type924Max by remember { mutableStateOf(prefs.getFloat("type_924_max", 1.35f).toString()) }
    var type924Start by remember { mutableStateOf(prefs.getInt("type_924_start", 80).toString()) }; var type924End by remember { mutableStateOf(prefs.getInt("type_924_end", 100).toString()) }
    var type927Min by remember { mutableStateOf(prefs.getFloat("type_927_min", 1.15f).toString()) }; var type927Max by remember { mutableStateOf(prefs.getFloat("type_927_max", 1.35f).toString()) }
    var type927Start by remember { mutableStateOf(prefs.getInt("type_927_start", 1).toString()) }; var type927End by remember { mutableStateOf(prefs.getInt("type_927_end", 45).toString()) }
    var type928Min by remember { mutableStateOf(prefs.getFloat("type_928_min", 1.15f).toString()) }; var type928Max by remember { mutableStateOf(prefs.getFloat("type_928_max", 1.35f).toString()) }
    var type928Start by remember { mutableStateOf(prefs.getInt("type_928_start", 1).toString()) }; var type928End by remember { mutableStateOf(prefs.getInt("type_928_end", 45).toString()) }
    var checkInterval by remember { mutableStateOf(prefs.getString("check_interval", "60") ?: "60") }
    var betAmount by remember { mutableStateOf(prefs.getString("bet_amount", "30") ?: "30") }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start", true)) }
    var notifications by remember { mutableStateOf(prefs.getBoolean("notifications", true)) }
    var testMode by remember { mutableStateOf(prefs.getBoolean("test_mode", true)) }
    var expandedLevel by remember { mutableStateOf(true) }; var expandedType924 by remember { mutableStateOf(false) }
    var expandedType927 by remember { mutableStateOf(false) }; var expandedType928 by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }; var showStatsDialog by remember { mutableStateOf(false) }; var isClearing by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = { TopAppBar(title = { Text("⚙️ Настройки") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().clickable { expandedLevel = !expandedLevel }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("🎯 LEVEL Настройки", fontSize = 16.sp, fontWeight = FontWeight.Bold); Icon(if (expandedLevel) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                        if (expandedLevel) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(value = maxMatchesPerExpress, onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) maxMatchesPerExpress = it }, label = { Text("Макс. матчей в экспрессе") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(value = multiply, onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) multiply = it }, label = { Text("Множитель (MULTIPLY)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(value = allMinKef, onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) allMinKef = it }, label = { Text("Мин. коэффициент (ALL_MIN_KEF)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                    }
                }
            }
            // Type settings cards (924, 927, 928) - same pattern
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().clickable { expandedType924 = !expandedType924 }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("924: 1х/футбол/хоккей", fontSize = 15.sp, fontWeight = FontWeight.Bold); Icon(if (expandedType924) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                        if (expandedType924) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = type924Min, onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) type924Min = it }, label = { Text("Мин. кэф", fontSize = 11.sp) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                OutlinedTextField(value = type924Max, onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d*$"))) type924Max = it }, label = { Text("Макс. кэф", fontSize = 11.sp) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = type924Start, onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) type924Start = it }, label = { Text("Мониторинг с", fontSize = 11.sp) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                OutlinedTextField(value = type924End, onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) type924End = it }, label = { Text("Мониторинг до", fontSize = 11.sp) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            }
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("🔧 Общие настройки", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                        OutlinedTextField(value = checkInterval, onValueChange = { if (it.all { c -> c.isDigit() } || it.isEmpty()) checkInterval = it }, label = { Text("Интервал проверки (сек)") }, leadingIcon = { Icon(Icons.Default.Timer, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = betAmount, onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) betAmount = it }, label = { Text("Сумма ставки (₽)") }, leadingIcon = { Icon(Icons.Default.AttachMoney, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("Начальная сумма для первой ставки (по умолчанию 30 ₽)", fontSize = 11.sp) })
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Автозапуск бота"); Switch(checked = autoStart, onCheckedChange = { autoStart = it }) }
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Уведомления"); Switch(checked = notifications, onCheckedChange = { notifications = it }) }
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("Тестовый режим"); Text("Ставки сохраняются в БД без реальной отправки", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = testMode, onCheckedChange = { testMode = it }) }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("💾 Управление данными", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Размер базы данных:"); Text(dbHelper.getDatabaseSize(context), fontWeight = FontWeight.Bold) }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = { showStatsDialog = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Info, null); Spacer(modifier = Modifier.width(8.dp)); Text("Статистика базы данных") }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { val deleted = dbHelper.cleanupOldLogs(30); Toast.makeText(context, "Удалено $deleted старых записей", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Delete, null); Spacer(modifier = Modifier.width(8.dp)); Text("Очистить старые логи") }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { showClearDialog = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.DeleteForever, null); Spacer(modifier = Modifier.width(8.dp)); Text("ОЧИСТИТЬ ВСЕ ДАННЫЕ") }
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Отмена") }
                    Button(onClick = {
                        prefs.edit().putInt("max_matches_per_express", maxMatchesPerExpress.toIntOrNull() ?: 2).putInt("multiply", multiply.toIntOrNull() ?: 2).putFloat("all_min_kef", allMinKef.toFloatOrNull() ?: 1.67f)
                            .putFloat("type_924_min", type924Min.toFloatOrNull() ?: 1.15f).putFloat("type_924_max", type924Max.toFloatOrNull() ?: 1.35f).putInt("type_924_start", type924Start.toIntOrNull() ?: 80).putInt("type_924_end", type924End.toIntOrNull() ?: 100)
                            .putFloat("type_927_min", type927Min.toFloatOrNull() ?: 1.15f).putFloat("type_927_max", type927Max.toFloatOrNull() ?: 1.35f).putInt("type_927_start", type927Start.toIntOrNull() ?: 1).putInt("type_927_end", type927End.toIntOrNull() ?: 45)
                            .putFloat("type_928_min", type928Min.toFloatOrNull() ?: 1.15f).putFloat("type_928_max", type928Max.toFloatOrNull() ?: 1.35f).putInt("type_928_start", type928Start.toIntOrNull() ?: 1).putInt("type_928_end", type928End.toIntOrNull() ?: 45)
                            .putString("check_interval", checkInterval).putString("bet_amount", betAmount.ifEmpty { "30" }).putBoolean("auto_start", autoStart).putBoolean("notifications", notifications).putBoolean("test_mode", testMode).apply()
                        Toast.makeText(context, "Настройки сохранены", Toast.LENGTH_SHORT).show(); onSave()
                    }, modifier = Modifier.weight(1f)) { Text("Сохранить") }
                }
            }
        }
        if (showClearDialog) {
            AlertDialog(onDismissRequest = { showClearDialog = false }, title = { Text("⚠️ Очистка данных") }, text = { Text("Вы уверены, что хотите удалить ВСЕ данные?\n\nБудут удалены:\n• Данные авторизации\n• История баланса\n• Логи работы\n• Статистика\n\nЭто действие нельзя отменить!") },
                confirmButton = { Button(onClick = { showClearDialog = false; isClearing = true; scope.launch { val success = dbHelper.clearAllData(context); isClearing = false; val message = if (success) { context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).edit().clear().apply(); context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).edit().clear().apply(); "Все данные успешно удалены" } else "Ошибка при удалении данных"; Toast.makeText(context, message, Toast.LENGTH_LONG).show(); if (success) onBack() } }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { if (isClearing) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text("Да, удалить всё") } },
                dismissButton = { TextButton(onClick = { showClearDialog = false }, enabled = !isClearing) { Text("Отмена") } })
        }
        if (showStatsDialog) {
            val stats = remember { dbHelper.getTableStats() }
            AlertDialog(onDismissRequest = { showStatsDialog = false }, title = { Text("📊 Статистика базы данных") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Записей в таблицах:", fontWeight = FontWeight.Bold); stats.forEach { (table, count) -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(table.replace("_", " ").replaceFirstChar { it.uppercase() }); Text(count.toString(), fontWeight = FontWeight.Bold) } }; Divider(modifier = Modifier.padding(vertical = 8.dp)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Общий размер:", fontWeight = FontWeight.Bold); Text(dbHelper.getDatabaseSize(context), fontWeight = FontWeight.Bold) } } }, confirmButton = { TextButton(onClick = { showStatsDialog = false }) { Text("Закрыть") } })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchesScreen(onBack: () -> Unit, dbHelper: DatabaseHelper) {
    var matches by remember { mutableStateOf<List<MatchInfo>>(emptyList()) }; var isLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { isLoading = true; matches = dbHelper.getAllMatches(); isLoading = false }
    Scaffold(topBar = { TopAppBar(title = { Text("📋 Таблица матчей") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) }) { paddingValues ->
        if (isLoading) Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (matches.isEmpty()) Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) { Text("Нет матчей в базе данных") }
        else LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(matches) { match -> MatchCard(match = match) } }
    }
}

@Composable
fun MatchCard(match: MatchInfo) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = when (match.status) { 1 -> MaterialTheme.colorScheme.surfaceVariant; 2 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f); else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) })) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ID: ${match.mId}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row { Text(when (match.status) { 1 -> "🔄 Активен"; 2 -> "✅ Зашёл"; else -> "❌ Не зашёл" }, fontSize = 12.sp); Spacer(modifier = Modifier.width(4.dp)); Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(20.dp)) }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("${match.homeTeam} vs ${match.awayTeam}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Лига: ${match.leagueName.ifEmpty { "—" }}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Счет: ${match.homeScore} - ${match.awayScore}", fontSize = 13.sp); Text("Кэф: ${"%.2f".format(match.startOdds)}", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Text("Тип ставки: ${typeName(match.betType)}", fontSize = 12.sp)
            if (expanded) { Divider(modifier = Modifier.padding(vertical = 8.dp)); DetailRow("ID матча", match.mId.toString()); DetailRow("ID лиги", match.idLiga?.toString() ?: "—"); DetailRow("ID экспресса", match.idExp.toString()); DetailRow("Текущий кэф", "%.2f".format(match.currentOdds ?: match.startOdds)); DetailRow("Время матча", "${match.matchTime} мин"); DetailRow("URL", match.matchUrl.ifEmpty { "—" }); DetailRow("Создан", formatTimestamp(match.createdAt)); DetailRow("Обновлен", formatTimestamp(match.updatedAt)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressesScreen(onBack: () -> Unit, dbHelper: DatabaseHelper) {
    var expresses by remember { mutableStateOf<List<ExpressInfo>>(emptyList()) }; var isLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { isLoading = true; expresses = dbHelper.getAllExpresses(); isLoading = false }
    Scaffold(topBar = { TopAppBar(title = { Text("🎯 Таблица экспрессов") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) }) { paddingValues ->
        if (isLoading) Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (expresses.isEmpty()) Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) { Text("Нет экспрессов в базе данных") }
        else LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(expresses) { express -> ExpressCard(express = express, matches = dbHelper.getMatchesByExpressId(express.id)) } }
    }
}

@Composable
fun ExpressCard(express: ExpressInfo, matches: List<MatchInfo>) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = when (express.stsAll) { 1 -> MaterialTheme.colorScheme.surfaceVariant; 2 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f); else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) }), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("🎯 Экспресс #${express.idExp}", fontWeight = FontWeight.Bold, fontSize = 16.sp); Spacer(modifier = Modifier.width(8.dp)); val (statusText, statusColor) = when (express.stsAll) { 1 -> Pair("🔄 Активен", Color(0xFFFF9800)); 2 -> Pair("🏆 Выиграл", Color(0xFF4CAF50)); -1 -> Pair("🔄 Заменён", Color(0xFF2196F3)); else -> Pair("❌ Проиграл", Color(0xFFF44336)) }; Text(statusText, fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.Bold) }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Матчей: ${express.eventsCount}", fontSize = 13.sp); Text("Общий кэф: ${"%.2f".format(express.kfall)}", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Ставка: ${express.sumbet.toInt()} ₽", fontSize = 13.sp); Text("Выигрыш: ${"%.2f".format(express.potentialWin)} ₽", fontSize = 13.sp, color = Color(0xFF4CAF50)) }
            if (express.stsAll != 1) Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Результат:", fontSize = 13.sp); Text("${if (express.profLoss >= 0) "+" else ""}${"%.2f".format(express.profLoss)} ₽", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (express.profLoss >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)) }
            Text("Стратегия: ${express.strategy}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Создан: ${formatTimestamp(express.ct)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded && matches.isNotEmpty()) {
                Divider(modifier = Modifier.padding(vertical = 12.dp))
                Text("📋 Матчи в экспрессе:", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                matches.forEach { match -> MatchInExpressCard(match = match); Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun MatchInExpressCard(match: MatchInfo) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("${match.homeTeam} vs ${match.awayTeam}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Счет: ${match.homeScore} - ${match.awayScore}", fontSize = 12.sp)
                Text("${when (match.status) { 1 -> "🔄"; 2 -> "✅"; else -> "❌" }} ${typeName(match.betType)}", fontSize = 12.sp)
                Text("Кэф: ${"%.2f".format(match.startOdds)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}

private fun getCurrentTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
fun typeName(type: Int): String = when (type) { 924 -> "1X"; 927 -> "Ф1(+1.5)"; 928 -> "Ф2(+1.5)"; else -> "Тип $type" }
fun formatTimestamp(timestamp: Long): String = if (timestamp == 0L) "—" else SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(timestamp * 1000))