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
    
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    
    // Функция для извлечения данных
    fun extractAllData() {
        isChecking = true
        
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
                
                // Захваченный FSID из перехватчика
                if (window._capturedFsid) result['headerApi_FSID'] = window._capturedFsid;
                
                return JSON.stringify(result);
            })();
        """.trimIndent()) { result ->
            try {
                if (result != "null" && result.isNotEmpty()) {
                    val jsonObject = JSONObject(result.trim('"'))
                    
                    var fsid = ""
                    var deviceId = ""
                    
                    // Приоритет: headerApi_FSID
                    if (jsonObject.has("headerApi_FSID")) {
                        fsid = jsonObject.getString("headerApi_FSID")
                    }
                    
                    // Если нет headerApi_FSID, ищем другие
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
                    
                    // Ищем DeviceID - приоритет ls_deviceld
                    if (jsonObject.has("ls_deviceld")) {
                        deviceId = jsonObject.getString("ls_deviceld")
                    } else if (jsonObject.has("local.deviceld")) {
                        deviceId = jsonObject.getString("local.deviceld")
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
                    
                    foundFsid = fsid
                    foundDeviceId = deviceId
                    manualFsid = fsid
                    manualDeviceId = deviceId
                    
                    // Если нашли оба - авторизуемся автоматически
                    if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                        onAuthSuccess(fsid, deviceId)
                    } else {
                        // Показываем диалог ручного ввода
                        showManualDialog = true
                    }
                } else {
                    foundFsid = ""
                    foundDeviceId = ""
                    showManualDialog = true
                }
            } catch (e: Exception) {
                foundFsid = ""
                foundDeviceId = ""
                showManualDialog = true
            }
            isChecking = false
        }
    }
    
    // Устанавливаем перехват запросов для захвата FSID
    fun injectInterceptor() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var originalFetch = window.fetch;
                window.fetch = function() {
                    var url = arguments[0];
                    var options = arguments[1] || {};
                    
                    if (url && url.includes('session/info')) {
                        try {
                            var body = JSON.parse(options.body);
                            if (body.fsid) {
                                window._capturedFsid = body.fsid;
                            }
                        } catch(e) {}
                    }
                    
                    return originalFetch.apply(this, arguments);
                };
                
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
                            if (jsonBody.fsid) {
                                window._capturedFsid = jsonBody.fsid;
                            }
                        } catch(e) {}
                    }
                    return originalXHRSend.apply(this, arguments);
                };
            })();
        """.trimIndent()) { }
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
                            "1. Войдите в аккаунт Фонбет\n2. Нажмите ✓ для проверки",
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
                        
                        TextButton(
                            onClick = { showManualDialog = true }
                        ) {
                            Text("✏️ Ввести данные вручную")
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
                        Text("Поиск данных авторизации...")
                    }
                }
            }
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
                                    focusedContainerColor = if (foundFsid.isNotEmpty() && manualFsid == foundFsid) 
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else 
                                        MaterialTheme.colorScheme.surface
                                )
                            )
                            if (foundFsid.isNotEmpty()) {
                                Text(
                                    "Найдено: ${foundFsid.take(30)}...",
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
                                    focusedContainerColor = if (foundDeviceId.isNotEmpty() && manualDeviceId == foundDeviceId) 
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else 
                                        MaterialTheme.colorScheme.surface
                                )
                            )
                            if (foundDeviceId.isNotEmpty()) {
                                Text(
                                    "Найдено: ${foundDeviceId.take(30)}...",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        
                        // Статус
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column {
                                Text("FSID:", fontSize = 12.sp)
                                Text(
                                    if (foundFsid.isNotEmpty()) "✅ Найден" else "❌ Не найден",
                                    fontSize = 12.sp,
                                    color = if (foundFsid.isNotEmpty()) Color(0xFF4CAF50) else Color(0xFFF44336)
                                )
                            }
                            Column {
                                Text("DeviceID:", fontSize = 12.sp)
                                Text(
                                    if (foundDeviceId.isNotEmpty()) "✅ Найден" else "❌ Не найден",
                                    fontSize = 12.sp,
                                    color = if (foundDeviceId.isNotEmpty()) Color(0xFF4CAF50) else Color(0xFFF44336)
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
                            "Введите FSID и Device ID, полученные после авторизации на сайте Фонбет.",
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