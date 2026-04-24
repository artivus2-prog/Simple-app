// BotForegroundService.kt - ПОЛНАЯ ИСПРАВЛЕННАЯ ВЕРСИЯ
package com.example.fonbetbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

class BotForegroundService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var balance = 0.0
    private val apiClient = ApiClient()
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var prefs: SharedPreferences
    
    companion object {
        const val CHANNEL_ID = "BotForegroundChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "STOP_BOT"
        private const val TAG = "BotForegroundService"
        
        var isRunning = false
        var onBalanceUpdate: ((Double) -> Unit)? = null
        var onLogUpdate: ((String) -> Unit)? = null
        var onBetsUpdate: ((List<Pair<Int, Int>>) -> Unit)? = null
        var onScoresUpdate: ((String) -> Unit)? = null
        var authData: AuthData? = null
        var lastBalance: Double = 0.0
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createBetNotificationChannel()
        dbHelper = DatabaseHelper(this)
        prefs = getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        
        authData?.let { data ->
            try {
                val user = dbHelper.getUser(data.fsid, data.deviceId)
                user?.let {
                    val stats = dbHelper.getBalanceStats(it.id)
                    if (stats.currentBalance > 0) {
                        balance = stats.currentBalance
                        lastBalance = stats.currentBalance
                    }
                }
            } catch (e: Exception) { }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBot()
                stopSelf()
            }
            else -> {
                startBot()
            }
        }
        return START_STICKY
    }
    
    private fun startBot() {
        if (isRunning) return
        
        isRunning = true
        
        val notification = createNotification("Бот запущен", "Баланс: загрузка...")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        onLogUpdate?.invoke("[${getCurrentTime()}] 🚀 Бот запущен в фоновом режиме")
        
        authData?.let { data ->
            try {
                val user = dbHelper.getUser(data.fsid, data.deviceId)
                user?.let {
                    dbHelper.startBotSession(it.id, balance)
                    dbHelper.addLog(it.id, "start", "Бот запущен")
                }
            } catch (e: Exception) { }
        }
        
        // Цикл проверки баланса (каждые 60 секунд)
        serviceScope.launch {
            while (isRunning) {
                delay(60000)
                authData?.let { data -> fetchBalance(data) }
            }
        }
        
        // Цикл получения ставок (каждые 30 секунд)
        serviceScope.launch {
            delay(5000)
            while (isRunning) {
                authData?.let { data -> fetchBets(data) }
                delay(30000)
            }
        }
        
        // Цикл обновления счета матчей (каждые 60 секунд)
        serviceScope.launch {
            delay(10000)
            while (isRunning) {
                updateActiveMatchesScores()
                delay(60000)
            }
        }
        
        // Цикл проверки замены экспрессов (каждый час)
        serviceScope.launch {
            delay(60000)
            while (isRunning) {
                checkAndCreateReplacementExpress()
                delay(3600000)
            }
        }
    }
    
    // ==================== МЕТОДЫ ПОЛУЧЕНИЯ ДАННЫХ ====================
    
    private fun fetchBalance(data: AuthData) {
        val cookieManager = CookieManager.getInstance()
        val cookieString = cookieManager.getCookie("https://www.fon.bet") ?: ""
        
        val cookies = if (cookieString.isNotEmpty()) {
            cookieString.split("; ").associate { cookie ->
                val parts = cookie.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }
        } else {
            emptyMap()
        }
        
        apiClient.getSaldo(
            cookies = cookies,
            fsid = data.fsid,
            deviceId = data.deviceId,
            onSuccess = { sessionInfo: ApiClient.SessionInfo? ->
                if (sessionInfo != null) {
                    val newBalance = sessionInfo.saldo
                    val oldBalance = balance
                    
                    Log.d(TAG, "fetchBalance: saldo=$newBalance, clientId=${sessionInfo.clientId}, userName=${sessionInfo.userName}")
                    
                    if (newBalance != null) {
                        val difference = newBalance - oldBalance
                        balance = newBalance
                        lastBalance = newBalance
                        
                        onBalanceUpdate?.invoke(newBalance)
                        
                        try {
                            val user = dbHelper.getUser(data.fsid, data.deviceId)
                            user?.let { userData ->
                                dbHelper.saveBalance(userData.id, newBalance, "success")
                                
                                val userClientId = sessionInfo.clientId
                                val userDisplayName = sessionInfo.userName
                                
                                if (userClientId != null || userDisplayName != null) {
                                    Log.d(TAG, "Обновление пользователя: clientId=$userClientId, userName=$userDisplayName")
                                    dbHelper.updateUserInfo(userData.id, userClientId, userDisplayName)
                                }
                                
                                if (difference > 0 && oldBalance > 0) {
                                    dbHelper.addLog(userData.id, "profit", "Профит: +%.2f ₽".format(difference))
                                } else if (difference < 0 && oldBalance > 0) {
                                    dbHelper.addLog(userData.id, "loss", "Убыток: %.2f ₽".format(difference))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка сохранения баланса: ${e.message}")
                        }
                        
                        val displayName = sessionInfo.userName ?: ""
                        val displayText: String = if (displayName.isNotEmpty()) {
                            "Баланс: %.2f ₽ | %s".format(newBalance, displayName)
                        } else {
                            "Баланс: %.2f ₽".format(newBalance)
                        }
                        
                        val notification = createNotification("Бот активен", displayText)
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
                        
                        val timestamp = getCurrentTime()
                        if (difference > 0 && oldBalance > 0) {
                            onLogUpdate?.invoke("[$timestamp] 💰 Профит: +%.2f ₽".format(difference))
                        } else if (difference < 0 && oldBalance > 0) {
                            onLogUpdate?.invoke("[$timestamp] 📉 Убыток: %.2f ₽".format(difference))
                        }
                    }
                }
            },
            onError = { error: String ->
                onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ошибка API: $error")
                try {
                    val user = dbHelper.getUser(data.fsid, data.deviceId)
                    user?.let { dbHelper.addLog(it.id, "error", "Ошибка API: $error", "ERROR") }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка логирования: ${e.message}")
                }
            }
        )
    }
    
    // ==================== НОВЫЙ МЕТОД fetchBets ====================
    
    private fun fetchBets(data: AuthData) {
        try {
            val user = dbHelper.getUser(data.fsid, data.deviceId)
            user?.let { userData ->
                val settings = ApiClient.BetSettings(
                    maxMatchesPerExpress = prefs.getInt("max_matches_per_express", 2),
                    multiply = prefs.getInt("multiply", 2),
                    allMinKef = prefs.getFloat("all_min_kef", 1.67f).toDouble(),
                    types = mapOf(
                        924 to ApiClient.TypeSettings("1х/футбол/хоккей",
                            prefs.getFloat("type_924_min", 1.15f).toDouble(),
                            prefs.getFloat("type_924_max", 1.35f).toDouble(),
                            prefs.getInt("type_924_start", 80),
                            prefs.getInt("type_924_end", 100)
                        ),
                        927 to ApiClient.TypeSettings("ф1(+1.5)/футбол/хоккей",
                            prefs.getFloat("type_927_min", 1.15f).toDouble(),
                            prefs.getFloat("type_927_max", 1.35f).toDouble(),
                            prefs.getInt("type_927_start", 1),
                            prefs.getInt("type_927_end", 45)
                        ),
                        928 to ApiClient.TypeSettings("ф2(+1.5)/футбол/хоккей",
                            prefs.getFloat("type_928_min", 1.15f).toDouble(),
                            prefs.getFloat("type_928_max", 1.35f).toDouble(),
                            prefs.getInt("type_928_start", 1),
                            prefs.getInt("type_928_end", 45)
                        )
                    )
                )
                
                apiClient.getBets(
                    userId = userData.id,
                    settings = settings,
                    onSuccess = { betDataList: List<ApiClient.BetData> ->
                        if (betDataList.isNotEmpty()) {
                            
                            val matchIds = betDataList.map { it.mId }.sorted()
                            val matchIdsKey = matchIds.joinToString(",")
                            
                            Log.d(TAG, "Получен готовый экспресс: $matchIdsKey (${betDataList.size} матчей)")
                            
                            // Проверяем, существует ли уже такой экспресс
                            if (isExpressAlreadyExists(userData.id, matchIds)) {
                                Log.d(TAG, "⏭ Экспресс с матчами $matchIdsKey уже существует, пропускаем")
                                onLogUpdate?.invoke("[${getCurrentTime()}] ⏭ Экспресс уже обработан: $matchIdsKey")
                                return@getBets
                            }
                            
                            onLogUpdate?.invoke("[${getCurrentTime()}] 🎲 Получен готовый экспресс (${betDataList.size} матчей)")
                            
                            betDataList.forEach { betData: ApiClient.BetData ->
                                val matchInfo = buildString {
                                    append("📊 Матч #${betData.mId}: ")
                                    if (betData.home.isNotEmpty() || betData.away.isNotEmpty()) {
                                        append("${betData.home} vs ${betData.away} | ")
                                    }
                                    if (betData.ligaName.isNotEmpty()) {
                                        append("${betData.ligaName} | ")
                                    }
                                    append("Кэф: ${"%.2f".format(betData.startKf)} | ")
                                    append("Тип: ${typeName(betData.type)}")
                                }
                                onLogUpdate?.invoke("[${getCurrentTime()}] $matchInfo")
                            }
                            
                            val newExpId = (System.currentTimeMillis() / 1000).toInt()
                            
                            onLogUpdate?.invoke("[${getCurrentTime()}] 🎯 Создаем экспресс #$newExpId")
                            onBetsUpdate?.invoke(betDataList.map { Pair(it.mId, it.type) })
                            
                            // 1. Сохраняем экспресс в БД
                            val expressDbId = saveExpressToDb(
                                userId = userData.id,
                                expId = newExpId,
                                betDataList = betDataList
                            )
                            
                            if (expressDbId > 0) {
                                // 2. Размещаем ставку
                                placeBet(
                                    expressDbId = expressDbId,
                                    expId = newExpId,
                                    userId = userData.id,
                                    betDataList = betDataList
                                )
                            }
                            
                        } else {
                            onLogUpdate?.invoke("[${getCurrentTime()}] 📭 Нет подходящих матчей")
                        }
                    },
                    onError = { error: String ->
                        Log.e(TAG, "Ошибка получения ставок: $error")
                        onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ошибка получения ставок: $error")
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в fetchBets: ${e.message}")
        }
    }
    
    // ==================== НОВЫЕ МЕТОДЫ ДЛЯ РАБОТЫ С ЭКСПРЕССАМИ ====================
    
    // Проверка существования экспресса по набору m_id
    private fun isExpressAlreadyExists(userId: Long, matchIds: List<Int>): Boolean {
        val db = dbHelper.readableDatabase
        
        val cursor = db.query(
            "express_bets",
            arrayOf("id"),
            "user_id = ? AND sts_all IN (0, 1, 2)",
            arrayOf(userId.toString()),
            null, null, null
        )
        
        val expressIds = mutableListOf<Long>()
        while (cursor.moveToNext()) {
            expressIds.add(cursor.getLong(0))
        }
        cursor.close()
        
        for (expressId in expressIds) {
            val eventsCursor = db.query(
                "express_events",
                arrayOf("m_id"),
                "express_id = ?",
                arrayOf(expressId.toString()),
                null, null, null
            )
            
            val existingMatchIds = mutableListOf<Int>()
            while (eventsCursor.moveToNext()) {
                existingMatchIds.add(eventsCursor.getInt(0))
            }
            eventsCursor.close()
            
            if (existingMatchIds.sorted() == matchIds.sorted()) {
                return true
            }
        }
        
        return false
    }
    
    // Сохранение экспресса в БД
    private fun saveExpressToDb(
        userId: Long,
        expId: Int,
        betDataList: List<ApiClient.BetData>
    ): Long {
        val db = dbHelper.writableDatabase
        val currentTimeUtc = System.currentTimeMillis() / 1000
        val betAmount = prefs.getString("bet_amount", "30")?.toDoubleOrNull() ?: 30.0
        
        var totalKef = 1.0
        betDataList.forEach { betData ->
            totalKef *= betData.startKf
        }
        
        val potentialWin = betAmount * totalKef
        
        Log.d(TAG, "Сохранение экспресса #$expId:")
        Log.d(TAG, "  Матчей: ${betDataList.size}")
        Log.d(TAG, "  Общий кэф: ${"%.2f".format(totalKef)}")
        Log.d(TAG, "  Ставка: ${betAmount.toInt()} ₽")
        Log.d(TAG, "  Выигрыш: ${"%.2f".format(potentialWin)} ₽")
        
        val expressValues = ContentValues().apply {
            put("user_id", userId)
            put("id_exp", expId)
            put("kfall", totalKef)
            put("profloss", 0.0)
            put("balans", balance)
            put("sumbet", betAmount)
            put("sts_all", 0)
            put("is_bet_placed", 0)
            put("ct", currentTimeUtc)
            put("strategy", "standard")
            put("id_exp_replace", 0)
            put("events_count", betDataList.size)
            put("total_odds", totalKef)
            put("bet_amount", betAmount)
            put("potential_win", potentialWin)
            put("balance", balance)
            put("created_time", currentTimeUtc)
            put("created_at", currentTimeUtc)
            put("updated_at", currentTimeUtc)
        }
        
        val expressId = db.insert("express_bets", null, expressValues)
        
        if (expressId == -1L) {
            Log.e(TAG, "❌ Ошибка сохранения экспресса в БД")
            return -1
        }
        
        Log.d(TAG, "✅ Экспресс #$expId сохранен: dbId=$expressId")
        
        // Сохраняем матчи
        betDataList.forEach { betData ->
            val initialStatus = if (betData.sh > 0 || betData.sa > 0) {
                checkMatchStatus(betData.type, betData.sh, betData.sa)
            } else {
                0
            }
            
            val eventValues = ContentValues().apply {
                put("express_id", expressId)
                put("id_exp", expId)
                put("user_id", userId)
                put("m_id", betData.mId)
                put("start_odds", betData.startKf)
                put("current_odds", betData.lastKf)
                put("bet_type", betData.type)
                put("status", initialStatus)
                put("home_score", betData.sh)
                put("away_score", betData.sa)
                put("match_time", 0)
                put("created_at", currentTimeUtc)
                put("updated_at", currentTimeUtc)
                put("is_finalized", 0)
                
                if (betData.idLiga > 0) put("id_liga", betData.idLiga.toLong())
                if (betData.ligaName.isNotEmpty()) put("league_name", betData.ligaName)
                if (betData.home.isNotEmpty()) put("home_team", betData.home)
                if (betData.away.isNotEmpty()) put("away_team", betData.away)
                if (betData.comand1Id > 0) put("id_home", betData.comand1Id.toLong())
                if (betData.comand2Id > 0) put("id_away", betData.comand2Id.toLong())
                if (betData.url.isNotEmpty()) put("match_url", betData.url)
                if (betData.uzh > 0) put("uzh", betData.uzh.toString())
                if (betData.tbType > 0) put("total_type", betData.tbType.toLong())
            }
            
            db.insert("express_events", null, eventValues)
            Log.d(TAG, "  📊 Матч #${betData.mId} сохранен (status=$initialStatus)")
        }
        
        dbHelper.addLog(userId, "express_created", 
            "Экспресс #$expId: ${betDataList.size} матчей, кэф ${"%.2f".format(totalKef)}, ставка ${betAmount.toInt()} ₽")
        
        return expressId
    }
    
    // Размещение ставки
    private fun placeBet(
        expressDbId: Long,
        expId: Int,
        userId: Long,
        betDataList: List<ApiClient.BetData>
    ): Boolean {
        try {
            val db = dbHelper.writableDatabase
            
            val cursor = db.query(
                "express_bets",
                arrayOf("sumbet", "kfall", "potential_win"),
                "id = ?",
                arrayOf(expressDbId.toString()),
                null, null, null
            )
            
            if (!cursor.moveToFirst()) {
                Log.e(TAG, "❌ Экспресс #$expId не найден в БД")
                return false
            }
            
            val betAmount = cursor.getDouble(0)
            val totalKef = cursor.getDouble(1)
            val potentialWin = cursor.getDouble(2)
            cursor.close()
            
            val matchesInfo = betDataList.joinToString(" + ") { betData ->
                "${betData.home.take(15)} vs ${betData.away.take(15)} (${typeName(betData.type)})"
            }
            
            Log.d(TAG, "🎲 РАЗМЕЩЕНИЕ СТАВКИ #$expId:")
            Log.d(TAG, "  Матчи: $matchesInfo")
            Log.d(TAG, "  Сумма: ${betAmount.toInt()} ₽")
            Log.d(TAG, "  Кэф: ${"%.2f".format(totalKef)}")
            Log.d(TAG, "  Выигрыш: ${"%.2f".format(potentialWin)} ₽")
            
            // ЗАГЛУШКА: всегда возвращает true
            val betSuccess = simulatePlaceBet(userId, expId, betAmount, totalKef)
            
            if (betSuccess) {
                val values = ContentValues().apply {
                    put("is_bet_placed", 1)
                    put("updated_at", System.currentTimeMillis() / 1000)
                }
                db.update("express_bets", values, "id = ?", arrayOf(expressDbId.toString()))
                
                val notification = createBetNotification(
                    "✅ Ставка принята!",
                    "Экспресс #$expId | ${betAmount.toInt()} ₽ | Кэф: ${"%.2f".format(totalKef)} | Выигрыш: ${"%.2f".format(potentialWin)} ₽"
                )
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify((NOTIFICATION_ID + expId).toInt(), notification)
                
                val successMessage = "✅ Ставка #$expId ПРИНЯТА! ${betAmount.toInt()} ₽ × ${"%.2f".format(totalKef)} = ${"%.2f".format(potentialWin)} ₽ | $matchesInfo"
                onLogUpdate?.invoke("[${getCurrentTime()}] $successMessage")
                onScoresUpdate?.invoke(successMessage)
                
                dbHelper.addLog(userId, "bet_placed", 
                    "Ставка #$expId: ${betAmount.toInt()} ₽, кэф ${"%.2f".format(totalKef)}")
                
                return true
            } else {
                onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ставка #$expId НЕ ПРИНЯТА!")
                dbHelper.addLog(userId, "bet_error", "Ошибка ставки #$expId", "ERROR")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в placeBet: ${e.message}")
            return false
        }
    }
    
    // Заглушка размещения ставки
    private fun simulatePlaceBet(
        userId: Long,
        expId: Int,
        amount: Double,
        kef: Double
    ): Boolean {
        Log.d(TAG, "🎲 PLACE BET (заглушка): userId=$userId, expId=$expId, amount=${amount.toInt()}₽, kef=${"%.2f".format(kef)}")
        Thread.sleep(300)
        return true
    }
    
    // ==================== МЕТОДЫ ОБНОВЛЕНИЯ СЧЕТА ====================
    
    private fun updateActiveMatchesScores() {
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.query("express_bets", arrayOf("id", "id_exp", "user_id"),
                "sts_all IN (0, 1, 2)", null, null, null, null)
            
            val activeExpresses = mutableListOf<Triple<Long, Int, Long>>()
            while (cursor.moveToNext()) activeExpresses.add(Triple(cursor.getLong(0), cursor.getInt(1), cursor.getLong(2)))
            cursor.close()
            
            activeExpresses.forEach { (expressId: Long, expId: Int, userId: Long) ->
                val eventsCursor = db.query("express_events",
                    arrayOf("id", "m_id", "bet_type", "status", "home_score", "away_score", "match_time"),
                    "express_id = ? AND is_finalized = 0", arrayOf(expressId.toString()), null, null, null)
                
                val events = mutableListOf<EventInfo>()
                while (eventsCursor.moveToNext()) events.add(EventInfo(eventsCursor.getLong(0), eventsCursor.getInt(1),
                    eventsCursor.getInt(2), eventsCursor.getInt(3), eventsCursor.getInt(4), eventsCursor.getInt(5), eventsCursor.getInt(6)))
                eventsCursor.close()
                
                events.forEach { event: EventInfo -> updateMatchScore(event, expressId, expId, userId) }
                checkExpressStatus(expressId, expId, userId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления счетов: ${e.message}")
        }
    }
    
    private fun updateMatchScore(event: EventInfo, expressId: Long, expId: Int, userId: Long) {
        apiClient.getMatchScore(matchId = event.mId,
            onSuccess = { matchFactors: ApiClient.MatchFactors? ->
                if (matchFactors != null) {
                    val sh = matchFactors.score1
                    val sa = matchFactors.score2
                    val matchTime = matchFactors.matchTime  // Теперь в минутах
                    
                    val db = dbHelper.writableDatabase
                    
                    db.update("express_events", ContentValues().apply {
                        put("home_score", sh)
                        put("away_score", sa)
                        put("match_time", matchTime)
                        put("updated_at", System.currentTimeMillis() / 1000)
                    }, "id = ?", arrayOf(event.id.toString()))
                    
                    event.homeScore = sh
                    event.awayScore = sa
                    event.matchTime = matchTime
                    
                    val newStatus = checkMatchStatus(event.betType, sh, sa)
                    
                    if (newStatus != event.status) {
                        db.update("express_events", ContentValues().apply {
                            put("status", newStatus)
                            put("updated_at", System.currentTimeMillis() / 1000)
                        }, "id = ?", arrayOf(event.id.toString()))
                        
                        event.status = newStatus
                        val statusText = if (newStatus == 2) "ЗАШЁЛ ✅" else "НЕ ЗАШЁЛ ❌"
                        val message = "📊 Матч #${event.mId}: $sh-$sa (${matchTime}') | $statusText"
                        onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                        onScoresUpdate?.invoke(message)
                        dbHelper.addLog(userId, "match_status", 
                            "Матч ${event.mId}: $sh-$sa, время: ${matchTime}', статус: $newStatus")
                        checkExpressStatus(expressId, expId, userId)
                    }
                } else {
                    checkMatchTimeout(event, expressId, expId, userId)
                }
            },
            onError = { error: String ->
                Log.e(TAG, "Ошибка получения счета: $error")
                checkMatchTimeout(event, expressId, expId, userId)
            }
        )
    }
    
    private fun checkMatchTimeout(event: EventInfo, expressId: Long, expId: Int, userId: Long) {
        val db = dbHelper.writableDatabase
        val currentTime = System.currentTimeMillis() / 1000
        
        val cursor = db.query("express_events", 
            arrayOf("match_time", "created_at", "home_score", "away_score"),
            "id = ?", arrayOf(event.id.toString()), null, null, null)
        
        var matchTime = event.matchTime
        var createdAt = 0L
        var homeScore = event.homeScore
        var awayScore = event.awayScore
        
        if (cursor.moveToFirst()) {
            matchTime = cursor.getInt(0)
            createdAt = cursor.getLong(1)
            homeScore = cursor.getInt(2)
            awayScore = cursor.getInt(3)
        }
        cursor.close()
        
        val matchAge = currentTime - createdAt
        if ((matchAge > 5400 && matchTime >= 90) || matchAge > 7200) {
            val status = checkMatchStatus(event.betType, homeScore, awayScore)
            
            db.update("express_events", ContentValues().apply {
                put("status", status)
                put("is_finalized", 1)
                put("updated_at", currentTime)
            }, "id = ?", arrayOf(event.id.toString()))
            
            val statusText = if (status == 2) "ЗАШЁЛ ✅" else "НЕ ЗАШЁЛ ❌"
            val message = "⏰ Матч #${event.mId} завершен (${matchTime}'): $homeScore-$awayScore | $statusText"
            onLogUpdate?.invoke("[${getCurrentTime()}] $message")
            onScoresUpdate?.invoke(message)
            dbHelper.addLog(userId, "match_finished", 
                "Матч ${event.mId} финализирован: $statusText (${matchTime}')")
            checkExpressStatus(expressId, expId, userId)
        }
    }
    
    private fun checkMatchStatus(betType: Int, sh: Int, sa: Int): Int = when (betType) {
        924 -> if (sh >= sa) 2 else 1
        927 -> if (sh + 1 >= sa) 2 else 1
        928 -> if (sa + 1 >= sh) 2 else 1
        else -> 1
    }
    
    private fun checkExpressStatus(expressId: Long, expId: Int, userId: Long) {
        val db = dbHelper.writableDatabase
        val cursor = db.query("express_events", arrayOf("status", "is_finalized"),
            "express_id = ?", arrayOf(expressId.toString()), null, null, null)
        
        var anyLost = false; var allWin = true; var hasUnfinished = false; var totalEvents = 0
        while (cursor.moveToNext()) {
            totalEvents++
            if (cursor.getInt(1) == 0) hasUnfinished = true
            if (cursor.getInt(0) == 1) anyLost = true
            if (cursor.getInt(0) != 2) allWin = false
        }
        cursor.close()
        
        val expressStatus = if (hasUnfinished) 0 else when { anyLost -> 1; allWin -> 2; else -> 0 }
        
        val expressCursor = db.query("express_bets", arrayOf("sumbet", "kfall", "sts_all"),
            "id = ?", arrayOf(expressId.toString()), null, null, null)
        
        var betAmount = 0.0; var totalKef = 0.0; var oldStatus = 0
        if (expressCursor.moveToFirst()) {
            betAmount = expressCursor.getDouble(0); totalKef = expressCursor.getDouble(1); oldStatus = expressCursor.getInt(2)
        }
        expressCursor.close()
        
        val profitLoss = when (expressStatus) { 2 -> betAmount * totalKef - betAmount; 1 -> -betAmount; else -> 0.0 }
        
        if (expressStatus != oldStatus) {
            db.update("express_bets", ContentValues().apply {
                put("sts_all", expressStatus)
                put("profloss", profitLoss)
                put("updated_at", System.currentTimeMillis() / 1000)
            }, "id = ?", arrayOf(expressId.toString()))
            
            val statusText = when (expressStatus) { 2 -> "ВЫИГРАЛ 🏆"; 1 -> "ПРОИГРАЛ ❌"; else -> "АКТИВЕН 🔄" }
            val message = "🎯 Экспресс #$expId ($totalEvents матчей) $statusText | ${if (expressStatus == 2) "+" else ""}${"%.2f".format(profitLoss)} ₽"
            onLogUpdate?.invoke("[${getCurrentTime()}] $message")
            onScoresUpdate?.invoke(message)
            dbHelper.addLog(userId, when (expressStatus) { 2 -> "express_win"; 1 -> "express_loss"; else -> "express_active" },
                "Экспресс #$expId $statusText, результат: ${"%.2f".format(profitLoss)} ₽")
        }
    }
    
    // ==================== МЕТОД ЗАМЕНЫ ЭКСПРЕССА ====================
    
    private fun checkAndCreateReplacementExpress() {
        val db = dbHelper.writableDatabase
        val currentTime = System.currentTimeMillis() / 1000
        val twelveHoursAgo = currentTime - (12 * 3600)
        
        val cursor = db.rawQuery("SELECT id, id_exp, user_id, sumbet FROM express_bets WHERE sts_all = 1 AND ct < ? AND id_exp_replace = 0 ORDER BY ct ASC LIMIT 1",
            arrayOf(twelveHoursAgo.toString()))
        
        if (cursor.moveToFirst()) {
            val oldExpressId = cursor.getLong(0); val oldExpId = cursor.getInt(1)
            val userId = cursor.getLong(2); val currentBetAmount = cursor.getDouble(3)
            cursor.close()
            
            val multiply = prefs.getInt("multiply", 2)
            val initialBet = prefs.getString("bet_amount", "30")?.toDoubleOrNull() ?: 30.0
            val maxBetMultiplier = prefs.getInt("max_bet_multiplier", 3)
            val maxAllowedBet = initialBet * maxBetMultiplier
            var newBetAmount = currentBetAmount * multiply
            
            if (newBetAmount > maxAllowedBet) {
                newBetAmount = initialBet + 1.0
                onLogUpdate?.invoke("[${getCurrentTime()}] ⚠️ Ставка превысила лимит (${maxAllowedBet.toInt()} ₽), сброс до начальной + 1: ${newBetAmount.toInt()} ₽")
                dbHelper.addLog(userId, "bet_reset", "Ставка сброшена до ${newBetAmount.toInt()} ₽")
            }
            
            val eventsCursor = db.query("express_events", arrayOf("m_id", "bet_type"),
                "express_id = ?", arrayOf(oldExpressId.toString()), null, null, null)
            val bets = mutableListOf<Pair<Int, Int>>()
            while (eventsCursor.moveToNext()) bets.add(Pair(eventsCursor.getInt(0), eventsCursor.getInt(1)))
            eventsCursor.close()
            
            if (bets.isNotEmpty()) {
                val newExpId = (System.currentTimeMillis() / 1000).toInt()
                onLogUpdate?.invoke("[${getCurrentTime()}] 🔄 Замена экспресса #$oldExpId → #$newExpId (ставка: ${currentBetAmount.toInt()} → ${newBetAmount.toInt()} ₽)")
                
                // Обновляем сумму ставки в prefs для нового экспресса
                prefs.edit().putString("bet_amount", newBetAmount.toInt().toString()).apply()
                
                // TODO: здесь нужно запросить новый экспресс через getBets с новой суммой
                // или переиспользовать матчи старого экспресса с новой суммой
                
                db.update("express_bets", ContentValues().apply {
                    put("sts_all", -1); put("id_exp_replace", newExpId); put("updated_at", currentTime)
                }, "id = ?", arrayOf(oldExpressId.toString()))
                
                dbHelper.addLog(userId, "express_replaced", "Экспресс #$oldExpId заменен на #$newExpId, ставка: ${newBetAmount.toInt()} ₽")
            }
        } else cursor.close()
    }
    
    // ==================== СЛУЖЕБНЫЕ МЕТОДЫ ====================
    
    private fun stopBot() {
        isRunning = false
        authData?.let { data ->
            try {
                val user = dbHelper.getUser(data.fsid, data.deviceId)
                user?.let {
                    dbHelper.stopBotSession(it.id, "user_stop")
                    dbHelper.addLog(it.id, "stop", "Бот остановлен")
                }
            } catch (e: Exception) { }
        }
        onLogUpdate?.invoke("[${getCurrentTime()}] ⏹ Бот остановлен")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Фонбет Бот", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Статус работы бота Фонбет"; setShowBadge(true)
                })
        }
    }
    
    private fun createBetNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("bet_channel", "Ставки", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Уведомления о размещении ставок"; setShowBadge(true)
                })
        }
    }
    
    private fun createNotification(title: String, content: String): Notification {
        val stopIntent = Intent(this, BotForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(this, 0, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(content)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(appPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }
    
    private fun createBetNotification(title: String, content: String): Notification {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(this, 0, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, "bet_channel")
            .setContentTitle(title).setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentIntent(appPendingIntent).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
    }
    
    private fun getCurrentTime(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    
    private fun typeName(type: Int): String = when (type) {
        924 -> "1X"; 927 -> "Ф1(+1.5)"; 928 -> "Ф2(+1.5)"; else -> "Тип $type"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    // Data classes
    data class EventInfo(val id: Long, val mId: Int, val betType: Int, var status: Int,
                         var homeScore: Int, var awayScore: Int, var matchTime: Int = 0)
    
    data class MatchFactorsData(val mId: Int, val type: Int, val kef: Double, val handicap: Double,
                                 val sh: Int, val sa: Int, val matchTime: Int = 0,
                                 val leagueName: String = "", val homeTeam: String = "", val awayTeam: String = "",
                                 val idLiga: Int = 0, val idHome: Int = 0, val idAway: Int = 0,
                                 val url: String = "", val uzh: String = "0.0", val tbType: Int = 0)
    
    data class ActiveMatch(val id: Long, val expressId: Long, val mId: Int, val createdAt: Long,
                           val homeScore: Int, val awayScore: Int, val betType: Int, val status: Int, val matchTime: Int)
}