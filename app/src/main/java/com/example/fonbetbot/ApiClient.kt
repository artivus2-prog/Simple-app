// ApiClient.kt - ПОЛНАЯ ИСПРАВЛЕННАЯ ВЕРСИЯ
package com.example.fonbetbot

import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ApiClient {
    private val client = OkHttpClient()
    
    companion object {
        const val TAG = "ApiClient"
    }
    
    // Data class для ответа session/info
    data class SessionInfo(
        val saldo: Double?,
        val clientId: Long?,
        val userName: String?
    )
    
    // Data class для матча с коэффициентами и временем
    data class MatchFactors(
        val score1: Int,
        val score2: Int,
        val matchTime: Int,
        val factors: Map<Int, Double>,
        val handicaps: Map<Int, Double>
    )
    
    // Data class для ставки из ответа getBets
    data class BetData(
        val mId: Int,
        val type: Int,
        val sport: String = "",
        val idExp: Int = 0,
        val idLiga: Int = 0,
        val ligaName: String = "",
        val home: String = "",
        val away: String = "",
        val comand1Id: Int = 0,
        val comand2Id: Int = 0,
        val curTime: String = "",
        val sh: Int = 0,
        val sa: Int = 0,
        val startKf: Double = 0.0,
        val lastKf: Double = 0.0,
        val sts: Int = 1,
        val url: String = "",
        val uzh: Double = 0.0,
        val tbType: Int = 0
    )
    
    // Data class для настроек ставок
    data class BetSettings(
        val maxMatchesPerExpress: Int = 2,
        val multiply: Int = 2,
        val allMinKef: Double = 1.67,
        val types: Map<Int, TypeSettings> = mapOf(
            924 to TypeSettings("1х/футбол/хоккей", 1.15, 1.35, 80, 100),
            927 to TypeSettings("ф1(+1.5)/футбол/хоккей", 1.15, 1.35, 1, 45),
            928 to TypeSettings("ф2(+1.5)/футбол/хоккей", 1.15, 1.35, 1, 45)
        )
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("MAX_MATCHES_PER_EXPRESS", maxMatchesPerExpress)
                put("MULTIPLY", multiply)
                put("ALL_MIN_KEF", allMinKef)
                put("types", JSONObject().apply {
                    types.forEach { (typeId, typeSettings) ->
                        put(typeId.toString(), JSONArray().apply {
                            put(typeSettings.name)
                            put(typeSettings.minBet)
                            put(typeSettings.maxBet)
                            put(typeSettings.monitorStart)
                            put(typeSettings.monitorEnd)
                        })
                    }
                })
            }
        }
    }
    
    data class TypeSettings(
        val name: String,
        val minBet: Double,
        val maxBet: Double,
        val monitorStart: Int,
        val monitorEnd: Int
    )
    
    // Метод получения баланса
    fun getSaldo(
        cookies: Map<String, String>,
        fsid: String,
        deviceId: String,
        clientId: Long = 18845703,
        sysId: Int = 21,
        onSuccess: (SessionInfo?) -> Unit,
        onError: (String) -> Unit
    ) {
        val jsonBody = JSONObject().apply {
            put("clientId", clientId)
            put("fsid", fsid)
            put("sysId", sysId)
            put("deviceId", deviceId)
        }
        
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toString().toRequestBody(mediaType)
        
        val requestBuilder = Request.Builder()
            .url("https://clientsapi-lb51-w.bk6bba-resources.com/session/info")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
        
        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (cookieHeader.isNotEmpty()) {
            requestBuilder.addHeader("Cookie", cookieHeader)
        }
        
        val request = requestBuilder.build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Ошибка сети: ${e.message}")
            }
            
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyString = response.body?.string() ?: ""
                    
                    // ЛОГИРОВАНИЕ ПОЛНОГО ОТВЕТА ДЛЯ ДИАГНОСТИКИ
                    Log.d(TAG, "=== session/info FULL RESPONSE ===")
                    Log.d(TAG, bodyString)
                    Log.d(TAG, "=== END session/info ===")
                    
                    if (!response.isSuccessful) {
                        onError("Ошибка ${response.code}")
                        return
                    }
                    
                    try {
                        val json = JSONObject(bodyString)
                        
                        val saldo: Double? = if (json.has("saldo") && !json.isNull("saldo")) {
                            json.getDouble("saldo")
                        } else {
                            null
                        }
                        
                        val extractedClientId: Long? = if (json.has("clientId") && !json.isNull("clientId")) {
                            json.getLong("clientId")
                        } else {
                            null
                        }
                        
                        // РАСШИРЕННОЕ ИЗВЛЕЧЕНИЕ ИМЕНИ ПОЛЬЗОВАТЕЛЯ
                        val userName: String? = try {
                            when {
                                // Основной путь: registration.name
                                json.has("registration") && !json.isNull("registration") -> {
                                    val registration = json.getJSONObject("registration")
                                    when {
                                        registration.has("name") && !registration.isNull("name") -> {
                                            val name = registration.getString("name")
                                            Log.d(TAG, "✅ Имя найдено в registration.name: $name")
                                            name
                                        }
                                        registration.has("fullName") && !registration.isNull("fullName") -> {
                                            registration.getString("fullName")
                                        }
                                        registration.has("firstName") && registration.has("lastName") -> {
                                            val firstName = registration.optString("firstName", "")
                                            val lastName = registration.optString("lastName", "")
                                            "$lastName $firstName".trim()
                                        }
                                        else -> null
                                    }
                                }
                                // Альтернативные пути
                                json.has("userName") && !json.isNull("userName") -> {
                                    json.getString("userName")
                                }
                                json.has("fullName") && !json.isNull("fullName") -> {
                                    json.getString("fullName")
                                }
                                json.has("name") && !json.isNull("name") -> {
                                    json.getString("name")
                                }
                                else -> {
                                    Log.d(TAG, "⚠️ Имя пользователя не найдено в ответе")
                                    null
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка извлечения имени: ${e.message}")
                            null
                        }
                        
                        val sessionInfo = SessionInfo(
                            saldo = saldo,
                            clientId = extractedClientId,
                            userName = userName
                        )
                        
                        Log.d(TAG, "SessionInfo: saldo=$saldo, clientId=$extractedClientId, userName=$userName")
                        onSuccess(sessionInfo)
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка парсинга: ${e.message}")
                        onError("Ошибка парсинга: ${e.message}")
                    }
                }
            }
        })
    }
    
    // Метод получения ставок
    fun getBets(
        userId: Long,
        settings: BetSettings,
        onSuccess: (List<BetData>) -> Unit,
        onError: (String) -> Unit
    ) {
        val jsonBody = JSONObject().apply {
            put("user_id", userId)
            put("settings", settings.toJson())
        }
        
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toString().toRequestBody(mediaType)
        
        val request = Request.Builder()
            .url("http://95.183.11.203:5000/api/getBets")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Ошибка сети getBets: ${e.message}")
                onError("Ошибка сети: ${e.message}")
            }
            
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyString = response.body?.string() ?: ""
                    
                    Log.d(TAG, "getBets response: $bodyString")
                    
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Ошибка getBets ${response.code}: $bodyString")
                        onError("Ошибка ${response.code}")
                        return
                    }
                    
                    try {
                        val json = JSONObject(bodyString)
                        val betsList = mutableListOf<BetData>()
                        
                        if (json.has("data")) {
                            val dataArray = json.getJSONArray("data")
                            
                            for (i in 0 until dataArray.length()) {
                                val betJson = dataArray.getJSONObject(i)
                                
                                val betData = BetData(
                                    mId = betJson.optInt("m_id", 0),
                                    type = betJson.optInt("type", 0),
                                    sport = betJson.optString("sport", ""),
                                    idExp = betJson.optInt("id_exp", 0),
                                    idLiga = betJson.optInt("id_liga", 0),
                                    ligaName = betJson.optString("liganame", ""),
                                    home = betJson.optString("home", ""),
                                    away = betJson.optString("away", ""),
                                    comand1Id = betJson.optInt("comand1id", 0),
                                    comand2Id = betJson.optInt("comand2id", 0),
                                    curTime = betJson.optString("curtime", ""),
                                    sh = betJson.optInt("sh", 0),
                                    sa = betJson.optInt("sa", 0),
                                    startKf = betJson.optDouble("startkf", 0.0),
                                    lastKf = betJson.optDouble("lastkf", 0.0),
                                    sts = betJson.optInt("sts", 1),
                                    url = betJson.optString("url", ""),
                                    uzh = betJson.optDouble("uzh", 0.0),
                                    tbType = betJson.optInt("tbtype", 0)
                                )
                                
                                betsList.add(betData)
                            }
                        }
                        
                        Log.d(TAG, "Получено ${betsList.size} ставок")
                        onSuccess(betsList)
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка парсинга getBets: ${e.message}")
                        onError("Ошибка парсинга: ${e.message}")
                    }
                }
            }
        })
    }
    
    // Метод получения счета, времени и коэффициентов матча
    fun getMatchScore(
        matchId: Int,
        onSuccess: (MatchFactors?) -> Unit,
        onError: (String) -> Unit
    ) {
        val url = "https://line-lb61-w.bk6bba-resources.com/ma/events/event?lang=ru&version=75079713020&eventId=$matchId&scopeMarket=1600"
        
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/json")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Ошибка сети: ${e.message}")
            }
            
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyString = response.body?.string() ?: ""
                    
                    if (!response.isSuccessful) {
                        onError("Ошибка ${response.code}")
                        return
                    }
                    
                    try {
                        val json = JSONObject(bodyString)
                        var sh = -1
                        var sa = -1
                        var matchTime = 0
                        
                        if (json.has("liveEventInfos")) {
                            val liveEventInfos = json.getJSONArray("liveEventInfos")
                            if (liveEventInfos.length() > 0) {
                                val liveEventInfo = liveEventInfos.getJSONObject(0)
                                
                                // ИСПРАВЛЕНИЕ: конвертируем секунды в минуты
                                val timerSeconds = liveEventInfo.optInt("timerSeconds", 0)
                                matchTime = if (timerSeconds > 0) {
                                    Math.round(timerSeconds / 60.0).toInt()
                                } else {
                                    0
                                }
                                
                                Log.d(TAG, "Матч #$matchId: timerSeconds=$timerSeconds, matchTime=$matchTime мин")
                                
                                if (liveEventInfo.has("scores")) {
                                    val scores = liveEventInfo.getJSONArray("scores")
                                    if (scores.length() > 0) {
                                        val mainScore = scores.getJSONArray(0)
                                        if (mainScore.length() > 0) {
                                            val scoreObj = mainScore.getJSONObject(0)
                                            sh = scoreObj.optInt("c1", -1)
                                            sa = scoreObj.optInt("c2", -1)
                                        }
                                    }
                                }
                            }
                        }
                        
                        val factors = mutableMapOf<Int, Double>()
                        val handicaps = mutableMapOf<Int, Double>()
                        
                        if (json.has("customFactors")) {
                            val customFactors = json.getJSONArray("customFactors")
                            for (i in 0 until customFactors.length()) {
                                val factorGroup = customFactors.getJSONObject(i)
                                
                                if (factorGroup.has("e") && factorGroup.getInt("e") == matchId) {
                                    if (factorGroup.has("factors")) {
                                        val factorsArray = factorGroup.getJSONArray("factors")
                                        for (j in 0 until factorsArray.length()) {
                                            val factor = factorsArray.getJSONObject(j)
                                            val f = factor.optInt("f", 0)
                                            val v = factor.optDouble("v", 0.0)
                                            
                                            if (f in listOf(924, 927, 928) && v > 0) {
                                                factors[f] = v
                                                val p = factor.optInt("p", 0)
                                                if (p > 0) {
                                                    handicaps[f] = p / 100.0
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        if (sh >= 0 && sa >= 0) {
                            onSuccess(MatchFactors(sh, sa, matchTime, factors, handicaps))
                        } else {
                            onSuccess(null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка парсинга: ${e.message}")
                        onError("Ошибка парсинга: ${e.message}")
                    }
                }
            }
        })
    }
    
    fun getMatchTime(
        matchId: Int,
        onSuccess: (Int?) -> Unit,
        onError: (String) -> Unit
    ) {
        getMatchScore(matchId, 
            onSuccess = { factors -> onSuccess(factors?.matchTime) },
            onError = onError
        )
    }

    // ==================== МЕТОДЫ ДЛЯ РАЗМЕЩЕНИЯ СТАВОК ====================

data class BetSlipInfo(
    val k: Double,
    val bets: List<BetSlipItem>,
    val sums: Sums?
) {
    data class BetSlipItem(
        val event: EventInfo,
        val factor: FactorInfo
    )
    
    data class EventInfo(
        val id: Int,
        val team1: String,
        val team2: String,
        val competitionName: String,
        val score: String
    )
    
    data class FactorInfo(
        val id: Int,
        val v: Double,
        val couponChoiceCaption: String,
        val param: Int?
    )
    
    data class Sums(
        val min: Double,
        val max: Double
    )
}

// Шаг 1: Получить информацию о ставках
fun getBetSlipInfo(
    bets: List<BetData>,
    cookies: Map<String, String>,
    fsid: String,
    clientId: Long,
    onSuccess: (JSONObject) -> Unit,
    onError: (String) -> Unit
) {
    val betsArray = JSONArray()
    bets.forEach { bet ->
        betsArray.put(JSONObject().apply {
            put("eventId", bet.mId)
            put("factorId", bet.type)
            put("old", true)
        })
    }
    
    val jsonBody = JSONObject().apply {
        put("lang", "ru")
        put("clientId", clientId)
        put("fsid", fsid)
        put("sysId", 21)
        put("scopeMarketId", "1600")
        put("bets", betsArray)
    }
    
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val body = jsonBody.toString().toRequestBody(mediaType)
    
    val requestBuilder = Request.Builder()
        .url("https://clientsapi-lb51-w.bk6bba-resources.com/coupon/betSlipInfo")
        .post(body)
        .addHeader("Content-Type", "application/json")
        .addHeader("Accept", "application/json")
    
    val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    if (cookieHeader.isNotEmpty()) {
        requestBuilder.addHeader("Cookie", cookieHeader)
    }
    
    client.newCall(requestBuilder.build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e(TAG, "Ошибка getBetSlipInfo: ${e.message}")
            onError(e.message ?: "Network error")
        }
        
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val bodyString = response.body?.string() ?: ""
                Log.d(TAG, "getBetSlipInfo response: $bodyString")
                
                if (!response.isSuccessful) {
                    onError("HTTP ${response.code}: $bodyString")
                    return
                }
                
                try {
                    val json = JSONObject(bodyString)
                    onSuccess(json)
                } catch (e: Exception) {
                    onError("Parse error: ${e.message}")
                }
            }
        }
    })
}

