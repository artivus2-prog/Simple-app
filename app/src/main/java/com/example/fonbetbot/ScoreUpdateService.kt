// ScoreUpdateService.kt — ПОЛНАЯ ВЕРСИЯ С ЛОГИКОЙ ЗАВЕРШЕНИЯ МАТЧЕЙ И ЭКСПРЕССОВ
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
        var onScoreUpdated: ((Long, Int, Int, Int) -> Unit)? = null // mId, sh, sa, minute
        
        private const val ACTIVE_HOURS = 2L
        private const val MATCH_FINISHED_MINUTE = 90
        private const val MATCH_FINISHED_MINUTE_HOCKEY = 60
        private const val STALE_DATA_TIMEOUT_MINUTES = 5L // Если данные не поступают 5 минут
    }

    // Отслеживание состояния матчей
    private data class MatchTrackingInfo(
        val matchId: Long,
        var lastSh: Int = -1,
        var lastSa: Int = -1,
        var lastMinute: Int = -1,
        var lastUpdateTime: LocalDateTime = LocalDateTime.now(),
        var isFinished: Boolean = false,
        var dataStopped: Boolean = false,      // Данные перестали поступать
        var dataStoppedTime: LocalDateTime? = null // Когда данные остановились
    )
    
    private val matchTracker = mutableMapOf<Long, MatchTrackingInfo>()
    
    // Карта для хранения текущей минуты матча (для UI)
    private val matchMinutes = mutableMapOf<Long, Int>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        database = AppDatabase.getDatabase(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startForeground()
            startScoreUpdates()
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
                delay(30_000) // Проверка каждые 30 секунд
                updateScores()
            }
        }
    }

    private suspend fun updateScores() {
        try {
            val allData = withContext(Dispatchers.IO) { database.dataDao().getAllData() }
            val allExp = withContext(Dispatchers.IO) { database.expDao().getAllExp() }
            if (allExp.isEmpty() || allData.isEmpty()) return

            val now = LocalDateTime.now()
            
            // Находим активные экспрессы
            val activeExps = mutableListOf<ExpEntity>()
            for (exp in allExp) {
                if (exp.sts_all != 1) continue
                try {
                    val expTime = parseDateTime(exp.ct)
                    val hoursSinceCreation = ChronoUnit.HOURS.between(expTime, now)
                    if (hoursSinceCreation <= ACTIVE_HOURS) {
                        activeExps.add(exp)
                    }
                } catch (e: Exception) {
                    Log.w("ScoreUpdate", "Ошибка парсинга ct для #${exp.id_exp}: ${exp.ct}")
                }
            }

            if (activeExps.isEmpty()) {
                onLogUpdate?.invoke("✓ Нет активных экспрессов")
                return
            }

            // Собираем ВСЕ матчи активных экспрессов
            val matchesToCheck = mutableListOf<DataEntity>()
            val seenIds = mutableSetOf<Long>()
            for (exp in activeExps) {
                for (data in allData) {
                    if (data.id_exp == exp.id_exp && data.m_id !in seenIds) {
                        matchesToCheck.add(data)
                        seenIds.add(data.m_id)
                    }
                }
            }

            var updatedCount = 0
            var minute90Count = 0

            for ((index, match) in matchesToCheck.withIndex()) {
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        apiClient.getMatchScore(
                            matchId = match.m_id.toInt(),
                            onSuccess = { factors ->
                                val tracking = matchTracker.getOrPut(match.m_id) {
                                    MatchTrackingInfo(matchId = match.m_id)
                                }
                                
                                if (factors != null && factors.score1 >= 0 && factors.score2 >= 0) {
                                    // Данные поступили — сбрасываем флаг остановки
                                    if (tracking.dataStopped) {
                                        Log.d("ScoreUpdate", "🔄 Матч ${match.m_id}: данные возобновились после остановки")
                                        tracking.dataStopped = false
                                        tracking.dataStoppedTime = null
                                    }
                                    
                                    // Обновляем счёт в БД
                                    if (match.sh != factors.score1 || match.sa != factors.score2) {
                                        saveScoreToDb(match.m_id, factors.score1, factors.score2)
                                        updatedCount++
                                    }
                                    
                                    // Обновляем минуту в оперативной памяти
                                    matchMinutes[match.m_id] = factors.matchTime
                                    
                                    // Обновляем tracking
                                    tracking.lastSh = factors.score1
                                    tracking.lastSa = factors.score2
                                    tracking.lastMinute = factors.matchTime
                                    tracking.lastUpdateTime = LocalDateTime.now()
                                    
                                    // Оповещаем UI
                                    onScoreUpdated?.invoke(
                                        match.m_id,
                                        factors.score1,
                                        factors.score2,
                                        factors.matchTime
                                    )
                                    
                                    // Считаем матчи с минутой >= лимита
                                    val minuteLimit = getMinuteLimit(match.liganame)
                                    if (factors.matchTime >= minuteLimit) {
                                        minute90Count++
                                        Log.d("ScoreUpdate", "⏰ Матч ${match.m_id}: минута ${factors.matchTime}' >= ${minuteLimit}'")
                                    }
                                    
                                } else {
                                    // Данные не поступили (API вернул null или счёт < 0)
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
                                continuation.resume(Unit) {}
                            }
                        )
                    }
                    
                    if (index < matchesToCheck.size - 1) {
                        delay(300) // Задержка между запросами к API
                    }
                } catch (e: Exception) {
                    Log.w("ScoreUpdate", "Ошибка m_id=${match.m_id}: ${e.message}")
                }
            }

            // Проверяем матчи с остановленными данными
            checkStaleMatches()

            // Проверяем и обновляем статусы завершенных экспрессов
            val finishedCount = checkAndFinishExpresses(allExp, allData)

            val msg = "📊 Активных: ${activeExps.size} | ✅$updatedCount | ⏰$minute90Count | 🏁$finishedCount"
            onLogUpdate?.invoke(msg)

        } catch (e: Exception) {
            Log.e("ScoreUpdate", "Ошибка в updateScores: ${e.message}")
        }
    }

    /**
     * Обработка отсутствия данных от API
     */
    private fun handleNoData(match: DataEntity, tracking: MatchTrackingInfo) {
        val now = LocalDateTime.now()
        
        when {
            // Минута матча от 1 до 89 — матч ещё идёт, данные временно не пришли
            tracking.lastMinute in 1..89 -> {
                if (!tracking.dataStopped) {
                    tracking.dataStopped = true
                    tracking.dataStoppedTime = now
                    Log.d("ScoreUpdate", "⚠️ Матч ${match.m_id}: данные остановились на ${tracking.lastMinute}'")
                }
            }
            
            // Минута матча >= 90 — проверяем, как долго нет данных
            tracking.lastMinute >= MATCH_FINISHED_MINUTE -> {
                if (!tracking.dataStopped) {
                    tracking.dataStopped = true
                    tracking.dataStoppedTime = now
                    Log.d("ScoreUpdate", "⚠️ Матч ${match.m_id}: >=${MATCH_FINISHED_MINUTE}', данные остановились")
                }
                
                // Если данные не поступают более 5 минут — матч завершён
                val stoppedDuration = ChronoUnit.MINUTES.between(tracking.dataStoppedTime, now)
                if (stoppedDuration >= STALE_DATA_TIMEOUT_MINUTES && !tracking.isFinished) {
                    tracking.isFinished = true
                    Log.d("ScoreUpdate", "🏁 Матч ${match.m_id}: завершён (нет данных ${stoppedDuration} мин после ${tracking.lastMinute}')")
                }
            }
            
            // Минута == -1 (никогда не было данных) — проверяем время с последней попытки
            tracking.lastMinute == -1 -> {
                val sinceLastUpdate = ChronoUnit.MINUTES.between(tracking.lastUpdateTime, now)
                if (sinceLastUpdate >= STALE_DATA_TIMEOUT_MINUTES && !tracking.isFinished) {
                    tracking.isFinished = true
                    Log.d("ScoreUpdate", "🏁 Матч ${match.m_id}: завершён (нет данных ${sinceLastUpdate} мин)")
                }
            }
        }
    }

    /**
     * Проверка матчей с остановленными данными
     */
    private fun checkStaleMatches() {
        val now = LocalDateTime.now()
        
        for ((matchId, tracking) in matchTracker) {
            if (tracking.isFinished) continue
            if (!tracking.dataStopped) continue
            
            tracking.dataStoppedTime?.let { stoppedTime ->
                val stoppedDuration = ChronoUnit.MINUTES.between(stoppedTime, now)
                
                // Если данные остановлены более 5 минут и минута >= 90 — матч завершён
                if (stoppedDuration >= STALE_DATA_TIMEOUT_MINUTES) {
                    if (tracking.lastMinute >= MATCH_FINISHED_MINUTE || tracking.lastMinute == -1) {
                        tracking.isFinished = true
                        Log.d("ScoreUpdate", "🏁 Матч $matchId: завершён по таймауту (остановка ${stoppedDuration} мин, минута ${tracking.lastMinute}')")
                    }
                }
            }
        }
    }

    /**
     * Определение лимита минут для вида спорта
     */
    private fun getMinuteLimit(liganame: String): Int {
        return if (liganame.contains("НХЛ", ignoreCase = true) ||
            liganame.contains("КХЛ", ignoreCase = true) ||
            liganame.contains("ВХЛ", ignoreCase = true) ||
            liganame.contains("AHL", ignoreCase = true)) {
            MATCH_FINISHED_MINUTE_HOCKEY
        } else {
            MATCH_FINISHED_MINUTE
        }
    }

    /**
     * Проверка и обновление статусов завершённых экспрессов
     */
    private fun checkAndFinishExpresses(allExp: List<ExpEntity>, allData: List<DataEntity>): Int {
        var finishedCount = 0
        
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            try {
                var needUpdate = false
                val updatedExp = allExp.map { exp ->
                    if (exp.sts_all != 1) {
                        // Уже завершён, не трогаем
                        exp
                    } else {
                        val matches = allData.filter { it.id_exp == exp.id_exp }
                        if (matches.isEmpty()) return@map exp
                        
                        // Проверяем завершение каждого матча
                        var allMatchesFinished = true
                        var hasLosingMatch = false
                        
                        for (match in matches) {
                            val tracking = matchTracker[match.m_id]
                            
                            // Определяем, завершён ли матч
                            val isMatchFinished = when {
                                tracking == null -> false // Нет данных — считаем незавершённым
                                tracking.isFinished -> true // Явно помечен как завершённый
                                tracking.lastMinute in 1..(getMinuteLimit(match.liganame) - 1) -> false // Матч ещё идёт
                                tracking.lastMinute >= getMinuteLimit(match.liganame) && !tracking.dataStopped -> false // >лимита, но данные идут (может быть 110')
                                tracking.dataStopped -> {
                                    // Данные остановлены, проверяем таймаут
                                    val stoppedDuration = tracking.dataStoppedTime?.let {
                                        ChronoUnit.MINUTES.between(it, LocalDateTime.now())
                                    } ?: 0
                                    stoppedDuration >= STALE_DATA_TIMEOUT_MINUTES
                                }
                                else -> false
                            }
                            
                            if (!isMatchFinished) {
                                allMatchesFinished = false
                            }
                            
                            // Проверяем результат матча (если есть счёт)
                            if (match.sh > 0 || match.sa > 0) {
                                val isWin = when (match.type) {
                                    924 -> match.sh >= match.sa
                                    927 -> match.sh + 1 > match.sa
                                    928 -> match.sa + 1 >= match.sh
                                    else -> match.sh >= match.sa
                                }
                                if (!isWin) {
                                    hasLosingMatch = true
                                }
                            }
                        }
                        
                        // Экспресс завершён если:
                        // - Все матчи завершены ИЛИ есть проигрышный матч
                        if (allMatchesFinished || hasLosingMatch) {
                            needUpdate = true
                            finishedCount++
                            
                            // Определяем результат
                            val allWins = if (hasLosingMatch) {
                                false // Есть проигрыш — экспресс проиграл
                            } else {
                                // Все завершены — проверяем все ли выиграли
                                matches.all { match ->
                                    when (match.type) {
                                        924 -> match.sh >= match.sa
                                        927 -> match.sh + 1 > match.sa
                                        928 -> match.sa + 1 >= match.sh
                                        else -> match.sh >= match.sa
                                    }
                                }
                            }
                            
                            val newStatus = if (allWins) 2 else -1
                            val statusText = if (allWins) "ВЫИГРЫШ" else "ПРОИГРЫШ"
                            
                            Log.d("ScoreUpdate", "🏁 Экспресс #${exp.id_exp}: $statusText (завершённых матчей: ${matches.count { 
                                val t = matchTracker[it.m_id]
                                t?.isFinished == true || (it.sh > 0 || it.sa > 0)
                            }}/${matches.size})")
                            
                            exp.copy(sts_all = newStatus)
                        } else {
                            exp
                        }
                    }
                }
                
                if (needUpdate) {
                    database.expDao().deleteAll()
                    database.expDao().insertAll(updatedExp)
                    Log.d("ScoreUpdate", "✅ Обновлено статусов экспрессов: $finishedCount")
                } else {Log.d("ScoresUpdate","some")}
            } catch (e: Exception) {
                Log.e("ScoreUpdate", "Ошибка обновления статусов: ${e.message}")
            }
        }
        
        return finishedCount
    }

    /**
     * Сохранение счёта в БД
     */
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
                Log.e("ScoreUpdate", "Ошибка сохранения счёта: ${e.message}")
            }
        }
    }

    /**
     * Парсинг даты из ct
     */
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

    /**
     * Создание канала уведомлений
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Обновление счетов",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}