// ScoreUpdateService.kt — ПОЛНАЯ ФИНАЛЬНАЯ ВЕРСИЯ
// CT < 2 часов → активен, запрашиваем API
// CT > 2 часов → завершён, не запрашиваем
// sh=0, sa=0 → выигрыш для любого типа ставки
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
        
        private const val ACTIVE_HOURS = 2L
    }

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

    // ИСПРАВЛЕНО: правильные формулы с +1.5
    private fun isMatchWin(sh: Int, sa: Int, type: Int): Boolean {
        return when (type) {
            924 -> sh >= sa
            927 -> (sh + 1.5) > sa    // Ф1(+1.5)
            928 -> (sa + 1.5) >= sh   // Ф2(+1.5)
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
            
            // Сначала проверяем и обновляем статусы всех экспрессов
            val initialFinished = checkAndUpdateAllExpresses(allExp, allData, now)
            
            // Собираем активные матчи (CT < 2 часов)
            val activeMatches = mutableListOf<DataEntity>()
            
            for (exp in allExp) {
                try {
                    val expTime = parseDateTime(exp.ct)
                    val hoursSinceCreation = ChronoUnit.HOURS.between(expTime, now)
                    
                    // ТОЛЬКО CT < 2 часов — запрашиваем API
                    if (hoursSinceCreation <= ACTIVE_HOURS) {
                        val matches = allData.filter { it.id_exp == exp.id_exp }
                        for (match in matches) {
                            if (match !in activeMatches) {
                                activeMatches.add(match)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ScoreUpdate", "Ошибка парсинга ct для #${exp.id_exp}: ${exp.ct}")
                }
            }
            
            val uniqueMatches = activeMatches.distinctBy { it.m_id }
            
            if (uniqueMatches.isEmpty()) {
                if (initialFinished > 0) {
                    onLogUpdate?.invoke("🏁 Завершено экспрессов: $initialFinished")
                } else {
                    onLogUpdate?.invoke("✓ Нет активных матчей")
                }
                return
            }

            onLogUpdate?.invoke("📊 Активных матчей: ${uniqueMatches.size}")

            var updatedCount = 0

            for ((index, match) in uniqueMatches.withIndex()) {
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        apiClient.getMatchScore(
                            matchId = match.m_id.toInt(),
                            onSuccess = { factors ->
                                if (factors != null && factors.score1 >= 0 && factors.score2 >= 0) {
                                    // Обновляем счёт в БД, curtime НЕ трогаем
                                    if (match.sh != factors.score1 || match.sa != factors.score2) {
                                        saveScoreToDb(match.m_id, factors.score1, factors.score2)
                                        updatedCount++
                                    }
                                    
                                    // Передаём живую минуту для отображения
                                    onScoreUpdated?.invoke(
                                        match.m_id,
                                        factors.score1,
                                        factors.score2,
                                        factors.matchTime
                                    )
                                    
                                    Log.d("ScoreUpdate", "✅ Матч ${match.m_id}: ${factors.score1}:${factors.score2} (${factors.matchTime}')")
                                }
                                continuation.resume(Unit) {}
                            },
                            onError = { error ->
                                Log.d("ScoreUpdate", "❌ Матч ${match.m_id}: ошибка API — $error")
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

            // Обновляем статусы после получения счетов
            val refreshedData = withContext(Dispatchers.IO) { database.dataDao().getAllData() }
            val finalFinished = checkAndUpdateAllExpresses(allExp, refreshedData, now)

            val msg = "📊 Матчей: ${uniqueMatches.size} | ✅$updatedCount | 🏁$finalFinished"
            onLogUpdate?.invoke(msg)

        } catch (e: Exception) {
            Log.e("ScoreUpdate", "Ошибка в updateScores: ${e.message}", e)
        }
    }

    private fun checkAndUpdateAllExpresses(allExp: List<ExpEntity>, allData: List<DataEntity>, now: LocalDateTime): Int {
        var updatedCount = 0
        
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            try {
                var needUpdate = false
                
                val updatedExp = allExp.map { exp ->
                    val matches = allData.filter { it.id_exp == exp.id_exp }
                    if (matches.isEmpty()) return@map exp
                    
                    try {
                        val expTime = parseDateTime(exp.ct)
                        val hoursSinceCreation = ChronoUnit.HOURS.between(expTime, now)
                        
                        var hasLosingMatch = false
                        var allHaveScore = true
                        
                        for (match in matches) {
                            if (match.sh > 0 || match.sa > 0) {
                                // Есть счёт (не 0:0)
                                if (!isMatchWin(match.sh, match.sa, match.type)) {
                                    hasLosingMatch = true
                                }
                            } else {
                                // sh=0, sa=0
                                if (hoursSinceCreation > ACTIVE_HOURS) {
                                    // CT > 2ч — данных не будет
                                    // 0:0 — это ВЫИГРЫШ для любого типа ставки
                                } else {
                                    // CT < 2ч — ещё ждём данные из API
                                    allHaveScore = false
                                }
                            }
                        }
                        
                        // Определяем новый статус
                        val newStatus = when {
                            hasLosingMatch -> -1
                            allHaveScore -> {
                                if (matches.all { isMatchWin(it.sh, it.sa, it.type) }) 2 else -1
                            }
                            else -> 1 // Ещё активен
                        }
                        
                        if (exp.sts_all != newStatus) {
                            needUpdate = true
                            updatedCount++
                            val statusText = when (newStatus) {
                                2 -> "ВЫИГРЫШ"
                                -1 -> "ПРОИГРЫШ"
                                else -> "АКТИВЕН"
                            }
                            Log.d("ScoreUpdate", "🔄 Экспресс #${exp.id_exp}: $statusText (CT: ${hoursSinceCreation}ч)")
                            return@map exp.copy(sts_all = newStatus)
                        }
                    } catch (e: Exception) {
                        Log.w("ScoreUpdate", "Ошибка обработки #${exp.id_exp}: ${e.message}")
                    }
                    
                    exp
                }
                
                if (needUpdate) {
                    database.expDao().deleteAll()
                    database.expDao().insertAll(updatedExp)
                    Log.d("ScoreUpdate", "✅ Обновлено статусов в БД: $updatedCount")
                } else {Log.d("ScoreUpdate","some")}
            } catch (e: Exception) {
                Log.e("ScoreUpdate", "Ошибка обновления статусов: ${e.message}", e)
            }
        }
        
        return updatedCount
    }

    // Обновляет ТОЛЬКО счёт (sh, sa), curtime НЕ трогаем
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