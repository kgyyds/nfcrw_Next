package com.kgapp.kptool.ui

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kgapp.kptool.AppSettings
import com.kgapp.kptool.nfc.MifareClassicTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReadActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null

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

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            HackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReadScreen(nfcAdapter)
                }
            }
        }
    }
}

/** ====== 日志类型（多色输出）====== */
private enum class LogType { OK, INFO, WARN, ERROR, DATA }

private data class LogLine(
    val time: String,
    val type: LogType,
    val msg: String
)

@Composable
fun ReadScreen(nfcAdapter: NfcAdapter?) {
    val activity = LocalContext.current as ComponentActivity
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val maxSector = 15 // Mifare Classic 1K：0..15

    // keys：从设置里读
    var keys by remember {
        mutableStateOf(AppSettings.parseKeysFromText(AppSettings.getKeysText(context)))
    }

    // ✅ 用 SnapshotStateList 避免 copyOf() 卡顿
    val checkedSectors = remember {
        mutableStateListOf<Boolean>().apply { repeat(maxSector + 1) { add(false) } }
    }

    // UI
    var selectorExpanded by rememberSaveable { mutableStateOf(false) }
    var includeTrailer by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("先选择扇区，再贴卡 📶") }

    // 最终 dump
    var output by remember { mutableStateOf("") }
    val outScroll = rememberScrollState()

    // ✅ 实时日志（彩色）
    val logs = remember { mutableStateListOf<LogLine>() }
    val logListState = rememberLazyListState()

    // 防并发
    var readingNow by remember { mutableStateOf(false) }

    fun nowStr(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    fun log(type: LogType, msg: String) {
        logs.add(LogLine(nowStr(), type, msg))
    }

    fun reloadKeys() {
        keys = AppSettings.parseKeysFromText(AppSettings.getKeysText(context))
        status = "已加载 keys：${keys.size} 个"
        log(LogType.INFO, "KEYS RELOAD => ${keys.size}")
    }

    fun selectedSectors(): List<Int> =
        checkedSectors.mapIndexedNotNull { idx, v -> if (v) idx else null }

    fun sectorSummary(): String {
        val s = selectedSectors()
        if (s.isEmpty()) return "NONE"
        return if (s.size <= 6) s.joinToString(",")
        else s.take(6).joinToString(",") + "…(${s.size})"
    }

    fun buildBlocksForSector(sec: Int): List<Int> {
        val base = sec * 4
        val list = arrayListOf(base, base + 1, base + 2)
        if (includeTrailer) list.add(base + 3)
        return list
    }

    fun sectorOf(block: Int) = block / 4
    fun indexInSector(block: Int) = block % 4

    /** ✅ 自动滚动到最新日志 */
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            runCatching { logListState.animateScrollToItem(logs.lastIndex) }
        }
    }

    fun buildDumpText(uid: String, sectors: List<Int>, allMap: Map<Int, ByteArray?>): String {
        val sb = StringBuilder()
        sb.append("TIME: ").append(nowStr()).append('\n')
        sb.append("UID : ").append(uid).append('\n')
        sb.append("MODE: trailer=").append(if (includeTrailer) "ON" else "OFF").append('\n')
        sb.append("SECT: ").append(sectors.joinToString(",")).append('\n')
        sb.append('\n')

        for (sec in sectors.sorted()) {
            sb.append("== SECTOR ").append(sec).append(" ==\n")
            val base = sec * 4
            val end = base + 3
            for (b in base..end) {
                if (!includeTrailer && b == end) continue
                val data = allMap[b]
                val local = b - base
                if (data != null) {
                    sb.append("S").append(sec)
                        .append(" B").append(local)
                        .append(" [").append(b).append("] = ")
                        .append(MifareClassicTool.bytesToHex(data))
                        .append('\n')
                } else {
                    sb.append("S").append(sec)
                        .append(" B").append(local)
                        .append(" [").append(b).append("] = (no data)\n")
                }
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun onTag(tag: Tag) {
        if (readingNow) return

        scope.launch {
            val sectors = selectedSectors()
            if (keys.isEmpty()) {
                status = "没有可用 keys ❌ 去设置页添加并保存"
                log(LogType.ERROR, "NO KEYS => go Settings")
                return@launch
            }
            if (sectors.isEmpty()) {
                status = "你没勾选任何扇区 😼"
                log(LogType.WARN, "NO SECTOR SELECTED")
                return@launch
            }

            readingNow = true
            try {
                val uid = runCatching { bytesToHex(tag.id ?: byteArrayOf()) }.getOrDefault("UNKNOWN")
                status = "READING… uid=$uid"

                log(LogType.INFO, "TAG DETECTED => UID=$uid")
                log(LogType.INFO, "MODE => trailer=${if (includeTrailer) "ON" else "OFF"} | keys=${keys.size}")
                log(LogType.INFO, "TARGET => sectors=${sectors.size} (${sectorSummary()})")

                val allMap = LinkedHashMap<Int, ByteArray?>()
                var okCount = 0
                var failCount = 0

                // ✅ 按扇区读：有过程感，也方便日志精确到块
                for (sec in sectors.sorted()) {
                    log(LogType.DATA, ">> SECTOR $sec START")
                    val blocks = buildBlocksForSector(sec)

                    blocks.forEach { b ->
                        log(LogType.DATA, "READ  S${sectorOf(b)} B${indexInSector(b)} [abs $b] ...")
                    }

                    // ✅ 关键修复：不用 runCatching/getOrElse 里 continue（会触发实验特性）
                    val map: Map<Int, ByteArray>?
                    try {
                        map = withContext(Dispatchers.IO) {
                            MifareClassicTool.readBlocks(tag, blocks, keys)
                        }
                    } catch (e: Exception) {
                        val reason = e.message ?: "未知错误"
                        log(LogType.ERROR, "!! SECTOR $sec FAIL => $reason")
                        blocks.forEach { b ->
                            allMap[b] = null
                            failCount++
                            log(LogType.ERROR, "FAIL S${sectorOf(b)} B${indexInSector(b)} [abs $b] => $reason")
                        }
                        log(LogType.DATA, "<< SECTOR $sec END")
                        continue
                    }

                    // 扇区读成功：逐块判定
                    blocks.forEach { b ->
                        val data = map[b]
                        if (data != null) {
                            allMap[b] = data
                            okCount++
                            log(LogType.OK, "OK   S${sectorOf(b)} B${indexInSector(b)} [abs $b]  (${data.size} bytes)")
                        } else {
                            allMap[b] = null
                            failCount++
                            log(LogType.ERROR, "FAIL S${sectorOf(b)} B${indexInSector(b)} [abs $b] => (no data)")
                        }
                    }

                    log(LogType.DATA, "<< SECTOR $sec END")
                }

                output = buildDumpText(uid, sectors, allMap)

                status = if (failCount == 0) {
                    "READ DONE ✅ ok=$okCount fail=$failCount"
                } else {
                    "READ DONE ⚠️ ok=$okCount fail=$failCount"
                }

                log(
                    if (failCount == 0) LogType.OK else LogType.WARN,
                    "DONE => uid=$uid | ok=$okCount | fail=$failCount"
                )
            } finally {
                readingNow = false
            }
        }
    }

    // ReaderMode（贴卡触发）
    DisposableEffect(nfcAdapter, activity) {
        if (nfcAdapter == null) {
            status = "该设备不支持 NFC ❌"
            log(LogType.ERROR, "NFC NOT SUPPORTED")
            onDispose { }
        } else {
            val cb = NfcAdapter.ReaderCallback { tag -> onTag(tag) }
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            nfcAdapter.enableReaderMode(activity, cb, flags, null)
            log(LogType.INFO, "READER MODE ENABLED")
            onDispose {
                runCatching { nfcAdapter.disableReaderMode(activity) }
                log(LogType.INFO, "READER MODE DISABLED")
            }
        }
    }

    fun colorFor(type: LogType): Color = when (type) {
        LogType.OK -> HackerGreen
        LogType.INFO -> HackerOrange
        LogType.WARN -> Color(0xFFB7FF4A) // 警告：黄绿
        LogType.ERROR -> HackerRed
        LogType.DATA -> Color(0xFF37E6FF) // 过程/数据：青色
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "READ//MIFARE",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "SECTORS=${maxSector + 1} | ${sectorSummary()} | TRAILER=${if (includeTrailer) "ON" else "OFF"} | KEYS=${keys.size}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 扇区选择卡片（可折叠）
        item {
            HackerCard {
                val interaction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = interaction, indication = null) {
                            selectorExpanded = !selectorExpanded
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "SELECT SECTORS (0-$maxSector)",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "CURRENT: ${sectorSummary()}  |  TRAILER: ${if (includeTrailer) "ON" else "OFF"}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (selectorExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "expand"
                    )
                }

                if (selectorExpanded) {
                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            for (i in 0..maxSector) checkedSectors[i] = true
                            log(LogType.INFO, "SELECT ALL")
                        }) { Text("全选", fontFamily = FontFamily.Monospace) }

                        OutlinedButton(onClick = {
                            for (i in 0..maxSector) checkedSectors[i] = false
                            log(LogType.INFO, "SELECT NONE")
                        }) { Text("全不选", fontFamily = FontFamily.Monospace) }

                        OutlinedButton(onClick = { reloadKeys() }) {
                            Text("加载Keys", fontFamily = FontFamily.Monospace)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = includeTrailer,
                            onCheckedChange = {
                                includeTrailer = it
                                log(LogType.INFO, "TRAILER => ${if (it) "ON" else "OFF"}")
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "包含 Trailer（每扇区最后一块）",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items((0..maxSector).toList(), key = { it }) { idx ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                        .clickable {
                                            checkedSectors[idx] = !checkedSectors[idx]
                                            log(LogType.DATA, "TOGGLE S$idx => ${checkedSectors[idx]}")
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checkedSectors[idx],
                                        onCheckedChange = { v ->
                                            checkedSectors[idx] = v
                                            log(LogType.DATA, "TOGGLE S$idx => $v")
                                        }
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "S$idx",
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "状态：$status",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        /** ===================== 实时日志 ===================== */
        item {
            HackerCard {
                Text(
                    "RUN//LOG",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(10.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 420.dp)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(logs.size) { i ->
                            val line = logs[i]
                            Text(
                                text = "[${line.time}] ${line.msg}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorFor(line.type)
                            )
                        }
                        if (logs.isEmpty()) {
                            item {
                                Text(
                                    "NO LOG YET…",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        enabled = !readingNow,
                        onClick = {
                            logs.clear()
                            log(LogType.INFO, "LOG CLEARED")
                        }
                    ) { Text("清空日志", fontFamily = FontFamily.Monospace) }

                    OutlinedButton(
                        enabled = !readingNow,
                        onClick = { reloadKeys() }
                    ) { Text("刷新Keys", fontFamily = FontFamily.Monospace) }
                }
            }
        }

        /** ===================== 最终 DUMP 输出 ===================== */
        item {
            HackerCard {
                Text(
                    "DUMP//OUTPUT (16 bytes/line)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(10.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 520.dp)
                            .padding(12.dp)
                            .verticalScroll(outScroll)
                    ) {
                        Text(
                            text = if (output.isBlank()) "暂无结果，贴卡开始读取～" else output,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        enabled = !readingNow,
                        onClick = { output = "" }
                    ) { Text("清空输出", fontFamily = FontFamily.Monospace) }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
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
