// ApiClient.kt - ПОЛНАЯ ИСПРАВЛЕННАЯ ВЕРСИЯ С matchTime
package com.example.fonbetbot

import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
// Data class для ставки из ответа

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

// Метод получения ставок

class ApiClient {
    private val client = OkHttpClient()
    private val TAG = "ApiClient"
    
    // Data class для матча с коэффициентами и временем
    data class MatchFactors(
        val score1: Int,
        val score2: Int,
        val matchTime: Int,
        val factors: Map<Int, Double>,
        val handicaps: Map<Int, Double>
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
        onSuccess: (Double?) -> Unit,
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
                    
                    if (!response.isSuccessful) {
                        onError("Ошибка ${response.code}")
                        return
                    }
                    
                    try {
                        val json = JSONObject(bodyString)
                        
                        val saldo: Double? = when {
                            json.has("saldo") -> json.getDouble("saldo")
                            json.has("balance") -> json.getDouble("balance")
                            json.has("amount") -> json.getDouble("amount")
                            json.has("data") -> {
                                val data = json.getJSONObject("data")
                                when {
                                    data.has("saldo") -> data.getDouble("saldo")
                                    data.has("balance") -> data.getDouble("balance")
                                    else -> null
                                }
                            }
                            else -> null
                        }
                        
                        onSuccess(saldo)
                    } catch (e: Exception) {
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
                        
                        // Парсим счет и время
                        if (json.has("liveEventInfos")) {
                            val liveEventInfos = json.getJSONArray("liveEventInfos")
                            if (liveEventInfos.length() > 0) {
                                val liveEventInfo = liveEventInfos.getJSONObject(0)
                                
                                // Получаем время матча
                                matchTime = liveEventInfo.optInt("timerSeconds", 0)
                                
                                // Получаем счет
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
                        
                        // Парсим коэффициенты из customFactors
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
    
    // Метод получения времени матча (оставлен для совместимости)
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
}