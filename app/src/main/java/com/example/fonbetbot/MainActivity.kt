package com.example.fonbetbot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_FSID = "fsid"
        private const val KEY_DEVICE_ID = "device_id"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FonbetBotApp()
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
fun FonbetBotApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE) }
    
    var isLoggedIn by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf("auth") }
    var authData by remember { mutableStateOf<AuthData?>(null) }
    
    // Загружаем сохранённые данные при старте
    LaunchedEffect(Unit) {
        val fsid = prefs.getString("fsid", "") ?: ""
        val deviceId = prefs.getString("device_id", "") ?: ""
        
        if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
            authData = AuthData(fsid, deviceId)
        }
    }
    
    // Функция сохранения данных
    fun saveAuthData(fsid: String, deviceId: String) {
        prefs.edit()
            .putString("fsid", fsid)
            .putString("device_id", deviceId)
            .apply()
        authData = AuthData(fsid, deviceId)
    }
    
    // Функция очистки данных
    fun clearAuthData() {
        prefs.edit().clear().apply()
        authData = null
    }
    
    if (!isLoggedIn) {
        AuthScreen(onLoginSuccess = { 
            isLoggedIn = true
            currentScreen = "main"
        })
    } else {
        when (currentScreen) {
            "main" -> MainBotScreen(
                authData = authData,
                onNavigateToStats = { currentScreen = "stats" },
                onNavigateToSettings = { currentScreen = "settings" },
                onNavigateToHistory = { currentScreen = "history" },
                onNavigateToProfile = { currentScreen = "profile" },
                onNavigateToWebAuth = { currentScreen = "webAuth" },
                onLogout = { 
                    isLoggedIn = false
                    currentScreen = "auth"
                    clearAuthData()
                }
            )
            "webAuth" -> WebViewAuthScreen(
                onAuthSuccess = { fsid, deviceId ->
                    saveAuthData(fsid, deviceId)
                    currentScreen = "main"
                },
                onBack = { currentScreen = "main" }
            )
            "stats" -> StatsScreen(onBack = { currentScreen = "main" })
            "settings" -> SettingsScreen(
                onBack = { currentScreen = "main" },
                onSave = { currentScreen = "main" }
            )
            "history" -> HistoryScreen(onBack = { currentScreen = "main" })
            "profile" -> ProfileScreen(
                authData = authData,
                onBack = { currentScreen = "main" }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(onLoginSuccess: () -> Unit) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val validLogin = "admin"
    val validPassword = "admin"
    
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text("🔐 Фонбет Бот", fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🤖", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Добро пожаловать!",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Войдите в аккаунт",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "💡 Тестовый доступ",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "Логин: admin | Пароль: admin",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it },
                        label = { Text("Логин") },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = errorMessage != null
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Пароль") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = errorMessage != null
                    )
                    
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            
                            if (login == validLogin && password == validPassword) {
                                onLoginSuccess()
                            } else {
                                isLoading = false
                                errorMessage = "Неверный логин или пароль"
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White
                            )
                        } else {
                            Text("ВОЙТИ", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
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
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val apiClient = remember { ApiClient() }
    
    var isBotRunning by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf(10000.0) }
    val logs = remember { mutableStateListOf<String>() }
    var showMenu by remember { mutableStateOf(false) }
    var isLoadingBalance by remember { mutableStateOf(false) }
    
    // Функция для получения баланса через API
    fun fetchBalanceFromApi() {
        if (authData == null) {
            logs.add(0, "[${getCurrentTime()}] ❌ Нет данных авторизации")
            return
        }
        
        isLoadingBalance = true
        
        val cookieManager = CookieManager.getInstance()
        val cookieString = cookieManager.getCookie("https://www.fon.bet") ?: ""
        
        val cookies = cookieString.split("; ").associate { cookie ->
            val parts = cookie.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        
        apiClient.getSaldo(
            cookies = cookies,
            fsid = authData.fsid,
            deviceId = authData.deviceId,
            onSuccess = { saldo ->
                isLoadingBalance = false
                if (saldo != null) {
                    balance = saldo
                    logs.add(0, "[${getCurrentTime()}] 💰 Баланс обновлён: $saldo ₽")
                }
            },
            onError = { error ->
                isLoadingBalance = false
                logs.add(0, "[${getCurrentTime()}] ❌ Ошибка API: $error")
            }
        )
    }
    
    // Симуляция работы бота
    LaunchedEffect(isBotRunning) {
        while (isBotRunning) {
            delay(5000)
            val timestamp = getCurrentTime()
            val profit = (10..500).random().toDouble()
            balance += profit
            logs.add(0, "[$timestamp] 💰 Профит: +$profit ₽")
            if (logs.size > 20) logs.removeLast()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🤖 ", fontSize = 24.sp)
                        Text("Fonbet Bot", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, "Меню")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("👤 Профиль") },
                            onClick = {
                                showMenu = false
                                onNavigateToProfile()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📊 Статистика") },
                            onClick = {
                                showMenu = false
                                onNavigateToStats()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📜 История") },
                            onClick = {
                                showMenu = false
                                onNavigateToHistory()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("⚙️ Настройки") },
                            onClick = {
                                showMenu = false
                                onNavigateToSettings()
                            }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("🚪 Выйти") },
                            onClick = {
                                showMenu = false
                                onLogout()
                            }
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
                .padding(16.dp)
        ) {
            // Карточка авторизации
            if (authData != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "✅ Данные авторизации получены",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Row {
                                IconButton(onClick = { 
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText(
                                        "Auth Data",
                                        "FSID: ${authData.fsid}\nDeviceID: ${authData.deviceId}"
                                    )
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.ContentCopy, "Копировать", modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { 
                                    onNavigateToWebAuth()
                                }) {
                                    Icon(Icons.Default.Refresh, "Обновить", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Text(
                            "FSID: ${authData.fsid.take(25)}...",
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            "DeviceID: ${authData.deviceId.take(25)}...",
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else {
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
                            "⚠️ Требуется авторизация на сайте Фонбет",
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
            
            // Карточка баланса и статуса
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isBotRunning) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isBotRunning) "🟢" else "🔴",
                            fontSize = 24.sp
                        )
                        Text(
                            text = if (isBotRunning) "БОТ АКТИВЕН" else "БОТ ОСТАНОВЛЕН",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        "Текущий баланс",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (isLoadingBalance) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    } else {
                        Text(
                            String.format("%.2f ₽", balance),
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (balance >= 10000) 
                                Color(0xFF4CAF50) 
                            else 
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Кнопка запуска/остановки
            Button(
                onClick = {
                    isBotRunning = !isBotRunning
                    val timestamp = getCurrentTime()
                    if (isBotRunning) {
                        logs.add(0, "[$timestamp] 🚀 Бот запущен")
                        if (authData != null) {
                            fetchBalanceFromApi()
                        }
                    } else {
                        logs.add(0, "[$timestamp] ⏹ Бот остановлен")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBotRunning) 
                        MaterialTheme.colorScheme.error 
                    else 
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    if (isBotRunning) "🛑 ОСТАНОВИТЬ БОТА" else "▶ ЗАПУСТИТЬ БОТА",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Быстрые действия
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionButton(
                    icon = Icons.Default.BarChart,
                    text = "Статистика",
                    onClick = onNavigateToStats,
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    icon = Icons.Default.History,
                    text = "История",
                    onClick = onNavigateToHistory,
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    icon = Icons.Default.Settings,
                    text = "Настройки",
                    onClick = onNavigateToSettings,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Лог событий
            Text(
                "📝 Лог событий",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Запустите бота для отображения логов",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(12.dp),
                        reverseLayout = true
                    ) {
                        items(logs) { log ->
                            Text(
                                log,
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

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
            Text(text, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authData: AuthData?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    
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
                    
                    Text(
                        "Administrator",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        "admin@fonbet-bot.com",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Divider()
                    
                    ProfileInfoRow("ID пользователя", "12345")
                    ProfileInfoRow("Статус", "Активен")
                    ProfileInfoRow("Дата регистрации", "01.01.2024")
                    ProfileInfoRow("Тариф", "Premium")
                    
                    if (authData != null) {
                        Divider(modifier = Modifier.padding(vertical = 12.dp))
                        Text(
                            "Данные авторизации:",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            "FSID: ${authData.fsid}",
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Text(
                            "DeviceID: ${authData.deviceId}",
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(
                                    "Auth Data",
                                    "FSID: ${authData.fsid}\nDeviceID: ${authData.deviceId}"
                                )
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
            
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
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
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
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
                        Text("Профит: +1,250 ₽")
                        Text("Ставок: 15")
                        Text("Выигрышей: 11")
                        Text("Процент побед: 73.3%")
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("📅 За неделю", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Профит: +8,420 ₽")
                        Text("Ставок: 87")
                        Text("Выигрышей: 58")
                        Text("Процент побед: 66.7%")
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("🏆 За всё время", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Профит: +24,850 ₽")
                        Text("Ставок: 312")
                        Text("Выигрышей: 218")
                        Text("Процент побед: 69.9%")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📜 История ставок") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(listOf(
                Triple("15:30", "Победа", "+500 ₽"),
                Triple("14:15", "Победа", "+250 ₽"),
                Triple("13:00", "Поражение", "-100 ₽"),
                Triple("12:30", "Победа", "+750 ₽"),
                Triple("11:45", "Победа", "+300 ₽"),
                Triple("10:20", "Поражение", "-100 ₽"),
                Triple("09:15", "Победа", "+200 ₽")
            )) { (time, status, amount) ->
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
                                time,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                status,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            amount,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (amount.startsWith("+")) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onSave: () -> Unit) {
    var betAmount by remember { mutableStateOf("100") }
    var checkInterval by remember { mutableStateOf("5") }
    var autoStart by remember { mutableStateOf(true) }
    var notifications by remember { mutableStateOf(true) }
    var soundEnabled by remember { mutableStateOf(false) }
    
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
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "💰 Параметры ставок",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        OutlinedTextField(
                            value = betAmount,
                            onValueChange = { betAmount = it },
                            label = { Text("Сумма ставки (₽)") },
                            leadingIcon = { Icon(Icons.Default.AttachMoney, null) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        OutlinedTextField(
                            value = checkInterval,
                            onValueChange = { checkInterval = it },
                            label = { Text("Интервал проверки (сек)") },
                            leadingIcon = { Icon(Icons.Default.Timer, null) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "🔧 Общие настройки",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Автозапуск бота")
                            Switch(
                                checked = autoStart,
                                onCheckedChange = { autoStart = it }
                            )
                        }
                        
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Уведомления")
                            Switch(
                                checked = notifications,
                                onCheckedChange = { notifications = it }
                            )
                        }
                        
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Звуковые сигналы")
                            Switch(
                                checked = soundEnabled,
                                onCheckedChange = { soundEnabled = it }
                            )
                        }
                    }
                }
            }
            
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Отмена")
                    }
                    
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Сохранить")
                    }
                }
            }
        }
    }
}