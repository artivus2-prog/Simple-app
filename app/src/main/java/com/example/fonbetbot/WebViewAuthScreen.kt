// WebViewAuthScreen.kt
package com.example.fonbetbot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var foundFsid by remember { mutableStateOf("") }
    var foundDeviceId by remember { mutableStateOf("") }
    var manualFsid by remember { mutableStateOf("") }
    var manualDeviceId by remember { mutableStateOf("") }
    var showManualDialog by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    var capturedRequest by remember { mutableStateOf<Map<String, String>?>(null) }
    var showRequestDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    
    // Устанавливаем перехват запросов для захвата ВСЕХ данных из тела запроса
    fun injectInterceptor() {
        webViewRef?.evaluateJavascript("""
            (function() {
                // Перехватываем fetch
                var originalFetch = window.fetch;
                window.fetch = function() {
                    var url = arguments[0];
                    var options = arguments[1] || {};
                    
                    if (url && url.includes('session/info')) {
                        try {
                            var body = JSON.parse(options.body);
                            window._capturedRequest = JSON.stringify(body);
                            window._capturedFsid = body.fsid;
                            window._capturedDeviceId = body.deviceId;
                            window._capturedClientId = body.clientId;
                            window._capturedSysId = body.sysId;
                            console.log('Captured request:', body);
                        } catch(e) {}
                    }
                    
                    return originalFetch.apply(this, arguments);
                };
                
                // Перехватываем XMLHttpRequest
                var originalXHROpen = XMLHttpRequest.prototype.open;
                var originalXHRSend = XMLHttpRequest.prototype.send;
                
                XMLHttpRequest.prototype.open = function(method, url) {
                    this._url = url;
                    return originalXHROpen.apply(this, arguments);
                };
                
                XMLHttpRequest.prototype.send = function(body) {
                    if (this._url && this._url.includes('session/info')) {
                        try {
                            var jsonBody = JSON.parse(body);
                            window._capturedRequest = JSON.stringify(jsonBody);
                            window._capturedFsid = jsonBody.fsid;
                            window._capturedDeviceId = jsonBody.deviceId;
                            window._capturedClientId = jsonBody.clientId;
                            window._capturedSysId = jsonBody.sysId;
                            console.log('Captured XHR request:', jsonBody);
                        } catch(e) {}
                    }
                    return originalXHRSend.apply(this, arguments);
                };
                
                console.log('Interceptor installed - waiting for session/info request');
            })();
        """.trimIndent()) { }
    }
    
    // Функция для получения захваченного запроса
    fun getCapturedRequest() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var result = {};
                result.fsid = window._capturedFsid || '';
                result.deviceId = window._capturedDeviceId || '';
                result.clientId = window._capturedClientId || '';
                result.sysId = window._capturedSysId || '';
                result.fullRequest = window._capturedRequest || '';
                return JSON.stringify(result);
            })();
        """.trimIndent()) { result ->
            try {
                if (result != "null" && result.isNotEmpty()) {
                    val json = JSONObject(result.trim('"'))
                    val fsid = json.optString("fsid", "")
                    val deviceId = json.optString("deviceId", "")
                    val clientId = json.optString("clientId", "")
                    val sysId = json.optString("sysId", "")
                    
                    if (fsid.isNotEmpty() || deviceId.isNotEmpty()) {
                        capturedRequest = mapOf(
                            "fsid" to fsid,
                            "deviceId" to deviceId,
                            "clientId" to clientId,
                            "sysId" to sysId
                        )
                        foundFsid = fsid
                        foundDeviceId = deviceId
                        manualFsid = fsid
                        manualDeviceId = deviceId
                    }
                }
            } catch (e: Exception) {
                // Игнорируем
            }
        }
    }
    
    // Функция для извлечения всех данных
    fun extractAllData() {
        isChecking = true
        
        // Сначала получаем захваченный запрос
        getCapturedRequest()
        
        // Затем ищем в куках и storage
        webViewRef?.evaluateJavascript("""
            (function() {
                var result = {};
                
                // Куки
                document.cookie.split(';').forEach(function(cookie) {
                    var parts = cookie.trim().split('=');
                    if (parts.length >= 2) {
                        result['cookie_' + parts[0]] = decodeURIComponent(parts.slice(1).join('='));
                    }
                });
                
                // localStorage
                try {
                    for (var i = 0; i < localStorage.length; i++) {
                        var key = localStorage.key(i);
                        result['ls_' + key] = localStorage.getItem(key);
                    }
                } catch(e) {}
                
                // sessionStorage
                try {
                    for (var i = 0; i < sessionStorage.length; i++) {
                        var key = sessionStorage.key(i);
                        result['ss_' + key] = sessionStorage.getItem(key);
                    }
                } catch(e) {}
                
                // Захваченные данные
                if (window._capturedFsid) result['captured_fsid'] = window._capturedFsid;
                if (window._capturedDeviceId) result['captured_deviceId'] = window._capturedDeviceId;
                
                return JSON.stringify(result);
            })();
        """.trimIndent()) { result ->
            try {
                if (result != "null" && result.isNotEmpty()) {
                    val jsonObject = JSONObject(result.trim('"'))
                    
                    var fsid = foundFsid
                    var deviceId = foundDeviceId
                    
                    // Приоритет: захваченные из запроса
                    if (fsid.isEmpty() && jsonObject.has("captured_fsid")) {
                        fsid = jsonObject.getString("captured_fsid")
                    }
                    if (deviceId.isEmpty() && jsonObject.has("captured_deviceId")) {
                        deviceId = jsonObject.getString("captured_deviceId")
                    }
                    
                    // Если нет - ищем в других местах
                    if (fsid.isEmpty()) {
                        val keys = jsonObject.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val lowerKey = key.lowercase()
                            if (lowerKey.contains("fsid") || lowerKey.contains("spid")) {
                                val value = jsonObject.getString(key)
                                if (value.length in 20..100) {
                                    fsid = value
                                    break
                                }
                            }
                        }
                    }
                    
                    if (deviceId.isEmpty()) {
                        if (jsonObject.has("ls_deviceld")) {
                            deviceId = jsonObject.getString("ls_deviceld")
                        } else {
                            val keys = jsonObject.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val lowerKey = key.lowercase()
                                if (lowerKey.contains("device")) {
                                    val value = jsonObject.getString(key)
                                    if (value.length in 20..100) {
                                        deviceId = value
                                        break
                                    }
                                }
                            }
                        }
                    }
                    
                    foundFsid = fsid
                    foundDeviceId = deviceId
                    manualFsid = fsid
                    manualDeviceId = deviceId
                    
                    // Показываем диалог с найденными данными
                    showManualDialog = true
                } else {
                    showManualDialog = true
                }
            } catch (e: Exception) {
                showManualDialog = true
            }
            isChecking = false
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
                    IconButton(onClick = { 
                        showManualDialog = true
                    }) {
                        Icon(Icons.Default.Edit, "Ввести вручную")
                    }
                    IconButton(onClick = { 
                        showRequestDialog = true
                    }) {
                        Icon(Icons.Default.Info, "Запрос")
                    }
                    IconButton(onClick = { 
                        injectInterceptor()
                        extractAllData()
                    }) {
                        Icon(Icons.Default.Check, "Проверить")
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
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                injectInterceptor()
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
                            "1. Войдите в аккаунт Фонбет\n2. Дождитесь загрузки баланса\n3. Нажмите ✓",
                            fontSize = 14.sp
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Button(
                            onClick = {
                                injectInterceptor()
                                extractAllData()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isChecking
                        ) {
                            if (isChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (isChecking) "ПРОВЕРКА..." else "🔍 ПРОВЕРИТЬ АВТОРИЗАЦИЮ")
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(onClick = { showManualDialog = true }) {
                                Text("✏️ Вручную")
                            }
                            TextButton(onClick = { 
                                getCapturedRequest()
                                showRequestDialog = true 
                            }) {
                                Text("📡 Запрос")
                            }
                        }
                    }
                }
            }
            
            // Индикатор проверки
            if (isChecking) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Анализ запроса session/info...")
                    }
                }
            }
        }
        
        // Диалог с захваченным запросом
        if (showRequestDialog) {
            AlertDialog(
                onDismissRequest = { showRequestDialog = false },
                title = { 
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("📡 Запрос session/info")
                        if (capturedRequest != null) {
                            TextButton(
                                onClick = {
                                    val data = capturedRequest!!.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                                    val clip = ClipData.newPlainText("Request", data)
                                    clipboardManager.setPrimaryClip(clip)
                                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Копировать")
                            }
                        }
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (capturedRequest == null || capturedRequest!!.isEmpty()) {
                            Text("Запрос ещё не перехвачен.")
                            Text(
                                "Войдите в аккаунт и дождитесь загрузки баланса, затем нажмите 'Проверить'.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            capturedRequest!!.forEach { (key, value) ->
                                if (value.isNotEmpty()) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = when (key) {
                                                "fsid" -> MaterialTheme.colorScheme.primaryContainer
                                                "deviceId" -> MaterialTheme.colorScheme.secondaryContainer
                                                else -> MaterialTheme.colorScheme.surfaceVariant
                                            }
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                key,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                value,
                                                fontSize = 11.sp,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Button(
                                onClick = {
                                    capturedRequest?.let {
                                        manualFsid = it["fsid"] ?: ""
                                        manualDeviceId = it["deviceId"] ?: ""
                                    }
                                    showRequestDialog = false
                                    showManualDialog = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("✅ Использовать эти данные")
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Button(
                            onClick = {
                                getCapturedRequest()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("🔄 Обновить")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showRequestDialog = false }) {
                        Text("Закрыть")
                    }
                }
            )
        }
        
        // Диалог ручного ввода
        if (showManualDialog) {
            AlertDialog(
                onDismissRequest = { showManualDialog = false },
                title = { Text("📝 Данные авторизации") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Информация о источнике данных
                        if (capturedRequest != null && capturedRequest!!.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "✅ Данные из запроса session/info",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        "FSID и DeviceID получены из тела запроса к API",
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        
                        // FSID
                        Column {
                            Text(
                                "FSID:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = manualFsid,
                                onValueChange = { manualFsid = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 3,
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
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = if (foundFsid.isNotEmpty()) 
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else 
                                        MaterialTheme.colorScheme.surface
                                )
                            )
                            if (foundFsid.isNotEmpty()) {
                                Text(
                                    "Найдено: ${foundFsid.take(40)}...",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        
                        // DeviceID
                        Column {
                            Text(
                                "Device ID:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = manualDeviceId,
                                onValueChange = { manualDeviceId = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 3,
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
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = if (foundDeviceId.isNotEmpty()) 
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else 
                                        MaterialTheme.colorScheme.surface
                                )
                            )
                            if (foundDeviceId.isNotEmpty()) {
                                Text(
                                    "Найдено: ${foundDeviceId.take(40)}...",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        
                        // Кнопка "Использовать найденные"
                        if (foundFsid.isNotEmpty() && foundDeviceId.isNotEmpty()) {
                            Button(
                                onClick = {
                                    manualFsid = foundFsid
                                    manualDeviceId = foundDeviceId
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Text("📋 Использовать найденные значения")
                            }
                        }
                        
                        Divider()
                        
                        Text(
                            "Эти данные берутся из запроса к session/info.\n" +
                            "Если поля пустые, нажмите 'Запрос' чтобы увидеть перехваченные данные.",
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