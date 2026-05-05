// DataManager.kt
package com.example.fonbetbot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DataManager(private val database: AppDatabase) {
    
    companion object {
        private const val TAG = "DataManager"
    }
    
    data class ImportResult(
        val newExpressCount: Int,
        val newMatchCount: Int,
        val skippedCount: Int,
        val totalReceived: Int,
        val newExpIds: List<Int> = emptyList()
    )
    
    suspend fun importBets(bets: List<BetData>): ImportResult {
        return withContext(Dispatchers.IO) {
            var newExpressCount = 0
            var newMatchCount = 0
            var skippedCount = 0
            val newExpIds = mutableListOf<Int>()
            
            val existingData = database.dataDao().getAllData()
            val existingMIds = existingData.map { it.m_id }.toSet()
            
            val maxDataId = existingData.maxOfOrNull { it.id } ?: 0
            val existingExp = database.expDao().getAllExp()
            val maxExpTableId = existingExp.maxOfOrNull { it.id } ?: 0
            val maxExpId = existingExp.maxOfOrNull { it.id_exp } ?: 0
            
            val newDataEntities = mutableListOf<DataEntity>()
            val newExpEntities = mutableListOf<ExpEntity>()
            val processedMIds = mutableSetOf<Long>()
            processedMIds.addAll(existingMIds)
            
            var currentDataId = maxDataId + 1
            var currentExpTableId = maxExpTableId + 1
            var nextExpId = maxExpId + 1
            
            // Все полученные матчи = 1 экспресс (1-4 матча)
            val newBets = bets.filter { bet -> bet.m_id !in processedMIds }
            
            if (newBets.isEmpty()) {
                return@withContext ImportResult(0, 0, bets.size, bets.size)
            }
            
            val currentExpId = nextExpId++
            newExpIds.add(currentExpId)
            
            for (bet in newBets) {
                newDataEntities.add(
                    DataEntity(
                        id = currentDataId++,
                        id_exp = currentExpId,
                        m_id = bet.m_id,
                        id_liga = bet.id_liga,
                        liganame = bet.liganame,
                        id_home = bet.comand1id,
                        home = bet.home,
                        id_away = bet.comand2id,
                        away = bet.away,
                        startkf = bet.startkf,
                        lastkf = bet.lastkf,
                        curtime = bet.curtime,
                        sh = bet.sh,
                        sa = bet.sa,
                        type = bet.type,
                        sts = bet.sts,
                        url = bet.url,
                        uzh = bet.uzh.toString(),
                        tbtype = bet.tbtype
                    )
                )
                processedMIds.add(bet.m_id)
            }
            
            val kfall = if (newBets.isNotEmpty()) {
                newBets.map { it.lastkf }.reduce { acc, kf -> acc * kf }
            } else 0.0
            
            newExpEntities.add(
                ExpEntity(
                    id = currentExpTableId++,
                    id_exp = currentExpId,
                    kfall = kfall,
                    sts_all = 1,
                    ct = LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    ),
                    profloss = 0.0,
                    balans = 1000,
                    sumbet = 30,
                    strategy = "api_import",
                    id_exp_replace = 0
                )
            )
            
            newExpressCount = 1
            newMatchCount = newBets.size
            skippedCount = bets.size - newBets.size
            
            if (newDataEntities.isNotEmpty()) {
                database.dataDao().insertAll(newDataEntities)
            }
            if (newExpEntities.isNotEmpty()) {
                database.expDao().insertAll(newExpEntities)
            }
            
            ImportResult(
                newExpressCount = newExpressCount,
                newMatchCount = newMatchCount,
                skippedCount = skippedCount,
                totalReceived = bets.size,
                newExpIds = newExpIds
            )
        }
    }
}