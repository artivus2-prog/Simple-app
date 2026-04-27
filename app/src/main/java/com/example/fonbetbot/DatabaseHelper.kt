// DatabaseHelper.kt - ПОЛНАЯ ВЕРСИЯ С is_finalized
package com.example.fonbetbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DatabaseHelper(context: Context) : 
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    
companion object {
    private const val DATABASE_NAME = "fonbet_bot.db"
    private const val DATABASE_VERSION = 4  // Было 3, стало 4
    private const val TAG = "DatabaseHelper"
}
    
    override fun onCreate(db: SQLiteDatabase) {
    Log.d(TAG, "Creating database...")
    
    try {
        // Таблица пользователей
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fsid TEXT NOT NULL,
                device_id TEXT NOT NULL,
                client_id INTEGER DEFAULT 18845703,
                sys_id INTEGER DEFAULT 21,
                username TEXT,
                is_active INTEGER DEFAULT 1,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now')),
                last_login INTEGER,
                UNIQUE(fsid, device_id)
            )
        """)
        
        // Таблица истории баланса
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS balance_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                balance REAL NOT NULL,
                previous_balance REAL,
                check_time INTEGER DEFAULT (strftime('%s', 'now')),
                status TEXT DEFAULT 'success',
                error_message TEXT,
                raw_response TEXT,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
        """)
        
        // Таблица логов
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS bot_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER,
                log_level TEXT DEFAULT 'INFO',
                log_type TEXT NOT NULL,
                message TEXT NOT NULL,
                context TEXT,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
            )
        """)
        
        // Таблица сессий
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS bot_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                start_time INTEGER DEFAULT (strftime('%s', 'now')),
                end_time INTEGER,
                start_balance REAL,
                end_balance REAL,
                checks_count INTEGER DEFAULT 0,
                errors_count INTEGER DEFAULT 0,
                stop_reason TEXT,
                device_info TEXT,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
        """)
        
        // Таблица экспрессов (БЕЗ user_id)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS express_bets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                id_exp INTEGER NOT NULL,
                kfall REAL,
                profloss REAL DEFAULT 0,
                balans REAL,
                sumbet REAL,
                sts_all INTEGER DEFAULT 0,
                ct INTEGER,
                strategy TEXT,
                id_exp_replace INTEGER DEFAULT 0,
                events_count INTEGER DEFAULT 0,
                total_odds REAL,
                bet_amount REAL,
                potential_win REAL,
                balance REAL,
                profit_loss REAL,
                is_bet_placed INTEGER DEFAULT 0,
                created_time INTEGER,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
        """)
        
        // Таблица событий/матчей (БЕЗ user_id)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS express_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                express_id INTEGER NOT NULL,
                id_exp INTEGER NOT NULL,
                m_id INTEGER NOT NULL,
                id_liga INTEGER,
                league_name TEXT,
                id_home INTEGER,
                home_team TEXT,
                id_away INTEGER,
                away_team TEXT,
                start_odds REAL,
                current_odds REAL,
                match_time INTEGER DEFAULT 0,
                home_score INTEGER DEFAULT 0,
                away_score INTEGER DEFAULT 0,
                bet_type INTEGER,
                status INTEGER DEFAULT 0,
                is_finalized INTEGER DEFAULT 0,
                match_url TEXT,
                uzh TEXT,
                total_type INTEGER,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (express_id) REFERENCES express_bets(id) ON DELETE CASCADE
            )
        """)
        
        // Создаем индексы
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_users_fsid ON users(fsid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_balance_user_time ON balance_history(user_id, check_time)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_user_time ON bot_logs(user_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_user ON bot_sessions(user_id, start_time)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_express_exp ON express_bets(id_exp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_express_sts ON express_bets(sts_all)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_express ON express_events(express_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_mid ON express_events(m_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_status ON express_events(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_finalized ON express_events(is_finalized)")
        
        Log.d(TAG, "Database created successfully")
    } catch (e: Exception) {
        Log.e(TAG, "Error creating database: ${e.message}")
    }
}
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    Log.d(TAG, "Upgrading database from $oldVersion to $newVersion")
    
    if (oldVersion < 2) {
        db.execSQL("ALTER TABLE express_events ADD COLUMN is_finalized INTEGER DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_finalized ON express_events(is_finalized)")
    }
    
    if (oldVersion < 3) {
        db.execSQL("ALTER TABLE users ADD COLUMN username TEXT")
    }
    
    if (oldVersion < 4) {
        // Удаляем user_id из express_bets и express_events
        try {
            // Создаем новые таблицы без user_id
            db.execSQL("""
                CREATE TABLE express_bets_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    id_exp INTEGER NOT NULL,
                    kfall REAL,
                    profloss REAL DEFAULT 0,
                    balans REAL,
                    sumbet REAL,
                    sts_all INTEGER DEFAULT 0,
                    ct INTEGER,
                    strategy TEXT,
                    id_exp_replace INTEGER DEFAULT 0,
                    events_count INTEGER DEFAULT 0,
                    total_odds REAL,
                    bet_amount REAL,
                    potential_win REAL,
                    balance REAL,
                    profit_loss REAL,
                    is_bet_placed INTEGER DEFAULT 0,
                    created_time INTEGER,
                    created_at INTEGER DEFAULT (strftime('%s', 'now')),
                    updated_at INTEGER DEFAULT (strftime('%s', 'now'))
                )
            """)
            
            // Копируем данные
            db.execSQL("""
                INSERT INTO express_bets_new (id, id_exp, kfall, profloss, balans, sumbet, sts_all, ct, 
                    strategy, id_exp_replace, events_count, total_odds, bet_amount, potential_win, 
                    balance, profit_loss, is_bet_placed, created_time, created_at, updated_at)
                SELECT id, id_exp, kfall, profloss, balans, sumbet, sts_all, ct, 
                    strategy, id_exp_replace, events_count, total_odds, bet_amount, potential_win, 
                    balance, profit_loss, is_bet_placed, created_time, created_at, updated_at
                FROM express_bets
            """)
            
            // Удаляем старую таблицу
            db.execSQL("DROP TABLE express_bets")
            db.execSQL("ALTER TABLE express_bets_new RENAME TO express_bets")
            
            // Создаем новую таблицу express_events без user_id
            db.execSQL("""
                CREATE TABLE express_events_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    express_id INTEGER NOT NULL,
                    id_exp INTEGER NOT NULL,
                    m_id INTEGER NOT NULL,
                    id_liga INTEGER,
                    league_name TEXT,
                    id_home INTEGER,
                    home_team TEXT,
                    id_away INTEGER,
                    away_team TEXT,
                    start_odds REAL,
                    current_odds REAL,
                    match_time INTEGER DEFAULT 0,
                    home_score INTEGER DEFAULT 0,
                    away_score INTEGER DEFAULT 0,
                    bet_type INTEGER,
                    status INTEGER DEFAULT 0,
                    is_finalized INTEGER DEFAULT 0,
                    match_url TEXT,
                    uzh TEXT,
                    total_type INTEGER,
                    created_at INTEGER DEFAULT (strftime('%s', 'now')),
                    updated_at INTEGER DEFAULT (strftime('%s', 'now')),
                    FOREIGN KEY (express_id) REFERENCES express_bets(id) ON DELETE CASCADE
                )
            """)
            
            // Копируем данные
            db.execSQL("""
                INSERT INTO express_events_new (id, express_id, id_exp, m_id, id_liga, league_name, 
                    id_home, home_team, id_away, away_team, start_odds, current_odds, match_time, 
                    home_score, away_score, bet_type, status, is_finalized, match_url, uzh, 
                    total_type, created_at, updated_at)
                SELECT id, express_id, id_exp, m_id, id_liga, league_name, 
                    id_home, home_team, id_away, away_team, start_odds, current_odds, match_time, 
                    home_score, away_score, bet_type, status, is_finalized, match_url, uzh, 
                    total_type, created_at, updated_at
                FROM express_events
            """)
            
            // Удаляем старую таблицу
            db.execSQL("DROP TABLE express_events")
            db.execSQL("ALTER TABLE express_events_new RENAME TO express_events")
            
            // Создаем индексы заново
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_express_exp ON express_bets(id_exp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_express_sts ON express_bets(sts_all)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_express ON express_events(express_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_mid ON express_events(m_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_status ON express_events(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_finalized ON express_events(is_finalized)")
            
            Log.d(TAG, "✅ Migration to version 4: removed user_id from express tables")
        } catch (e: Exception) {
            Log.e(TAG, "Error during migration to version 4: ${e.message}")
        }
    }
}

    // Обновление информации по fsid и deviceId
    fun updateUserInfoByAuth(fsid: String, deviceId: String, clientId: Long?, username: String?): Boolean {
        val db = writableDatabase
        val values = ContentValues()
        
        clientId?.let { values.put("client_id", it) }
        username?.let { values.put("username", it) }
        
        if (values.size() > 0) {
            values.put("updated_at", System.currentTimeMillis() / 1000)
            val rows = db.update("users", values, "fsid = ? AND device_id = ?", 
                arrayOf(fsid, deviceId))
            return rows > 0
        }
        return false
    }
        fun saveUser(fsid: String, deviceId: String, username: String? = null): Long {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("fsid", fsid)
                put("device_id", deviceId)
                username?.let { put("username", it) }
                put("last_login", System.currentTimeMillis() / 1000)
            }
            
            return db.insertWithOnConflict("users", null, values, 
                SQLiteDatabase.CONFLICT_REPLACE)
        }

        // В DatabaseHelper.kt, после метода getUser
    fun updateUserInfo(userId: Long, clientId: Long?, username: String?) {
        val db = writableDatabase
        val values = ContentValues()
        
        Log.d(TAG, "updateUserInfo: userId=$userId, clientId=$clientId, username=$username")
        
        clientId?.let { values.put("client_id", it) }
        username?.let { values.put("username", it) }
        
        if (values.size() > 0) {
            values.put("updated_at", System.currentTimeMillis() / 1000)
            val rows = db.update("users", values, "id = ?", arrayOf(userId.toString()))
            Log.d(TAG, "Обновлено строк: $rows")
        }
    }
    // Получение пользователя
        fun getUser(fsid: String, deviceId: String): User? {
            val db = readableDatabase
            val cursor = db.query(
                "users",
                null,
                "fsid = ? AND device_id = ?",
                arrayOf(fsid, deviceId),
                null, null, null
            )
            
            cursor.use {
                if (it.moveToFirst()) {
                    return User(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        fsid = it.getString(it.getColumnIndexOrThrow("fsid")),
                        deviceId = it.getString(it.getColumnIndexOrThrow("device_id")),
                        clientId = it.getLong(it.getColumnIndexOrThrow("client_id")),
                        sysId = it.getInt(it.getColumnIndexOrThrow("sys_id")),
                        username = it.getString(it.getColumnIndexOrThrow("username")),
                        isActive = it.getInt(it.getColumnIndexOrThrow("is_active")) == 1
                    )
                }
            }
            return null
        }
                // Получить информацию о пользователе с балансом
        fun getUserFullInfo(fsid: String, deviceId: String): UserFullInfo? {
            val user = getUser(fsid, deviceId) ?: return null
            val stats = getBalanceStats(user.id)
            
            return UserFullInfo(
                id = user.id,
                fsid = user.fsid,
                deviceId = user.deviceId,
                clientId = user.clientId,
                username = user.username,
                currentBalance = stats.currentBalance,
                lastCheckTime = stats.lastCheckTime,
                isActive = user.isActive
            )
        }

        // Data class для полной информации
        data class UserFullInfo(
            val id: Long,
            val fsid: String,
            val deviceId: String,
            val clientId: Long,
            val username: String?,
            val currentBalance: Double,
            val lastCheckTime: Long,
            val isActive: Boolean
        )
    
fun getActiveUser(): User? {
    val db = readableDatabase
    val cursor = db.query(
        "users",
        null,
        "is_active = 1",
        null, null, null,
        "last_login DESC",
        "1"
    )
    
    cursor.use {
        if (it.moveToFirst()) {
            return User(
                id = it.getLong(it.getColumnIndexOrThrow("id")),
                fsid = it.getString(it.getColumnIndexOrThrow("fsid")),
                deviceId = it.getString(it.getColumnIndexOrThrow("device_id")),
                clientId = it.getLong(it.getColumnIndexOrThrow("client_id")),
                sysId = it.getInt(it.getColumnIndexOrThrow("sys_id")),
                username = it.getString(it.getColumnIndexOrThrow("username")),
                isActive = true
            )
        }
    }
    return null
}
    
    // Сохранение баланса
    fun saveBalance(userId: Long, balance: Double, status: String = "success", 
                    errorMessage: String? = null, rawResponse: String? = null): Long {
        val db = writableDatabase
        
        var previousBalance: Double? = null
        val cursor = db.query(
            "balance_history",
            arrayOf("balance"),
            "user_id = ?",
            arrayOf(userId.toString()),
            null, null,
            "check_time DESC",
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                previousBalance = it.getDouble(0)
            }
        }
        
        val values = ContentValues().apply {
            put("user_id", userId)
            put("balance", balance)
            previousBalance?.let { put("previous_balance", it) }
            put("check_time", System.currentTimeMillis() / 1000)
            put("status", status)
            errorMessage?.let { put("error_message", it) }
            rawResponse?.let { put("raw_response", it) }
        }
        
        val id = db.insert("balance_history", null, values)
        updateActiveSession(userId, balance)
        
        return id
    }
    
    private fun updateActiveSession(userId: Long, balance: Double) {
        val db = writableDatabase
        
        val cursor = db.query(
            "bot_sessions",
            arrayOf("id"),
            "user_id = ? AND end_time IS NULL",
            arrayOf(userId.toString()),
            null, null, null
        )
        
        cursor.use {
            if (it.moveToFirst()) {
                val sessionId = it.getLong(0)
                db.execSQL("""
                    UPDATE bot_sessions 
                    SET checks_count = checks_count + 1,
                        end_balance = ?
                    WHERE id = ?
                """, arrayOf(balance.toString(), sessionId.toString()))
            } else {
                val values = ContentValues().apply {
                    put("user_id", userId)
                    put("start_time", System.currentTimeMillis() / 1000)
                    put("start_balance", balance)
                }
                db.insert("bot_sessions", null, values)
            }
        }
    }
    
    // Начать сессию бота
    fun startBotSession(userId: Long, startBalance: Double): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("user_id", userId)
            put("start_time", System.currentTimeMillis() / 1000)
            put("start_balance", startBalance)
        }
        return db.insert("bot_sessions", null, values)
    }
    
    // Остановить сессию бота
    fun stopBotSession(userId: Long, reason: String = "user_stop") {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("end_time", System.currentTimeMillis() / 1000)
            put("stop_reason", reason)
        }
        db.update("bot_sessions", values, 
            "user_id = ? AND end_time IS NULL", 
            arrayOf(userId.toString()))
    }
    
    // Добавить лог
    fun addLog(userId: Long?, type: String, message: String, 
               level: String = "INFO", context: String? = null): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            userId?.let { put("user_id", it) }
            put("log_type", type)
            put("message", message)
            put("log_level", level)
            context?.let { put("context", it) }
            put("created_at", System.currentTimeMillis() / 1000)
        }
        return db.insert("bot_logs", null, values)
    }
    
    // Получить логи
    fun getLogs(limit: Int = 100): List<BotLog> {
        val db = readableDatabase
        val logs = mutableListOf<BotLog>()
        val cursor = db.query(
            "bot_logs",
            null,
            null, null, null, null,
            "created_at DESC",
            limit.toString()
        )
        
        cursor.use {
            while (it.moveToNext()) {
                logs.add(BotLog(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    userId = it.getLong(it.getColumnIndexOrThrow("user_id")),
                    logLevel = it.getString(it.getColumnIndexOrThrow("log_level")),
                    logType = it.getString(it.getColumnIndexOrThrow("log_type")),
                    message = it.getString(it.getColumnIndexOrThrow("message")),
                    createdAt = it.getLong(it.getColumnIndexOrThrow("created_at"))
                ))
            }
        }
        return logs
    }
    
    // Получить статистику баланса
    fun getBalanceStats(userId: Long): BalanceStats {
        val db = readableDatabase
        val stats = BalanceStats()
        
        val cursor = db.query(
            "balance_history",
            arrayOf("balance", "check_time"),
            "user_id = ?",
            arrayOf(userId.toString()),
            null, null,
            "check_time DESC",
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                stats.currentBalance = it.getDouble(0)
                stats.lastCheckTime = it.getLong(1)
            }
        }
        
        val todayCursor = db.rawQuery("""
            SELECT 
                MIN(balance) as min_balance,
                MAX(balance) as max_balance,
                AVG(balance) as avg_balance,
                SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) as errors
            FROM balance_history 
            WHERE user_id = ? AND date(check_time, 'unixepoch') = date('now')
        """, arrayOf(userId.toString()))
        
        todayCursor.use {
            if (it.moveToFirst()) {
                stats.todayMin = it.getDouble(0)
                stats.todayMax = it.getDouble(1)
                stats.todayAvg = it.getDouble(2)
                stats.todayErrors = it.getInt(3)
            }
        }
        
        return stats
    }
    
    // Очистка старых логов
    fun cleanupOldLogs(daysToKeep: Int = 30): Int {
        val db = writableDatabase
        val cutoffTime = (System.currentTimeMillis() / 1000) - (daysToKeep * 24 * 60 * 60)
        return db.delete("bot_logs", "created_at < ?", arrayOf(cutoffTime.toString()))
    }
    
    // Полная очистка базы данных
    suspend fun clearAllData(context: Context): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val db = writableDatabase
            
            db.execSQL("DELETE FROM express_events")
            db.execSQL("DELETE FROM express_bets")
            db.execSQL("DELETE FROM bot_logs")
            db.execSQL("DELETE FROM bot_sessions")
            db.execSQL("DELETE FROM balance_history")
            db.execSQL("DELETE FROM users")
            db.execSQL("DELETE FROM sqlite_sequence")
            
            context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                .edit().clear().apply()
            context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                .edit().clear().apply()
            
            Log.d(TAG, "All data cleared successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing database: ${e.message}")
            false
        }
    }
    
    // Получить размер базы данных
    fun getDatabaseSize(context: Context): String {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        val sizeBytes = dbFile.length()
        
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> "${sizeBytes / (1024 * 1024)} MB"
        }
    }
    
    // Получить статистику по таблицам
    fun getTableStats(): Map<String, Int> {
        val db = readableDatabase
        val stats = mutableMapOf<String, Int>()
        
        val tables = listOf("users", "balance_history", "bot_logs", 
                           "bot_sessions", "express_bets", "express_events")
        
        tables.forEach { table ->
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $table", null)
            cursor.use {
                if (it.moveToFirst()) {
                    stats[table] = it.getInt(0)
                }
            }
        }
        
        return stats
    }

    // ==================== МЕТОДЫ ДЛЯ ТАБЛИЦ ====================
    
    // Получить все матчи
    fun getAllMatches(): List<MatchInfo> {
        val db = readableDatabase
        val matches = mutableListOf<MatchInfo>()
        
        val cursor = db.rawQuery("""
            SELECT 
                e.id, e.express_id, e.id_exp, e.m_id, e.id_liga, 
                COALESCE(e.league_name, '') as league_name,
                COALESCE(e.home_team, '') as home_team, 
                COALESCE(e.away_team, '') as away_team,
                e.id_home, e.id_away, 
                e.start_odds, e.current_odds, e.match_time,
                e.home_score, e.away_score, e.bet_type, e.status,
                e.is_finalized,
                COALESCE(e.match_url, '') as match_url,
                COALESCE(e.uzh, '') as uzh,
                e.total_type, e.created_at, e.updated_at
            FROM express_events e
            ORDER BY e.created_at DESC
        """, null)
        
        while (cursor.moveToNext()) {
            matches.add(MatchInfo(
                id = cursor.getLong(0),
                expressId = cursor.getLong(1),
                idExp = cursor.getInt(2),
                mId = cursor.getInt(3),
                idLiga = if (cursor.isNull(4)) null else cursor.getInt(4),
                leagueName = cursor.getString(5),
                homeTeam = cursor.getString(6),
                awayTeam = cursor.getString(7),
                idHome = if (cursor.isNull(8)) null else cursor.getInt(8),
                idAway = if (cursor.isNull(9)) null else cursor.getInt(9),
                startOdds = cursor.getDouble(10),
                currentOdds = if (cursor.isNull(11)) null else cursor.getDouble(11),
                matchTime = cursor.getInt(12),
                homeScore = cursor.getInt(13),
                awayScore = cursor.getInt(14),
                betType = cursor.getInt(15),
                status = cursor.getInt(16),
                isFinalized = cursor.getInt(17),
                matchUrl = cursor.getString(18),
                uzh = cursor.getString(19),
                totalType = if (cursor.isNull(20)) null else cursor.getInt(20),
                createdAt = cursor.getLong(21),
                updatedAt = cursor.getLong(22)
            ))
        }
        cursor.close()
        
        return matches
    }
    
