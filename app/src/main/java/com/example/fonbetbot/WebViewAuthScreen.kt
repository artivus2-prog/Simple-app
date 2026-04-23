// WebViewAuthScreen.kt - ИСПРАВЛЕННАЯ ВЕРСИЯ С ОТОБРАЖЕНИЕМ ДАННЫХ ПОСЛЕ АВТОРИЗАЦИИ
package com.example.fonbetbot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    var capturedClientId by remember { mutableStateOf<Long?>(null) }
    var capturedUserName by remember { mutableStateOf("") }
    var capturedRequest by remember { mutableStateOf("") }
    var capturedResponse by remember { mutableStateOf("") }
    var showCapturedDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showErrorDialog by remember { mutableStateOf(false) }
    var isAuthorized by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    
    // Функция для загрузки баланса
    fun fetchSessionInfo(fsid: String, deviceId: String) {
        val apiClient = ApiClient()
        apiClient.getSaldo(
            cookies = emptyMap(),
            fsid = fsid,
            deviceId = deviceId,
            onSuccess = { sessionInfo ->
                if (sessionInfo != null) {
                    capturedFsid = fsid
                    capturedDeviceId = deviceId
                    capturedClientId = sessionInfo.clientId
                    capturedUserName = sessionInfo.userName ?: "Неизвестно"
                    isAuthorized = true
                    
                    // Скрываем WebView после получения данных
                    webViewRef?.visibility = android.view.View.GONE
                }
            },
            onError = { error ->
                // Если не удалось получить данные, используем что есть
                capturedFsid = fsid
                capturedDeviceId = deviceId
                capturedUserName = "Данные не загружены"
                isAuthorized = true
                
                webViewRef?.visibility = android.view.View.GONE
            }
        )
    }
    
    // Устанавливаем перехватчик для автоматического захвата
    fun setupInterceptor() {
        webViewRef?.evaluateJavascript("""
            (function() {
                console.log('=== SETTING UP INTERCEPTOR ===');
                
                var originalFetch = window.fetch;
                window.fetch = function(url, options) {
                    if (url && url.includes('session/info')) {
                        console.log('=== INTERCEPTED session/info ===');
                        
                        if (options && options.body) {
                            try {
                                var body = JSON.parse(options.body);
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
                                window._authorized = true;
                            });
                            return response;
                        });
                    }
                    return originalFetch.apply(this, arguments);
                };
                
                console.log('=== INTERCEPTOR INSTALLED ===');
                return 'ok';
            })();
        """.trimIndent()) { }
    }
    
    // Функция для получения захваченных данных
    fun getCapturedData() {
        webViewRef?.evaluateJavascript("""
            (function() {
                var fsid = window._capturedFsid || '';
                var deviceId = window._capturedDeviceId || '';
                var authorized = window._authorized || false;
                var request = window._capturedRequest || '';
                var response = window._capturedResponse || '';
                
                return fsid + '###' + deviceId + '###' + authorized + '###' + request + '###' + response;
            })();
        """.trimIndent()) { result ->
            try {
                val cleanResult = result.trim('"').replace("\\\"", "\"")
                
                if (cleanResult.isNotEmpty() && cleanResult != "null") {
                    val parts = cleanResult.split("###")
                    
                    if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                        val fsid = parts[0]
                        val deviceId = parts[1]
                        val authorized = parts.getOrNull(2) == "true"
                        
                        capturedRequest = parts.getOrNull(3) ?: ""
                        capturedResponse = parts.getOrNull(4) ?: ""
                        
                        if (authorized) {
                            // Если сессия уже активна, получаем данные
                            fetchSessionInfo(fsid, deviceId)
                        } else {
                            // Форматируем запрос
                            if (capturedRequest.isNotEmpty()) {
                                try {
                                    val json = JSONObject(capturedRequest)
                                    capturedRequest = json.toString(2)
                                } catch (e: Exception) { }
                            }
                            
                            if (capturedResponse.isNotEmpty()) {
                                try {
                                    val json = JSONObject(capturedResponse)
                                    capturedResponse = json.toString(2)
                                } catch (e: Exception) { }
                            }
                            
                            manualFsid = fsid
                            manualDeviceId = deviceId
                            showCapturedDialog = true
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
                title = { 
                    Text(
                        if (isAuthorized) "✅ Авторизация успешна" 
                        else "🔐 Авторизация Фонбет"
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                actions = {
                    if (!isAuthorized) {
                        IconButton(onClick = { showManualDialog = true }) {
                            Icon(Icons.Default.Edit, "Ввести")
                        }
                        IconButton(onClick = { 
                            setupInterceptor()
                            getCapturedData()
                        }) {
                            Icon(Icons.Default.Search, "Найти")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isAuthorized) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isAuthorized) {
                // Экран с данными авторизованного пользователя
                AuthorizedUserScreen(
                    userName = capturedUserName,
                    clientId = capturedClientId,
                    fsid = capturedFsid,
                    deviceId = capturedDeviceId,
                    onConfirm = {
                        onAuthSuccess(capturedFsid, capturedDeviceId)
                    },
                    onCopy = {
                        val data = "FSID: $capturedFsid\nDeviceID: $capturedDeviceId"
                        val clip = ClipData.newPlainText("Auth Data", data)
                        clipboardManager.setPrimaryClip(clip)
                        Toast.makeText(context, "Данные скопированы", Toast.LENGTH_SHORT).show()
                    },
                    onReauth = {
                        isAuthorized = false
                        webViewRef?.visibility = android.view.View.VISIBLE
                        webViewRef?.reload()
                    }
                )
            } else {
                // WebView для авторизации
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
                                    
                                    // Проверяем, залогинен ли пользователь
                                    view?.evaluateJavascript("""
                                        (function() {
                                            try {
                                                var cookies = document.cookie || '';
                                                var hasSession = cookies.indexOf('fsid') > -1 || 
                                                               cookies.indexOf('session') > -1;
                                                return hasSession ? 'true' : 'false';
                                            } catch(e) {
                                                return 'false';
                                            }
                                        })();
                                    """.trimIndent()) { hasSession ->
                                        if (hasSession == "true") {
                                            // Пользователь залогинен, пытаемся получить данные
                                            setupInterceptor()
                                            getCapturedData()
                                        }
                                    }
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
                                "1. Войдите в аккаунт\n2. Дождитесь загрузки баланса (1-2 сек)\n3. Нажмите 'Найти'",
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
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
                                    fontFamily = FontFamily.Monospace
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
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        
                        // Запрос
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
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Ответ
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
                                            fontFamily = FontFamily.Monospace
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
                                // Получаем данные сессии и показываем экран авторизации
                                fetchSessionInfo(capturedFsid, capturedDeviceId)
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
                        OutlinedTextField(
                            value = manualFsid,
                            onValueChange = { manualFsid = it },
                            label = { Text("FSID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4
                        )
                        
                        OutlinedTextField(
                            value = manualDeviceId,
                            onValueChange = { manualDeviceId = it },
                            label = { Text("Device ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4
                        )
                        
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
                                fetchedSessionInfo(manualFsid, manualDeviceId)
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
private fun AuthorizedUserScreen(
    userName: String,
    clientId: Long?,
    fsid: String,
    deviceId: String,
    onConfirm: () -> Unit,
    onCopy: () -> Unit,
    onReauth: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Аватар
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary
                        )
                    ),
                    shape = RoundedCornerShape(50.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                getUserInitials(userName),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Приветствие
        Text(
            "Добро пожаловать!",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // ФИО
        Text(
            userName,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        if (clientId != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "ID: $clientId",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Карточка с данными авторизации
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Данные авторизации получены",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    "FSID:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    fsid,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    "Device ID:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    deviceId,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Кнопки действий
        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.Check, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "✅ Использовать эти данные",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Копировать")
            }
            
            OutlinedButton(
                onClick = onReauth,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Заново")
            }
        }
    }
}

private fun getUserInitials(fullName: String): String {
    val parts = fullName.split(" ")
    return when {
        parts.size >= 2 -> "${parts[0].firstOrNull() ?: '?'}${parts[1].firstOrNull() ?: '?'}".uppercase()
        parts.size == 1 -> parts[0].firstOrNull()?.uppercase() ?: "?"
        else -> "?"
    }
}