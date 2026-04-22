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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    var capturedFsid by remember { mutableStateOf("") }
    var capturedDeviceId by remember { mutableStateOf("") }
    var capturedRequest by remember { mutableStateOf("") }
    var capturedResponse by remember { mutableStateOf("") }
    var showCapturedDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    
    // Устанавливаем перехватчик ДО загрузки страницы
    fun setupInterceptor() {
        webViewRef?.evaluateJavascript("""
            (function() {
                console.log('=== SETTING UP INTERCEPTOR ===');
                
                // Перехватываем fetch
                var originalFetch = window.fetch;
                window.fetch = function(url, options) {
                    if (url && url.includes('session/info')) {
                        console.log('=== INTERCEPTED FETCH session/info ===');
                        console.log('URL:', url);
                        
                        if (options && options.body) {
                            try {
                                var body = JSON.parse(options.body);
                                console.log('Request body:', JSON.stringify(body));
                                window._capturedFsid = body.fsid;
                                window._capturedDeviceId = body.deviceId;
                                window._capturedRequest = JSON.stringify(body, null, 2);
                            } catch(e) {
                                console.log('Parse error:', e);
                            }
                        }
                        
                        // Перехватываем ответ
                        return originalFetch.apply(this, arguments).then(function(response) {
                            var clonedResponse = response.clone();
                            clonedResponse.text().then(function(body) {
                                console.log('Response body:', body);
                                window._capturedResponse = body;
                                try {
                                    var json = JSON.parse(body);
                                    window._capturedResponse = JSON.stringify(json, null, 2);
                                } catch(e) {}
                            });
                            return response;
                        });
                    }
                    return originalFetch.apply(this, arguments);
                };
                
                // Перехватываем XMLHttpRequest
                var XHR = XMLHttpRequest.prototype;
                var originalOpen = XHR.open;
                var originalSend = XHR.send;
                var originalSetRequestHeader = XHR.setRequestHeader;
                
                XHR.open = function(method, url) {
                    this._url = url;
                    this._method = method;
                    return originalOpen.apply(this, arguments);
                };
                
                XHR.send = function(body) {
                    if (this._url && this._url.includes('session/info')) {
                        console.log('=== INTERCEPTED XHR session/info ===');
                        console.log('URL:', this._url);
                        
                        if (body) {
                            try {
                                var jsonBody = JSON.parse(body);
                                console.log('Request body:', JSON.stringify(jsonBody));
                                window._capturedFsid = jsonBody.fsid;
                                window._capturedDeviceId = jsonBody.deviceId;
                                window._capturedRequest = JSON.stringify(jsonBody, null, 2);
                            } catch(e) {
                                console.log('Parse error:', e);
                            }
                        }
                        
                        // Перехватываем ответ
                        this.addEventListener('load', function() {
                            console.log('Response:', this.responseText);
                            window._capturedResponse = this.responseText;
                            try {
                                var json = JSON.parse(this.responseText);
                                window._capturedResponse = JSON.stringify(json, null, 2);
                            } catch(e) {}
                        });
                    }
                    return originalSend.apply(this, arguments);
                };
                
                console.log('=== INTERCEPTOR INSTALLED ===');
            })();
        """.trimIndent()) { }
    }
    
    // Функция для получения захваченных данных
    fun getCapturedData() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var result = {
                    fsid: window._capturedFsid || '',
                    deviceId: window._capturedDeviceId || '',
                    request: window._capturedRequest || '',
                    response: window._capturedResponse || ''
                };
                return JSON.stringify(result);
            })();
        """.trimIndent()) { result ->
            try {
                if (result != "null" && result.isNotEmpty()) {
                    val json = JSONObject(result.trim('"'))
                    capturedFsid = json.optString("fsid", "")
                    capturedDeviceId = json.optString("deviceId", "")
                    capturedRequest = json.optString("request", "")
                    capturedResponse = json.optString("response", "")
                    
                    if (capturedFsid.isNotEmpty() && capturedDeviceId.isNotEmpty()) {
                        // Нашли данные - показываем диалог
                        manualFsid = capturedFsid
                        manualDeviceId = capturedDeviceId
                        showCapturedDialog = true
                    } else {
                        Toast.makeText(context, "Данные ещё не перехвачены. Попробуйте обновить страницу.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, "Данные не найдены. Войдите в аккаунт.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
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
                    IconButton(onClick = { 
                        setupInterceptor()
                        getCapturedData()
                    }) {
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
                        
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                // Устанавливаем перехватчик после загрузки страницы
                                setupInterceptor()
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
                            "1. Войдите в аккаунт Фонбет\n2. Дождитесь загрузки баланса\n3. Нажмите 'Найти'",
                            fontSize = 14.sp
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { 
                                    setupInterceptor()
                                    getCapturedData()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Найти")
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
                        
                        // Показываем статус
                        if (capturedFsid.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "✅ FSID найден: ${capturedFsid.take(20)}...",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        
        // Диалог с захваченными данными
        if (showCapturedDialog) {
            AlertDialog(
                onDismissRequest = { showCapturedDialog = false },
                title = { 
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("✅ Данные перехвачены")
                        TextButton(
                            onClick = {
                                val data = "FSID: $capturedFsid\nDeviceID: $capturedDeviceId\n\nЗапрос:\n$capturedRequest\n\nОтвет:\n$capturedResponse"
                                val clip = ClipData.newPlainText("Data", data)
                                clipboardManager.setPrimaryClip(clip)
                                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("📋 Всё")
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // FSID
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("FSID:", fontWeight = FontWeight.Bold)
                                    IconButton(
                                        onClick = {
                                            val clip = ClipData.newPlainText("FSID", capturedFsid)
                                            clipboardManager.setPrimaryClip(clip)
                                            Toast.makeText(context, "FSID скопирован", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                    }
                                }
                                Text(
                                    capturedFsid,
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                        
                        // DeviceID
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("DeviceID:", fontWeight = FontWeight.Bold)
                                    IconButton(
                                        onClick = {
                                            val clip = ClipData.newPlainText("DeviceID", capturedDeviceId)
                                            clipboardManager.setPrimaryClip(clip)
                                            Toast.makeText(context, "DeviceID скопирован", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                    }
                                }
                                Text(
                                    capturedDeviceId,
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                        
                        // Запрос
                        if (capturedRequest.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("📤 Запрос:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        capturedRequest,
                                        fontSize = 10.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        
                        // Ответ
                        if (capturedResponse.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("📥 Ответ:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        capturedResponse.take(500) + if (capturedResponse.length > 500) "..." else "",
                                        fontSize = 10.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
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
                        TextButton(onClick = { showCapturedDialog = false }) {
                            Text("Закрыть")
                        }
                        Button(
                            onClick = {
                                onAuthSuccess(capturedFsid, capturedDeviceId)
                                showCapturedDialog = false
                            }
                        ) {
                            Text("✅ Использовать")
                        }
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
                        
                        // Если есть захваченные данные - показываем кнопку
                        if (capturedFsid.isNotEmpty() && capturedDeviceId.isNotEmpty()) {
                            Button(
                                onClick = {
                                    manualFsid = capturedFsid
                                    manualDeviceId = capturedDeviceId
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Text("📋 Использовать перехваченные данные")
                            }
                        }
                        
                        Divider()
                        
                        Text(
                            "FSID: Y9TFJYN2zYIp1ox0IVlovj0e\nDeviceID: 90A1A04A4F31CFA1631C7D3C06492A7C",
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