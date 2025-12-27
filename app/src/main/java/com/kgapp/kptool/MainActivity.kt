package com.kgapp.kptool

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kgapp.kptool.ui.AboutActivity
import com.kgapp.kptool.ui.ReadActivity
import com.kgapp.kptool.ui.SettingsActivity
import com.kgapp.kptool.ui.WriteActivity

private enum class NfcState { NOT_SUPPORTED, DISABLED, ENABLED }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ UI 不和状态栏合并（保留系统状态栏区域）
        WindowCompat.setDecorFitsSystemWindows(window, true)

        /*
        //hide 状态栏
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            val controller = ViewCompat.getWindowInsetsController(v)
            controller?.hide(WindowInsetsCompat.Type.statusBars())
            insets
        }
        */

        // ✅ 状态栏暗色 + 浅色图标
        window.statusBarColor = android.graphics.Color.parseColor("#050607")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            HackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .statusBarsPadding()
            .imePadding(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "KPTOOL//NFC",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "READ | WRITE | KEYS | PROFILES",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item { NFCStatusCardHacker() }

        item {
            MenuCardHacker(
                title = "READ//DUMP",
                description = "读取 NFC 标签数据（扇区/块）",
                icon = Icons.Default.FileOpen
            ) {
                context.startActivity(Intent(context, ReadActivity::class.java))
            }
        }

        item {
            MenuCardHacker(
                title = "WRITE//FLASH",
                description = "写入数据到 NFC 标签（支持配置库）",
                icon = Icons.Default.Edit
            ) {
                context.startActivity(Intent(context, WriteActivity::class.java))
            }
        }

        item {
            MenuCardHacker(
                title = "SETTINGS//KEYS",
                description = "管理密钥集（KeyA/KeyB）",
                icon = Icons.Default.Settings
            ) {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
        }

        item {
            MenuCardHacker(
                title = "ABOUT//AUTHOR",
                description = "关于作者 / 项目信息",
                icon = Icons.Default.Person
            ) {
                context.startActivity(Intent(context, AboutActivity::class.java))
            }
        }

        item { Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun MenuCardHacker(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HackerPanel),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Box(
                    modifier = Modifier.padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "go",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NFCStatusCardHacker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var nfcState by remember { mutableStateOf(getNfcState(context)) }

    // ✅ 自动刷新：从设置返回 / App 回到前台时更新
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) nfcState = getNfcState(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val (title, desc, icon, color) = when (nfcState) {
        NfcState.NOT_SUPPORTED -> Quad("NFC//UNSUPPORTED", "没有 NFC 硬件", Icons.Default.Block, HackerRed)
        NfcState.ENABLED -> Quad("NFC//ONLINE", "可以直接读写 ✅", Icons.Default.Nfc, HackerGreen)
        NfcState.DISABLED -> Quad("NFC//OFFLINE", "点一下去系统设置开启", Icons.Default.Warning, HackerOrange)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = HackerPanel),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        modifier = Modifier.size(22.dp),
                        tint = color
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = desc,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (nfcState == NfcState.DISABLED) {
                Button(
                    onClick = { openNfcSettings(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("ENABLE", fontFamily = FontFamily.Monospace, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun getNfcState(context: Context): NfcState {
    val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcState.NOT_SUPPORTED
    return if (adapter.isEnabled) NfcState.ENABLED else NfcState.DISABLED
}

private fun openNfcSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NFC_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.getOrElse {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}

/** 小工具：四元组 */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** ====== 固定暗色主题：不跟随系统（和你其他页一致） ====== */
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