// Шаг 2: Получить requestId
fun getBetRequestId(
    cookies: Map<String, String>,
    fsid: String,
    clientId: Long,
    deviceId: String,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit
) {
    val jsonBody = JSONObject().apply {
        put("lang", "ru")
        put("clientId", clientId)
        put("fsid", fsid)
        put("sysId", 21)
        put("CDI", 877)
        put("deviceId", deviceId)
    }
    
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val body = jsonBody.toString().toRequestBody(mediaType)
    
    val requestBuilder = Request.Builder()
        .url("https://clientsapi-lb51-w.bk6bba-resources.com/coupon/betRequestId")
        .post(body)
        .addHeader("Content-Type", "application/json")
        .addHeader("Accept", "application/json")
    
    val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    if (cookieHeader.isNotEmpty()) {
        requestBuilder.addHeader("Cookie", cookieHeader)
    }
    
    client.newCall(requestBuilder.build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            onError(e.message ?: "Network error")
        }
        
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val bodyString = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    onError("HTTP ${response.code}")
                    return
                }
                
                try {
                    val json = JSONObject(bodyString)
                    val requestId = json.getString("requestId")
                    onSuccess(requestId)
                } catch (e: Exception) {
                    onError("Parse error: ${e.message}")
                }
            }
        }
    })
}

