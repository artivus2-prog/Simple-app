// MainActivity.kt
package com.example.fonbetbot

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                    MainBotScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainBotScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isBotRunning by remember { mutableStateOf(BotForegroundService.isRunning) }
    var balance by remember { mutableStateOf(BotForegroundService.lastBalance) }
    val logs = remember { mutableStateListOf<String>() }
    var showExitDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Подписка на обновления от сервиса
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

    // Автопрокрутка логов вверх
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollTo(0)
        }
    }

    fun startBot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(context, "Нет разрешения на фоновую работу", Toast.LENGTH_LONG).show()
                return
            }
        }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (balance > 0) "₽ %.2f".format(balance) else "₽ —",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                actions = {
                    if (isBotRunning) {
                        IconButton(onClick = { showExitDialog = true }) {
                            Icon(Icons.Default.Stop, "Остановить")
                        }
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
            // Статус
            Text(
                text = if (isBotRunning) "🟢 Бот работает" else "🔴 Бот остановлен",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Кнопка запуска/остановки
            Button(
                onClick = { if (isBotRunning) showExitDialog = true else startBot() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBotRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isBotRunning) "🛑 ОСТАНОВИТЬ БОТА" else "▶ ЗАПУСТИТЬ БОТА",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Заголовок логов
            Text(
                text = "📝 Лог событий",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Логи
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
    }

    // Диалог подтверждения остановки
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

private fun getCurrentTime(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}