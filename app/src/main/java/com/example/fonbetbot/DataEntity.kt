// DataEntity.kt
package com.example.fonbetbot

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "data")
data class DataEntity(
    @PrimaryKey
    val id: Int,
    val id_exp: Int,
    val m_id: Long,
    val id_liga: Int,
    val liganame: String,
    val id_home: Long,
    val home: String,
    val id_away: Long,
    val away: String,
    val startkf: Double,
    val lastkf: Double,
    val curtime: Int,
    val sh: Int,
    val sa: Int,
    val type: Int,
    val sts: Int,
    val url: String = "",
    val uzh: String = "",  // Изменено с Double на String
    val tbtype: Int
)