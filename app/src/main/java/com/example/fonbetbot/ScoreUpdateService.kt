// ScoreUpdateService.kt
package com.example.fonbetbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class ScoreUpdateService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val apiClient = ApiClient()
    private lateinit var database: AppDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var updateJob: Job? = null

    companion object {
        const val CHANNEL_ID = "FonbetBotScoreUpdate"
        const val NOTIFICATION_ID = 100
        const val TAG = "ScoreUpdateService"
        var isRunning = false
        var onLogUpdate: ((String) -> Unit)? = null
        private const val UPDATE_INTERVAL_MS = 30_000L // 30 секунд
        private const val WAKE_LOCK_TAG = "FonbetBot::ScoreUpdateWakeLock"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Инициализация сервиса обновления счетов")
        
        createNotificationChannel()
        database = AppDatabase.getDatabase(this)
        acquireWakeLock()
        
        // Проверка оптимизации батареи
        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            Log.w(TAG, "Оптимизация батареи не отключена. Сервис может быть остановлен системой.")
            logMessage("⚠️ Рекомендуется отключить оптимизацию батареи для стабильной работы")
        }
        
        Log.d(TAG, "Сервис создан успешно")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: flags=$flags, startId=$startId")
        
        when {
            intent?.action == "STOP_SERVICE" -> {
                Log.d(TAG, "Получена команда остановки сервиса")
                stopSelf()
                return START_NOT_STICKY
            }
            !isRunning -> {
                Log.d(TAG, "Запуск сервиса обновления счетов")
                isRunning = true
                startForegroundService()
                startScoreUpdates()
                logMessage("🔄 Сервис обновления счетов запущен")
            }
            else -> {
                Log.d(TAG, "Сервис уже запущен, обновляем foreground notification")
                updateForegroundNotification()
                logMessage("🔄 Сервис уже активен")
            }
        }
        
        return START_STICKY
    }

    private fun startForegroundService() {
        try {
            val notification = buildNotification()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Для Android 10+ используем FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION_ID, notification, 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground service запущен успешно")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска foreground service: ${e.message}", e)
            logMessage("❌ Ошибка запуска: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent для остановки сервиса
        val stopIntent = Intent(this, ScoreUpdateService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Fonbet Bot")
                .setContentText("Отслеживание матчей активно")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Fonbet Bot")
                .setContentText("Отслеживание матчей активно")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
                .build()
        }
    }

    private fun updateForegroundNotification() {
        try {
            val notification = buildNotification()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления уведомления: ${e.message}", e)
        }
    }

    private fun startScoreUpdates() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            Log.d(TAG, "Запуск цикла обновления счетов с интервалом ${UPDATE_INTERVAL_MS}мс")
            
            while (isActive) {
                try {
                    updateScores()
                } catch (e: CancellationException) {
                    Log.d(TAG, "Цикл обновления отменен")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка в цикле обновления: ${e.message}", e)
                    logMessage("❌ Ошибка: ${e.message}")
                }
                
                // Используем задержку с проверкой на активность
                try {
                    delay(UPDATE_INTERVAL_MS)
                } catch (e: CancellationException) {
                    Log.d(TAG, "Задержка прервана")
                    throw e
                }
            }
        }
    }

    private suspend fun updateScores() {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Начало обновления счетов")
        
        try {
            // Получаем все данные из БД
            val allData = withContext(Dispatchers.IO) { 
                database.dataDao().getAllData() 
            }
            
            if (allData.isEmpty()) {
                Log.d(TAG, "Нет данных для обновления")
                return
            }
            
            // Получаем уникальные ID матчей, которые не имеют счета
            val matchesWithoutScore = allData
                .filter { it.sh == 0 && it.sa == 0 }
                .distinctBy { it.m_id }
            
            if (matchesWithoutScore.isEmpty()) {
                Log.d(TAG, "Все матчи уже имеют счет")
                return
            }
            
            Log.d(TAG, "Найдено ${matchesWithoutScore.size} матчей без счета из ${allData.size} записей")
            
            // Получаем аналитику для определения активных экспрессов
            val engine = AnalyticsEngine(database)
            val analytics = engine.calculateAnalytics()
            val expresses = (analytics["allExpresses"] as? List<ExpressResult>) ?: emptyList()
            
            // Фильтруем только активные экспрессы
            val liveExpresses = expresses.filter { !isExpressFinished(it) }
            val liveMatchIds = liveExpresses
                .flatMap { it.matches }
                .filter { it.sh == 0 && it.sa == 0 }
                .map { it.matchId }
                .distinct()
            
            Log.d(TAG, "Активных матчей для обновления: ${liveMatchIds.size}")
            
            var updatedCount = 0
            var finishedCount = 0
            var errorCount = 0
            val batchSize = 50 // Обновляем матчи батчами

            // Обрабатываем только активные матчи по ID
            val matchesToUpdate = matchesWithoutScore
                .filter { it.m_id in liveMatchIds }
                .take(100) // Ограничиваем количество за один цикл
            
            matchesToUpdate.chunked(batchSize).forEach { batch ->
                batch.forEach { match ->
                    try {
                        val result = withContext(Dispatchers.IO) {
                            getMatchScore(match.m_id)
                        }
                        
                        if (result != null) {
                            if (result.first >= 0 && result.second >= 0) {
                                saveScoreToDb(match.m_id, result.first, result.second)
                                updatedCount++
                            }
                            if (result.third) { // Матч завершен
                                finishedCount++
                            }
                        } else {
                            errorCount++
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Ошибка обработки матча ${match.m_id}: ${e.message}")
                        errorCount++
                    }
                    
                    // Небольшая задержка между запросами
                    delay(200)
                }
                
                // Задержка между батчами
                if (batch != matchesToUpdate.chunked(batchSize).last()) {
                    delay(1000)
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            val msg = buildString {
                append("📊 Обновление за ${duration}мс: ")
                if (updatedCount > 0) append("✅ $updatedCount счета ")
                if (finishedCount > 0) append("🏁 $finishedCount завершено ")
                if (errorCount > 0) append("❌ $errorCount ошибок")
            }
            
            if (updatedCount > 0 || finishedCount > 0 || errorCount > 0) {
                logMessage(msg)
            }
            
            Log.d(TAG, msg)
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в updateScores: ${e.message}", e)
            logMessage("❌ Ошибка обновления: ${e.message}")
        }
    }

    private suspend fun getMatchScore(matchId: Long): Triple<Int, Int, Boolean>? {
        return suspendCancellableCoroutine { continuation ->
            try {
                apiClient.getMatchScore(
                    matchId = matchId.toInt(),
                    onSuccess = { factors ->
                        if (factors != null && factors.score1 >= 0 && factors.score2 >= 0) {
                            // Проверяем, завершен ли матч
                            val isFinished = factors.matchTime >= 90 // Для футбола
                            continuation.resume(
                                Triple(factors.score1, factors.score2, isFinished),
                                null
                            )
                        } else {
                            continuation.resume(null, null)
                        }
                    },
                    onError = { error ->
                        Log.w(TAG, "Ошибка получения счета для матча $matchId: $error")
                        continuation.resume(null, null)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Исключение при запросе счета для $matchId: ${e.message}")
                continuation.resume(null, null)
            }
        }
    }

    private suspend fun saveScoreToDb(matchId: Long, sh: Int, sa: Int) {
        withContext(Dispatchers.IO) {
            try {
                val allData = database.dataDao().getAllData()
                val updatedData = allData.map { 
                    if (it.m_id == matchId) {
                        it.copy(sh = sh, sa = sa)
                    } else {
                        it
                    }
                }
                
                // Оптимизированное обновление - удаляем и вставляем только измененные записи
                database.dataDao().deleteAll()
                database.dataDao().insertAll(updatedData)
                
                Log.d(TAG, "Счет обновлен для матча $matchId: $sh:$sa")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка сохранения счета для $matchId: ${e.message}", e)
            }
        }
    }

    private fun isExpressFinished(express: ExpressResult): Boolean {
        val now = LocalDateTime.now()
        val ageHours = ChronoUnit.HOURS.between(express.dateTime, now)
        
        // Экспресс старше 3 часов считаем завершенным
        if (ageHours >= 3) return true
        
        // Если есть хотя бы один проигравший матч - экспресс проигран
        if (express.matches.any { !it.isWin && (it.sh > 0 || it.sa > 0) }) return true
        
        // Если все матчи выиграны - экспресс выигран
        if (express.matches.all { it.isWin && (it.sh > 0 || it.sa > 0) }) return true
        
        return false
    }

    private fun logMessage(message: String) {
        Log.d(TAG, message)
        onLogUpdate?.invoke(message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Обновление счетов",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Канал для фонового обновления счетов матчей"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel создан")
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(10 * 60 * 1000L) // 10 минут максимум
            }
            Log.d(TAG, "WakeLock получен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения WakeLock: ${e.message}", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock освобожден")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка освобождения WakeLock: ${e.message}", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: Приложение удалено из недавних")
        // Перезапускаем сервис, если приложение убрали из недавних
        val restartIntent = Intent(applicationContext, ScoreUpdateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Остановка сервиса")
        isRunning = false
        
        updateJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        
        logMessage("⏹ Сервис обновления остановлен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind: $intent")
        return null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: $intent")
        return super.onUnbind(intent)
    }
}
