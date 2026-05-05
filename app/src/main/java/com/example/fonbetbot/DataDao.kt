// DataDao.kt
package com.example.fonbetbot

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DataDao {
    @Query("SELECT * FROM data ORDER BY id")
    suspend fun getAllData(): List<DataEntity>  // Изменено на suspend fun с List
    
    @Query("SELECT * FROM data ORDER BY id")
    fun getAllDataFlow(): Flow<List<DataEntity>>  // Версия с Flow
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<DataEntity>)
    
    @Query("DELETE FROM data")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM data WHERE id_exp = :idExp")
    fun getDataByExpId(idExp: Int): Flow<List<DataEntity>>
    
    @Query("SELECT COUNT(*) FROM data")
    suspend fun getDataCount(): Int
    
    @Query("SELECT COUNT(*) FROM exp")
    suspend fun getExpCount(): Int
}