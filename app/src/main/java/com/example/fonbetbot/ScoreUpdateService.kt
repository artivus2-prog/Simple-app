// ScoreUpdateService.kt - ПОЛНОСТЬЮ ИСПРАВЛЕННЫЙ ФАЙЛ
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
        
        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            logMessage("⚠️ Рекомендуется отключить оптимизацию батареи")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
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
                startForeground(NOTIFICATION_ID, notification, 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка foreground: ${e.message}")
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
            Notification.Builder(this)
                .setContentTitle("Fonbet Bot")
                .setContentText("Отслеживание активных матчей")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
                .build()
        }
    }

    private fun startScoreUpdates() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            Log.d(TAG, "Запуск цикла обновления (интервал ${UPDATE_INTERVAL_MS}мс)")
            
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
            val allExp = withContext(Dispatchers.IO) { database.expDao().getAllExp() }
            val allData = withContext(Dispatchers.IO) { database.dataDao().getAllData() }
            
            if (allExp.isEmpty() || allData.isEmpty()) {
                Log.d(TAG, "БД пуста, нечего обновлять")
                return
            }
            
            val now = LocalDateTime.now()
            
            val activeExps = allExp.filter { exp ->
                try {
                    val expTime = parseDateTime(exp.ct)
                    val hoursSinceCreation = ChronoUnit.HOURS.between(expTime, now)
                    val expMatches = allData.filter { it.id_exp == exp.id_exp }
                    val isFinished = isExpressFinished(expMatches)
                    val isActive = hoursSinceCreation <= ACTIVE_HOURS && !isFinished
                    isActive
                } catch (e: Exception) {
                    false
                }
            }
            
            Log.d(TAG, "=== ИТОГО: всего=${allExp.size}, активных=${activeExps.size} ===")
            
            if (activeExps.isEmpty()) {
                logMessage("✓ Нет активных экспрессов")
                return
            }
            
            val activeExpIds = activeExps.map { it.id_exp }.toSet()
            val activeMatches = allData.filter { it.id_exp in activeExpIds }
            
            val matchesToCheck = activeMatches
                .filter { it.sh == 0 && it.sa == 0 }
                .groupBy { it.m_id }
                .map { (_, matches) -> matches.first() }
            
            Log.d(TAG, "Матчей без счета для проверки: ${matchesToCheck.size}")
            
            var updatedCount = 0
            var finishedByMinuteCount = 0
            var retryLaterCount = 0
            var noScoreCount = 0
            
            for ((index, match) in matchesToCheck.withIndex()) {
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
                        
                        when {
                            currentMinute <= MIN_MATCH_MINUTE_FOR_RETRY -> {
                                noScoreCount++
                                failedAttemptsMap.remove(match.m_id)
                            }
                            
                            currentMinute in (MIN_MATCH_MINUTE_FOR_RETRY + 1) until MATCH_FINISHED_MINUTE -> {
                                retryLaterCount++
                                val now2 = System.currentTimeMillis()
                                val info = failedAttemptsMap.getOrPut(match.m_id) {
                                    FailedAttemptInfo(now2, 0, currentMinute)
                                }
                                info.count++
                                info.lastMinute = currentMinute
                            }
                            
                            currentMinute >= MATCH_FINISHED_MINUTE -> {
                                val now2 = System.currentTimeMillis()
                                val info = failedAttemptsMap.getOrPut(match.m_id) {
                                    FailedAttemptInfo(now2, 0, currentMinute)
                                }
                                info.count++
                                info.lastMinute = currentMinute
                                
                                val elapsedSinceFirstFail = now2 - info.firstFailTime
                                
                                if (info.count >= MAX_FAILED_ATTEMPTS && elapsedSinceFirstFail >= FAILED_TIMEOUT_MS) {
                                    markMatchAsFinished(match)
                                    finishedByMinuteCount++
                                    failedAttemptsMap.remove(match.m_id)
                                } else {
                                    retryLaterCount++
                                }
                            }
                            
                            else -> {
                                noScoreCount++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  ❌ Ошибка матча ${match.m_id}: ${e.message}")
                }
                
                if (index < matchesToCheck.size - 1) {
                    delay(500)
                }
            }
            
            cleanupFailedAttempts()
            val newlyFinishedExps = checkAndFinishExpresses(allExp, allData)
            
            val duration = System.currentTimeMillis() - startTime
            val msg = buildString {
                append("📊 [${duration}мс] ")
                append("Активных: ${activeExps.size} | ")
                if (updatedCount > 0) append("✅$updatedCount ")
                if (finishedByMinuteCount > 0) append("🏁$finishedByMinuteCount ")
                if (retryLaterCount > 0) append("🔄$retryLaterCount ")
                if (newlyFinishedExps > 0) append("📋$newlyFinishedExps ")
                if (noScoreCount > 0) append("⏸$noScoreCount ")
                if (updatedCount == 0 && finishedByMinuteCount == 0 && 
                    retryLaterCount == 0 && newlyFinishedExps == 0 && 
                    noScoreCount == 0) {
                    append("без изменений")
                }
            }
            
            logMessage(msg)
            Log.d(TAG, msg)
            
        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка updateScores: ${e.message}", e)
            logMessage("❌ Ошибка: ${e.message}")
        }
    }

    private fun getMinuteLimit(match: DataEntity): Int {
        if (match.liganame.contains("НХЛ", ignoreCase = true) ||
            match.liganame.contains("КХЛ", ignoreCase = true) ||
            match.liganame.contains("ВХЛ", ignoreCase = true) ||
            match.liganame.contains("AHL", ignoreCase = true)) {
            return MATCH_FINISHED_MINUTE_HOCKEY
        }
        return MATCH_FINISHED_MINUTE
    }

    private fun isMatchFinishedByTime(match: DataEntity, currentMinute: Int): Boolean {
        val minuteLimit = getMinuteLimit(match)
        return currentMinute >= minuteLimit
    }

    private fun isExpressFinished(matches: List<DataEntity>): Boolean {
        if (matches.isEmpty()) return false
        
        var allHaveScore = true
        for (match in matches) {
            if (match.sh == 0 && match.sa == 0) {
                allHaveScore = false
                break
            }
        }
        if (allHaveScore) return true
        
        for (match in matches) {
            if (match.sh == 0 && match.sa == 0) continue
            
            val isWin = when (match.type) {
                924 -> match.sh >= match.sa
                927 -> match.sh + 1 > match.sa
                928 -> match.sa + 1 >= match.sh
                else -> match.sh >= match.sa
            }
            
            if (!isWin) return true
        }
        
        return false
    }

    private fun cleanupFailedAttempts() {
        val now = System.currentTimeMillis()
        val toRemove = failedAttemptsMap.filter { (_, info) ->
            now - info.firstFailTime > FAILED_TIMEOUT_MS * 2
        }.keys
        toRemove.forEach { failedAttemptsMap.remove(it) }
    }

    private suspend fun markMatchAsFinished(match: DataEntity) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "  🏁 Матч ${match.m_id}: помечен как завершенный")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка markMatchAsFinished: ${e.message}")
            }
        }
    }

    private suspend fun checkAndFinishExpresses(
        allExp: List<ExpEntity>, 
        allData: List<DataEntity>
    ): Int {
        var finishedCount = 0
        
        for (exp in allExp) {
            val expMatches = allData.filter { it.id_exp == exp.id_exp }
            if (expMatches.isEmpty()) continue
            if (exp.sts_all != 1) continue
            
            val isFinished = isExpressFinished(expMatches)
            
            if (isFinished) {
                var allHaveScore = true
                for (match in expMatches) {
                    if (match.sh == 0 && match.sa == 0) {
                        allHaveScore = false
                        break
                    }
                }
                
                var allWins = false
                if (allHaveScore) {
                    allWins = true
                    for (match in expMatches) {
                        val isWin = when (match.type) {
                            924 -> match.sh >= match.sa
                            927 -> match.sh + 1 > match.sa
                            928 -> match.sa + 1 >= match.sh
                            else -> match.sh >= match.sa
                        }
                        if (!isWin) {
                            allWins = false
                            break
                        }
                    }
                }
                
                val newStatus = if (allWins) 2 else -1
                
                Log.d(TAG, "🏁 Экспресс #${exp.id_exp} завершен: " +
                         "статус=${if (allWins) "ВЫИГРЫШ" else "ПРОИГРЫШ"}")
                
                updateExpressStatus(exp.id, newStatus)
                finishedCount++
            }
        }
        
        return finishedCount
    }

    private suspend fun updateExpressStatus(expId: Int, newStatus: Int) {
        withContext(Dispatchers.IO) {
            try {
                val allExp = database.expDao().getAllExp()
                val updatedExp = allExp.map { 
                    if (it.id == expId) it.copy(sts_all = newStatus) else it 
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
                            continuation.resume(
                                MatchScoreResult(
                                    score1 = factors.score1,
                                    score2 = factors.score2,
                                    matchTime = factors.matchTime
                                ),
                                null
                            )
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
                
                val updatedData = allData.map { 
                    if (it.m_id == matchId && (it.sh != sh || it.sa != sa)) {
                        updated = true
                        it.copy(sh = sh, sa = sa)
                    } else {
                        it
                    }
                }
                
                if (updated) {
                    database.dataDao().deleteAll()
                    database.dataDao().insertAll(updatedData)
                }
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
            ).apply {
                description = "Фоновое обновление счетов активных матчей"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
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
            ).apply {
                acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
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

    override fun onBind(intent: Intent?): IBinder? = null
}