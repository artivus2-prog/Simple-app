// ApiClient.kt
package com.example.fonbetbot

import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ApiClient {
    private val client = OkHttpClient()
    
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
                        
                        val saldo = when {
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
}