// AnalyticsEngine.kt — ИСПРАВЛЕННАЯ ВЕРСИЯ С ГРАДАЦИЕЙ startkf
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
    val curtime: Int = 0
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

// НОВАЯ структура данных для градации по startkf
data class KfRangeStats(
    val rangeLabel: String,    // Например "1.00-1.05"
    val minKf: Double,
    val maxKf: Double,
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
            
            val updatedExpStatuses = mutableListOf<ExpEntity>()
            
            // Собираем все startkf для определения диапазонов
            val allStartKfs = mutableListOf<Double>()
            
            for ((expId, matches) in dataByExpId) {
                if (matches.isEmpty()) continue
                
                val exp = expMap[expId] ?: continue
                
                val matchResults = matches.map { match ->
                    val isWin = when (match.type) {
                        924 -> match.sh >= match.sa
                        927 -> (match.sh + 1.5) > match.sa
                        928 -> (match.sa + 1.5) >= match.sh
                        else -> match.sh >= match.sa
                    }
                    
                    // Сохраняем startkf для статистики
                    if (match.startkf > 0) {
                        synchronized(allStartKfs) {
                            allStartKfs.add(match.startkf)
                        }
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
                        curtime = match.curtime
                    )
                }
                
                val allHaveScore = matchResults.all { it.curtime > 0 }
                
                val allWins = if (allHaveScore) {
                    matchResults.all { it.isWin }
                } else {
                    false
                }
                
                val hasLosingMatch = matchResults.any { !it.isWin && it.curtime > 0 }
                
                val dateTime = parseDateTime(exp.ct)
                val isReplaced = expId in replacedExpIds
                
                val weekField = WeekFields.of(Locale.getDefault())
                val weekNumber = dateTime.get(weekField.weekOfWeekBasedYear())
                val yearWeek = "${dateTime.year}-W${String.format("%02d", weekNumber)}"
                
                val allStartKf = matches.map { it.startkf }
                val totalStartKf = if (allStartKf.isNotEmpty() && allStartKf.all { it > 0 }) {
                    allStartKf.reduce { acc, kf -> acc * kf }
                } else {
                    allStartKf.firstOrNull() ?: 0.0
                }
                
                val newStsAll = when {
                    hasLosingMatch -> -1
                    allHaveScore && allWins -> 2
                    else -> 1
                }
                
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
            
            if (updatedExpStatuses.isNotEmpty()) {
                database.expDao().deleteAll()
                database.expDao().insertAll(
                    allExp.map { exp ->
                        updatedExpStatuses.find { it.id == exp.id } ?: exp
                    }
                )
            }
            
            calculateStatistics(expressResults, allStartKfs)
        }
    }
    
    private fun parseDateTime(ct: String): LocalDateTime {
        return try {
            val trimmed = ct.trim()
            
            val numericValue = trimmed.toDoubleOrNull()
            if (numericValue != null && numericValue > 40000) {
                val baseDate = LocalDateTime.of(1899, 12, 30, 0, 0)
                val days = numericValue.toLong()
                val fraction = numericValue - days
                val totalSeconds = (fraction * 24 * 60 * 60).toLong()
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                
                baseDate.plusDays(days)
                    .plusHours(hours)
                    .plusMinutes(minutes)
                    .plusSeconds(seconds)
            } else {
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
            }
        } catch (e: Exception) {
            LocalDateTime.now()
        }
    }
    
    private fun calculateStatistics(
        expressResults: List<ExpressResult>, 
        allStartKfs: List<Double>
    ): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        result["allExpresses"] = expressResults
        
        val totalExpress = expressResults.size
        val winExpress = expressResults.count { it.isWin }
        val loseExpress = totalExpress - winExpress
        val winRate = if (totalExpress > 0) (winExpress.toDouble() / totalExpress) * 100 else 0.0
        
        // ==================== ДЕТАЛЬНАЯ СТАТИСТИКА ====================
        val detailsBuilder = StringBuilder()
        detailsBuilder.appendLine("=== ОБЩАЯ СТАТИСТИКА ===")
        detailsBuilder.appendLine("Всего экспрессов: $totalExpress")
        detailsBuilder.appendLine("Выигрышных: $winExpress")
        detailsBuilder.appendLine("Проигрышных: $loseExpress")
        detailsBuilder.appendLine("Процент проходимости: ${String.format(Locale.US, "%.1f", winRate)}%")
        detailsBuilder.appendLine()
        
        // Статистика по типам ставок
        val allMatches = expressResults.flatMap { it.matches }
        val type924Matches = allMatches.filter { it.type == 924 }
        val type927Matches = allMatches.filter { it.type == 927 }
        val type928Matches = allMatches.filter { it.type == 928 }
        
        detailsBuilder.appendLine("=== ПО ТИПАМ СТАВОК (максимум) ===")
        
        val type924WinRate = if (type924Matches.isNotEmpty()) 
            (type924Matches.count { it.isWin }.toDouble() / type924Matches.size) * 100 else 0.0
        detailsBuilder.appendLine("Тип 924 (1X): ${type924Matches.size} матчей, " +
            "${type924Matches.count { it.isWin }} выигрышей, " +
            "${String.format(Locale.US, "%.1f", type924WinRate)}%")
        
        val type927WinRate = if (type927Matches.isNotEmpty()) 
            (type927Matches.count { it.isWin }.toDouble() / type927Matches.size) * 100 else 0.0
        detailsBuilder.appendLine("Тип 927 (Ф1 +1.5): ${type927Matches.size} матчей, " +
            "${type927Matches.count { it.isWin }} выигрышей, " +
            "${String.format(Locale.US, "%.1f", type927WinRate)}%")
        
        val type928WinRate = if (type928Matches.isNotEmpty()) 
            (type928Matches.count { it.isWin }.toDouble() / type928Matches.size) * 100 else 0.0
        detailsBuilder.appendLine("Тип 928 (Ф2 +1.5): ${type928Matches.size} матчей, " +
            "${type928Matches.count { it.isWin }} выигрышей, " +
            "${String.format(Locale.US, "%.1f", type928WinRate)}%")
        detailsBuilder.appendLine()
        
        // ==================== ГРАДАЦИЯ ПО startkf ====================
        val kfRangeStats = calculateKfRangeStats(allMatches, detailsBuilder)
        result["byKfRange"] = kfRangeStats
        
        // Статистика по экспрессам с разным количеством матчей
        val byMatchCount = expressResults.groupBy { it.matches.size }
        detailsBuilder.appendLine("=== ПО КОЛИЧЕСТВУ МАТЧЕЙ В ЭКСПРЕССЕ ===")
        byMatchCount.entries.sortedBy { it.key }.forEach { (count, expresses) ->
            val wins = expresses.count { it.isWin }
            val rate = if (expresses.isNotEmpty()) (wins.toDouble() / expresses.size) * 100 else 0.0
            detailsBuilder.appendLine("$count матчей: ${expresses.size} экспрессов, $wins выигрышей, ${String.format(Locale.US, "%.1f", rate)}%")
        }
        detailsBuilder.appendLine()
        
        result["total"] = AnalyticsSummary(
            totalExpress = totalExpress,
            winExpress = winExpress,
            loseExpress = loseExpress,
            winRate = winRate,
            details = detailsBuilder.toString()
        )
        
        // Статистика по дням недели
        val byDayOfWeek = expressResults.groupBy { it.dateTime.dayOfWeek }
        val dayStats = LinkedHashMap<DayOfWeek, Pair<Int, Int>>()
        for (day in DayOfWeek.entries) {
            val expresses = byDayOfWeek[day] ?: emptyList()
            dayStats[day] = Pair(expresses.count { it.isWin }, expresses.size)
        }
        result["byDayOfWeek"] = dayStats
        
        // Статистика по часам
        val byHour = expressResults.groupBy { it.dateTime.hour }
        val hourStats = TreeMap<Int, Pair<Int, Int>>()
        for (hour in 0..23) {
            val expresses = byHour[hour] ?: emptyList()
            hourStats[hour] = Pair(expresses.count { it.isWin }, expresses.size)
        }
        result["byHour"] = hourStats
        
        // Статистика по месяцам
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
        
        // Статистика по неделям
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
        
        // Статистика по лигам
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
        
        // Статистика по типам
        val typeStats = mutableMapOf<Int, Triple<Int, Int, Double>>()
        for (type in listOf(924, 927, 928)) {
            val typeMatches = allMatches.filter { it.type == type }
            val wins = typeMatches.count { it.isWin }
            val total = typeMatches.size
            val rate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
            typeStats[type] = Triple(wins, total, rate)
        }
        result["byType"] = typeStats
        
        return result
    }
    
    /**
     * Рассчитывает проходимость по градациям startkf.
     * Градация: 1.00-1.05, 1.05-1.10, 1.10-1.15, и т.д.
     * Шаг 0.05.
     */
    private fun calculateKfRangeStats(
        allMatches: List<MatchResult>,
        detailsBuilder: StringBuilder
    ): List<KfRangeStats> {
        // Определяем минимальный и максимальный startkf
        val validKfs = allMatches.filter { it.startkf > 0 }.map { it.startkf }
        if (validKfs.isEmpty()) {
            detailsBuilder.appendLine("=== ГРАДАЦИЯ ПО STARTKF === ")
            detailsBuilder.appendLine("Нет данных с коэффициентами")
            return emptyList()
        }
        
        val minKf = validKfs.minOrNull() ?: 1.0
        val maxKf = validKfs.maxOrNull() ?: 2.0
        
        // Округляем границы до шага 0.05
        val step = 0.05
        // Начинаем с 1.00 (минимальный логичный коэффициент)
        val startRange = (Math.floor(1.0 / step) * step)  // = 1.00
        val endRange = (Math.ceil(maxKf / step) * step)    // округляем вверх
        
        val kfRangeStatsList = mutableListOf<KfRangeStats>()
        
        detailsBuilder.appendLine("=== ГРАДАЦИЯ ПО STARTKF (шаг 0.05) ===")
        detailsBuilder.appendLine("Всего матчей с коэффициентами: ${validKfs.size}")
        detailsBuilder.appendLine()
        
        var currentMin = startRange
        while (currentMin < endRange) {
            val currentMax = currentMin + step
            
            // Фильтруем матчи, попадающие в диапазон (currentMin <= startkf < currentMax)
            // Последний диапазон включает верхнюю границу
            val rangeMatches = if (currentMax >= endRange - step / 2) {
                allMatches.filter { it.startkf >= currentMin && it.startkf <= currentMax }
            } else {
                allMatches.filter { it.startkf >= currentMin && it.startkf < currentMax }
            }
            
            val total = rangeMatches.size
            val wins = rangeMatches.count { it.isWin }
            val rate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
            
            val rangeLabel = String.format(Locale.US, "%.2f-%.2f", currentMin, currentMax)
            
            if (total > 0) {
                detailsBuilder.appendLine("$rangeLabel: $total матчей, $wins выигрышей, ${String.format(Locale.US, "%.1f", rate)}%")
            }
            
            kfRangeStatsList.add(
                KfRangeStats(
                    rangeLabel = rangeLabel,
                    minKf = currentMin,
                    maxKf = currentMax,
                    total = total,
                    wins = wins,
                    rate = rate
                )
            )
            
            currentMin = currentMax
        }
        
        // Дополнительно: статистика по типам внутри каждого диапазона
        detailsBuilder.appendLine()
        detailsBuilder.appendLine("=== ДЕТАЛИЗАЦИЯ ПО ТИПАМ В ДИАПАЗОНАХ ===")
        
        // Группируем по диапазонам и типам
        val rangeTypeMap = mutableMapOf<String, MutableMap<Int, Pair<Int, Int>>>()
        
        for (match in allMatches.filter { it.startkf > 0 }) {
            val rangeIndex = ((match.startkf - startRange) / step).toInt()
            val rangeMin = startRange + rangeIndex * step
            val rangeMax = rangeMin + step
            val rangeLabel = String.format(Locale.US, "%.2f-%.2f", rangeMin, rangeMax)
            
            rangeTypeMap.getOrPut(rangeLabel) { mutableMapOf() }
                .merge(match.type, Pair(if (match.isWin) 1 else 0, 1)) { old, new ->
                    Pair(old.first + new.first, old.second + new.second)
                }
        }
        
        rangeTypeMap.entries.sortedBy { it.key }.forEach { (range, typeMap) ->
            detailsBuilder.appendLine("$range:")
            typeMap.entries.sortedBy { it.key }.forEach { (type, stats) ->
                val (wins, total) = stats
                val rate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
                val typeName = when (type) {
                    924 -> "1X"
                    927 -> "Ф1(+1.5)"
                    928 -> "Ф2(+1.5)"
                    else -> "Тип $type"
                }
                detailsBuilder.appendLine("  $typeName: $total матчей, $wins выигрышей, ${String.format(Locale.US, "%.1f", rate)}%")
            }
        }
        
        return kfRangeStatsList
    }
}