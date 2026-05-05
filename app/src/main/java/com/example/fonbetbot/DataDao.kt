// DataDao.kt
package com.example.fonbetbot

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DataDao {
    @Query("SELECT * FROM data ORDER BY id")
    fun getAllData(): Flow<List<DataEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<DataEntity>)
    
    @Query("DELETE FROM data")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM data WHERE id_exp = :idExp")
    fun getDataByExpId(idExp: Int): Flow<List<DataEntity>>
    
    // Запрос с JOIN
    @Query("""
        SELECT d.*, e.kfall, e.sts_all, e.strategy 
        FROM data d 
        INNER JOIN exp e ON d.id_exp = e.id_exp 
        ORDER BY d.id
    """)
    fun getDataWithExp(): Flow<List<DataWithExp>>
}

data class DataWithExp(
    val id: Int,
    val id_exp: Int,
    val m_id: Int,
    val liganame: String,
    val home: String,
    val away: String,
    val startkf: Double,
    val lastkf: Double,
    val curtime: Int,
    val sh: Int,
    val sa: Int,
    val type: Int,
    val sts: Int,
    val kfall: Double,
    val sts_all: Int,
    val strategy: String
)