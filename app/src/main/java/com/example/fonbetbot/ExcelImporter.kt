// ExcelImporter.kt - ПОЛНЫЙ ИМПОРТЕР С АВТООПРЕДЕЛЕНИЕМ КОЛОНОК И ПАРСИНГОМ ДАТ
package com.example.fonbetbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.text.SimpleDateFormat
import java.util.*

class ExcelImporter(private val dbHelper: DatabaseHelper) {
    
    companion object {
        private const val TAG = "ExcelImporter"
    }
    
    data class ImportResult(
        val successCount: Int = 0,
        val errorCount: Int = 0,
        val errors: List<String> = emptyList()
    )
    
    /**
     * Парсинг CT поля - поддерживает:
     * 1. Unix timestamp в секундах (число > 1000000000)
     * 2. Дату в формате "yyyy-MM-dd HH:mm:ss"
     * 3. Дату в формате "dd.MM.yyyy HH:mm:ss"
     * 4. Дату в формате "yyyy-MM-dd"
     */
    private fun parseCT(value: String, numericValue: Double?): Long {
        // Если это Excel дата (число дней от 1900-01-01)
        if (numericValue != null && numericValue > 1000 && numericValue < 100000) {
            try {
                val calendar = Calendar.getInstance()
                calendar.set(1900, 0, 1)
                calendar.add(Calendar.DAY_OF_YEAR, numericValue.toInt() - 2)
                return calendar.timeInMillis / 1000
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Ошибка парсинга Excel даты: $numericValue")
            }
        }
        
        // Если это Unix timestamp (большое число)
        if (numericValue != null && numericValue > 1000000000) {
            return numericValue.toLong()
        }
        
        // Пробуем распарсить как строку с датой
        val cleanedValue = value.trim()
        if (cleanedValue.isEmpty()) {
            return System.currentTimeMillis() / 1000
        }
        
        val dateFormats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "dd.MM.yyyy HH:mm:ss",
            "dd.MM.yyyy HH:mm",
            "yyyy-MM-dd",
            "dd.MM.yyyy",
            "MM/dd/yyyy HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss"
        )
        
