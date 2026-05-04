// MainActivity.kt - МИНИМАЛЬНАЯ ВЕРСИЯ
package com.example.fonbetbot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

object BybitColors {
    val Background = Color(0xFF0B0E11)
    val Surface = Color(0xFF1E2329)
    val SurfaceLight = Color(0xFF2B3139)
    val Yellow = Color(0xFFF0B90B)
    val YellowLight = Color(0xFFF8D33A)
    val Green = Color(0xFF0ECB81)
    val Red = Color(0xFFF6465D)
    val TextPrimary = Color(0xFFEAECEF)
    val TextSecondary = Color(0xFF848E9C)
    val TextTertiary = Color(0xFF5E6673)
    val Divider = Color(0xFF2B3139)
    val Blue = Color(0xFF3772FF)
}

enum class Screen {
    MAIN,
    AUTH,
    BETS,
    STATS,
    ANALYTICS,
    PROFILE
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BybitColors.Background,
                    surface = BybitColors.Surface,
                    primary = BybitColors.Yellow,
                    secondary = BybitColors.YellowLight,
                    onBackground = BybitColors.TextPrimary,
                    onSurface = BybitColors.TextPrimary,
                    onPrimary = Color.Black,
                    error = BybitColors.Red,
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BybitColors.Background
                ) {
                    MainApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    var currentScreen by remember { mutableStateOf(Screen.MAIN) }
    
    when (currentScreen) {
        Screen.MAIN -> MainScreen(
            onNavigate = { screen -> currentScreen = screen }
        )
        Screen.AUTH -> PlaceholderScreen(
            title = "Авторизация",
            icon = Icons.Default.Lock,
            description = "Здесь будет страница авторизации через WebView",
            onBack = { currentScreen = Screen.MAIN }
        )
        Screen.BETS -> PlaceholderScreen(
            title = "Экспрессы",
            icon = Icons.Default.ListAlt,
            description = "Здесь будут отображаться активные экспрессы",
            onBack = { currentScreen = Screen.MAIN }
        )
        Screen.STATS -> PlaceholderScreen(
            title = "Статистика",
            icon = Icons.Default.BarChart,
            description = "Здесь будет статистика ставок и баланса",
            onBack = { currentScreen = Screen.MAIN }
        )
        Screen.ANALYTICS -> PlaceholderScreen(
            title = "Аналитика",
            icon = Icons.Default.TrendingUp,
            description = "Здесь будет детальная аналитика экспрессов",
            onBack = { currentScreen = Screen.MAIN }
        )
        Screen.PROFILE -> PlaceholderScreen(
            title = "Профиль",
            icon = Icons.Default.Person,
            description = "Здесь будет информация о профиле пользователя",
            onBack = { currentScreen = Screen.MAIN }
        )
    }
}

@Composable
fun MainScreen(onNavigate: (Screen) -> Unit) {
    Scaffold(
        containerColor = BybitColors.Background,
        bottomBar = {
            BottomNavigationBar(onNavigate = onNavigate)
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                BalanceCard()
            }
            
            item {
                ActionButtons(onNavigate = onNavigate)
            }
            
            item {
                PromoBanner()
            }
            
            item {
                QuickStatsCard()
            }
            
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun BalanceCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Общие активы",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = BybitColors.TextSecondary
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(BybitColors.Green)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "0.00 ₽",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = BybitColors.TextPrimary,
                fontFamily = FontFamily.Default
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "P&L за сегодня 0.00 ₽ (0.00%)",
                fontSize = 13.sp,
                color = BybitColors.TextSecondary
            )
        }
    }
}

@Composable
fun ActionButtons(onNavigate: (Screen) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButton(
                icon = Icons.Default.PlayArrow,
                label = "Старт",
                onClick = { /* Заглушка: запуск бота */ }
            )
            
            ActionButton(
                icon = Icons.Default.Lock,
                label = "Авторизация",
                onClick = { onNavigate(Screen.AUTH) }
            )
            
            ActionButton(
                icon = Icons.Default.ListAlt,
                label = "Экспрессы",
                onClick = { onNavigate(Screen.BETS) }
            )
            
            ActionButton(
                icon = Icons.Default.BarChart,
                label = "Статистика",
                onClick = { onNavigate(Screen.STATS) }
            )
            
            ActionButton(
                icon = Icons.Default.TrendingUp,
                label = "Аналитика",
                onClick = { onNavigate(Screen.ANALYTICS) }
            )
        }
    }
}

@Composable
fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable { onClick() }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BybitColors.SurfaceLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = BybitColors.TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = BybitColors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PromoBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🚀 Авто-экспрессы",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = BybitColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Умные ставки в реальном времени",
                    fontSize = 13.sp,
                    color = BybitColors.TextSecondary
                )
            }
            
            Icon(
                Icons.Default.TrendingUp,
                contentDescription = null,
                tint = BybitColors.Yellow,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
fun QuickStatsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BybitColors.Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📊 Быстрая статистика",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = BybitColors.TextPrimary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("0", "Экспрессов")
                StatItem("0", "Выигрышей")
                StatItem("0%", "Win rate")
                StatItem("0.00 ₽", "Прибыль")
            }
        }
    }
}

@Composable
fun StatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = BybitColors.TextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = BybitColors.TextTertiary
        )
    }
}

@Composable
fun BottomNavigationBar(onNavigate: (Screen) -> Unit) {
    Surface(
        color = BybitColors.Surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavBarItem(
                icon = Icons.Default.Home,
                label = "Главная",
                onClick = { onNavigate(Screen.MAIN) }
            )
            
            NavBarItem(
                icon = Icons.Default.ListAlt,
                label = "Экспрессы",
                onClick = { onNavigate(Screen.BETS) }
            )
            
            NavBarItem(
                icon = Icons.Default.BarChart,
                label = "Статистика",
                onClick = { onNavigate(Screen.STATS) }
            )
            
            NavBarItem(
                icon = Icons.Default.Person,
                label = "Профиль",
                onClick = { onNavigate(Screen.PROFILE) }
            )
            
            // Кнопка Старт/Стоп
            Column(
                modifier = Modifier
                    .weight(1.2f, fill = true)
                    .clickable { /* Заглушка: старт/стоп бота */ }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(BybitColors.Green),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Старт",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BybitColors.TextSecondary
                )
            }
        }
    }
}

@Composable
fun NavBarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f, fill = true)
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = BybitColors.TextTertiary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            color = BybitColors.TextTertiary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = BybitColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(title, color = BybitColors.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Назад",
                            tint = BybitColors.TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BybitColors.Surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = BybitColors.TextTertiary,
                    modifier = Modifier.size(64.dp)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = BybitColors.TextPrimary
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = description,
                    fontSize = 14.sp,
                    color = BybitColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BybitColors.Yellow
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Назад",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}