// WebViewAuthScreen.kt - ИСПРАВЛЕННАЯ ВЕРСИЯ
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
    var errorMessage by remember { mutableStateOf("") }
    var showErrorDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    
    // Устанавливаем перехватчик
    fun setupInterceptor() {
        webViewRef?.evaluateJavascript("""
            (function() {
                console.log('=== SETTING UP INTERCEPTOR ===');
                
                // Перехватываем fetch
                var originalFetch = window.fetch;
                window.fetch = function(url, options) {
                    if (url && url.includes('session/info')) {
                        console.log('=== INTERCEPTED session/info ===');
                        
                        if (options && options.body) {
                            try {
                                var body = JSON.parse(options.body);
                                console.log('FSID:', body.fsid);
                                console.log('DeviceID:', body.deviceId);
                                window._capturedFsid = body.fsid || '';
                                window._capturedDeviceId = body.deviceId || '';
                                window._capturedRequest = options.body;
                            } catch(e) {
                                console.log('Parse error:', e);
                            }
                        }
                        
                        return originalFetch.apply(this, arguments).then(function(response) {
                            var clonedResponse = response.clone();
                            clonedResponse.text().then(function(body) {
                                window._capturedResponse = body;
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
                
                XHR.open = function(method, url) {
                    this._url = url;
                    return originalOpen.apply(this, arguments);
                };
                
                XHR.send = function(body) {
                    if (this._url && this._url.includes('session/info')) {
                        console.log('=== INTERCEPTED XHR session/info ===');
                        
                        if (body) {
                            try {
                                var jsonBody = JSON.parse(body);
                                console.log('FSID:', jsonBody.fsid);
                                console.log('DeviceID:', jsonBody.deviceId);
                                window._capturedFsid = jsonBody.fsid || '';
                                window._capturedDeviceId = jsonBody.deviceId || '';
                                window._capturedRequest = body;
                            } catch(e) {
                                console.log('Parse error:', e);
                            }
                        }
                        
                        this.addEventListener('load', function() {
                            window._capturedResponse = this.responseText;
                        });
                    }
                    return originalSend.apply(this, arguments);
                };
                
                console.log('=== INTERCEPTOR INSTALLED ===');
                return 'ok';
            })();
        """.trimIndent()) { }
    }
    
    // Функция для получения захваченных данных (ИСПРАВЛЕНО)
    fun getCapturedData() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var fsid = window._capturedFsid || '';
                var deviceId = window._capturedDeviceId || '';
                var request = window._capturedRequest || '';
                var response = window._capturedResponse || '';
                
                // Возвращаем как строку с разделителями
                return fsid + '###' + deviceId + '###' + request + '###' + response;
            })();
        """.trimIndent()) { result ->
            try {
                // Убираем кавычки в начале и конце
                val cleanResult = result.trim('"').replace("\\\"", "\"")
                
                if (cleanResult.isNotEmpty() && cleanResult != "null") {
                    val parts = cleanResult.split("###")
                    
                    if (parts.size >= 2) {
                        capturedFsid = parts[0]
                        capturedDeviceId = parts[1]
                        capturedRequest = if (parts.size > 2) parts[2] else ""
                        capturedResponse = if (parts.size > 3) parts[3] else ""
                        
                        if (capturedFsid.isNotEmpty() && capturedDeviceId.isNotEmpty()) {
                            // Форматируем запрос для отображения
                            if (capturedRequest.isNotEmpty()) {
                                try {
                                    val json = JSONObject(capturedRequest)
                                    capturedRequest = json.toString(2)
                                } catch (e: Exception) {
                                    // Оставляем как есть
                                }
                            }
                            
                            // Форматируем ответ для отображения
                            if (capturedResponse.isNotEmpty()) {
                                try {
                                    val json = JSONObject(capturedResponse)
                                    capturedResponse = json.toString(2)
                                } catch (e: Exception) {
                                    // Оставляем как есть
                                }
                            }
                            
                            manualFsid = capturedFsid
                            manualDeviceId = capturedDeviceId
                            showCapturedDialog = true
                        } else {
                            errorMessage = "Данные ещё не перехвачены.\n\nВойдите в аккаунт и дождитесь загрузки баланса (обычно 2-3 секунды после входа)."
                            showErrorDialog = true
                        }
                    } else {
                        errorMessage = "Данные не найдены. Войдите в аккаунт."
                        showErrorDialog = true
                    }
                } else {
                    errorMessage = "Данные не найдены. Войдите в аккаунт."
                    showErrorDialog = true
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка: ${e.message}"
                showErrorDialog = true
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
                            "1. Войдите в аккаунт\n2. Дождитесь загрузки баланса (2-3 сек)\n3. Нажмите 'Найти'",
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
                        
                        // Статус
                        if (capturedFsid.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "✅ Данные найдены!",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        
        // Диалог с ошибкой
        if (showErrorDialog) {
            AlertDialog(
                onDismissRequest = { showErrorDialog = false },
                title = { Text("ℹ️ Информация") },
                text = { Text(errorMessage) },
                confirmButton = {
                    TextButton(onClick = { showErrorDialog = false }) {
                        Text("OK")
                    }
                }
            )
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
                                val data = "FSID: $capturedFsid\nDeviceID: $capturedDeviceId"
                                val clip = ClipData.newPlainText("Data", data)
                                clipboardManager.setPrimaryClip(clip)
                                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("📋")
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
                                Text("FSID:", fontWeight = FontWeight.Bold)
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
                                Text("DeviceID:", fontWeight = FontWeight.Bold)
                                Text(
                                    capturedDeviceId,
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                        
                        // Запрос (свёрнут по умолчанию)
                        if (capturedRequest.isNotEmpty()) {
                            var expanded by remember { mutableStateOf(false) }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded = !expanded },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("📤 Запрос:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(if (expanded) "▲" else "▼", fontSize = 12.sp)
                                    }
                                    if (expanded) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            capturedRequest,
                                            fontSize = 10.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Ответ (свёрнут по умолчанию)
                        if (capturedResponse.isNotEmpty()) {
                            var expanded by remember { mutableStateOf(false) }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded = !expanded },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("📥 Ответ:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(if (expanded) "▲" else "▼", fontSize = 12.sp)
                                    }
                                    if (expanded) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            capturedResponse.take(1000),
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
                        OutlinedTextField(
                            value = manualFsid,
                            onValueChange = { manualFsid = it },
                            label = { Text("FSID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4
                        )
                        
                        // DeviceID
                        OutlinedTextField(
                            value = manualDeviceId,
                            onValueChange = { manualDeviceId = it },
                            label = { Text("Device ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4
                        )
                        
                        // Если есть захваченные данные
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
                                Text("📋 Использовать найденные")
                            }
                        }
                        
                        Text(
                            "Пример FSID: Y9TFJYN2zYIp1ox0IVlovj0e\nПример DeviceID: 90A1A04A4F31CFA1631C7D3C06492A7C",
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

// Вспомогательная функция для clickable
import androidx.compose.foundation.clickable