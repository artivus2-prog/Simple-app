// WebViewAuthScreen.kt
package com.example.fonbetbot

import android.annotation.SuppressLint
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
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var foundData by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    
    val context = LocalContext.current
    
    // Функция для извлечения данных
    fun extractAllData() {
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
                
                // Ищем FSID в заголовках через перехват
                if (window._capturedFsid) result['headerApi_FSID'] = window._capturedFsid;
                
                return JSON.stringify(result);
            })();
        """.trimIndent()) { result ->
            try {
                if (result != "null" && result.isNotEmpty()) {
                    val jsonObject = JSONObject(result.trim('"'))
                    val allData = mutableMapOf<String, String>()
                    
                    val keys = jsonObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = jsonObject.optString(key, "")
                        if (value.isNotEmpty() && value != "null") {
                            allData[key] = value
                        }
                    }
                    
                    foundData = allData
                    
                    // Автоматически ищем правильные значения
                    var fsid = ""
                    var deviceId = ""
                    
                    // Приоритет: headerApi_FSID
                    if (allData.containsKey("headerApi_FSID")) {
                        fsid = allData["headerApi_FSID"] ?: ""
                    }
                    
                    // Если нет headerApi_FSID, ищем другие
                    if (fsid.isEmpty()) {
                        for ((key, value) in allData) {
                            val lowerKey = key.lowercase()
                            if (lowerKey.contains("fsid") && value.length in 20..100) {
                                fsid = value
                                break
                            }
                        }
                    }
                    
                    // Ищем DeviceID - приоритет local.deviceld
                    if (allData.containsKey("ls_deviceld")) {
                        deviceId = allData["ls_deviceld"] ?: ""
                    } else if (allData.containsKey("local.deviceld")) {
                        deviceId = allData["local.deviceld"] ?: ""
                    } else {
                        for ((key, value) in allData) {
                            val lowerKey = key.lowercase()
                            if (lowerKey.contains("device") && value.length in 20..100) {
                                deviceId = value
                                break
                            }
                        }
                    }
                    
                    // Если нашли оба - авторизуемся автоматически
                    if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                        onAuthSuccess(fsid, deviceId)
                    }
                }
            } catch (e: Exception) {
                // Игнорируем
            }
        }
    }
    
    // Устанавливаем перехват запросов для захвата FSID
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
                            if (body.fsid) {
                                window._capturedFsid = body.fsid;
                                console.log('Captured FSID:', body.fsid);
                            }
                        } catch(e) {}
                    }
                    
                    return originalFetch.apply(this, arguments);
                };
                
                // Перехватываем XMLHttpRequest
                var originalXHROpen = XMLHttpRequest.prototype.open;
                var originalXHRSend = XMLHttpRequest.prototype.send;
                var originalXHRSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
                
                XMLHttpRequest.prototype.open = function(method, url) {
                    this._url = url;
                    this._headers = {};
                    return originalXHROpen.apply(this, arguments);
                };
                
                XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                    this._headers[name] = value;
                    return originalXHRSetRequestHeader.apply(this, arguments);
                };
                
                XMLHttpRequest.prototype.send = function(body) {
                    if (this._url && this._url.includes('session/info')) {
                        try {
                            var jsonBody = JSON.parse(body);
                            if (jsonBody.fsid) {
                                window._capturedFsid = jsonBody.fsid;
                                console.log('Captured FSID from XHR:', jsonBody.fsid);
                            }
                        } catch(e) {}
                    }
                    return originalXHRSend.apply(this, arguments);
                };
                
                console.log('Interceptor installed');
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
                        injectInterceptor()
                        extractAllData()
                        Toast.makeText(context, "Проверка авторизации...", Toast.LENGTH_SHORT).show()
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
                                
                                // Устанавливаем перехватчик
                                injectInterceptor()
                                
                                url?.let { currentUrl ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        extractAllData()
                                    }, 2000)
                                }
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
            
            // Подсказка внизу
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
                            "1. Войдите в аккаунт Фонбет\n2. Нажмите ✓ для авторизации",
                            fontSize = 14.sp
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Button(
                            onClick = {
                                extractAllData()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🔍 ПРОВЕРИТЬ АВТОРИЗАЦИЮ")
                        }
                        
                        if (foundData.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Показываем статус
                            val hasFsid = foundData.containsKey("headerApi_FSID") || 
                                         foundData.keys.any { it.lowercase().contains("fsid") }
                            val hasDeviceId = foundData.containsKey("ls_deviceld") || 
                                             foundData.keys.any { it.lowercase().contains("device") }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Text(
                                    "FSID: ${if (hasFsid) "✅" else "❌"}",
                                    fontSize = 12.sp
                                )
                                Text(
                                    "DeviceID: ${if (hasDeviceId) "✅" else "❌"}",
                                    fontSize = 12.sp
                                )
                            }
                            
                            if (hasFsid && hasDeviceId) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "✅ Данные найдены! Авторизация...",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}