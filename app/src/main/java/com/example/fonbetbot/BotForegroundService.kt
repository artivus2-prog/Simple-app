// BotForegroundService.kt - ИСПРАВЛЕНО: все методы используют id_exp
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
//import com.example.fonbetbot.DatabaseHelper.ExpressEventData
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
        
        if (prefs.getBoolean("test_mode", true)) {
            balance = prefs.getString("test_balance", "1000")?.toDoubleOrNull() ?: 1000.0
            lastBalance = balance
            Log.d(TAG, "🧪 Тестовый режим, виртуальный баланс: $balance ₽")
        }
        
        authData?.let { data ->
            try {
                val user = dbHelper.getUser(data.fsid, data.deviceId)
                user?.let {
                    if (!prefs.getBoolean("test_mode", true)) {
                        val stats = dbHelper.getBalanceStats(it.id)
                        if (stats.currentBalance > 0) {
                            balance = stats.currentBalance
                            lastBalance = stats.currentBalance
                        }
                    }
                    Log.d(TAG, "✅ Пользователь загружен: id=${it.id}, clientId=${it.clientId}, username=${it.username}")
                } ?: Log.e(TAG, "❌ Пользователь не найден в БД!")
            } catch (e: Exception) { 
                Log.e(TAG, "Ошибка загрузки пользователя: ${e.message}")
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
                    dbHelper.addLog(it.id, "start", "Бот запущен")
                    Log.d(TAG, "✅ Сессия бота начата для userId=${it.id}")
                } ?: Log.e(TAG, "❌ Не удалось начать сессию - пользователь не найден")
            } catch (e: Exception) { 
                Log.e(TAG, "Ошибка старта сессии: ${e.message}")
            }
        }
        
        // Цикл проверки баланса
        serviceScope.launch {
            while (isRunning) {
                delay(60000)
                authData?.let { data -> fetchBalance(data) }
            }
        }
        
        // Цикл получения ставок
        serviceScope.launch {
            delay(5000)
            Log.d(TAG, "🔄 Запуск цикла получения ставок")
            while (isRunning) {
                authData?.let { data -> fetchBets(data) }
                delay(30000)
            }
        }
        
        // Цикл обновления счета матчей
        serviceScope.launch {
            delay(10000)
            Log.d(TAG, "🔄 Запущен цикл обновления счетов")
            while (isRunning) {
                try {
                    updateActiveMatchesScores()
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка в цикле обновления счетов: ${e.message}")
                }
                delay(60000)
            }
        }
        
        // Цикл проверки замены экспрессов
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
        val testMode = prefs.getBoolean("test_mode", true)
        
        if (testMode) {
            Log.d(TAG, "🧪 Тестовый режим: баланс не запрашивается")
            return
        }
        
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
        
        Log.d(TAG, "💰 Запрос баланса...")
        
        apiClient.getSaldo(
            cookies = cookies,
            fsid = data.fsid,
            deviceId = data.deviceId,
            onSuccess = { sessionInfo: ApiClient.SessionInfo? ->
                if (sessionInfo != null) {
                    val newBalance = sessionInfo.saldo
                    val oldBalance = balance
                    
                    Log.d(TAG, "✅ Баланс получен: saldo=$newBalance, clientId=${sessionInfo.clientId}, userName=${sessionInfo.userName}")
                    
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
                                }
                                if (difference < 0 && oldBalance > 0) {
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
                Log.e(TAG, "❌ Ошибка получения баланса: $error")
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
        Log.d(TAG, "🎲 ===== fetchBets: НАЧАЛО =====")
        
        try {
            val user = dbHelper.getUser(data.fsid, data.deviceId)
            if (user == null) {
                Log.e(TAG, "❌ fetchBets: Пользователь не найден в БД!")
                onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ошибка: пользователь не найден")
                return
            }
            
            val userData = user
            Log.d(TAG, "📊 fetchBets: userId=${userData.id}, clientId=${userData.clientId}, username=${userData.username}")
            
            val apiUserId = if (userData.clientId > 0) userData.clientId else userData.id
            Log.d(TAG, "📊 fetchBets: отправляем user_id=$apiUserId на сервер")
            
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
            
            Log.d(TAG, "📤 Отправляем запрос getBets с настройками:")
            Log.d(TAG, "  maxMatchesPerExpress: ${settings.maxMatchesPerExpress}")
            Log.d(TAG, "  multiply: ${settings.multiply}")
            Log.d(TAG, "  allMinKef: ${settings.allMinKef}")
            
            apiClient.getBets(
                userId = apiUserId,
                settings = settings,
                onSuccess = { betDataList: List<ApiClient.BetData> ->
                    Log.d(TAG, "📥 getBets ответ: получено ${betDataList.size} матчей")
                    
                    if (betDataList.isNotEmpty()) {
                        betDataList.forEach { bet ->
                            Log.d(TAG, "  📊 mId=${bet.mId}, type=${bet.type}, home=${bet.home}, away=${bet.away}, kef=${bet.startKf}")
                        }
                        
                        val matchIds = betDataList.map { it.mId }.sorted()
                        val matchIdsKey = matchIds.joinToString(",")
                        
                        Log.d(TAG, "🎯 Экспресс из матчей: $matchIdsKey (${betDataList.size} матчей)")
                        
                        // Проверяем, существует ли уже такой экспресс
                        val alreadyExists = dbHelper.isExpressExists(matchIds)
                        Log.d(TAG, "🔍 Проверка дубликата: exists=$alreadyExists")
                        
                        if (alreadyExists) {
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
                        
                        Log.d(TAG, "💾 Сохраняем экспресс в БД...")
                        val saved = saveExpressToDb(
                            userId = userData.id,
                            expId = newExpId,
                            betDataList = betDataList
                        )
                        
                        Log.d(TAG, "💾 Результат сохранения: $saved")
                        
                        if (saved) {
                            Log.d(TAG, "✅ Экспресс сохранен успешно, размещаем ставку...")
                            val betPlaced = placeBet(
                                expId = newExpId,
                                userId = userData.id,
                                betDataList = betDataList
                            )
                            Log.d(TAG, "🎰 Результат размещения ставки: $betPlaced")
                        } else {
                            Log.e(TAG, "❌ Ошибка сохранения экспресса в БД!")
                            onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ошибка сохранения экспресса!")
                        }
                        
                    } else {
                        Log.d(TAG, "📭 getBets: пустой список матчей")
                        onLogUpdate?.invoke("[${getCurrentTime()}] 📭 Нет подходящих матчей")
                    }
                },
                onError = { error: String ->
                    Log.e(TAG, "❌ Ошибка получения ставок: $error")
                    onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ошибка получения ставок: $error")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Исключение в fetchBets: ${e.message}", e)
        }
        
        Log.d(TAG, "🎲 ===== fetchBets: КОНЕЦ =====")
    }
    
    // ==================== МЕТОДЫ СОХРАНЕНИЯ И РАЗМЕЩЕНИЯ ====================
    
    private fun saveExpressToDb(
        userId: Long,
        expId: Int,
        betDataList: List<ApiClient.BetData>
    ): Boolean {
        val currentTimeUtc = System.currentTimeMillis() / 1000
        val betAmount = prefs.getString("bet_amount", "30")?.toDoubleOrNull() ?: 30.0
        
        var totalKef = 1.0
        betDataList.forEach { betData ->
            totalKef *= betData.startKf
        }
        
        val potentialWin = betAmount * totalKef
        
        val strategyStr = betDataList.joinToString("+") { betData ->
            when (betData.type) {
                924 -> "1x"
                927 -> "f1(+1.5)"
                928 -> "f2(+1.5)"
                else -> "type${betData.type}"
            }
        }
        
        Log.d(TAG, "💾 saveExpressToDb #$expId:")
        Log.d(TAG, "  Матчей: ${betDataList.size}")
        Log.d(TAG, "  Общий кэф: ${"%.2f".format(totalKef)}")
        Log.d(TAG, "  Ставка: ${betAmount.toInt()} ₽")
        Log.d(TAG, "  Выигрыш: ${"%.2f".format(potentialWin)} ₽")
        Log.d(TAG, "  Стратегия: $strategyStr")
        Log.d(TAG, "  Баланс: $balance")
  
// Подготавливаем данные матчей
val matchesData: List<DatabaseHelper.ExpressEventData> = betDataList.map { betData ->
    DatabaseHelper.ExpressEventData(
        mId = betData.mId,
        idLiga = if (betData.idLiga > 0) betData.idLiga else null,
        leagueName = betData.ligaName,
        idHome = if (betData.comand1Id > 0) betData.comand1Id else null,
        homeTeam = betData.home,
        idAway = if (betData.comand2Id > 0) betData.comand2Id else null,
        awayTeam = betData.away,
        startOdds = betData.startKf,
        currentOdds = betData.lastKf,
        homeScore = betData.sh,
        awayScore = betData.sa,
        betType = betData.type,
        status = if (betData.sh > 0 || betData.sa > 0) {
            checkMatchStatus(betData.type, betData.sh, betData.sa)
        } else 0,
        matchUrl = betData.url,
        uzh = if (betData.uzh > 0) betData.uzh.toString() else "0.0",
        totalType = if (betData.tbType > 0) betData.tbType else null
    )
}
        val expressRowId = dbHelper.saveExpressWithMatches(
            expId = expId,
            kfall = totalKef,
            sumbet = betAmount,
            potentialWin = potentialWin,
            balance = balance,
            strategy = strategyStr,
            eventsCount = betDataList.size,
            matches = matchesData
        )
        
        if (expressRowId > 0) {
            dbHelper.addLog(userId, "express_created", 
                "Экспресс #$expId: ${betDataList.size} матчей, кэф ${"%.2f".format(totalKef)}, ставка ${betAmount.toInt()} ₽, стратегия: $strategyStr")
            return true
        }
        
        return false
    }
    
    private fun placeBet(
        expId: Int,
        userId: Long,
        betDataList: List<ApiClient.BetData>
    ): Boolean {
        try {
            val betAmount = prefs.getString("bet_amount", "30")?.toDoubleOrNull()?.toInt() ?: 30
            
            var totalKef = 1.0
            betDataList.forEach { totalKef *= it.startKf }
            val potentialWin = betAmount * totalKef
            
            val matchesInfo = betDataList.joinToString(" + ") { betData ->
                "${betData.home.take(15)} vs ${betData.away.take(15)} (${typeName(betData.type)})"
            }
            
            Log.d(TAG, "🎲 РАЗМЕЩЕНИЕ СТАВКИ #$expId:")
            Log.d(TAG, "  Матчи: $matchesInfo")
            Log.d(TAG, "  Сумма: $betAmount ₽")
            Log.d(TAG, "  Кэф: ${"%.2f".format(totalKef)}")
            Log.d(TAG, "  Выигрыш: ${"%.2f".format(potentialWin)} ₽")
            
            val testMode = prefs.getBoolean("test_mode", true)
            
            if (testMode) {
                Log.d(TAG, "🧪 ТЕСТОВЫЙ РЕЖИМ: Ставка симулируется")
                
                handleSuccessfulBet(
                    expId = expId,
                    userId = userId,
                    betAmount = betAmount,
                    totalKef = totalKef,
                    potentialWin = potentialWin,
                    matchesInfo = matchesInfo,
                    newBalance = null
                )
                return true
                
            } else {
                Log.d(TAG, "💰 РЕАЛЬНЫЙ РЕЖИМ: Размещаем ставку на сервере")
                
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
                
                val authData = BotForegroundService.authData ?: run {
                    Log.e(TAG, "❌ Нет данных авторизации")
                    onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Нет данных авторизации для ставки")
                    return false
                }
                
                val user = dbHelper.getUser(authData.fsid, authData.deviceId)
                val clientId = user?.clientId ?: 18845703L
                
                Log.d(TAG, "📤 Отправляем запрос на размещение ставки...")
                Log.d(TAG, "  clientId: $clientId")
                Log.d(TAG, "  fsid: ${authData.fsid.take(10)}...")
                Log.d(TAG, "  cookies: ${cookies.size} шт.")
                
                apiClient.makeBet(
                    bets = betDataList,
                    amount = betAmount,
                    cookies = cookies,
                    fsid = authData.fsid,
                    clientId = clientId,
                    deviceId = authData.deviceId,
                    onSuccess = { result ->
                        Log.d(TAG, "✅ Ставка успешно размещена!")
                        
                        val couponResult = result.result.optJSONObject("coupon")
                        val resultCode = couponResult?.optInt("resultCode", -1) ?: -1
                        
                        if (resultCode == 0) {
                            val newBalance = couponResult?.optDouble("clientSaldo")
                            
                            handleSuccessfulBet(
                                expId = expId,
                                userId = userId,
                                betAmount = betAmount,
                                totalKef = totalKef,
                                potentialWin = potentialWin,
                                matchesInfo = matchesInfo,
                                newBalance = newBalance
                            )
                        } else {
                            Log.e(TAG, "❌ Ставка отклонена, resultCode=$resultCode")
                            onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ставка #$expId отклонена (код: $resultCode)")
                            dbHelper.addLog(userId, "bet_error", 
                                "Ставка #$expId отклонена, код: $resultCode", "ERROR")
                        }
                    },
                    onError = { error ->
                        Log.e(TAG, "❌ Ошибка размещения ставки: $error")
                        onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ошибка ставки #$expId: $error")
                        dbHelper.addLog(userId, "bet_error", 
                            "Ошибка ставки #$expId: $error", "ERROR")
                    }
                )
                
                return true
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Исключение в placeBet: ${e.message}", e)
            return false
        }
    }
    
    private fun handleSuccessfulBet(
        expId: Int,
        userId: Long,
        betAmount: Int,
        totalKef: Double,
        potentialWin: Double,
        matchesInfo: String,
        newBalance: Double?
    ) {
        // Обновляем флаг размещения
        dbHelper.updateBetPlaced(expId, true)
        
        val testMode = prefs.getBoolean("test_mode", true)
        val modeLabel = if (testMode) "[ТЕСТ]" else ""
        val displayBalance = newBalance?.let { "%.2f".format(it) } ?: "%.2f".format(balance)
        
        val notification = createBetNotification(
            "$modeLabel ✅ Ставка принята!",
            "Экспресс #$expId | $betAmount ₽ | Кэф: ${"%.2f".format(totalKef)} | Выигрыш: ${"%.2f".format(potentialWin)} ₽ | Баланс: $displayBalance ₽"
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify((NOTIFICATION_ID + expId).toInt(), notification)
        
        val successMessage = "$modeLabel ✅ Ставка #$expId ПРИНЯТА! $betAmount ₽ × ${"%.2f".format(totalKef)} = ${"%.2f".format(potentialWin)} ₽ | Баланс: $displayBalance ₽ | $matchesInfo"
        onLogUpdate?.invoke("[${getCurrentTime()}] $successMessage")
        onScoresUpdate?.invoke(successMessage)
        
        dbHelper.addLog(userId, "bet_placed",
            "Ставка #$expId: $betAmount ₽, кэф ${"%.2f".format(totalKef)}, баланс: $displayBalance ₽")
        
        // Обновляем баланс если он изменился
        newBalance?.let {
            if (it != balance) {
                balance = it
                lastBalance = it
                onBalanceUpdate?.invoke(it)
            }
        }
    }
    
    // ==================== МЕТОДЫ ОБНОВЛЕНИЯ СЧЕТА ====================
    
    private fun updateActiveMatchesScores() {
        try {
            val db = dbHelper.readableDatabase
            
            // Получаем все активные экспрессы
            val cursor = db.query("express_bets", arrayOf("id", "id_exp"),
                "sts_all IN (0, 1, 2)", null, null, null, null)
            
            val activeExpresses = mutableListOf<Pair<Long, Int>>()
            while (cursor.moveToNext()) {
                activeExpresses.add(Pair(cursor.getLong(0), cursor.getInt(1)))
            }
            cursor.close()
            
            Log.d(TAG, "📊 updateActiveMatchesScores: активных экспрессов: ${activeExpresses.size}")
            
            if (activeExpresses.isEmpty()) {
                Log.d(TAG, "📊 Нет активных экспрессов для обновления")
                return
            }
            
            // Собираем все m_id из активных экспрессов
            val allExpIds = activeExpresses.map { it.second }
            val placeholders = allExpIds.joinToString(",") { "?" }
            
            val eventsCursor = db.rawQuery(
                "SELECT id, id_exp, m_id, bet_type, status, home_score, away_score, match_time " +
                "FROM express_events WHERE is_finalized = 0 AND id_exp IN ($placeholders)",
                allExpIds.map { it.toString() }.toTypedArray()
            )
            
            val events = mutableListOf<EventInfo>()
            while (eventsCursor.moveToNext()) {
                events.add(EventInfo(
                    id = eventsCursor.getLong(0),
                    idExp = eventsCursor.getInt(1),
                    mId = eventsCursor.getInt(2),
                    betType = eventsCursor.getInt(3),
                    status = eventsCursor.getInt(4),
                    homeScore = eventsCursor.getInt(5),
                    awayScore = eventsCursor.getInt(6),
                    matchTime = eventsCursor.getInt(7)
                ))
            }
            eventsCursor.close()
            
            Log.d(TAG, "📊 Всего незавершенных матчей: ${events.size}")
            
            // Обновляем каждый матч
            events.forEach { event ->
                Log.d(TAG, "🔄 Обновляем счет матча #${event.mId} (тип=${event.betType})")
                updateMatchScore(event)
            }
            
            // Проверяем статусы экспрессов
            activeExpresses.forEach { (_, idExp) ->
                checkExpressStatus(idExp)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления счетов: ${e.message}", e)
        }
    }
    
    private fun updateMatchScore(event: EventInfo) {
        Log.d(TAG, "🔍 Запрашиваем счет матча #${event.mId}...")
        
        apiClient.getMatchScore(
            matchId = event.mId,
            onSuccess = { matchFactors: ApiClient.MatchFactors? ->
                if (matchFactors != null) {
                    val sh = matchFactors.score1
                    val sa = matchFactors.score2
                    val matchTime = matchFactors.matchTime
                    
                    Log.d(TAG, "📊 Матч #${event.mId}: счет $sh:$sa, время ${matchTime}'")
                    
                    // Обновляем в БД по m_id
                    val newStatus = checkMatchStatus(event.betType, sh, sa)
                    
                    dbHelper.updateMatchStatus(
                        mId = event.mId,
                        homeScore = sh,
                        awayScore = sa,
                        matchTime = matchTime,
                        status = newStatus
                    )
                    
                    // Обновляем локальные данные
                    event.homeScore = sh
                    event.awayScore = sa
                    event.matchTime = matchTime
                    
                    if (newStatus != event.status) {
                        event.status = newStatus
                        
                        val statusText = when (newStatus) {
                            2 -> "ЗАШЁЛ ✅"
                            1 -> "НЕ ЗАШЁЛ ❌"
                            else -> "АКТИВЕН 🔄"
                        }
                        
                        val message = "📊 Матч #${event.mId}: $sh-$sa (${matchTime}') | $statusText"
                        onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                        onScoresUpdate?.invoke(message)
                        
                        Log.d(TAG, message)
                    }
                } else {
                    Log.d(TAG, "⚠️ Матч #${event.mId}: нет данных (matchFactors = null)")
                    checkMatchTimeout(event)
                }
            },
            onError = { error: String ->
                Log.e(TAG, "❌ Ошибка получения счета матча #${event.mId}: $error")
                checkMatchTimeout(event)
            }
        )
    }
    
    private fun checkMatchTimeout(event: EventInfo) {
        val db = dbHelper.readableDatabase
        val currentTime = System.currentTimeMillis() / 1000
        
        // Получаем время создания матча
        val cursor = db.query("express_events",
            arrayOf("created_at"),
            "m_id = ?", arrayOf(event.mId.toString()), null, null, null)
        
        var createdAt = currentTime
        if (cursor.moveToFirst()) {
            createdAt = cursor.getLong(0)
        }
        cursor.close()
        
        val matchAge = currentTime - createdAt
        Log.d(TAG, "⏰ Матч #${event.mId}: возраст=${matchAge}сек, время=${event.matchTime}', счет=${event.homeScore}:${event.awayScore}")
        
        // Если матчу больше 90 минут ИЛИ больше 2 часов
        if ((matchAge > 5400 && event.matchTime >= 90) || matchAge > 7200) {
            val status = checkMatchStatus(event.betType, event.homeScore, event.awayScore)
            
            dbHelper.updateMatchStatus(
                mId = event.mId,
                homeScore = event.homeScore,
                awayScore = event.awayScore,
                matchTime = event.matchTime,
                status = status,
                isFinalized = 1
            )
            
            event.status = status
            
            val statusText = when (status) {
                2 -> "ЗАШЁЛ ✅"
                1 -> "НЕ ЗАШЁЛ ❌"
                else -> "ЗАВЕРШЕН"
            }
            
            val message = "⏰ Матч #${event.mId} завершен по таймауту (${event.matchTime}'): ${event.homeScore}-${event.awayScore} | $statusText"
            onLogUpdate?.invoke("[${getCurrentTime()}] $message")
            onScoresUpdate?.invoke(message)
            
            Log.d(TAG, message)
        }
    }
    
    private fun checkMatchStatus(betType: Int, sh: Int, sa: Int): Int = when (betType) {
        924 -> if (sh > sa) 2 else if (sh == sa) 2 else 1  // 1X: победа1 или ничья
        927 -> if (sh + 1 >= sa) 2 else 1  // Ф1(+1.5): с учетом форы +1.5
        928 -> if (sa + 1 >= sh) 2 else 1  // Ф2(+1.5): с учетом форы +1.5
        else -> 1
    }
    
    private fun checkExpressStatus(idExp: Int) {
        try {
            val db = dbHelper.writableDatabase
            
            // Получаем все матчи экспресса по id_exp
            val cursor = db.query("express_events", 
                arrayOf("status", "is_finalized", "home_score", "away_score", "m_id", "bet_type"),
                "id_exp = ?", arrayOf(idExp.toString()), null, null, null)
            
            var anyLost = false
            var allWin = true
            var hasUnfinished = false
            var totalEvents = 0
            val matchDetails = mutableListOf<String>()
            
            while (cursor.moveToNext()) {
                totalEvents++
                val isFinalized = cursor.getInt(1) == 1
                val status = cursor.getInt(0)
                val sh = cursor.getInt(2)
                val sa = cursor.getInt(3)
                val mId = cursor.getInt(4)
                val betType = cursor.getInt(5)
                
                if (!isFinalized) {
                    hasUnfinished = true
                }
                
                when (status) {
                    1 -> {
                        anyLost = true
                        allWin = false
                        matchDetails.add("❌ #$mId: $sh-$sa (${typeName(betType)})")
                    }
                    2 -> {
                        matchDetails.add("✅ #$mId: $sh-$sa (${typeName(betType)})")
                    }
                    else -> {
                        allWin = false
                        matchDetails.add("🔄 #$mId: $sh-$sa (${typeName(betType)})")
                    }
                }
            }
            cursor.close()
            
            Log.d(TAG, "🎯 Экспресс #$idExp: totalEvents=$totalEvents, hasUnfinished=$hasUnfinished, anyLost=$anyLost, allWin=$allWin")
            
            // Определяем статус
            val expressStatus = when {
                hasUnfinished -> return  // Ещё активен, не обновляем
                anyLost -> 1             // Проиграл
                allWin -> 2              // Выиграл
                else -> return           // Активен
            }
            
            // Получаем данные экспресса
            val expressCursor = db.query("express_bets", 
                arrayOf("sumbet", "kfall", "sts_all", "balans"),
                "id_exp = ?", arrayOf(idExp.toString()), null, null, null)
            
            var betAmount = 0.0
            var totalKef = 0.0
            var oldStatus = 0
            var currentBalance = 0.0
            
            if (expressCursor.moveToFirst()) {
                betAmount = expressCursor.getDouble(0)
                totalKef = expressCursor.getDouble(1)
                oldStatus = expressCursor.getInt(2)
                currentBalance = expressCursor.getDouble(3)
            }
            expressCursor.close()
            
            val profitLoss = when (expressStatus) {
                2 -> betAmount * totalKef - betAmount
                1 -> -betAmount
                else -> 0.0
            }
            
            val newBalance = currentBalance + profitLoss + betAmount
            
            Log.d(TAG, "🎯 Экспресс #$idExp: oldStatus=$oldStatus, newStatus=$expressStatus, profit=$profitLoss, balance=$newBalance")
            
            if (expressStatus != oldStatus) {
                // Обновляем статус экспресса
                dbHelper.updateExpressStatus(idExp, expressStatus, profitLoss, newBalance)
                
                val statusText = when (expressStatus) {
                    2 -> "ВЫИГРАЛ 🏆"
                    1 -> "ПРОИГРАЛ ❌"
                    else -> "АКТИВЕН 🔄"
                }
                
                val message = buildString {
                    append("🎯 Экспресс #$idExp ")
                    append("($totalEvents матчей) ")
                    append("$statusText")
                    if (expressStatus != 0) {
                        append(" | ${if (expressStatus == 2) "+" else ""}${"%.2f".format(profitLoss)} ₽")
                        append(" | Баланс: ${"%.2f".format(newBalance)} ₽")
                    }
                    append("\n")
                    matchDetails.forEach { append("$it ") }
                }
                
                onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                onScoresUpdate?.invoke(message)
                
                Log.d(TAG, message)
                
                // Обновляем баланс
                balance = newBalance
                lastBalance = newBalance
                onBalanceUpdate?.invoke(newBalance)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки статуса экспресса #$idExp: ${e.message}", e)
        }
    }
    
    // ==================== ЗАМЕНА ЭКСПРЕССОВ ====================
    
    private fun checkAndCreateReplacementExpress() {
        val db = dbHelper.writableDatabase
        val currentTime = System.currentTimeMillis() / 1000
        val twelveHoursAgo = currentTime - (12 * 3600)
        
        val cursor = db.rawQuery(
            "SELECT id, id_exp, sumbet FROM express_bets WHERE sts_all = 1 AND ct < ? AND id_exp_replace = 0 ORDER BY ct ASC LIMIT 1",
            arrayOf(twelveHoursAgo.toString())
        )
        
        if (cursor.moveToFirst()) {
            val oldExpressId = cursor.getLong(0)
            val oldExpId = cursor.getInt(1)
            val currentBetAmount = cursor.getDouble(2)
            cursor.close()
            
            val multiply = prefs.getInt("multiply", 2)
            val initialBet = prefs.getString("bet_amount", "30")?.toDoubleOrNull() ?: 30.0
            val maxBetMultiplier = prefs.getInt("max_bet_multiplier", 3)
            val maxAllowedBet = initialBet * maxBetMultiplier
            var newBetAmount = currentBetAmount * multiply
            
            if (newBetAmount > maxAllowedBet) {
                newBetAmount = initialBet + 1.0
                onLogUpdate?.invoke("[${getCurrentTime()}] ⚠️ Ставка превысила лимит (${maxAllowedBet.toInt()} ₽), сброс до начальной + 1: ${newBetAmount.toInt()} ₽")
                
                authData?.let { data ->
                    val user = dbHelper.getUser(data.fsid, data.deviceId)
                    user?.let { dbHelper.addLog(it.id, "bet_reset", "Ставка сброшена до ${newBetAmount.toInt()} ₽") }
                }
            }
            
            val eventsCursor = db.query("express_events", arrayOf("m_id", "bet_type"),
                "id_exp = ?", arrayOf(oldExpId.toString()), null, null, null)
            val bets = mutableListOf<Pair<Int, Int>>()
            while (eventsCursor.moveToNext()) bets.add(Pair(eventsCursor.getInt(0), eventsCursor.getInt(1)))
            eventsCursor.close()
            
            if (bets.isNotEmpty()) {
                val newExpId = (System.currentTimeMillis() / 1000).toInt()
                onLogUpdate?.invoke("[${getCurrentTime()}] 🔄 Замена экспресса #$oldExpId → #$newExpId (ставка: ${currentBetAmount.toInt()} → ${newBetAmount.toInt()} ₽)")
                
                prefs.edit().putString("bet_amount", newBetAmount.toInt().toString()).apply()
                
                // Помечаем старый как замененный
                val updateValues = ContentValues().apply {
                    put("sts_all", -1)
                    put("id_exp_replace", newExpId)
                    put("updated_at", currentTime)
                }
                db.update("express_bets", updateValues, "id = ?", arrayOf(oldExpressId.toString()))
                
                authData?.let { data ->
                    val user = dbHelper.getUser(data.fsid, data.deviceId)
                    user?.let { 
                        dbHelper.addLog(it.id, "express_replaced", 
                            "Экспресс #$oldExpId заменен на #$newExpId, ставка: ${newBetAmount.toInt()} ₽") 
                    }
                }
            }
        } else {
            cursor.close()
        }
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
    data class EventInfo(
        val id: Long, 
        val idExp: Int,
        val mId: Int, 
        val betType: Int, 
        var status: Int,
        var homeScore: Int, 
        var awayScore: Int, 
        var matchTime: Int = 0
    )
    
    data class MatchFactorsData(
        val mId: Int, val type: Int, val kef: Double, val handicap: Double,
        val sh: Int, val sa: Int, val matchTime: Int = 0,
        val leagueName: String = "", val homeTeam: String = "", val awayTeam: String = "",
        val idLiga: Int = 0, val idHome: Int = 0, val idAway: Int = 0,
        val url: String = "", val uzh: String = "0.0", val tbType: Int = 0
    )
}