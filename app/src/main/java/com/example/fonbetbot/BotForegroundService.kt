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
            } catch (e: Exception) {
                // Игнорируем
            }
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
                    dbHelper.addLog(it.id, "start", "Бот запущен в фоновом режиме")
                }
            } catch (e: Exception) {
                // Игнорируем
            }
        }
        
        // Цикл обновления баланса (раз в минуту)
        serviceScope.launch {
            while (isRunning) {
                delay(60000)
                authData?.let { data ->
                    fetchBalance(data)
                }
            }
        }
        
        // Цикл получения ставок (раз в 30 секунд)
        serviceScope.launch {
            delay(5000)
            while (isRunning) {
                authData?.let { data ->
                    fetchBets(data)
                }
                delay(30000)
            }
        }
        
        // Цикл обновления счетов (раз в минуту)
        serviceScope.launch {
            delay(10000)
            while (isRunning) {
                updateActiveMatchesScores()
                delay(60000)
            }
        }
    }
    
    private fun fetchBalance(data: AuthData) {
        val cookieManager = CookieManager.getInstance()
        val cookieString = cookieManager.getCookie("https://www.fon.bet") ?: ""
        
        val cookies: Map<String, String> = if (cookieString.isNotEmpty()) {
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
            onSuccess = { saldo ->
                if (saldo != null) {
                    val oldBalance = balance
                    val difference = saldo - oldBalance
                    balance = saldo
                    lastBalance = saldo
                    
                    onBalanceUpdate?.invoke(saldo)
                    
                    try {
                        val user = dbHelper.getUser(data.fsid, data.deviceId)
                        user?.let {
                            dbHelper.saveBalance(it.id, saldo, "success")
                            
                            if (difference > 0 && oldBalance > 0) {
                                dbHelper.addLog(it.id, "profit", "Профит: +%.2f ₽".format(difference))
                            } else if (difference < 0 && oldBalance > 0) {
                                dbHelper.addLog(it.id, "loss", "Убыток: %.2f ₽".format(difference))
                            }
                        }
                    } catch (e: Exception) {
                        // Игнорируем
                    }
                    
                    val notification = createNotification("Бот активен", "Баланс: %.2f ₽".format(saldo))
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
                    
                    val timestamp = getCurrentTime()
                    if (difference > 0 && oldBalance > 0) {
                        onLogUpdate?.invoke("[$timestamp] 💰 Профит: +%.2f ₽".format(difference))
                    } else if (difference < 0 && oldBalance > 0) {
                        onLogUpdate?.invoke("[$timestamp] 📉 Убыток: %.2f ₽".format(difference))
                    }
                }
            },
            onError = { error ->
                onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ошибка API: $error")
                
                try {
                    val user = dbHelper.getUser(data.fsid, data.deviceId)
                    user?.let {
                        dbHelper.addLog(it.id, "error", "Ошибка API: $error", "ERROR")
                    }
                } catch (e: Exception) {
                    // Игнорируем
                }
            }
        )
    }
    
    private fun fetchBets(data: AuthData) {
        try {
            val user = dbHelper.getUser(data.fsid, data.deviceId)
            user?.let { userId ->
                val settings = ApiClient.BetSettings(
                    maxMatchesPerExpress = prefs.getInt("max_matches_per_express", 2),
                    multiply = prefs.getInt("multiply", 2),
                    allMinKef = prefs.getFloat("all_min_kef", 1.67f).toDouble(),
                    types = mapOf(
                        924 to ApiClient.TypeSettings(
                            "1х/футбол/хоккей",
                            prefs.getFloat("type_924_min", 1.15f).toDouble(),
                            prefs.getFloat("type_924_max", 1.35f).toDouble(),
                            prefs.getInt("type_924_start", 80),
                            prefs.getInt("type_924_end", 100)
                        ),
                        927 to ApiClient.TypeSettings(
                            "ф1(+1.5)/футбол/хоккей",
                            prefs.getFloat("type_927_min", 1.15f).toDouble(),
                            prefs.getFloat("type_927_max", 1.35f).toDouble(),
                            prefs.getInt("type_927_start", 1),
                            prefs.getInt("type_927_end", 45)
                        ),
                        928 to ApiClient.TypeSettings(
                            "ф2(+1.5)/футбол/хоккей",
                            prefs.getFloat("type_928_min", 1.15f).toDouble(),
                            prefs.getFloat("type_928_max", 1.35f).toDouble(),
                            prefs.getInt("type_928_start", 1),
                            prefs.getInt("type_928_end", 45)
                        )
                    )
                )
                
                apiClient.getBets(
                    userId = userId.id,
                    settings = settings,
                    onSuccess = { bets ->
                        if (bets.isNotEmpty()) {
                            val expressGroups = bets.chunked(2)
                            
                            expressGroups.forEachIndexed { index, group ->
                                if (group.size == 2) {
                                    val bet1 = group[0]
                                    val bet2 = group[1]
                                    
                                    val existingExpress = findExistingExpress(userId.id, group)
                                    
                                    if (existingExpress != null) {
                                        Log.d("BotForegroundService", "Экспресс #${existingExpress.first} уже существует, мониторим")
                                        checkExpressMatchesStatus(existingExpress.second, existingExpress.first, userId.id)
                                    } else {
                                        val newExpId = (System.currentTimeMillis() / 1000).toInt() + index
                                        
                                        val message = "🎯 Новый экспресс #$newExpId: m_id=${bet1.first} (${bet1.second}) + m_id=${bet2.first} (${bet2.second})"
                                        onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                                        onBetsUpdate?.invoke(group)
                                        
                                        saveExpressToDb(userId.id, newExpId, group)
                                    }
                                }
                            }
                            
                            checkCompletedMatches(userId.id)
                        }
                    },
                    onError = { error ->
                        Log.e("BotForegroundService", "Ошибка получения ставок: $error")
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("BotForegroundService", "Ошибка в fetchBets: ${e.message}")
        }
    }
    
    private fun findExistingExpress(userId: Long, bets: List<Pair<Int, Int>>): Pair<Int, Long>? {
        val db = dbHelper.readableDatabase
        
        val cursor = db.query(
            "express_bets",
            arrayOf("id", "id_exp"),
            "user_id = ? AND sts_all = 1",
            arrayOf(userId.toString()),
            null, null, null
        )
        
        val activeExpresses = mutableListOf<Pair<Long, Int>>()
        while (cursor.moveToNext()) {
            activeExpresses.add(Pair(cursor.getLong(0), cursor.getInt(1)))
        }
        cursor.close()
        
        for ((expressId, expId) in activeExpresses) {
            val eventsCursor = db.query(
                "express_events",
                arrayOf("m_id", "bet_type"),
                "express_id = ?",
                arrayOf(expressId.toString()),
                null, null, null
            )
            
            val existingBets = mutableListOf<Pair<Int, Int>>()
            while (eventsCursor.moveToNext()) {
                existingBets.add(Pair(eventsCursor.getInt(0), eventsCursor.getInt(1)))
            }
            eventsCursor.close()
            
            if (existingBets.size == bets.size) {
                val sortedExisting = existingBets.sortedBy { it.first }
                val sortedNew = bets.sortedBy { it.first }
                
                if (sortedExisting == sortedNew) {
                    return Pair(expId, expressId)
                }
            }
        }
        
        return null
    }
    
    private fun checkExpressMatchesStatus(expressId: Long, expId: Int, userId: Long) {
        val db = dbHelper.readableDatabase
        
        val eventsCursor = db.query(
            "express_events",
            arrayOf("id", "m_id", "bet_type", "status", "home_score", "away_score"),
            "express_id = ? AND status = 1",
            arrayOf(expressId.toString()),
            null, null, null
        )
        
        val events = mutableListOf<EventInfo>()
        while (eventsCursor.moveToNext()) {
            events.add(EventInfo(
                id = eventsCursor.getLong(0),
                mId = eventsCursor.getInt(1),
                betType = eventsCursor.getInt(2),
                status = eventsCursor.getInt(3),
                homeScore = eventsCursor.getInt(4),
                awayScore = eventsCursor.getInt(5)
            ))
        }
        eventsCursor.close()
        
        events.forEach { event ->
            updateMatchScore(event, expressId, expId, userId)
        }
        
        checkExpressStatus(expressId, expId, userId)
    }
    
    private fun saveExpressToDb(userId: Long, expId: Int, bets: List<Pair<Int, Int>>) {
        try {
            val currentTimeUtc = System.currentTimeMillis() / 1000
            val betAmount = prefs.getString("bet_amount", "100")?.toDoubleOrNull() ?: 100.0
            
            fetchFactorsForBets(userId, expId, bets, betAmount, currentTimeUtc.toLong())
            
        } catch (e: Exception) {
            Log.e("BotForegroundService", "Ошибка сохранения экспресса: ${e.message}")
        }
    }
    
    private fun fetchFactorsForBets(
        userId: Long, 
        expId: Int, 
        bets: List<Pair<Int, Int>>, 
        betAmount: Double, 
        currentTimeUtc: Long
    ) {
        val matchesFactors = mutableListOf<MatchFactorsData>()
        var completedRequests = 0
        
        bets.forEach { (mId, type) ->
            apiClient.getMatchScore(
                matchId = mId,
                onSuccess = { matchFactors ->
                    val kef: Double
                    val handicap: Double
                    val sh: Int
                    val sa: Int
                    
                    if (matchFactors != null) {
                        kef = matchFactors.factors[type] ?: when (type) {
                            924 -> 1.67
                            927 -> 1.85
                            928 -> 1.85
                            else -> 1.5
                        }
                        handicap = matchFactors.handicaps[type] ?: 0.0
                        sh = matchFactors.score1
                        sa = matchFactors.score2
                    } else {
                        kef = when (type) {
                            924 -> 1.67
                            927 -> 1.85
                            928 -> 1.85
                            else -> 1.5
                        }
                        handicap = 0.0
                        sh = 0
                        sa = 0
                    }
                    
                    matchesFactors.add(MatchFactorsData(
                        mId = mId,
                        type = type,
                        kef = kef,
                        handicap = handicap,
                        sh = sh,
                        sa = sa
                    ))
                    
                    completedRequests++
                    if (completedRequests == bets.size) {
                        saveExpressWithFactors(userId, expId, matchesFactors, betAmount, currentTimeUtc)
                    }
                },
                onError = { _ ->
                    val kef = when (type) {
                        924 -> 1.67
                        927 -> 1.85
                        928 -> 1.85
                        else -> 1.5
                    }
                    
                    matchesFactors.add(MatchFactorsData(
                        mId = mId,
                        type = type,
                        kef = kef,
                        handicap = 0.0,
                        sh = 0,
                        sa = 0
                    ))
                    
                    completedRequests++
                    if (completedRequests == bets.size) {
                        saveExpressWithFactors(userId, expId, matchesFactors, betAmount, currentTimeUtc)
                    }
                }
            )
        }
    }
    
    private fun saveExpressWithFactors(
        userId: Long,
        expId: Int,
        matchesFactors: List<MatchFactorsData>,
        betAmount: Double,
        currentTimeUtc: Long
    ) {
        val db = dbHelper.writableDatabase
        
        var totalKef = 1.0
        matchesFactors.forEach { totalKef *= it.kef }
        
        val values = ContentValues().apply {
            put("user_id", userId)
            put("id_exp", expId)
            put("kfall", totalKef)
            put("profloss", 0.0)
            put("balans", balance)
            put("sumbet", betAmount)
            put("sts_all", 1)
            put("ct", currentTimeUtc)
            put("strategy", "0.0")
            put("id_exp_replace", 0)
            put("events_count", matchesFactors.size)
            put("total_odds", totalKef)
            put("bet_amount", betAmount)
            put("potential_win", betAmount * totalKef)
            put("balance", balance)
            put("created_time", currentTimeUtc)
        }
        
        val expressId = db.insert("express_bets", null, values)
        
        matchesFactors.forEach { match ->
            val eventValues = ContentValues().apply {
                put("express_id", expressId)
                put("id_exp", expId)
                put("user_id", userId)
                put("m_id", match.mId)
                put("start_odds", match.kef)
                put("bet_type", match.type)
                put("status", 1)
                put("home_score", match.sh)
                put("away_score", match.sa)
                put("created_at", currentTimeUtc)
                put("uzh", "0.0")
            }
            
            db.insert("express_events", null, eventValues)
        }
        
        val kefInfo = matchesFactors.joinToString(" + ") { 
            "m_id=${it.mId}:${it.type}(${"%.2f".format(it.kef)})" 
        }
        val message = "✅ Экспресс #$expId сохранен: $kefInfo | Общий кэф: ${"%.2f".format(totalKef)}"
        
        onLogUpdate?.invoke("[${getCurrentTime()}] $message")
        Log.d("BotForegroundService", "Экспресс #$expId сохранен с коэффициентами из API")
    }
    
    private fun updateActiveMatchesScores() {
        try {
            val db = dbHelper.readableDatabase
            
            val cursor = db.query(
                "express_bets",
                arrayOf("id", "id_exp", "user_id"),
                "sts_all = 1",
                null, null, null, null
            )
            
            val activeExpresses = mutableListOf<Triple<Long, Int, Long>>()
            while (cursor.moveToNext()) {
                activeExpresses.add(Triple(
                    cursor.getLong(0),
                    cursor.getInt(1),
                    cursor.getLong(2)
                ))
            }
            cursor.close()
            
            activeExpresses.forEach { (expressId, expId, userId) ->
                val eventsCursor = db.query(
                    "express_events",
                    arrayOf("id", "m_id", "bet_type", "status", "home_score", "away_score"),
                    "express_id = ? AND status = 1",
                    arrayOf(expressId.toString()),
                    null, null, null
                )
                
                val events = mutableListOf<EventInfo>()
                while (eventsCursor.moveToNext()) {
                    events.add(EventInfo(
                        id = eventsCursor.getLong(0),
                        mId = eventsCursor.getInt(1),
                        betType = eventsCursor.getInt(2),
                        status = eventsCursor.getInt(3),
                        homeScore = eventsCursor.getInt(4),
                        awayScore = eventsCursor.getInt(5)
                    ))
                }
                eventsCursor.close()
                
                events.forEach { event ->
                    updateMatchScore(event, expressId, expId, userId)
                }
                
                checkExpressStatus(expressId, expId, userId)
            }
        } catch (e: Exception) {
            Log.e("BotForegroundService", "Ошибка обновления счетов: ${e.message}")
        }
    }
    
    private fun updateMatchScore(event: EventInfo, expressId: Long, expId: Int, userId: Long) {
        apiClient.getMatchScore(
            matchId = event.mId,
            onSuccess = { matchFactors ->
                if (matchFactors != null) {
                    val sh = matchFactors.score1
                    val sa = matchFactors.score2
                    
                    if (sh != event.homeScore || sa != event.awayScore) {
                        val db = dbHelper.writableDatabase
                        val values = ContentValues().apply {
                            put("home_score", sh)
                            put("away_score", sa)
                            put("updated_at", System.currentTimeMillis() / 1000)
                        }
                        db.update("express_events", values, "id = ?", arrayOf(event.id.toString()))
                        
                        event.homeScore = sh
                        event.awayScore = sa
                        
                        val newStatus = checkMatchStatus(event.betType, sh, sa)
                        
                        if (newStatus != event.status) {
                            val statusValues = ContentValues().apply {
                                put("status", newStatus)
                                put("updated_at", System.currentTimeMillis() / 1000)
                            }
                            db.update("express_events", statusValues, "id = ?", arrayOf(event.id.toString()))
                            
                            event.status = newStatus
                            
                            val statusText = if (newStatus == 2) "ЗАШЁЛ ✅" else "НЕ ЗАШЁЛ ❌"
                            val message = "📊 Матч #${event.mId}: $sh-$sa | $statusText"
                            
                            onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                            onScoresUpdate?.invoke(message)
                            
                            dbHelper.addLog(userId, "match_status", "Матч ${event.mId}: $sh-$sa, статус: $newStatus")
                            
                            checkExpressStatus(expressId, expId, userId)
                        }
                    }
                }
            },
            onError = { error ->
                Log.e("BotForegroundService", "Ошибка получения счета: $error")
            }
        )
    }
    
    private fun checkMatchStatus(betType: Int, sh: Int, sa: Int): Int {
        return when (betType) {
            924 -> if (sh >= sa) 2 else 1
            927 -> if (sh + 1 >= sa) 2 else 1
            928 -> if (sa + 1 >= sh) 2 else 1
            else -> 1
        }
    }
    
    private fun checkExpressStatus(expressId: Long, expId: Int, userId: Long) {
        val db = dbHelper.writableDatabase
        
        val cursor = db.query(
            "express_events",
            arrayOf("status"),
            "express_id = ?",
            arrayOf(expressId.toString()),
            null, null, null
        )
        
        var allFinished = true
        var anyLost = false
        
        while (cursor.moveToNext()) {
            val status = cursor.getInt(0)
            if (status == 1) {
                allFinished = false
            }
            if (status == 1) {
                anyLost = true
            }
        }
        cursor.close()
        
        if (allFinished || anyLost) {
            val expressStatus = if (anyLost) 1 else 2
            
            val expressCursor = db.query(
                "express_bets",
                arrayOf("sumbet", "kfall"),
                "id = ?",
                arrayOf(expressId.toString()),
                null, null, null
            )
            
            var betAmount = 0.0
            var totalKef = 0.0
            
            if (expressCursor.moveToFirst()) {
                betAmount = expressCursor.getDouble(0)
                totalKef = expressCursor.getDouble(1)
            }
            expressCursor.close()
            
            val profitLoss = if (expressStatus == 2) {
                betAmount * totalKef - betAmount
            } else {
                -betAmount
            }
            
            val values = ContentValues().apply {
                put("sts_all", expressStatus)
                put("profloss", profitLoss)
                put("updated_at", System.currentTimeMillis() / 1000)
            }
            db.update("express_bets", values, "id = ?", arrayOf(expressId.toString()))
            
            val statusText = if (expressStatus == 2) "ВЫИГРАЛ 🏆" else "ПРОИГРАЛ ❌"
            val message = "🎯 Экспресс #$expId $statusText | ${if (expressStatus == 2) "+" else ""}${"%.2f".format(profitLoss)} ₽"
            
            onLogUpdate?.invoke("[${getCurrentTime()}] $message")
            onScoresUpdate?.invoke(message)
            
            dbHelper.addLog(
                userId,
                if (expressStatus == 2) "express_win" else "express_loss",
                "Экспресс #$expId $statusText, результат: ${"%.2f".format(profitLoss)} ₽"
            )
        }
    }
    
    private fun checkCompletedMatches(userId: Long) {
        val db = dbHelper.writableDatabase
        val currentTime = System.currentTimeMillis() / 1000
        
        val cursor = db.query(
            "express_events",
            arrayOf("id", "express_id", "m_id", "match_time", "created_at", "home_score", "away_score", "bet_type"),
            "status = 1",
            null, null, null, null
        )
        
        val activeMatches = mutableListOf<ActiveMatch>()
        while (cursor.moveToNext()) {
            activeMatches.add(ActiveMatch(
                id = cursor.getLong(0),
                expressId = cursor.getLong(1),
                mId = cursor.getInt(2),
                matchTime = cursor.getInt(3),
                createdAt = cursor.getLong(4),
                homeScore = cursor.getInt(5),
                awayScore = cursor.getInt(6),
                betType = cursor.getInt(7)
            ))
        }
        cursor.close()
        
        for (match in activeMatches) {
            val matchAge = currentTime - match.createdAt
            
            if (matchAge > 5400) {
                apiClient.getMatchScore(
                    matchId = match.mId,
                    onSuccess = { score ->
                        if (score == null) {
                            markMatchAsCompleted(match)
                        }
                    },
                    onError = { _ ->
                        markMatchAsCompleted(match)
                    }
                )
            }
        }
    }
    
    private fun markMatchAsCompleted(match: ActiveMatch) {
        val db = dbHelper.writableDatabase
        
        val status = checkMatchStatus(match.betType, match.homeScore, match.awayScore)
        
        val values = ContentValues().apply {
            put("status", status)
            put("updated_at", System.currentTimeMillis() / 1000)
        }
        db.update("express_events", values, "id = ?", arrayOf(match.id.toString()))
        
        val statusText = if (status == 2) "ЗАШЁЛ ✅" else "НЕ ЗАШЁЛ ❌"
        val message = "🏁 Матч #${match.mId} завершен: ${match.homeScore}-${match.awayScore} | $statusText"
        
        onLogUpdate?.invoke("[${getCurrentTime()}] $message")
        onScoresUpdate?.invoke(message)
        
        val expressCursor = db.query(
            "express_bets",
            arrayOf("id_exp", "user_id"),
            "id = ?",
            arrayOf(match.expressId.toString()),
            null, null, null
        )
        
        var expId = 0
        var userId = 0L
        
        if (expressCursor.moveToFirst()) {
            expId = expressCursor.getInt(0)
            userId = expressCursor.getLong(1)
        }
        expressCursor.close()
        
        checkExpressStatus(match.expressId, expId, userId)
    }
    
    private fun stopBot() {
        isRunning = false
        
        authData?.let { data ->
            try {
                val user = dbHelper.getUser(data.fsid, data.deviceId)
                user?.let {
                    dbHelper.stopBotSession(it.id, "user_stop")
                    dbHelper.addLog(it.id, "stop", "Бот остановлен")
                }
            } catch (e: Exception) {
                // Игнорируем
            }
        }
        
        onLogUpdate?.invoke("[${getCurrentTime()}] ⏹ Бот остановлен")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фонбет Бот",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус работы бота Фонбет"
                setShowBadge(true)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(title: String, content: String): Notification {
        val stopIntent = Intent(this, BotForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            this, 0, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(appPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun getCurrentTime(): String {
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    data class EventInfo(
        val id: Long,
        val mId: Int,
        val betType: Int,
        var status: Int,
        var homeScore: Int,
        var awayScore: Int
    )
    
    data class MatchFactorsData(
        val mId: Int,
        val type: Int,
        val kef: Double,
        val handicap: Double,
        val sh: Int,
        val sa: Int
    )
    
    data class ActiveMatch(
        val id: Long,
        val expressId: Long,
        val mId: Int,
        val matchTime: Int,
        val createdAt: Long,
        val homeScore: Int,
        val awayScore: Int,
        val betType: Int
    )
}