// ScoreUpdateService.kt — ФИНАЛЬНАЯ ВЕРСИЯ
// Новые матчи из API — активны сразу (curtime может быть 0)
// Импорт из Excel — активны если ct не старше 2 часов
package com.example.fonbetbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class ScoreUpdateService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val apiClient = ApiClient()
    private lateinit var database: AppDatabase

    companion object {
        const val CHANNEL_ID = "ScoreUpdateChannel"
        const val NOTIFICATION_ID = 100
        var isRunning = false
        var onLogUpdate: ((String) -> Unit)? = null
        var onScoreUpdated: ((Long, Int, Int, Int) -> Unit)? = null
        
        private const val ACTIVE_HOURS = 2L  // Для импортированных экспрессов
        private const val STALE_DATA_TIMEOUT_MINUTES = 5L
        private const val MATCH_FINISHED_MINUTE = 90
        private const val MATCH_FINISHED_MINUTE_HOCKEY = 60
    }

    private data class MatchTrackingInfo(
        val matchId: Long,
        var lastSh: Int = -1,
        var lastSa: Int = -1,
        var lastMinute: Int = -1,
        var lastUpdateTime: LocalDateTime = LocalDateTime.now(),
        var isFinished: Boolean = false,
        var dataStopped: Boolean = false,
        var dataStoppedTime: LocalDateTime? = null
    )
    
    private val matchTracker = mutableMapOf<Long, MatchTrackingInfo>()
    private val matchMinutes = mutableMapOf<Long, Int>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        database = AppDatabase.getDatabase(this)
        Log.d("ScoreUpdate", "🟢 Сервис создан")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startForeground()
            startScoreUpdates()
            Log.d("ScoreUpdate", "🟢 Сервис запущен")
        }
        return START_STICKY
    }

    private fun startForeground() {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Fonbet Bot")
            .setContentText("Отслеживание активных матчей...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startScoreUpdates() {
        serviceScope.launch {
            while (isActive) {
                delay(30_000)
                updateScores()
            }
        }
    }

    private fun isMatchWin(sh: Int, sa: Int, type: Int): Boolean {
        return when (type) {
            924 -> sh >= sa
            927 -> (sh + 1.5) > sa
            928 -> (sa + 1.5) >= sh
            else -> sh >= sa
        }
    }

    private fun parseDateTime(ct: String): LocalDateTime {
        val trimmed = ct.trim()
        val formatters = listOf(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"),
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
        )
        for (formatter in formatters) {
            try {
                return LocalDateTime.parse(trimmed, formatter)
            } catch (_: Exception) { }
        }
        Log.e("ScoreUpdate", "❌ Не удалось распарсить ct: '$ct'")
        return LocalDateTime.of(1970, 1, 1, 0, 0)
    }

    private suspend fun updateScores() {
        try {
            val allData = withContext(Dispatchers.IO) { database.dataDao().getAllData() }
            val allExp = withContext(Dispatchers.IO) { database.expDao().getAllExp() }
            if (allExp.isEmpty() || allData.isEmpty()) return

            val now = LocalDateTime.now()
            
            val activeMatches = mutableListOf<DataEntity>()
            val activeExpIds = mutableSetOf<Int>()
            
            for (exp in allExp) {
                val matches = allData.filter { it.id_exp == exp.id_exp }
                if (matches.isEmpty()) continue
                
                // Проверяем, есть ли проигрышный матч
                var hasLosingMatch = false
                for (match in matches) {
                    if (match.sh > 0 || match.sa > 0) {
                        if (!isMatchWin(match.sh, match.sa, match.type)) {
                            hasLosingMatch = true
                        }
                    }
                }
                
                // Пропускаем проигрышные экспрессы
                if (hasLosingMatch) {
                    if (exp.sts_all != -1) {
                        updateExpStatus(exp, -1)
                    }
                    continue
                }
                
                // Проверяем, все ли матчи имеют счёт (все выиграли)
                val allHaveScore = matches.all { it.sh > 0 || it.sa > 0 }
                if (allHaveScore) {
                    val allWins = matches.all { isMatchWin(it.sh, it.sa, it.type) }
                    val newStatus = if (allWins) 2 else -1
                    if (exp.sts_all != newStatus) {
                        updateExpStatus(exp, newStatus)
                    }
                    continue
                }
                
                // Экспресс не завершён — проверяем, нужно ли его отслеживать
                try {
                    val expTime = parseDateTime(exp.ct)
                    val hoursSinceCreation = ChronoUnit.HOURS.between(expTime, now)
                    
                    // Экспресс активен если:
                    // 1. Создан менее 2 часов назад (из Excel)
                    // 2. Или есть матчи без счёта (новые из API или ещё идут)
                    
                    if (hoursSinceCreation <= ACTIVE_HOURS) {
                        // Экспресс свежий — отслеживаем все его матчи без счёта
                        activeExpIds.add(exp.id_exp)
                        for (match in matches) {
                            if (match.sh == 0 && match.sa == 0 && match.m_id !in matchTracker.map { it.key }) {
                                activeMatches.add(match)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ScoreUpdate", "Ошибка парсинга ct для #${exp.id_exp}: ${exp.ct}")
                }
            }
            
            // Добавляем матчи из трекера, у которых данные остановлены, но таймаут не истёк
            for ((matchId, tracking) in matchTracker) {
                if (!tracking.isFinished && tracking.dataStopped) {
                    val stoppedDuration = tracking.dataStoppedTime?.let {
                        ChronoUnit.MINUTES.between(it, now)
                    } ?: 0
                    
                    if (stoppedDuration < STALE_DATA_TIMEOUT_MINUTES) {
                        val match = allData.find { it.m_id == matchId }
                        if (match != null && match !in activeMatches) {
                            activeMatches.add(match)
                        }
                    }
                }
            }
            
            val uniqueMatches = activeMatches.distinctBy { it.m_id }
            
            if (uniqueMatches.isEmpty()) {
                checkStaleMatches()
                val finishedCount = checkAndFinishExpresses(allExp, allData)
                if (finishedCount > 0) {
                    onLogUpdate?.invoke("🏁 Завершено экспрессов: $finishedCount")
                } else {
                    onLogUpdate?.invoke("✓ Нет активных матчей")
                }
                return
            }

            onLogUpdate?.invoke("📊 Активных матчей: ${uniqueMatches.size}")

            var updatedCount = 0
            var errorCount = 0

            for ((index, match) in uniqueMatches.withIndex()) {
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        apiClient.getMatchScore(
                            matchId = match.m_id.toInt(),
                            onSuccess = { factors ->
                                val tracking = matchTracker.getOrPut(match.m_id) {
                                    MatchTrackingInfo(matchId = match.m_id)
                                }
                                
                                if (factors != null && factors.score1 >= 0 && factors.score2 >= 0) {
                                    if (tracking.dataStopped) {
                                        Log.d("ScoreUpdate", "🔄 Матч ${match.m_id}: данные возобновились")
                                        tracking.dataStopped = false
                                        tracking.dataStoppedTime = null
                                    }
                                    
                                    if (match.sh != factors.score1 || match.sa != factors.score2) {
                                        saveScoreToDb(match.m_id, factors.score1, factors.score2)
                                        updatedCount++
                                    }
                                    
                                    matchMinutes[match.m_id] = factors.matchTime
                                    tracking.lastSh = factors.score1
                                    tracking.lastSa = factors.score2
                                    tracking.lastMinute = factors.matchTime
                                    tracking.lastUpdateTime = LocalDateTime.now()
                                    
                                    onScoreUpdated?.invoke(
                                        match.m_id,
                                        factors.score1,
                                        factors.score2,
                                        factors.matchTime
                                    )
                                    
                                    Log.d("ScoreUpdate", "✅ Матч ${match.m_id}: ${factors.score1}:${factors.score2} (${factors.matchTime}')")
                                } else {
                                    handleNoData(match, tracking)
                                }
                                
                                continuation.resume(Unit) {}
                            },
                            onError = { error ->
                                Log.d("ScoreUpdate", "❌ Матч ${match.m_id}: ошибка API — $error")
                                val tracking = matchTracker.getOrPut(match.m_id) {
                                    MatchTrackingInfo(matchId = match.m_id)
                                }
                                handleNoData(match, tracking)
                                errorCount++
                                continuation.resume(Unit) {}
                            }
                        )
                    }
                    
                    if (index < uniqueMatches.size - 1) {
                        delay(300)
                    }
                } catch (e: Exception) {
                    Log.w("ScoreUpdate", "Ошибка m_id=${match.m_id}: ${e.message}")
                }
            }

            checkStaleMatches()
            val refreshedData = withContext(Dispatchers.IO) { database.dataDao().getAllData() }
            val finishedCount = checkAndFinishExpresses(allExp, refreshedData)

            val msg = "📊 Матчей: ${uniqueMatches.size} | ✅$updatedCount | ❌$errorCount | 🏁$finishedCount"
            onLogUpdate?.invoke(msg)

        } catch (e: Exception) {
            Log.e("ScoreUpdate", "Ошибка в updateScores: ${e.message}", e)
        }
    }

    private fun updateExpStatus(exp: ExpEntity, newStatus: Int) {
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            try {
                val allExp = database.expDao().getAllExp()
                val updated = allExp.map { if (it.id == exp.id) it.copy(sts_all = newStatus) else it }
                database.expDao().deleteAll()
                database.expDao().insertAll(updated)
            } catch (e: Exception) {
                Log.e("ScoreUpdate", "Ошибка обновления статуса экспресса: ${e.message}")
            }
        }
    }

    private fun handleNoData(match: DataEntity, tracking: MatchTrackingInfo) {
        val now = LocalDateTime.now()
        
        if (!tracking.dataStopped) {
            tracking.dataStopped = true
            tracking.dataStoppedTime = now
            Log.d("ScoreUpdate", "⚠️ Матч ${match.m_id}: данные остановились на ${tracking.lastMinute}'")
        }
        
        val stoppedDuration = ChronoUnit.MINUTES.between(tracking.dataStoppedTime, now)
        if (stoppedDuration >= STALE_DATA_TIMEOUT_MINUTES && !tracking.isFinished) {
            tracking.isFinished = true
            Log.d("ScoreUpdate", "🏁 Матч ${match.m_id}: завершён (нет данных ${stoppedDuration} мин)")
        }
    }

    private fun checkStaleMatches() {
        val now = LocalDateTime.now()
        
        for ((matchId, tracking) in matchTracker) {
            if (tracking.isFinished) continue
            if (!tracking.dataStopped) continue
            
            tracking.dataStoppedTime?.let { stoppedTime ->
                val stoppedDuration = ChronoUnit.MINUTES.between(stoppedTime, now)
                if (stoppedDuration >= STALE_DATA_TIMEOUT_MINUTES) {
                    tracking.isFinished = true
                    Log.d("ScoreUpdate", "🏁 Матч $matchId: завершён по таймауту (${stoppedDuration} мин)")
                }
            }
        }
        
        // Завершаем экспрессы старше 24 часов
        val allExp = kotlinx.coroutines.runBlocking(Dispatchers.IO) { database.expDao().getAllExp() }
        for (exp in allExp) {
            if (exp.sts_all != 1) continue
            try {
                val expTime = parseDateTime(exp.ct)
                val hoursSinceCreation = ChronoUnit.HOURS.between(expTime, now)
                if (hoursSinceCreation > 24) {
                    Log.d("ScoreUpdate", "🏁 Экспресс #${exp.id_exp}: завершён по возрасту (${hoursSinceCreation}ч)")
                    updateExpStatus(exp, -1)
                }
            } catch (e: Exception) { }
        }
    }

    private fun checkAndFinishExpresses(allExp: List<ExpEntity>, allData: List<DataEntity>): Int {
        var finishedCount = 0
        
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            try {
                var needUpdate = false
                val now = LocalDateTime.now()
                
                val updatedExp = allExp.map { exp ->
                    if (exp.sts_all != 1) return@map exp
                    
                    val matches = allData.filter { it.id_exp == exp.id_exp }
                    if (matches.isEmpty()) return@map exp
                    
                    // Проверяем проигрышные матчи
                    var hasLosingMatch = false
                    var allHaveScore = true
                    
                    for (match in matches) {
                        if (match.sh > 0 || match.sa > 0) {
                            if (!isMatchWin(match.sh, match.sa, match.type)) {
                                hasLosingMatch = true
                            }
                        } else {
                            allHaveScore = false
                        }
                    }
                    
                    when {
                        hasLosingMatch -> {
                            needUpdate = true
                            finishedCount++
                            Log.d("ScoreUpdate", "🏁 Экспресс #${exp.id_exp}: ПРОИГРЫШ")
                            exp.copy(sts_all = -1)
                        }
                        allHaveScore -> {
                            needUpdate = true
                            finishedCount++
                            val allWins = matches.all { isMatchWin(it.sh, it.sa, it.type) }
                            val newStatus = if (allWins) 2 else -1
                            Log.d("ScoreUpdate", "🏁 Экспресс #${exp.id_exp}: ${if (allWins) "ВЫИГРЫШ" else "ПРОИГРЫШ"}")
                            exp.copy(sts_all = newStatus)
                        }
                        else -> exp
                    }
                }
                
                if (needUpdate) {
                    database.expDao().deleteAll()
                    database.expDao().insertAll(updatedExp)
                    Log.d("ScoreUpdate", "✅ Обновлено статусов в БД: $finishedCount")
                } else { Log.d("ScoreUpdate","some")}
            } catch (e: Exception) {
                Log.e("ScoreUpdate", "Ошибка обновления статусов: ${e.message}", e)
            }
        }
        
        return finishedCount
    }

    private fun saveScoreToDb(matchId: Long, sh: Int, sa: Int) {
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            try {
                val allData = database.dataDao().getAllData()
                val updated = allData.map { 
                    if (it.m_id == matchId) it.copy(sh = sh, sa = sa) else it 
                }
                database.dataDao().deleteAll()
                database.dataDao().insertAll(updated)
            } catch (e: Exception) {
                Log.e("ScoreUpdate", "Ошибка сохранения счёта для $matchId: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Обновление счетов",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Канал для фонового обновления счетов матчей"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        Log.d("ScoreUpdate", "🔴 Сервис остановлен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}