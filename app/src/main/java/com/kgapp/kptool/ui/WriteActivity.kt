package com.kgapp.kptool.ui

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.input.KeyboardType
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WriteActivity : ComponentActivity() {
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

        // ✅ 状态栏配色：暗色背景 + 浅色图标
        window.statusBarColor = android.graphics.Color.parseColor("#050607")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            HackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WriteScreen(nfcAdapter)
                }
            }
        }
    }
}

/** ✅ 用“稳定状态对象”代替 data class + 反复复制 list（否则输入会炸焦点/卡顿） */
private class WriteItemState(
    val id: Long,
    initBlock: Int,
    initHex: String
) {
    var blockText by mutableStateOf(initBlock.toString())
    var hexText by mutableStateOf(initHex)

    fun blockIndex(): Int {
        val digits = blockText.filter { it.isDigit() }
        return digits.toIntOrNull()?.coerceIn(0, 63) ?: 0
    }
}

/** ====== 配置持久化（写入配置库）====== */
private data class WriteProfile(
    val id: Long,
    val name: String,
    val allowTrailer: Boolean,
    val items: List<WriteProfileItem>
)

private data class WriteProfileItem(
    val block: Int,
    val hex32: String
)

private object WriteProfileStore {
    private const val PREF = "kptool_write_profiles"
    private const val KEY = "profiles_json"

