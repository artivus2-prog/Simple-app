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
// ApiClient.kt - ИСПРАВЛЕННЫЙ МЕТОД getSaldo

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
                
                Log.d(TAG, "=== session/info FULL RESPONSE ===")
                Log.d(TAG, bodyString)
                Log.d(TAG, "=== END session/info ===")
                
                if (!response.isSuccessful) {
                    onError("Ошибка ${response.code}")
                    return
                }
                
                try {
                    val json = JSONObject(bodyString)
                    
                    // 1. ИЗВЛЕКАЕМ clientId
                    val extractedClientId: Long? = when {
                        json.has("clientId") && !json.isNull("clientId") -> {
                            val cid = json.getLong("clientId")
                            Log.d(TAG, "✅ clientId из корня: $cid")
                            cid
                        }
                        else -> {
                            Log.w(TAG, "⚠️ clientId не найден в ответе")
                            null
                        }
                    }
                    
                    // 2. ИЗВЛЕКАЕМ saldo
                    val saldo: Double? = when {
                        json.has("saldo") && !json.isNull("saldo") -> {
                            val s = json.getDouble("saldo")
                            Log.d(TAG, "✅ saldo: $s")
                            s
                        }
                        else -> {
                            Log.w(TAG, "⚠️ saldo не найден в ответе")
                            null
                        }
                    }
                    
                    // 3. ИЗВЛЕКАЕМ ИМЯ ПОЛЬЗОВАТЕЛЯ
                    // Приоритет: registration.name > registration.fullName > registration.firstName+lastName
                    val userName: String? = try {
                        when {
                            // Проверяем registration объект
                            json.has("registration") && !json.isNull("registration") -> {
                                val registration = json.getJSONObject("registration")
                                Log.d(TAG, "📋 registration объект найден")
                                
                                when {
                                    // registration.name (полное имя: "Артамонов Илья Витальевич")
                                    registration.has("name") && !registration.isNull("name") -> {
                                        val name = registration.getString("name")
                                        Log.d(TAG, "✅ Имя из registration.name: $name")
                                        name
                                    }
                                    // registration.fullName
                                    registration.has("fullName") && !registration.isNull("fullName") -> {
                                        val fullName = registration.getString("fullName")
                                        Log.d(TAG, "✅ Имя из registration.fullName: $fullName")
                                        fullName
                                    }
                                    // registration.firstName + registration.lastName
                                    registration.has("firstName") && registration.has("lastName") -> {
                                        val firstName = registration.optString("firstName", "")
                                        val lastName = registration.optString("lastName", "")
                                        val combined = "$lastName $firstName".trim()
                                        Log.d(TAG, "✅ Имя из registration.firstName+lastName: $combined")
                                        combined
                                    }
                                    else -> {
                                        Log.w(TAG, "⚠️ registration найден, но нет поля с именем")
                                        Log.d(TAG, "registration keys: ${registration.keys().asSequence().toList()}")
                                        null
                                    }
                                }
                            }
                            // Запасные варианты в корне JSON
                            json.has("userName") && !json.isNull("userName") -> {
                                val name = json.getString("userName")
                                Log.d(TAG, "✅ Имя из корневого userName: $name")
                                name
                            }
                            json.has("fullName") && !json.isNull("fullName") -> {
                                val name = json.getString("fullName")
                                Log.d(TAG, "✅ Имя из корневого fullName: $name")
                                name
                            }
                            json.has("name") && !json.isNull("name") -> {
                                val name = json.getString("name")
                                Log.d(TAG, "✅ Имя из корневого name: $name")
                                name
                            }
                            else -> {
                                Log.w(TAG, "⚠️ Имя пользователя не найдено ни в одном поле")
                                null
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Ошибка извлечения имени: ${e.message}", e)
                        null
                    }
                    
                    val sessionInfo = SessionInfo(
                        saldo = saldo,
                        clientId = extractedClientId,
                        userName = userName
                    )
                    
                    Log.d(TAG, "📊 ИТОГО SessionInfo:")
                    Log.d(TAG, "  clientId: $extractedClientId")
                    Log.d(TAG, "  saldo: $saldo")
                    Log.d(TAG, "  userName: $userName")
                    
                    onSuccess(sessionInfo)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Ошибка парсинга: ${e.message}", e)
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
                                    tbType = betJson.optInt("tbtype", 0),
                                    matchTime = betJson.optInt("match_time", 0)
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
    // Шаг 3: Разместить ставку (ИСПРАВЛЕННАЯ ВЕРСИЯ)
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
    // Детальное логирование входящих параметров
    Log.d(TAG, "═══════════════════════════════════════")
    Log.d(TAG, "🎰 placeBet: НАЧАЛО РАЗМЕЩЕНИЯ СТАВКИ")
    Log.d(TAG, "═══════════════════════════════════════")
    Log.d(TAG, "📋 requestId: $requestId")
    Log.d(TAG, "💰 amount: $amount")
    Log.d(TAG, "👤 clientId: $clientId")
    Log.d(TAG, "🔑 fsid: ${fsid.take(20)}...")
    Log.d(TAG, "📊 Количество ставок: ${bets.size}")
    
    // Логируем содержимое betSlipInfo
    try {
        Log.d(TAG, "📦 betSlipInfo K: ${betSlipInfo.optDouble("K", 0.0)}")
        val betsArray = betSlipInfo.optJSONArray("bets")
        if (betsArray != null) {
            for (i in 0 until betsArray.length()) {
                val bet = betsArray.getJSONObject(i)
                val event = bet.getJSONObject("event")
                val factor = bet.getJSONObject("factor")
                Log.d(TAG, "  Ставка #${i+1}:")
                Log.d(TAG, "    Событие: ${event.optString("team1", "?")} vs ${event.optString("team2", "?")}")
                Log.d(TAG, "    Турнир: ${event.optString("competitionName", "?")}")
                Log.d(TAG, "    Ставка: ${factor.optString("couponChoiceCaption", "?")}")
                Log.d(TAG, "    Коэф: ${factor.optDouble("v", 0.0)}")
                Log.d(TAG, "    eventId: ${event.optInt("id", 0)}")
                Log.d(TAG, "    factorId: ${factor.optInt("id", 0)}")
                if (factor.has("param")) {
                    Log.d(TAG, "    param: ${factor.optInt("param", 0)}")
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "❌ Ошибка при логировании betSlipInfo: ${e.message}")
    }
    
    // Формируем массив ставок для купона
    val betsArray = JSONArray()
    val betsFromSlip = betSlipInfo.getJSONArray("bets")
    
    // КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: создаем карту для быстрого поиска tbtype
    val paramsMap = mutableMapOf<String, Int>()
    bets.forEach { betData ->
        val key = "${betData.mId}_${betData.type}"
        if (betData.tbType > 0) {
            paramsMap[key] = betData.tbType
        }
    }
    
    for (i in 0 until betsFromSlip.length()) {
        val bet = betsFromSlip.getJSONObject(i)
        val eventId = bet.getJSONObject("event").getInt("id")
        val factorId = bet.getJSONObject("factor").getInt("id")
        val factorValue = bet.getJSONObject("factor").getDouble("v")
        val score = bet.getJSONObject("event").optString("score", "0:0")
        
        val betItem = JSONObject().apply {
            put("event", eventId)
            put("factor", factorId)
            put("value", factorValue)
            put("score", score)
            
            // ИСПРАВЛЕНИЕ: добавляем param для тоталов
            // Список типов тоталов (как в Python)
            val totalTypes = listOf(1696, 1793, 1796, 1799, 930)
            if (factorId in totalTypes) {
                // Ищем параметр в карте или в betSlipInfo
                val key = "${eventId}_${factorId}"
                when {
                    paramsMap.containsKey(key) && paramsMap[key]!! > 0 -> {
                        put("param", paramsMap[key]!!)
                        Log.d(TAG, "  ✅ Добавлен param=${paramsMap[key]} для тотала $factorId (из BetData)")
                    }
                    bet.getJSONObject("factor").has("param") -> {
                        val param = bet.getJSONObject("factor").getInt("param")
                        if (param > 0) {
                            put("param", param)
                            Log.d(TAG, "  ✅ Добавлен param=$param для тотала $factorId (из betSlipInfo)")
                        }
                    }
                    else -> {
                        Log.d(TAG, "  ⚠️ Тотал $factorId без param")
                    }
                }
            }
        }
        betsArray.put(betItem)
    }
    
    // Формируем купон
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
    
    // ИСПРАВЛЕНИЕ: добавляем CDI
    val jsonBody = JSONObject().apply {
        put("requestId", requestId)
        put("lang", "ru")
        put("clientId", clientId)
        put("fsid", fsid)
        put("sysId", 21)
        put("CDI", 877)  // ← ДОБАВЛЕН CDI
        put("deviceId", "9B883CE30780121A11348AF8C3542A89") // ← ДОБАВЛЕН deviceId
        put("coupon", coupon)
    }
    
    // Логируем полный payload
    Log.d(TAG, "📤 placeBet PAYLOAD:")
    try {
        Log.d(TAG, jsonBody.toString(2))
    } catch (e: Exception) {
        Log.d(TAG, jsonBody.toString())
    }
    
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val body = jsonBody.toString().toRequestBody(mediaType)
    
    // ИСПРАВЛЕНИЕ: правильный URL (.com вместо .ru)
    val url = "https://clientsapi-lb52-w.bk6bba-resources.com/coupon/bet"
    Log.d(TAG, "🌐 URL: $url")
    
    val requestBuilder = Request.Builder()
        .url(url)
        .post(body)
        .addHeader("Content-Type", "application/json")
        .addHeader("Accept", "application/json")
    
    val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    if (cookieHeader.isNotEmpty()) {
        requestBuilder.addHeader("Cookie", cookieHeader)
        Log.d(TAG, "🍪 Cookie header: ${cookieHeader.take(100)}...")
    } else {
        Log.w(TAG, "⚠️ Cookie header ПУСТОЙ!")
    }
    
    client.newCall(requestBuilder.build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e(TAG, "═══════════════════════════════════════")
            Log.e(TAG, "❌ placeBet NETWORK ERROR: ${e.message}")
            Log.e(TAG, "═══════════════════════════════════════")
            onError("Network error: ${e.message}")
        }
        
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val bodyString = response.body?.string() ?: ""
                val code = response.code
                val isSuccess = response.isSuccessful
                
                Log.d(TAG, "═══════════════════════════════════════")
                Log.d(TAG, "📥 placeBet RESPONSE")
                Log.d(TAG, "═══════════════════════════════════════")
                Log.d(TAG, "  HTTP Status: $code")
                Log.d(TAG, "  Successful: $isSuccess")
                Log.d(TAG, "  Headers: ${response.headers}")
                Log.d(TAG, "  Body: ${bodyString.take(2000)}") // Ограничиваем длину
                Log.d(TAG, "═══════════════════════════════════════")
                
                if (!isSuccess) {
                    val errorMsg = "HTTP $code: ${bodyString.take(500)}"
                    Log.e(TAG, "❌ $errorMsg")
                    onError(errorMsg)
                    return
                }
                
                try {
                    val json = JSONObject(bodyString)
                    
                    // Логируем результат
                    val result = json.optString("result", "unknown")
                    Log.d(TAG, "  result: $result")
                    
                    if (result == "betDelay") {
                        val delay = json.optLong("betDelay", 0)
                        Log.d(TAG, "  ⏳ betDelay: $delay мс")
                    }
                    
                    if (result == "couponResult") {
                        val coupon = json.optJSONObject("coupon")
                        if (coupon != null) {
                            Log.d(TAG, "  📋 regId: ${coupon.optString("regId", "?")}")
                            Log.d(TAG, "  🔢 checkCode: ${coupon.optString("checkCode", "?")}")
                            Log.d(TAG, "  💰 amount: ${coupon.optDouble("amount", 0.0)}")
                            Log.d(TAG, "  📈 originalK: ${coupon.optDouble("originalK", 0.0)}")
                            Log.d(TAG, "  💳 clientSaldo: ${coupon.optDouble("clientSaldo", 0.0)}")
                            Log.d(TAG, "  🎁 bonusSaldo: ${coupon.optDouble("bonusAccountClientSaldo", 0.0)}")
                            Log.d(TAG, "  ✅ resultCode: ${coupon.optInt("resultCode", -999)}")
                        }
                    }
                    
                    onSuccess(json)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Parse error: ${e.message}")
                    Log.e(TAG, "Raw body: ${bodyString.take(1000)}")
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
        Log.d(TAG, "🎰 makeBet: ${bets.size} матчей, сумма $amount ₽")
        
        getBetSlipInfo(bets, cookies, fsid, clientId,
            onSuccess = { betSlipInfo ->
                Log.d(TAG, "✅ betSlipInfo получен, K=${betSlipInfo.optDouble("K")}")
                
                getBetRequestId(cookies, fsid, clientId, deviceId,
                    onSuccess = { requestId ->
                        Log.d(TAG, "✅ requestId получен: $requestId")
                        
                        placeBet(requestId, betSlipInfo, amount, cookies, fsid, clientId, bets,
                            onSuccess = { placeBetResponse ->
                                Log.d(TAG, "✅ Ставка размещена, ответ: $placeBetResponse")
                                
                                val betDelay = placeBetResponse.optLong("betDelay", 0)
                                
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