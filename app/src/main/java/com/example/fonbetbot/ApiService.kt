// ApiService.kt
package com.example.fonbetbot

import retrofit2.http.GET
import retrofit2.http.Query

interface ApiService {
    @GET("getBets")
    suspend fun getBets(): List<BetData>
}