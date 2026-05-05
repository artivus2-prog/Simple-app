// ExcelReader.kt
package com.example.fonbetbot

import android.content.Context
import android.net.Uri
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
                    id = row.getCell(0).numericCellValue.toInt(),
                    id_exp = row.getCell(1).numericCellValue.toInt(),
                    kfall = row.getCell(2).numericCellValue,
                    sts_all = row.getCell(3).numericCellValue.toInt(),
                    ct = row.getCell(4).stringCellValue,
                    profloss = row.getCell(5).numericCellValue,
                    balans = row.getCell(6).numericCellValue.toInt(),
                    sumbet = row.getCell(7).numericCellValue.toInt(),
                    strategy = row.getCell(8).stringCellValue,
                    id_exp_replace = row.getCell(9)?.numericCellValue?.toInt() ?: 0
                )
                dataList.add(exp)
            } catch (e: Exception) {
                e.printStackTrace()
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
                    id = row.getCell(0).numericCellValue.toInt(),
                    id_exp = row.getCell(1).numericCellValue.toInt(),
                    m_id = row.getCell(2).numericCellValue.toInt(),
                    id_liga = row.getCell(3).numericCellValue.toInt(),
                    liganame = row.getCell(4).stringCellValue,
                    id_home = row.getCell(5).numericCellValue.toInt(),
                    home = row.getCell(6).stringCellValue,
                    id_away = row.getCell(7).numericCellValue.toInt(),
                    away = row.getCell(8).stringCellValue,
                    startkf = row.getCell(9).numericCellValue,
                    lastkf = row.getCell(10).numericCellValue,
                    curtime = row.getCell(11).numericCellValue.toInt(),
                    sh = row.getCell(12).numericCellValue.toInt(),
                    sa = row.getCell(13).numericCellValue.toInt(),
                    type = row.getCell(14).numericCellValue.toInt(),
                    sts = row.getCell(15).numericCellValue.toInt(),
                    url = row.getCell(16)?.stringCellValue ?: "",
                    uzh = row.getCell(17).numericCellValue,
                    tbtype = row.getCell(18).numericCellValue.toInt()
                )
                dataList.add(data)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        workbook.close()
        inputStream.close()
        return dataList
    }
}