// В начале файла добавьте импорт
package com.example.fonbetbot

// ... все импорты ...

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
    
    // ... другие методы ...

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
            onSuccess = { sessionInfo ->
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
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(NOTIFICATION_ID, notification)
                        
                        val timestamp = getCurrentTime()
                        if (difference > 0 && oldBalance > 0) {
                            onLogUpdate?.invoke("[$timestamp] 💰 Профит: +%.2f ₽".format(difference))
                        } else if (difference < 0 && oldBalance > 0) {
                            onLogUpdate?.invoke("[$timestamp] 📉 Убыток: %.2f ₽".format(difference))
                        }
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
                    userId = userData.id,
                    settings = settings,
                    onSuccess = { betDataList ->
                        if (betDataList.isNotEmpty()) {
                            val bets = betDataList.map { it.mId to it.type }
                            
                            onLogUpdate?.invoke("[${getCurrentTime()}] 🎲 Получен сигнал на создание ставки! (${bets.size} матчей)")
                            
                            betDataList.forEach { betData ->
                                val matchInfo = buildString {
                                    append("📊 Матч #${betData.mId}: ")
                                    if (betData.home.isNotEmpty() || betData.away.isNotEmpty()) {
                                        append("${betData.home} vs ${betData.away} | ")
                                    }
                                    if (betData.ligaName.isNotEmpty()) {
                                        append("${betData.ligaName} | ")
                                    }
                                    append("Кэф: ${"%.2f".format(betData.startKf)} | ")
                                    append("Тип: ${getTypeName(betData.type)} ")
                                }
                                onLogUpdate?.invoke("[${getCurrentTime()}] $matchInfo")
                            }
                            
                            val maxMatches = settings.maxMatchesPerExpress
                            val expressGroups = bets.chunked(maxMatches)
                            
                            onLogUpdate?.invoke("[${getCurrentTime()}] 📨 Сгруппировано в ${expressGroups.size} экспрессов (по $maxMatches матча)")
                            
                            expressGroups.forEachIndexed { index, group ->
                                if (group.isNotEmpty()) {
                                    val existingExpress = findExistingExpress(userData.id, group)
                                    
                                    if (existingExpress != null) {
                                        Log.d(TAG, "Экспресс #${existingExpress.first} уже существует, мониторим")
                                        checkExpressMatchesStatus(existingExpress.second, existingExpress.first, userData.id)
                                    } else {
                                        val newExpId = (System.currentTimeMillis() / 1000).toInt() + index
                                        
                                        val groupBetData = betDataList.filter { betData ->
                                            group.any { it.first == betData.mId }
                                        }
                                        
                                        val matchesInfo = groupBetData.joinToString(" + ") { 
                                            "${it.home.take(10)} vs ${it.away.take(10)} (${getTypeName(it.type)})" 
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
                    onError = { error ->
                        Log.e(TAG, "Ошибка получения ставок: $error")
                        onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ошибка получения ставок: $error")
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в fetchBets: ${e.message}")
        }
    }
    
    // ... остальные методы ...
}