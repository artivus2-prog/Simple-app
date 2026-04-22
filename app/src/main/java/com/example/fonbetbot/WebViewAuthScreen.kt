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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

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
            "fonbet_session", "session_id", "sid", "SessionId"
        )
        
        // Расширенный список для DeviceID
        val deviceIdKeys = listOf(
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
                // Показываем диалог с найденными куками для отладки
                showCookiesDialog = true
            }
        }
        isCheckingAuth = false
    }
    
    // Функция для извлечения через JavaScript
    fun extractViaJavaScript() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var cookies = document.cookie;
                var result = {};
                cookies.split(';').forEach(function(cookie) {
                    var parts = cookie.trim().split('=');
                    if (parts.length >= 2) {
                        result[parts[0]] = decodeURIComponent(parts.slice(1).join('='));
                    }
                });
                
                // Также пробуем получить из localStorage
                try {
                    result['_localStorage_fsid'] = localStorage.getItem('fsid');
                    result['_localStorage_deviceId'] = localStorage.getItem('deviceId');
                } catch(e) {}
                
                return JSON.stringify(result);
            })();
        """.trimIndent()) { result ->
            try {
                if (result != "null" && result.isNotEmpty()) {
                    val jsonObject = org.json.JSONObject(result)
                    val jsCookies = mutableMapOf<String, String>()
                    
                    val keys = jsonObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        jsCookies[key] = jsonObject.getString(key)
                    }
                    
                    foundCookies = jsCookies
                    val (fsid, deviceId) = extractAuthData(jsCookies)
                    
                    if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                        onAuthSuccess(fsid, deviceId)
                    }
                }
            } catch (e: Exception) {
                // Игнорируем
            }
        }
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
                            
                            // Важно для сохранения кук
                            setAppCacheEnabled(true)
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        }
                        
                        // Настройка CookieManager
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        // Очищаем старые куки (опционально)
                        // cookieManager.removeAllCookies(null)
                        // cookieManager.flush()
                        
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
                                    }, 1500)
                                }
                            }
                            
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                // Отслеживаем редиректы
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
                title = { Text("Найденные куки") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (foundCookies.isEmpty()) {
                            Text("Куки не найдены")
                        } else {
                            foundCookies.forEach { (key, value) ->
                                val displayValue = if (value.length > 30) 
                                    value.take(30) + "..." 
                                else 
                                    value
                                
                                val isFsid = key.lowercase().contains("fsid") || 
                                            key.lowercase().contains("session")
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
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            displayValue,
                                            fontSize = 10.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
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
                            "Найдите эти значения в куках сайта fon.bet после авторизации",
                            fontSize = 12.sp,
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

// Вспомогательная функция
private inline fun <T, R> Iterable<T>.firstNotNullOfOrNull(transform: (T) -> R?): R? {
    for (element in this) {
        val result = transform(element)
        if (result != null) return result
    }
    return null
}