    fun load(context: Context): List<WriteProfile> {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val itemsArr = o.getJSONArray("items")
                    val items = buildList {
                        for (j in 0 until itemsArr.length()) {
                            val it = itemsArr.getJSONObject(j)
                            add(
                                WriteProfileItem(
                                    block = it.getInt("block"),
                                    hex32 = it.getString("hex32")
                                )
                            )
                        }
                    }
                    add(
                        WriteProfile(
                            id = o.getLong("id"),
                            name = o.getString("name"),
                            allowTrailer = o.getBoolean("allowTrailer"),
                            items = items
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun save(context: Context, profiles: List<WriteProfile>) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (p in profiles) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("allowTrailer", p.allowTrailer)
            val itemsArr = JSONArray()
            for (it in p.items) {
                val io = JSONObject()
                io.put("block", it.block)
                io.put("hex32", it.hex32)
                itemsArr.put(io)
            }
            o.put("items", itemsArr)
            arr.put(o)
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }
}

/** ====== 日志 ====== */
private enum class LogLevel { OK, INFO, ERR }

private data class LogEntry(
    val ts: String,
    val level: LogLevel,
    val msg: String
)

@Composable
fun WriteScreen(nfcAdapter: NfcAdapter?) {
    val activity = LocalContext.current as ComponentActivity
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Keys
    var keys by remember {
        mutableStateOf(AppSettings.parseKeysFromText(AppSettings.getKeysText(context)))
    }

    // UI states
    var allowTrailer by rememberSaveable { mutableStateOf(false) }
    var profileExpanded by rememberSaveable { mutableStateOf(true) }
    var writeExpanded by rememberSaveable { mutableStateOf(true) }
    var profileName by rememberSaveable { mutableStateOf("") }

    // Items
    val items = remember {
        mutableStateListOf(
            WriteItemState(id = System.currentTimeMillis(), initBlock = 4, initHex = "")
        )
    }

    // Profiles
    var profiles by remember { mutableStateOf(WriteProfileStore.load(context)) }

    // Status + log
    var status by remember { mutableStateOf("配置好要写的块，然后贴卡 ✍️") }
    val logEntries = remember { mutableStateListOf<LogEntry>() }
    val logListState = rememberLazyListState()

    // 持续写入状态
    var armed by remember { mutableStateOf(false) }         // 持续写入开关
    var writingNow by remember { mutableStateOf(false) }    // 写入中（防并发）
    var lastUid by remember { mutableStateOf<String?>(null) }   // 防抖：上一次写入的UID
    var lastWriteAt by remember { mutableStateOf(0L) }          // 防抖：上一次写入时间

    // ===== helpers =====
    fun nowStr(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    fun normalizeHex(input: String): String =
        input.replace(" ", "").replace("\n", "").trim().uppercase()

    fun isHex32(s: String): Boolean =
        s.length == 32 && s.all { it in "0123456789ABCDEF" }

    fun sectorOf(block: Int): Int = block / 4
    fun indexInSector(block: Int): Int = block % 4

    fun log(level: LogLevel, line: String) {
        logEntries.add(LogEntry(ts = nowStr(), level = level, msg = line))
    }

    // ✅ 日志自动滚到最新
    LaunchedEffect(logEntries.size) {
        if (logEntries.isNotEmpty()) {
            logListState.animateScrollToItem(logEntries.size - 1)
        }
    }

    fun reloadKeys() {
        keys = AppSettings.parseKeysFromText(AppSettings.getKeysText(context))
        status = "已加载 keys：${keys.size} 个"
        log(LogLevel.INFO, "Reload keys => ${keys.size}")
    }

    fun validateItems(): String? {
        if (keys.isEmpty()) return "没有可用 keys：去设置页添加并保存"
        if (items.isEmpty()) return "至少添加一个要写的 block"

        for (it in items) {
            val block = it.blockIndex()
            if (block !in 0..63) return "Block $block 超范围（1K 是 0..63）"
            if (block == 0) return "禁止写 Block 0（厂商块），否则很容易把卡写废"
            val bInSector = indexInSector(block)
            if (!allowTrailer && bInSector == 3) return "当前不允许写 Trailer（每扇区最后一块），去开关开启后再写"

            val hex = normalizeHex(it.hexText)
            if (!isHex32(hex)) return "Block $block 数据必须是 16 bytes（32个hex字符）"
        }
        return null
    }

    fun onTag(tag: Tag) {
        if (!armed) return
        if (writingNow) return

        scope.launch {
            val uid = runCatching { bytesToHex(tag.id ?: byteArrayOf()) }.getOrDefault("UNKNOWN")

            // ✅ 防抖：同一张卡短时间重复触发，避免重复写
            val now = System.currentTimeMillis()
            val debounceMs = 1200L
            if (uid == lastUid && (now - lastWriteAt) < debounceMs) {
                log(LogLevel.INFO, "Debounce ignore UID=$uid (${now - lastWriteAt}ms)")
                return@launch
            }

            log(LogLevel.INFO, "TAG DETECTED uid=$uid")
            val err = validateItems()
            if (err != null) {
                status = "参数不合法 （仍在持续等待）"
                log(LogLevel.ERR, "Validate fail: $err")
                return@launch
            }

            writingNow = true
            status = "写入中…（UID=$uid）"
            log(LogLevel.INFO, "WRITE START uid=$uid items=${items.size} trailer=${allowTrailer}")

            val writeMap = LinkedHashMap<Int, ByteArray>()
            items.forEach { itState ->
                val block = itState.blockIndex()
                val hex = normalizeHex(itState.hexText)
                val s = sectorOf(block)
                val b = indexInSector(block)
                log(LogLevel.INFO, "PLAN  S$s B$b (abs=$block) <= $hex")
                writeMap[block] = MifareClassicTool.hexToBytes(hex)
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    MifareClassicTool.writeBlocks(tag, writeMap, keys, allowTrailer)
                }
            }.getOrElse { e ->
                writingNow = false
                status = "写入失败（仍在持续等待）"
                log(LogLevel.ERR, "WRITE EXCEPTION uid=$uid msg=${e.message ?: "UNKNOWN"}")
                lastUid = uid
                lastWriteAt = System.currentTimeMillis()
                return@launch
            }

            val allOk = result.allSuccess
            log(LogLevel.INFO, "WRITE DONE uid=$uid allOk=$allOk")

            result.details.forEach { r ->
                val block = r.block
                val s = sectorOf(block)
                val b = indexInSector(block)
                if (r.success) {
                    log(LogLevel.OK, "OK    S$s B$b (abs=$block)")
                } else {
                    log(LogLevel.ERR, "FAIL  S$s B$b (abs=$block) reason=${r.message ?: "UNKNOWN"}")
                }
            }

            writingNow = false
            lastUid = uid
            lastWriteAt = System.currentTimeMillis()

            status = if (armed) {
                if (allOk) "写入完成 ✅（继续等待贴卡）" else "部分失败 ⚠️（继续等待贴卡）"
            } else {
                if (allOk) "写入完成 ✅" else "部分失败 ⚠️"
            }
        }
    }

    // NFC ReaderMode（一直开着，但只有 armed=true 才会写）
    DisposableEffect(nfcAdapter, activity) {
        if (nfcAdapter == null) {
            status = "该设备不支持 NFC ❌"
            onDispose { }
        } else {
            val cb = NfcAdapter.ReaderCallback { tag -> onTag(tag) }
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            nfcAdapter.enableReaderMode(activity, cb, flags, null)
            onDispose { runCatching { nfcAdapter.disableReaderMode(activity) } }
        }
    }

    val pageListState = rememberLazyListState()

    LazyColumn(
        state = pageListState,
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
                text = "WRITE//MIFARE",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Mode: ${if (armed) "ARMED" else "IDLE"}  |  ${if (writingNow) "BUSY" else "READY"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        /** ===================== 配置库卡片 ===================== */
        item {
            HackerCard {
                val interaction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = interaction, indication = null) {
                            profileExpanded = !profileExpanded
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("配置库 / Profiles", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Saved=${profiles.size}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (profileExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "expand"
                    )
                }

                if (profileExpanded) {
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("配置名字（可自定义）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val err = validateItems()
                                if (err != null) {
                                    status = "当前配置不合法 ❌"
                                    log(LogLevel.ERR, "Save profile fail: $err")
                                    return@Button
                                }

                                val name = profileName.trim().ifEmpty { "profile_${System.currentTimeMillis()}" }
                                val newProfile = WriteProfile(
                                    id = System.currentTimeMillis(),
                                    name = name,
                                    allowTrailer = allowTrailer,
                                    items = items.map {
                                        WriteProfileItem(
                                            block = it.blockIndex(),
                                            hex32 = normalizeHex(it.hexText)
                                        )
                                    }
                                )

                                val next = profiles.toMutableList()
                                val idx = next.indexOfFirst { it.name == name }
                                if (idx >= 0) next[idx] = newProfile else next.add(0, newProfile)

                                profiles = next
                                WriteProfileStore.save(context, profiles)

                                status = "已保存配置 ✅：$name"
                                log(LogLevel.INFO, "Save profile => $name items=${newProfile.items.size} trailer=${newProfile.allowTrailer}")
                            }
                        ) { Text("保存当前配置", fontFamily = FontFamily.Monospace) }

                        OutlinedButton(
                            onClick = {
                                profiles = WriteProfileStore.load(context)
                                status = "已刷新配置库 ✅"
                                log(LogLevel.INFO, "Refresh profiles => ${profiles.size}")
                            }
                        ) { Text("刷新", fontFamily = FontFamily.Monospace) }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (profiles.isEmpty()) {
                        Text("暂无配置～先保存一个吧 😼", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            profiles.forEach { p ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(p.name, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                            Text(
                                                "items=${p.items.size} | trailer=${p.allowTrailer}",
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                allowTrailer = p.allowTrailer
                                                items.clear()
                                                p.items.forEach {
                                                    items.add(
                                                        WriteItemState(
                                                            id = System.currentTimeMillis() + it.block,
                                                            initBlock = it.block,
                                                            initHex = it.hex32
                                                        )
                                                    )
                                                }
                                                status = "已加载配置 ✅：${p.name}"
                                                log(LogLevel.INFO, "Load profile => ${p.name} items=${p.items.size}")
                                            }
                                        ) { Text("加载", fontFamily = FontFamily.Monospace) }

                                        Spacer(Modifier.width(8.dp))

                                        IconButton(
                                            onClick = {
                                                profiles = profiles.filterNot { it.id == p.id }
                                                WriteProfileStore.save(context, profiles)
                                                status = "已删除配置 🗑️"
                                                log(LogLevel.INFO, "Delete profile => ${p.name}")
                                            }
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "delete")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        /** ===================== 写入配置卡片 ===================== */
        item {
            HackerCard {
                val interaction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = interaction, indication = null) {
                            writeExpanded = !writeExpanded
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("写入配置 / Payload", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "items=${items.size} | keys=${keys.size} | trailer=${if (allowTrailer) "ON" else "OFF"}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (writeExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "expand"
                    )
                }

                if (writeExpanded) {
                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { reloadKeys() }) { Text("加载Keys", fontFamily = FontFamily.Monospace) }

                        OutlinedButton(onClick = {
                            items.add(WriteItemState(System.currentTimeMillis(), 4, ""))
                            log(LogLevel.INFO, "Add item => size=${items.size}")
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "add")
                            Spacer(Modifier.width(6.dp))
                            Text("新增条目", fontFamily = FontFamily.Monospace)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = allowTrailer,
                            onCheckedChange = {
                                allowTrailer = it
                                log(LogLevel.INFO, "Trailer write => ${if (allowTrailer) "ON" else "OFF"}")
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("允许写 Trailer（危险）", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }

                    Spacer(Modifier.height(12.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items.forEachIndexed { idx, item ->
                            key(item.id) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "ITEM #${idx + 1}",
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(onClick = {
                                                items.remove(item)
                                                log(LogLevel.INFO, "Remove item => size=${items.size}")
                                            }) {
                                                Icon(Icons.Default.Delete, contentDescription = "delete")
                                            }
                                        }

                                        Spacer(Modifier.height(8.dp))

                                        val block = item.blockIndex()
                                        val sector = sectorOf(block)
                                        val bInSector = indexInSector(block)

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = item.blockText,
                                                onValueChange = { v ->
                                                    item.blockText = v.filter { it.isDigit() }.take(2)
                                                },
                                                label = { Text("Block (0-63)") },
                                                singleLine = true,
                                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.weight(0.45f),
                                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                                            )

                                            Spacer(Modifier.width(12.dp))

                                            Text(
                                                "S$sector / B$bInSector",
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(0.55f)
                                            )
                                        }

                                        Spacer(Modifier.height(10.dp))

                                        OutlinedTextField(
                                            value = item.hexText,
                                            onValueChange = { item.hexText = it },
                                            label = { Text("HEX 16 bytes (32 chars)") },
                                            placeholder = { Text("00112233445566778899AABBCCDDEEFF") },
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                                        )

                                        val hexNorm = normalizeHex(item.hexText)
                                        val ok = isHex32(hexNorm)
                                        val warn = when {
                                            block == 0 -> "当前设置策略禁止写 Block 0"
                                            (!allowTrailer && block % 4 == 3) -> "Trailer 禁止写 ，请允许写Trailer以继续"
                                            !ok && hexNorm.isNotBlank() -> "格式错误：必须 32 个 hex"
                                            else -> null
                                        }
                                        if (warn != null) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(warn, color = HackerRed, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (!armed) {
                                val err = validateItems()
                                if (err != null) {
                                    status = "参数不合法 ❌"
                                    log(LogLevel.ERR, "Arm fail: $err")
                                    armed = false
                                } else {
                                    armed = true
                                    status = "持续写入模式 ✅（贴卡就写，点“停止写入”结束）"
                                    val activity = context as ComponentActivity
                                    www.sanju.motiontoast.MotionToast.darkToast(
                                        activity,
                                        "持续写入启动",
                                        "对识别的MCT标签进行写入",
                                        www.sanju.motiontoast.MotionToastStyle.SUCCESS,
                                        www.sanju.motiontoast.MotionToast.GRAVITY_BOTTOM,
                                        www.sanju.motiontoast.MotionToast.LONG_DURATION,
                                        null   //不传字体也行
                                    )


                                    log(LogLevel.INFO, "ARMED => waiting tags…")
                                }
                            } else {
                                armed = false
                                status = "已停止写入 ⛔"
                                val activity = context as ComponentActivity
                                www.sanju.motiontoast.MotionToast.darkToast(
                                    activity,
                                    "持续写入停止",
                                    "停止对识别的MCT标签写入",
                                    www.sanju.motiontoast.MotionToastStyle.INFO,
                                    www.sanju.motiontoast.MotionToast.GRAVITY_BOTTOM,
                                    www.sanju.motiontoast.MotionToast.LONG_DURATION,
                                    null   //不传字体也行
                                )
                                log(LogLevel.INFO, "DISARMED => ignore tags")
                            }
                        },
                        enabled = !writingNow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (armed) "停止写入" else "开始写入（持续）", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        item {
            Text(
                text = "状态：$status",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        /** ===================== 日志卡片 ===================== */
        item {
            HackerCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "LOG//STREAM",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (armed) "ARMED" else "IDLE",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (armed) HackerGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }

                Spacer(Modifier.height(10.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 420.dp)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (logEntries.isEmpty()) {
                            item {
                                Text(
                                    "暂无日志",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            itemsIndexed(logEntries) { _, e ->
                                val c = when (e.level) {
                                    LogLevel.OK -> HackerGreen
                                    LogLevel.INFO -> HackerOrange
                                    LogLevel.ERR -> HackerRed
                                }
                                Text(
                                    text = "[${e.ts}] ${e.msg}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = c
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            logEntries.clear()
                            log(LogLevel.INFO, "Log cleared")
                        }
                    ) { Text("清空日志", fontFamily = FontFamily.Monospace) }

                    OutlinedButton(
                        onClick = { reloadKeys() }
                    ) { Text("刷新Keys", fontFamily = FontFamily.Monospace) }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** ====== “黑客风”卡片容器 ====== */
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

private val HackerGreen = Color(0xFF00FF7A)   // ✅ 成功
private val HackerOrange = Color(0xFFFFA43A)  // ⚙️ 信息/设置
private val HackerRed = Color(0xFFFF4D5A)     // ❌ 错误

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
