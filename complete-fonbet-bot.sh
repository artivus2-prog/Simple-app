#!/data/data/com.termux/files/usr/bin/bash

cd ~/simple-app
git checkout dev

echo "📝 ДОБАВЛЯЕМ ЗАГЛУШКУ АВТОРИЗАЦИИ И ДОДЕЛЫВАЕМ МЕНЮ..."

cat > app/src/main/java/com/example/fonbetbot/MainActivity.kt << 'KOTLIN_EOF'
package com.example.fonbetbot

import android.os.Bundle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FonbetBotApp() {
    var isLoggedIn by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf("auth") }
    
    if (!isLoggedIn) {
        AuthScreen(onLoginSuccess = { 
            isLoggedIn = true
            currentScreen = "main"
        })
    } else {
        when (currentScreen) {
            "main" -> MainBotScreen(
                onNavigateToStats = { currentScreen = "stats" },
                onNavigateToSettings = { currentScreen = "settings" },
                onNavigateToHistory = { currentScreen = "history" },
                onNavigateToProfile = { currentScreen = "profile" },
                onLogout = { 
                    isLoggedIn = false
                    currentScreen = "auth"
                }
            )
            "stats" -> StatsScreen(onBack = { currentScreen = "main" })
            "settings" -> SettingsScreen(
                onBack = { currentScreen = "main" },
                onSave = { currentScreen = "main" }
            )
            "history" -> HistoryScreen(onBack = { currentScreen = "main" })
            "profile" -> ProfileScreen(onBack = { currentScreen = "main" })
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
    
    // Заглушка авторизации
    val validLogin = "admin"
    val validPassword = "admin"
    
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text("🔐 Fonbet Bot", fontWeight = FontWeight.Bold)
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
                    
                    // Подсказка для теста
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
                        isError = errorMessage != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
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
                            
                            // Проверка логина и пароля
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
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onLogout: () -> Unit
) {
    var isBotRunning by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf(10000.0) }
    val logs = remember { mutableStateListOf<String>() }
    var showMenu by remember { mutableStateOf(false) }
    
    // Симуляция работы бота
    LaunchedEffect(isBotRunning) {
        while (isBotRunning) {
            delay(5000)
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
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
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Кнопка запуска/остановки
            Button(
                onClick = {
                    isBotRunning = !isBotRunning
                    val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    if (isBotRunning) {
                        logs.add(0, "[$timestamp] 🚀 Бот запущен")
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
                StatCard(
                    title = "За сегодня",
                    items = listOf(
                        StatItem("Профит", "+1,250 ₽", Color(0xFF4CAF50)),
                        StatItem("Ставок", "15", Color(0xFF2196F3)),
                        StatItem("Выигрышей", "11", Color(0xFF4CAF50)),
                        StatItem("Процент", "73.3%", Color(0xFFFF9800))
                    )
                )
            }
            
            item {
                StatCard(
                    title = "За неделю",
                    items = listOf(
                        StatItem("Профит", "+8,420 ₽", Color(0xFF4CAF50)),
                        StatItem("Ставок", "87", Color(0xFF2196F3)),
                        StatItem("Выигрышей", "58", Color(0xFF4CAF50)),
                        StatItem("Процент", "66.7%", Color(0xFFFF9800))
                    )
                )
            }
            
            item {
                StatCard(
                    title = "За всё время",
                    items = listOf(
                        StatItem("Профит", "+24,850 ₽", Color(0xFF4CAF50)),
                        StatItem("Ставок", "312", Color(0xFF2196F3)),
                        StatItem("Выигрышей", "218", Color(0xFF4CAF50)),
                        StatItem("Проигрышей", "94", Color(0xFFF44336)),
                        StatItem("Процент", "69.9%", Color(0xFFFF9800)),
                        StatItem("Лучший день", "+3,200 ₽", Color(0xFF9C27B0))
                    )
                )
            }
        }
    }
}

@Composable
fun StatCard(title: String, items: List<StatItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        item.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        item.value,
                        fontWeight = FontWeight.Bold,
                        color = item.color
                    )
                }
                if (item != items.last()) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

data class StatItem(val label: String, val value: String, val color: Color)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val historyItems = listOf(
        HistoryItem("15:30", "Победа", "+500 ₽", Color(0xFF4CAF50)),
        HistoryItem("14:15", "Победа", "+250 ₽", Color(0xFF4CAF50)),
        HistoryItem("13:00", "Поражение", "-100 ₽", Color(0xFFF44336)),
        HistoryItem("12:30", "Победа", "+750 ₽", Color(0xFF4CAF50)),
        HistoryItem("11:45", "Победа", "+300 ₽", Color(0xFF4CAF50)),
        HistoryItem("10:20", "Поражение", "-100 ₽", Color(0xFFF44336)),
        HistoryItem("09:15", "Победа", "+200 ₽", Color(0xFF4CAF50))
    )
    
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
            items(historyItems) { item ->
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
                                item.time,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                item.status,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            item.amount,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = item.color
                        )
                    }
                }
            }
        }
    }
}

data class HistoryItem(
    val time: String,
    val status: String,
    val amount: String,
    val color: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
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
                    ProfileInfoRow("Баланс", "10,000 ₽")
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
KOTLIN_EOF

echo "✅ Код обновлён!"
echo ""
echo "📦 КОММИТИМ И ПУШИМ..."

git add app/src/main/java/com/example/fonbetbot/MainActivity.kt
git commit -m "Complete Fonbet Bot: auth stub (admin/admin), all menus, improved UI"
git push origin dev

echo ""
echo "════════════════════════════════════════════════════"
echo "✅ FONBET BOT ПОЛНОСТЬЮ ГОТОВ!"
echo "════════════════════════════════════════════════════"
echo ""
echo "🔐 ДАННЫЕ ДЛЯ ВХОДА:"
echo "   Логин: admin"
echo "   Пароль: admin"
echo ""
echo "📱 ЧТО ДОБАВЛЕНО:"
echo "   ✅ Заглушка авторизации admin/admin"
echo "   ✅ Главный экран с балансом и логами"
echo "   ✅ Статистика (день/неделя/всё время)"
echo "   ✅ История ставок"
echo "   ✅ Профиль пользователя"
echo "   ✅ Настройки с переключателями"
echo "   ✅ Улучшенный дизайн всех экранов"
echo "   ✅ Симуляция работы бота"
echo ""
echo "🔍 СБОРКА ЗАПУЩЕНА АВТОМАТИЧЕСКИ"
echo "   https://github.com/artivus2-prog/simple-app/actions"
echo ""

