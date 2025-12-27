package com.kgapp.kptool.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsKabaddi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        /*
        //hide 状态栏
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            val controller = ViewCompat.getWindowInsetsController(v)
            controller?.hide(WindowInsetsCompat.Type.statusBars())
            insets
        }
        */
        window.statusBarColor = android.graphics.Color.parseColor("#050607")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            HackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AboutScreen()
                }
            }
        }
    }
}

@Composable
private fun AboutScreen() {
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .statusBarsPadding()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ABOUT//AUTHOR",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "KPTOOL NFC // MIFARE CLASSIC TOOLKIT",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HackerCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SportsKabaddi,
                    contentDescription = "icon",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "AUTHOR",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Kgyyds",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = "注意事项\n" +
                        "\t\t仅用于非合法无授权场景，请勿用于学习目的",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HackerCard {
            Text(
                text = "FEATURES",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))

            Bullet("READ//DUMP  扇区/块读取输出", HackerGreen)
            Bullet("WRITE//FLASH 多块写入 + 配置库", HackerGreen)
            Bullet("KEYS//SET   KeyA/KeyB 管理", HackerOrange)
            Bullet("LOG//TRACE  读写过程可追踪", HackerOrange)
        }

        HackerCard {
            Text(
                text = "DISCLAIMER",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = HackerRed
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "仅用于学习、研究、测试、或获得明确授权的场景。\n" +
                        "对未经授权的读写行为造成的后果，作者不承担责任。",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HackerCard {
            Text(
                text = "BUILD",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "TOOL: Android Studio\n" +
                        "BUILDLG: Kotlin + Java\n" +
                        "UI: Jetpack Compose\n" +
                        "NFC: ReaderMode + MifareClassic\n" +
                        "Target: Mifare Classic 1K",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Bullet(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("•", fontFamily = FontFamily.Monospace, color = color, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** ====== 黑客风卡片容器 ====== */
@Composable
private fun HackerCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HackerPanel),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            content = content
        )
    }
}

/** ====== 固定暗色主题：不跟随系统 ====== */
private val HackerBg = Color(0xFF050607)
private val HackerPanel = Color(0xFF0B0F10)
private val HackerSurface = Color(0xFF0F1416)

private val HackerGreen = Color(0xFF00FF7A)
private val HackerOrange = Color(0xFFFFA43A)
private val HackerRed = Color(0xFFFF4D5A)

@Composable
private fun HackerTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = HackerGreen,
        background = HackerBg,
        surface = HackerSurface,
        surfaceVariant = Color(0xFF151C1F),
        onPrimary = Color.Black,
        onBackground = Color(0xFFE6F7EF),
        onSurface = Color(0xFFE6F7EF),
        onSurfaceVariant = Color(0xFF9AB0A6),
        error = HackerRed
    )

    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content
    )
}