// Шаг 3: Разместить ставку
fun placeBet(
    requestId: String,
    betSlipInfo: JSONObject,
    amount: Int,
    cookies: Map<String, String>,
    fsid: String,
    clientId: Long,
    bets: List<BetData>,
    onSuccess: (JSONObject) -> Unit,
    onError: (String) -> Unit
) {
    val betsArray = JSONArray()
    val betsFromSlip = betSlipInfo.getJSONArray("bets")
    
    for (i in 0 until betsFromSlip.length()) {
        val bet = betsFromSlip.getJSONObject(i)
        val betData = bets[i]
        
        betsArray.put(JSONObject().apply {
            put("event", bet.getJSONObject("event").getInt("id"))
            put("factor", bet.getJSONObject("factor").getInt("id"))
            put("value", bet.getJSONObject("factor").getDouble("v"))
            put("score", bet.getJSONObject("event").optString("score", "0:0"))
            
            // Добавляем param для тоталов если нужно
            if (betData.tbType > 0) {
                put("param", betData.tbType)
            }
        })
    }
    
    val coupon = JSONObject().apply {
        put("amount", amount)
        put("flexBet", "any")
        put("flexParam", true)
        put("mirror", "https://fon.bet")
        put("type", "express")
        put("expressBonus", 0)
        put("betType", "express")
        put("part", 1)
        put("bets", betsArray)
    }
    
    val jsonBody = JSONObject().apply {
        put("requestId", requestId)
        put("lang", "ru")
        put("clientId", clientId)
        put("fsid", fsid)
        put("sysId", 21)
        put("coupon", coupon)
    }
    
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val body = jsonBody.toString().toRequestBody(mediaType)
    
    val requestBuilder = Request.Builder()
        .url("https://clientsapi-lb52-w.bk6bba-resources.ru/coupon/bet")
        .post(body)
        .addHeader("Content-Type", "application/json")
        .addHeader("Accept", "application/json")
    
    val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    if (cookieHeader.isNotEmpty()) {
        requestBuilder.addHeader("Cookie", cookieHeader)
    }
    
    client.newCall(requestBuilder.build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            onError(e.message ?: "Network error")
        }
        
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val bodyString = response.body?.string() ?: ""
                Log.d(TAG, "placeBet response: $bodyString")
                
                if (!response.isSuccessful) {
                    onError("HTTP ${response.code}: $bodyString")
                    return
                }
                
                try {
                    val json = JSONObject(bodyString)
                    onSuccess(json)
                } catch (e: Exception) {
                    onError("Parse error: ${e.message}")
                }
            }
        }
    })
}

