// AnalyticsEngine.kt
package com.example.fonbetbot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.DayOfWeek
import java.util.*

data class MatchResult(
    val matchId: Long,
    val home: String,
    val away: String,
    val sh: Int,
    val sa: Int,
    val type: Int,
    val isWin: Boolean
)

data class ExpressResult(
    val expId: Int,
    val matches: List<MatchResult>,
    val isWin: Boolean,
    val dateTime: LocalDateTime,
    val isReplaced: Boolean
)

data class AnalyticsSummary(
    val totalExpress: Int,
    val winExpress: Int,
    val loseExpress: Int,
    val winRate: Double,
    val details: String
)

class AnalyticsEngine(private val database: AppDatabase) {
    
    suspend fun calculateAnalytics(): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            val allData = database.dataDao().getAllData()
            val allExp = database.expDao().getAllExp()
            
            // Группируем данные по id_exp
            val dataByExpId = allData.groupBy { it.id_exp }
            val expMap = allExp.associateBy { it.id_exp }
            
            // Анализируем каждый экспресс
            val expressResults = mutableListOf<ExpressResult>()
            val replacedExpIds = allExp.filter { it.id_exp_replace > 0 }
                .map { it.id_exp }
                .toSet()
            
            for ((expId, matches) in dataByExpId) {
                if (matches.size < 2) continue // Пропускаем неполные экспрессы
                
                val exp = expMap[expId] ?: continue
                
                val matchResults = matches.map { match ->
                    val isWin = when (match.type) {
                        924 -> match.sh >= match.sa  // Победа/тотал
                        927 -> match.sh + 1 > match.sa  // Фора 1 (+1.5)
                        else -> match.sh >= match.sa  // По умолчанию
                    }
                    
                    MatchResult(
                        matchId = match.m_id,
                        home = match.home,
                        away = match.away,
                        sh = match.sh,
                        sa = match.sa,
                        type = match.type,
                        isWin = isWin
                    )
                }
                
                val allWins = matchResults.all { it.isWin }
                val dateTime = parseDateTime(exp.ct)
                val isReplaced = expId in replacedExpIds
                
                expressResults.add(
                    ExpressResult(
                        expId = expId,
                        matches = matchResults,
                        isWin = allWins,
                        dateTime = dateTime,
                        isReplaced = isReplaced
                    )
                )
            }
            
