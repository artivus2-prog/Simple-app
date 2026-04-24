// BotForegroundService.kt - ИСПРАВЛЕННАЯ ВЕРСИЯ
package com.example.fonbetbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

class BotForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var prefs: SharedPreferences
    private lateinit var engine: BotEngine

    companion object {
        const val CHANNEL_ID = "BotForegroundChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "STOP_BOT"
        private const val TAG = "BotForegroundService"

        var isRunning = false
        var onBalanceUpdate: ((Double) -> Unit)? = null
        var onLogUpdate: ((String) -> Unit)? = null
        var onBetsUpdate: ((List<Pair<Int, Int>>) -> Unit)? = null
        var onScoresUpdate: ((String) -> Unit)? = null
        var authData: AuthData? = null
        var lastBalance: Double = 0.0
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createBetNotificationChannel()
        dbHelper = DatabaseHelper(this)
        prefs = getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

        engine = BotEngine(applicationContext, dbHelper, prefs).apply {
            onBalanceUpdate = { bal ->
                lastBalance = bal
                BotForegroundService.onBalanceUpdate?.invoke(bal)
            }
            onLogUpdate = { log -> BotForegroundService.onLogUpdate?.invoke(log) }
            onBetsUpdate = { bets -> BotForegroundService.onBetsUpdate?.invoke(bets) }
            onScoresUpdate = { msg -> BotForegroundService.onScoresUpdate?.invoke(msg) }
        }

        // Восстановление данных
        authData = BotForegroundService.authData
        engine.setAuthData(authData)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBot()
                // stopSelf() вызывается внутри stopBot() после завершения stopEngine()
            }
            else -> startBot()
        }
        return START_STICKY
    }

    private fun startBot() {
        if (isRunning) return
        isRunning = true

        val notification = createNotification("Бот запущен", "Баланс: загрузка...")
        startForegroundNotification(notification)

        BotForegroundService.authData?.let { engine.setAuthData(it) }
        val testMode = prefs.getBoolean("test_mode", true)
        val modeText = if (testMode) "ТЕСТОВЫЙ РЕЖИМ" else "РЕАЛЬНЫЙ РЕЖИМ"
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        engine.onLogUpdate?.invoke("[$timestamp] 🚀 Бот запущен | $modeText")

        serviceScope.launch {
            try {
                engine.startEngine()
            } catch (e: CancellationException) {
                Log.d(TAG, "startEngine отменён")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка startEngine: ${e.message}")
            }
        }
    }

    private fun stopBot() {
        isRunning = false

        // Дожидаемся завершения остановки движка перед стопом сервиса
        serviceScope.launch {
            try {
                engine.stopEngine()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка stopEngine: ${e.message}")
            } finally {
                ServiceCompat.stopForeground(this@BotForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startForegroundNotification(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val stopIntent = Intent(this, BotForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            this, 0, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(appPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Фонбет Бот", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Статус работы бота Фонбет"
                    setShowBadge(true)
                }
            )
        }
    }

    private fun createBetNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel("bet_channel", "Ставки", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Уведомления о размещении ставок"
                    setShowBadge(true)
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}