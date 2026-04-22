// WebViewAuthScreen.kt
package com.example.fonbetbot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    var apiResponse by remember { mutableStateOf("") }
    var showApiResponseDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    
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
        val fsidKeys = listOf(
            "fsid", "FSID", "_fsid", "fonbet_fsid", 
            "fonbet_session", "session_id", "sid", "SessionId",
            "spid", "spsc", "PHPSESSID", "connect.sid"
        )
        
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
    
    // Функция для проверки ответа API
    fun checkApiResponse() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var result = {};
                
                // Перехватываем fetch запросы
                var originalFetch = window.fetch;
                window.fetch = function() {
                    return originalFetch.apply(this, arguments)
                        .then(function(response) {
                            var clonedResponse = response.clone();
                            if (arguments[0] && arguments[0].includes('session/info')) {
                                clonedResponse.text().then(function(body) {
                                    result['fetch_response'] = body;
                                    window._apiResponse = body;
                                    console.log('Fetch response:', body);
                                });
                            }
                            return response;
                        });
                };
                
                // Перехватываем XMLHttpRequest
                var originalXHROpen = XMLHttpRequest.prototype.open;
                var originalXHRSend = XMLHttpRequest.prototype.send;
                
                XMLHttpRequest.prototype.open = function() {
                    this._url = arguments[1];
                    return originalXHROpen.apply(this, arguments);
                };
                
                XMLHttpRequest.prototype.send = function() {
                    if (this._url && this._url.includes('session/info')) {
                        this.addEventListener('load', function() {
                            result['xhr_response'] = this.responseText;
                            window._apiResponse = this.responseText;
                            console.log('XHR response:', this.responseText);
                        });
                    }
                    return originalXHRSend.apply(this, arguments);
                };
                
                return JSON.stringify(result);
            })();
        """.trimIndent()) { jsResult ->
            android.util.Log.d("WebViewAuth", "JS interceptor installed: $jsResult")
        }
    }
    
    // Функция для прямого запроса к API
    fun makeDirectApiCall() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var fsid = '';
                var deviceId = '';
                
                // Ищем fsid и deviceId в куках и localStorage
                var cookies = document.cookie;
                cookies.split(';').forEach(function(cookie) {
                    var parts = cookie.trim().split('=');
                    if (parts[0] === 'spid' || parts[0] === 'fsid') {
                        fsid = decodeURIComponent(parts.slice(1).join('='));
                    }
                });
                
                try {
                    deviceId = localStorage.getItem('devicedl') || '';
                } catch(e) {}
                
                // Формируем тело запроса
                var body = JSON.stringify({
                    clientId: 18845703,
                    fsid: fsid,
                    sysId: 21,
                    deviceId: deviceId
                });
                
                console.log('API Request body:', body);
                
                fetch('https://clientsapi-lb51-w.bk6bba-resources.com/session/info', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    credentials: 'include',
                    body: body
                })
                .then(r => r.text())
                .then(d => {
                    console.log('Direct API call response:', d);
                    window._apiResponse = d;
                })
                .catch(e => {
                    console.error('Error:', e);
                    window._apiResponse = 'Error: ' + e.message;
                });
                
                return 'Request sent with fsid: ' + fsid + ', deviceId: ' + deviceId;
            })();
        """.trimIndent()) { result ->
            Toast.makeText(context, "Запрос отправлен: $result", Toast.LENGTH_LONG).show()
            
            // Ждём и получаем ответ
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                webViewRef?.evaluateJavascript("window._apiResponse || ''") { response ->
                    if (response != "null" && response.isNotEmpty() && response != "undefined") {
                        try {
                            // Убираем кавычки в начале и конце если есть
                            val cleanResponse = response.trim('"')
                            val json = JSONObject(cleanResponse)
                            apiResponse = json.toString(2)
                        } catch (e: Exception) {
                            apiResponse = response.trim('"')
                        }
                    } else {
                        apiResponse = "Ответ не получен. Проверьте консоль."
                    }
                }
            }, 3000)
        }
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
                    
                    var fsid = ""
                    var deviceId = ""
                    
                    val fsidPatterns = listOf("fsid", "FSID", "session", "token", "auth", "spid")
                    for ((key, value) in allData) {
                        val lowerKey = key.lowercase()
                        
                        if (fsid.isEmpty()) {
                            for (pattern in fsidPatterns) {
                                if (lowerKey.contains(pattern) && value.length in 20..200) {
                                    fsid = value
                                    break
                                }
                            }
                        }
                        
                        if (deviceId.isEmpty() && lowerKey.contains("device")) {
                            deviceId = value
                        }
                    }
                    
                    if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                        onAuthSuccess(fsid, deviceId)
                    } else if (fsid.isNotEmpty()) {
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
                        checkApiResponse()
                    }) {
                        Icon(Icons.Default.Check, "Проверить")
                    }
                    IconButton(onClick = { 
                        checkApiResponse()
                        showApiResponseDialog = true 
                    }) {
                        Icon(Icons.Default.Build, "API ответ")
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
                factory = { ctx ->
                    WebView(ctx).apply {
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
                        
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        webViewClient = object : WebViewClient() {
                            
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                request?.url?.toString()?.let { url ->
                                    if (url.contains("session/info")) {
                                        android.util.Log.d("WebViewAuth", "Intercepted request: $url")
                                        request.requestHeaders?.forEach { (key, value) ->
                                            android.util.Log.d("WebViewAuth", "Request Header: $key: $value")
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                            
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                
                                url?.let { currentUrl ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        val cookies = getAllCookies(currentUrl)
                                        foundCookies = cookies
                                        
                                        val (fsid, deviceId) = extractAuthData(cookies)
                                        if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                                            onAuthSuccess(fsid, deviceId)
                                        }
                                        
                                        extractViaJavaScript()
                                        checkApiResponse()
                                    }, 2000)
                                }
                            }
                            
                            override fun onLoadResource(view: WebView?, url: String?) {
                                url?.let {
                                    if (it.contains("session") || it.contains("auth") || it.contains("token") || it.contains("info")) {
                                        android.util.Log.d("WebViewAuth", "Loading resource: $it")
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
                                checkApiResponse()
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
                                "Найдено данных: ${foundCookies.size}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // Диалог с ответом API
        if (showApiResponseDialog) {
            AlertDialog(
                onDismissRequest = { showApiResponseDialog = false },
                title = { 
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📡 Ответ session/info")
                        Row {
                            TextButton(
                                onClick = {
                                    if (apiResponse.isNotEmpty()) {
                                        val clip = ClipData.newPlainText("API Response", apiResponse)
                                        clipboardManager.setPrimaryClip(clip)
                                        Toast.makeText(context, "Ответ скопирован", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Text("Копировать")
                            }
                            TextButton(
                                onClick = {
                                    makeDirectApiCall()
                                }
                            ) {
                                Text("🔄")
                            }
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        if (apiResponse.isEmpty()) {
                            Text("Ответ API не получен")
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    makeDirectApiCall()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("🚀 Выполнить запрос к API")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Нажмите кнопку чтобы отправить запрос к session/info",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item {
                                    Text(
                                        apiResponse,
                                        fontSize = 11.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showApiResponseDialog = false }) {
                        Text("Закрыть")
                    }
                }
            )
        }
        
        // Диалог с найденными данными
        if (showCookiesDialog) {
            AlertDialog(
                onDismissRequest = { showCookiesDialog = false },
                title = { 
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📋 Найденные данные")
                        TextButton(
                            onClick = {
                                val allData = foundCookies.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                                val clip = ClipData.newPlainText("All Data", allData)
                                clipboardManager.setPrimaryClip(clip)
                                Toast.makeText(context, "Все данные скопированы", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Копировать всё", fontSize = 12.sp)
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (foundCookies.isEmpty()) {
                            Text("Данные не найдены")
                        } else {
                            val cookieData = foundCookies.filter { it.key.startsWith("cookie_") }
                            val lsData = foundCookies.filter { it.key.startsWith("ls_") }
                            val ssData = foundCookies.filter { it.key.startsWith("ss_") }
                            val otherData = foundCookies.filter { 
                                !it.key.startsWith("cookie_") && 
                                !it.key.startsWith("ls_") && 
                                !it.key.startsWith("ss_") 
                            }
                            
                            val potentialFsid = mutableListOf<Pair<String, String>>()
                            val potentialDeviceId = mutableListOf<Pair<String, String>>()
                            
                            foundCookies.forEach { (key, value) ->
                                val lowerKey = key.lowercase()
                                if (lowerKey.contains("fsid") || lowerKey.contains("session") || 
                                    lowerKey.contains("spid") || lowerKey.contains("token")) {
                                    potentialFsid.add(key to value)
                                }
                                if (lowerKey.contains("device") || lowerKey.contains("devicedl")) {
                                    potentialDeviceId.add(key to value)
                                }
                            }
                            
                            // Возможные FSID
                            if (potentialFsid.isNotEmpty()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("🔑 Возможные FSID:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        potentialFsid.forEach { (key, value) ->
                                            DataRowWithCopy(
                                                key = key,
                                                value = value,
                                                onCopy = {
                                                    val clip = ClipData.newPlainText(key, value)
                                                    clipboardManager.setPrimaryClip(clip)
                                                    Toast.makeText(context, "$key скопирован", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            // Возможные DeviceID
                            if (potentialDeviceId.isNotEmpty()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("📱 Возможные DeviceID:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        potentialDeviceId.forEach { (key, value) ->
                                            DataRowWithCopy(
                                                key = key,
                                                value = value,
                                                onCopy = {
                                                    val clip = ClipData.newPlainText(key, value)
                                                    clipboardManager.setPrimaryClip(clip)
                                                    Toast.makeText(context, "$key скопирован", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            // Куки
                            if (cookieData.isNotEmpty()) {
                                Text("🍪 Куки:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                cookieData.take(5).forEach { (key, value) ->
                                    SimpleDataRow(key.removePrefix("cookie_"), value)
                                }
                                if (cookieData.size > 5) {
                                    Text("... и ещё ${cookieData.size - 5}", fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            // LocalStorage
                            if (lsData.isNotEmpty()) {
                                Text("💾 LocalStorage:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                lsData.take(5).forEach { (key, value) ->
                                    SimpleDataRow(key.removePrefix("ls_"), value)
                                }
                                if (lsData.size > 5) {
                                    Text("... и ещё ${lsData.size - 5}", fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            // SessionStorage
                            if (ssData.isNotEmpty()) {
                                Text("📦 SessionStorage:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                ssData.take(5).forEach { (key, value) ->
                                    SimpleDataRow(key.removePrefix("ss_"), value)
                                }
                                if (ssData.size > 5) {
                                    Text("... и ещё ${ssData.size - 5}", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { showCookiesDialog = false }) {
                            Text("Закрыть")
                        }
                        TextButton(onClick = { 
                            showCookiesDialog = false
                            showManualDialog = true 
                        }) {
                            Text("Ввести вручную")
                        }
                    }
                }
            )
        }
        
        // Диалог ручного ввода
        if (showManualDialog) {
            LaunchedEffect(Unit) {
                if (manualFsid.isEmpty() && manualDeviceId.isEmpty()) {
                    var foundFsid = ""
                    var foundDeviceId = ""
                    
                    foundCookies.forEach { (key, value) ->
                        val lowerKey = key.lowercase()
                        if (foundFsid.isEmpty() && 
                            (lowerKey.contains("fsid") || lowerKey.contains("spid") || lowerKey.contains("session"))) {
                            foundFsid = value
                        }
                        if (foundDeviceId.isEmpty() && 
                            (lowerKey.contains("device") || lowerKey.contains("devicedl"))) {
                            foundDeviceId = value
                        }
                    }
                    
                    manualFsid = foundFsid
                    manualDeviceId = foundDeviceId
                }
            }
            
            AlertDialog(
                onDismissRequest = { showManualDialog = false },
                title = { Text("📝 Ввод данных вручную") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = manualFsid,
                            onValueChange = { manualFsid = it },
                            label = { Text("FSID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                if (manualFsid.isNotEmpty()) {
                                    IconButton(onClick = {
                                        val clip = ClipData.newPlainText("FSID", manualFsid)
                                        clipboardManager.setPrimaryClip(clip)
                                        Toast.makeText(context, "FSID скопирован", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.ContentCopy, "Копировать")
                                    }
                                }
                            }
                        )
                        
                        OutlinedTextField(
                            value = manualDeviceId,
                            onValueChange = { manualDeviceId = it },
                            label = { Text("Device ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                if (manualDeviceId.isNotEmpty()) {
                                    IconButton(onClick = {
                                        val clip = ClipData.newPlainText("DeviceID", manualDeviceId)
                                        clipboardManager.setPrimaryClip(clip)
                                        Toast.makeText(context, "Device ID скопирован", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.ContentCopy, "Копировать")
                                    }
                                }
                            }
                        )
                        
                        Text(
                            "Нажмите на поле чтобы редактировать.\n" +
                            "Значения автоматически подставлены из найденных данных.",
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
                            } else {
                                Toast.makeText(context, "Заполните оба поля", Toast.LENGTH_SHORT).show()
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
fun DataRowWithCopy(
    key: String,
    value: String,
    onCopy: () -> Unit
) {
    val displayValue = if (value.length > 50) 
        value.take(50) + "..." 
    else 
        value
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    "Копировать",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun SimpleDataRow(key: String, value: String) {
    val displayValue = if (value.length > 30) 
        value.take(30) + "..." 
    else 
        value
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            key,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            displayValue,
            fontSize = 10.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

private inline fun <T, R> Iterable<T>.firstNotNullOfOrNull(transform: (T) -> R?): R? {
    for (element in this) {
        val result = transform(element)
        if (result != null) return result
    }
    return null
}