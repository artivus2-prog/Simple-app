// ApiClient.kt - ПОЛНАЯ ИСПРАВЛЕННАЯ ВЕРСИЯ С ДЕТАЛЬНЫМ ЛОГИРОВАНИЕМ
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
        val tbType: Int = 0,
        val matchTime: Int = 0
    )
    
    // Data class для настроек ставок
    data class BetSettings(
        val maxMatchesPerExpress: Int = 2,
        val multiply: Int = 2,
        val allMinKef: Double = 1.67,
        val maxActiveExpresses: Int = 5,
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
                put("MAX_ACTIVE_EXPRESSES", maxActiveExpresses)
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

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ЛОГИРОВАНИЯ ====================
    
    private fun logRequestDetails(request: Request, bodyString: String? = null) {
        Log.d(TAG, "🌐 ===== REQUEST DETAILS =====")
        Log.d(TAG, "🌐 URL: ${request.url}")
        Log.d(TAG, "🌐 Method: ${request.method}")
        Log.d(TAG, "🌐 Headers:")
        request.headers.forEach { (name, value) ->
            if (name.equals("Cookie", ignoreCase = true)) {
                Log.d(TAG, "  $name: ${value.take(100)}... (${value.length} chars)")
            } else {
                Log.d(TAG, "  $name: $value")
            }
        }
        if (bodyString != null) {
            Log.d(TAG, "🌐 Body:")
            try {
                val json = JSONObject(bodyString)
                Log.d(TAG, json.toString(2))
            } catch (e: Exception) {
                Log.d(TAG, bodyString)
            }
        }
        Log.d(TAG, "🌐 ===== END REQUEST =====")
    }
    
    private fun logResponseDetails(response: Response, bodyString: String) {
        Log.d(TAG, "📥 ===== RESPONSE DETAILS =====")
        Log.d(TAG, "📥 URL: ${response.request.url}")
        Log.d(TAG, "📥 Status: ${response.code} ${response.message}")
        Log.d(TAG, "📥 Headers:")
        response.headers.forEach { (name, value) ->
            Log.d(TAG, "  $name: $value")
        }
        Log.d(TAG, "📥 Body (${bodyString.length} chars):")
        try {
            val json = JSONObject(bodyString)
            Log.d(TAG, json.toString(2))
        } catch (e: Exception) {
            Log.d(TAG, bodyString.take(3000))
        }
        Log.d(TAG, "📥 ===== END RESPONSE =====")
    }
    
    private fun buildCookieHeader(cookies: Map<String, String>, fsid: String): String {
        val allCookies = mutableMapOf<String, String>()
        allCookies.putAll(cookies)
        if (!allCookies.containsKey("fsid") && fsid.isNotEmpty()) {
            allCookies["fsid"] = fsid
            Log.w(TAG, "⚠️ fsid не было в cookies, добавлен из параметров")
        }
        val cookieHeader = allCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d(TAG, "🍪 Cookie header (${allCookies.size} cookies): ${cookieHeader.take(150)}...")
        return cookieHeader
    }
    
    // ==================== МЕТОД ПОЛУЧЕНИЯ БАЛАНСА ====================
    
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
        
        val bodyString = jsonBody.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = bodyString.toRequestBody(mediaType)
        
        val cookieHeader = buildCookieHeader(cookies, fsid)
        
        val request = Request.Builder()
            .url("https://clientsapi-lb51-w.bk6bba-resources.com/session/info")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("Accept-Language", "ru-RU,ru;q=0.9")
            .addHeader("Origin", "https://www.fon.bet")
            .addHeader("Referer", "https://www.fon.bet/")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .addHeader("Cookie", cookieHeader)
            .build()
        
        logRequestDetails(request, bodyString)
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ session/info NETWORK ERROR: ${e.message}", e)
                onError("Ошибка сети: ${e.message}")
            }
            
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyString = response.body?.string() ?: ""
                    logResponseDetails(response, bodyString)
                    
                    if (!response.isSuccessful) {
                        onError("Ошибка ${response.code}")
                        return
                    }
                    
                    try {
                        val json = JSONObject(bodyString)
                        
                        val extractedClientId: Long? = when {
                            json.has("clientId") && !json.isNull("clientId") -> json.getLong("clientId")
                            else -> null
                        }
                        
                        val saldo: Double? = when {
                            json.has("saldo") && !json.isNull("saldo") -> json.getDouble("saldo")
                            else -> null
                        }
                        
                        val userName: String? = try {
                            when {
                                json.has("registration") && !json.isNull("registration") -> {
                                    val registration = json.getJSONObject("registration")
                                    when {
                                        registration.has("name") && !registration.isNull("name") -> registration.getString("name")
                                        registration.has("fullName") && !registration.isNull("fullName") -> registration.getString("fullName")
                                        registration.has("firstName") && registration.has("lastName") -> {
                                            "${registration.optString("lastName", "")} ${registration.optString("firstName", "")}".trim()
                                        }
                                        else -> null
                                    }
                                }
                                json.has("userName") && !json.isNull("userName") -> json.getString("userName")
                                json.has("fullName") && !json.isNull("fullName") -> json.getString("fullName")
                                json.has("name") && !json.isNull("name") -> json.getString("name")
                                else -> null
                            }
                        } catch (e: Exception) { null }
                        
                        val sessionInfo = SessionInfo(saldo = saldo, clientId = extractedClientId, userName = userName)
                        Log.d(TAG, "📊 SessionInfo: clientId=$extractedClientId, saldo=$saldo, userName=$userName")
                        onSuccess(sessionInfo)
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Ошибка парсинга: ${e.message}", e)
                        onError("Ошибка парсинга: ${e.message}")
                    }
                }
            }
        })
    }
    
    // ==================== МЕТОД ПОЛУЧЕНИЯ СТАВОК ====================
    
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
        
        val bodyString = jsonBody.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = bodyString.toRequestBody(mediaType)
        
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
                                betsList.add(BetData(
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
                                    tbType = betJson.optInt("tbtype", 0),
                                    matchTime = betJson.optInt("match_time", 0)
                                ))
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
    
    // ==================== МЕТОД ПОЛУЧЕНИЯ СЧЕТА МАТЧА ====================
    
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
                                val timerSeconds = liveEventInfo.optInt("timerSeconds", 0)
                                matchTime = if (timerSeconds > 0) timerSeconds / 60 else 0
                                
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
                                                if (p > 0) handicaps[f] = p / 100.0
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
    
    // Шаг 1: Получить информацию о ставках
    fun getBetSlipInfo(
        bets: List<BetData>,
        cookies: Map<String, String>,
        fsid: String,
        clientId: Long,
        onSuccess: (JSONObject) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "📤 ШАГ 1/4: ЗАПРОС betSlipInfo")
        Log.d(TAG, "========================================")
        
        val betsArray = JSONArray()
        bets.forEach { bet ->
            betsArray.put(JSONObject().apply {
                put("eventId", bet.mId)
                put("factorId", bet.type)
                put("old", true)
                if (bet.tbType > 0) {
                    put("param", bet.tbType)
                }
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
        
        val bodyString = jsonBody.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = bodyString.toRequestBody(mediaType)
        
        val cookieHeader = buildCookieHeader(cookies, fsid)
        
        val request = Request.Builder()
            .url("https://clientsapi-lb51-w.bk6bba-resources.com/coupon/betSlipInfo")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .addHeader("Origin", "https://www.fon.bet")
            .addHeader("Referer", "https://www.fon.bet/")
            .addHeader("Sec-Fetch-Dest", "empty")
            .addHeader("Sec-Fetch-Mode", "cors")
            .addHeader("Sec-Fetch-Site", "same-site")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Cookie", cookieHeader)
            .build()
        
        logRequestDetails(request, bodyString)
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ ШАГ 1/4: ОШИБКА СЕТИ")
                Log.e(TAG, "❌ ${e.message}", e)
                onError(e.message ?: "Network error")
            }
            
            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string() ?: ""
                logResponseDetails(response, bodyString)
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ ШАГ 1/4: HTTP ${response.code}")
                    onError("HTTP ${response.code}: ${bodyString.take(500)}")
                    return
                }
                
                try {
                    val json = JSONObject(bodyString)
                    
                    // Детальный разбор ответа
                    Log.d(TAG, "📊 АНАЛИЗ betSlipInfo:")
                    if (json.has("K")) {
                        Log.d(TAG, "  ✅ K (коэффициент): ${json.getDouble("K")}")
                    } else {
                        Log.e(TAG, "  ❌ K не найден!")
                    }
                    
                    if (json.has("bets")) {
                        val betsArray = json.getJSONArray("bets")
                        Log.d(TAG, "  ✅ bets: ${betsArray.length()} элементов")
                        for (i in 0 until betsArray.length()) {
                            val bet = betsArray.getJSONObject(i)
                            Log.d(TAG, "  bet[$i]: event=${bet.optJSONObject("event")?.optInt("id")}, factor=${bet.optJSONObject("factor")?.optInt("id")}, v=${bet.optJSONObject("factor")?.optDouble("v")}")
                        }
                    }
                    
                    if (json.has("sums")) {
                        val sums = json.getJSONObject("sums")
                        Log.d(TAG, "  ✅ sums: min=${sums.optDouble("min")}, max=${sums.optDouble("max")}")
                    }
                    
                    if (json.has("error")) {
                        Log.e(TAG, "  ❌ API Error: ${json.optString("error")}")
                    }
                    
                    onSuccess(json)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ ШАГ 1/4: Ошибка парсинга JSON: ${e.message}")
                    onError("Parse error: ${e.message}")
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
        Log.d(TAG, "========================================")
        Log.d(TAG, "📤 ШАГ 2/4: ЗАПРОС betRequestId")
        Log.d(TAG, "========================================")
        
        val jsonBody = JSONObject().apply {
            put("lang", "ru")
            put("clientId", clientId)
            put("fsid", fsid)
            put("sysId", 21)
            put("CDI", 877)
            put("deviceId", deviceId)
        }
        
        val bodyString = jsonBody.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = bodyString.toRequestBody(mediaType)
        
        val cookieHeader = buildCookieHeader(cookies, fsid)
        
        val request = Request.Builder()
            .url("https://clientsapi-lb51-w.bk6bba-resources.com/coupon/betRequestId")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("Accept-Language", "ru-RU,ru;q=0.9")
            .addHeader("Origin", "https://www.fon.bet")
            .addHeader("Referer", "https://www.fon.bet/")
            .addHeader("Sec-Fetch-Dest", "empty")
            .addHeader("Sec-Fetch-Mode", "cors")
            .addHeader("Sec-Fetch-Site", "same-site")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Cookie", cookieHeader)
            .build()
        
        logRequestDetails(request, bodyString)
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ ШАГ 2/4: ОШИБКА СЕТИ")
                Log.e(TAG, "❌ ${e.message}", e)
                onError(e.message ?: "Network error")
            }
            
            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string() ?: ""
                logResponseDetails(response, bodyString)
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ ШАГ 2/4: HTTP ${response.code}")
                    onError("HTTP ${response.code}")
                    return
                }
                
                try {
                    val json = JSONObject(bodyString)
                    val requestId = json.getString("requestId")
                    Log.d(TAG, "✅ requestId получен: $requestId")
                    onSuccess(requestId)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ ШАГ 2/4: requestId не найден в ответе")
                    Log.e(TAG, "❌ Полный ответ: $bodyString")
                    onError("Parse error: ${e.message}")
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
        Log.d(TAG, "========================================")
        Log.d(TAG, "📤 ШАГ 3/4: ЗАПРОС placeBet")
        Log.d(TAG, "========================================")
        
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
                
                if (betData.tbType > 0) {
                    put("param", betData.tbType)
                } else if (bet.getJSONObject("factor").has("param")) {
                    put("param", bet.getJSONObject("factor").getInt("param"))
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
        
        val bodyString = jsonBody.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = bodyString.toRequestBody(mediaType)
        
        val cookieHeader = buildCookieHeader(cookies, fsid)
        
        val request = Request.Builder()
            .url("https://clientsapi-lb52-w.bk6bba-resources.com/coupon/bet")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("Accept-Language", "ru-RU,ru;q=0.9")
            .addHeader("Origin", "https://www.fon.bet")
            .addHeader("Referer", "https://www.fon.bet/")
            .addHeader("Sec-Fetch-Dest", "empty")
            .addHeader("Sec-Fetch-Mode", "cors")
            .addHeader("Sec-Fetch-Site", "same-site")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Cookie", cookieHeader)
            .build()
        
        logRequestDetails(request, bodyString)
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ ШАГ 3/4: ОШИБКА СЕТИ")
                Log.e(TAG, "❌ ${e.message}", e)
                onError(e.message ?: "Network error")
            }
            
            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string() ?: ""
                logResponseDetails(response, bodyString)
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ ШАГ 3/4: HTTP ${response.code}")
                    Log.e(TAG, "❌ Response: ${bodyString.take(1000)}")
                    onError("HTTP ${response.code}: ${bodyString.take(500)}")
                    return
                }
                
                try {
                    val json = JSONObject(bodyString)
                    
                    // ДЕТАЛЬНЫЙ АНАЛИЗ ОТВЕТА
                    Log.d(TAG, "📊 АНАЛИЗ placeBet ОТВЕТА:")
                    Log.d(TAG, "  resultCode (корень): ${json.optInt("resultCode", -999)}")
                    
                    val couponResult = json.optJSONObject("coupon")
                    if (couponResult != null) {
                        val couponCode = couponResult.optInt("resultCode", -999)
                        Log.d(TAG, "  coupon.resultCode: $couponCode")
                        
                        when (couponCode) {
                            -1 -> {
                                Log.e(TAG, "❌❌❌ КОД -1: СТАВКА НЕ ПРИНЯТА ❌❌❌")
                                Log.e(TAG, "Возможные причины:")
                                Log.e(TAG, "  1. Невалидная сессия (куки/fsid устарели)")
                                Log.e(TAG, "  2. Изменился коэффициент")
                                Log.e(TAG, "  3. Недостаточно средств")
                                Log.e(TAG, "  4. Неверный eventId или factorId")
                                Log.e(TAG, "  5. Событие заблокировано для ставок")
                                Log.e(TAG, "  6. Несовпадение score с реальным счетом")
                                Log.e(TAG, "  7. flexBet должен быть 'any' (строчными)")
                                Log.e(TAG, "  8. flexParam должен быть true (булево)")
                                if (couponResult.has("error")) {
                                    Log.e(TAG, "  API error: ${couponResult.optString("error")}")
                                }
                                if (couponResult.has("errorMessage")) {
                                    Log.e(TAG, "  API errorMessage: ${couponResult.optString("errorMessage")}")
                                }
                            }
                            0 -> Log.d(TAG, "✅ КОД 0: СТАВКА ПРИНЯТА УСПЕШНО")
                            1 -> Log.e(TAG, "❌ КОД 1: Ошибка авторизации")
                            2 -> Log.e(TAG, "❌ КОД 2: Недостаточно средств")
                            3 -> Log.w(TAG, "⚠️ КОД 3: Изменился коэффициент")
                            4 -> Log.w(TAG, "⚠️ КОД 4: Событие недоступно")
                            else -> Log.w(TAG, "⚠️ Неизвестный код: $couponCode")
                        }
                    } else {
                        Log.e(TAG, "❌ coupon объект отсутствует в ответе!")
                    }
                    
                    onSuccess(json)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ ШАГ 3/4: Ошибка парсинга JSON: ${e.message}")
                    onError("Parse error: ${e.message}")
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
        Log.d(TAG, "========================================")
        Log.d(TAG, "📤 ШАГ 4/4: ЗАПРОС betResult (задержка ${delayMs}мс)")
        Log.d(TAG, "========================================")
        
        val jsonBody = JSONObject().apply {
            put("lang", "ru")
            put("requestId", requestId)
            put("fsid", fsid)
            put("sysId", 21)
            put("clientId", clientId)
            put("CDI", 877)
            put("deviceId", deviceId)
        }
        
        val bodyString = jsonBody.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = bodyString.toRequestBody(mediaType)
        
        val cookieHeader = buildCookieHeader(cookies, fsid)
        
        val request = Request.Builder()
            .url("https://clientsapi-lb51-w.bk6bba-resources.com/coupon/betResult")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("Accept-Language", "ru-RU,ru;q=0.9")
            .addHeader("Origin", "https://www.fon.bet")
            .addHeader("Referer", "https://www.fon.bet/")
            .addHeader("Sec-Fetch-Dest", "empty")
            .addHeader("Sec-Fetch-Mode", "cors")
            .addHeader("Sec-Fetch-Site", "same-site")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Cookie", cookieHeader)
            .build()
        
        logRequestDetails(request, bodyString)
        
        Thread.sleep(delayMs)
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ ШАГ 4/4: ОШИБКА СЕТИ")
                Log.e(TAG, "❌ ${e.message}", e)
                onError(e.message ?: "Network error")
            }
            
            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string() ?: ""
                logResponseDetails(response, bodyString)
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ ШАГ 4/4: HTTP ${response.code}")
                    onError("HTTP ${response.code}")
                    return
                }
                
                try {
                    val json = JSONObject(bodyString)
                    
                    Log.d(TAG, "📊 АНАЛИЗ betResult:")
                    if (json.has("result")) {
                        Log.d(TAG, "  result: ${json.getString("result")}")
                    }
                    if (json.has("coupon")) {
                        val coupon = json.getJSONObject("coupon")
                        Log.d(TAG, "  coupon.regId: ${coupon.optString("regId", "N/A")}")
                        Log.d(TAG, "  coupon.resultCode: ${coupon.optInt("resultCode", -999)}")
                        Log.d(TAG, "  coupon.clientSaldo: ${coupon.optDouble("clientSaldo", -1.0)}")
                        Log.d(TAG, "  coupon.amount: ${coupon.optDouble("amount", -1.0)}")
                        Log.d(TAG, "  coupon.originalK: ${coupon.optDouble("originalK", -1.0)}")
                    }
                    
                    onSuccess(json)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ ШАГ 4/4: Ошибка парсинга JSON: ${e.message}")
                    onError("Parse error: ${e.message}")
                }
            }
        })
    }

    // Главный метод размещения ставки
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
        Log.d(TAG, "========================================")
        Log.d(TAG, "🎰 НАЧАЛО ПОЛНОГО ЦИКЛА СТАВКИ")
        Log.d(TAG, "========================================")
        Log.d(TAG, "📊 Параметры: сумма=$amount ₽, clientId=$clientId")
        Log.d(TAG, "📊 Матчей: ${bets.size}")
        Log.d(TAG, "📊 FSID: ${fsid.take(10)}...")
        Log.d(TAG, "📊 DeviceID: ${deviceId.take(10)}...")
        Log.d(TAG, "📊 Cookies получено: ${cookies.size}")
        
        bets.forEachIndexed { index, bet ->
            Log.d(TAG, "📊 Матч ${index+1}: mId=${bet.mId}, type=${bet.type}, home=${bet.home}, away=${bet.away}, kef=${bet.startKf}")
        }
        
        getBetSlipInfo(bets, cookies, fsid, clientId,
            onSuccess = { betSlipInfo ->
                getBetRequestId(cookies, fsid, clientId, deviceId,
                    onSuccess = { requestId ->
                        placeBet(requestId, betSlipInfo, amount, cookies, fsid, clientId, bets,
                            onSuccess = { placeBetResponse ->
                                val betDelay = placeBetResponse.optLong("betDelay", 0)
                                getBetResult(requestId, betDelay, cookies, fsid, clientId, deviceId,
                                    onSuccess = { betResult ->
                                        Log.d(TAG, "========================================")
                                        Log.d(TAG, "🎉 ПОЛНЫЙ ЦИКЛ СТАВКИ ЗАВЕРШЕН")
                                        Log.d(TAG, "========================================")
                                        
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
                                        Log.e(TAG, "❌ Ошибка на шаге 4/4: $error")
                                        onError("Ошибка получения результата: $error")
                                    }
                                )
                            },
                            onError = { error ->
                                Log.e(TAG, "❌ Ошибка на шаге 3/4: $error")
                                onError("Ошибка размещения ставки: $error")
                            }
                        )
                    },
                    onError = { error ->
                        Log.e(TAG, "❌ Ошибка на шаге 2/4: $error")
                        onError("Ошибка получения requestId: $error")
                    }
                )
            },
            onError = { error ->
                Log.e(TAG, "❌ Ошибка на шаге 1/4: $error")
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