// ExpEntity.kt
package com.example.fonbetbot

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exp")
data class ExpEntity(
    @PrimaryKey
    val id: Int,
    val id_exp: Int,
    val kfall: Double,
    val sts_all: Int,
    val ct: String,
    val profloss: Double,
    val balans: Int,
    val sumbet: Int,
    val strategy: String,
    val id_exp_replace: Int = 0
)