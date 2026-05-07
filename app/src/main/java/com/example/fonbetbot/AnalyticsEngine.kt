// AnalyticsEngine.kt — ИСПРАВЛЕННАЯ ВЕРСИЯ (правильные формулы и sts_all по счёту)
package com.example.fonbetbot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.*

data class MatchResult(
    val matchId: Long,
    val home: String,
    val away: String,
    val sh: Int,
    val sa: Int,
    val type: Int,
    val isWin: Boolean,
    val liganame: String = "",
    val startkf: Double = 0.0,
    val curtime: Int = 0  // <-- ДОБАВЛЕНО: минута матча
)

data class ExpressResult(
    val expId: Int,
    val matches: List<MatchResult>,
    val isWin: Boolean,
    val dateTime: LocalDateTime,
    val isReplaced: Boolean,
    val weekNumber: Int = 0,
    val yearWeek: String = "",
    val totalStartKf: Double = 0.0
)

data class AnalyticsSummary(
    val totalExpress: Int,
    val winExpress: Int,
    val loseExpress: Int,
    val winRate: Double,
    val details: String
)

data class WeekStats(
    val yearWeek: String,
    val startDate: String,
    val endDate: String,
    val total: Int,
    val wins: Int,
    val rate: Double
)

data class LeagueStats(
    val liganame: String,
    val total: Int,
    val wins: Int,
    val rate: Double
)

data class MixedTypeStats(
    val typeCombination: String,
    val total: Int,
    val wins: Int,
    val rate: Double
)

class AnalyticsEngine(private val database: AppDatabase) {
    
    suspend fun calculateAnalytics(): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            val allData = database.dataDao().getAllData()
            val allExp = database.expDao().getAllExp()
            
            val dataByExpId = allData.groupBy { it.id_exp }
            val expMap = allExp.associateBy { it.id_exp }
            
            val expressResults = mutableListOf<ExpressResult>()
            val replacedExpIds = allExp.filter { it.id_exp_replace > 0 }
                .map { it.id_exp }
                .toSet()
            
            // Собираем обновлённые статусы для записи в БД
            val updatedExpStatuses = mutableListOf<ExpEntity>()
            
            for ((expId, matches) in dataByExpId) {
                if (matches.size < 2) continue
                
                val exp = expMap[expId] ?: continue
                
                val matchResults = matches.map { match ->
                    // ПРАВИЛЬНЫЕ ФОРМУЛЫ С +1.5
                    val isWin = when (match.type) {
                        924 -> match.sh >= match.sa
                        927 -> (match.sh + 1.5) > match.sa    // Ф1(+1.5)
                        928 -> (match.sa + 1.5) >= match.sh   // Ф2(+1.5)
                        else -> match.sh >= match.sa
                    }
                    
                    MatchResult(
                        matchId = match.m_id,
                        home = match.home,
                        away = match.away,
                        sh = match.sh,
                        sa = match.sa,
                        type = match.type,
                        isWin = isWin,
                        liganame = match.liganame,
                        startkf = match.startkf,
                        curtime = match.curtime  // <-- ДОБАВЛЕНО
                    )
                }
                
                // Определяем результат экспресса ТОЛЬКО ПО СЧЁТУ
                val allHaveScore = matchResults.all { it.sh > 0 || it.sa > 0 }
                val hasLosingMatch = matchResults.any { !it.isWin }
                
                val allWins = if (allHaveScore) {
                    matchResults.all { it.isWin }
                } else {
                    false
                }
                
                val dateTime = parseDateTime(exp.ct)
                val isReplaced = expId in replacedExpIds
                
                val weekField = WeekFields.of(Locale.getDefault())
                val weekNumber = dateTime.get(weekField.weekOfWeekBasedYear())
                val yearWeek = "${dateTime.year}-W${String.format("%02d", weekNumber)}"
                
                val allStartKf = matches.map { it.startkf }
                val totalStartKf = if (allStartKf.all { it > 0 }) {
                    allStartKf.reduce { acc, kf -> acc * kf }
                } else 0.0
                
                // Пересчитываем sts_all на основе счёта
                val newStsAll = when {
                    hasLosingMatch -> -1
                    allHaveScore && allWins -> 2
                    else -> 1 // Нет полных данных
                }
                
                // Обновляем статус в БД если он изменился
                if (exp.sts_all != newStsAll) {
                    updatedExpStatuses.add(exp.copy(sts_all = newStsAll))
                }
                
                expressResults.add(
                    ExpressResult(
                        expId = expId,
                        matches = matchResults,
                        isWin = allWins,
                        dateTime = dateTime,
                        isReplaced = isReplaced,
                        weekNumber = weekNumber,
                        yearWeek = yearWeek,
                        totalStartKf = totalStartKf
                    )
                )
            }
            
