// MainActivity.kt
package com.example.fonbetbot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) Toast.makeText(this, "Требуются разрешения для работы бота", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE) }
    
    var authData by remember {
        val fsid = prefs.getString("fsid", "") ?: ""
        val deviceId = prefs.getString("device_id", "") ?: ""
        mutableStateOf(
            if (fsid.isNotEmpty() && deviceId.isNotEmpty()) AuthData(fsid, deviceId) else null
        )
    }
    
    var currentScreen by remember { mutableStateOf("main") }
    var isBotRunning by remember { mutableStateOf(BotForegroundService.isRunning) }
    var balance by remember { mutableStateOf(BotForegroundService.lastBalance) }
    val logs = remember { mutableStateListOf<String>() }
    var showStopDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    DisposableEffect(Unit) {
        BotForegroundService.onBalanceUpdate = { newBalance -> balance = newBalance }
        BotForegroundService.onLogUpdate = { log ->
            logs.add(0, log)
            if (logs.size > 100) logs.removeLast()
        }
        onDispose {
            BotForegroundService.onBalanceUpdate = null
            BotForegroundService.onLogUpdate = null
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) scrollState.animateScrollTo(0)
    }

    fun startBot() {
        if (authData == null) return
        BotForegroundService.authData = authData
        val serviceIntent = Intent(context, BotForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        isBotRunning = true
        logs.add(0, "[${getCurrentTime()}] 🚀 Бот запущен")
    }

    fun stopBot() {
        val stopIntent = Intent(context, BotForegroundService::class.java).apply {
            action = BotForegroundService.ACTION_STOP
        }
        context.startService(stopIntent)
        isBotRunning = false
        logs.add(0, "[${getCurrentTime()}] ⏹ Бот остановлен")
    }

    // Простой экран без Scaffold
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        // Верхняя панель
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(12.dp)
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (isBotRunning) Color(0xFF4CAF50) else Color(0xFFF44336),
                        RoundedCornerShape(5.dp)
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (balance > 0) "₽ %.2f".format(balance) else "₽ —",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            if (isBotRunning) {
                TextButton(onClick = { showStopDialog = true }) {
                    Text("СТОП", color = Color(0xFFF44336), fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Статус авторизации
        if (authData == null) {
            Button(
                onClick = { currentScreen = "webAuth" },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("🔐 Авторизоваться", fontSize = 16.sp)
            }
        } else {
            Text("✅ Авторизация активна", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "FSID: ${authData.fsid.take(30)}...",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Кнопка запуска
        Button(
            onClick = { if (isBotRunning) showStopDialog = true else startBot() },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isBotRunning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isBotRunning) "🛑 ОСТАНОВИТЬ" else "▶ ЗАПУСТИТЬ",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Логи
        Text("📝 Логи:", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(16.dp)
                )
        ) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Логи появятся здесь", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(12.dp)
                ) {
                    logs.forEach { log ->
                        Text(
                            text = log,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }

    // WebView экран
    if (currentScreen == "webAuth") {
        WebViewAuthScreen(
            onAuthSuccess = { fsid, deviceId ->
                prefs.edit().putString("fsid", fsid).putString("device_id", deviceId).apply()
                authData = AuthData(fsid, deviceId)
                currentScreen = "main"
            },
            onBack = { currentScreen = "main" }
        )
    }

    // Диалог остановки
    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Остановить бота?") },
            text = { Text("Бот работает в фоновом режиме.") },
            confirmButton = {
                Button(
                    onClick = { showStopDialog = false; stopBot() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Остановить") }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text("Отмена") }
            }
        )
    }
}

private fun getCurrentTime(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}