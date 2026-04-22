// WebViewAuthScreen.kt
package com.example.fonbetbot

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewAuthScreen(
    onAuthSuccess: (fsid: String, deviceId: String) -> Unit,
    onBack: () -> Unit
) {
    val authUrl = "https://www.fon.bet/"
    var isLoading by remember { mutableStateOf(true) }
    var isCheckingAuth by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var foundCookies by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showCookiesDialog by remember { mutableStateOf(false) }
    var manualFsid by remember { mutableStateOf("") }
    var manualDeviceId by remember { mutableStateOf("") }
    var showManualDialog by remember { mutableStateOf(false) }
    
    // Функция для извлечения ВСЕХ кук
    fun getAllCookies(url: String): Map<String, String> {
        val cookieManager = CookieManager.getInstance()
        val cookieString = cookieManager.getCookie(url) ?: ""
        
        return if (cookieString.isNotEmpty()) {
            cookieString.split("; ").associate { cookie ->
                val parts = cookie.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }
        } else {
            emptyMap()
        }
    }
    
    // Функция для поиска FSID и DeviceID
    fun extractAuthData(cookies: Map<String, String>): Pair<String, String> {
        // Расширенный список возможных названий для FSID
        val fsidKeys = listOf(
            "fsid", "FSID", "_fsid", "fonbet_fsid", 
            "fonbet_session", "session_id", "sid", "SessionId",
            "spid", "spsc", "PHPSESSID", "connect.sid"
        )
        
        // Расширенный список для DeviceID
        val deviceIdKeys = listOf(
            "local.devicedl",
            "deviceid", "deviceId", "device_id", "_deviceId", 
            "d_id", "device", "DeviceId", "deviceUID", "uid"
        )
        
        val fsid = fsidKeys.firstNotNullOfOrNull { key ->
            cookies[key]
        } ?: ""
        
        val deviceId = deviceIdKeys.firstNotNullOfOrNull { key ->
            cookies[key]
        } ?: ""
        
        return Pair(fsid, deviceId)
    }
    
    // Функция для извлечения через JavaScript
    fun extractViaJavaScript() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var result = {};
                
                // 1. Куки
                document.cookie.split(';').forEach(function(cookie) {
                    var parts = cookie.trim().split('=');
                    if (parts.length >= 2) {
                        result['cookie_' + parts[0]] = decodeURIComponent(parts.slice(1).join('='));
                    }
                });
                
                // 2. localStorage
                try {
                    for (var i = 0; i < localStorage.length; i++) {
                        var key = localStorage.key(i);
                        result['ls_' + key] = localStorage.getItem(key);
                    }
                } catch(e) {}
                
                // 3. sessionStorage
                try {
                    for (var i = 0; i < sessionStorage.length; i++) {
                        var key = sessionStorage.key(i);
                        result['ss_' + key] = sessionStorage.getItem(key);
                    }
                } catch(e) {}
                
                // 4. Поиск в window объекте
                try {
                    if (window.fsid) result['window_fsid'] = window.fsid;
                    if (window.FSID) result['window_FSID'] = window.FSID;
                    if (window.user && window.user.fsid) result['window_user_fsid'] = window.user.fsid;
                    if (window.__INITIAL_STATE__) {
                        result['__INITIAL_STATE__'] = JSON.stringify(window.__INITIAL_STATE__);
                    }
                } catch(e) {}
                
                return JSON.stringify(result);
            })();
        """.trimIndent()) { result ->
            try {
                if (result != "null" && result.isNotEmpty()) {
                    val jsonObject = JSONObject(result)
                    val allData = mutableMapOf<String, String>()
                    
                    val keys = jsonObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = jsonObject.optString(key, "")
                        if (value.isNotEmpty() && value != "null") {
                            allData[key] = value
                        }
                    }
                    
                    foundCookies = allData
                    
                    // Поиск fsid
                    var fsid = ""
                    var deviceId = ""
                    
                    // Ищем fsid по ключевым словам
                    val fsidPatterns = listOf("fsid", "FSID", "session", "token", "auth", "spid")
                    for ((key, value) in allData) {
                        val lowerKey = key.lowercase()
                        
                        // Поиск fsid
                        if (fsid.isEmpty()) {
                            for (pattern in fsidPatterns) {
                                if (lowerKey.contains(pattern) && value.length in 20..200) {
                                    fsid = value
                                    break
                                }
                            }
                        }
                        
                        // Поиск deviceId
                        if (deviceId.isEmpty() && lowerKey.contains("device")) {
                            deviceId = value
                        }
                    }
                    
                    // Если нашли - авторизуемся
                    if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                        onAuthSuccess(fsid, deviceId)
                    } else if (fsid.isNotEmpty()) {
                        // Есть fsid, но нет deviceId - пробуем получить из кук
                        val cookies = getAllCookies(webViewRef?.url ?: "")
                        val devId = extractAuthData(cookies).second
                        if (devId.isNotEmpty()) {
                            onAuthSuccess(fsid, devId)
                        }
                    }
                }
            } catch (e: Exception) {
                // Игнорируем
            }
        }
    }
    
    // Функция проверки авторизации
    fun checkAuth() {
        isCheckingAuth = true
        webViewRef?.url?.let { url ->
            val cookies = getAllCookies(url)
            foundCookies = cookies
            
            val (fsid, deviceId) = extractAuthData(cookies)
            
            if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                onAuthSuccess(fsid, deviceId)
            } else {
                // Запускаем JavaScript извлечение
                extractViaJavaScript()
            }
        }
        isCheckingAuth = false
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🔐 Авторизация Фонбет") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showManualDialog = true }) {
                        Icon(Icons.Default.Edit, "Ввести вручную")
                    }
                    IconButton(onClick = { 
                        checkAuth()
                        extractViaJavaScript()
                    }) {
                        Icon(Icons.Default.Check, "Проверить")
                    }
                    IconButton(onClick = { showCookiesDialog = true }) {
                        Icon(Icons.Default.Info, "Куки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // WebView
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        webViewRef = this
                        
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            javaScriptCanOpenWindowsAutomatically = true
                            setSupportMultipleWindows(false)
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        }
                        
                        // Настройка CookieManager
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                
                                // После загрузки страницы сразу пробуем получить куки
                                url?.let { currentUrl ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        val cookies = getAllCookies(currentUrl)
                                        foundCookies = cookies
                                        
                                        val (fsid, deviceId) = extractAuthData(cookies)
                                        if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                                            // Автоматический успех при обнаружении
                                            onAuthSuccess(fsid, deviceId)
                                        }
                                        
                                        // Запускаем JavaScript извлечение
                                        extractViaJavaScript()
                                    }, 2000)
                                }
                            }
                            
                            override fun onLoadResource(view: WebView?, url: String?) {
                                // Логируем все загружаемые ресурсы для отладки
                                url?.let {
                                    if (it.contains("session") || it.contains("auth") || it.contains("token")) {
                                        android.util.Log.d("WebViewAuth", "Loading: $it")
                                    }
                                }
                                super.onLoadResource(view, url)
                            }
                            
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                url?.let {
                                    view?.loadUrl(it)
                                }
                                return true
                            }
                        }
                        
                        loadUrl(authUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Индикатор загрузки
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
            
            // Кнопка проверки внизу
            if (!isLoading) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Войдите в аккаунт Фонбет, затем нажмите кнопку ниже",
                            fontSize = 14.sp
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Button(
                            onClick = {
                                checkAuth()
                                extractViaJavaScript()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isCheckingAuth
                        ) {
                            if (isCheckingAuth) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (isCheckingAuth) "ПРОВЕРКА..." else "🔍 ПРОВЕРИТЬ АВТОРИЗАЦИЮ")
                        }
                        
                        if (foundCookies.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Найдено кук: ${foundCookies.size}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // Диалог с найденными куками (для отладки)
        if (showCookiesDialog) {
            AlertDialog(
                onDismissRequest = { showCookiesDialog = false },
                title = { Text("Найденные данные") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (foundCookies.isEmpty()) {
                            Text("Данные не найдены")
                        } else {
                            // Группируем по источнику
                            val cookieData = foundCookies.filter { it.key.startsWith("cookie_") }
                            val lsData = foundCookies.filter { it.key.startsWith("ls_") }
                            val ssData = foundCookies.filter { it.key.startsWith("ss_") }
                            val otherData = foundCookies.filter { 
                                !it.key.startsWith("cookie_") && 
                                !it.key.startsWith("ls_") && 
                                !it.key.startsWith("ss_") 
                            }
                            
                            if (cookieData.isNotEmpty()) {
                                Text("🍪 Куки:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                cookieData.forEach { (key, value) ->
                                    DataRow(key.removePrefix("cookie_"), value)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            if (lsData.isNotEmpty()) {
                                Text("💾 LocalStorage:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                lsData.forEach { (key, value) ->
                                    DataRow(key.removePrefix("ls_"), value)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            if (ssData.isNotEmpty()) {
                                Text("📦 SessionStorage:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                ssData.forEach { (key, value) ->
                                    DataRow(key.removePrefix("ss_"), value)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            if (otherData.isNotEmpty()) {
                                Text("🔧 Другие:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                otherData.forEach { (key, value) ->
                                    DataRow(key, value)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCookiesDialog = false }) {
                        Text("Закрыть")
                    }
                }
            )
        }
        
        // Диалог ручного ввода
        if (showManualDialog) {
            AlertDialog(
                onDismissRequest = { showManualDialog = false },
                title = { Text("Ввод данных вручную") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = manualFsid,
                            onValueChange = { manualFsid = it },
                            label = { Text("FSID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = manualDeviceId,
                            onValueChange = { manualDeviceId = it },
                            label = { Text("Device ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text(
                            "Как найти fsid:\n" +
                            "1. Откройте инструменты разработчика (F12)\n" +
                            "2. Перейдите на вкладку Network\n" +
                            "3. Найдите запрос к /session/info\n" +
                            "4. В теле запроса будет поле fsid\n\n" +
                            "Или попробуйте значения:\n" +
                            "- spid из кук\n" +
                            "- значение из localStorage с ключом fsid",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (manualFsid.isNotBlank() && manualDeviceId.isNotBlank()) {
                                onAuthSuccess(manualFsid, manualDeviceId)
                                showManualDialog = false
                            }
                        }
                    ) {
                        Text("Сохранить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}

@Composable
fun DataRow(key: String, value: String) {
    val displayValue = if (value.length > 40) 
        value.take(40) + "..." 
    else 
        value
    
    val isFsid = key.lowercase().contains("fsid") || 
                key.lowercase().contains("session") ||
                key.lowercase().contains("spid")
    val isDeviceId = key.lowercase().contains("device")
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isFsid -> MaterialTheme.colorScheme.primaryContainer
                isDeviceId -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                key,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Text(
                displayValue,
                fontSize = 10.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}

// Вспомогательная функция
private inline fun <T, R> Iterable<T>.firstNotNullOfOrNull(transform: (T) -> R?): R? {
    for (element in this) {
        val result = transform(element)
        if (result != null) return result
    }
    return null
}