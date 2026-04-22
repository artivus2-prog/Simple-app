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
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewAuthScreen(
    onAuthSuccess: (fsid: String, deviceId: String) -> Unit,
    onBack: () -> Unit
) {
    val authUrl = "https://your-site.com/login" // ЗАМЕНИТЕ НА РЕАЛЬНЫЙ URL
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(authUrl) }
    
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
                        }
                        
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                currentUrl = url ?: authUrl
                                
                                if (url?.contains("/profile") == true || 
                                    url?.contains("/dashboard") == true) {
                                    
                                    val cookieString = cookieManager.getCookie(url)
                                    if (cookieString != null && cookieString.isNotEmpty()) {
                                        val cookies = cookieString.split("; ").associate { cookie ->
                                            val parts = cookie.split("=", limit = 2)
                                            parts[0] to (parts.getOrNull(1) ?: "")
                                        }
                                        
                                        val fsid = cookies["fsid"] ?: cookies["FSID"] ?: ""
                                        val deviceId = cookies["deviceid"] ?: cookies["deviceId"] ?: ""
                                        
                                        if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                                            onAuthSuccess(fsid, deviceId)
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
        }
    }
}