// BotForegroundService.kt
package com.example.fonbetbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class BotForegroundService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var balance = 0.0
    private val apiClient = ApiClient()
    
    companion object {
        const val CHANNEL_ID = "BotForegroundChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "STOP_BOT"
        
        var isRunning = false
        var onBalanceUpdate: ((Double) -> Unit)? = null
        var onLogUpdate: ((String) -> Unit)? = null
        var authData: AuthData? = null
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBot()
                stopSelf()
            }
            else -> {
                startBot()
            }
        }
        return START_STICKY
    }
    
    private fun startBot() {
        if (isRunning) return
        
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification("Бот запущен", "Баланс: загрузка..."))
        
        onLogUpdate?.invoke("[${getCurrentTime()}] 🚀 Бот запущен в фоновом режиме")
        
        serviceScope.launch {
            while (isRunning) {
                delay(5000)
                
                authData?.let { data ->
                    fetchBalance(data)
                }
            }
        }
    }
    
    private fun fetchBalance(data: AuthData) {
        val cookieManager = CookieManager.getInstance()
        val cookieString = cookieManager.getCookie("https://www.fon.bet") ?: ""
        
        val cookies = if (cookieString.isNotEmpty()) {
            cookieString.split("; ").associate { cookie ->
                val parts = cookie.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }
        } else {
            emptyMap()
        }
        
        apiClient.getSaldo(
            cookies = cookies,
            fsid = data.fsid,
            deviceId = data.deviceId,
            onSuccess = { saldo ->
                if (saldo != null) {
                    val oldBalance = balance
                    val difference = saldo - oldBalance
                    balance = saldo
                    
                    onBalanceUpdate?.invoke(saldo)
                    
                    val notification = createNotification("Бот активен", "Баланс: %.2f ₽".format(saldo))
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
                    
                    val timestamp = getCurrentTime()
                    if (difference > 0) {
                        onLogUpdate?.invoke("[$timestamp] 💰 Профит: +%.2f ₽".format(difference))
                    } else if (difference < 0) {
                        onLogUpdate?.invoke("[$timestamp] 📉 Убыток: %.2f ₽".format(difference))
                    }
                } else {
                    onLogUpdate?.invoke("[${getCurrentTime()}] ⚠️ Баланс не получен")
                }
            },
            onError = { error ->
                onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ошибка: $error")
            }
        )
    }
    
    private fun stopBot() {
        isRunning = false
        onLogUpdate?.invoke("[${getCurrentTime()}] ⏹ Бот остановлен")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фонбет Бот",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус работы бота Фонбет"
                setShowBadge(true)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(title: String, content: String): Notification {
        val stopIntent = Intent(this, BotForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val appIntent = Intent(this, MainActivity::class.java)
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
    
    private fun getCurrentTime(): String {
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}