// Шаг 4: Получить результат ставки
fun getBetResult(
    requestId: String,
    delayMs: Long,
    cookies: Map<String, String>,
    fsid: String,
    clientId: Long,
    deviceId: String,
    onSuccess: (JSONObject) -> Unit,
    onError: (String) -> Unit
) {
    val jsonBody = JSONObject().apply {
        put("lang", "ru")
        put("requestId", requestId)
        put("fsid", fsid)
        put("sysId", 21)
        put("clientId", clientId)
        put("CDI", 877)
        put("deviceId", deviceId)
    }
    
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val body = jsonBody.toString().toRequestBody(mediaType)
    
    val requestBuilder = Request.Builder()
        .url("https://clientsapi-lb51-w.bk6bba-resources.com/coupon/betResult")
        .post(body)
        .addHeader("Content-Type", "application/json")
        .addHeader("Accept", "application/json")
    
    val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    if (cookieHeader.isNotEmpty()) {
        requestBuilder.addHeader("Cookie", cookieHeader)
    }
    
    // Задержка перед запросом результата
    Thread.sleep(delayMs)
    
    client.newCall(requestBuilder.build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            onError(e.message ?: "Network error")
        }
        
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val bodyString = response.body?.string() ?: ""
                Log.d(TAG, "getBetResult response: $bodyString")
                
                if (!response.isSuccessful) {
                    onError("HTTP ${response.code}")
                    return
                }
                
                try {
                    val json = JSONObject(bodyString)
                    onSuccess(json)
                } catch (e: Exception) {
                    onError("Parse error: ${e.message}")
                }
            }
        }
    })
}

