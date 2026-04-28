// DatabaseHelper.kt - ПОЛНАЯ ИСПРАВЛЕННАЯ ВЕРСИЯ (убраны лишние поля, упрощена структура)
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
        private const val DATABASE_VERSION = 5  // Версия 5: убраны дублирующиеся поля
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
            
            // Таблица экспрессов
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS express_bets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    id_exp INTEGER NOT NULL UNIQUE,
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
                    profit_loss REAL,
                    is_bet_placed INTEGER DEFAULT 0,
                    created_at INTEGER DEFAULT (strftime('%s', 'now')),
                    updated_at INTEGER DEFAULT (strftime('%s', 'now'))
                )
            """)
            
            // Таблица событий/матчей
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS express_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    id_exp INTEGER NOT NULL,
                    m_id INTEGER NOT NULL UNIQUE,
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
                    FOREIGN KEY (id_exp) REFERENCES express_bets(id_exp) ON DELETE CASCADE
                )
            """)
            
            // Создаем индексы
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_users_fsid ON users(fsid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_balance_user_time ON balance_history(user_id, check_time)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_logs_user_time ON bot_logs(user_id, created_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_user ON bot_sessions(user_id, start_time)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_express_exp ON express_bets(id_exp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_express_sts ON express_bets(sts_all)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_exp ON express_events(id_exp)")
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
        
        if (oldVersion < 5) {
            // Полная перестройка таблиц экспрессов для версии 5
            try {
                // Сохраняем старые данные
                db.execSQL("CREATE TABLE IF NOT EXISTS express_bets_backup AS SELECT * FROM express_bets")
                db.execSQL("CREATE TABLE IF NOT EXISTS express_events_backup AS SELECT * FROM express_events")
                
                // Удаляем старые таблицы
                db.execSQL("DROP TABLE IF EXISTS express_events")
                db.execSQL("DROP TABLE IF EXISTS express_bets")
                
                // Создаем новые таблицы
                db.execSQL("""
                    CREATE TABLE express_bets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        id_exp INTEGER NOT NULL UNIQUE,
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
                        profit_loss REAL,
                        is_bet_placed INTEGER DEFAULT 0,
                        created_at INTEGER DEFAULT (strftime('%s', 'now')),
                        updated_at INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                """)
                
                db.execSQL("""
                    CREATE TABLE express_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        id_exp INTEGER NOT NULL,
                        m_id INTEGER NOT NULL UNIQUE,
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
                        FOREIGN KEY (id_exp) REFERENCES express_bets(id_exp) ON DELETE CASCADE
                    )
                """)
                
                // Восстанавливаем данные из бекапа (без дублирующихся полей)
                db.execSQL("""
                    INSERT INTO express_bets (id, id_exp, kfall, profloss, balans, sumbet, sts_all, ct, 
                        strategy, id_exp_replace, is_bet_placed, created_at, updated_at)
                    SELECT id, id_exp, kfall, profloss, balans, sumbet, sts_all, ct, 
                        strategy, id_exp_replace, COALESCE(is_bet_placed, 0), created_at, updated_at
                    FROM express_bets_backup
                """)
                
                db.execSQL("""
                    INSERT INTO express_events (id, id_exp, m_id, id_liga, league_name, 
                        id_home, home_team, id_away, away_team, start_odds, current_odds, match_time, 
                        home_score, away_score, bet_type, status, is_finalized, match_url, uzh, 
                        total_type, created_at, updated_at)
                    SELECT id, id_exp, m_id, id_liga, league_name, 
                        id_home, home_team, id_away, away_team, start_odds, current_odds, match_time, 
                        home_score, away_score, bet_type, status, 
                        COALESCE(is_finalized, 0), match_url, uzh, total_type, created_at, updated_at
                    FROM express_events_backup
                """)
                
                // Удаляем бекапы
                db.execSQL("DROP TABLE IF EXISTS express_bets_backup")
                db.execSQL("DROP TABLE IF EXISTS express_events_backup")
                
                // Создаем индексы
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_express_exp ON express_bets(id_exp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_express_sts ON express_bets(sts_all)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_exp ON express_events(id_exp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_mid ON express_events(m_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_status ON express_events(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_finalized ON express_events(is_finalized)")
                
                Log.d(TAG, "✅ Migration to version 5: simplified express tables structure")
            } catch (e: Exception) {
                Log.e(TAG, "Error during migration to version 5: ${e.message}")
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
    
    fun startBotSession(userId: Long, startBalance: Double): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("user_id", userId)
            put("start_time", System.currentTimeMillis() / 1000)
            put("start_balance", startBalance)
        }
        return db.insert("bot_sessions", null, values)
    }
    
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
    
    fun cleanupOldLogs(daysToKeep: Int = 30): Int {
        val db = writableDatabase
        val cutoffTime = (System.currentTimeMillis() / 1000) - (daysToKeep * 24 * 60 * 60)
        return db.delete("bot_logs", "created_at < ?", arrayOf(cutoffTime.toString()))
    }
    
    suspend fun clearAllData(context: Context): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val db = writableDatabase
            
            db.execSQL("DELETE FROM express_events")
            db.execSQL("DELETE FROM express_bets")
            db.execSQL("DELETE FROM bot_logs")
            db.execSQL("DELETE FROM bot_sessions")
            db.execSQL("DELETE FROM balance_history")
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
    
    fun getDatabaseSize(context: Context): String {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        val sizeBytes = dbFile.length()
        
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> "${sizeBytes / (1024 * 1024)} MB"
        }
    }
    
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

    // ==================== МЕТОДЫ ДЛЯ ТАБЛИЦ ЭКСПРЕССОВ ====================
    
    /**
     * Получить все экспрессы
     */
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
                    COALESCE(profit_loss, 0.0) as profit_loss,
                    COALESCE(is_bet_placed, 0) as is_bet_placed,
                    created_at, updated_at
                FROM express_bets
                ORDER BY ct DESC
            """, null)
            
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
                        profitLoss = cursor.getDouble(14),
                        isBetPlaced = cursor.getInt(15),
                        createdAt = cursor.getLong(16),
                        updatedAt = cursor.getLong(17)
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
    
    /**
     * Получить все матчи
     */
    fun getAllMatches(): List<MatchInfo> {
        val db = readableDatabase
        val matches = mutableListOf<MatchInfo>()
        
        val cursor = db.rawQuery("""
            SELECT 
                e.id, e.id_exp, e.m_id, e.id_liga, 
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
                idExp = cursor.getInt(1),
                mId = cursor.getInt(2),
                idLiga = if (cursor.isNull(3)) null else cursor.getInt(3),
                leagueName = cursor.getString(4),
                homeTeam = cursor.getString(5),
                awayTeam = cursor.getString(6),
                idHome = if (cursor.isNull(7)) null else cursor.getInt(7),
                idAway = if (cursor.isNull(8)) null else cursor.getInt(8),
                startOdds = cursor.getDouble(9),
                currentOdds = if (cursor.isNull(10)) null else cursor.getDouble(10),
                matchTime = cursor.getInt(11),
                homeScore = cursor.getInt(12),
                awayScore = cursor.getInt(13),
                betType = cursor.getInt(14),
                status = cursor.getInt(15),
                isFinalized = cursor.getInt(16),
                matchUrl = cursor.getString(17),
                uzh = cursor.getString(18),
                totalType = if (cursor.isNull(19)) null else cursor.getInt(19),
                createdAt = cursor.getLong(20),
                updatedAt = cursor.getLong(21)
            ))
        }
        cursor.close()
        
        return matches
    }
    
    /**
     * Получить матчи по id_exp экспресса
     */
    fun getMatchesByExpId(idExp: Int): List<MatchInfo> {
        val db = readableDatabase
        val matches = mutableListOf<MatchInfo>()
        
        val cursor = db.rawQuery("""
            SELECT 
                e.id, e.id_exp, e.m_id, e.id_liga, 
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
            WHERE e.id_exp = ?
            ORDER BY e.id ASC
        """, arrayOf(idExp.toString()))
        
        while (cursor.moveToNext()) {
            matches.add(MatchInfo(
                id = cursor.getLong(0),
                idExp = cursor.getInt(1),
                mId = cursor.getInt(2),
                idLiga = if (cursor.isNull(3)) null else cursor.getInt(3),
                leagueName = cursor.getString(4),
                homeTeam = cursor.getString(5),
                awayTeam = cursor.getString(6),
                idHome = if (cursor.isNull(7)) null else cursor.getInt(7),
                idAway = if (cursor.isNull(8)) null else cursor.getInt(8),
                startOdds = cursor.getDouble(9),
                currentOdds = if (cursor.isNull(10)) null else cursor.getDouble(10),
                matchTime = cursor.getInt(11),
                homeScore = cursor.getInt(12),
                awayScore = cursor.getInt(13),
                betType = cursor.getInt(14),
                status = cursor.getInt(15),
                isFinalized = cursor.getInt(16),
                matchUrl = cursor.getString(17),
                uzh = cursor.getString(18),
                totalType = if (cursor.isNull(19)) null else cursor.getInt(19),
                createdAt = cursor.getLong(20),
                updatedAt = cursor.getLong(21)
            ))
        }
        cursor.close()
        
        return matches
    }
    
    /**
     * Сохранить экспресс с матчами
     */
    fun saveExpressWithMatches(
    expId: Int,
    kfall: Double,
    sumbet: Double,
    potentialWin: Double,
    balance: Double,
    strategy: String,
    eventsCount: Int,
    matches: List<ExpressEventData>
): Long {
    val db = writableDatabase
    val currentTime = System.currentTimeMillis() / 1000
    var expressRowId = -1L
    
    db.beginTransaction()
    try {
        // Сохраняем экспресс
        val expressValues = ContentValues().apply {
            put("id_exp", expId)
            put("kfall", kfall)
            put("profloss", 0.0)
            put("balans", balance)
            put("sumbet", sumbet)
            put("sts_all", 0)
            put("is_bet_placed", 0)
            put("ct", currentTime)
            put("strategy", strategy)
            put("id_exp_replace", 0)
            put("events_count", eventsCount)
            put("total_odds", kfall)
            put("bet_amount", sumbet)
            put("potential_win", potentialWin)
            put("profit_loss", 0.0)
            put("created_at", currentTime)
            put("updated_at", currentTime)
        }
        
        expressRowId = db.insertWithOnConflict("express_bets", null, expressValues,
            SQLiteDatabase.CONFLICT_REPLACE)
        
        if (expressRowId == -1L) {
            Log.e(TAG, "❌ Ошибка вставки в express_bets")
            db.endTransaction()
            return -1
        }
        
        // Сохраняем матчи
        matches.forEach { match ->
            val eventValues = ContentValues().apply {
                put("id_exp", expId)
                put("m_id", match.mId)
                match.idLiga?.let { put("id_liga", it) }
                put("league_name", match.leagueName)
                match.idHome?.let { put("id_home", it) }
                put("home_team", match.homeTeam)
                match.idAway?.let { put("id_away", it) }
                put("away_team", match.awayTeam)
                put("start_odds", match.startOdds)
                match.currentOdds?.let { put("current_odds", it) }
                put("match_time", match.matchTime)
                put("home_score", match.homeScore)
                put("away_score", match.awayScore)
                put("bet_type", match.betType)
                put("status", match.status)
                put("is_finalized", match.isFinalized)
                put("match_url", match.matchUrl)
                put("uzh", match.uzh)
                match.totalType?.let { put("total_type", it) }
                put("created_at", currentTime)
                put("updated_at", currentTime)
            }
            
            val eventInsertId = db.insertWithOnConflict("express_events", null, eventValues,
                SQLiteDatabase.CONFLICT_REPLACE)
                
            if (eventInsertId == -1L) {
                Log.e(TAG, "❌ Ошибка вставки матча #${match.mId}")
            }
        }
        
        db.setTransactionSuccessful()
        Log.d(TAG, "✅ Экспресс #$expId сохранен с ${matches.size} матчами")
        
    } catch (e: Exception) {
        Log.e(TAG, "Ошибка сохранения экспресса: ${e.message}")
        return -1
    } finally {
        db.endTransaction()
    }
    
    return expressRowId
}
    
    /**
     * Обновить статус матча по m_id
     */
    fun updateMatchStatus(
        mId: Int,
        homeScore: Int,
        awayScore: Int,
        matchTime: Int,
        status: Int,
        currentOdds: Double? = null,
        isFinalized: Int = 0
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("home_score", homeScore)
            put("away_score", awayScore)
            put("match_time", matchTime)
            put("status", status)
            put("is_finalized", isFinalized)
            put("updated_at", System.currentTimeMillis() / 1000)
            currentOdds?.let { put("current_odds", it) }
        }
        db.update("express_events", values, "m_id = ?", arrayOf(mId.toString()))
    }
    
    /**
     * Обновить статус экспресса
     */
    fun updateExpressStatus(
        idExp: Int,
        stsAll: Int,
        profLoss: Double,
        balance: Double
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("sts_all", stsAll)
            put("profloss", profLoss)
            put("balans", balance)
            put("updated_at", System.currentTimeMillis() / 1000)
        }
        db.update("express_bets", values, "id_exp = ?", arrayOf(idExp.toString()))
    }
    
    /**
     * Обновить флаг размещения ставки
     */
    fun updateBetPlaced(idExp: Int, isPlaced: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("is_bet_placed", if (isPlaced) 1 else 0)
            put("updated_at", System.currentTimeMillis() / 1000)
        }
        db.update("express_bets", values, "id_exp = ?", arrayOf(idExp.toString()))
    }
    
    /**
     * Получить id_exp по m_id матча
     */
    fun getExpIdByMatchId(mId: Int): Int? {
        val db = readableDatabase
        val cursor = db.query("express_events", arrayOf("id_exp"),
            "m_id = ?", arrayOf(mId.toString()), null, null, null)
        
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return null
    }
    
    /**
     * Проверить существование экспресса с такими же матчами
     */
    fun isExpressExists(matchIds: List<Int>): Boolean {
        val db = readableDatabase
        
        // Получаем все активные экспрессы
        val expressCursor = db.query("express_bets", arrayOf("id_exp"),
            "sts_all != -1", null, null, null, "id_exp DESC")
        
        val expressIds = mutableListOf<Int>()
        while (expressCursor.moveToNext()) {
            expressIds.add(expressCursor.getInt(0))
        }
        expressCursor.close()
        
        for (idExp in expressIds) {
            val eventsCursor = db.query("express_events", arrayOf("m_id"),
                "id_exp = ?", arrayOf(idExp.toString()), null, null, null)
            
            val existingMatchIds = mutableListOf<Int>()
            while (eventsCursor.moveToNext()) {
                existingMatchIds.add(eventsCursor.getInt(0))
            }
            eventsCursor.close()
            
            if (existingMatchIds.sorted() == matchIds.sorted()) {
                return true
            }
        }
        
        return false
    }
}

// ==================== DATA CLASSES ====================

data class User(
    val id: Long,
    val fsid: String,
    val deviceId: String,
    val clientId: Long,
    val sysId: Int,
    val username: String? = null,
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
    val profitLoss: Double = 0.0,
    val isBetPlaced: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Data class для данных матча при сохранении
 */
data class ExpressEventData(
    val mId: Int,
    val idLiga: Int? = null,
    val leagueName: String = "",
    val idHome: Int? = null,
    val homeTeam: String = "",
    val idAway: Int? = null,
    val awayTeam: String = "",
    val startOdds: Double,
    val currentOdds: Double? = null,
    val matchTime: Int = 0,
    val homeScore: Int = 0,
    val awayScore: Int = 0,
    val betType: Int,
    val status: Int = 0,
    val isFinalized: Int = 0,
    val matchUrl: String = "",
    val uzh: String = "0.0",
    val totalType: Int? = null
)