        for (format in dateFormats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                sdf.isLenient = false
                val date = sdf.parse(cleanedValue)
                if (date != null) {
                    val timestamp = date.time / 1000
                    Log.d(TAG, "🕐 CT распарсен: '$cleanedValue' (формат: $format) → timestamp: $timestamp")
                    return timestamp
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        // Если ничего не подошло
        Log.w(TAG, "⚠️ Не удалось распарсить CT: '$cleanedValue', используется текущее время")
        return System.currentTimeMillis() / 1000
    }
    
    /**
     * Импорт экспрессов из exp.xlsx
     * Колонки (определяются автоматически по заголовкам):
     * id_exp, kfall, profloss, balans, sumbet, sts_all, ct, strategy,
     * id_exp_replace, events_count, total_odds, bet_amount, potential_win,
     * balance, profit_loss, is_bet_placed
     */
    fun importExpresses(context: Context, uri: Uri): ImportResult {
        val db = dbHelper.writableDatabase
        var successCount = 0
        var updateCount = 0
        val errors = mutableListOf<String>()
        
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val workbook = XSSFWorkbook(inputStream)
                val sheet = workbook.getSheetAt(0)
                
                Log.d(TAG, "📥 ===== ИМПОРТ ЭКСПРЕССОВ =====")
                Log.d(TAG, "📥 Строк в файле: ${sheet.lastRowNum + 1}")
                
                // Читаем заголовок для определения колонок
                val headerRow = sheet.getRow(0)
                val columnMap = mutableMapOf<String, Int>()
                
                if (headerRow != null) {
                    for (i in 0..headerRow.lastCellNum) {
                        val header = getCellStringValue(headerRow, i).lowercase().trim()
                        if (header.isNotEmpty()) {
                            columnMap[header] = i
                        }
                    }
                    Log.d(TAG, "📋 Заголовки: $columnMap")
                }
                
                // Если нет заголовков, используем стандартные индексы
                val hasHeaders = columnMap.isNotEmpty()
                
                // Функция получения индекса колонки
                fun getCol(standardIndex: Int, vararg names: String): Int {
                    if (!hasHeaders) return standardIndex
                    for (name in names) {
                        columnMap[name.lowercase()]?.let { return it }
                    }
                    return standardIndex
                }
                
                // Определяем индексы всех колонок
                val colIdExp = getCol(0, "id_exp")
                val colKfall = getCol(1, "kfall", "kef")
                val colProfloss = getCol(2, "profloss", "profit_loss", "pnl")
                val colBalans = getCol(3, "balans", "balance", "баланс")
                val colSumbet = getCol(4, "sumbet", "bet_amount", "ставка")
                val colStsAll = getCol(5, "sts_all", "status", "статус")
                val colCt = getCol(6, "ct", "created_time", "created_at", "дата", "date")
                val colStrategy = getCol(7, "strategy", "стратегия")
                val colIdExpReplace = getCol(8, "id_exp_replace", "replace")
                val colEventsCount = getCol(9, "events_count", "events", "матчей")
                val colTotalOdds = getCol(10, "total_odds", "kfall")
                val colBetAmount = getCol(11, "bet_amount", "sumbet")
                val colPotentialWin = getCol(12, "potential_win", "выигрыш")
                val colBalance = getCol(13, "balance", "balans")
                val colProfitLoss = getCol(14, "profit_loss", "profloss")
                val colIsBetPlaced = getCol(15, "is_bet_placed", "placed")
                
                Log.d(TAG, "📊 Индексы колонок:")
                Log.d(TAG, "  id_exp=$colIdExp, kfall=$colKfall, sumbet=$colSumbet")
                Log.d(TAG, "  sts_all=$colStsAll, ct=$colCt, strategy=$colStrategy")
                
                // Пропускаем заголовок
                val startRow = if (hasHeaders) 1 else 0
                
                for (rowIndex in startRow..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    
                    try {
                        val idExp = getCellIntValue(row, colIdExp)
                        if (idExp <= 0) {
                            Log.w(TAG, "  ⏭ Строка $rowIndex: id_exp=$idExp, пропускаем")
                            continue
                        }
                        
                        // Парсим CT
                        val ctCell = row.getCell(colCt)
                        val ctString = getCellStringValue(row, colCt)
                        val ctNumeric = if (ctCell?.cellType == CellType.NUMERIC) ctCell.numericCellValue else null
                        val ct = parseCT(ctString, ctNumeric)
                        
                        val kfall = getCellDoubleValue(row, colKfall) ?: 1.0
                        val profLoss = getCellDoubleValue(row, colProfloss) ?: 0.0
                        val balans = getCellDoubleValue(row, colBalans) ?: 0.0
                        val sumbet = getCellDoubleValue(row, colSumbet) ?: 0.0
                        val stsAll = getCellIntValue(row, colStsAll)
                        val strategy = getCellStringValue(row, colStrategy)
                        val idExpReplace = getCellIntValue(row, colIdExpReplace)
                        val eventsCount = getCellIntValue(row, colEventsCount)
                        val totalOdds = getCellDoubleValue(row, colTotalOdds) ?: kfall
                        val betAmount = getCellDoubleValue(row, colBetAmount) ?: sumbet
                        val potentialWin = getCellDoubleValue(row, colPotentialWin) ?: (sumbet * kfall)
                        val balance = getCellDoubleValue(row, colBalance) ?: balans
                        val profitLoss = getCellDoubleValue(row, colProfitLoss) ?: profLoss
                        val isBetPlaced = getCellIntValue(row, colIsBetPlaced)
                        
                        Log.d(TAG, "  📊 Строка $rowIndex: id_exp=$idExp, ct=$ctString → ${ct}sec, kfall=$kfall, sumbet=$sumbet, sts=$stsAll, strategy='$strategy'")
                        
                        val values = ContentValues().apply {
                            put("id_exp", idExp)
                            put("kfall", kfall)
                            put("profloss", profLoss)
                            put("balans", balans)
                            put("sumbet", sumbet)
                            put("sts_all", stsAll)
                            put("ct", ct)
                            if (strategy.isNotEmpty()) put("strategy", strategy)
                            put("id_exp_replace", idExpReplace)
                            put("events_count", eventsCount)
                            put("total_odds", totalOdds)
                            put("bet_amount", betAmount)
                            put("potential_win", potentialWin)
                            put("balance", balance)
                            put("profit_loss", profitLoss)
                            put("is_bet_placed", isBetPlaced)
                            put("created_at", ct)
                            put("created_time", ct)
                            put("updated_at", System.currentTimeMillis() / 1000)
                        }
                        
                        // Вставляем или обновляем
                        val existingId = findExpressIdByIdExp(db, idExp)
                        if (existingId != null) {
                            db.update("express_bets", values, "id = ?", arrayOf(existingId.toString()))
                            updateCount++
                            
                            // Связываем матчи с экспрессом
                            val eventValues = ContentValues().apply {
                                put("express_id", existingId)
                            }
                            db.update("express_events", eventValues, "id_exp = ? AND express_id = 0", arrayOf(idExp.toString()))
                            
                            Log.d(TAG, "    🔄 Обновлён экспресс #$idExp (db_id=$existingId)")
                        } else {
                            val newId = db.insert("express_bets", null, values)
                            if (newId != -1L) {
                                successCount++
                                
                                // Связываем матчи с новым экспрессом
                                val eventValues = ContentValues().apply {
                                    put("express_id", newId)
                                }
                                val updatedEvents = db.update("express_events", eventValues, "id_exp = ?", arrayOf(idExp.toString()))
                                
                                Log.d(TAG, "    ✅ Добавлен экспресс #$idExp (new_db_id=$newId, связано матчей: $updatedEvents)")
                            } else {
                                errors.add("Строка $rowIndex: не удалось вставить экспресс #$idExp")
                                Log.e(TAG, "    ❌ Ошибка вставки экспресса #$idExp")
                            }
                        }
                        
                    } catch (e: Exception) {
                        val error = "Строка $rowIndex: ${e.message}"
                        errors.add(error)
                        Log.e(TAG, "  ❌ $error", e)
                    }
                }
                
                workbook.close()
            }
        } catch (e: Exception) {
            val error = "Ошибка открытия файла: ${e.message}"
            errors.add(error)
            Log.e(TAG, "❌ $error", e)
        }
        
