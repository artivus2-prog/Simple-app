// AnalyticsEngine.kt — УПРОЩЕННАЯ ВЕРСИЯ (только вывод таблицы, без статусов)
package com.example.fonbetbot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class MatchResultSimplified(
    val matchId: Long,
    val home: String,
    val away: String,
    val sh: Int,
    val sa: Int,
    val type: Int,
    val liganame: String = "",
    val startkf: Double = 0.0
)

data class ExpressResultSimplified(
    val expId: Int,
    val matches: List<MatchResultSimplified>,
    val dateTime: LocalDateTime,
    val totalStartKf: Double = 0.0
)

class AnalyticsEngine(private val database: AppDatabase) {
    
    suspend fun calculateAnalytics(): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            val allData = database.dataDao().getAllData()
            val allExp = database.expDao().getAllExp()
            
            val dataByExpId = allData.groupBy { it.id_exp }
            val expMap = allExp.associateBy { it.id_exp }
            
            val expressResults = mutableListOf<ExpressResultSimplified>()
            
            for ((expId, matches) in dataByExpId) {
                if (matches.size < 2) continue
                
                val exp = expMap[expId] ?: continue
                
                val matchResults = matches.map { match ->
                    MatchResultSimplified(
                        matchId = match.m_id,
                        home = match.home,
                        away = match.away,
                        sh = match.sh,
                        sa = match.sa,
                        type = match.type,
                        liganame = match.liganame,
                        startkf = match.startkf
                    )
                }
                
                val dateTime = parseDateTime(exp.ct)
                
                val allStartKf = matches.map { it.startkf }
                val totalStartKf = if (allStartKf.all { it > 0 }) {
                    allStartKf.reduce { acc, kf -> acc * kf }
                } else 0.0
                
                expressResults.add(
                    ExpressResultSimplified(
                        expId = expId,
                        matches = matchResults,
                        dateTime = dateTime,
                        totalStartKf = totalStartKf
                    )
                )
            }
            
            // Сортируем по дате (новые сверху)
            expressResults.sortByDescending { it.dateTime }
            
            mutableMapOf<String, Any>(
                "allExpresses" to expressResults
            )
        }
    }
    
    private fun parseDateTime(ct: String): LocalDateTime {
        return try {
            val trimmed = ct.trim()
            val formatters = listOf(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
            )
            for (formatter in formatters) {
                try {
                    return LocalDateTime.parse(trimmed, formatter)
                } catch (_: Exception) { }
            }
            LocalDateTime.now()
        } catch (e: Exception) {
            LocalDateTime.now()
        }
    }
}