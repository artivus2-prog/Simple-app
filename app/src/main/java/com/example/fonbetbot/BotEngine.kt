// BotEngine.kt - ИСПРАВЛЕННАЯ ВЕРСИЯ (ошибки компиляции устранены)
package com.example.fonbetbot

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class BotEngine(
    private val context: Context,
    private val dbHelper: DatabaseHelper,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "BotEngine"
    }

    private val apiClient = ApiClient()
    private var authData: AuthData? = null
    private var balance = 0.0

    // Собственный Job для управления жизненным циклом
    private var engineJob: Job? = null

    // Колбеки для оповещения UI
    var onBalanceUpdate: ((Double) -> Unit)? = null
    var onLogUpdate: ((String) -> Unit)? = null
    var onBetsUpdate: ((List<Pair<Int, Int>>) -> Unit)? = null
    var onScoresUpdate: ((String) -> Unit)? = null

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    }

    fun setAuthData(data: AuthData?) {
        authData = data
    }

    // ==================== ЗАПУСК / ОСТАНОВКА ====================
    suspend fun startEngine() {
        if (authData == null) {
            onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Нет данных авторизации")
            return
        }

        // Отменяем предыдущий запуск, если был
        engineJob?.cancel()
        engineJob = Job()
        val engineScope = CoroutineScope(Dispatchers.IO + engineJob!!)

        // Загружаем начальный баланс из БД
        loadBalanceFromDb()

        // Сохраняем сессию
        try {
            val user = dbHelper.getUser(authData!!.fsid, authData!!.deviceId)
            user?.let {
                dbHelper.startBotSession(it.id, balance)
                dbHelper.addLog(it.id, "start", "Бот запущен (Engine)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения сессии: ${e.message}")
        }

        onLogUpdate?.invoke("[${getCurrentTime()}] 🚀 Бот запущен")

        // Запускаем циклы в СВОЁМ скоупе
        launchBalanceLoop(engineScope)
        launchBetsLoop(engineScope)
        launchScoreLoop(engineScope)
        launchReplacementLoop(engineScope)
    }

    suspend fun stopEngine() {
        // Отменяем ВСЕ циклы
        engineJob?.cancel()
        engineJob = null

        try {
            authData?.let { data ->
                val user = dbHelper.getUser(data.fsid, data.deviceId)
                user?.let {
                    dbHelper.stopBotSession(it.id, "user_stop")
                    dbHelper.addLog(it.id, "stop", "Бот остановлен (Engine)")
                }
            }
        } catch (_: Exception) {
        }
        onLogUpdate?.invoke("[${getCurrentTime()}] ⏹ Бот остановлен")
    }

    // ==================== ЦИКЛЫ (с обработкой CancellationException) ====================
    private fun launchBalanceLoop(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try {
                    delay(60_000)
                    fetchBalance()
                } catch (e: CancellationException) {
                    Log.d(TAG, "Цикл баланса остановлен")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка в цикле баланса: ${e.message}")
                }
            }
        }
    }

    private fun launchBetsLoop(scope: CoroutineScope) {
        scope.launch {
            delay(5_000)
            while (isActive) {
                try {
                    fetchBets()
                    delay(30_000)
                } catch (e: CancellationException) {
                    Log.d(TAG, "Цикл ставок остановлен")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка в цикле ставок: ${e.message}")
                }
            }
        }
    }

    private fun launchScoreLoop(scope: CoroutineScope) {
        scope.launch {
            delay(10_000)
            while (isActive) {
                try {
                    updateActiveMatchesScores()
                    delay(60_000)
                } catch (e: CancellationException) {
                    Log.d(TAG, "Цикл счёта остановлен")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка в цикле счёта: ${e.message}")
                }
            }
        }
    }

    private fun launchReplacementLoop(scope: CoroutineScope) {
        scope.launch {
            delay(60_000)
            while (isActive) {
                try {
                    checkAndCreateReplacementExpress()
                    delay(3_600_000)
                } catch (e: CancellationException) {
                    Log.d(TAG, "Цикл замены остановлен")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка в цикле замены: ${e.message}")
                }
            }
        }
    }

    // ==================== БАЛАНС ====================
    private suspend fun fetchBalance() {
        val data = authData ?: return
        val cookies = getCookies()

        suspendCancellableCoroutine<Unit> { continuation ->
            apiClient.getSaldo(
                cookies = cookies,
                fsid = data.fsid,
                deviceId = data.deviceId,
                onSuccess = { sessionInfo ->
                    if (sessionInfo != null && sessionInfo.saldo != null) {
                        val newBalance = sessionInfo.saldo
                        val oldBalance = balance
                        balance = newBalance
                        BotForegroundService.lastBalance = newBalance
                        onBalanceUpdate?.invoke(newBalance)

                        try {
                            val user = dbHelper.getUser(data.fsid, data.deviceId)
                            user?.let { u ->
                                dbHelper.saveBalance(u.id, newBalance)
                                // Сохраняем clientId и userName из ответа API
                                dbHelper.updateUserInfo(u.id, sessionInfo.clientId, sessionInfo.userName)
                                if (newBalance > oldBalance && oldBalance > 0) {
                                    dbHelper.addLog(
                                        u.id, "profit",
                                        "Профит: +%.2f ₽".format(newBalance - oldBalance)
                                    )
                                    onLogUpdate?.invoke("[${getCurrentTime()}] 💰 Профит: +%.2f ₽".format(newBalance - oldBalance))
                                } else if (newBalance < oldBalance && oldBalance > 0) {
                                    dbHelper.addLog(
                                        u.id, "loss",
                                        "Убыток: %.2f ₽".format(newBalance - oldBalance)
                                    )
                                    onLogUpdate?.invoke("[${getCurrentTime()}] 📉 Убыток: %.2f ₽".format(newBalance - oldBalance))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка сохранения баланса: ${e.message}")
                        }
                    }
                    continuation.resume(Unit)
                },
                onError = { error ->
                    onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Баланс: $error")
                    dbHelper.addLog(null, "error", "Баланс: $error", "ERROR")
                    continuation.resume(Unit)
                }
            )
        }
    }

    // ==================== СТАВКИ ====================
    private suspend fun fetchBets() {
        val data = authData ?: return
        try {
            val user = dbHelper.getUser(data.fsid, data.deviceId) ?: return
            val settings = buildBetSettings()

            suspendCancellableCoroutine<Unit> { continuation ->
                apiClient.getBets(
                    userId = user.id,
                    settings = settings,
                    onSuccess = { betDataList ->
                        if (betDataList.isEmpty()) {
                            continuation.resume(Unit)
                            return@getBets
                        }
                        val matchIds = betDataList.map { it.mId }.sorted()
                        val key = matchIds.joinToString(",")
                        if (isExpressAlreadyExists(user.id, matchIds)) {
                            onLogUpdate?.invoke("[${getCurrentTime()}] ⏭ Экспресс уже обработан: $key")
                            continuation.resume(Unit)
                            return@getBets
                        }
                        onLogUpdate?.invoke("[${getCurrentTime()}] 🎲 Получен экспресс: $key")
                        betDataList.forEach { betData ->
                            onLogUpdate?.invoke(
                                "[${getCurrentTime()}] 📊 Матч #${betData.mId}: " +
                                        "${betData.home} vs ${betData.away} | " +
                                        "Кэф: ${"%.2f".format(betData.startKf)} | " +
                                        "Тип: ${typeName(betData.type)}"
                            )
                        }
                        onBetsUpdate?.invoke(betDataList.map { Pair(it.mId, it.type) })

                        val newExpId = (System.currentTimeMillis() / 1000).toInt()
                        val expressDbId = saveExpressToDb(user.id, newExpId, betDataList)
                        if (expressDbId > 0) {
                            // Запускаем placeBet в отдельной корутине
                            CoroutineScope(Dispatchers.IO).launch {
                                placeBet(expressDbId, newExpId, user.id, betDataList)
                            }
                        }
                        continuation.resume(Unit)
                    },
                    onError = { error ->
                        onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ставки: $error")
                        continuation.resume(Unit)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchBets error", e)
        }
    }

    private suspend fun placeBet(
        expressDbId: Long,
        expId: Int,
        userId: Long,
        betDataList: List<ApiClient.BetData>
    ) {
        val testMode = prefs.getBoolean("test_mode", true)
        if (testMode) {
            placeTestBet(expressDbId, expId, userId, betDataList)
        } else {
            placeRealBet(expressDbId, expId, userId, betDataList)
        }
    }

    // ==================== ТЕСТОВАЯ СТАВКА ====================
    private fun placeTestBet(
        expressDbId: Long,
        expId: Int,
        userId: Long,
        betDataList: List<ApiClient.BetData>
    ) {
        val db = dbHelper.writableDatabase
        val cursor = db.query(
            "express_bets", arrayOf("sumbet", "kfall", "potential_win"),
            "id = ?", arrayOf(expressDbId.toString()), null, null, null
        )
        if (!cursor.moveToFirst()) {
            cursor.close()
            return
        }
        val betAmount = cursor.getDouble(0)
        val totalKef = cursor.getDouble(1)
        val potentialWin = cursor.getDouble(2)
        cursor.close()

        val matchesInfo = betDataList.joinToString(" + ") { "${it.home.take(15)} vs ${it.away.take(15)}" }
        onLogUpdate?.invoke(
            "[${getCurrentTime()}] 🧪 ТЕСТ #$expId: " +
                    "${betAmount.toInt()}₽ × ${"%.2f".format(totalKef)} = ${"%.2f".format(potentialWin)}₽ | $matchesInfo"
        )
        onScoresUpdate?.invoke(
            "🧪 ТЕСТ #$expId: ${betAmount.toInt()}₽ × ${"%.2f".format(totalKef)} = ${"%.2f".format(potentialWin)}₽"
        )
        dbHelper.addLog(userId, "test_express", "ТЕСТ #$expId: ${betAmount.toInt()}₽", context = "test_mode")
        showBetNotification(
            "🧪 ТЕСТ: Экспресс #$expId",
            "${betAmount.toInt()}₽ | Кэф: ${"%.2f".format(totalKef)} | $matchesInfo"
        )
    }

    // ==================== РЕАЛЬНАЯ СТАВКА ====================
    private suspend fun placeRealBet(
        expressDbId: Long,
        expId: Int,
        userId: Long,
        betDataList: List<ApiClient.BetData>
    ) {
        val data = authData ?: return
        val cookies = getCookies()
        if (cookies.isEmpty()) {
            onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Нет cookies для реальной ставки")
            return
        }

        // ✅ ВЫЗЫВАЕМ ДО ВСЕХ КОЛБЕКОВ
        val clientId = getClientId()
        onLogUpdate?.invoke("[${getCurrentTime()}] 📋 Запрос betSlipInfo (clientId=$clientId)...")

        apiClient.getBetSlipInfo(
            bets = betDataList,
            cookies = cookies,
            fsid = data.fsid,
            deviceId = data.deviceId,
            clientId = clientId,
            onSuccess = { slipInfo ->
                onLogUpdate?.invoke("[${getCurrentTime()}] ✅ betSlipInfo: K=${"%.2f".format(slipInfo.totalK)}, мин=${slipInfo.minSum.toInt()}₽")
                val betAmount = prefs.getString("bet_amount", "30")?.toDoubleOrNull() ?: 30.0
                if (betAmount < slipInfo.minSum) {
                    onLogUpdate?.invoke("[${getCurrentTime()}] ⚠️ Сумма ${betAmount.toInt()}₽ меньше мин. ${slipInfo.minSum.toInt()}₽")
                }

                apiClient.getBetRequestId(
                    cookies = cookies,
                    fsid = data.fsid,
                    deviceId = data.deviceId,
                    clientId = clientId,
                    onSuccess = { requestId ->
                        onLogUpdate?.invoke("[${getCurrentTime()}] ✅ requestId: $requestId")
                        apiClient.placeRealBet(
                            requestId = requestId,
                            betSlipInfo = slipInfo,
                            amount = betAmount,
                            bets = betDataList,
                            cookies = cookies,
                            fsid = data.fsid,
                            clientId = clientId,
                            onSuccess = { placeResponse ->
                                if (placeResponse.result == "betDelay") {
                                    onLogUpdate?.invoke("[${getCurrentTime()}] ⏳ Ставка в обработке (betDelay=${placeResponse.betDelay}мс)...")
                                    // Запускаем отложенный запрос в отдельной корутине
                                    CoroutineScope(Dispatchers.IO).launch {
                                        delay(placeResponse.betDelay.toLong())
                                        apiClient.getBetResult(
                                            requestId = requestId,
                                            cookies = cookies,
                                            fsid = data.fsid,
                                            deviceId = data.deviceId,
                                            clientId = clientId,
                                            onSuccess = { result ->
                                                processRealBetResult(
                                                    expressDbId, expId, userId, betDataList,
                                                    betAmount, slipInfo.totalK, result
                                                )
                                            },
                                            onError = { error ->
                                                onLogUpdate?.invoke("[${getCurrentTime()}] ❌ betResult ошибка: $error")
                                                dbHelper.addLog(userId, "bet_error", "betResult: $error", "ERROR")
                                            }
                                        )
                                    }
                                } else {
                                    processRealBetResult(
                                        expressDbId, expId, userId, betDataList,
                                        betAmount, slipInfo.totalK,
                                        ApiClient.BetResultResponse(placeResponse.rawResponse)
                                    )
                                }
                            },
                            onError = { error ->
                                onLogUpdate?.invoke("[${getCurrentTime()}] ❌ placeBet: $error")
                                dbHelper.addLog(userId, "bet_error", "placeBet: $error", "ERROR")
                            }
                        )
                    },
                    onError = { error ->
                        onLogUpdate?.invoke("[${getCurrentTime()}] ❌ requestId: $error")
                        dbHelper.addLog(userId, "bet_error", "requestId: $error", "ERROR")
                    }
                )
            },
            onError = { error ->
                onLogUpdate?.invoke("[${getCurrentTime()}] ❌ betSlipInfo: $error")
                dbHelper.addLog(userId, "bet_error", "betSlipInfo: $error", "ERROR")
            }
        )
    }

    // ✅ НЕ suspend — вызывается из обычных колбеков
    private fun processRealBetResult(
        expressDbId: Long,
        expId: Int,
        userId: Long,
        betDataList: List<ApiClient.BetData>,
        betAmount: Double,
        totalKef: Double,
        resultResponse: ApiClient.BetResultResponse
    ) {
        val json = resultResponse.rawResponse
        val result = json.optString("result", "")
        Log.d(TAG, "betResult: $result, full=${json.toString().take(500)}")

        if (result == "couponResult") {
            val coupon = json.optJSONObject("coupon")
            val resultCode = coupon?.optInt("resultCode", -1) ?: -1
            val regId = coupon?.optString("regId", "—") ?: "—"
            val saldo = coupon?.optDouble("clientSaldo", 0.0) ?: 0.0
            val errorMsg = coupon?.optString("errorMessage", "Неизвестная ошибка") ?: "Неизвестная ошибка"

            if (resultCode == 0) {
                // Успех
                val db = dbHelper.writableDatabase
                db.update(
                    "express_bets", ContentValues().apply {
                        put("is_bet_placed", 1)
                        put("sts_all", 1)
                        put("strategy", "real")
                        put("updated_at", System.currentTimeMillis() / 1000)
                    }, "id = ?", arrayOf(expressDbId.toString())
                )

                val potentialWin = betAmount * totalKef
                val message = "✅ РЕАЛЬНАЯ СТАВКА #$expId | ${betAmount.toInt()}₽ × ${"%.2f".format(totalKef)} = ${"%.2f".format(potentialWin)}₽ | №$regId"
                onLogUpdate?.invoke("[${getCurrentTime()}] $message")
                onScoresUpdate?.invoke(message)
                dbHelper.addLog(
                    userId, "bet_placed",
                    "РЕАЛ #$expId: ${betAmount.toInt()}₽, кэф ${"%.2f".format(totalKef)}, №$regId, баланс: ${"%.2f".format(saldo)}₽"
                )
                showBetNotification(
                    "✅ Ставка #$expId принята!",
                    "${betAmount.toInt()}₽ | Кэф: ${"%.2f".format(totalKef)} | Выигрыш: ${"%.2f".format(potentialWin)}₽ | №$regId"
                )

                if (saldo > 0) {
                    balance = saldo
                    BotForegroundService.lastBalance = saldo
                    onBalanceUpdate?.invoke(saldo)
                    dbHelper.saveBalance(userId, saldo, "bet_placed")
                }
            } else {
                // Отклонена
                onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Ставка #$expId отклонена: код=$resultCode, $errorMsg")
                dbHelper.writableDatabase.update(
                    "express_bets", ContentValues().apply {
                        put("is_bet_placed", 0)
                        put("sts_all", 0)
                        put("strategy", "real_rejected")
                        put("updated_at", System.currentTimeMillis() / 1000)
                    }, "id = ?", arrayOf(expressDbId.toString())
                )
                dbHelper.addLog(userId, "bet_rejected", "Отклонена #$expId: код=$resultCode, $errorMsg", "ERROR")
            }
        } else {
            onLogUpdate?.invoke("[${getCurrentTime()}] ⚠️ Неожиданный результат: $result")
            dbHelper.addLog(userId, "bet_unknown", "Неожиданный результат #$expId: $result", "WARNING")
        }
    }

    // ==================== ОБНОВЛЕНИЕ СЧЕТА МАТЧЕЙ ====================
    private suspend fun updateActiveMatchesScores() {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "express_bets", arrayOf("id", "id_exp", "user_id"),
            "sts_all IN (0,1,2)", null, null, null, null
        )
        val expresses = mutableListOf<Triple<Long, Int, Long>>()
        while (cursor.moveToNext()) {
            expresses.add(Triple(cursor.getLong(0), cursor.getInt(1), cursor.getLong(2)))
        }
        cursor.close()

        expresses.forEach { (expressDbId, expId, userId) ->
            val events = getActiveEvents(expressDbId)
            events.forEach { event -> updateSingleMatchScore(event, expressDbId, expId, userId) }
        }
    }

    private fun getActiveEvents(expressId: Long): List<EventInfo> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "express_events",
            arrayOf("id", "m_id", "bet_type", "status", "home_score", "away_score", "match_time"),
            "express_id = ? AND is_finalized = 0", arrayOf(expressId.toString()), null, null, null
        )
        val events = mutableListOf<EventInfo>()
        while (cursor.moveToNext()) {
            events.add(
                EventInfo(
                    cursor.getLong(0), cursor.getInt(1), cursor.getInt(2),
                    cursor.getInt(3), cursor.getInt(4), cursor.getInt(5), cursor.getInt(6)
                )
            )
        }
        cursor.close()
        return events
    }

    private suspend fun updateSingleMatchScore(event: EventInfo, expressDbId: Long, expId: Int, userId: Long) {
        suspendCancellableCoroutine<Unit> { continuation ->
            apiClient.getMatchScore(
                matchId = event.mId,
                onSuccess = { factors ->
                    if (factors != null) {
                        val sh = factors.score1
                        val sa = factors.score2
                        val time = factors.matchTime
                        dbHelper.writableDatabase.update(
                            "express_events", ContentValues().apply {
                                put("home_score", sh)
                                put("away_score", sa)
                                put("match_time", time)
                                put("updated_at", System.currentTimeMillis() / 1000)
                            }, "id = ?", arrayOf(event.id.toString())
                        )
                        event.homeScore = sh
                        event.awayScore = sa
                        event.matchTime = time

                        val newStatus = checkMatchStatus(event.betType, sh, sa)
                        if (newStatus != event.status) {
                            dbHelper.writableDatabase.update(
                                "express_events", ContentValues().apply {
                                    put("status", newStatus)
                                    put("updated_at", System.currentTimeMillis() / 1000)
                                }, "id = ?", arrayOf(event.id.toString())
                            )
                            event.status = newStatus
                            val txt = if (newStatus == 2) "ЗАШЁЛ ✅" else "НЕ ЗАШЁЛ ❌"
                            onLogUpdate?.invoke("[${getCurrentTime()}] 📊 Матч #${event.mId}: $sh-$sa (${time}') | $txt")
                            onScoresUpdate?.invoke("📊 Матч #${event.mId}: $sh-$sa | $txt")
                            dbHelper.addLog(userId, "match_status", "Матч ${event.mId}: $sh-$sa, время: ${time}', статус: $newStatus")
                            checkExpressStatus(expressDbId, expId, userId)
                        }
                    } else {
                        checkMatchTimeout(event, expressDbId, expId, userId)
                    }
                    continuation.resume(Unit)
                },
                onError = { error ->
                    Log.e(TAG, "Ошибка счёта матча #${event.mId}: $error")
                    checkMatchTimeout(event, expressDbId, expId, userId)
                    continuation.resume(Unit)
                }
            )
        }
    }

    private fun checkMatchTimeout(event: EventInfo, expressDbId: Long, expId: Int, userId: Long) {
        val currentTime = System.currentTimeMillis() / 1000
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "express_events",
            arrayOf("match_time", "created_at", "home_score", "away_score"),
            "id = ?", arrayOf(event.id.toString()), null, null, null
        )
        if (cursor.moveToFirst()) {
            val matchTime = cursor.getInt(0)
            val createdAt = cursor.getLong(1)
            val homeScore = cursor.getInt(2)
            val awayScore = cursor.getInt(3)
            cursor.close()
            val matchAge = currentTime - createdAt
            if ((matchAge > 5400 && matchTime >= 90) || matchAge > 7200) {
                val status = checkMatchStatus(event.betType, homeScore, awayScore)
                dbHelper.writableDatabase.update(
                    "express_events", ContentValues().apply {
                        put("status", status)
                        put("is_finalized", 1)
                        put("updated_at", currentTime)
                    }, "id = ?", arrayOf(event.id.toString())
                )
                val txt = if (status == 2) "ЗАШЁЛ ✅" else "НЕ ЗАШЁЛ ❌"
                onLogUpdate?.invoke("[${getCurrentTime()}] ⏰ Матч #${event.mId} завершён (таймаут): $homeScore-$awayScore | $txt")
                onScoresUpdate?.invoke("⏰ Матч #${event.mId}: $homeScore-$awayScore | $txt")
                dbHelper.addLog(userId, "match_finished", "Матч ${event.mId} финализирован (таймаут): $txt")
                checkExpressStatus(expressDbId, expId, userId)
            }
        } else {
            cursor.close()
        }
    }

    private fun checkExpressStatus(expressId: Long, expId: Int, userId: Long) {
        val db = dbHelper.writableDatabase
        val cursor = db.query(
            "express_events", arrayOf("status", "is_finalized"),
            "express_id = ?", arrayOf(expressId.toString()), null, null, null
        )
        var anyLost = false
        var allWin = true
        var hasUnfinished = false
        var total = 0
        while (cursor.moveToNext()) {
            total++
            if (cursor.getInt(1) == 0) hasUnfinished = true
            if (cursor.getInt(0) == 1) anyLost = true
            if (cursor.getInt(0) != 2) allWin = false
        }
        cursor.close()

        val expressStatus = if (hasUnfinished) 0 else when {
            anyLost -> 1
            allWin -> 2
            else -> 0
        }

        val exprCursor = db.query(
            "express_bets", arrayOf("sumbet", "kfall", "sts_all"),
            "id = ?", arrayOf(expressId.toString()), null, null, null
        )
        var betAmount = 0.0
        var totalKef = 0.0
        var oldStatus = 0
        if (exprCursor.moveToFirst()) {
            betAmount = exprCursor.getDouble(0)
            totalKef = exprCursor.getDouble(1)
            oldStatus = exprCursor.getInt(2)
        }
        exprCursor.close()

        val profitLoss = when (expressStatus) {
            2 -> betAmount * totalKef - betAmount
            1 -> -betAmount
            else -> 0.0
        }

        if (expressStatus != oldStatus) {
            db.update(
                "express_bets", ContentValues().apply {
                    put("sts_all", expressStatus)
                    put("profloss", profitLoss)
                    put("updated_at", System.currentTimeMillis() / 1000)
                }, "id = ?", arrayOf(expressId.toString())
            )
            val statusText = when (expressStatus) {
                2 -> "ВЫИГРАЛ 🏆"
                1 -> "ПРОИГРАЛ ❌"
                else -> "АКТИВЕН 🔄"
            }
            val message = "🎯 Экспресс #$expId ($total матчей) $statusText | ${if (expressStatus == 2) "+" else ""}${"%.2f".format(profitLoss)} ₽"
            onLogUpdate?.invoke("[${getCurrentTime()}] $message")
            onScoresUpdate?.invoke(message)
            dbHelper.addLog(
                userId,
                when (expressStatus) { 2 -> "express_win"; 1 -> "express_loss"; else -> "express_active" },
                "Экспресс #$expId $statusText, результат: ${"%.2f".format(profitLoss)} ₽"
            )
        }
    }

    // ==================== ЗАМЕНА ЭКСПРЕССА ====================
    private suspend fun checkAndCreateReplacementExpress() {
        val db = dbHelper.writableDatabase
        val currentTime = System.currentTimeMillis() / 1000
        val twelveHoursAgo = currentTime - 12 * 3600
        val cursor = db.rawQuery(
            "SELECT id, id_exp, user_id, sumbet FROM express_bets WHERE sts_all = 1 AND ct < ? AND id_exp_replace = 0 ORDER BY ct ASC LIMIT 1",
            arrayOf(twelveHoursAgo.toString())
        )
        if (cursor.moveToFirst()) {
            val oldExpressId = cursor.getLong(0)
            val oldExpId = cursor.getInt(1)
            val userId = cursor.getLong(2)
            val currentBetAmount = cursor.getDouble(3)
            cursor.close()

            val multiply = prefs.getInt("multiply", 2)
            val initialBet = prefs.getString("bet_amount", "30")?.toDoubleOrNull() ?: 30.0
            val maxBetMultiplier = prefs.getInt("max_bet_multiplier", 3)
            val maxAllowedBet = initialBet * maxBetMultiplier
            var newBetAmount = currentBetAmount * multiply
            if (newBetAmount > maxAllowedBet) {
                newBetAmount = initialBet + 1.0
                onLogUpdate?.invoke("[${getCurrentTime()}] ⚠️ Ставка сброшена до начальной + 1: ${newBetAmount.toInt()} ₽")
                dbHelper.addLog(userId, "bet_reset", "Ставка сброшена до ${newBetAmount.toInt()} ₽")
            }
            val newExpId = (System.currentTimeMillis() / 1000).toInt()
            onLogUpdate?.invoke("[${getCurrentTime()}] 🔄 Замена экспресса #$oldExpId → #$newExpId (${currentBetAmount.toInt()} → ${newBetAmount.toInt()} ₽)")
            prefs.edit().putString("bet_amount", newBetAmount.toInt().toString()).apply()

            db.update(
                "express_bets", ContentValues().apply {
                    put("sts_all", -1)
                    put("id_exp_replace", newExpId)
                    put("updated_at", currentTime)
                }, "id = ?", arrayOf(oldExpressId.toString())
            )
            dbHelper.addLog(userId, "express_replaced", "Экспресс #$oldExpId заменён на #$newExpId, ставка: ${newBetAmount.toInt()} ₽")
        } else {
            cursor.close()
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    private fun getCookies(): Map<String, String> {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookieString = cookieManager.getCookie("https://www.fon.bet") ?: ""
        return if (cookieString.isNotEmpty()) {
            cookieString.split("; ").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }
        } else emptyMap()
    }

    /**
     * Получает clientId: сначала из БД, если нет — запрашивает через API.
     */
    private suspend fun getClientId(): Long {
        val data = authData ?: return 18845703L

        // 1. Пробуем из БД
        val user = dbHelper.getUser(data.fsid, data.deviceId)
        val cachedId = user?.clientId?.takeIf { it > 0 }
        if (cachedId != null) {
            Log.d(TAG, "getClientId: из БД = $cachedId")
            return cachedId
        }

        // 2. Запрашиваем через API
        Log.w(TAG, "clientId не найден в БД, запрашиваем через API...")
        onLogUpdate?.invoke("[${getCurrentTime()}] ⚠️ clientId не найден, запрашиваю через API...")

        return suspendCancellableCoroutine { continuation ->
            apiClient.getSaldo(
                cookies = getCookies(),
                fsid = data.fsid,
                deviceId = data.deviceId,
                onSuccess = { sessionInfo ->
                    val clientId = sessionInfo?.clientId ?: 18845703L
                    user?.let {
                        dbHelper.updateUserInfo(it.id, clientId, sessionInfo?.userName)
                    }
                    Log.d(TAG, "getClientId: через API = $clientId")
                    onLogUpdate?.invoke("[${getCurrentTime()}] ✅ clientId получен: $clientId")
                    continuation.resume(clientId)
                },
                onError = { error ->
                    Log.e(TAG, "getClientId ошибка API: $error")
                    onLogUpdate?.invoke("[${getCurrentTime()}] ❌ Не удалось получить clientId: $error")
                    continuation.resume(18845703L)
                }
            )
        }
    }

    private fun loadBalanceFromDb() {
        authData?.let { data ->
            val user = dbHelper.getUser(data.fsid, data.deviceId)
            user?.let {
                val stats = dbHelper.getBalanceStats(it.id)
                if (stats.currentBalance > 0) {
                    balance = stats.currentBalance
                    BotForegroundService.lastBalance = balance
                }
            }
        }
    }

    private fun buildBetSettings(): ApiClient.BetSettings {
        return ApiClient.BetSettings(
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
    }

    private fun isExpressAlreadyExists(userId: Long, matchIds: List<Int>): Boolean {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "express_bets", arrayOf("id"),
            "user_id = ? AND sts_all IN (0,1,2)", arrayOf(userId.toString()), null, null, null
        )
        val expressIds = mutableListOf<Long>()
        while (cursor.moveToNext()) expressIds.add(cursor.getLong(0))
        cursor.close()

        for (eId in expressIds) {
            val evCursor = db.query(
                "express_events", arrayOf("m_id"),
                "express_id = ?", arrayOf(eId.toString()), null, null, null
            )
            val ids = mutableListOf<Int>()
            while (evCursor.moveToNext()) ids.add(evCursor.getInt(0))
            evCursor.close()
            if (ids.sorted() == matchIds.sorted()) return true
        }
        return false
    }

    private fun saveExpressToDb(userId: Long, expId: Int, betDataList: List<ApiClient.BetData>): Long {
        val db = dbHelper.writableDatabase
        val currentTime = System.currentTimeMillis() / 1000
        val betAmount = prefs.getString("bet_amount", "30")?.toDoubleOrNull() ?: 30.0
        var totalKef = 1.0
        betDataList.forEach { totalKef *= it.startKf }
        val potentialWin = betAmount * totalKef
        val testMode = prefs.getBoolean("test_mode", true)
        val strategy = if (testMode) "test" else "real_pending"

        val values = ContentValues().apply {
            put("user_id", userId)
            put("id_exp", expId)
            put("kfall", totalKef)
            put("profloss", 0.0)
            put("balans", balance)
            put("sumbet", betAmount)
            put("sts_all", 0)
            put("is_bet_placed", 0)
            put("ct", currentTime)
            put("strategy", strategy)
            put("id_exp_replace", 0)
            put("events_count", betDataList.size)
            put("total_odds", totalKef)
            put("bet_amount", betAmount)
            put("potential_win", potentialWin)
            put("balance", balance)
            put("created_time", currentTime)
            put("created_at", currentTime)
            put("updated_at", currentTime)
        }
        val expressId = db.insert("express_bets", null, values)
        if (expressId == -1L) return -1

        betDataList.forEach { bet ->
            val initialStatus = if (bet.sh > 0 || bet.sa > 0) checkMatchStatus(bet.type, bet.sh, bet.sa) else 0
            val evValues = ContentValues().apply {
                put("express_id", expressId)
                put("id_exp", expId)
                put("user_id", userId)
                put("m_id", bet.mId)
                put("start_odds", bet.startKf)
                put("current_odds", bet.lastKf)
                put("bet_type", bet.type)
                put("status", initialStatus)
                put("home_score", bet.sh)
                put("away_score", bet.sa)
                put("match_time", 0)
                put("created_at", currentTime)
                put("updated_at", currentTime)
                put("is_finalized", 0)
                if (bet.idLiga > 0) put("id_liga", bet.idLiga.toLong())
                if (bet.ligaName.isNotEmpty()) put("league_name", bet.ligaName)
                if (bet.home.isNotEmpty()) put("home_team", bet.home)
                if (bet.away.isNotEmpty()) put("away_team", bet.away)
                if (bet.comand1Id > 0) put("id_home", bet.comand1Id.toLong())
                if (bet.comand2Id > 0) put("id_away", bet.comand2Id.toLong())
                if (bet.url.isNotEmpty()) put("match_url", bet.url)
                if (bet.uzh > 0) put("uzh", bet.uzh.toString())
                if (bet.tbType > 0) put("total_type", bet.tbType.toLong())
            }
            db.insert("express_events", null, evValues)
        }
        dbHelper.addLog(
            userId, "express_created",
            "Экспресс #$expId: ${betDataList.size} матчей, кэф ${"%.2f".format(totalKef)}, ставка ${betAmount.toInt()} ₽"
        )
        return expressId
    }

    // ✅ Исправлено: when как statement, а не expression
    private fun checkMatchStatus(betType: Int, sh: Int, sa: Int): Int {
        return when (betType) {
            924 -> if (sh >= sa) 2 else 1
            927 -> if (sh + 1 >= sa) 2 else 1
            928 -> if (sa + 1 >= sh) 2 else 1
            else -> 1
        }
    }

    private fun typeName(type: Int): String = when (type) {
        924 -> "1X"
        927 -> "Ф1(+1.5)"
        928 -> "Ф2(+1.5)"
        else -> "Тип $type"
    }

    private fun showBetNotification(title: String, content: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, "bet_channel")
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun getCurrentTime(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

    data class EventInfo(
        val id: Long,
        val mId: Int,
        val betType: Int,
        var status: Int,
        var homeScore: Int,
        var awayScore: Int,
        var matchTime: Int = 0
    )
}