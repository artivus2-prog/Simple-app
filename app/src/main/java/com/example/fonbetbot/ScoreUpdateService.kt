// ScoreUpdateService.kt - ПОЛНОСТЬЮ РАБОЧАЯ ВЕРСИЯ
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
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class ScoreUpdateService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val apiClient = ApiClient()
    private lateinit var database: AppDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var updateJob: Job? = null

    private val matchTimeCache = mutableMapOf<Long, Int>()
    
    private data class FailedAttemptInfo(
        val firstFailTime: Long,
        var count: Int,
        var lastMinute: Int
    )
    private val failedAttemptsMap = mutableMapOf<Long, FailedAttemptInfo>()

    companion object {
        const val CHANNEL_ID = "FonbetBotScoreUpdate"
        const val NOTIFICATION_ID = 100
        const val TAG = "ScoreUpdateService"
        var isRunning = false
        var onLogUpdate: ((String) -> Unit)? = null
        
        private const val UPDATE_INTERVAL_MS = 30_000L
        private const val WAKE_LOCK_TAG = "FonbetBot::ScoreUpdateWakeLock"
        private const val ACTIVE_HOURS = 2L
        
        private const val MATCH_FINISHED_MINUTE = 90
        private const val MATCH_FINISHED_MINUTE_HOCKEY = 60
        private const val MIN_MATCH_MINUTE_FOR_RETRY = 1
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val FAILED_TIMEOUT_MS = 5 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Инициализация сервиса")
        createNotificationChannel()
        database = AppDatabase.getDatabase(this)
        acquireWakeLock()
        
        val ignoringOptimizations = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        if (!ignoringOptimizations) {
            logMessage("⚠️ Рекомендуется отключить оптимизацию батареи")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val action = intent.action
            if (action == "STOP_SERVICE") {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        
        if (!isRunning) {
            isRunning = true
            startForegroundService()
            startScoreUpdates()
            logMessage("🔄 Сервис обновления запущен")
        }
        
        return START_STICKY
    }

    private fun startForegroundService() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка foreground: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val appIntent = Intent(this, MainActivity::class.java)
        appIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScoreUpdateService::class.java)
        stopIntent.action = "STOP_SERVICE"
        
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Fonbet Bot")
                .setContentText("Отслеживание активных матчей")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            notification = Notification.Builder(this)
                .setContentTitle("Fonbet Bot")
                .setContentText("Отслеживание активных матчей")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
                .build()
        }
        return notification
    }

    private fun startScoreUpdates() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            updateScores()
            
            while (isActive) {
                try {
                    delay(UPDATE_INTERVAL_MS)
                    updateScores()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка в цикле: ${e.message}", e)
                }
            }
        }
    }

    private suspend fun updateScores() {
        val startTime = System.currentTimeMillis()
        
        try {
            val allExp = withContext(Dispatchers.IO) {
                database.expDao().getAllExp()
            }
            val allData = withContext(Dispatchers.IO) {
                database.dataDao().getAllData()
            }
            
            if (allExp.isEmpty() || allData.isEmpty()) {
                return
            }
            
            val now = LocalDateTime.now()
            
            // Фильтруем активные экспрессы
            val activeExpList = mutableListOf<ExpEntity>()
            for (exp in allExp) {
                try {
                    val expTime = parseDateTime(exp.ct)
                    val hoursSinceCreation = ChronoUnit.HOURS.between(expTime, now)
                    
                    // Проверяем, завершен ли экспресс
                    if (hoursSinceCreation <= ACTIVE_HOURS) {
                        val finished = isExpressFinished(exp.id_exp, allData)
                        if (!finished) {
                            activeExpList.add(exp)
                        }
                    }
                } catch (e: Exception) {
                    // ignore parsing errors
                }
            }
            
            if (activeExpList.isEmpty()) {
                logMessage("✓ Нет активных экспрессов")
                return
            }
            
            // Собираем матчи активных экспрессов
            val matchesToCheckList = mutableListOf<DataEntity>()
            val seenIds = mutableSetOf<Long>()
            
            for (exp in activeExpList) {
                for (data in allData) {
                    if (data.id_exp == exp.id_exp) {
                        if (data.sh == 0 && data.sa == 0 && data.m_id !in seenIds) {
                            matchesToCheckList.add(data)
                            seenIds.add(data.m_id)
                        }
                    }
                }
            }
            
            var updatedCount = 0
            var finishedByMinuteCount = 0
            var retryLaterCount = 0
            var noScoreCount = 0
            
            var index = 0
            for (match in matchesToCheckList) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        getMatchScoreSync(match.m_id)
                    }
                    
                    if (result != null && result.score1 >= 0 && result.score2 >= 0) {
                        matchTimeCache[match.m_id] = result.matchTime
                        failedAttemptsMap.remove(match.m_id)
                        
                        if (result.score1 != match.sh || result.score2 != match.sa) {
                            saveScoreToDb(match.m_id, result.score1, result.score2)
                            updatedCount++
                        }
                    } else {
                        val currentMinute = matchTimeCache[match.m_id] ?: 0
                        
                        if (currentMinute <= MIN_MATCH_MINUTE_FOR_RETRY) {
                            noScoreCount++
                            failedAttemptsMap.remove(match.m_id)
                        } else if (currentMinute < MATCH_FINISHED_MINUTE) {
                            retryLaterCount++
                            val now2 = System.currentTimeMillis()
                            var info = failedAttemptsMap[match.m_id]
                            if (info == null) {
                                info = FailedAttemptInfo(now2, 0, currentMinute)
                                failedAttemptsMap[match.m_id] = info
                            }
                            info.count = info.count + 1
                            info.lastMinute = currentMinute
                        } else {
                            val now2 = System.currentTimeMillis()
                            var info = failedAttemptsMap[match.m_id]
                            if (info == null) {
                                info = FailedAttemptInfo(now2, 0, currentMinute)
                                failedAttemptsMap[match.m_id] = info
                            }
                            info.count = info.count + 1
                            info.lastMinute = currentMinute
                            
                            val elapsedSinceFirstFail = now2 - info.firstFailTime
                            
                            if (info.count >= MAX_FAILED_ATTEMPTS && 
                                elapsedSinceFirstFail >= FAILED_TIMEOUT_MS) {
                                finishedByMinuteCount++
                                failedAttemptsMap.remove(match.m_id)
                            } else {
                                retryLaterCount++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка матча ${match.m_id}: ${e.message}")
                }
                
                if (index < matchesToCheckList.size - 1) {
                    delay(500)
                }
                index++
            }
            
            cleanupFailedAttempts()
            
            // Проверяем и обновляем статусы экспрессов
            val newlyFinishedExps = checkAndUpdateExpressStatuses(allExp, allData)
            
            val duration = System.currentTimeMillis() - startTime
            val parts = mutableListOf<String>()
            parts.add("Активных: ${activeExpList.size}")
            if (updatedCount > 0) {
                parts.add("✅$updatedCount")
            }
            if (finishedByMinuteCount > 0) {
                parts.add("🏁$finishedByMinuteCount")
            }
            if (retryLaterCount > 0) {
                parts.add("🔄$retryLaterCount")
            }
            if (newlyFinishedExps > 0) {
                parts.add("📋$newlyFinishedExps")
            }
            if (noScoreCount > 0) {
                parts.add("⏸$noScoreCount")
            }
            if (parts.size == 1) {
                parts.add("без изменений")
            }
            
            val msg = "📊 [${duration}мс] " + parts.joinToString(" | ")
            logMessage(msg)
            
        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка: ${e.message}", e)
            logMessage("❌ Ошибка: ${e.message}")
        }
    }

    private fun isExpressFinished(expId: Int, allData: List<DataEntity>): Boolean {
        val matches = mutableListOf<DataEntity>()
        for (data in allData) {
            if (data.id_exp == expId) {
                matches.add(data)
            }
        }
        
        if (matches.isEmpty()) {
            return false
        }
        
        // Проверяем, все ли матчи имеют счет
        var allHaveScore = true
        for (match in matches) {
            if (match.sh == 0 && match.sa == 0) {
                allHaveScore = false
                break
            }
        }
        if (allHaveScore) {
            return true
        }
        
        // Проверяем, есть ли проигрышный матч
        for (match in matches) {
            if (match.sh == 0 && match.sa == 0) {
                continue
            }
            
            var isWin = false
            when (match.type) {
                924 -> {
                    isWin = match.sh >= match.sa
                }
                927 -> {
                    isWin = match.sh + 1 > match.sa
                }
                928 -> {
                    isWin = match.sa + 1 >= match.sh
                }
                else -> {
                    isWin = match.sh >= match.sa
                }
            }
            
            if (!isWin) {
                return true
            }
        }
        
        return false
    }

    private fun cleanupFailedAttempts() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<Long>()
        for (entry in failedAttemptsMap.entries) {
            val key = entry.key
            val info = entry.value
            if (now - info.firstFailTime > FAILED_TIMEOUT_MS * 2) {
                toRemove.add(key)
            }
        }
        for (key in toRemove) {
            failedAttemptsMap.remove(key)
        }
    }

    private suspend fun checkAndUpdateExpressStatuses(
        allExp: List<ExpEntity>, 
        allData: List<DataEntity>
    ): Int {
        var finishedCount = 0
        
        for (exp in allExp) {
            // Пропускаем уже завершенные
            if (exp.sts_all != 1) {
                continue
            }
            
            if (!isExpressFinished(exp.id_exp, allData)) {
                continue
            }
            
            // Собираем матчи этого экспресса
            val expMatches = mutableListOf<DataEntity>()
            for (data in allData) {
                if (data.id_exp == exp.id_exp) {
                    expMatches.add(data)
                }
            }
            
            if (expMatches.isEmpty()) {
                continue
            }
            
            // Проверяем, все ли матчи имеют счет
            var allHaveScore = true
            for (match in expMatches) {
                if (match.sh == 0 && match.sa == 0) {
                    allHaveScore = false
                    break
                }
            }
            
            // Определяем результат
            var allWins = false
            if (allHaveScore) {
                allWins = true
                for (match in expMatches) {
                    var isWin = false
                    when (match.type) {
                        924 -> {
                            isWin = match.sh >= match.sa
                        }
                        927 -> {
                            isWin = match.sh + 1 > match.sa
                        }
                        928 -> {
                            isWin = match.sa + 1 >= match.sh
                        }
                        else -> {
                            isWin = match.sh >= match.sa
                        }
                    }
                    if (!isWin) {
                        allWins = false
                        break
                    }
                }
            }
            
            val newStatus: Int
            val statusText: String
            if (allWins) {
                newStatus = 2
                statusText = "ВЫИГРЫШ"
            } else {
                newStatus = -1
                statusText = "ПРОИГРЫШ"
            }
            
            Log.d(TAG, "Экспресс #${exp.id_exp} завершен: $statusText")
            
            // Обновляем статус в БД
            updateExpressStatusInDb(exp.id, newStatus)
            finishedCount++
        }
        
        return finishedCount
    }

    private suspend fun updateExpressStatusInDb(expId: Int, newStatus: Int) {
        withContext(Dispatchers.IO) {
            try {
                val allExp = database.expDao().getAllExp()
                val updatedExp = mutableListOf<ExpEntity>()
                for (exp in allExp) {
                    if (exp.id == expId) {
                        updatedExp.add(exp.copy(sts_all = newStatus))
                    } else {
                        updatedExp.add(exp)
                    }
                }
                database.expDao().deleteAll()
                database.expDao().insertAll(updatedExp)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления статуса: ${e.message}")
            }
        }
    }

    private suspend fun getMatchScoreSync(matchId: Long): MatchScoreResult? {
        return suspendCancellableCoroutine { continuation ->
            try {
                apiClient.getMatchScore(
                    matchId = matchId.toInt(),
                    onSuccess = { factors ->
                        if (factors != null) {
                            val result = MatchScoreResult(
                                score1 = factors.score1,
                                score2 = factors.score2,
                                matchTime = factors.matchTime
                            )
                            continuation.resume(result, null)
                        } else {
                            continuation.resume(null, null)
                        }
                    },
                    onError = { error ->
                        continuation.resume(null, null)
                    }
                )
            } catch (e: Exception) {
                continuation.resume(null, null)
            }
        }
    }

    private data class MatchScoreResult(
        val score1: Int,
        val score2: Int,
        val matchTime: Int
    )

    private suspend fun saveScoreToDb(matchId: Long, sh: Int, sa: Int) {
        withContext(Dispatchers.IO) {
            try {
                val allData = database.dataDao().getAllData()
                var updated = false
                
                val updatedData = mutableListOf<DataEntity>()
                for (data in allData) {
                    if (data.m_id == matchId) {
                        if (data.sh != sh || data.sa != sa) {
                            updated = true
                            updatedData.add(data.copy(sh = sh, sa = sa))
                        } else {
                            updatedData.add(data)
                        }
                    } else {
                        updatedData.add(data)
                    }
                }
                
                if (updated) {
                    database.dataDao().deleteAll()
                    database.dataDao().insertAll(updatedData)
                } else {Log.e(TAG,"some")}
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка сохранения $matchId: ${e.message}", e)
            }
        }
    }

    private fun parseDateTime(ct: String): LocalDateTime {
        val formatters = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
        )
        
        for (formatter in formatters) {
            try {
                return LocalDateTime.parse(ct.trim(), formatter)
            } catch (e: Exception) {
                continue
            }
        }
        
        throw IllegalArgumentException("Не удалось распарсить дату: '$ct'")
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
            )
            channel.description = "Фоновое обновление счетов активных матчей"
            channel.setShowBadge(false)
            channel.enableVibration(false)
            channel.enableLights(false)
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            )
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock != null) {
                val wl = wakeLock
                if (wl != null) {
                    if (wl.isHeld) {
                        wl.release()
                    }
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка освобождения WakeLock: ${e.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restartIntent = Intent(applicationContext, ScoreUpdateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }

    override fun onDestroy() {
        isRunning = false
        updateJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        matchTimeCache.clear()
        failedAttemptsMap.clear()
        logMessage("⏹ Сервис остановлен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}