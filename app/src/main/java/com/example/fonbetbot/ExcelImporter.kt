// ExcelImporter.kt
package com.example.fonbetbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class ExcelImporter(private val dbHelper: DatabaseHelper) {
    
    companion object {
        private const val TAG = "ExcelImporter"
    }
    
    data class ImportResult(
        val successCount: Int,
        val errorCount: Int,
        val errors: List<String>
    )
    
    /**
     * Импорт матчей из XLSX файла (data)
     */
    fun importMatches(context: Context, uri: Uri): ImportResult {
        val successCount = mutableListOf<Int>()
        val errors = mutableListOf<String>()
        
        try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return ImportResult(0, 0, listOf("Не удалось открыть файл"))
            
            val workbook = XSSFWorkbook(inputStream)
            val sheet = workbook.getSheetAt(0)
            
            val db = dbHelper.writableDatabase
            
            db.beginTransaction()
            try {
                // Пропускаем заголовок (строка 0)
                for (rowIndex in 1..sheet.lastRowNum) {
                    try {
                        val row = sheet.getRow(rowIndex) ?: continue
                        
                        val matchData = parseMatchRow(row) ?: continue
                        
                        // Вставляем или обновляем матч
                        val eventValues = ContentValues().apply {
                            put("id_exp", matchData.idExp)
                            put("m_id", matchData.mId)
                            matchData.idLiga?.let { put("id_liga", it) }
                            put("league_name", matchData.ligaName ?: "")
                            matchData.idHome?.let { put("id_home", it) }
                            put("home_team", matchData.home ?: "")
                            matchData.idAway?.let { put("id_away", it) }
                            put("away_team", matchData.away ?: "")
                            put("start_odds", matchData.startKf)
                            put("current_odds", matchData.lastKf)
                            put("match_time", parseTimeToMinutes(matchData.curTime))
                            put("home_score", matchData.sh)
                            put("away_score", matchData.sa)
                            put("bet_type", matchData.type)
                            put("status", matchData.sts)
                            put("is_finalized", if (matchData.sts in listOf(1, 2)) 1 else 0)
                            if (matchData.url != null) put("match_url", matchData.url)
                            put("uzh", matchData.uzh?.toString() ?: "0")
                            matchData.tbType?.let { put("total_type", it) }
                            put("created_at", System.currentTimeMillis() / 1000)
                            put("updated_at", System.currentTimeMillis() / 1000)
                        }
                        
                        // Пробуем вставить, если конфликт по m_id - обновляем
                        val existingCursor = db.query(
                            "express_events",
                            arrayOf("id", "express_id"),
                            "m_id = ?",
                            arrayOf(matchData.mId.toString()),
                            null, null, null
                        )
                        
                        if (existingCursor.moveToFirst()) {
                            val eventId = existingCursor.getLong(0)
                            val expressId = existingCursor.getLong(1)
                            existingCursor.close()
                            
                            eventValues.put("express_id", expressId)
                            db.update("express_events", eventValues, "m_id = ?", 
                                arrayOf(matchData.mId.toString()))
                        } else {
                            existingCursor.close()
                            
                            // Проверяем существование экспресса
                            val expressCursor = db.query(
                                "express_bets",
                                arrayOf("id"),
                                "id_exp = ?",
                                arrayOf(matchData.idExp.toString()),
                                null, null, null
                            )
                            
                            val expressId = if (expressCursor.moveToFirst()) {
                                expressCursor.getLong(0)
                            } else {
                                // Создаём экспресс если не существует
                                val expressValues = ContentValues().apply {
                                    put("id_exp", matchData.idExp)
                                    put("kfall", matchData.startKf)
                                    put("sumbet", 30.0)
                                    put("sts_all", if (matchData.sts in listOf(1, 2)) matchData.sts else 0)
                                    put("ct", System.currentTimeMillis() / 1000)
                                    put("strategy", "imported")
                                    put("created_at", System.currentTimeMillis() / 1000)
                                    put("updated_at", System.currentTimeMillis() / 1000)
                                }
                                db.insert("express_bets", null, expressValues)
                            }
                            expressCursor.close()
                            
                            eventValues.put("express_id", expressId)
                            db.insertWithOnConflict(
                                "express_events", null, eventValues,
                                SQLiteDatabase.CONFLICT_REPLACE
                            )
                        }
                        
                        successCount.add(rowIndex)
                        
                    } catch (e: Exception) {
                        errors.add("Строка $rowIndex: ${e.message}")
                        Log.e(TAG, "Ошибка в строке $rowIndex: ${e.message}")
                    }
                }
                
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            
            workbook.close()
            inputStream.close()
            
            Log.d(TAG, "✅ Импортировано ${successCount.size} матчей, ошибок: ${errors.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка импорта: ${e.message}", e)
            return ImportResult(0, 0, listOf("Ошибка: ${e.message}"))
        }
        
        return ImportResult(
            successCount = successCount.size,
            errorCount = errors.size,
            errors = errors
        )
    }
    
    /**
     * Импорт экспрессов из XLSX файла (exp)
     */
    fun importExpresses(context: Context, uri: Uri): ImportResult {
        val successCount = mutableListOf<Int>()
        val errors = mutableListOf<String>()
        
        try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return ImportResult(0, 0, listOf("Не удалось открыть файл"))
            
            val workbook = XSSFWorkbook(inputStream)
            val sheet = workbook.getSheetAt(0)
            
            val db = dbHelper.writableDatabase
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            
            db.beginTransaction()
            try {
                for (rowIndex in 1..sheet.lastRowNum) {
                    try {
                        val row = sheet.getRow(rowIndex) ?: continue
                        
                        val expressData = parseExpressRow(row, dateFormat) ?: continue
                        
                        val values = ContentValues().apply {
                            put("id_exp", expressData.idExp)
                            put("kfall", expressData.kfall)
                            put("sts_all", expressData.stsAll)
                            put("profloss", expressData.profLoss)
                            put("balans", expressData.balans)
                            put("sumbet", expressData.sumBet)
                            put("strategy", expressData.strategy ?: "")
                            put("id_exp_replace", expressData.idExpReplace)
                            put("is_bet_placed", 1)
                            put("created_at", System.currentTimeMillis() / 1000)
                            put("updated_at", System.currentTimeMillis() / 1000)
                            
                            // Парсим дату если есть
                            if (expressData.ct != null) {
                                try {
                                    val date = dateFormat.parse(expressData.ct)
                                    put("ct", date.time / 1000)
                                } catch (e: Exception) {
                                    put("ct", System.currentTimeMillis() / 1000)
                                }
                            } else {
                                put("ct", System.currentTimeMillis() / 1000)
                            }
                        }
                        
                        // Проверяем существование
                        val cursor = db.query(
                            "express_bets",
                            arrayOf("id"),
                            "id_exp = ?",
                            arrayOf(expressData.idExp.toString()),
                            null, null, null
                        )
                        
                        if (cursor.moveToFirst()) {
                            db.update("express_bets", values, "id_exp = ?", 
                                arrayOf(expressData.idExp.toString()))
                        } else {
                            db.insert("express_bets", null, values)
                        }
                        cursor.close()
                        
                        successCount.add(rowIndex)
                        
                    } catch (e: Exception) {
                        errors.add("Строка $rowIndex: ${e.message}")
                        Log.e(TAG, "Ошибка в строке $rowIndex: ${e.message}")
                    }
                }
                
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            
            workbook.close()
            inputStream.close()
            
            Log.d(TAG, "✅ Импортировано ${successCount.size} экспрессов, ошибок: ${errors.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка импорта: ${e.message}", e)
            return ImportResult(0, 0, listOf("Ошибка: ${e.message}"))
        }
        
        return ImportResult(
            successCount = successCount.size,
            errorCount = errors.size,
            errors = errors
        )
    }
    
    // ==================== ПАРСИНГ СТРОК ====================
    
    private fun parseMatchRow(row: Row): MatchImportData? {
        return try {
            MatchImportData(
                id = getCellInt(row, 0),
                idExp = getCellInt(row, 2) ?: return null,
                mId = getCellInt(row, 3) ?: return null,
                idLiga = getCellInt(row, 4),
                ligaName = getCellString(row, 5),
                idHome = getCellInt(row, 6),
                home = getCellString(row, 7),
                idAway = getCellInt(row, 8),
                away = getCellString(row, 9),
                startKf = getCellDouble(row, 10) ?: 1.0,
                lastKf = getCellDouble(row, 11) ?: 1.0,
                curTime = getCellString(row, 12) ?: "0",
                sh = getCellInt(row, 13) ?: 0,
                sa = getCellInt(row, 14) ?: 0,
                type = getCellInt(row, 15) ?: 924,
                sts = getCellInt(row, 16) ?: 0,
                url = getCellString(row, 17),
                uzh = getCellDouble(row, 18),
                tbType = getCellInt(row, 19)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга строки матча: ${e.message}")
            null
        }
    }
    
    private fun parseExpressRow(row: Row, dateFormat: SimpleDateFormat): ExpressImportData? {
        return try {
            ExpressImportData(
                id = getCellInt(row, 0),
                idExp = getCellInt(row, 2) ?: return null,
                kfall = getCellDouble(row, 3) ?: 1.0,
                stsAll = getCellInt(row, 4) ?: 0,
                ct = getCellString(row, 5),
                profLoss = getCellDouble(row, 6) ?: 0.0,
                balans = getCellDouble(row, 7) ?: 0.0,
                sumBet = getCellDouble(row, 8) ?: 0.0,
                strategy = getCellString(row, 9),
                idExpReplace = getCellInt(row, 10) ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга строки экспресса: ${e.message}")
            null
        }
    }
    
    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    
    private fun getCellString(row: Row, index: Int): String? {
        return try {
            val cell = row.getCell(index) ?: return null
            when (cell.cellType) {
                CellType.STRING -> cell.stringCellValue
                CellType.NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        cell.localDateTimeCellValue.toString()
                    } else {
                        val value = cell.numericCellValue
                        if (value == value.toLong().toDouble()) {
                            value.toLong().toString()
                        } else {
                            value.toString()
                        }
                    }
                }
                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                CellType.FORMULA -> cell.cellFormula
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getCellInt(row: Row, index: Int): Int? {
        return try {
            val cell = row.getCell(index) ?: return null
            when (cell.cellType) {
                CellType.NUMERIC -> cell.numericCellValue.toInt()
                CellType.STRING -> cell.stringCellValue.toIntOrNull()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getCellDouble(row: Row, index: Int): Double? {
        return try {
            val cell = row.getCell(index) ?: return null
            when (cell.cellType) {
                CellType.NUMERIC -> cell.numericCellValue
                CellType.STRING -> cell.stringCellValue.toDoubleOrNull()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun parseTimeToMinutes(timeStr: String): Int {
        return timeStr.toIntOrNull() ?: 0
    }
    
    // ==================== DATA CLASSES ====================
    
    data class MatchImportData(
        val id: Int?,
        val idExp: Int,
        val mId: Int,
        val idLiga: Int?,
        val ligaName: String?,
        val idHome: Int?,
        val home: String?,
        val idAway: Int?,
        val away: String?,
        val startKf: Double,
        val lastKf: Double,
        val curTime: String,
        val sh: Int,
        val sa: Int,
        val type: Int,
        val sts: Int,
        val url: String?,
        val uzh: Double?,
        val tbType: Int?
    )
    
    data class ExpressImportData(
        val id: Int?,
        val idExp: Int,
        val kfall: Double,
        val stsAll: Int,
        val ct: String?,
        val profLoss: Double,
        val balans: Double,
        val sumBet: Double,
        val strategy: String?,
        val idExpReplace: Int
    )
}