package com.app33.sofw

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
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.app33.sofw.ui.AboutActivity
import com.app33.sofw.ui.ReadActivity
import com.app33.sofw.ui.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private enum class NfcState { NOT_SUPPORTED, DISABLED, ENABLED }

data class LoginResult(
    val success: Boolean,
    val msg: String,
    val data: LoginInfo? = null
)

data class InfoResult(
    val success: Boolean,
    val msg: String,
    val data: RuntimeCardInfo? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.parseColor("#050607")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            HackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RootScreen()
                }
            }
        }
    }
}

@Composable
private fun RootScreen() {
    var loginInfo by remember { mutableStateOf(AppSession.getLoginInfo()) }
    if (loginInfo == null) {
        LoginScreen(onSuccess = { loginInfo = it })
    } else {
        MainMenuScreen(loginInfo = loginInfo!!)
    }
}

@Composable
private fun LoginScreen(onSuccess: (LoginInfo) -> Unit) {
    var cardCode by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().navigationBarsPadding().statusBarsPadding().imePadding().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = HackerPanel), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LOGIN//KPTOOL", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                Text("输入卡密后登录", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = cardCode,
                    onValueChange = { cardCode = it; loginError = null },
                    singleLine = true,
                    label = { Text("CARD_CODE", fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.fillMaxWidth()
                )

                if (!loginError.isNullOrEmpty()) {
                    Text(loginError!!, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        if (cardCode.isBlank()) {
                            loginError = "卡密不能为空"
                            return@Button
                        }
                        isLoading = true
                        scope.launch {
                            val login = loginWithCardCode(cardCode.trim())
                            if (!login.success || login.data == null) {
                                isLoading = false
                                loginError = login.msg
                                return@launch
                            }
                            val info = fetchRuntimeInfo(login.data.cardCode)
                            isLoading = false
                            if (!info.success || info.data == null) {
                                loginError = info.msg
                                return@launch
                            }
                            AppSession.updateSession(login.data, info.data)
                            onSuccess(login.data)
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Text("登录", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainMenuScreen(loginInfo: LoginInfo) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val runtimeInfo = AppSession.getRuntimeInfo()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().navigationBarsPadding().statusBarsPadding().imePadding(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("KPTOOL//NFC", fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text("READ | WRITE | KEYS | PROFILES", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { LoginInfoCard(loginInfo = loginInfo, runtimeInfo = runtimeInfo) }
        item { NFCStatusCardHacker() }

        item {
            MenuCardHacker("READ//WRITE", "读取余额 / 固定金额写入（同一界面）", Icons.Default.FileOpen) {
                context.startActivity(Intent(context, ReadActivity::class.java))
            }
        }
        item {
            MenuCardHacker("SETTINGS", "应用设置（日志开关）", Icons.Default.Settings) {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
        }
        item {
            MenuCardHacker("ABOUT//AUTHOR", "关于作者 / 项目信息", Icons.Default.Person) {
                context.startActivity(Intent(context, AboutActivity::class.java))
            }
        }
    }
}

@Composable
private fun LoginInfoCard(loginInfo: LoginInfo, runtimeInfo: RuntimeCardInfo?) {
    Card(colors = CardDefaults.cardColors(containerColor = HackerPanel), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CreditCard, "card info", modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("CARD//INFO", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("卡密: ${loginInfo.cardCode}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("绑定卡号: ${loginInfo.cardNo ?: "未绑定"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("剩余次数: ${loginInfo.remainTimes} | 单次金额: ${loginInfo.allowAmount}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val sectorText = when (runtimeInfo?.sector) {
                    null -> "未知"
                    100 -> "可选(100)"
                    else -> "固定 S${runtimeInfo.sector}"
                }
                Text("扇区策略: $sectorText | 秘钥: ${runtimeInfo?.keys?.size ?: 0} 个", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MenuCardHacker(title: String, description: String, icon: ImageVector, onClick: () -> Unit) { /* unchanged */
    Card(colors = CardDefaults.cardColors(containerColor = HackerPanel), modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = title, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(description, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(imageVector = Icons.Default.ChevronRight, contentDescription = "go", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NFCStatusCardHacker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var nfcState by remember { mutableStateOf(getNfcState(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) nfcState = getNfcState(context) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val (title, desc, icon, color) = when (nfcState) {
        NfcState.NOT_SUPPORTED -> Quad("NFC//UNSUPPORTED", "没有 NFC 硬件", Icons.Default.Block, HackerRed)
        NfcState.ENABLED -> Quad("NFC//ONLINE", "可以直接读写 ✅", Icons.Default.Nfc, HackerGreen)
        NfcState.DISABLED -> Quad("NFC//OFFLINE", "等待授权", Icons.Default.Warning, HackerOrange)
    }
    Card(colors = CardDefaults.cardColors(containerColor = HackerPanel), modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, title, modifier = Modifier.size(22.dp), tint = color)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(desc, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (nfcState == NfcState.DISABLED) {
                Button(onClick = { openNfcSettings(context) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Text("ENABLE", fontFamily = FontFamily.Monospace, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private suspend fun loginWithCardCode(cardCode: String): LoginResult = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.33app.top/login.php").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 10_000
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    }
    return@withContext runCatching {
        val body = "card_code=${URLEncoder.encode(cardCode, "UTF-8")}"
        OutputStreamWriter(connection.outputStream).use { it.write(body) }
        val responseText = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream)
            .bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        val code = json.optInt("code", -1)
        val msg = json.optString("msg", "未知错误")
        if (code == 0) {
            val data = json.getJSONObject("data")
            LoginResult(true, msg, LoginInfo(data.optString("card_code", cardCode), if (data.isNull("card_no")) null else data.optString("card_no"), data.optInt("remain_times", 0), data.optInt("allow_amount", 0)))
        } else LoginResult(false, msg)
    }.getOrElse { LoginResult(false, "网络异常：${it.message ?: "未知错误"}") }.also { connection.disconnect() }
}

private suspend fun fetchRuntimeInfo(cardCode: String): InfoResult = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.33app.top/info.php").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 10_000
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    }
    return@withContext runCatching {
        val body = "card_code=${URLEncoder.encode(cardCode, "UTF-8")}"
        OutputStreamWriter(connection.outputStream).use { it.write(body) }
        val responseText = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream)
            .bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        val code = json.optInt("code", -1)
        val msg = json.optString("msg", "未知错误")
        if (code != 0) return@runCatching InfoResult(false, msg)

        val data = json.getJSONObject("data")
        val keysArray = data.optJSONArray("keys")
        val keysHex = ArrayList<String>()
        val keysBytes = ArrayList<ByteArray>()
        if (keysArray != null) {
            for (i in 0 until keysArray.length()) {
                val key = keysArray.optString(i, "").trim().uppercase()
                if (key.matches(Regex("[0-9A-F]{12}"))) {
                    keysHex.add(key)
                    keysBytes.add(hexToBytes(key))
                }
            }
        }
        if (keysBytes.isEmpty()) return@runCatching InfoResult(false, "服务器未返回可用秘钥")

        InfoResult(
            true,
            msg,
            RuntimeCardInfo(
                cardCode = data.optString("card_code", cardCode),
                cardNo = if (data.isNull("card_no")) null else data.optString("card_no"),
                sector = data.optInt("sector", 100),
                keysHex = keysHex,
                keys = keysBytes
            )
        )
    }.getOrElse { InfoResult(false, "获取秘钥失败：${it.message ?: "未知错误"}") }.also { connection.disconnect() }
}

private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
private fun getNfcState(context: Context): NfcState {
    val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcState.NOT_SUPPORTED
    return if (adapter.isEnabled) NfcState.ENABLED else NfcState.DISABLED
}
private fun openNfcSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NFC_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(intent) }.getOrElse {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
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
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
