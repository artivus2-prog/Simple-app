// ScoreUpdateService.kt — ФИНАЛ
// Полный пересчёт: только при старте и ручных изменениях.
// Цикл: только активные матчи (CT >= сейчас - 2ч).
// 0:0 валидный счёт если curtime > 1.
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
        
        @Volatile
        var requestFullRecalc = false
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
            .setContentText("Отслеживание матчей...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startScoreUpdates() {
        serviceScope.launch {
            // Первичный полный пересчёт при старте
            onLogUpdate?.invoke("🔄 Первичный пересчёт всей БД...")
            fullRecalculation()
            onLogUpdate?.invoke("✅ Первичный пересчёт завершён")

            // Основной цикл — только активные
            while (isActive) {
                delay(30_000)
                
                if (requestFullRecalc) {
                    onLogUpdate?.invoke("🔄 Ручной пересчёт всей БД...")
                    fullRecalculation()
                    requestFullRecalc = false
                    onLogUpdate?.invoke("✅ Ручной пересчёт завершён")
                } else {
                    updateActiveMatches()
                }
            }
        }
    }

    // ==================== ФОРМУЛЫ ====================
    
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
            try { return LocalDateTime.parse(trimmed, formatter) } catch (_: Exception) {}
        }
        Log.e("ScoreUpdate", "❌ Не удалось распарсить ct: '$ct'")
        return LocalDateTime.of(1970, 1, 1, 0, 0)
    }

    // ==================== ПОЛНЫЙ ПЕРЕСЧЁТ (только при старте / ручном изменении) ====================

    private suspend fun fullRecalculation() {
        try {
            val allData = withContext(Dispatchers.IO) { database.dataDao().getAllData() }
            val allExp = withContext(Dispatchers.IO) { database.expDao().getAllExp() }
            if (allExp.isEmpty()) return

            var matchUpdated = 0
            var expUpdated = 0

            // Пересчёт sts матчей
            val updatedMatches = allData.map { match ->
                val newSts = if (match.curtime > 1) {
                    if (isMatchWin(match.sh, match.sa, match.type)) 2 else -1
                } else {
                    match.sts
                }
                if (newSts != match.sts) matchUpdated++
                match.copy(sts = newSts)
            }

            if (matchUpdated > 0) {
                withContext(Dispatchers.IO) {
                    database.dataDao().deleteAll()
                    database.dataDao().insertAll(updatedMatches)
                }
            }

            // Пересчёт sts_all экспрессов
            val updatedExp = allExp.map { exp ->
                val matches = updatedMatches.filter { it.id_exp == exp.id_exp }
                if (matches.isEmpty()) return@map exp

                val allHaveScore = matches.all { it.curtime > 1 }
                val newStsAll = if (allHaveScore) {
                    if (matches.all { isMatchWin(it.sh, it.sa, it.type) }) 2 else -1
                } else {
                    1
                }

                if (newStsAll != exp.sts_all) expUpdated++
                exp.copy(sts_all = newStsAll)
            }

            if (expUpdated > 0) {
                withContext(Dispatchers.IO) {
                    database.expDao().deleteAll()
                    database.expDao().insertAll(updatedExp)
                }
            }

            Log.d("ScoreUpdate", "Полный пересчёт: матчей ✏$matchUpdated, экспрессов ✏$expUpdated")

        } catch (e: Exception) {
            Log.e("ScoreUpdate", "Ошибка полного пересчёта: ${e.message}", e)
        }
    }

    // ==================== ОБНОВЛЕНИЕ ТОЛЬКО АКТИВНЫХ ====================

    private suspend fun updateActiveMatches() {
        try {
            val allData = withContext(Dispatchers.IO) { database.dataDao().getAllData() }
            val allExp = withContext(Dispatchers.IO) { database.expDao().getAllExp() }
            if (allExp.isEmpty()) return

            val now = LocalDateTime.now()
            val twoHoursAgo = now.minusHours(ACTIVE_HOURS)
            
            // Активен если CT >= сейчас - 2ч
            val activeExpIds = allExp.filter { exp ->
                val expTime = parseDateTime(exp.ct)
                !expTime.isBefore(twoHoursAgo)
            }.map { it.id_exp }.toSet()

            if (activeExpIds.isEmpty()) {
                onLogUpdate?.invoke("✓ Нет активных матчей")
                return
            }

            val activeMatches = allData.filter { it.id_exp in activeExpIds }.distinctBy { it.m_id }
            onLogUpdate?.invoke("📡 Активных матчей: ${activeMatches.size}")

            var updatedCount = 0

            for ((index, match) in activeMatches.withIndex()) {
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        apiClient.getMatchScore(
                            matchId = match.m_id.toInt(),
                            onSuccess = { factors ->
                                if (factors != null && factors.score1 >= 0 && factors.score2 >= 0) {
                                    if (match.sh != factors.score1 || match.sa != factors.score2) {
                                        saveScoreToDb(match.m_id, factors.score1, factors.score2)
                                        updatedCount++
                                    }
                                    onScoreUpdated?.invoke(match.m_id, factors.score1, factors.score2, factors.matchTime)
                                }
                                continuation.resume(Unit) {}
                            },
                            onError = { continuation.resume(Unit) {} }
                        )
                    }
                    if (index < activeMatches.size - 1) delay(300)
                } catch (e: Exception) {
                    Log.w("ScoreUpdate", "Ошибка API m_id=${match.m_id}: ${e.message}")
                }
            }

            // Частичный пересчёт статусов для активных
            if (updatedCount > 0) {
                recalcActiveStatuses(activeExpIds)
            }

            onLogUpdate?.invoke("📡 Запрошено: ${activeMatches.size} | ✅$updatedCount")

        } catch (e: Exception) {
            Log.e("ScoreUpdate", "Ошибка: ${e.message}", e)
        }
    }

    private suspend fun recalcActiveStatuses(activeExpIds: Set<Int>) {
        val refreshedData = withContext(Dispatchers.IO) { database.dataDao().getAllData() }
        val refreshedExp = withContext(Dispatchers.IO) { database.expDao().getAllExp() }
        var matchUpdated = 0
        var expUpdated = 0

        // Обновляем sts для матчей активных экспрессов
        val updatedMatches = refreshedData.map { match ->
            if (match.id_exp in activeExpIds && match.curtime > 1) {
                val newSts = if (isMatchWin(match.sh, match.sa, match.type)) 2 else -1
                if (newSts != match.sts) matchUpdated++
                match.copy(sts = newSts)
            } else match
        }

        if (matchUpdated > 0) {
            withContext(Dispatchers.IO) {
                database.dataDao().deleteAll()
                database.dataDao().insertAll(updatedMatches)
            }
        }

        // Обновляем sts_all для активных экспрессов
        val updatedExp = refreshedExp.map { exp ->
            if (exp.id_exp !in activeExpIds) return@map exp
            val matches = updatedMatches.filter { it.id_exp == exp.id_exp }
            if (matches.isEmpty()) return@map exp

            val allHaveScore = matches.all { it.curtime > 1 }
            val newStsAll = if (allHaveScore) {
                if (matches.all { isMatchWin(it.sh, it.sa, it.type) }) 2 else -1
            } else 1

            if (newStsAll != exp.sts_all) expUpdated++
            exp.copy(sts_all = newStsAll)
        }

        if (expUpdated > 0) {
            withContext(Dispatchers.IO) {
                database.expDao().deleteAll()
                database.expDao().insertAll(updatedExp)
            }
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
                Log.e("ScoreUpdate", "Ошибка сохранения счёта: ${e.message}")
            }
        }
    }

    // ==================== СЛУЖЕБНЫЕ ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Обновление счетов", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Фоновое обновление счетов"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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