            // Рассчитываем статистику
            calculateStatistics(expressResults)
        }
    }
    
    private fun parseDateTime(ct: String): LocalDateTime {
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            LocalDateTime.parse(ct, formatter)
        } catch (e: Exception) {
            try {
                // Пробуем с миллисекундами
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                LocalDateTime.parse(ct, formatter)
            } catch (e2: Exception) {
                try {
                    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    LocalDateTime.parse(ct, formatter)
                } catch (e3: Exception) {
                    LocalDateTime.now()
                }
            }
        }
    }
    
    private fun calculateStatistics(expressResults: List<ExpressResult>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        // Общая статистика
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
        
        // По дням недели
        val byDayOfWeek = expressResults.groupBy { it.dateTime.dayOfWeek }
        val dayStats = mutableMapOf<DayOfWeek, Pair<Int, Int>>()
        val dayNames = mapOf(
            DayOfWeek.MONDAY to "Понедельник",
            DayOfWeek.TUESDAY to "Вторник",
            DayOfWeek.WEDNESDAY to "Среда",
            DayOfWeek.THURSDAY to "Четверг",
            DayOfWeek.FRIDAY to "Пятница",
            DayOfWeek.SATURDAY to "Суббота",
            DayOfWeek.SUNDAY to "Воскресенье"
        )
        
        for ((day, expresses) in byDayOfWeek) {
            val wins = expresses.count { it.isWin }
            val total = expresses.size
            dayStats[day] = Pair(wins, total)
        }
        
        val dayDetails = buildString {
            appendLine("=== ПО ДНЯМ НЕДЕЛИ ===")
            DayOfWeek.entries.forEach { day ->
                val stats = dayStats[day]
                if (stats != null) {
                    val (wins, total) = stats
                    val rate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
                    appendLine("${dayNames[day] ?: day.name}: $wins/$total (${String.format(Locale.US, "%.1f", rate)}%)")
                }
            }
            appendLine()
        }
        
        result["byDayOfWeek"] = dayStats
        result["dayDetails"] = dayDetails
        
        // По часам
        val byHour = expressResults.groupBy { it.dateTime.hour }
        val hourStats = TreeMap<Int, Pair<Int, Int>>()
        
        for ((hour, expresses) in byHour) {
            val wins = expresses.count { it.isWin }
            val total = expresses.size
            hourStats[hour] = Pair(wins, total)
        }
        
        val hourDetails = buildString {
            appendLine("=== ПО ЧАСАМ ===")
            for (hour in 0..23) {
                val stats = hourStats[hour]
                if (stats != null) {
                    val (wins, total) = stats
                    val rate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
                    appendLine("${String.format("%02d", hour)}:00-${
                        String.format("%02d", (hour + 1) % 24)
                    }:00: $wins/$total (${String.format(Locale.US, "%.1f", rate)}%)")
                }
            }
            appendLine()
        }
        
        result["byHour"] = hourStats
        result["hourDetails"] = hourDetails
        
        // По месяцам
        val byMonth = expressResults.groupBy { it.dateTime.month }
        val monthStats = LinkedHashMap<String, Pair<Int, Int>>()
        val monthNames = mapOf(
            1 to "Январь", 2 to "Февраль", 3 to "Март", 4 to "Апрель",
            5 to "Май", 6 to "Июнь", 7 to "Июль", 8 to "Август",
            9 to "Сентябрь", 10 to "Октябрь", 11 to "Ноябрь", 12 to "Декабрь"
        )
        
        for ((month, expresses) in byMonth) {
            val wins = expresses.count { it.isWin }
            val total = expresses.size
            monthStats["${monthNames[month.value] ?: month.name} ${month.value}"] = Pair(wins, total)
        }
        
        val monthDetails = buildString {
            appendLine("=== ПО МЕСЯЦАМ ===")
            monthStats.forEach { (month, stats) ->
                val (wins, total) = stats
                val rate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
                appendLine("$month: $wins/$total (${String.format(Locale.US, "%.1f", rate)}%)")
            }
            appendLine()
        }
        
        result["byMonth"] = monthStats
        result["monthDetails"] = monthDetails
        
        // По типам ставок
        val byType = expressResults.flatMap { exp -> exp.matches.map { it.type } }
            .groupBy { it }
        val typeStats = mutableMapOf<Int, Int>()
        byType.forEach { (type, list) -> typeStats[type] = list.size }
        
        val typeDetails = buildString {
            appendLine("=== ПО ТИПАМ СТАВОК ===")
            typeStats.forEach { (type, count) ->
                val typeName = when (type) {
                    924 -> "Победа/Тотал"
                    927 -> "Фора 1 (+1.5)"
                    else -> "Тип $type"
                }
                appendLine("$typeName: $count ставок")
            }
            appendLine()
        }
        
        result["byType"] = typeStats
        result["typeDetails"] = typeDetails
        
        // Детальная информация
        val detailedInfo = buildString {
            appendLine("=== ДЕТАЛЬНАЯ ИНФОРМАЦИЯ ===")
            // Показываем только последние 50 для производительности
            expressResults.takeLast(50).forEach { express ->
                val status = if (express.isWin) "✓ ВЫИГРЫШ" else "✗ ПРОИГРЫШ"
                appendLine("Экспресс #${express.expId} - $status")
                appendLine("  Дата: ${express.dateTime.format(
                    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                )}")
                if (express.isReplaced) {
                    appendLine("  (Замененный)")
                }
                express.matches.forEach { match ->
                    val matchStatus = if (match.isWin) "✓" else "✗"
                    val typeName = when (match.type) {
                        924 -> "Тотал"
                        927 -> "Фора1"
                        else -> "Тип${match.type}"
                    }
                    appendLine("  $matchStatus ${match.home} vs ${match.away} (${match.sh}:${match.sa}) [$typeName]")
                }
                appendLine()
            }
        }
        
        result["detailedInfo"] = detailedInfo
        
        return result
    }
}