// Получить все экспрессы
fun getAllExpresses(): List<ExpressInfo> {
    val db = readableDatabase
    val expresses = mutableListOf<ExpressInfo>()
    
    try {
        val cursor = db.rawQuery("""
            SELECT 
                id, id_exp, 
                COALESCE(kfall, 1.0) as kfall,
                COALESCE(profloss, 0.0) as profloss,
                COALESCE(balans, 0.0) as balans,
                COALESCE(sumbet, 0.0) as sumbet,
                sts_all, ct,
                COALESCE(strategy, '') as strategy,
                COALESCE(id_exp_replace, 0) as id_exp_replace,
                COALESCE(events_count, 0) as events_count,
                COALESCE(total_odds, 1.0) as total_odds,
                COALESCE(bet_amount, 0.0) as bet_amount,
                COALESCE(potential_win, 0.0) as potential_win,
                COALESCE(balance, 0.0) as balance,
                COALESCE(profit_loss, 0.0) as profit_loss,
                COALESCE(is_bet_placed, 0) as is_bet_placed,
                COALESCE(created_time, 0) as created_time,
                created_at, updated_at
            FROM express_bets
            ORDER BY ct DESC
        """, null)
        
        // Логируем количество колонок для отладки
        Log.d(TAG, "getAllExpresses: columnCount=${cursor.columnCount}")
        
        while (cursor.moveToNext()) {
            try {
                expresses.add(ExpressInfo(
                    id = cursor.getLong(0),
                    idExp = cursor.getInt(1),
                    kfall = cursor.getDouble(2),
                    profLoss = cursor.getDouble(3),
                    balans = cursor.getDouble(4),
                    sumbet = cursor.getDouble(5),
                    stsAll = cursor.getInt(6),
                    ct = cursor.getLong(7),
                    strategy = cursor.getString(8),
                    idExpReplace = cursor.getInt(9),
                    eventsCount = cursor.getInt(10),
                    totalOdds = cursor.getDouble(11),
                    betAmount = cursor.getDouble(12),
                    potentialWin = cursor.getDouble(13),
                    createdAt = cursor.getLong(18),
                    updatedAt = cursor.getLong(19)
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка чтения строки в getAllExpresses: ${e.message}")
            }
        }
        cursor.close()
        
    } catch (e: Exception) {
        Log.e(TAG, "Ошибка getAllExpresses: ${e.message}")
    }
    
    Log.d(TAG, "getAllExpresses: загружено ${expresses.size} экспрессов")
    return expresses
}
    
    // Получить матчи по ID экспресса
    fun getMatchesByExpressId(expressId: Long): List<MatchInfo> {
        val db = readableDatabase
        val matches = mutableListOf<MatchInfo>()
        
        val cursor = db.rawQuery("""
            SELECT 
                e.id, e.express_id, e.id_exp, e.m_id, e.id_liga, 
                COALESCE(e.league_name, '') as league_name,
                COALESCE(e.home_team, '') as home_team, 
                COALESCE(e.away_team, '') as away_team,
                e.id_home, e.id_away, 
                e.start_odds, e.current_odds, e.match_time,
                e.home_score, e.away_score, e.bet_type, e.status,
                e.is_finalized,
                COALESCE(e.match_url, '') as match_url,
                COALESCE(e.uzh, '') as uzh,
                e.total_type, e.created_at, e.updated_at
            FROM express_events e
            WHERE e.express_id = ?
            ORDER BY e.id ASC
        """, arrayOf(expressId.toString()))
        
        while (cursor.moveToNext()) {
            matches.add(MatchInfo(
                id = cursor.getLong(0),
                expressId = cursor.getLong(1),
                idExp = cursor.getInt(2),
                mId = cursor.getInt(3),
                idLiga = if (cursor.isNull(4)) null else cursor.getInt(4),
                leagueName = cursor.getString(5),
                homeTeam = cursor.getString(6),
                awayTeam = cursor.getString(7),
                idHome = if (cursor.isNull(8)) null else cursor.getInt(8),
                idAway = if (cursor.isNull(9)) null else cursor.getInt(9),
                startOdds = cursor.getDouble(10),
                currentOdds = if (cursor.isNull(11)) null else cursor.getDouble(11),
                matchTime = cursor.getInt(12),
                homeScore = cursor.getInt(13),
                awayScore = cursor.getInt(14),
                betType = cursor.getInt(15),
                status = cursor.getInt(16),
                isFinalized = cursor.getInt(17),
                matchUrl = cursor.getString(18),
                uzh = cursor.getString(19),
                totalType = if (cursor.isNull(20)) null else cursor.getInt(20),
                createdAt = cursor.getLong(21),
                updatedAt = cursor.getLong(22)
            ))
        }
        cursor.close()
        
        return matches
    }
}

// ==================== DATA CLASSES ====================

data class User(
    val id: Long,
    val fsid: String,
    val deviceId: String,
    val clientId: Long,
    val sysId: Int,
    val username: String? = null,  // новое поле
    val isActive: Boolean
)

data class BotLog(
    val id: Long,
    val userId: Long,
    val logLevel: String,
    val logType: String,
    val message: String,
    val createdAt: Long
)

data class BalanceStats(
    var currentBalance: Double = 0.0,
    var lastCheckTime: Long = 0,
    var todayMin: Double = 0.0,
    var todayMax: Double = 0.0,
    var todayAvg: Double = 0.0,
    var todayErrors: Int = 0
)

data class MatchInfo(
    val id: Long,
    val expressId: Long,
    val idExp: Int,
    val mId: Int,
    val idLiga: Int?,
    val leagueName: String,
    val homeTeam: String,
    val awayTeam: String,
    val idHome: Int?,
    val idAway: Int?,
    val startOdds: Double,
    val currentOdds: Double?,
    val matchTime: Int,
    val homeScore: Int,
    val awayScore: Int,
    val betType: Int,
    val status: Int,
    val isFinalized: Int,
    val matchUrl: String,
    val uzh: String,
    val totalType: Int?,
    val createdAt: Long,
    val updatedAt: Long
)

data class ExpressInfo(
    val id: Long,
    val idExp: Int,
    val kfall: Double,
    val profLoss: Double,
    val balans: Double,
    val sumbet: Double,
    val stsAll: Int,
    val ct: Long,
    val strategy: String,
    val idExpReplace: Int,
    val eventsCount: Int,
    val totalOdds: Double,
    val betAmount: Double,
    val potentialWin: Double,
    val createdAt: Long,
    val updatedAt: Long
)