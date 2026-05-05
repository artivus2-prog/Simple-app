// ExpDao.kt
package com.example.fonbetbot

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpDao {
    @Query("SELECT * FROM exp ORDER BY id")
    fun getAllExp(): Flow<List<ExpEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(exp: List<ExpEntity>)
    
    @Query("DELETE FROM exp")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM exp WHERE id_exp = :idExp")
    fun getExpById(idExp: Int): Flow<List<ExpEntity>>
}