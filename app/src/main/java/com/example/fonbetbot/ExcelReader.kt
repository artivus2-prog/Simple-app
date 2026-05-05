// ExcelReader.kt
package com.example.fonbetbot

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.InputStream

class ExcelReader(private val context: Context) {
    
    fun readExpData(uri: Uri): List<ExpEntity> {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)!!
        val workbook = WorkbookFactory.create(inputStream)
        val sheet = workbook.getSheetAt(0)
        
        val dataList = mutableListOf<ExpEntity>()
        
        // Начинаем с 1, пропуская заголовок
        for (i in 1 until sheet.physicalNumberOfRows) {
            val row = sheet.getRow(i) ?: continue
            try {
                val exp = ExpEntity(
                    id = getNumericCellValue(row, 0).toInt(),
                    id_exp = getNumericCellValue(row, 1).toInt(),
                    kfall = getNumericCellValue(row, 2),
                    sts_all = getNumericCellValue(row, 3).toInt(),
                    ct = getStringCellValue(row, 4),
                    profloss = getStringCellValue(row, 5).replace(",", ".").toDoubleOrNull() ?: 0.0,
                    balans = getNumericCellValue(row, 6).toInt(),
                    sumbet = getNumericCellValue(row, 7).toInt(),
                    strategy = getStringCellValue(row, 8),
                    id_exp_replace = try {
                        getNumericCellValue(row, 9).toInt()
                    } catch (e: Exception) {
                        0
                    }
                )
                dataList.add(exp)
            } catch (e: Exception) {
                // Логируем ошибку, но продолжаем
                e.printStackTrace()
                continue
            }
        }
        
        workbook.close()
        inputStream.close()
        return dataList
    }
    
    fun readDataData(uri: Uri): List<DataEntity> {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)!!
        val workbook = WorkbookFactory.create(inputStream)
        val sheet = workbook.getSheetAt(0)
        
        val dataList = mutableListOf<DataEntity>()
        
        for (i in 1 until sheet.physicalNumberOfRows) {
            val row = sheet.getRow(i) ?: continue
            try {
                val data = DataEntity(
                    id = getNumericCellValue(row, 0).toInt(),
                    id_exp = getNumericCellValue(row, 1).toInt(),
                    m_id = getNumericCellValue(row, 2).toLong(),
                    id_liga = getNumericCellValue(row, 3).toInt(),
                    liganame = getStringCellValue(row, 4),
                    id_home = getNumericCellValue(row, 5).toLong(),
                    home = getStringCellValue(row, 6),
                    id_away = getNumericCellValue(row, 7).toLong(),
                    away = getStringCellValue(row, 8),
                    startkf = getNumericCellValue(row, 9),
                    lastkf = getNumericCellValue(row, 10),
                    curtime = getNumericCellValue(row, 11).toInt(),
                    sh = getNumericCellValue(row, 12).toInt(),
                    sa = getNumericCellValue(row, 13).toInt(),
                    type = getNumericCellValue(row, 14).toInt(),
                    sts = getNumericCellValue(row, 15).toInt(),
                    url = try { getStringCellValue(row, 16) } catch (e: Exception) { "" },
                    uzh = getNumericCellValue(row, 17),
                    tbtype = try { getNumericCellValue(row, 18).toInt() } catch (e: Exception) { 0 }
                )
                dataList.add(data)
            } catch (e: Exception) {
                e.printStackTrace()
                continue
            }
        }
        
        workbook.close()
        inputStream.close()
        return dataList
    }
    
    private fun getNumericCellValue(row: org.apache.poi.ss.usermodel.Row, cellIndex: Int): Double {
        val cell = row.getCell(cellIndex) ?: return 0.0
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> cell.stringCellValue.replace(",", ".").toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
    
    private fun getStringCellValue(row: org.apache.poi.ss.usermodel.Row, cellIndex: Int): String {
        val cell = row.getCell(cellIndex) ?: return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> cell.numericCellValue.toLong().toString()
            else -> cell.toString()
        }
    }
}