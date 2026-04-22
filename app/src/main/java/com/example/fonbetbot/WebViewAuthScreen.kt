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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    var apiResponse by remember { mutableStateOf("") }
    var showResponseDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val scope = rememberCoroutineScope()
    
    // Функция для поиска FSID и DeviceID в storage
    fun findInStorage(onFound: (fsid: String, deviceId: String) -> Unit) {
        webViewRef?.evaluateJavascript("""
            (function() {
                var result = { fsid: '', deviceId: '' };
                
                // Ищем в localStorage
                try {
                    for (var i = 0; i < localStorage.length; i++) {
                        var key = localStorage.key(i);
                        var value = localStorage.getItem(key);
                        
                        if (!result.fsid && (key.toLowerCase().indexOf('fsid') >= 0 || key === 'headerApi.FSID')) {
                            result.fsid = value;
                        }
                        
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
            try {
                if (result != "null" && result.isNotEmpty()) {
                    val json = JSONObject(result.trim('"'))
                    val fsid = json.optString("fsid", "")
                    val deviceId = json.optString("deviceId", "")
                    
                    if (fsid.isNotEmpty() && deviceId.isNotEmpty()) {
                        onFound(fsid, deviceId)
                    } else {
                        onFound("", "")
                    }
                } else {
                    onFound("", "")
                }
            } catch (e: Exception) {
                onFound("", "")
            }
        }
    }
    
    // Функция для отправки запроса к API и получения полного ответа
    fun fetchSessionInfo() {
        isChecking = true
        
        // Сначала ищем FSID и DeviceID в storage
        findInStorage { foundFsid, foundDeviceId ->
            if (foundFsid.isNotEmpty() && foundDeviceId.isNotEmpty()) {
                // Отправляем запрос к API
                scope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            val client = OkHttpClient()
                            
                            val cookieManager = CookieManager.getInstance()
                            val cookieString = cookieManager.getCookie(authUrl) ?: ""
                            
                            val jsonBody = JSONObject().apply {
                                put("clientId", 18845703)
                                put("fsid", foundFsid)
                                put("sysId", 21)
                                put("deviceId", foundDeviceId)
                            }
                            
                            val mediaType = "application/json; charset=utf-8".toMediaType()
                            val body = jsonBody.toString().toRequestBody(mediaType)
                            
                            val requestBuilder = Request.Builder()
                                .url("https://clientsapi-lb51-w.bk6bba-resources.com/session/info")
                                .post(body)
                                .addHeader("Content-Type", "application/json")
                                .addHeader("Accept", "application/json")
                            
                            if (cookieString.isNotEmpty()) {
                                requestBuilder.addHeader("Cookie", cookieString)
                            }
                            
                            val request = requestBuilder.build()
                            client.newCall(request).execute()
                        }
                        
                        val responseBody = response.body?.string() ?: ""
                        
                        // Форматируем JSON для читаемости
                        val formattedJson = try {
                            val json = JSONObject(responseBody)
                            json.toString(2)
                        } catch (e: Exception) {
                            responseBody
                        }
                        
                        apiResponse = """
                            |📤 ЗАПРОС:
                            |URL: https://clientsapi-lb51-w.bk6bba-resources.com/session/info
                            |Method: POST
                            |FSID: $foundFsid
                            |DeviceID: $foundDeviceId
                            |ClientID: 18845703
                            |SysID: 21
                            |
                            |📥 ОТВЕТ (Код: ${response.code}):
                            |$formattedJson
                        """.trimMargin()
                        
                        // Пытаемся извлечь баланс из ответа
                        try {
                            val json = JSONObject(responseBody)
                            val saldo = when {
                                json.has("saldo") -> json.getDouble("saldo")
                                json.has("balance") -> json.getDouble("balance")
                                json.has("data") -> {
                                    val data = json.getJSONObject("data")
                                    if (data.has("saldo")) data.getDouble("saldo")
                                    else if (data.has("balance")) data.getDouble("balance")
                                    else null
                                }
                                else -> null
                            }
                            
                            if (saldo != null) {
                                apiResponse += "\n\n💰 БАЛАНС: %.2f ₽".format(saldo)
                            }
                        } catch (e: Exception) {
                            // Игнорируем
                        }
                        
                        manualFsid = foundFsid
                        manualDeviceId = foundDeviceId
                        showResponseDialog = true
                        
                    } catch (e: Exception) {
                        apiResponse = "❌ Ошибка запроса:\n${e.message}"
                        showResponseDialog = true
                    } finally {
                        isChecking = false
                    }
                }
            } else {
                isChecking = false
                // Если не нашли в storage, показываем диалог ручного ввода
                manualFsid = foundFsid
                manualDeviceId = foundDeviceId
                showManualDialog = true
                Toast.makeText(context, "Данные не найдены. Введите вручную.", Toast.LENGTH_LONG).show()
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
                    IconButton(onClick = { fetchSessionInfo() }) {
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
                                onClick = { fetchSessionInfo() },
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
                                Text(if (isChecking) "Запрос..." else "Найти")
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
        
        // Диалог с ответом API
        if (showResponseDialog) {
            AlertDialog(
                onDismissRequest = { showResponseDialog = false },
                title = { 
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("📡 Ответ session/info")
                        Row {
                            TextButton(
                                onClick = {
                                    val clip = ClipData.newPlainText("API Response", apiResponse)
                                    clipboardManager.setPrimaryClip(clip)
                                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("📋")
                            }
                            TextButton(
                                onClick = {
                                    showResponseDialog = false
                                    showManualDialog = true
                                }
                            ) {
                                Text("✏️")
                            }
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            apiResponse,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { showResponseDialog = false }) {
                            Text("Закрыть")
                        }
                        Button(
                            onClick = {
                                if (manualFsid.isNotEmpty() && manualDeviceId.isNotEmpty()) {
                                    onAuthSuccess(manualFsid, manualDeviceId)
                                    showResponseDialog = false
                                }
                            },
                            enabled = manualFsid.isNotEmpty() && manualDeviceId.isNotEmpty()
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
                        
                        Divider()
                        
                        Text(
                            "FSID обычно: Y9TFJYN2zYIp1ox0IVlovj0e\n" +
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