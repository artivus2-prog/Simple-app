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
        
        for (i in 1 until sheet.physicalNumberOfRows) {
            val row = sheet.getRow(i) ?: continue
            try {
                val exp = ExpEntity(
                    id = getNumericCellValue(row, 1).toInt(),
                    id_exp = getNumericCellValue(row, 2).toInt(),
                    kfall = getNumericCellValue(row, 3),
                    sts_all = getNumericCellValue(row, 4).toInt(),
                    ct = getStringCellValue(row, 5),
                    profloss = getNumericCellValue(row, 6),
                    balans = getNumericCellValue(row, 7).toInt(),
                    sumbet = getNumericCellValue(row, 8).toInt(),
                    strategy = getStringCellValue(row, 9),
                    id_exp_replace = try {
                        getNumericCellValue(row, 10).toInt()
                    } catch (e: Exception) {
                        0
                    }
                )
                dataList.add(exp)
            } catch (e: Exception) {
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
                    id = getNumericCellValue(row, 1).toInt(),
                    id_exp = getNumericCellValue(row, 2).toInt(),
                    m_id = getNumericCellValue(row, 3).toLong(),
                    id_liga = getNumericCellValue(row, 4).toInt(),
                    liganame = getStringCellValue(row, 5),
                    id_home = getNumericCellValue(row, 6).toLong(),
                    home = getStringCellValue(row, 7),
                    id_away = getNumericCellValue(row, 8).toLong(),
                    away = getStringCellValue(row, 9),
                    startkf = getNumericCellValue(row, 10),
                    lastkf = getNumericCellValue(row, 11),
                    curtime = getNumericCellValue(row, 12).toInt(),
                    sh = getNumericCellValue(row, 13).toInt(),
                    sa = getNumericCellValue(row, 14).toInt(),
                    type = getNumericCellValue(row, 15).toInt(),
                    sts = getNumericCellValue(row, 16).toInt(),
                    url = try { getStringCellValue(row, 17) } catch (e: Exception) { "" },
                    uzh = getStringCellValue(row, 18),  // Всегда читаем как строку
                    tbtype = try { getNumericCellValue(row, 19).toInt() } catch (e: Exception) { 0 }
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
        return try {
            when (cell.cellType) {
                CellType.NUMERIC -> cell.numericCellValue
                CellType.STRING -> {
                    val str = cell.stringCellValue.trim()
                    if (str.isEmpty()) 0.0 
                    else str.replace(",", ".").toDoubleOrNull() ?: 0.0
                }
                CellType.FORMULA -> {
                    try {
                        cell.numericCellValue
                    } catch (e: Exception) {
                        cell.stringCellValue.trim().replace(",", ".").toDoubleOrNull() ?: 0.0
                    }
                }
                else -> 0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }
    
    private fun getStringCellValue(row: org.apache.poi.ss.usermodel.Row, cellIndex: Int): String {
        val cell = row.getCell(cellIndex) ?: return ""
        return try {
            when (cell.cellType) {
                CellType.STRING -> cell.stringCellValue.trim()
                CellType.NUMERIC -> {
                    val num = cell.numericCellValue
                    // Если число целое, возвращаем без десятичной части
                    if (num == num.toLong().toDouble()) {
                        num.toLong().toString()
                    } else {
                        num.toString()
                    }
                }
                CellType.FORMULA -> {
                    try {
                        cell.stringCellValue.trim()
                    } catch (e: Exception) {
                        val num = cell.numericCellValue
                        if (num == num.toLong().toDouble()) {
                            num.toLong().toString()
                        } else {
                            num.toString()
                        }
                    }
                }
                else -> cell.toString().trim()
            }
        } catch (e: Exception) {
            ""
        }
    }
}