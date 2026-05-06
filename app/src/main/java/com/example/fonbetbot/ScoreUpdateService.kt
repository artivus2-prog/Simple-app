// ScoreUpdateService.kt — ПОЛНАЯ ФИНАЛЬНАЯ ВЕРСИЯ
// Активность ТОЛЬКО по времени создания (< 2 часов)
// sts_all полностью игнорируется при определении активности
// Счёт запрашивается для ВСЕХ матчей активных экспрессов
// Формулы с +1.5 для форы
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
        private const val STALE_DATA_TIMEOUT_MINUTES = 5L
    }

    // Отслеживание состояния матчей
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

    /**
     * Проверка выигрыша матча с правильными формулами (+1.5 для форы)
     */
    private fun isMatchWin(sh: Int, sa: Int, type: Int): Boolean {
        return when (type) {
            924 -> sh >= sa              // 1X — первая команда не проиграла
            927 -> (sh + 1.5) > sa       // Ф1(+1.5) — первая команда с форой +1.5
            928 -> (sa + 1.5) >= sh      // Ф2(+1.5) — вторая команда с форой +1.5
            else -> sh >= sa
        }
    }

    private suspend fun updateScores() {
        try {
            val allData = withContext(Dispatchers.IO) { database.dataDao().getAllData() }
            val allExp = withContext(Dispatchers.IO) { database.expDao().getAllExp() }
            
            if (allExp.isEmpty() || allData.isEmpty()) {
                Log.d("ScoreUpdate", "Нет данных в БД")
                return
            }

            val now = LocalDateTime.now()
            
            // ========== ШАГ 1: Определяем активные экспрессы (ТОЛЬКО по времени) ==========
            val activeExps = mutableListOf<ExpEntity>()
            val expiredExps = mutableListOf<ExpEntity>()
            
            for (exp in allExp) {
                try {
                    val expTime = parseDateTime(exp.ct)
                    val hoursSinceCreation = ChronoUnit.HOURS.between(expTime, now)
                    
                    if (hoursSinceCreation <= ACTIVE_HOURS) {
                        // Экспресс создан менее 2 часов назад — АКТИВЕН
                        activeExps.add(exp)
                    } else {
                        // Экспресс старше 2 часов
                        if (exp.sts_all == 1) {
                            // Статус не обновлён — нужно пометить как завершённый
                            expiredExps.add(exp.copy(sts_all = -1))
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ScoreUpdate", "Ошибка парсинга ct для #${exp.id_exp}: ${exp.ct}")
                }
            }
            
            // Обновляем статусы просроченных экспрессов в БД
            if (expiredExps.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val updatedExp = allExp.map { exp ->
                        val expired = expiredExps.find { it.id == exp.id }
                        expired ?: exp
                    }
                    database.expDao().deleteAll()
                    database.expDao().insertAll(updatedExp)
                    Log.d("ScoreUpdate", "✅ Обновлено просроченных: ${expiredExps.size}")
                }
            }

            // Логируем активные экспрессы
            if (activeExps.isEmpty()) {
                onLogUpdate?.invoke("✓ Нет активных экспрессов")
                return
            }
            
            Log.d("ScoreUpdate", "📊 Активных экспрессов: ${activeExps.size}")
            for (exp in activeExps) {
                Log.d("ScoreUpdate", "  #${exp.id_exp}: ct=${exp.ct}")
            }

            // ========== ШАГ 2: Запрашиваем счета для ВСЕХ матчей активных экспрессов ==========
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
            
            Log.d("ScoreUpdate", "📊 Матчей для проверки: ${matchesToCheck.size}")

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
                                    // Данные поступили успешно
                                    if (tracking.dataStopped) {
                                        Log.d("ScoreUpdate", "🔄 Матч ${match.m_id}: данные возобновились")
                                        tracking.dataStopped = false
                                        tracking.dataStoppedTime = null
                                    }
                                    
                                    // Всегда сохраняем счёт в БД
                                    saveScoreToDb(match.m_id, factors.score1, factors.score2)
                                    updatedCount++
                                    
                                    // Обновляем минуту
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
                                        Log.d("ScoreUpdate", "⏰ Матч ${match.m_id}: ${factors.matchTime}' >= ${minuteLimit}'")
                                    }
                                    
                                    Log.d("ScoreUpdate", "✅ Матч ${match.m_id}: ${factors.score1}:${factors.score2} (${factors.matchTime}')")
                                } else {
                                    // Данные не поступили
                                    handleNoData(match, tracking)
                                }
                                
                                continuation.resume(Unit) {}
                            },
                            onError = { error ->
                                Log.d("ScoreUpdate", "❌ Матч ${match.m_id}: ошибка — $error")
                                val tracking = matchTracker.getOrPut(match.m_id) {
                                    MatchTrackingInfo(matchId = match.m_id)
                                }
                                handleNoData(match, tracking)
                                continuation.resume(Unit) {}
                            }
                        )
                    }
                    
                    if (index < matchesToCheck.size - 1) {
                        delay(300) // Задержка между запросами
                    }
                } catch (e: Exception) {
                    Log.w("ScoreUpdate", "Ошибка m_id=${match.m_id}: ${e.message}")
                }
            }

            // ========== ШАГ 3: Проверяем зависшие матчи ==========
            checkStaleMatches()

            // ========== ШАГ 4: Проверяем и обновляем статусы экспрессов ==========
            val refreshedData = withContext(Dispatchers.IO) { database.dataDao().getAllData() }
            val finishedCount = checkAndFinishExpresses(allExp, refreshedData)

            val msg = "📊 Активных: ${activeExps.size} | ✅$updatedCount | ⏰$minute90Count | 🏁$finishedCount"
            onLogUpdate?.invoke(msg)
            Log.d("ScoreUpdate", msg)

        } catch (e: Exception) {
            Log.e("ScoreUpdate", "Ошибка в updateScores: ${e.message}", e)
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
                
                val stoppedDuration = ChronoUnit.MINUTES.between(tracking.dataStoppedTime, now)
                if (stoppedDuration >= STALE_DATA_TIMEOUT_MINUTES && !tracking.isFinished) {
                    tracking.isFinished = true
                    Log.d("ScoreUpdate", "🏁 Матч ${match.m_id}: завершён (нет данных ${stoppedDuration} мин после ${tracking.lastMinute}')")
                }
            }
            
            // Минута == -1 (никогда не было данных)
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
                
                if (stoppedDuration >= STALE_DATA_TIMEOUT_MINUTES) {
                    if (tracking.lastMinute >= MATCH_FINISHED_MINUTE || tracking.lastMinute == -1) {
                        tracking.isFinished = true
                        Log.d("ScoreUpdate", "🏁 Матч $matchId: завершён по таймауту (${stoppedDuration} мин)")
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
     * Проверка и обновление статусов экспрессов на основе счёта матчей
     */
    private fun checkAndFinishExpresses(allExp: List<ExpEntity>, allData: List<DataEntity>): Int {
        var finishedCount = 0
        
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            try {
                var needUpdate = false
                val now = LocalDateTime.now()
                
                val updatedExp = allExp.map { exp ->
                    val expTime = try {
                        parseDateTime(exp.ct)
                    } catch (e: Exception) {
                        Log.w("ScoreUpdate", "Не удалось распарсить ct для #${exp.id_exp}")
                        return@map exp
                    }
                    
                    val hoursSinceCreation = ChronoUnit.HOURS.between(expTime, now)
                    
                    // Экспресс старше 2 часов и ещё активен — завершаем
                    if (hoursSinceCreation > ACTIVE_HOURS) {
                        if (exp.sts_all == 1) {
                            needUpdate = true
                            finishedCount++
                            Log.d("ScoreUpdate", "⏰ Экспресс #${exp.id_exp}: завершён по времени (${hoursSinceCreation}ч)")
                            return@map exp.copy(sts_all = -1)
                        }
                        return@map exp
                    }
                    
                    // Экспресс младше 2 часов — проверяем матчи
                    val matches = allData.filter { it.id_exp == exp.id_exp }
                    if (matches.isEmpty()) return@map exp
                    
                    var hasLosingMatch = false
                    var allHaveScore = true
                    
                    for (match in matches) {
                        if (match.sh > 0 || match.sa > 0) {
                            if (!isMatchWin(match.sh, match.sa, match.type)) {
                                hasLosingMatch = true
                                Log.d("ScoreUpdate", "❌ Экспресс #${exp.id_exp}: матч ${match.m_id} проигран (${match.sh}:${match.sa}, тип ${match.type})")
                            }
                        } else {
                            allHaveScore = false
                        }
                    }
                    
                    when {
                        hasLosingMatch -> {
                            // Есть проигрышный матч — экспресс проиграл
                            if (exp.sts_all != -1) {
                                needUpdate = true
                                finishedCount++
                                Log.d("ScoreUpdate", "🏁 Экспресс #${exp.id_exp}: ПРОИГРЫШ")
                                return@map exp.copy(sts_all = -1)
                            }
                        }
                        allHaveScore -> {
                            // Все матчи имеют счёт — проверяем результат
                            val allWins = matches.all { match ->
                                isMatchWin(match.sh, match.sa, match.type)
                            }
                            val newStatus = if (allWins) 2 else -1
                            if (exp.sts_all != newStatus) {
                                needUpdate = true
                                finishedCount++
                                val statusText = if (allWins) "ВЫИГРЫШ" else "ПРОИГРЫШ"
                                Log.d("ScoreUpdate", "🏁 Экспресс #${exp.id_exp}: $statusText")
                                return@map exp.copy(sts_all = newStatus)
                            }
                        }
                        else -> {
                            // Не все матчи имеют счёт — оставляем как есть (активен)
                            if (exp.sts_all != 1) {
                                needUpdate = true
                                Log.d("ScoreUpdate", "🔄 Экспресс #${exp.id_exp}: установлен статус АКТИВЕН")
                                return@map exp.copy(sts_all = 1)
                            }
                        }
                    }
                    
                    exp
                }
                
                if (needUpdate) {
                    database.expDao().deleteAll()
                    database.expDao().insertAll(updatedExp)
                    Log.d("ScoreUpdate", "✅ Обновлено статусов в БД: $finishedCount")
                } else {Log.d("ScoresUpdate","some")}
            } catch (e: Exception) {
                Log.e("ScoreUpdate", "Ошибка обновления статусов: ${e.message}", e)
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
                var found = false
                val updated = allData.map { 
                    if (it.m_id == matchId) {
                        found = true
                        it.copy(sh = sh, sa = sa)
                    } else {
                        it
                    }
                }
                if (found) {
                    database.dataDao().deleteAll()
                    database.dataDao().insertAll(updated)
                } else {it}
            } catch (e: Exception) {
                Log.e("ScoreUpdate", "Ошибка сохранения счёта для $matchId: ${e.message}")
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