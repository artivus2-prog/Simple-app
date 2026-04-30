// ExcelImporter.kt - ПОЛНЫЙ ИМПОРТЕР С ПРАВИЛЬНОЙ СВЯЗЬЮ ТАБЛИЦ
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
     * Импорт матчей из data.xlsx
     * Колонки: id_exp, m_id, id_liga, league_name, id_home, home_team, id_away, away_team,
     *          start_odds, current_odds, match_time, match_start_time, expected_end_time,
     *          sport_type, home_score, away_score, bet_type, status, is_finalized,
     *          match_url, uzh, total_type
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
                
                Log.d(TAG, "📥 Импорт матчей: строк в файле = ${sheet.lastRowNum + 1}")
                
                // Пропускаем заголовок (строка 0)
                for (rowIndex in 1..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    
                    try {
                        val idExp = getCellIntValue(row, 0)
                        val mId = getCellIntValue(row, 1)
                        
                        if (idExp <= 0 || mId <= 0) {
                            Log.w(TAG, "Строка $rowIndex: пустой id_exp или m_id, пропускаем")
                            continue
                        }
                        
                        val idLiga = getCellLongValue(row, 2)
                        val leagueName = getCellStringValue(row, 3)
                        val idHome = getCellLongValue(row, 4)
                        val homeTeam = getCellStringValue(row, 5)
                        val idAway = getCellLongValue(row, 6)
                        val awayTeam = getCellStringValue(row, 7)
                        val startOdds = getCellDoubleValue(row, 8)
                        val currentOdds = getCellDoubleValue(row, 9)
                        val matchTime = getCellIntValue(row, 10)
                        val matchStartTime = getCellIntValue(row, 11)
                        val expectedEndTime = getCellLongValue(row, 12) ?: 0L
                        val sportType = getCellStringValue(row, 13).ifEmpty { "football" }
                        val homeScore = getCellIntValue(row, 14)
                        val awayScore = getCellIntValue(row, 15)
                        val betType = getCellIntValue(row, 16)
                        val status = getCellIntValue(row, 17)
                        val isFinalized = getCellIntValue(row, 18)
                        val matchUrl = getCellStringValue(row, 19)
                        val uzh = getCellStringValue(row, 20)
                        val totalType = getCellLongValue(row, 21)
                        
                        // Находим express_id по id_exp
                        val expressId = findExpressIdByIdExp(db, idExp)
                        
                        val values = ContentValues().apply {
                            put("id_exp", idExp)
                            put("express_id", expressId ?: 0) // Связь с express_bets
                            put("m_id", mId)
                            idLiga?.let { put("id_liga", it) }
                            if (leagueName.isNotEmpty()) put("league_name", leagueName)
                            idHome?.let { put("id_home", it) }
                            if (homeTeam.isNotEmpty()) put("home_team", homeTeam)
                            idAway?.let { put("id_away", it) }
                            if (awayTeam.isNotEmpty()) put("away_team", awayTeam)
                            put("start_odds", startOdds)
                            currentOdds?.let { put("current_odds", it) }
                            put("match_time", matchTime)
                            put("match_start_time", matchStartTime)
                            put("expected_end_time", expectedEndTime)
                            put("sport_type", sportType)
                            put("home_score", homeScore)
                            put("away_score", awayScore)
                            put("bet_type", betType)
                            put("status", status)
                            put("is_finalized", isFinalized)
                            if (matchUrl.isNotEmpty()) put("match_url", matchUrl)
                            if (uzh.isNotEmpty()) put("uzh", uzh)
                            totalType?.let { put("total_type", it) }
                            put("created_at", System.currentTimeMillis() / 1000)
                            put("updated_at", System.currentTimeMillis() / 1000)
                        }
                        
                        // Вставляем или обновляем по m_id (уникальный ключ)
                        val existingEventId = findEventIdByMId(db, mId)
                        if (existingEventId != null) {
                            db.update("express_events", values, "id = ?", arrayOf(existingEventId.toString()))
                            updateCount++
                            Log.d(TAG, "  🔄 Обновлён матч #$mId (event_id=$existingEventId, express_id=$expressId)")
                        } else {
                            val newId = db.insert("express_events", null, values)
                            if (newId != -1L) {
                                successCount++
                                Log.d(TAG, "  ✅ Добавлен матч #$mId (event_id=$newId, express_id=$expressId)")
                            } else {
                                errors.add("Строка $rowIndex: не удалось вставить матч #$mId")
                            }
                        }
                        
                    } catch (e: Exception) {
                        val error = "Строка $rowIndex: ${e.message}"
                        errors.add(error)
                        Log.e(TAG, error, e)
                    }
                }
                
                workbook.close()
            }
        } catch (e: Exception) {
            val error = "Ошибка открытия файла: ${e.message}"
            errors.add(error)
            Log.e(TAG, error, e)
        }
        
        Log.d(TAG, "📥 Импорт матчей завершён: добавлено $successCount, обновлено $updateCount, ошибок ${errors.size}")
        return ImportResult(successCount + updateCount, errors.size, errors)
    }
    
    /**
     * Импорт экспрессов из exp.xlsx
     * Колонки: id_exp, kfall, profloss, balans, sumbet, sts_all, ct, strategy,
     *          id_exp_replace, events_count, total_odds, bet_amount, potential_win,
     *          balance, profit_loss, is_bet_placed
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
                
                Log.d(TAG, "📥 Импорт экспрессов: строк в файле = ${sheet.lastRowNum + 1}")
                
                // Пропускаем заголовок (строка 0)
                for (rowIndex in 1..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    
                    try {
                        val idExp = getCellIntValue(row, 0)
                        
                        if (idExp <= 0) {
                            Log.w(TAG, "Строка $rowIndex: пустой id_exp, пропускаем")
                            continue
                        }
                        
                        val kfall = getCellDoubleValue(row, 1) ?: 1.0
                        val profLoss = getCellDoubleValue(row, 2) ?: 0.0
                        val balans = getCellDoubleValue(row, 3) ?: 0.0
                        val sumbet = getCellDoubleValue(row, 4) ?: 0.0
                        val stsAll = getCellIntValue(row, 5)
                        val ct = getCellLongValue(row, 6) ?: (System.currentTimeMillis() / 1000)
                        val strategy = getCellStringValue(row, 7)
                        val idExpReplace = getCellIntValue(row, 8)
                        val eventsCount = getCellIntValue(row, 9)
                        val totalOdds = getCellDoubleValue(row, 10) ?: kfall
                        val betAmount = getCellDoubleValue(row, 11) ?: sumbet
                        val potentialWin = getCellDoubleValue(row, 12) ?: (sumbet * kfall)
                        val balance = getCellDoubleValue(row, 13) ?: balans
                        val profitLoss = getCellDoubleValue(row, 14) ?: profLoss
                        val isBetPlaced = getCellIntValue(row, 15)
                        
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
                        
                        // Вставляем или обновляем по id_exp (уникальный ключ)
                        val existingExpressId = findExpressIdByIdExp(db, idExp)
                        if (existingExpressId != null) {
                            db.update("express_bets", values, "id = ?", arrayOf(existingExpressId.toString()))
                            updateCount++
                            
                            // Обновляем express_id в express_events
                            val eventValues = ContentValues().apply {
                                put("express_id", existingExpressId)
                            }
                            db.update("express_events", eventValues, "id_exp = ? AND express_id = 0", arrayOf(idExp.toString()))
                            
                            Log.d(TAG, "  🔄 Обновлён экспресс #$idExp (db_id=$existingExpressId, ct=$ct)")
                        } else {
                            val newId = db.insert("express_bets", null, values)
                            if (newId != -1L) {
                                successCount++
                                
                                // Обновляем express_id в express_events для этого id_exp
                                val eventValues = ContentValues().apply {
                                    put("express_id", newId)
                                }
                                val updatedEvents = db.update("express_events", eventValues, "id_exp = ?", arrayOf(idExp.toString()))
                                
                                Log.d(TAG, "  ✅ Добавлен экспресс #$idExp (db_id=$newId, ct=$ct, обновлено матчей: $updatedEvents)")
                            } else {
                                errors.add("Строка $rowIndex: не удалось вставить экспресс #$idExp")
                            }
                        }
                        
                    } catch (e: Exception) {
                        val error = "Строка $rowIndex: ${e.message}"
                        errors.add(error)
                        Log.e(TAG, error, e)
                    }
                }
                
                workbook.close()
            }
        } catch (e: Exception) {
            val error = "Ошибка открытия файла: ${e.message}"
            errors.add(error)
            Log.e(TAG, error, e)
        }
        
        Log.d(TAG, "📥 Импорт экспрессов завершён: добавлено $successCount, обновлено $updateCount, ошибок ${errors.size}")
        return ImportResult(successCount + updateCount, errors.size, errors)
    }
    
    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    
    /**
     * Находит express_id в таблице express_bets по полю id_exp
     */
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
    
    /**
     * Находит id записи в таблице express_events по m_id
     */
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
                    value.toString()
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    cell.stringCellValue.trim()
                } catch (e: Exception) {
                    try {
                        cell.numericCellValue.toString()
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
            CellType.STRING -> cell.stringCellValue.trim().toIntOrNull() ?: 0
            CellType.FORMULA -> {
                try {
                    cell.numericCellValue.toInt()
                } catch (e: Exception) {
                    try {
                        cell.stringCellValue.trim().toIntOrNull() ?: 0
                    } catch (e2: Exception) {
                        0
                    }
                }
            }
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