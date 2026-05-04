// DatabaseHelper.kt - ЗАГЛУШКА
package com.example.fonbetbot

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "fonbet.db", null, 1) {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase?) {}
    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase?, oldVersion: Int, newVersion: Int) {}
}