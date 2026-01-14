package com.kgapp.kptool.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kgapp.kptool.AppSettings

class SettingsActivity : ComponentActivity() {
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen()
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scroll = rememberScrollState()

    var keysText by remember { mutableStateOf(AppSettings.getKeysText(context)) }
    var keyCount by remember { mutableStateOf(AppSettings.parseKeysFromText(keysText).size) }

    fun refreshCount() {
        keyCount = AppSettings.parseKeysFromText(keysText).size
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LazyColumnHacker(

        scrollState = scroll
    ) {
        Text(
            text = "SETTINGS//KEYS",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "KEYSET SIZE=$keyCount | FORMAT=12HEX | #COMMENT SUPPORTED",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))

        HackerCard {
            Text(
                text = "KEYSET RULES",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "• 每行一个 key（12位 HEX，比如 FFFFFFFFFFFF）\n" +
                        "• 支持 # 注释\n" +
                        "• 建议只放你确认有效的 KeyA/KeyB（别塞太多没用的，这样读写会更快）\n" +
                        "• 当前可用 keys：$keyCount",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        HackerCard {
            Text(
                text = "EDIT//KEYS",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(10.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = keysText,
                    onValueChange = {
                        keysText = it
                        refreshCount()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp)
                        .padding(12.dp),
                    singleLine = false,
                    label = {
                        Text("keys list", fontFamily = FontFamily.Monospace)
                    },
                    placeholder = {
                        Text(
                            "FFFFFFFFFFFF\nA0A1A2A3A4A5\n# comment",
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.Characters
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val parsed = AppSettings.parseKeysFromText(keysText)
                        if (parsed.isEmpty()) {
                            val activity = context as ComponentActivity
                            Toast.makeText(
                                activity,
                                "至少需要1个合法key（key数量太少）",
                                Toast.LENGTH_LONG
                            ).show()
                            return@Button
                        }
                        AppSettings.setKeysText(context, keysText)

                        val activity = context as ComponentActivity
                        Toast.makeText(
                            activity,
                            "保存成功：保存key数量（$keyCount）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                ) {
                    Text("SAVE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        keysText = AppSettings.getKeysText(context)
                        refreshCount()
                        val activity = context as ComponentActivity
                        Toast.makeText(
                            activity,
                            "加载成功：加载了key",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                ) {
                    Text("RELOAD", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = "TIP: 保存后读写页点击“加载Keys”即可刷新缓存。",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = HackerOrange
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** ====== 统一的滚动布局（保持暗色 hacker padding）====== */
@Composable
private fun LazyColumnHacker(
    scrollState: androidx.compose.foundation.ScrollState,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.Start,
        content = content
    )
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
