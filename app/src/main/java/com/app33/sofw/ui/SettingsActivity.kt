package com.app33.sofw.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.app33.sofw.AppSettings

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.parseColor("#050607")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            HackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen()
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var detailedLogsEnabled by remember { mutableStateOf(AppSettings.isDetailedLogsEnabled(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "SETTINGS",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = HackerPanel),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text(
                    text = "秘钥管理已改为云端下发",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "登录成功后自动获取秘钥和扇区策略，本地不再维护秘钥列表。",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = HackerPanel),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("日志设置", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Switch(checked = detailedLogsEnabled, onCheckedChange = { detailedLogsEnabled = it })
                    Text(if (detailedLogsEnabled) "详细日志：开启" else "详细日志：关闭", fontFamily = FontFamily.Monospace)
                }
                Button(onClick = {
                    AppSettings.setDetailedLogsEnabled(context, detailedLogsEnabled)
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                }) {
                    Text("保存", fontFamily = FontFamily.Monospace, color = Color.Black)
                }
            }
        }
    }
}

private val HackerBg = Color(0xFF050607)
private val HackerPanel = Color(0xFF0B0F10)
private val HackerSurface = Color(0xFF0F1416)
private val HackerGreen = Color(0xFF00FF7A)
private val HackerRed = Color(0xFFFF4D5A)

@Composable
private fun HackerTheme(content: @Composable () -> Unit) {
    val scheme = androidx.compose.material3.darkColorScheme(
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

    MaterialTheme(colorScheme = scheme, content = content)
}
