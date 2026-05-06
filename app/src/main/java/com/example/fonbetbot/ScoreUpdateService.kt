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

class ScoreUpdateService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val apiClient = ApiClient()
    private lateinit var database: AppDatabase

    companion object {
        const val CHANNEL_ID = "ScoreUpdateChannel"
        const val NOTIFICATION_ID = 100
        var isRunning = false
        var onLogUpdate: ((String) -> Unit)? = null
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
            .setContentTitle("Обновление счетов")
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

            val engine = AnalyticsEngine(database)
            val analytics = engine.calculateAnalytics()
            val expresses = (analytics["allExpresses"] as? List<ExpressResult>) ?: return

            val liveExpresses = expresses.filter { !isExpressFinished(it) }
            if (liveExpresses.isEmpty()) return

            val liveMatchIds = liveExpresses
                .flatMap { it.matches }
                .filter { it.sh == 0 && it.sa == 0 }
                .map { it.matchId }
                .distinct()

            var updatedCount = 0
            var finishedCount = 0

            for (matchId in liveMatchIds) {
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        apiClient.getMatchScore(
                            matchId = matchId.toInt(),
                            onSuccess = { factors ->
                                if (factors != null && factors.score1 >= 0 && factors.score2 >= 0) {
                                    saveScoreToDb(matchId, factors.score1, factors.score2)
                                    updatedCount++
                                } else {
                                    saveScoreToDb(matchId, 0, 0)
                                    finishedCount++
                                }
                                continuation.resume(Unit) {}
                            },
                            onError = {
                                saveScoreToDb(matchId, 0, 0)
                                finishedCount++
                                continuation.resume(Unit) {}
                            }
                        )
                    }
                    delay(200)
                } catch (e: Exception) {
                    Log.w("ScoreUpdateService", "Ошибка m_id=$matchId: ${e.message}")
                }
            }

            val msg = buildString {
                if (updatedCount > 0) append("✅ Счетов: $updatedCount ")
                if (finishedCount > 0) append("🏁 Завершено: $finishedCount")
            }
            if (msg.isNotEmpty()) {
                onLogUpdate?.invoke(msg)
            }
        } catch (e: Exception) {
            Log.e("ScoreUpdateService", "Ошибка: ${e.message}")
        }
    }

    private fun saveScoreToDb(matchId: Long, sh: Int, sa: Int) {
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            try {
                val allData = database.dataDao().getAllData()
                val updated = allData.map { if (it.m_id == matchId) it.copy(sh = sh, sa = sa) else it }
                database.dataDao().deleteAll()
                database.dataDao().insertAll(updated)
            } catch (e: Exception) {
                Log.e("ScoreUpdateService", "Ошибка сохранения: ${e.message}")
            }
        }
    }

    private fun isExpressFinished(express: ExpressResult): Boolean {
        val now = java.time.LocalDateTime.now()
        val ageHours = java.time.temporal.ChronoUnit.HOURS.between(express.dateTime, now)
        if (ageHours >= 3) return true
        if (express.matches.any { !it.isWin }) return true
        if (express.matches.all { it.isWin }) return true
        return false
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