            // Сохраняем обновлённые статусы в БД
            if (updatedExpStatuses.isNotEmpty()) {
                val allExpList = allExp.toMutableList()
                for (updated in updatedExpStatuses) {
                    val index = allExpList.indexOfFirst { it.id == updated.id }
                    if (index >= 0) {
                        allExpList[index] = updated
                    }
                }
                database.expDao().deleteAll()
                database.expDao().insertAll(allExpList)
            }
            
            calculateStatistics(expressResults)
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
    
    private fun calculateStatistics(expressResults: List<ExpressResult>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        result["allExpresses"] = expressResults
        
        val totalExpress = expressResults.size
        val winExpress = expressResults.count { it.isWin }
        val loseExpress = totalExpress - winExpress
        val winRate = if (totalExpress > 0) (winExpress.toDouble() / totalExpress) * 100 else 0.0
        
        result["total"] = AnalyticsSummary(
            totalExpress = totalExpress,
            winExpress = winExpress,
            loseExpress = loseExpress,
            winRate = winRate,
            details = buildString {
                appendLine("=== ОБЩАЯ СТАТИСТИКА ===")
                appendLine("Всего экспрессов: $totalExpress")
                appendLine("Выигрышных: $winExpress")
                appendLine("Проигрышных: $loseExpress")
                appendLine("Процент проходимости: ${String.format(Locale.US, "%.1f", winRate)}%")
                appendLine()
            }
        )
        
        val byDayOfWeek = expressResults.groupBy { it.dateTime.dayOfWeek }
        val dayStats = LinkedHashMap<DayOfWeek, Pair<Int, Int>>()
        for (day in DayOfWeek.entries) {
            val expresses = byDayOfWeek[day] ?: emptyList()
            dayStats[day] = Pair(expresses.count { it.isWin }, expresses.size)
        }
        result["byDayOfWeek"] = dayStats
        
        val byHour = expressResults.groupBy { it.dateTime.hour }
        val hourStats = TreeMap<Int, Pair<Int, Int>>()
        for (hour in 0..23) {
            val expresses = byHour[hour] ?: emptyList()
            hourStats[hour] = Pair(expresses.count { it.isWin }, expresses.size)
        }
        result["byHour"] = hourStats
        
        val byMonth = expressResults.groupBy { it.dateTime.month }
        val monthStats = LinkedHashMap<String, Pair<Int, Int>>()
        val monthNames = mapOf(
            1 to "Январь", 2 to "Февраль", 3 to "Март", 4 to "Апрель",
            5 to "Май", 6 to "Июнь", 7 to "Июль", 8 to "Август",
            9 to "Сентябрь", 10 to "Октябрь", 11 to "Ноябрь", 12 to "Декабрь"
        )
        val sortedMonths = byMonth.entries.sortedBy { it.key.value }
        for ((month, expresses) in sortedMonths) {
            monthStats[monthNames[month.value] ?: month.name] = Pair(
                expresses.count { it.isWin }, expresses.size
            )
        }
        result["byMonth"] = monthStats
        
        val byWeek = expressResults.groupBy { it.yearWeek }
        val weekStatsList = mutableListOf<WeekStats>()
        
        for ((yearWeek, expresses) in byWeek) {
            if (expresses.isEmpty()) continue
            val sorted = expresses.sortedBy { it.dateTime }
            val startDate = sorted.first().dateTime.format(DateTimeFormatter.ofPattern("dd.MM"))
            val endDate = sorted.last().dateTime.format(DateTimeFormatter.ofPattern("dd.MM"))
            val wins = expresses.count { it.isWin }
            val total = expresses.size
            val rate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
            
            weekStatsList.add(WeekStats(yearWeek, startDate, endDate, total, wins, rate))
        }
        
        weekStatsList.sortBy { it.yearWeek }
        result["byWeek"] = weekStatsList
        
        val allMatches = expressResults.flatMap { express -> express.matches }
        val byLeague = allMatches.groupBy { it.liganame }
        val leagueStatsList = mutableListOf<LeagueStats>()
        
        for ((league, matches) in byLeague) {
            val wins = matches.count { it.isWin }
            val total = matches.size
            val rate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
            leagueStatsList.add(LeagueStats(league, total, wins, rate))
        }
        
        leagueStatsList.sortByDescending { it.total }
        result["byLeague"] = leagueStatsList
        result["topLeagues"] = leagueStatsList.take(20)
        
        val typeStats = mutableMapOf<Int, Triple<Int, Int, Double>>()
        for (type in listOf(924, 927, 928)) {
            val typeMatches = allMatches.filter { it.type == type }
            val wins = typeMatches.count { it.isWin }
            val total = typeMatches.size
            val rate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
            typeStats[type] = Triple(wins, total, rate)
        }
        result["byType"] = typeStats
        
        val mixedStatsList = mutableListOf<MixedTypeStats>()
        
        val mixed924927 = expressResults.filter { express ->
            val types = express.matches.map { it.type }.distinct()
            types.contains(924) && types.contains(927) && !types.contains(928)
        }
        mixedStatsList.add(MixedTypeStats(
            "924 + 927", mixed924927.size,
            mixed924927.count { it.isWin },
            if (mixed924927.isNotEmpty()) (mixed924927.count { it.isWin }.toDouble() / mixed924927.size) * 100 else 0.0
        ))
        
        val mixed924928 = expressResults.filter { express ->
            val types = express.matches.map { it.type }.distinct()
            types.contains(924) && types.contains(928) && !types.contains(927)
        }
        mixedStatsList.add(MixedTypeStats(
            "924 + 928", mixed924928.size,
            mixed924928.count { it.isWin },
            if (mixed924928.isNotEmpty()) (mixed924928.count { it.isWin }.toDouble() / mixed924928.size) * 100 else 0.0
        ))
        
        val mixed927928 = expressResults.filter { express ->
            val types = express.matches.map { it.type }.distinct()
            types.contains(927) && types.contains(928) && !types.contains(924)
        }
        mixedStatsList.add(MixedTypeStats(
            "927 + 928", mixed927928.size,
            mixed927928.count { it.isWin },
            if (mixed927928.isNotEmpty()) (mixed927928.count { it.isWin }.toDouble() / mixed927928.size) * 100 else 0.0
        ))
        
        val mixedAll = expressResults.filter { express ->
            val types = express.matches.map { it.type }.distinct()
            types.containsAll(listOf(924, 927, 928))
        }
        mixedStatsList.add(MixedTypeStats(
            "924 + 927 + 928", mixedAll.size,
            mixedAll.count { it.isWin },
            if (mixedAll.isNotEmpty()) (mixedAll.count { it.isWin }.toDouble() / mixedAll.size) * 100 else 0.0
        ))
        
        val only924 = expressResults.filter { express ->
            express.matches.all { it.type == 924 }
        }
        mixedStatsList.add(MixedTypeStats(
            "Только 924", only924.size,
            only924.count { it.isWin },
            if (only924.isNotEmpty()) (only924.count { it.isWin }.toDouble() / only924.size) * 100 else 0.0
        ))
        
        val only927 = expressResults.filter { express ->
            express.matches.all { it.type == 927 }
        }
        mixedStatsList.add(MixedTypeStats(
            "Только 927", only927.size,
            only927.count { it.isWin },
            if (only927.isNotEmpty()) (only927.count { it.isWin }.toDouble() / only927.size) * 100 else 0.0
        ))
        
        val only928 = expressResults.filter { express ->
            express.matches.all { it.type == 928 }
        }
        mixedStatsList.add(MixedTypeStats(
            "Только 928", only928.size,
            only928.count { it.isWin },
            if (only928.isNotEmpty()) (only928.count { it.isWin }.toDouble() / only928.size) * 100 else 0.0
        ))
        
        result["mixedTypes"] = mixedStatsList
        
        return result
    }
}