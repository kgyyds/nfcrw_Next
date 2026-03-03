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
import com.kgapp.kptool.AppSession
import com.kgapp.kptool.AppSettings
import com.kgapp.kptool.nfc.MifareClassicTool
import com.kgapp.kptool.nfc.ValuePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

private data class AmountInfo(
    val sourceBlock: Int,
    val rawValue: Int,
    val displayText: String,
    val k: Int,
    val checksum: Int,
    val sLow: Int,
    val expected: Int,
    val checksumOk: Boolean,
    val hex32: String
)

private fun parseAmount(blockIndex: Int, data16: ByteArray): AmountInfo {
    val b0 = data16[0].toInt() and 0xFF
    val b1 = data16[1].toInt() and 0xFF
    val rawValue = (b1 shl 8) or b0
    var sum = 0
    for (i in 0..13) {
        sum += data16[i].toInt() and 0xFF
    }
    val sLow = sum and 0xFF
    val k = data16[14].toInt() and 0xFF
    val checksum = data16[15].toInt() and 0xFF
    val expected = (sLow + k) and 0xFF
    val checksumOk = checksum == expected
    val displayText = String.format(Locale.US, "%.2f", rawValue / 100.0)
    val hex32 = data16.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    return AmountInfo(
        sourceBlock = blockIndex,
        rawValue = rawValue,
        displayText = displayText,
        k = k,
        checksum = checksum,
        sLow = sLow,
        expected = expected,
        checksumOk = checksumOk,
        hex32 = hex32
    )
}

private fun pickAmount(baseBlock: Int, b0: ByteArray?, b1: ByteArray?): Pair<AmountInfo?, String?> {
    val block0 = b0?.takeIf { it.size == 16 }
    val block1 = b1?.takeIf { it.size == 16 }
    val mismatchWarn = if (block0 != null && block1 != null && !block0.contentEquals(block1)) {
        "BLOCK MISMATCH => B$baseBlock != B${baseBlock + 1}"
    } else {
        null
    }
    return when {
        block0 != null -> parseAmount(baseBlock, block0) to mismatchWarn
        block1 != null -> parseAmount(baseBlock + 1, block1) to mismatchWarn
        else -> null to mismatchWarn
    }
}

private data class DeductResult(
    val success: Boolean,
    val msg: String,
    val rechargeId: String? = null,
    val remainTimes: Int? = null,
)

private data class ConfirmResult(
    val success: Boolean,
    val msg: String,
)

private suspend fun requestDeduct(cardCode: String, cardNo: String, amount: Int): DeductResult = withContext(Dispatchers.IO) {
    val conn = (URL("https://api.33app.top/deduct.php").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 10_000
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    }
    return@withContext runCatching {
        val body = "card_code=${URLEncoder.encode(cardCode, "UTF-8")}" +
            "&card_no=${URLEncoder.encode(cardNo, "UTF-8")}" +
            "&amount=${URLEncoder.encode(amount.toString(), "UTF-8")}"
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val response = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream)
            .bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val code = json.optInt("code", -1)
        val msg = json.optString("msg", "未知错误")
        if (code == 0) {
            val data = json.optJSONObject("data")
            DeductResult(
                success = true,
                msg = msg,
                rechargeId = data?.optString("recharge_id"),
                remainTimes = data?.optInt("remain_times")
            )
        } else {
            DeductResult(false, msg)
        }
    }.getOrElse { DeductResult(false, "扣次请求失败：${it.message ?: "未知错误"}") }
        .also { conn.disconnect() }
}

private suspend fun requestConfirm(rechargeId: String): ConfirmResult = withContext(Dispatchers.IO) {
    val conn = (URL("https://api.33app.top/confirm.php").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 10_000
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    }
    return@withContext runCatching {
        val body = "recharge_id=${URLEncoder.encode(rechargeId, "UTF-8")}&status=done"
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val response = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream)
            .bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val code = json.optInt("code", -1)
        val msg = json.optString("msg", "未知错误")
        ConfirmResult(code == 0, msg)
    }.getOrElse { ConfirmResult(false, "确认请求失败：${it.message ?: "未知错误"}") }
        .also { conn.disconnect() }
}

