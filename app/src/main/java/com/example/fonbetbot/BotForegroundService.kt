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
        
        serviceScope.launch {
            while (isRunning) {
                delay(60000)
                authData?.let { data -> fetchBalance(data) }
            }
        }
        
        serviceScope.launch {
            delay(5000)
            while (isRunning) {
                authData?.let { data -> fetchBets(data) }
                delay(30000)
            }
        }
        
        serviceScope.launch {
            delay(10000)
            while (isRunning) {
                updateActiveMatchesScores()
                delay(60000)
            }
        }
        
        serviceScope.launch {
            delay(60000)
            while (isRunning) {
                checkAndCreateReplacementExpress()
                delay(3600000)
            }
        }
    }
    
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
                                    dbHelper.updateUserInfo(userData.id, userClientId, userDisplayName)
                                }
                                
                                if (difference > 0 && oldBalance > 0) {
                                    dbHelper.addLog(userData.id, "profit", "Профит: +%.2f ₽".format(difference))
                                } else if (difference < 0 && oldBalance > 0) {
                                    dbHelper.addLog(userData.id, "loss", "Убыток: %.2f ₽".format(difference))
                                } else {dbHelper.addLog(userData.id, "loss", "Убыток: %.2f ₽".format(difference))}
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
                            val bets = betDataList.map { betData: ApiClient.BetData -> Pair(betData.mId, betData.type) }
                            
                            onLogUpdate?.invoke("[${getCurrentTime()}] 🎲 Получен сигнал на создание ставки! (${bets.size} матчей)")
                            
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
                                    append("Тип: ${typeName(betData.type)} ")
                                }
                                onLogUpdate?.invoke("[${getCurrentTime()}] $matchInfo")
                            }
                            
                            val maxMatches = settings.maxMatchesPerExpress
                            val expressGroups = bets.chunked(maxMatches)
                            
                            onLogUpdate?.invoke("[${getCurrentTime()}] 📨 Сгруппировано в ${expressGroups.size} экспрессов (по $maxMatches матча)")
                            
                            expressGroups.forEachIndexed { index: Int, group: List<Pair<Int, Int>> ->
                                if (group.isNotEmpty()) {
                                    val existingExpress = findExistingExpress(userData.id, group)
                                    
                                    if (existingExpress != null) {
                                        Log.d(TAG, "Экспресс #${existingExpress.first} уже существует, мониторим")
                                        checkExpressMatchesStatus(existingExpress.second, existingExpress.first, userData.id)
                                    } else {
                                        val newExpId = (System.currentTimeMillis() / 1000).toInt() + index
                                        
                                        val groupBetData = betDataList.filter { betData: ApiClient.BetData ->
                                            group.any { it.first == betData.mId }
                                        }
                                        
                                        val matchesInfo = groupBetData.joinToString(" + ") { betData: ApiClient.BetData ->
                                            "${betData.home.take(10)} vs ${betData.away.take(10)} (${typeName(betData.type)})" 
                                        }
                                        val message = "🎯 Новый экспресс #$newExpId (${group.size} матчей): $matchesInfo"
                                        
                                        onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                                        onBetsUpdate?.invoke(group)
                                        
                                        saveExpressToDb(userData.id, newExpId, group, null, groupBetData)
                                        placeBet(userData.id, newExpId, group)
                                    }
                                }
                            }
                            
                            checkCompletedMatches(userData.id)
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
    
    private fun placeBet(userId: Long, expId: Int, bets: List<Pair<Int, Int>>) {
        try {
            val db = dbHelper.writableDatabase
            
            val cursor = db.query("express_bets", arrayOf("id", "sumbet", "kfall", "potential_win"),
                "id_exp = ? AND user_id = ?", arrayOf(expId.toString(), userId.toString()), null, null, null)
            
            var expressId = 0L
            var betAmount = 0.0
            var totalKef = 0.0
            var potentialWin = 0.0
            
            if (cursor.moveToFirst()) {
                expressId = cursor.getLong(0)
                betAmount = cursor.getDouble(1)
                totalKef = cursor.getDouble(2)
                potentialWin = cursor.getDouble(3)
            }
            cursor.close()
            
            if (simulatePlaceBet(userId, expId, bets, betAmount, totalKef)) {
                val notification = createBetNotification("✅ Ставка сделана!",
                    "Экспресс #$expId | ${betAmount.toInt()} ₽ | Кэф: ${"%.2f".format(totalKef)} | Выигрыш: ${"%.2f".format(potentialWin)} ₽")
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify((NOTIFICATION_ID + expId).toInt(), notification)
                
                val message = "✅ Ставка #$expId принята! Сумма: ${betAmount.toInt()} ₽, Кэф: ${"%.2f".format(totalKef)}, Потенциальный выигрыш: ${"%.2f".format(potentialWin)} ₽"
                onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                onScoresUpdate?.invoke(message)
                
                dbHelper.addLog(userId, "bet_placed", "Ставка #$expId: ${betAmount.toInt()} ₽, кэф ${"%.2f".format(totalKef)}")
                
                val values = ContentValues().apply {
                    put("is_bet_placed", 1)
                    put("updated_at", System.currentTimeMillis() / 1000)
                }
                db.update("express_bets", values, "id = ?", arrayOf(expressId.toString()))
            } else {
                val message = "❌ Ошибка при размещении ставки #$expId"
                onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                dbHelper.addLog(userId, "bet_error", "Ошибка ставки #$expId", "ERROR")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в placeBet: ${e.message}")
        }
    }
    
    private fun simulatePlaceBet(userId: Long, expId: Int, bets: List<Pair<Int, Int>>, amount: Double, kef: Double): Boolean {
        Log.d(TAG, "🎲 SIMULATE PLACE BET: userId=$userId, expId=$expId, matches=${bets.size}, amount=$amount, kef=$kef")
        Thread.sleep(500)
        return Math.random() > 0.05
    }
    
    private fun findExistingExpress(userId: Long, bets: List<Pair<Int, Int>>): Pair<Int, Long>? {
        val db = dbHelper.readableDatabase
        
        val cursor = db.query("express_bets", arrayOf("id", "id_exp", "events_count"),
            "user_id = ? AND sts_all IN (0, 1, 2)", arrayOf(userId.toString()), null, null, null)
        
        val activeExpresses = mutableListOf<Triple<Long, Int, Int>>()
        while (cursor.moveToNext()) {
            activeExpresses.add(Triple(cursor.getLong(0), cursor.getInt(1), cursor.getInt(2)))
        }
        cursor.close()
        
        val sortedNewBets = bets.sortedBy { it.first }
        
        for ((expressId, expId, eventsCount) in activeExpresses) {
            if (eventsCount != bets.size) continue
            
            val eventsCursor = db.query("express_events", arrayOf("m_id", "bet_type"),
                "express_id = ?", arrayOf(expressId.toString()), null, null, null)
            
            val existingBets = mutableListOf<Pair<Int, Int>>()
            while (eventsCursor.moveToNext()) {
                existingBets.add(Pair(eventsCursor.getInt(0), eventsCursor.getInt(1)))
            }
            eventsCursor.close()
            
            if (existingBets.sortedBy { it.first } == sortedNewBets) {
                return Pair(expId, expressId)
            }
        }
        
        return null
    }
    
    private fun checkExpressMatchesStatus(expressId: Long, expId: Int, userId: Long) {
        val db = dbHelper.readableDatabase
        
        val eventsCursor = db.query("express_events",
            arrayOf("id", "m_id", "bet_type", "status", "home_score", "away_score", "match_time"),
            "express_id = ? AND is_finalized = 0", arrayOf(expressId.toString()), null, null, null)
        
        val events = mutableListOf<EventInfo>()
        while (eventsCursor.moveToNext()) {
            events.add(EventInfo(eventsCursor.getLong(0), eventsCursor.getInt(1), eventsCursor.getInt(2),
                eventsCursor.getInt(3), eventsCursor.getInt(4), eventsCursor.getInt(5), eventsCursor.getInt(6)))
        }
        eventsCursor.close()
        
        events.forEach { event: EventInfo -> updateMatchScore(event, expressId, expId, userId) }
        checkExpressStatus(expressId, expId, userId)
    }
    
    private fun saveExpressToDb(userId: Long, expId: Int, bets: List<Pair<Int, Int>>,
                                customBetAmount: Double? = null, betDataList: List<ApiClient.BetData> = emptyList()) {
        try {
            val currentTimeUtc = System.currentTimeMillis() / 1000
            val betAmount = customBetAmount ?: prefs.getString("bet_amount", "30")?.toDoubleOrNull() ?: 30.0
            val betDataMap: Map<Int, ApiClient.BetData> = betDataList.associateBy { it.mId }
            fetchFactorsForBets(userId, expId, bets, betAmount, currentTimeUtc, betDataMap)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения экспресса: ${e.message}")
        }
    }
    
    private fun fetchFactorsForBets(userId: Long, expId: Int, bets: List<Pair<Int, Int>>,
                                     betAmount: Double, currentTimeUtc: Long,
                                     betDataMap: Map<Int, ApiClient.BetData> = emptyMap()) {
        val matchesFactors = mutableListOf<MatchFactorsData>()
        var completedCount = 0
        
        bets.forEach { (mId: Int, type: Int) ->
            val betData = betDataMap[mId]
            
            apiClient.getMatchScore(matchId = mId,
                onSuccess = { matchFactors: ApiClient.MatchFactors? ->
                    val kef: Double
                    val handicap: Double
                    val sh: Int
                    val sa: Int
                    val matchTime: Int
                    
                    if (matchFactors != null) {
                        kef = matchFactors.factors[type] ?: betData?.startKf ?: when (type) {
                            924 -> 1.67; 927 -> 1.85; 928 -> 1.85; else -> 1.5
                        }
                        handicap = matchFactors.handicaps[type] ?: 0.0
                        sh = matchFactors.score1
                        sa = matchFactors.score2
                        matchTime = matchFactors.matchTime
                    } else {
                        kef = betData?.startKf ?: when (type) {
                            924 -> 1.67; 927 -> 1.85; 928 -> 1.85; else -> 1.5
                        }
                        handicap = 0.0; sh = betData?.sh ?: 0; sa = betData?.sa ?: 0; matchTime = 0
                    }
                    
                    matchesFactors.add(MatchFactorsData(mId, type, kef, handicap, sh, sa, matchTime,
                        betData?.ligaName ?: "", betData?.home ?: "", betData?.away ?: "",
                        betData?.idLiga ?: 0, betData?.comand1Id ?: 0, betData?.comand2Id ?: 0,
                        betData?.url ?: "", betData?.uzh?.toString() ?: "0.0", betData?.tbType ?: 0))
                    
                    completedCount++
                    if (completedCount == bets.size) saveExpressWithFactors(userId, expId, matchesFactors, betAmount, currentTimeUtc)
                },
                onError = { _: String ->
                    val kef = betData?.startKf ?: when (type) {
                        924 -> 1.67; 927 -> 1.85; 928 -> 1.85; else -> 1.5
                    }
                    matchesFactors.add(MatchFactorsData(mId, type, kef, 0.0, betData?.sh ?: 0, betData?.sa ?: 0, 0,
                        betData?.ligaName ?: "", betData?.home ?: "", betData?.away ?: "",
                        betData?.idLiga ?: 0, betData?.comand1Id ?: 0, betData?.comand2Id ?: 0,
                        betData?.url ?: "", betData?.uzh?.toString() ?: "0.0", betData?.tbType ?: 0))
                    
                    completedCount++
                    if (completedCount == bets.size) saveExpressWithFactors(userId, expId, matchesFactors, betAmount, currentTimeUtc)
                }
            )
        }
    }
    
    private fun saveExpressWithFactors(userId: Long, expId: Int, matchesFactors: List<MatchFactorsData>,
                                        betAmount: Double, currentTimeUtc: Long) {
        val db = dbHelper.writableDatabase
        
        var totalKef = 1.0
        matchesFactors.forEach { totalKef *= it.kef }
        
        val values = ContentValues().apply {
            put("user_id", userId); put("id_exp", expId); put("kfall", totalKef)
            put("profloss", 0.0); put("balans", balance); put("sumbet", betAmount)
            put("sts_all", 0); put("is_bet_placed", 1); put("ct", currentTimeUtc)
            put("strategy", "0.0"); put("id_exp_replace", 0); put("events_count", matchesFactors.size)
            put("total_odds", totalKef); put("bet_amount", betAmount)
            put("potential_win", betAmount * totalKef); put("balance", balance)
            put("created_time", currentTimeUtc)
        }
        
        val expressId = db.insert("express_bets", null, values)
        
        matchesFactors.forEach { match ->
            val initialStatus = if (match.sh > 0 || match.sa > 0) checkMatchStatus(match.type, match.sh, match.sa) else 0
            
            val eventValues = ContentValues().apply {
                put("express_id", expressId); put("id_exp", expId); put("user_id", userId)
                put("m_id", match.mId); put("start_odds", match.kef); put("bet_type", match.type)
                put("status", initialStatus); put("home_score", match.sh); put("away_score", match.sa)
                put("match_time", match.matchTime); put("created_at", currentTimeUtc)
                put("uzh", match.uzh); put("is_finalized", 0)
                if (match.idLiga > 0) put("id_liga", match.idLiga.toLong())
                if (match.leagueName.isNotEmpty()) put("league_name", match.leagueName)
                if (match.homeTeam.isNotEmpty()) put("home_team", match.homeTeam)
                if (match.awayTeam.isNotEmpty()) put("away_team", match.awayTeam)
                if (match.idHome > 0) put("id_home", match.idHome.toLong())
                if (match.idAway > 0) put("id_away", match.idAway.toLong())
                if (match.url.isNotEmpty()) put("match_url", match.url)
                if (match.tbType > 0) put("total_type", match.tbType.toLong())
            }
            db.insert("express_events", null, eventValues)
        }
        
        checkExpressStatus(expressId, expId, userId)
        
        val kefInfo = matchesFactors.joinToString(" × ") {
            "${it.homeTeam.take(10)} vs ${it.awayTeam.take(10)}:${typeName(it.type)}(${"%.2f".format(it.kef)})" 
        }
        onLogUpdate?.invoke("[${getCurrentTime()}] ✅ Экспресс #$expId сохранен (${matchesFactors.size} матчей): $kefInfo | Общий кэф: ${"%.2f".format(totalKef)} | Ставка: ${betAmount.toInt()} ₽")
        Log.d(TAG, "Экспресс #$expId сохранен с ${matchesFactors.size} матчами")
    }
    
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
                    val sh = matchFactors.score1; val sa = matchFactors.score2; val matchTime = matchFactors.matchTime
                    val db = dbHelper.writableDatabase
                    
                    db.update("express_events", ContentValues().apply {
                        put("home_score", sh); put("away_score", sa); put("match_time", matchTime)
                        put("updated_at", System.currentTimeMillis() / 1000)
                    }, "id = ?", arrayOf(event.id.toString()))
                    
                    event.homeScore = sh; event.awayScore = sa; event.matchTime = matchTime
                    val newStatus = checkMatchStatus(event.betType, sh, sa)
                    
                    if (newStatus != event.status) {
                        db.update("express_events", ContentValues().apply {
                            put("status", newStatus); put("updated_at", System.currentTimeMillis() / 1000)
                        }, "id = ?", arrayOf(event.id.toString()))
                        
                        event.status = newStatus
                        val statusText = if (newStatus == 2) "ЗАШЁЛ ✅" else "НЕ ЗАШЁЛ ❌"
                        val message = "📊 Матч #${event.mId}: $sh-$sa (${matchTime}') | $statusText"
                        onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                        onScoresUpdate?.invoke(message)
                        dbHelper.addLog(userId, "match_status", "Матч ${event.mId}: $sh-$sa, время: ${matchTime}', статус: $newStatus")
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
        
        val cursor = db.query("express_events", arrayOf("match_time", "created_at", "home_score", "away_score"),
            "id = ?", arrayOf(event.id.toString()), null, null, null)
        
        var matchTime = event.matchTime; var createdAt = 0L; var homeScore = event.homeScore; var awayScore = event.awayScore
        if (cursor.moveToFirst()) {
            matchTime = cursor.getInt(0); createdAt = cursor.getLong(1)
            homeScore = cursor.getInt(2); awayScore = cursor.getInt(3)
        }
        cursor.close()
        
        val matchAge = currentTime - createdAt
        if ((matchAge > 5400 && matchTime >= 90) || matchAge > 7200) {
            val status = checkMatchStatus(event.betType, homeScore, awayScore)
            db.update("express_events", ContentValues().apply {
                put("status", status); put("is_finalized", 1); put("updated_at", currentTime)
            }, "id = ?", arrayOf(event.id.toString()))
            
            val statusText = if (status == 2) "ЗАШЁЛ ✅" else "НЕ ЗАШЁЛ ❌"
            val message = "⏰ Матч #${event.mId} завершен (${matchTime}'): $homeScore-$awayScore | $statusText"
            onLogUpdate?.invoke("[${getCurrentTime()}] $message")
            onScoresUpdate?.invoke(message)
            dbHelper.addLog(userId, "match_finished", "Матч ${event.mId} финализирован: $statusText")
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
                put("sts_all", expressStatus); put("profloss", profitLoss)
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
                saveExpressToDb(userId, newExpId, bets, newBetAmount)
                placeBet(userId, newExpId, bets)
                
                db.update("express_bets", ContentValues().apply {
                    put("sts_all", -1); put("id_exp_replace", newExpId); put("updated_at", currentTime)
                }, "id = ?", arrayOf(oldExpressId.toString()))
                
                dbHelper.addLog(userId, "express_replaced", "Экспресс #$oldExpId заменен на #$newExpId, ставка: ${newBetAmount.toInt()} ₽")
            }
        } else cursor.close()
    }
    
    private fun checkCompletedMatches(userId: Long) {
        val db = dbHelper.writableDatabase
        val currentTime = System.currentTimeMillis() / 1000
        
        val cursor = db.query("express_events", arrayOf("id", "express_id", "m_id", "created_at", "home_score", "away_score", "bet_type", "status", "match_time"),
            "is_finalized = 0", null, null, null, null)
        
        val activeMatches = mutableListOf<ActiveMatch>()
        while (cursor.moveToNext()) activeMatches.add(ActiveMatch(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2),
            cursor.getLong(3), cursor.getInt(4), cursor.getInt(5), cursor.getInt(6), cursor.getInt(7), cursor.getInt(8)))
        cursor.close()
        
        for (match in activeMatches) {
            val matchAge = currentTime - match.createdAt
            if ((matchAge > 5400 && match.matchTime >= 90) || matchAge > 7200) finalizeMatch(match)
        }
    }
    
    private fun finalizeMatch(match: ActiveMatch) {
        val db = dbHelper.writableDatabase
        val finalStatus = checkMatchStatus(match.betType, match.homeScore, match.awayScore)
        
        db.update("express_events", ContentValues().apply {
            put("status", finalStatus); put("is_finalized", 1)
            put("updated_at", System.currentTimeMillis() / 1000)
        }, "id = ?", arrayOf(match.id.toString()))
        
        val statusText = if (finalStatus == 2) "ЗАШЁЛ ✅" else "НЕ ЗАШЁЛ ❌"
        onLogUpdate?.invoke("[${getCurrentTime()}] 🏁 Матч #${match.mId} завершен: ${match.homeScore}-${match.awayScore} | $statusText")
        onScoresUpdate?.invoke("🏁 Матч #${match.mId} завершен: ${match.homeScore}-${match.awayScore} | $statusText")
        
        val expressCursor = db.query("express_bets", arrayOf("id_exp", "user_id"),
            "id = ?", arrayOf(match.expressId.toString()), null, null, null)
        var expId = 0; var userId = 0L
        if (expressCursor.moveToFirst()) { expId = expressCursor.getInt(0); userId = expressCursor.getLong(1) }
        expressCursor.close()
        
        dbHelper.addLog(userId, "match_finished", "Матч ${match.mId} финализирован: $statusText")
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