// Главный метод размещения ставки (объединяет все шаги)
fun makeBet(
    bets: List<BetData>,
    amount: Int,
    cookies: Map<String, String>,
    fsid: String,
    clientId: Long,
    deviceId: String,
    onSuccess: (MakeBetResult) -> Unit,
    onError: (String) -> Unit
) {
    Log.d(TAG, "🎰 makeBet: ${bets.size} матчей, сумма $amount ₽")
    
    // Шаг 1: Получаем информацию о ставках
    getBetSlipInfo(bets, cookies, fsid, clientId,
        onSuccess = { betSlipInfo ->
            Log.d(TAG, "✅ betSlipInfo получен, K=${betSlipInfo.optDouble("K")}")
            
            // Шаг 2: Получаем requestId
            getBetRequestId(cookies, fsid, clientId, deviceId,
                onSuccess = { requestId ->
                    Log.d(TAG, "✅ requestId получен: $requestId")
                    
                    // Шаг 3: Размещаем ставку
                    placeBet(requestId, betSlipInfo, amount, cookies, fsid, clientId, bets,
                        onSuccess = { placeBetResponse ->
                            Log.d(TAG, "✅ Ставка размещена, ответ: $placeBetResponse")
                            
                            val betDelay = placeBetResponse.optLong("betDelay", 0)
                            
                            // Шаг 4: Получаем результат (с задержкой если нужно)
                            getBetResult(requestId, betDelay, cookies, fsid, clientId, deviceId,
                                onSuccess = { betResult ->
                                    Log.d(TAG, "✅ Результат ставки: $betResult")
                                    
                                    val result = MakeBetResult(
                                        betSlipInfo = betSlipInfo,
                                        requestId = requestId,
                                        betDelay = betDelay,
                                        result = betResult,
                                        success = true
                                    )
                                    onSuccess(result)
                                },
                                onError = { error ->
                                    Log.e(TAG, "❌ Ошибка getBetResult: $error")
                                    onError("Ошибка получения результата: $error")
                                }
                            )
                        },
                        onError = { error ->
                            Log.e(TAG, "❌ Ошибка placeBet: $error")
                            onError("Ошибка размещения ставки: $error")
                        }
                    )
                },
                onError = { error ->
                    Log.e(TAG, "❌ Ошибка getBetRequestId: $error")
                    onError("Ошибка получения requestId: $error")
                }
            )
        },
        onError = { error ->
            Log.e(TAG, "❌ Ошибка getBetSlipInfo: $error")
            onError("Ошибка получения информации о ставках: $error")
        }
    )
}

    data class MakeBetResult(
        val betSlipInfo: JSONObject,
        val requestId: String,
        val betDelay: Long,
        val result: JSONObject,
        val success: Boolean
    )
    
}