// WebViewAuthScreen.kt - ПРОСТАЯ И РАБОЧАЯ ВЕРСИЯ
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
    var manualFsid by remember { mutableStateOf("") }
    var manualDeviceId by remember { mutableStateOf("") }
    var showManualDialog by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    
    // Простая функция поиска данных
    fun findAuthData() {
        isChecking = true
        
        webViewRef?.evaluateJavascript("""
            (function() {
                var result = { fsid: '', deviceId: '' };
                
                // Ищем в localStorage
                try {
                    for (var i = 0; i < localStorage.length; i++) {
                        var key = localStorage.key(i);
                        var value = localStorage.getItem(key);
                        
                        // Ищем FSID
                        if (!result.fsid && (key.toLowerCase().indexOf('fsid') >= 0 || key === 'headerApi.FSID')) {
                            result.fsid = value;
                        }
                        
                        // Ищем DeviceID
                        if (!result.deviceId && (key.toLowerCase().indexOf('device') >= 0 || key === 'devicedl')) {
                            result.deviceId = value;
                        }
                    }
                } catch(e) {}
                
                // Ищем в sessionStorage
                try {
                    for (var i = 0; i < sessionStorage.length; i++) {
                        var key = sessionStorage.key(i);
                        var value = sessionStorage.getItem(key);
                        
                        if (!result.fsid && key.toLowerCase().indexOf('fsid') >= 0) {
                            result.fsid = value;
                        }
                        if (!result.deviceId && key.toLowerCase().indexOf('device') >= 0) {
                            result.deviceId = value;
                        }
                    }
                } catch(e) {}
                
                // Ищем в куках
                var cookies = document.cookie.split(';');
                for (var i = 0; i < cookies.length; i++) {
                    var parts = cookies[i].trim().split('=');
                    if (parts.length >= 2) {
                        var key = parts[0];
                        var value = decodeURIComponent(parts.slice(1).join('='));
                        
                        if (!result.fsid && (key === 'spid' || key.toLowerCase().indexOf('fsid') >= 0)) {
                            result.fsid = value;
                        }
                        if (!result.deviceId && key.toLowerCase().indexOf('device') >= 0) {
                            result.deviceId = value;
                        }
                    }
                }
                
                return JSON.stringify(result);
            })();
        """.trimIndent()) { result ->
            isChecking = false
            
            try {
                if (result != "null" && result.isNotEmpty()) {
                    val json = JSONObject(result.trim('"'))
                    val fsid = json.optString("fsid", "")
                    val deviceId = json.optString("deviceId", "")
                    
                    manualFsid = fsid
                    manualDeviceId = deviceId
                    
                    // Если нашли оба - сразу сохраняем
                    if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                        onAuthSuccess(fsid, deviceId)
                    } else {
                        // Иначе показываем диалог для ручного ввода
                        showManualDialog = true
                    }
                } else {
                    showManualDialog = true
                }
            } catch (e: Exception) {
                showManualDialog = true
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
                        Icon(Icons.Default.Edit, "Ввести")
                    }
                    IconButton(onClick = { findAuthData() }) {
                        Icon(Icons.Default.Search, "Найти")
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
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        }
                        
                        // ВАЖНО: включаем куки
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
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
            
            // Кнопка внизу
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
                            "1. Войдите в аккаунт Фонбет\n2. Нажмите 'Найти' или 'Ввести'",
                            fontSize = 14.sp
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { findAuthData() },
                                modifier = Modifier.weight(1f),
                                enabled = !isChecking
                            ) {
                                if (isChecking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isChecking) "Поиск..." else "Найти")
                            }
                            
                            Button(
                                onClick = { showManualDialog = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Ввести")
                            }
                        }
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
                            Text("FSID:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = manualFsid,
                                onValueChange = { manualFsid = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 4,
                                trailingIcon = {
                                    if (manualFsid.isNotEmpty()) {
                                        IconButton(onClick = {
                                            val clip = ClipData.newPlainText("FSID", manualFsid)
                                            clipboardManager.setPrimaryClip(clip)
                                            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.ContentCopy, "Копировать")
                                        }
                                    }
                                }
                            )
                        }
                        
                        // DeviceID
                        Column {
                            Text("Device ID:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = manualDeviceId,
                                onValueChange = { manualDeviceId = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 4,
                                trailingIcon = {
                                    if (manualDeviceId.isNotEmpty()) {
                                        IconButton(onClick = {
                                            val clip = ClipData.newPlainText("DeviceID", manualDeviceId)
                                            clipboardManager.setPrimaryClip(clip)
                                            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.ContentCopy, "Копировать")
                                        }
                                    }
                                }
                            )
                        }
                        
                        Divider()
                        
                        Text(
                            "FSID обычно выглядит как: Y9TFJYN2zYIp1ox0IVlovj0e\n" +
                            "DeviceID обычно: 90A1A04A4F31CFA1631C7D3C06492A7C",
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