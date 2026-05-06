// ScoreUpdateService.kt
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
        private const val ACTIVE_HOURS = 2L
        private const val MATCH_FINISHED_MINUTE = 90
        private const val MATCH_FINISHED_MINUTE_HOCKEY = 60
    }

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
                delay(30_000)
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
            
            // Находим активные экспрессы: sts_all == 1 И созданы <= ACTIVE_HOURS часов назад
            val activeExps = mutableListOf<ExpEntity>()
            for (exp in allExp) {
                if (exp.sts_all != 1) continue
                
                try {
                    val expTime = parseDateTime(exp.ct)
                    val hoursSinceCreation = ChronoUnit.HOURS.between(expTime, now)
                    
                    if (hoursSinceCreation <= ACTIVE_HOURS) {
                        activeExps.add(exp)
                        Log.d("ScoreUpdate", "Экспресс #${exp.id_exp}: ct=${exp.ct}, прошло=${hoursSinceCreation}ч, АКТИВЕН")
                    } else {
                        Log.d("ScoreUpdate", "Экспресс #${exp.id_exp}: ct=${exp.ct}, прошло=${hoursSinceCreation}ч, неактивен (>${ACTIVE_HOURS}ч)")
                    }
                } catch (e: Exception) {
                    Log.w("ScoreUpdate", "Ошибка парсинга ct для #${exp.id_exp}: ${exp.ct}")
                }
            }

            if (activeExps.isEmpty()) {
                onLogUpdate?.invoke("✓ Нет активных экспрессов")
                return
            }

            // Собираем матчи активных экспрессов, у которых нет счета (sh=0 и sa=0)
            val matchesToCheck = mutableListOf<DataEntity>()
            val seenIds = mutableSetOf<Long>()
            
            for (exp in activeExps) {
                for (data in allData) {
                    if (data.id_exp == exp.id_exp && data.sh == 0 && data.sa == 0 && data.m_id !in seenIds) {
                        matchesToCheck.add(data)
                        seenIds.add(data.m_id)
                    }
                }
            }

            var updatedCount = 0
            var minute90Count = 0

            // Запрашиваем счета для матчей без результата
            for ((index, match) in matchesToCheck.withIndex()) {
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        apiClient.getMatchScore(
                            matchId = match.m_id.toInt(),
                            onSuccess = { factors ->
                                if (factors != null && factors.score1 >= 0 && factors.score2 >= 0) {
                                    saveScoreToDb(match.m_id, factors.score1, factors.score2)
                                    updatedCount++
                                    
                                    Log.d("ScoreUpdate", "Матч ${match.m_id}: ${factors.score1}:${factors.score2} (${factors.matchTime}')")
                                    
                                    // Проверяем завершение по минуте
                                    val minuteLimit = if (match.liganame.contains("НХЛ", ignoreCase = true) ||
                                        match.liganame.contains("КХЛ", ignoreCase = true) ||
                                        match.liganame.contains("ВХЛ", ignoreCase = true) ||
                                        match.liganame.contains("AHL", ignoreCase = true)) {
                                        MATCH_FINISHED_MINUTE_HOCKEY
                                    } else {
                                        MATCH_FINISHED_MINUTE
                                    }
                                    
                                    if (factors.matchTime >= minuteLimit) {
                                        minute90Count++
                                        Log.d("ScoreUpdate", "  ⏰ Матч ${match.m_id} завершен по времени: ${factors.matchTime}' >= ${minuteLimit}'")
                                    }
                                }
                                continuation.resume(Unit) {}
                            },
                            onError = {
                                Log.d("ScoreUpdate", "  ❌ Матч ${match.m_id}: ошибка API")
                                continuation.resume(Unit) {}
                            }
                        )
                    }
                    // Задержка между запросами
                    if (index < matchesToCheck.size - 1) {
                        delay(300)
                    }
                } catch (e: Exception) {
                    Log.w("ScoreUpdate", "Ошибка m_id=${match.m_id}: ${e.message}")
                }
            }

            // Проверяем и обновляем статусы завершенных экспрессов
            val finishedCount = checkAndFinishExpresses(allExp, allData)

            val msg = "📊 Активных: ${activeExps.size} | ✅$updatedCount | ⏰$minute90Count | 🏁$finishedCount"
            onLogUpdate?.invoke(msg)

        } catch (e: Exception) {
            Log.e("ScoreUpdate", "Ошибка: ${e.message}")
        }
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
                Log.e("ScoreUpdate", "Ошибка сохранения: ${e.message}")
            }
        }
    }

    private fun checkAndFinishExpresses(allExp: List<ExpEntity>, allData: List<DataEntity>): Int {
        var finishedCount = 0
        
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            try {
                var needUpdate = false
                val updatedExp = allExp.map { exp ->
                    if (exp.sts_all != 1) {
                        exp // Уже завершен, не трогаем
                    } else {
                        // Собираем матчи этого экспресса
                        val matches = mutableListOf<DataEntity>()
                        for (data in allData) {
                            if (data.id_exp == exp.id_exp) {
                                matches.add(data)
                            }
                        }
                        
                        if (matches.isEmpty()) {
                            exp
                        } else {
                            // Проверяем, все ли матчи имеют счет
                            var allHaveScore = true
                            for (m in matches) {
                                if (m.sh == 0 && m.sa == 0) {
                                    allHaveScore = false
                                    break
                                }
                            }
                            
                            // Проверяем, есть ли проигрышный матч
                            var hasLosingMatch = false
                            for (m in matches) {
                                if (m.sh == 0 && m.sa == 0) continue
                                
                                var isWin = false
                                when (m.type) {
                                    924 -> isWin = m.sh >= m.sa
                                    927 -> isWin = m.sh + 1 > m.sa
                                    928 -> isWin = m.sa + 1 >= m.sh
                                    else -> isWin = m.sh >= m.sa
                                }
                                
                                if (!isWin) {
                                    hasLosingMatch = true
                                    break
                                }
                            }
                            
                            // Экспресс завершен, если все матчи имеют счет ИЛИ есть проигрышный матч
                            if (allHaveScore || hasLosingMatch) {
                                needUpdate = true
                                finishedCount++
                                
                                // Определяем результат
                                var allWins = true
                                if (allHaveScore) {
                                    for (m in matches) {
                                        var isWin = false
                                        when (m.type) {
                                            924 -> isWin = m.sh >= m.sa
                                            927 -> isWin = m.sh + 1 > m.sa
                                            928 -> isWin = m.sa + 1 >= m.sh
                                            else -> isWin = m.sh >= m.sa
                                        }
                                        if (!isWin) {
                                            allWins = false
                                            break
                                        }
                                    }
                                } else {
                                    allWins = false
                                }
                                
                                val newStatus = if (allWins) 2 else -1
                                val statusText = if (allWins) "ВЫИГРЫШ" else "ПРОИГРЫШ"
                                
                                Log.d("ScoreUpdate", "🏁 Экспресс #${exp.id_exp}: $statusText (матчей с результатом: ${matches.count { it.sh > 0 || it.sa > 0 }}/${matches.size})")
                                
                                exp.copy(sts_all = newStatus)
                            } else {
                                exp
                            }
                        }
                    }
                }
                
                if (needUpdate) {
                    database.expDao().deleteAll()
                    database.expDao().insertAll(updatedExp)
                    Log.d("ScoreUpdate", "Обновлено статусов: $finishedCount")
                } else {Log.e("dome")}
            } catch (e: Exception) {
                Log.e("ScoreUpdate", "Ошибка обновления статусов: ${e.message}")
            }
        }
        
        return finishedCount
    }

    private fun parseDateTime(ct: String): LocalDateTime {
        val formatters = listOf(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"),
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
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