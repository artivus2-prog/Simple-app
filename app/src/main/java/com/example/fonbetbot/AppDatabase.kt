// AppDatabase.kt
package com.example.fonbetbot

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ExpEntity::class, DataEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expDao(): ExpDao
    abstract fun dataDao(): DataDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fonbet_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}