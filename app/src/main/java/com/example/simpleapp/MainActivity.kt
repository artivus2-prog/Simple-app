package com.example.simpleapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
                onLogout = { 
                    isLoggedIn = false
                    currentScreen = "auth"
                }
            )
            "stats" -> StatsScreen(onBack = { currentScreen = "main" })
            "settings" -> SettingsScreen(onBack = { currentScreen = "main" })
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
    
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("🔐 Авторизация Fonbet", fontWeight = FontWeight.Bold) })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🤖", fontSize = 48.sp)
                    Text("Добро пожаловать!", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Войдите в аккаунт Fonbet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    OutlinedTextField(
                        value = login, onValueChange = { login = it },
                        label = { Text("Логин / Email") },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.fillMaxWidth(),
                        isError = errorMessage != null
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Пароль") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        isError = errorMessage != null
                    )
                    
                    if (errorMessage != null) {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            if (login.isNotEmpty() && password.isNotEmpty()) {
                                onLoginSuccess()
                            } else {
                                errorMessage = "Введите логин и пароль"
                                isLoading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        } else {
                            Text("ВОЙТИ", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainBotScreen(onNavigateToStats: () -> Unit, onNavigateToSettings: () -> Unit, onLogout: () -> Unit) {
    var isBotRunning by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf(1000.0) }
    val logs = remember { mutableStateListOf<String>() }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🤖 Fonbet Bot", fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = onLogout) { Icon(Icons.Default.ExitToApp, "Выход") } }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isBotRunning) "🟢 БОТ АКТИВЕН" else "🔴 БОТ ОСТАНОВЛЕН",
                        fontWeight = FontWeight.Bold
                    )
                    Text("Баланс", fontSize = 14.sp)
                    Text(String.format("%.2f ₽", balance), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
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
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBotRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isBotRunning) "🛑 ОСТАНОВИТЬ" else "▶ ЗАПУСТИТЬ", fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onNavigateToStats, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.BarChart, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Статистика")
                }
                OutlinedButton(onClick = onNavigateToSettings, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Settings, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Настройки")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text("📝 Лог событий", fontSize = 12.sp)
            
            Card(modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(12.dp)) {
                LazyColumn(modifier = Modifier.padding(12.dp), reverseLayout = true) {
                    items(logs) { log ->
                        Text(log, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    StatItem("Общий профит", "+150.50 ₽", Color(0xFF4CAF50))
                    StatItem("Всего ставок", "42", Color(0xFF2196F3))
                    StatItem("Выигрышей", "28", Color(0xFF4CAF50))
                    StatItem("Проигрышей", "14", Color(0xFFF44336))
                    StatItem("Процент побед", "66.7%", Color(0xFFFF9800))
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var betAmount by remember { mutableStateOf("100") }
    var checkInterval by remember { mutableStateOf("5") }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⚙️ Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Параметры бота", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    
                    OutlinedTextField(
                        value = betAmount, onValueChange = { betAmount = it },
                        label = { Text("Сумма ставки (₽)") },
                        leadingIcon = { Icon(Icons.Default.AttachMoney, null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = checkInterval, onValueChange = { checkInterval = it },
                        label = { Text("Интервал проверки (сек)") },
                        leadingIcon = { Icon(Icons.Default.Timer, null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Button(onClick = { }, modifier = Modifier.fillMaxWidth()) {
                        Text("Сохранить")
                    }
                }
            }
        }
    }
}