@Composable
fun ReadScreen(nfcAdapter: NfcAdapter?) {
    val activity = LocalContext.current as ComponentActivity
    val context = LocalContext.current
    val showDetailedLogs = remember { AppSettings.isDetailedLogsEnabled(context) }
    val scope = rememberCoroutineScope()

    val loginInfo = remember { AppSession.getLoginInfo() }
    val serverAllowAmount = (loginInfo?.allowAmount ?: 0).coerceAtLeast(0)

    val maxSector = 15 // Mifare Classic 1K：0..15
    val runtimeInfo = remember { AppSession.getRuntimeInfo() }
    val isSectorSelectable = runtimeInfo?.sector == 100
    val fixedSector = runtimeInfo?.sector?.takeIf { it in 0..maxSector }

    // keys：登录后从云端获取
    var keys by remember {
        mutableStateOf(runtimeInfo?.keys ?: emptyList())
    }

    // ✅ 用 SnapshotStateList 避免 copyOf() 卡顿
    val checkedSectors = remember {
        mutableStateListOf<Boolean>().apply { repeat(maxSector + 1) { add(false) } }
    }

    // UI
    var selectorExpanded by rememberSaveable { mutableStateOf(false) }
    var includeTrailer by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("等待读卡") }

    // 最终 dump
    var output by remember { mutableStateOf("") }
    val outScroll = rememberScrollState()

    var amountInfo by remember { mutableStateOf<AmountInfo?>(null) }
    var amountWarn by remember { mutableStateOf<String?>(null) }

    // ✅ 实时日志（彩色）
    val logs = remember { mutableStateListOf<LogLine>() }
    val logListState = rememberLazyListState()

    // 防并发 + 手动触发
    var readingNow by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf("NONE") }
    val kOptions = listOf(0x39, 0x01, 0x59, 0xC9, 0x91)
    var selectedK by rememberSaveable { mutableStateOf(kOptions.first()) }

    fun nowStr(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    fun log(type: LogType, msg: String) {
        if (!showDetailedLogs) return
        logs.add(LogLine(nowStr(), type, msg))
    }

    fun reloadKeys() {
        keys = AppSession.getRuntimeInfo()?.keys ?: emptyList()
        status = "已同步云端 keys：${keys.size} 个"
        log(LogType.INFO, "CLOUD KEYS SYNC => ${keys.size}")
    }

    fun selectedSectors(): List<Int> =
        if (isSectorSelectable) checkedSectors.mapIndexedNotNull { idx, v -> if (v) idx else null }
        else fixedSector?.let { listOf(it) } ?: emptyList()

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
        if (pendingAction == "NONE" || readingNow) return

        scope.launch {
            val action = pendingAction
            pendingAction = "NONE"
            val sectors = selectedSectors()
            if (keys.isEmpty()) {
                status = "没有可用云端 keys，请先登录"
                log(LogType.ERROR, "NO KEYS => go Settings")
                return@launch
            }
            if (action == "READ" && sectors.isEmpty()) {
                status = "未勾选任何扇区"
                log(LogType.WARN, "NO SECTOR SELECTED")
                return@launch
            }

            readingNow = true
            try {
                val uid = runCatching { bytesToHex(tag.id ?: byteArrayOf()) }.getOrDefault("UNKNOWN")
                if (action == "WRITE") {
                    val targetSector = selectedSectors().firstOrNull() ?: fixedSector ?: 15
                    val baseBlock = targetSector * 4
                    val amountFen = serverAllowAmount * 100
                    val cardCode = loginInfo?.cardCode

                    if (cardCode.isNullOrBlank()) {
                        status = "未登录或卡密缺失，禁止刷入"
                        log(LogType.ERROR, "WRITE ABORT => missing card_code")
                        return@launch
                    }
                    if (serverAllowAmount <= 0) {
                        status = "allow_amount 异常，禁止刷入"
                        log(LogType.ERROR, "WRITE ABORT => invalid allow_amount=$serverAllowAmount")
                        return@launch
                    }

                    status = "预读取卡片信息… uid=$uid"
                    val preReadOk = runCatching {
                        withContext(Dispatchers.IO) {
                            MifareClassicTool.readBlocks(tag, listOf(baseBlock, baseBlock + 1), keys)
                        }
                    }.isSuccess
                    if (!preReadOk) {
                        status = "读取卡片失败，无法扣次"
                        log(LogType.ERROR, "WRITE ABORT => pre-read failed")
                        return@launch
                    }

                    status = "请求服务器扣次中…"
                    val deduct = requestDeduct(cardCode = cardCode, cardNo = uid, amount = serverAllowAmount)
                    if (!deduct.success || deduct.rechargeId.isNullOrBlank()) {
                        status = "扣次失败：${deduct.msg}"
                        log(LogType.ERROR, "DEDUCT FAIL => ${deduct.msg}")
                        return@launch
                    }
                    log(LogType.OK, "DEDUCT OK => recharge_id=${deduct.rechargeId} remain=${deduct.remainTimes ?: "?"}")

                    status = "WRITING… uid=$uid"
                    val payload = ValuePayload.build(amountFen, selectedK)
                    val writeMap = linkedMapOf(baseBlock to payload, (baseBlock + 1) to payload)
                    val writeResult = withContext(Dispatchers.IO) {
                        MifareClassicTool.writeBlocks(tag, writeMap, keys, false)
                    }
                    val allOk = writeResult.allSuccess
                    amountInfo = parseAmount(baseBlock, payload)
                    amountWarn = null
                    if (!allOk) {
                        status = "WRITE FAIL ⚠️ 已扣次未完全写入"
                        log(LogType.ERROR, "WRITE FAIL => recharge_id=${deduct.rechargeId}")
                        return@launch
                    }

                    status = "校验写入结果中…"
                    val verifyMap = runCatching {
                        withContext(Dispatchers.IO) {
                            MifareClassicTool.readBlocks(tag, listOf(baseBlock, baseBlock + 1), keys)
                        }
                    }.getOrNull()
                    val verifyAmount = pickAmount(baseBlock, verifyMap?.get(baseBlock), verifyMap?.get(baseBlock + 1)).first
                    val verifyOk = verifyAmount != null && verifyAmount.rawValue == amountFen
                    if (!verifyOk) {
                        status = "写入校验失败，未发送确认"
                        log(LogType.ERROR, "VERIFY FAIL => expect=$amountFen actual=${verifyAmount?.rawValue}")
                        return@launch
                    }

                    val confirm = requestConfirm(deduct.rechargeId)
                    status = if (confirm.success) {
                        "WRITE DONE ✅ amount=$serverAllowAmount"
                    } else {
                        "WRITE DONE ⚠️ 确认失败：${confirm.msg}"
                    }
                    log(
                        if (confirm.success) LogType.OK else LogType.WARN,
                        "WRITE+CONFIRM uid=$uid amount=$serverAllowAmount recharge_id=${deduct.rechargeId} msg=${confirm.msg}"
                    )
                    return@launch
                }
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

                val targetSector = selectedSectors().firstOrNull() ?: fixedSector ?: 15
                val baseBlock = targetSector * 4
                val existing60 = allMap[baseBlock]
                val existing61 = allMap[baseBlock + 1]
                var block60: ByteArray? = existing60
                var block61: ByteArray? = existing61
                if (block60 == null && block61 == null) {
                    log(LogType.INFO, "AMOUNT EXTRA READ => blocks=$baseBlock,${baseBlock + 1}")
                    val extraMap = try {
                        withContext(Dispatchers.IO) {
                            MifareClassicTool.readBlocks(tag, listOf(baseBlock, baseBlock + 1), keys)
                        }
                    } catch (e: Exception) {
                        val reason = e.message ?: "未知错误"
                        log(LogType.WARN, "AMOUNT EXTRA READ FAIL => $reason")
                        null
                    }
                    block60 = extraMap?.get(baseBlock)
                    block61 = extraMap?.get(baseBlock + 1)
                }

                val (pickedAmount, warn) = pickAmount(baseBlock, block60, block61)
                amountInfo = pickedAmount
                amountWarn = warn
                if (warn != null) {
                    log(LogType.WARN, warn)
                }
                if (pickedAmount != null) {
                    log(
                        LogType.INFO,
                        "AMOUNT => raw=${pickedAmount.rawValue} display=${pickedAmount.displayText} src=B${pickedAmount.sourceBlock}"
                    )
                    if (!pickedAmount.checksumOk) {
                        log(
                            LogType.WARN,
                            "CHECKSUM BAD => K=0x%02X got=0x%02X exp=0x%02X".format(
                                pickedAmount.k,
                                pickedAmount.checksum,
                                pickedAmount.expected
                            )
                        )
                    }
                }

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
            status = "该设备不支持 NFC "
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
            Card(
                colors = CardDefaults.cardColors(containerColor = HackerGreen.copy(alpha = 0.18f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        text = "剩余金额",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFB8FFD8)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = amountInfo?.displayText?.let { "¥$it" } ?: "--",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = HackerGreen
                    )
                    if (amountWarn != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = amountWarn!!,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFB7FF4A)
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    if (keys.isEmpty()) {
                        status = "没有可用云端 keys，请先登录"
                        log(LogType.ERROR, "NO KEYS => go Settings")
                    } else if (selectedSectors().isEmpty()) {
                        status = "未勾选任何扇区"
                        log(LogType.WARN, "NO SECTOR SELECTED")
                    } else {
                        pendingAction = "READ"
                        status = "请贴卡执行一次读取 📶"
                        log(LogType.INFO, "ARMED READ => waiting one tag")
                    }
                },
                enabled = !readingNow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (pendingAction == "READ") "等待贴卡中…" else "开始读取（手动）", fontFamily = FontFamily.Monospace)
            }
        }

        item {
            HackerCard {
                Text(
                    "WRITE//AMOUNT",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "刷入金额由服务端下发（allow_amount）：¥$serverAllowAmount",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "K值选择：0x%02X".format(selectedK),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    kOptions.forEach { kValue ->
                        FilterChip(
                            selected = selectedK == kValue,
                            onClick = { selectedK = kValue },
                            label = { Text("0x%02X".format(kValue), fontFamily = FontFamily.Monospace) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        pendingAction = "WRITE"
                        status = "请贴卡执行一次写入 ¥$serverAllowAmount / K=0x%02X ✍️".format(selectedK)
                        log(LogType.INFO, "ARMED WRITE => amount=$serverAllowAmount k=0x${"%02X".format(selectedK)}")
                    },
                    enabled = !readingNow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (pendingAction == "WRITE") "等待贴卡写入中…" else "开始写入（手动）",
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

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
                text = "SECTOR_MODE=${if (isSectorSelectable) "SELECTABLE" else "FIXED"} | ${sectorSummary()} | TRAILER=${if (includeTrailer) "ON" else "OFF"} | KEYS=${keys.size}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 扇区选择卡片（仅 sector=100 时可选）
        if (isSectorSelectable) item {
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


        if (!isSectorSelectable) item {
            HackerCard {
                Text(
                    "SECTOR POLICY",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前为固定扇区：S${fixedSector ?: "?"}（由服务端 info.php 下发）",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

        item {
            HackerCard {
                Text(
                    "AMOUNT//PARSE (B60/B61)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(10.dp))

                val warnColor = Color(0xFFB7FF4A)

                if (amountInfo != null) {
                    val info = amountInfo!!
                    val statusColor = if (info.checksumOk) HackerGreen else HackerRed
                    Text(
                        "Amount: ${info.displayText} (raw=${info.rawValue})",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "Source: B${info.sourceBlock}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "K: 0x%02X  checksum: 0x%02X  expected: 0x%02X".format(
                            info.k,
                            info.checksum,
                            info.expected
                        ),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "Status: ${if (info.checksumOk) "CHECKSUM OK" else "CHECKSUM BAD"}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = statusColor
                    )
                    if (amountWarn != null) {
                        Text(
                            amountWarn!!,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = warnColor
                        )
                    }
                    Text(
                        "Hex: ${info.hex32}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "未读取到 60/61（请确认密钥或贴卡重试）",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        /** ===================== 实时日志 ===================== */
        if (showDetailedLogs) item {
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
                            text = if (output.isBlank()) "暂无结果，点击上方按钮后贴卡读取～" else output,
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
