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
import android.database.sqlite.SQLiteDatabase
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
        const val BET_NOTIFICATION_BASE = 10000
        const val ACTION_STOP = "STOP_BOT"
        private const val TAG = "BotForegroundService"
        
        var isRunning = false
        var onBalanceUpdate: ((Double) -> Unit)? = null
        var onLogUpdate: ((String) -> Unit)? = null
        var onBetsUpdate: ((List<Pair<Int, Int>>) -> Unit)? = null
        var onScoresUpdate: ((String) -> Unit)? = null
        var authData: AuthData? = null
        var lastBalance: Double = 0.0
        private var betNotificationCounter = 0
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
            Log.d(TAG, "🔄 Запуск цикла получения ставок")
            while (isRunning) {
                authData?.let { data -> fetchBets(data) }
                delay(30000)
            }
        }
        
        // Цикл обновления счета матчей (каждые 60 секунд)
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
        
        // Цикл проверки замены экспрессов (каждый час)
        serviceScope.launch {
            delay(60000)
            while (isRunning) {
                checkAndCreateReplacementExpress()
                delay(3600000)
            }
        }
        
        // Цикл очистки неразмещенных экспрессов (каждые 5 минут)
        serviceScope.launch {
            delay(120000)
            while (isRunning) {
                cleanupUnplacedExpresses()
                delay(300000)
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
    
    // ==================== ИСПРАВЛЕННЫЙ МЕТОД fetchBets ====================
    
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
            
            // ПРОВЕРЯЕМ ЛИМИТ АКТИВНЫХ ЭКСПРЕССОВ
            val maxActiveExpresses = prefs.getInt("max_active_expresses", 5)
            val activeCount = getActiveExpressCount()
            
            Log.d(TAG, "📊 Лимит активных экспрессов: $activeCount/$maxActiveExpresses")
            
            if (activeCount >= maxActiveExpresses) {
                Log.d(TAG, "⏭ Достигнут лимит активных экспрессов ($activeCount/$maxActiveExpresses), пропускаем запрос")
                onLogUpdate?.invoke("[${getCurrentTime()}] ⏭ Лимит экспрессов ($activeCount/$maxActiveExpresses), ждем...")
                return
            }
            
            val apiUserId = if (userData.clientId > 0) userData.clientId else userData.id
            Log.d(TAG, "📊 fetchBets: отправляем user_id=$apiUserId на сервер")
            
            val settings = ApiClient.BetSettings(
                maxMatchesPerExpress = prefs.getInt("max_matches_per_express", 2),
                multiply = prefs.getInt("multiply", 2),
                allMinKef = prefs.getFloat("all_min_kef", 1.67f).toDouble(),
                maxActiveExpresses = maxActiveExpresses,
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
                userId = apiUserId,
                settings = settings,
                onSuccess = { betDataList: List<ApiClient.BetData> ->
                    Log.d(TAG, "📥 getBets ответ: получено ${betDataList.size} матчей")
                    
                    if (betDataList.isNotEmpty()) {
                        // ПОВТОРНАЯ ПРОВЕРКА ЛИМИТА
                        val currentActiveCount = getActiveExpressCount()
                        if (currentActiveCount >= maxActiveExpresses) {
                            Log.d(TAG, "⏭ Лимит достигнут во время запроса ($currentActiveCount/$maxActiveExpresses)")
                            onLogUpdate?.invoke("[${getCurrentTime()}] ⏭ Лимит экспрессов ($currentActiveCount/$maxActiveExpresses)")
                            return@getBets
                        }
                        
                        betDataList.forEach { bet ->
                            Log.d(TAG, "  📊 mId=${bet.mId}, type=${bet.type}, home=${bet.home}, away=${bet.away}, kef=${bet.startKf}")
                        }
                        
                        val matchIds = betDataList.map { it.mId }.sorted()
                        val matchIdsKey = matchIds.joinToString(",")
                        
                        Log.d(TAG, "🎯 Экспресс из матчей: $matchIdsKey (${betDataList.size} матчей)")
                        
                        val alreadyExists = isExpressAlreadyExists(userData.id, matchIds)
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
                        
                        // ВАЖНО: разная логика для тестового и реального режима
                        val testMode = prefs.getBoolean("test_mode", true)
                        
                        if (testMode) {
                            // В тестовом режиме сохраняем сразу
                            Log.d(TAG, "🧪 Тестовый режим: сохраняем экспресс в БД и симулируем ставку")
                            val expressDbId = saveExpressToDb(
                                userId = userData.id,
                                expId = newExpId,
                                betDataList = betDataList
                            )
                            
                            if (expressDbId > 0) {
                                placeBet(
                                    expressDbId = expressDbId,
                                    expId = newExpId,
                                    userId = userData.id,
                                    betDataList = betDataList
                                )
                            }
                        } else {
                            // В РЕАЛЬНОМ режиме: НЕ сохраняем в БД до успешной ставки!
                            Log.d(TAG, "💰 Реальный режим: пробуем разместить ставку ДО сохранения в БД")
                            placeBetAndSaveOnSuccess(
                                expId = newExpId,
                                userId = userData.id,
                                betDataList = betDataList
                            )
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
    
    // ==================== НОВЫЙ МЕТОД: Ставка с сохранением только при успехе ====================
    
    /**
     * Размещает ставку в реальном режиме и сохраняет экспресс в БД ТОЛЬКО при успехе (resultCode == 0).
     * При ошибке (resultCode != 0) экспресс НЕ сохраняется.
     */
    private fun placeBetAndSaveOnSuccess(
        expId: Int,
        userId: Long,
        betDataList: List<ApiClient.BetData>
    ) {
        val authData = BotForegroundService.authData
        if (authData == null) {
            Log.e(TAG, "❌ Нет данных авторизации")
            onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Нет данных авторизации")
            return
        }
        
        val user = dbHelper.getUser(authData.fsid, authData.deviceId)
        val clientId = user?.clientId ?: 18845703L
        
        val betAmount = prefs.getString("bet_amount", "30")?.toDoubleOrNull() ?: 30.0
        
        // Собираем ВСЕ куки из всех источников
        val cookieManager = CookieManager.getInstance()
        val allCookies = mutableMapOf<String, String>()
        
        // Основной домен
        val mainCookieString = cookieManager.getCookie("https://www.fon.bet") ?: ""
        Log.d(TAG, "🍪 Куки с www.fon.bet: ${if (mainCookieString.isNotEmpty()) mainCookieString.take(100) + "..." else "ПУСТО"}")
        
        mainCookieString.split(";").forEach { cookie ->
            val trimmed = cookie.trim()
            val parts = trimmed.split("=", limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                allCookies[parts[0].trim()] = parts[1].trim()
            }
        }
        
        // API домены
        listOf(
            "https://clientsapi-lb51-w.bk6bba-resources.com",
            "https://clientsapi-lb52-w.bk6bba-resources.com",
            "https://bk6bba-resources.com"
        ).forEach { domain ->
            val domainCookies = cookieManager.getCookie(domain) ?: ""
            if (domainCookies.isNotEmpty()) {
                Log.d(TAG, "🍪 Куки с $domain: ${domainCookies.take(100)}...")
            }
            domainCookies.split(";").forEach { cookie ->
                val trimmed = cookie.trim()
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                    allCookies.putIfAbsent(parts[0].trim(), parts[1].trim())
                }
            }
        }
        
        Log.d(TAG, "🍪 ИТОГО собрано куков: ${allCookies.size}")
        
        // Проверяем наличие критически важных кук
        val requiredCookies = listOf("fsid", "JSESSIONID", "clientId")
        requiredCookies.forEach { name ->
            if (allCookies.containsKey(name)) {
                Log.d(TAG, "  ✅ Кука '$name' присутствует: ${allCookies[name]?.take(30)}...")
            } else {
                Log.e(TAG, "  ❌ КРИТИЧЕСКИ: Кука '$name' ОТСУТСТВУЕТ!")
            }
        }
        
        Log.d(TAG, "👤 clientId для запроса: $clientId")
        Log.d(TAG, "👤 FSID: ${authData.fsid.take(15)}...")
        Log.d(TAG, "👤 DeviceID: ${authData.deviceId.take(15)}...")
        Log.d(TAG, "💰 Сумма ставки: ${betAmount.toInt()} ₽")
        
        // Делаем запрос на размещение ставки
        apiClient.makeBet(
            bets = betDataList,
            amount = betAmount.toInt(),
            cookies = allCookies,
            fsid = authData.fsid,
            clientId = clientId,
            deviceId = authData.deviceId,
            onSuccess = { result ->
                Log.d(TAG, "✅ ===== makeBet УСПЕШНО =====")
                
                // Выводим полные ответы в логи интерфейса
                val betSlipInfoStr = result.betSlipInfo.toString(2)
                val placeBetResponseStr = result.result.toString(2)
                
                onLogUpdate?.invoke("[${getCurrentTime()}] 📥 betSlipInfo (K=${result.betSlipInfo.optDouble("K", -1.0)}):")
                betSlipInfoStr.chunked(200).forEach { chunk ->
                    onLogUpdate?.invoke("[${getCurrentTime()}]   $chunk")
                }
                
                onLogUpdate?.invoke("[${getCurrentTime()}] 📥 placeBet response:")
                placeBetResponseStr.chunked(200).forEach { chunk ->
                    onLogUpdate?.invoke("[${getCurrentTime()}]   $chunk")
                }
                
                // Анализируем ответ
                val couponResult = result.result.optJSONObject("coupon")
                val resultCode = couponResult?.optInt("resultCode", -999) ?: -999
                
                Log.d(TAG, "📊 coupon = ${if (couponResult != null) "присутствует" else "null"}")
                Log.d(TAG, "📊 resultCode = $resultCode")
                
                onLogUpdate?.invoke("[${getCurrentTime()}] 📊 resultCode: $resultCode")
                
                if (couponResult == null) {
                    Log.e(TAG, "❌ coupon = null в ответе!")
                    onLogUpdate?.invoke("[${getCurrentTime()}] ❌ coupon = null в ответе!")
                    
                    // Проверяем другие поля в ответе
                    if (result.result.has("error")) {
                        val errorMsg = result.result.optString("error")
                        Log.e(TAG, "❌ error: $errorMsg")
                        onLogUpdate?.invoke("[${getCurrentTime()}] ❌ error: $errorMsg")
                    }
                    if (result.result.has("result")) {
                        val resultField = result.result.optString("result")
                        Log.d(TAG, "📊 result: $resultField")
                        onLogUpdate?.invoke("[${getCurrentTime()}] 📊 result: $resultField")
                    }
                    if (result.result.has("resultCode")) {
                        val rootResultCode = result.result.optInt("resultCode")
                        Log.d(TAG, "📊 resultCode (корень): $rootResultCode")
                        onLogUpdate?.invoke("[${getCurrentTime()}] 📊 resultCode (корень): $rootResultCode")
                    }
                    
                    // Выводим все ключи ответа
                    val keys = result.result.keys()
                    val keysList = mutableListOf<String>()
                    while (keys.hasNext()) {
                        keysList.add(keys.next())
                    }
                    Log.d(TAG, "📊 Все ключи ответа: $keysList")
                    onLogUpdate?.invoke("[${getCurrentTime()}] 📊 Ключи ответа: $keysList")
                    
                    onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ставка #$expId отклонена (код: $resultCode)")
                    onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Экспресс НЕ сохранен в базу данных")
                    
                    dbHelper.addLog(userId, "bet_rejected",
                        "Ставка #$expId отклонена, код: $resultCode, coupon=null", "ERROR")
                    return@makeBet
                }
                
                if (resultCode == 0) {
                    // ✅ СТАВКА ПРИНЯТА - теперь сохраняем в БД
                    Log.d(TAG, "✅ Ставка #$expId ПРИНЯТА! Сохраняем в БД...")
                    onLogUpdate?.invoke("[${getCurrentTime()}] ✅ Ставка #$expId ПРИНЯТА!")
                    
                    val expressDbId = saveExpressToDb(
                        userId = userId,
                        expId = expId,
                        betDataList = betDataList
                    )
                    
                    if (expressDbId > 0) {
                        // Обновляем статус в БД
                        val db = dbHelper.writableDatabase
                        val values = ContentValues().apply {
                            put("is_bet_placed", 1)
                            put("updated_at", System.currentTimeMillis() / 1000)
                        }
                        db.update("express_bets", values, "id = ?", arrayOf(expressDbId.toString()))
                        
                        // Показываем уведомление
                        betNotificationCounter++
                        val notificationId = BET_NOTIFICATION_BASE + betNotificationCounter
                        
                        val totalKef = result.betSlipInfo.optDouble("K", 1.0)
                        val potentialWin = betAmount * totalKef
                        val newBalance = couponResult?.optDouble("clientSaldo")
                        val displayBalance = newBalance?.let { "%.2f".format(it) } ?: "%.2f".format(balance)
                        
                        val notification = createBetNotification(
                            "✅ Ставка принята!",
                            "Экспресс #$expId | ${betAmount.toInt()} ₽ | Кэф: ${"%.2f".format(totalKef)} | Выигрыш: ${"%.2f".format(potentialWin)} ₽ | Баланс: $displayBalance ₽"
                        )
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(notificationId, notification)
                        
                        if (newBalance != null && newBalance != balance) {
                            balance = newBalance
                            lastBalance = newBalance
                            onBalanceUpdate?.invoke(newBalance)
                        }
                        
                        onLogUpdate?.invoke("[${getCurrentTime()}] ✅ Ставка #$expId: ${betAmount.toInt()} ₽ × ${"%.2f".format(totalKef)}")
                        
                        dbHelper.addLog(userId, "bet_placed",
                            "Ставка #$expId ПРИНЯТА: ${betAmount.toInt()} ₽, кэф ${"%.2f".format(totalKef)}")
                    }
                } else {
                    // ❌ СТАВКА ОТКЛОНЕНА - НЕ сохраняем в БД!
                    Log.e(TAG, "❌ Ставка #$expId ОТКЛОНЕНА (код: $resultCode). НЕ сохраняем в БД!")
                    onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ставка #$expId отклонена (код: $resultCode)")
                    onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Экспресс НЕ сохранен в базу данных")
                    
                    dbHelper.addLog(userId, "bet_rejected",
                        "Ставка #$expId отклонена, код: $resultCode. Экспресс не сохранен.", "ERROR")
                }
            },
            onError = { error ->
                // ❌ ОШИБКА СЕТИ - НЕ сохраняем в БД!
                Log.e(TAG, "❌ Ошибка сети при размещении ставки #$expId: $error")
                Log.e(TAG, "❌ Экспресс НЕ сохранен в базу данных")
                
                onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ошибка сети: $error")
                onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Экспресс НЕ сохранен")
                
                dbHelper.addLog(userId, "bet_network_error",
                    "Ошибка сети для ставки #$expId: $error. Экспресс не сохранен.", "ERROR")
            }
        )
    }
    
    // ==================== МЕТОДЫ ДЛЯ РАБОТЫ С ЭКСПРЕССАМИ ====================
    
    private fun getActiveExpressCount(): Int {
        val db = dbHelper.readableDatabase
        var count = 0
        
        try {
            val cursor = db.rawQuery("""
                SELECT eb.id, eb.id_exp, eb.sts_all
                FROM express_bets eb
                WHERE eb.sts_all IN (0, 1, 2) AND eb.is_bet_placed = 1
            """, null)
            
            val expressIds = mutableListOf<Pair<Long, Int>>()
            while (cursor.moveToNext()) {
                expressIds.add(Pair(cursor.getLong(0), cursor.getInt(1)))
            }
            cursor.close()
            
            Log.d(TAG, "📊 Кандидатов в активные: ${expressIds.size} экспрессов (со статусом 0,1,2 и is_bet_placed=1)")
            
            for ((expressId, idExp) in expressIds) {
                val matchesCursor = db.rawQuery("""
                    SELECT COUNT(*) 
                    FROM express_events 
                    WHERE express_id = ? 
                    AND is_finalized = 0 
                    AND (
                        match_time < 100 
                        OR match_time = 0
                        OR (expected_end_time > 0 AND expected_end_time > ?)
                    )
                """, arrayOf(
                    expressId.toString(),
                    (System.currentTimeMillis() / 1000).toString()
                ))
                
                var activeMatches = 0
                if (matchesCursor.moveToFirst()) {
                    activeMatches = matchesCursor.getInt(0)
                }
                matchesCursor.close()
                
                if (activeMatches > 0) {
                    count++
                    Log.d(TAG, "  ✅ Экспресс #$idExp: активен ($activeMatches незавершенных матчей)")
                } else {
                    Log.d(TAG, "  ⏹ Экспресс #$idExp: неактивен (все матчи завершены или превышено время)")
                }
            }
            
            Log.d(TAG, "📊 Итого активных экспрессов: $count")
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка подсчета активных экспрессов: ${e.message}")
        }
        
        return count
    }
    
    private fun isExpressAlreadyExists(userId: Long, matchIds: List<Int>): Boolean {
        val db = dbHelper.readableDatabase
        val currentTime = System.currentTimeMillis() / 1000
        
        val sortedNewIds = matchIds.sorted()
        
        val expressCursor = db.query(
            "express_bets",
            arrayOf("id", "id_exp"),
            "sts_all IN (0, 1, 2) AND is_bet_placed = 1",
            null, null, null, "id_exp DESC"
        )
        
        val activeExpressIds = mutableListOf<Pair<Long, Int>>()
        while (expressCursor.moveToNext()) {
            activeExpressIds.add(Pair(expressCursor.getLong(0), expressCursor.getInt(1)))
        }
        expressCursor.close()
        
        Log.d(TAG, "🔍 isExpressAlreadyExists: проверяем ${activeExpressIds.size} активных экспрессов")
        
        for ((expressId, idExp) in activeExpressIds) {
            val eventsCursor = db.rawQuery("""
                SELECT ee.m_id, ee.match_time, ee.is_finalized, ee.expected_end_time
                FROM express_events ee
                WHERE ee.express_id = ?
                AND ee.is_finalized = 0
                AND (
                    ee.match_time < 100 
                    OR ee.match_time = 0
                    OR (ee.expected_end_time > 0 AND ee.expected_end_time > ?)
                )
                ORDER BY ee.m_id ASC
            """, arrayOf(expressId.toString(), currentTime.toString()))
            
            val existingMatchIds = mutableListOf<Int>()
            var hasValidMatches = false
            
            while (eventsCursor.moveToNext()) {
                val mId = eventsCursor.getInt(0)
                val matchTime = eventsCursor.getInt(1)
                val isFinalized = eventsCursor.getInt(2) == 1
                val expectedEndTime = eventsCursor.getLong(3)
                
                if (isFinalized) continue
                if (matchTime >= 100 && matchTime > 0) continue
                if (expectedEndTime > 0 && currentTime >= expectedEndTime) continue
                
                existingMatchIds.add(mId)
                hasValidMatches = true
            }
            eventsCursor.close()
            
            if (hasValidMatches && existingMatchIds.sorted() == sortedNewIds) {
                Log.d(TAG, "🔍 НАЙДЕН ДУБЛИКАТ: экспресс #$idExp")
                return true
            }
        }
        
        Log.d(TAG, "🔍 Дубликатов не найдено")
        return false
    }
    
    private fun saveExpressToDb(
        userId: Long,
        expId: Int,
        betDataList: List<ApiClient.BetData>
    ): Long {
        val db = dbHelper.writableDatabase
        
        val conflictedMatches = checkConflictedMatches(db, betDataList)
        if (conflictedMatches.isNotEmpty()) {
            Log.w(TAG, "⚠️ Обнаружены конфликты матчей: ${conflictedMatches.map { it.mId }}")
            val canProceed = handleConflictedExpresses(db, conflictedMatches)
            if (!canProceed) {
                Log.e(TAG, "❌ Невозможно создать экспресс #$expId: матчи уже заняты")
                return -1
            }
        }
        
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
        
        val expressValues = ContentValues().apply {
            put("id_exp", expId)
            put("kfall", totalKef)
            put("profloss", 0.0)
            put("balans", balance)
            put("sumbet", betAmount)
            put("sts_all", 0)
            put("is_bet_placed", 0)
            put("ct", currentTimeUtc)
            put("strategy", strategyStr)
            put("id_exp_replace", 0)
            put("events_count", betDataList.size)
            put("total_odds", totalKef)
            put("bet_amount", betAmount)
            put("potential_win", potentialWin)
            put("balance", balance)
            put("profit_loss", 0.0)
            put("created_time", currentTimeUtc)
            put("created_at", currentTimeUtc)
            put("updated_at", currentTimeUtc)
        }
        
        val expressId = db.insert("express_bets", null, expressValues)
        
        if (expressId == -1L) {
            Log.e(TAG, "❌ Ошибка вставки в express_bets!")
            return -1
        }
        
        Log.d(TAG, "✅ Экспресс #$expId сохранен: expressId=$expressId")
        
        var eventsSavedCount = 0
        betDataList.forEach { betData ->
            val initialStatus = if (betData.sh > 0 || betData.sa > 0) {
                checkMatchStatus(betData.type, betData.sh, betData.sa)
            } else {
                0
            }
            
            val sportType = determineSportType(betData)
            
            val expectedEndSeconds = calculateExpectedEndTimeSeconds(
                matchStartMinute = betData.matchTime,
                sportType = sportType,
                currentMatchTime = betData.matchTime
            )
            
            val expectedEndTime = currentTimeUtc + expectedEndSeconds
            
            val eventValues = ContentValues().apply {
                put("express_id", expressId)
                put("id_exp", expId)
                put("m_id", betData.mId)
                put("start_odds", betData.startKf)
                put("current_odds", betData.lastKf)
                put("bet_type", betData.type)
                put("status", initialStatus)
                put("home_score", betData.sh)
                put("away_score", betData.sa)
                put("match_time", betData.matchTime)
                put("match_start_time", betData.matchTime)
                put("expected_end_time", expectedEndTime)
                put("sport_type", sportType)
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
            
            try {
                val eventInsertId = db.insertWithOnConflict(
                    "express_events",
                    null,
                    eventValues,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                
                if (eventInsertId == -1L) {
                    Log.e(TAG, "⚠️ Матч #${betData.mId} уже существует в другом экспрессе (пропущен)")
                } else {
                    eventsSavedCount++
                    Log.d(TAG, "  ✅ Матч #${betData.mId} сохранен (event_id=$eventInsertId, завершение через ${expectedEndSeconds}сек)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка вставки матча #${betData.mId}: ${e.message}")
            }
        }
        
        if (eventsSavedCount == 0) {
            Log.e(TAG, "❌ Ни один матч не сохранен, удаляем экспресс #$expId")
            db.delete("express_bets", "id = ?", arrayOf(expressId.toString()))
            return -1
        }
        
        val updateCount = ContentValues().apply {
            put("events_count", eventsSavedCount)
        }
        db.update("express_bets", updateCount, "id = ?", arrayOf(expressId.toString()))
        
        dbHelper.addLog(userId, "express_created",
            "Экспресс #$expId: $eventsSavedCount/${betDataList.size} матчей, кэф ${"%.2f".format(totalKef)}, ставка ${betAmount.toInt()} ₽, стратегия: $strategyStr")
        
        return expressId
    }
    
    private fun checkConflictedMatches(db: SQLiteDatabase, betDataList: List<ApiClient.BetData>): List<ConflictInfo> {
        val conflicts = mutableListOf<ConflictInfo>()
        
        for (betData in betDataList) {
            val cursor = db.query(
                "express_events",
                arrayOf("id", "express_id", "id_exp", "status", "is_finalized"),
                "m_id = ? AND is_finalized = 0",
                arrayOf(betData.mId.toString()),
                null, null, null
            )
            
            cursor.use {
                if (it.moveToFirst()) {
                    conflicts.add(ConflictInfo(
                        eventId = it.getLong(0),
                        expressId = it.getLong(1),
                        idExp = it.getInt(2),
                        status = it.getInt(3),
                        isFinalized = it.getInt(4) == 1,
                        mId = betData.mId
                    ))
                }
            }
        }
        
        return conflicts
    }
    
    private fun handleConflictedExpresses(db: SQLiteDatabase, conflicts: List<ConflictInfo>): Boolean {
        val expressIds = conflicts.map { it.expressId }.distinct()
        val currentTime = System.currentTimeMillis() / 1000
        
        for (expressId in expressIds) {
            val cursor = db.query(
                "express_bets",
                arrayOf("sts_all", "is_bet_placed"),
                "id = ?",
                arrayOf(expressId.toString()),
                null, null, null
            )
            
            var stsAll = 0
            var isBetPlaced = 0
            
            cursor.use {
                if (it.moveToFirst()) {
                    stsAll = it.getInt(0)
                    isBetPlaced = it.getInt(1)
                }
            }
            
            if (stsAll == 1 || stsAll == 2) {
                Log.d(TAG, "Завершаем матчи для экспресса #$expressId (статус: $stsAll)")
                
                val updateValues = ContentValues().apply {
                    put("is_finalized", 1)
                    put("updated_at", currentTime)
                }
                db.update("express_events", updateValues,
                    "express_id = ? AND is_finalized = 0",
                    arrayOf(expressId.toString()))
                continue
            }
            
            if (isBetPlaced == 1) {
                Log.w(TAG, "⚠️ Нельзя перезаписать: экспресс #$expressId активен и ставка размещена")
                return false
            }
            
            if (isBetPlaced == 0) {
                Log.d(TAG, "Отменяем экспресс #$expressId (ставка не размещена)")
                
                val updateExpress = ContentValues().apply {
                    put("sts_all", -2)
                    put("updated_at", currentTime)
                }
                db.update("express_bets", updateExpress, "id = ?", arrayOf(expressId.toString()))
                
                val updateEvents = ContentValues().apply {
                    put("is_finalized", 1)
                    put("status", -2)
                    put("updated_at", currentTime)
                }
                db.update("express_events", updateEvents,
                    "express_id = ?", arrayOf(expressId.toString()))
            }
        }
        
        return true
    }
    
    private fun calculateExpectedEndTimeSeconds(
        matchStartMinute: Int,
        sportType: String = "football",
        currentMatchTime: Int = 0
    ): Long {
        val totalMatchDuration = when (sportType) {
            "football" -> 90
            "hockey" -> 60
            else -> 90
        }
        
        val halftimeBreak = when (sportType) {
            "football" -> 15
            "hockey" -> 17
            else -> 10
        }
        
        val extraTime = when (sportType) {
            "football" -> 10
            "hockey" -> 5
            else -> 5
        }
        
        val effectiveMinute = if (currentMatchTime > matchStartMinute) currentMatchTime else matchStartMinute
        
        val remainingMinutes = totalMatchDuration + halftimeBreak + extraTime - effectiveMinute
        
        val seconds: Long = (remainingMinutes * 60).toLong() + 300L
        
        return maxOf(seconds, 300L)
    }
    
    private fun determineSportType(betData: ApiClient.BetData): String {
        return when {
            betData.sport.contains("футбол", ignoreCase = true) || betData.sport.contains("football", ignoreCase = true) -> "football"
            betData.sport.contains("хоккей", ignoreCase = true) || betData.sport.contains("hockey", ignoreCase = true) -> "hockey"
            betData.type in listOf(924, 927, 928) -> "football"
            else -> "football"
        }
    }
    
    // ==================== МЕТОД placeBet (для тестового режима) ====================
    
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
                Log.e(TAG, "❌ placeBet: Экспресс #$expId (dbId=$expressDbId) не найден в БД!")
                cursor.close()
                return false
            }
            
            val betAmount = cursor.getDouble(0).toInt()
            val totalKef = cursor.getDouble(1)
            val potentialWin = cursor.getDouble(2)
            cursor.close()
            
            val matchesInfo = betDataList.joinToString(" + ") { betData ->
                "${betData.home.take(15)} vs ${betData.away.take(15)} (${typeName(betData.type)})"
            }
            
            Log.d(TAG, "🎲 РАЗМЕЩЕНИЕ СТАВКИ #$expId (тестовый режим):")
            Log.d(TAG, "  expressDbId: $expressDbId")
            Log.d(TAG, "  Матчи: $matchesInfo")
            Log.d(TAG, "  Сумма: $betAmount ₽")
            Log.d(TAG, "  Кэф: ${"%.2f".format(totalKef)}")
            Log.d(TAG, "  Выигрыш: ${"%.2f".format(potentialWin)} ₽")
            
            handleSuccessfulBet(
                expressDbId = expressDbId,
                expId = expId,
                userId = userId,
                betAmount = betAmount,
                totalKef = totalKef,
                potentialWin = potentialWin,
                matchesInfo = matchesInfo,
                newBalance = null
            )
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Исключение в placeBet: ${e.message}", e)
            return false
        }
    }
    
    private fun handleSuccessfulBet(
        expressDbId: Long,
        expId: Int,
        userId: Long,
        betAmount: Int,
        totalKef: Double,
        potentialWin: Double,
        matchesInfo: String,
        newBalance: Double?
    ) {
        val db = dbHelper.writableDatabase
        val currentTime = System.currentTimeMillis() / 1000
        
        val calculatedPotentialWin = betAmount * totalKef
        
        val values = ContentValues().apply {
            put("is_bet_placed", 1)
            put("potential_win", calculatedPotentialWin)
            put("profit_loss", 0.0)
            put("updated_at", currentTime)
        }
        db.update("express_bets", values, "id = ?", arrayOf(expressDbId.toString()))
        
        val testMode = prefs.getBoolean("test_mode", true)
        val modeLabel = if (testMode) "[ТЕСТ]" else ""
        val displayBalance = newBalance?.let { "%.2f".format(it) } ?: "%.2f".format(balance)
        
        betNotificationCounter++
        val notificationId = BET_NOTIFICATION_BASE + betNotificationCounter
        
        val notification = createBetNotification(
            "$modeLabel ✅ Ставка принята!",
            "Экспресс #$expId | $betAmount ₽ | Кэф: ${"%.2f".format(totalKef)} | Выигрыш: ${"%.2f".format(calculatedPotentialWin)} ₽ | Баланс: $displayBalance ₽"
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, notification)
        
        val successMessage = "$modeLabel ✅ Ставка #$expId ПРИНЯТА! $betAmount ₽ × ${"%.2f".format(totalKef)} = ${"%.2f".format(calculatedPotentialWin)} ₽ | Баланс: $displayBalance ₽ | $matchesInfo"
        onLogUpdate?.invoke("[${getCurrentTime()}] $successMessage")
        onScoresUpdate?.invoke(successMessage)
        
        dbHelper.addLog(userId, "bet_placed",
            "Ставка #$expId: $betAmount ₽, кэф ${"%.2f".format(totalKef)}, баланс: $displayBalance ₽")
        
        newBalance?.let {
            if (it != balance) {
                balance = it
                lastBalance = it
                onBalanceUpdate?.invoke(it)
                
                val balanceValues = ContentValues().apply {
                    put("balans", it)
                    put("balance", it)
                    put("updated_at", currentTime)
                }
                db.update("express_bets", balanceValues, "id = ?", arrayOf(expressDbId.toString()))
            }
        }
    }
    
    // ==================== ОЧИСТКА НЕРАЗМЕЩЕННЫХ ЭКСПРЕССОВ ====================
    
    private fun cleanupUnplacedExpresses() {
        try {
            val db = dbHelper.writableDatabase
            val currentTime = System.currentTimeMillis() / 1000
            val fiveMinutesAgo = currentTime - 300
            
            val cursor = db.rawQuery(
                "SELECT id, id_exp FROM express_bets WHERE is_bet_placed = 0 AND sts_all = 0 AND ct < ?",
                arrayOf(fiveMinutesAgo.toString())
            )
            
            val toDelete = mutableListOf<Pair<Long, Int>>()
            while (cursor.moveToNext()) {
                toDelete.add(Pair(cursor.getLong(0), cursor.getInt(1)))
            }
            cursor.close()
            
            if (toDelete.isNotEmpty()) {
                Log.d(TAG, "🧹 Очистка ${toDelete.size} неразмещенных экспрессов:")
                toDelete.forEach { (id, idExp) ->
                    db.delete("express_events", "express_id = ?", arrayOf(id.toString()))
                    db.delete("express_bets", "id = ?", arrayOf(id.toString()))
                    Log.d(TAG, "  🗑 Удален экспресс #$idExp (не был размещен)")
                }
                onLogUpdate?.invoke("[${getCurrentTime()}] 🧹 Удалено ${toDelete.size} неразмещенных экспрессов")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка очистки: ${e.message}")
        }
    }
    
    // ==================== МЕТОДЫ ОБНОВЛЕНИЯ СЧЕТА ====================
    
    private fun updateActiveMatchesScores() {
        try {
            val db = dbHelper.readableDatabase
            val currentTime = System.currentTimeMillis() / 1000
            
            val cursor = db.rawQuery("""
                SELECT eb.id, eb.id_exp
                FROM express_bets eb
                WHERE eb.sts_all IN (0, 1, 2) AND eb.is_bet_placed = 1
            """, null)
            
            val activeExpresses = mutableListOf<Pair<Long, Int>>()
            while (cursor.moveToNext()) {
                activeExpresses.add(Pair(cursor.getLong(0), cursor.getInt(1)))
            }
            cursor.close()
            
            Log.d(TAG, "📊 updateActiveMatchesScores: экспрессов со статусом 0,1,2 и is_bet_placed=1: ${activeExpresses.size}")
            
            if (activeExpresses.isEmpty()) return
            
            activeExpresses.forEach { (expressDbId, expId) ->
                val eventsCursor = db.rawQuery("""
                    SELECT ee.id, ee.m_id, ee.bet_type, ee.status, ee.home_score, 
                           ee.away_score, ee.match_time, ee.match_start_time, 
                           ee.expected_end_time, ee.sport_type
                    FROM express_events ee
                    WHERE ee.express_id = ? 
                    AND ee.is_finalized = 0
                    AND (
                        ee.match_time < 100 
                        OR ee.match_time = 0
                        OR (ee.expected_end_time > 0 AND ee.expected_end_time > ?)
                    )
                """, arrayOf(expressDbId.toString(), currentTime.toString()))
                
                val events = mutableListOf<EventInfo>()
                while (eventsCursor.moveToNext()) {
                    val event = EventInfo(
                        id = eventsCursor.getLong(0),
                        mId = eventsCursor.getInt(1),
                        betType = eventsCursor.getInt(2),
                        status = eventsCursor.getInt(3),
                        homeScore = eventsCursor.getInt(4),
                        awayScore = eventsCursor.getInt(5),
                        matchTime = eventsCursor.getInt(6),
                        matchStartTime = eventsCursor.getInt(7),
                        expectedEndTime = eventsCursor.getLong(8),
                        sportType = eventsCursor.getString(9) ?: "football"
                    )
                    
                    if (currentTime >= event.expectedEndTime && event.expectedEndTime > 0) {
                        Log.d(TAG, "⏰ Матч #${event.mId}: время истекло, финализируем")
                        checkMatchTimeout(event, expressDbId, expId)
                    } else {
                        events.add(event)
                    }
                }
                eventsCursor.close()
                
                events.forEach { event ->
                    Log.d(TAG, "🔄 Обновляем счет матча #${event.mId} (время=${event.matchTime}')")
                    updateMatchScore(event, expressDbId, expId)
                }
                
                checkExpressStatus(expressDbId, expId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления счетов: ${e.message}", e)
        }
    }
    
    private fun updateMatchScore(event: EventInfo, expressId: Long, expId: Int) {
        apiClient.getMatchScore(
            matchId = event.mId,
            onSuccess = { matchFactors: ApiClient.MatchFactors? ->
                if (matchFactors != null) {
                    val sh = matchFactors.score1
                    val sa = matchFactors.score2
                    val matchTime = matchFactors.matchTime
                    
                    Log.d(TAG, "📊 Матч #${event.mId}: счет $sh:$sa, время ${matchTime}'")
                    
                    val db = dbHelper.writableDatabase
                    
                    val updateValues = ContentValues().apply {
                        put("home_score", sh)
                        put("away_score", sa)
                        put("match_time", matchTime)
                        put("updated_at", System.currentTimeMillis() / 1000)
                    }
                    db.update("express_events", updateValues, "id = ?", arrayOf(event.id.toString()))
                    
                    event.homeScore = sh
                    event.awayScore = sa
                    event.matchTime = matchTime
                    
                    val newStatus = checkMatchStatus(event.betType, sh, sa)
                    
                    if (newStatus != event.status) {
                        val statusValues = ContentValues().apply {
                            put("status", newStatus)
                            put("updated_at", System.currentTimeMillis() / 1000)
                        }
                        db.update("express_events", statusValues, "id = ?", arrayOf(event.id.toString()))
                        
                        event.status = newStatus
                        
                        val statusText = when (newStatus) {
                            2 -> "ЗАХОДИТ ✅"
                            1 -> "НЕ ЗАХОДИТ ❌"
                            else -> "АКТИВЕН 🔄"
                        }
                        
                        val message = "📊 Матч #${event.mId}: $sh-$sa (${matchTime}') | $statusText"
                        onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                        onScoresUpdate?.invoke(message)
                        
                        checkExpressStatus(expressId, expId)
                    }
                } else {
                    checkMatchTimeout(event, expressId, expId)
                }
            },
            onError = { error: String ->
                Log.e(TAG, "❌ Ошибка получения счета матча #${event.mId}: $error")
                checkMatchTimeout(event, expressId, expId)
            }
        )
    }
    
    private fun checkMatchTimeout(event: EventInfo, expressId: Long, expId: Int) {
        val db = dbHelper.writableDatabase
        val currentTime = System.currentTimeMillis() / 1000
        
        val cursor = db.query("express_events",
            arrayOf("match_time", "match_start_time", "expected_end_time",
                    "sport_type", "home_score", "away_score", "created_at"),
            "id = ?", arrayOf(event.id.toString()), null, null, null)
        
        var matchTime = event.matchTime
        var matchStartTime = 0
        var expectedEndTime = 0L
        var sportType = "football"
        var homeScore = event.homeScore
        var awayScore = event.awayScore
        var createdAt = 0L
        
        if (cursor.moveToFirst()) {
            matchTime = cursor.getInt(0)
            matchStartTime = cursor.getInt(1)
            expectedEndTime = cursor.getLong(2)
            sportType = cursor.getString(3) ?: "football"
            homeScore = cursor.getInt(4)
            awayScore = cursor.getInt(5)
            createdAt = cursor.getLong(6)
        }
        cursor.close()
        
        val matchAge = currentTime - createdAt
        
        val shouldFinalize = when {
            expectedEndTime > 0 && currentTime >= expectedEndTime -> true
            matchTime >= getMaxMatchDuration(sportType) -> true
            matchAge > 14400 -> true
            matchStartTime > 0 && matchAge > calculateExpectedEndTimeSeconds(
                matchStartMinute = matchStartTime,
                sportType = sportType,
                currentMatchTime = matchTime
            ) -> true
            else -> false
        }
        
        if (shouldFinalize) {
            val status = checkMatchStatus(event.betType, homeScore, awayScore)
            
            val updateValues = ContentValues().apply {
                put("status", status)
                put("is_finalized", 1)
                put("updated_at", currentTime)
            }
            db.update("express_events", updateValues, "id = ?", arrayOf(event.id.toString()))
            
            event.status = status
            
            val statusText = when (status) {
                2 -> "ЗАХОДИТ ✅"
                1 -> "НЕ ЗАХОДИТ ❌"
                else -> "ЗАВЕРШЕН"
            }
            
            val message = "⏰ Матч #${event.mId} завершен по таймауту (${matchTime}', счет $homeScore:$awayScore) | $statusText"
            onLogUpdate?.invoke("[${getCurrentTime()}] $message")
            onScoresUpdate?.invoke(message)
            
            checkExpressStatus(expressId, expId)
        }
    }
    
    private fun checkMatchStatus(betType: Int, sh: Int, sa: Int): Int = when (betType) {
        924 -> if (sh >= sa) 2 else 1
        927 -> if (sh + 1 >= sa) 2 else 1
        928 -> if (sa + 1 >= sh) 2 else 1
        else -> 1
    }
    
    private fun getMaxMatchDuration(sportType: String): Int {
        return when (sportType) {
            "football" -> 130
            "hockey" -> 100
            else -> 150
        }
    }
    
    private fun checkExpressStatus(expressId: Long, expId: Int) {
        try {
            val db = dbHelper.writableDatabase
            val currentTime = System.currentTimeMillis() / 1000
            
            val cursor = db.query("express_events",
                arrayOf("id", "status", "is_finalized", "home_score", "away_score",
                        "m_id", "bet_type", "match_time", "expected_end_time", "start_odds"),
                "express_id = ?",
                arrayOf(expressId.toString()), null, null, null)
            
            var anyLost = false
            var allWin = true
            var hasUnfinished = false
            var totalEvents = 0
            var finishedEvents = 0
            var actualTotalKef = 1.0
            val matchDetails = mutableListOf<String>()
            val eventsToFinalize = mutableListOf<Triple<Long, Int, Double>>()
            
            while (cursor.moveToNext()) {
                totalEvents++
                val eventId = cursor.getLong(0)
                val status = cursor.getInt(1)
                val isFinalized = cursor.getInt(2) == 1
                val sh = cursor.getInt(3)
                val sa = cursor.getInt(4)
                val mId = cursor.getInt(5)
                val betType = cursor.getInt(6)
                val matchTime = cursor.getInt(7)
                val expectedEndTime = cursor.getLong(8)
                val startOdds = cursor.getDouble(9)
                
                val shouldFinalize = when {
                    isFinalized -> false
                    matchTime >= 100 -> true
                    (expectedEndTime > 0 && currentTime >= expectedEndTime) -> true
                    else -> false
                }
                
                val effectiveStatus = if (shouldFinalize && !isFinalized) {
                    val autoStatus = checkMatchStatus(betType, sh, sa)
                    eventsToFinalize.add(Triple(eventId, autoStatus, startOdds))
                    autoStatus
                } else {
                    status
                }
                
                if (!isFinalized && !shouldFinalize) {
                    hasUnfinished = true
                }
                
                if (isFinalized || shouldFinalize) {
                    finishedEvents++
                    if (effectiveStatus == 2) {
                        actualTotalKef *= startOdds
                    }
                }
                
                when (effectiveStatus) {
                    1 -> {
                        anyLost = true
                        allWin = false
                        matchDetails.add("❌ #$mId: $sh-$sa (${typeName(betType)}) кэф=$startOdds")
                    }
                    2 -> {
                        matchDetails.add("✅ #$mId: $sh-$sa (${typeName(betType)}) кэф=$startOdds")
                    }
                    else -> {
                        allWin = false
                        matchDetails.add("🔄 #$mId: $sh-$sa (${typeName(betType)}) кэф=$startOdds")
                    }
                }
            }
            cursor.close()
            
            for ((eventId, finalStatus, odds) in eventsToFinalize) {
                val updateValues = ContentValues().apply {
                    put("status", finalStatus)
                    put("is_finalized", 1)
                    put("current_odds", odds)
                    put("updated_at", currentTime)
                }
                db.update("express_events", updateValues, "id = ?", arrayOf(eventId.toString()))
            }
            
            val expressStatus = when {
                hasUnfinished -> 0
                anyLost -> 1
                allWin -> 2
                finishedEvents == totalEvents -> { if (anyLost) 1 else 2 }
                else -> 0
            }
            
            val expressCursor = db.query("express_bets",
                arrayOf("sumbet", "kfall", "sts_all", "balans", "potential_win", "profit_loss"),
                "id = ?", arrayOf(expressId.toString()), null, null, null)
            
            var betAmount = 0.0
            var originalKef = 0.0
            var oldStatus = 0
            var currentBalance = 0.0
            var oldPotentialWin = 0.0
            var oldProfitLoss = 0.0
            
            if (expressCursor.moveToFirst()) {
                betAmount = expressCursor.getDouble(0)
                originalKef = expressCursor.getDouble(1)
                oldStatus = expressCursor.getInt(2)
                currentBalance = expressCursor.getDouble(3)
                oldPotentialWin = expressCursor.getDouble(4)
                oldProfitLoss = expressCursor.getDouble(5)
            }
            expressCursor.close()
            
            val potentialWin: Double
            val profitLoss: Double
            
            when (expressStatus) {
                2 -> {
                    val effectiveKef = if (actualTotalKef > 1.0 && finishedEvents == totalEvents) {
                        actualTotalKef
                    } else if (allWin && actualTotalKef == 1.0) {
                        originalKef
                    } else {
                        actualTotalKef
                    }
                    
                    potentialWin = betAmount * effectiveKef
                    profitLoss = potentialWin - betAmount
                    
                    Log.d(TAG, "  💰 ВЫИГРЫШ: ставка=$betAmount × кэф=${"%.3f".format(effectiveKef)} = ${"%.2f".format(potentialWin)}")
                }
                1 -> {
                    potentialWin = 0.0
                    profitLoss = -betAmount
                    Log.d(TAG, "  💸 ПРОИГРЫШ: -${"%.0f".format(betAmount)}")
                }
                else -> {
                    potentialWin = oldPotentialWin
                    profitLoss = oldProfitLoss
                }
            }
            
            val newBalance = when {
                expressStatus != oldStatus && expressStatus != 0 -> {
                    if (expressStatus == 2) currentBalance + profitLoss
                    else currentBalance + profitLoss
                }
                else -> currentBalance
            }
            
            if (expressStatus != oldStatus || potentialWin != oldPotentialWin || profitLoss != oldProfitLoss) {
                val updateValues = ContentValues().apply {
                    put("sts_all", expressStatus)
                    put("potential_win", potentialWin)
                    put("profit_loss", profitLoss)
                    put("balans", newBalance)
                    put("total_odds", if (actualTotalKef > 1.0) actualTotalKef else originalKef)
                    put("updated_at", currentTime)
                }
                db.update("express_bets", updateValues, "id = ?", arrayOf(expressId.toString()))
            }
            
            if (expressStatus != oldStatus || expressStatus in listOf(1, 2)) {
                val statusText = when (expressStatus) {
                    2 -> "ВЫИГРАЛ 🏆"
                    1 -> "ПРОИГРАЛ ❌"
                    else -> "АКТИВЕН 🔄"
                }
                
                val message = buildString {
                    append("🎯 Экспресс #$expId ")
                    append("($finishedEvents/$totalEvents матчей) ")
                    append("$statusText")
                    if (expressStatus == 2) {
                        append(" | Выигрыш: ${"%.2f".format(potentialWin)} ₽")
                        append(" | Прибыль: +${"%.2f".format(profitLoss)} ₽")
                    } else if (expressStatus == 1) {
                        append(" | Убыток: ${"%.0f".format(betAmount)} ₽")
                    }
                    append(" | Баланс: ${"%.2f".format(newBalance)} ₽")
                    append("\n")
                    matchDetails.forEach { append("$it ") }
                }
                
                onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                onScoresUpdate?.invoke(message)
                
                Log.d(TAG, message)
                
                if (expressStatus != 0) {
                    balance = newBalance
                    lastBalance = newBalance
                    onBalanceUpdate?.invoke(newBalance)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки статуса экспресса #$expId: ${e.message}", e)
        }
    }
    
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
                "express_id = ?", arrayOf(oldExpressId.toString()), null, null, null)
            val bets = mutableListOf<Pair<Int, Int>>()
            while (eventsCursor.moveToNext()) bets.add(Pair(eventsCursor.getInt(0), eventsCursor.getInt(1)))
            eventsCursor.close()
            
            if (bets.isNotEmpty()) {
                val newExpId = (System.currentTimeMillis() / 1000).toInt()
                onLogUpdate?.invoke("[${getCurrentTime()}] 🔄 Замена экспресса #$oldExpId → #$newExpId (ставка: ${currentBetAmount.toInt()} → ${newBetAmount.toInt()} ₽)")
                
                prefs.edit().putString("bet_amount", newBetAmount.toInt().toString()).apply()
                
                db.update("express_bets", ContentValues().apply {
                    put("sts_all", -1)
                    put("id_exp_replace", newExpId)
                    put("updated_at", currentTime)
                }, "id = ?", arrayOf(oldExpressId.toString()))
                
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
    
    fun canCreateNewExpress(): Boolean {
        val maxActive = prefs.getInt("max_active_expresses", 5)
        val current = getActiveExpressCount()
        val canCreate = current < maxActive
        
        Log.d(TAG, "📊 Проверка лимита: $current/$maxActive, создание " +
                  if (canCreate) "разрешено ✅" else "запрещено ❌")
        
        if (!canCreate) {
            onLogUpdate?.invoke("[${getCurrentTime()}] ⏭ Лимит экспрессов ($current/$maxActive)")
        }
        
        return canCreate
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
        val id: Long, val mId: Int, val betType: Int, var status: Int,
        var homeScore: Int, var awayScore: Int, var matchTime: Int = 0,
        var matchStartTime: Int = 0, var expectedEndTime: Long = 0,
        var sportType: String = "football"
    )
    
    data class ConflictInfo(
        val eventId: Long,
        val expressId: Long,
        val idExp: Int,
        val status: Int,
        val isFinalized: Boolean,
        val mId: Int
    )
}