        Log.d(TAG, "📥 ===== ИМПОРТ ЗАВЕРШЁН =====")
        Log.d(TAG, "📥 Добавлено: $successCount, обновлено: $updateCount, ошибок: ${errors.size}")
        
        return ImportResult(successCount + updateCount, errors.size, errors)
    }
    
    /**
     * Импорт матчей из data.xlsx
     * Колонки (определяются автоматически по заголовкам):
     * id_exp, m_id, id_liga, league_name, id_home, home_team, id_away, away_team,
     * start_odds, current_odds, match_time, match_start_time, expected_end_time,
     * sport_type, home_score, away_score, bet_type, status, is_finalized,
     * match_url, uzh, total_type
     */
    fun importMatches(context: Context, uri: Uri): ImportResult {
        val db = dbHelper.writableDatabase
        var successCount = 0
        var updateCount = 0
        val errors = mutableListOf<String>()
        
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val workbook = XSSFWorkbook(inputStream)
                val sheet = workbook.getSheetAt(0)
                
                Log.d(TAG, "📥 ===== ИМПОРТ МАТЧЕЙ =====")
                Log.d(TAG, "📥 Строк в файле: ${sheet.lastRowNum + 1}")
                
                // Читаем заголовок
                val headerRow = sheet.getRow(0)
                val columnMap = mutableMapOf<String, Int>()
                
                if (headerRow != null) {
                    for (i in 0..headerRow.lastCellNum) {
                        val header = getCellStringValue(headerRow, i).lowercase().trim()
                        if (header.isNotEmpty()) {
                            columnMap[header] = i
                        }
                    }
                    Log.d(TAG, "📋 Заголовки: $columnMap")
                }
                
                val hasHeaders = columnMap.isNotEmpty()
                
                fun getCol(standardIndex: Int, vararg names: String): Int {
                    if (!hasHeaders) return standardIndex
                    for (name in names) {
                        columnMap[name.lowercase()]?.let { return it }
                    }
                    return standardIndex
                }
                
                // Определяем индексы колонок
                val colIdExp = getCol(0, "id_exp")
                val colMId = getCol(1, "m_id", "match_id", "матч")
                val colIdLiga = getCol(2, "id_liga", "league_id")
                val colLeagueName = getCol(3, "league_name", "лига", "liga")
                val colIdHome = getCol(4, "id_home", "comand1id", "home_id")
                val colHomeTeam = getCol(5, "home_team", "home", "хозяева")
                val colIdAway = getCol(6, "id_away", "comand2id", "away_id")
                val colAwayTeam = getCol(7, "away_team", "away", "гости")
                val colStartOdds = getCol(8, "start_odds", "startkf", "начальный_кэф")
                val colCurrentOdds = getCol(9, "current_odds", "lastkf", "текущий_кэф")
                val colMatchTime = getCol(10, "match_time", "время", "time")
                val colMatchStartTime = getCol(11, "match_start_time")
                val colExpectedEndTime = getCol(12, "expected_end_time")
                val colSportType = getCol(13, "sport_type", "спорт", "sport")
                val colHomeScore = getCol(14, "home_score", "sh", "голы_хозяев")
                val colAwayScore = getCol(15, "away_score", "sa", "голы_гостей")
                val colBetType = getCol(16, "bet_type", "type", "тип")
                val colStatus = getCol(17, "status", "sts", "статус")
                val colIsFinalized = getCol(18, "is_finalized", "завершён")
                val colMatchUrl = getCol(19, "match_url", "url")
                val colUzh = getCol(20, "uzh")
                val colTotalType = getCol(21, "total_type", "tbtype")
                
                val startRow = if (hasHeaders) 1 else 0
                
                for (rowIndex in startRow..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    
                    try {
                        val idExp = getCellIntValue(row, colIdExp)
                        val mId = getCellIntValue(row, colMId)
                        
                        if (idExp <= 0 || mId <= 0) {
                            Log.w(TAG, "  ⏭ Строка $rowIndex: id_exp=$idExp, m_id=$mId, пропускаем")
                            continue
                        }
                        
                        // Находим express_id
                        val expressId = findExpressIdByIdExp(db, idExp)
                        
                        val values = ContentValues().apply {
                            put("id_exp", idExp)
                            put("express_id", expressId ?: 0)
                            put("m_id", mId)
                            
                            val idLiga = getCellLongValue(row, colIdLiga)
                            idLiga?.let { put("id_liga", it) }
                            
                            val leagueName = getCellStringValue(row, colLeagueName)
                            if (leagueName.isNotEmpty()) put("league_name", leagueName)
                            
                            val idHome = getCellLongValue(row, colIdHome)
                            idHome?.let { put("id_home", it) }
                            
                            val homeTeam = getCellStringValue(row, colHomeTeam)
                            if (homeTeam.isNotEmpty()) put("home_team", homeTeam)
                            
                            val idAway = getCellLongValue(row, colIdAway)
                            idAway?.let { put("id_away", it) }
                            
                            val awayTeam = getCellStringValue(row, colAwayTeam)
                            if (awayTeam.isNotEmpty()) put("away_team", awayTeam)
                            
                            put("start_odds", getCellDoubleValue(row, colStartOdds) ?: 1.0)
                            
                            val currentOdds = getCellDoubleValue(row, colCurrentOdds)
                            currentOdds?.let { put("current_odds", it) }
                            
                            put("match_time", getCellIntValue(row, colMatchTime))
                            put("match_start_time", getCellIntValue(row, colMatchStartTime))
                            put("expected_end_time", getCellLongValue(row, colExpectedEndTime) ?: 0L)
                            
                            val sportType = getCellStringValue(row, colSportType)
                            put("sport_type", sportType.ifEmpty { "football" })
                            
                            put("home_score", getCellIntValue(row, colHomeScore))
                            put("away_score", getCellIntValue(row, colAwayScore))
                            put("bet_type", getCellIntValue(row, colBetType))
                            put("status", getCellIntValue(row, colStatus))
                            put("is_finalized", getCellIntValue(row, colIsFinalized))
                            
                            val matchUrl = getCellStringValue(row, colMatchUrl)
                            if (matchUrl.isNotEmpty()) put("match_url", matchUrl)
                            
                            val uzh = getCellStringValue(row, colUzh)
                            if (uzh.isNotEmpty()) put("uzh", uzh)
                            
                            val totalType = getCellLongValue(row, colTotalType)
                            totalType?.let { put("total_type", it) }
                            
                            put("created_at", System.currentTimeMillis() / 1000)
                            put("updated_at", System.currentTimeMillis() / 1000)
                        }
                        
                        // Вставляем или обновляем
                        val existingEventId = findEventIdByMId(db, mId)
                        if (existingEventId != null) {
                            db.update("express_events", values, "id = ?", arrayOf(existingEventId.toString()))
                            updateCount++
                            Log.d(TAG, "  🔄 Обновлён матч #$mId (express_id=$expressId)")
                        } else {
                            val newId = db.insert("express_events", null, values)
                            if (newId != -1L) {
                                successCount++
                                Log.d(TAG, "  ✅ Добавлен матч #$mId (express_id=$expressId)")
                            } else {
                                errors.add("Строка $rowIndex: не удалось вставить матч #$mId")
                            }
                        }
                        
                    } catch (e: Exception) {
                        val error = "Строка $rowIndex: ${e.message}"
                        errors.add(error)
                        Log.e(TAG, "  ❌ $error", e)
                    }
                }
                
                workbook.close()
            }
        } catch (e: Exception) {
            val error = "Ошибка открытия файла: ${e.message}"
            errors.add(error)
            Log.e(TAG, "❌ $error", e)
        }
        
        Log.d(TAG, "📥 ===== ИМПОРТ МАТЧЕЙ ЗАВЕРШЁН =====")
        Log.d(TAG, "📥 Добавлено: $successCount, обновлено: $updateCount, ошибок: ${errors.size}")
        
        return ImportResult(successCount + updateCount, errors.size, errors)
    }
    
    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ БД ====================
    
    private fun findExpressIdByIdExp(db: SQLiteDatabase, idExp: Int): Long? {
        val cursor = db.query(
            "express_bets",
            arrayOf("id"),
            "id_exp = ?",
            arrayOf(idExp.toString()),
            null, null, null
        )
        cursor.use {
            return if (it.moveToFirst()) it.getLong(0) else null
        }
    }
    
    private fun findEventIdByMId(db: SQLiteDatabase, mId: Int): Long? {
        val cursor = db.query(
            "express_events",
            arrayOf("id"),
            "m_id = ?",
            arrayOf(mId.toString()),
            null, null, null
        )
        cursor.use {
            return if (it.moveToFirst()) it.getLong(0) else null
        }
    }
    
    // ==================== МЕТОДЫ ЧТЕНИЯ ЯЧЕЕК ====================
    
    private fun getCellStringValue(row: Row, columnIndex: Int): String {
        val cell = row.getCell(columnIndex) ?: return ""
        
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.NUMERIC -> {
                val value = cell.numericCellValue
                if (value == Math.floor(value) && !value.isInfinite()) {
                    value.toLong().toString()
                } else {
                    String.format("%.6f", value).trimEnd('0').trimEnd('.')
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    cell.stringCellValue.trim()
                } catch (e: Exception) {
                    try {
                        val value = cell.numericCellValue
                        if (value == Math.floor(value) && !value.isInfinite()) {
                            value.toLong().toString()
                        } else {
                            String.format("%.6f", value).trimEnd('0').trimEnd('.')
                        }
                    } catch (e2: Exception) {
                        ""
                    }
                }
            }
            CellType.BLANK -> ""
            else -> ""
        }
    }
    
    private fun getCellIntValue(row: Row, columnIndex: Int): Int {
        val cell = row.getCell(columnIndex) ?: return 0
        
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue.toInt()
            CellType.STRING -> {
                cell.stringCellValue.trim().replace(",", ".").toDoubleOrNull()?.toInt() ?: 0
            }
            CellType.FORMULA -> {
                try {
                    cell.numericCellValue.toInt()
                } catch (e: Exception) {
                    try {
                        cell.stringCellValue.trim().replace(",", ".").toDoubleOrNull()?.toInt() ?: 0
                    } catch (e2: Exception) {
                        0
                    }
                }
            }
            CellType.BOOLEAN -> if (cell.booleanCellValue) 1 else 0
            else -> 0
        }
    }
    
    private fun getCellLongValue(row: Row, columnIndex: Int): Long? {
        val cell = row.getCell(columnIndex) ?: return null
        
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue.toLong()
            CellType.STRING -> {
                val str = cell.stringCellValue.trim()
                str.toLongOrNull()
            }
            CellType.FORMULA -> {
                try {
                    cell.numericCellValue.toLong()
                } catch (e: Exception) {
                    try {
                        cell.stringCellValue.trim().toLongOrNull()
                    } catch (e2: Exception) {
                        null
                    }
                }
            }
            CellType.BLANK -> null
            else -> null
        }
    }
    
    private fun getCellDoubleValue(row: Row, columnIndex: Int): Double? {
        val cell = row.getCell(columnIndex) ?: return null
        
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> {
                val str = cell.stringCellValue.trim().replace(",", ".")
                str.toDoubleOrNull()
            }
            CellType.FORMULA -> {
                try {
                    cell.numericCellValue
                } catch (e: Exception) {
                    try {
                        cell.stringCellValue.trim().replace(",", ".").toDoubleOrNull()
                    } catch (e2: Exception) {
                        null
                    }
                }
            }
            CellType.BLANK -> null
            else -> null
        }
    }
}