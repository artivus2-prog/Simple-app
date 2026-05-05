// ApiModels.kt
package com.example.fonbetbot

import com.google.gson.annotations.SerializedName

data class BetData(
    @SerializedName("sport") val sport: String = "",
    @SerializedName("id_exp") val id_exp: Int = 0,
    @SerializedName("m_id") val m_id: Long = 0,
    @SerializedName("id_liga") val id_liga: Int = 0,
    @SerializedName("liganame") val liganame: String = "",
    @SerializedName("home") val home: String = "",
    @SerializedName("away") val away: String = "",
    @SerializedName("comand1id") val comand1id: Long = 0,
    @SerializedName("comand2id") val comand2id: Long = 0,
    @SerializedName("curtime") val curtime: Int = 0,
    @SerializedName("sh") val sh: Int = 0,
    @SerializedName("sa") val sa: Int = 0,
    @SerializedName("startkf") val startkf: Double = 0.0,
    @SerializedName("lastkf") val lastkf: Double = 0.0,
    @SerializedName("type") val type: Int = 924,
    @SerializedName("sts") val sts: Int = 1,
    @SerializedName("url") val url: String = "",
    @SerializedName("uzh") val uzh: Double = 0.0,
    @SerializedName("tbtype") val tbtype: Int = 0
)