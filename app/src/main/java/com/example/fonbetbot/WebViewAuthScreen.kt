package com.example.fonbetbot

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.webview.WebView
import com.google.accompanist.webview.rememberWebViewState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewAuthScreen(
    onAuthSuccess: (fsid: String, deviceId: String) -> Unit,
    onBack: () -> Unit
) {
    val authUrl = "https://your-site.com/login" // ЗАМЕНИТЕ НА РЕАЛЬНЫЙ URL
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(authUrl) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🔐 Авторизация") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
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
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            setWebContentsDebuggingEnabled(true)
                        }
                        
                        // Настройка куки
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                currentUrl = url ?: authUrl
                                
                                // Проверяем, не попали ли мы на страницу после авторизации
                                // ЗАМЕНИТЕ УСЛОВИЯ НА РЕАЛЬНЫЕ URL ПОСЛЕ ЛОГИНА
                                if (url?.contains("/profile") == true || 
                                    url?.contains("/dashboard") == true ||
                                    url?.contains("/account") == true) {
                                    
                                    extractCookies(url) { fsid, deviceId ->
                                        if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                                            onAuthSuccess(fsid, deviceId)
                                        } else {
                                            // Пробуем через JavaScript
                                            extractCookiesViaJavaScript(view) { jsFsid, jsDeviceId ->
                                                if (jsFsid.isNotEmpty() && jsDeviceId.isNotEmpty()) {
                                                    onAuthSuccess(jsFsid, jsDeviceId)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        loadUrl(authUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            
            if (errorMessage != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        errorMessage!!,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

private fun extractCookies(url: String, callback: (String, String) -> Unit) {
    try {
        val cookieManager = CookieManager.getInstance()
        val cookieString = cookieManager.getCookie(url)
        
        if (cookieString != null && cookieString.isNotEmpty()) {
            val cookies = cookieString.split("; ").associate { cookie ->
                val parts = cookie.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }
            
            val fsid = cookies["fsid"] ?: cookies["FSID"] ?: ""
            val deviceId = cookies["deviceid"] ?: cookies["deviceId"] ?: cookies["device_id"] ?: ""
            
            callback(fsid, deviceId)
        } else {
            callback("", "")
        }
    } catch (e: Exception) {
        callback("", "")
    }
}

private fun extractCookiesViaJavaScript(webView: WebView, callback: (String, String) -> Unit) {
    val jsCode = """
        (function() {
            var cookies = document.cookie;
            var result = {};
            cookies.split(';').forEach(function(cookie) {
                var parts = cookie.trim().split('=');
                if (parts.length >= 2) {
                    result[parts[0]] = parts[1];
                }
            });
            return JSON.stringify(result);
        })();
    """.trimIndent()

    webView.evaluateJavascript(jsCode) { result ->
        try {
            if (result != "null" && result.isNotEmpty()) {
                val jsonObject = org.json.JSONObject(result)
                
                val fsid = jsonObject.optString("fsid", "")
                val deviceId = jsonObject.optString("deviceid", "")
                    .ifEmpty { jsonObject.optString("deviceId", "") }
                    .ifEmpty { jsonObject.optString("device_id", "") }
                
                callback(fsid, deviceId)
            } else {
                callback("", "")
            }
        } catch (e: Exception) {
            callback("", "")
        }
    }
}