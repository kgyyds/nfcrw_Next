package com.kgapp.kptool.toolact

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/* ===================== 数据模型（保证 UI 字段永远不空） ===================== */

data class KPToolStats(
    val cpu: CpuInfo = CpuInfo(),
    val gpu: GpuInfo = GpuInfo(),
    val ram: RamInfo = RamInfo()
)

data class CpuCoreInfo(
    val index: Int,
    val usage: Float,
    val usageText: String,
    val freqMHz: Int? = null
)

data class CpuInfo(
    val usage: Float = 0f,
    val usageText: String = "--%",
    val freqMHz: Int? = null,
    val tempC: Float? = null,
    val tempText: String = "读取失败",
    val extraText: String = "频率 -- MHz · 温度 读取失败",
    val cores: List<CpuCoreInfo> = emptyList(),
    val tempSource: String = "未命中温度节点"
)

data class GpuStaticInfo(
    val platform: String? = null,
    val hardware: String? = null,
    val model: String? = null,
    val rendererHint: String? = null
)

data class GpuParams(
    val devfreqBase: String? = null,
    val governor: String? = null,
    val minFreqMHz: Int? = null,
    val maxFreqMHz: Int? = null,
    val availFreqMHz: List<Int> = emptyList()
)

data class GpuInfo(
    val usage: Float = 0f,
    val usageText: String = "--%",
    val freqMHz: Int? = null,
    val tempC: Float? = null,
    val tempText: String = "读取失败",
    val extraText: String = "频率 -- MHz · 温度 读取失败",
    val history: List<Float> = emptyList(),
    val staticInfo: GpuStaticInfo = GpuStaticInfo(),
    val params: GpuParams = GpuParams(),
    val usageSource: String = "未知",
    val freqSource: String = "未知",
    val devfreqSource: String = "无",
    val tempSource: String = "未命中温度节点"
)

data class ZramInfo(
    val enabled: Boolean = false,
    val algorithm: String? = null,
    val diskSizeGB: Double? = null,
    val origDataGB: Double? = null,
    val comprDataGB: Double? = null,
    val memUsedGB: Double? = null,
    val ratio: Double? = null
)

data class RamInfo(
    val totalGB: Double = 0.0,
    val usedGB: Double = 0.0,
    val cachedGB: Double = 0.0,
    val realAvailGB: Double = 0.0,
    val freeRealGB: Double = 0.0,
    val swapUsedGB: Double = 0.0,
    val swapTotalGB: Double = 0.0,
    val swapText: String = "未启用",
    val usage: Float = 0f,
    val valueText: String = "-- / -- GB",
    val extraText: String = "可用 -- GB · Swap 未启用",
    val algorithmText: String = "",
    val zram: ZramInfo = ZramInfo()
)

/* ===================== 对外：1s 刷新 ===================== */

@Composable
fun rememberKPToolStats(refreshMs: Long = 1000L, gpuHistorySize: Int = 60): State<KPToolStats> {
    val state = remember { mutableStateOf(KPToolStats()) }
    val cpuPrev = remember { CpuPrev() }
    val gpuPrev = remember { GpuPrev(historySize = gpuHistorySize) }

    LaunchedEffect(Unit) {
        while (true) {
            val stats = withContext(Dispatchers.IO) { collectAll(cpuPrev, gpuPrev) }
            state.value = stats
            delay(refreshMs)
        }
    }
    return state
}

private class CpuPrev {
    val lastTotal = HashMap<Int, Long>()
    val lastIdle = HashMap<Int, Long>()
    val tempCache = TempCache()
}

private class GpuPrev(val historySize: Int) {
    var lastBusy: Long? = null
    var lastTotal: Long? = null

    var cachedVendor: String? = null
    var cachedDevfreqBase: String? = null

    var staticInfo: GpuStaticInfo = GpuStaticInfo()
    var params: GpuParams = GpuParams()
    var lastStaticUpdateMs: Long = 0L

    val tempCache = TempCache()

    val history = ArrayDeque<Float>(historySize)
}

private suspend fun collectAll(cpuPrev: CpuPrev, gpuPrev: GpuPrev): KPToolStats {
    val cpu = readCpu(cpuPrev)
    val gpu = readGpu(gpuPrev)
    val ram = readRam()
    return KPToolStats(cpu = cpu, gpu = gpu, ram = ram)
}

/* ===================== IO 工具 ===================== */

private fun readTextFast(path: String): String? = try {
    File(path).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
} catch (_: Throwable) { null }

private fun runRoot(cmd: String): String? = try {
    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    out.trim().ifEmpty { null }
} catch (_: Throwable) { null }

private fun readAny(path: String): String? = readTextFast(path) ?: runRoot("cat $path 2>/dev/null")?.trim()
private fun existsAny(path: String): Boolean = File(path).exists() || (runRoot("[ -e $path ] && echo ok") == "ok")

private fun bytesToGiB(b: Long): Double = b.toDouble() / 1024.0 / 1024.0 / 1024.0
private fun kbToGiB(kb: Long): Double = kb.toDouble() / 1024.0 / 1024.0

private fun normalizeToMHz(raw: Long): Int? {
    if (raw <= 0) return null
    val mhz = when {
        raw >= 10_000_000L -> (raw / 1_000_000L).toInt()
        raw >= 10_000L -> (raw / 1_000L).toInt()
        else -> raw.toInt()
    }
    return mhz.takeIf { it in 1..6000 }
}

/* ===================== ✅ 温度读取（强兜底，适配各种“非纯数字”） ===================== */

private class TempCache {
    var lastTempC: Float? = null
    var lastSource: String = ""
    var lastUpdateMs: Long = 0L
    var lastScanMs: Long = 0L
    var candidates: List<TempCandidate> = emptyList()
    var lastDumpsysMs: Long = 0L
}

private data class TempCandidate(
    val path: String,
    val meta: String,
    val score: Int
)

private fun firstNumberToken(s: String): String? {
    // 抓第一段数字：支持 45000、45.0、-1 等
    return Regex("[-+]?[0-9]+(?:\\.[0-9]+)?").find(s)?.value
}

private fun parseTempToC(raw: String, last: Float?): Float? {
    val tok = firstNumberToken(raw) ?: return null
    val asLong = tok.toLongOrNull()
    val asFloat = tok.toFloatOrNull()
    if (asLong == null && asFloat == null) return null

    // 先用 long（更常见），不行再 float
    val v = asLong?.toDouble() ?: asFloat!!.toDouble()

    // 多单位猜测：°C / 0.1°C / 0.01°C / 0.001°C / 1e-6°C
    val options = listOf(
        v,
        v / 10.0,
        v / 100.0,
        v / 1000.0,
        v / 10000.0,
        v / 1_000_000.0
    ).map { it.toFloat() }
        .filter { it in 5f..150f }

    if (options.isEmpty()) return null
    if (last == null) return options.first()

    // 选最接近上次温度的那个，避免单位误判
    return options.minByOrNull { abs(it - last) }
}

private fun scoreMeta(meta: String, prefer: List<String>, avoid: List<String>): Int {
    val low = meta.lowercase()
    var s = 0
    prefer.forEachIndexed { i, k ->
        if (low.contains(k)) s += (120 - i * 6)
    }
    avoid.forEach { k ->
        if (low.contains(k)) s -= 200
    }
    if (low.contains("tsens") || low.contains("tmu") || low.contains("therm") || low.contains("sensor")) s += 6
    return s
}

private fun scanTempCandidates(prefer: List<String>, avoid: List<String>): List<TempCandidate> {
    val out = ArrayList<TempCandidate>()

    // A) thermal_zone
    val thermalDir = File("/sys/class/thermal")
    val zones = thermalDir.listFiles()?.filter { it.name.startsWith("thermal_zone") } ?: emptyList()
    for (z in zones) {
        val type = readTextFast("${z.absolutePath}/type") ?: ""
        val tempPath = "${z.absolutePath}/temp"
        if (!existsAny(tempPath)) continue
        val meta = "thermal:$type"
        out.add(TempCandidate(tempPath, meta, scoreMeta(meta, prefer, avoid)))
    }

    // B) hwmon
    val hwmonDir = File("/sys/class/hwmon")
    val hwmons = hwmonDir.listFiles() ?: emptyArray()
    for (h in hwmons) {
        val name = readTextFast("${h.absolutePath}/name") ?: h.name
        val inputs = h.listFiles()?.filter { it.name.startsWith("temp") && it.name.endsWith("_input") } ?: emptyList()
        for (f in inputs) {
            val idx = f.name.removePrefix("temp").removeSuffix("_input")
            val label = readTextFast("${h.absolutePath}/temp${idx}_label") ?: ""
            val meta = "hwmon:$name $label".trim()
            out.add(TempCandidate(f.absolutePath, meta, scoreMeta(meta, prefer, avoid)))
        }
    }

    return out.sortedByDescending { it.score }
}

private fun parseDumpsysThermalStrong(
    text: String,
    prefer: List<String>,
    avoid: List<String>
): Pair<Float?, String> {
    // 兼容多种 dumpsys thermalservice 输出格式：抓 name + value
    var bestScore = Int.MIN_VALUE
    var bestTemp: Float? = null
    var bestName = ""

    val lines = text.lineSequence().toList()
    for (ln in lines) {
        val low = ln.lowercase()

        // 跳过明显无关
        if (low.contains("battery") || low.contains("charger")) continue

        // 取 name（多种字段）
        val name =
            Regex("mName=([^,}]+)").find(ln)?.groupValues?.getOrNull(1)?.trim()
                ?: Regex("name=([^,}]+)").find(ln)?.groupValues?.getOrNull(1)?.trim()
                ?: ""

        // 取 value（多种字段）
        val vStr =
            Regex("mValue=([-+]?[0-9]+(?:\\.[0-9]+)?)").find(ln)?.groupValues?.getOrNull(1)
                ?: Regex("value=([-+]?[0-9]+(?:\\.[0-9]+)?)").find(ln)?.groupValues?.getOrNull(1)
                ?: firstNumberToken(ln)

        val v = vStr?.toFloatOrNull()
        if (v == null || v !in 5f..150f) continue

        val meta = "dumpsys:$name $ln"
        val sc = scoreMeta(meta, prefer, avoid)
        if (sc > bestScore) {
            bestScore = sc
            bestTemp = v
            bestName = if (name.isNotBlank()) name else "unknown"
        }
    }

    return bestTemp to if (bestTemp != null) "dumpsys:$bestName" else ""
}

private fun readTempWithCache(
    cache: TempCache,
    prefer: List<String>,
    avoid: List<String>,
    dumpsysPrefer: List<String>,
    dumpsysAvoid: List<String>,
    label: String // "CPU" or "GPU"
): Pair<Float?, String> {
    val now = System.currentTimeMillis()

    // 每 45 秒扫一次候选（避免每秒遍历）
    if (cache.candidates.isEmpty() || now - cache.lastScanMs > 45_000L) {
        cache.candidates = scanTempCandidates(prefer, avoid).take(16)
        cache.lastScanMs = now
    }

    // 读前几个高分候选，平均，抗抖动
    val readList = cache.candidates.take(6)
    val temps = ArrayList<Float>()
    val srcs = ArrayList<String>()

    for (c in readList) {
        val raw = readAny(c.path) ?: continue
        val t = parseTempToC(raw, cache.lastTempC) ?: continue
        if (t !in 5f..150f) continue
        temps.add(t)
        srcs.add("${c.meta}(${c.path})")
        if (temps.size >= 3) break
    }

    var picked: Float? = null
    var source: String = ""

    if (temps.isNotEmpty()) {
        picked = temps.average().toFloat()
        source = srcs.joinToString(" | ")
    } else {
        // dumpsys 兜底（别每秒跑）
        if (now - cache.lastDumpsysMs > 12_000L) {
            cache.lastDumpsysMs = now
            val ds = runRoot("dumpsys thermalservice") ?: ""
            val (dt, dsSrc) = parseDumpsysThermalStrong(ds, dumpsysPrefer, dumpsysAvoid)
            if (dt != null) {
                picked = dt
                source = dsSrc.ifBlank { "dumpsys thermalservice" }
            }
        }
    }

    // 跳变过滤：5s 内跳变 > 15℃
    if (picked != null && cache.lastTempC != null && (now - cache.lastUpdateMs) < 5000L) {
        if (abs(picked - cache.lastTempC!!) > 15f) {
            return cache.lastTempC to "$label 温度缓存（跳变过滤）"
        }
    }

    // 缓存更新
    if (picked != null) {
        cache.lastTempC = picked
        cache.lastSource = source
        cache.lastUpdateMs = now
        return picked to source
    }

    // 本次没读到，用缓存（仍然返回非空 source）
    return cache.lastTempC to if (cache.lastTempC != null) "$label 温度缓存：${cache.lastSource}" else "$label 未命中温度节点"
}

/* ===================== CPU ===================== */

private fun parseProcStatCpuLines(): List<Pair<Int, List<Long>>> {
    val text = readAny("/proc/stat") ?: return emptyList()
    return text.lineSequence()
        .filter { it.startsWith("cpu") }
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val name = parts.getOrNull(0) ?: return@mapNotNull null
            val idx = when {
                name == "cpu" -> -1
                name.startsWith("cpu") -> name.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
                else -> return@mapNotNull null
            }
            val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 5) return@mapNotNull null
            idx to nums
        }.toList()
}

private fun calcCpuUsage(prev: CpuPrev, idx: Int, fields: List<Long>): Float {
    val idle = fields[3] + fields.getOrElse(4) { 0L }
    val total = fields.sum()

    val lt = prev.lastTotal[idx]
    val li = prev.lastIdle[idx]
    prev.lastTotal[idx] = total
    prev.lastIdle[idx] = idle

    if (lt == null || li == null) return 0f
    val td = (total - lt).coerceAtLeast(0L)
    val id = (idle - li).coerceAtLeast(0L)
    if (td == 0L) return 0f
    return ((td - id).toFloat() / td.toFloat()).coerceIn(0f, 1f)
}

private fun readCpuCoreFreqMHz(core: Int): Int? {
    val paths = listOf(
        "/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq",
        "/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_cur_freq"
    )
    val raw = paths.firstNotNullOfOrNull { readAny(it)?.let { s -> firstNumberToken(s)?.toLongOrNull() } } ?: return null
    return normalizeToMHz(raw)
}

private fun fmtTempText(t: Float?): String = if (t == null) "读取失败" else "%.1f℃".format(t)

private fun readCpu(prev: CpuPrev): CpuInfo {
    val lines = parseProcStatCpuLines()
    if (lines.isEmpty()) return CpuInfo()

    val totalFields = lines.firstOrNull { it.first == -1 }?.second ?: return CpuInfo()
    val totalUsage = calcCpuUsage(prev, -1, totalFields)
    val totalText = "${(totalUsage * 100f).roundToInt()}%"

    val cores = lines.filter { it.first >= 0 }.sortedBy { it.first }.map { (idx, fields) ->
        val u = calcCpuUsage(prev, idx, fields)
        CpuCoreInfo(
            index = idx,
            usage = u,
            usageText = "${(u * 100f).roundToInt()}%",
            freqMHz = readCpuCoreFreqMHz(idx)
        )
    }

    val avgFreq = cores.mapNotNull { it.freqMHz }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val freq = readCpuCoreFreqMHz(0) ?: avgFreq

    val (temp, src) = readTempWithCache(
        cache = prev.tempCache,
        prefer = listOf("mtktscpu", "cpu", "apss", "cpuss", "soc", "tsens", "big", "little", "cluster"),
        avoid = listOf("battery", "charger", "skin", "usb", "wifi", "modem", "pmic"),
        dumpsysPrefer = listOf("cpu", "apss", "cpuss", "soc"),
        dumpsysAvoid = listOf("battery", "skin", "usb"),
        label = "CPU"
    )

    val tempText = fmtTempText(temp)
    val extra = "频率 ${freq ?: "--"} MHz · 温度 $tempText"

    return CpuInfo(
        usage = totalUsage,
        usageText = totalText,
        freqMHz = freq,
        tempC = temp,
        tempText = tempText,
        extraText = extra,
        cores = cores,
        tempSource = src
    )
}

/* ===================== GPU ===================== */

private fun detectGpuVendor(): String {
    return when {
        existsAny("/sys/class/kgsl/kgsl-3d0") -> "高通Adreno"
        existsAny("/sys/kernel/ged") || existsAny("/proc/gpufreq") -> "联发科/天玑(常见)"
        existsAny("/sys/class/misc/mali0") -> "Mali(常见)"
        else -> "未知"
    }
}

private fun findDevfreqBase(gpuPrev: GpuPrev): String? {
    gpuPrev.cachedDevfreqBase?.let { return it }

    val q = "/sys/class/kgsl/kgsl-3d0/devfreq"
    if (existsAny(q) && existsAny("$q/cur_freq")) {
        gpuPrev.cachedDevfreqBase = q
        return q
    }

    val dir = File("/sys/class/devfreq")
    val nodes = dir.listFiles()?.toList().orEmpty()
    val hit = nodes.firstOrNull { node ->
        val name = node.name.lowercase()
        val maybe = name.contains("kgsl") || name.contains("gpu") || name.contains("mali") || name.contains("mtk")
        maybe && readAny("${node.absolutePath}/cur_freq")?.let { firstNumberToken(it)?.toLongOrNull() }?.let { it > 0 } == true
    }?.absolutePath

    if (hit != null) {
        gpuPrev.cachedDevfreqBase = hit
        return hit
    }

    val shellFound = runRoot(
        "ls -d /sys/devices/platform/*mali*/devfreq /sys/devices/platform/*gpu*/devfreq 2>/dev/null | head -n 1"
    )?.trim()

    if (!shellFound.isNullOrBlank() && existsAny("$shellFound/cur_freq")) {
        gpuPrev.cachedDevfreqBase = shellFound
        return shellFound
    }

    return null
}

private fun readDevfreqLong(base: String, file: String): Long? =
    readAny("$base/$file")?.let { firstNumberToken(it)?.toLongOrNull() }

private fun readDevfreqStr(base: String, file: String): String? =
    readAny("$base/$file")?.trim()

private fun parseFreqListMHz(raw: String?): List<Int> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(Regex("[\\s,]+"))
        .mapNotNull { it.toLongOrNull() }
        .mapNotNull { normalizeToMHz(it) }
        .distinct()
        .sorted()
}

private fun updateGpuStaticIfNeeded(gpuPrev: GpuPrev) {
    val now = System.currentTimeMillis()
    if (now - gpuPrev.lastStaticUpdateMs < 30_000L && gpuPrev.staticInfo != GpuStaticInfo()) return

    val platform = runRoot("getprop ro.board.platform")?.trim()
    val hardware = runRoot("getprop ro.hardware")?.trim()
    val model = readAny("/sys/class/kgsl/kgsl-3d0/gpu_model")?.trim()
    val rendererHint = model ?: runRoot("getprop ro.hardware.egl")?.trim()

    val base = findDevfreqBase(gpuPrev)
    val governor = base?.let { readDevfreqStr(it, "governor") }
    val avail = base?.let { parseFreqListMHz(readDevfreqStr(it, "available_frequencies")) }.orEmpty()

    val minRaw = base?.let { readDevfreqLong(it, "min_freq") }
    val maxRaw = base?.let { readDevfreqLong(it, "max_freq") }
    val minMHz = normalizeToMHz(minRaw ?: 0L) ?: avail.firstOrNull()
    val maxMHz = normalizeToMHz(maxRaw ?: 0L) ?: avail.lastOrNull()

    gpuPrev.staticInfo = GpuStaticInfo(platform, hardware, model, rendererHint)
    gpuPrev.params = GpuParams(base, governor, minMHz, maxMHz, avail)
    gpuPrev.lastStaticUpdateMs = now
}

private fun readKgslUsageSmart(gpuPrev: GpuPrev): Pair<Float?, String> {
    val raw = readAny("/sys/class/kgsl/kgsl-3d0/gpubusy") ?: return null to ""
    val parts = raw.trim().split(Regex("\\s+"))
    if (parts.size < 2) return null to ""
    val busy = parts[0].toLongOrNull() ?: return null to ""
    val total = parts[1].toLongOrNull() ?: return null to ""
    if (total <= 0) return null to ""

    val lastBusy = gpuPrev.lastBusy
    val lastTotal = gpuPrev.lastTotal
    gpuPrev.lastBusy = busy
    gpuPrev.lastTotal = total

    val looksLikeWindow = total in 200_000L..5_000_000L && busy <= total

    val usage = when {
        looksLikeWindow -> busy.toFloat() / total.toFloat()
        lastBusy == null || lastTotal == null -> busy.toFloat() / total.toFloat()
        total < lastTotal || busy < lastBusy -> busy.toFloat() / total.toFloat()
        else -> {
            val bd = (busy - lastBusy).coerceAtLeast(0L)
            val td = (total - lastTotal).coerceAtLeast(0L)
            if (td <= 0L) busy.toFloat() / total.toFloat() else bd.toFloat() / td.toFloat()
        }
    }.coerceIn(0f, 1f)

    return usage to if (looksLikeWindow) "KGSL利用率（窗口值）" else "KGSL利用率（差分/自动）"
}

private fun readGedUtil(): Pair<Float?, String> {
    val raw = readAny("/sys/kernel/ged/hal/gpu_utilization") ?: return null to ""
    val first = firstNumberToken(raw)?.toIntOrNull() ?: return null to ""
    return (first / 100f).coerceIn(0f, 1f) to "GED利用率"
}

private fun readMaliUtil(): Pair<Float?, String> {
    val candidates = listOf(
        "/sys/class/misc/mali0/device/utilization",
        "/sys/devices/platform/mali/utilization"
    )
    val raw = candidates.firstNotNullOfOrNull { readAny(it) } ?: return null to ""
    val v = firstNumberToken(raw)?.toIntOrNull() ?: return null to ""
    val u = when {
        v in 0..100 -> v / 100f
        v in 0..255 -> v / 255f
        else -> return null to ""
    }.coerceIn(0f, 1f)
    return u to "Mali利用率"
}

private fun readGpuFreqMHzMulti(gpuPrev: GpuPrev): Pair<Int?, String> {
    val base = gpuPrev.params.devfreqBase ?: findDevfreqBase(gpuPrev)
    if (base != null) {
        val hz = readDevfreqLong(base, "cur_freq")
        val mhz = hz?.let { normalizeToMHz(it) }
        if (mhz != null) return mhz to "Devfreq频率"
    }

    val qcPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpuclk",
        "/sys/class/kgsl/kgsl-3d0/gpu_clock",
        "/sys/kernel/debug/kgsl/kgsl-3d0/gpuclk"
    )
    for (p in qcPaths) {
        val v = readAny(p)?.let { firstNumberToken(it)?.toLongOrNull() }
        val mhz = v?.let { normalizeToMHz(it) }
        if (mhz != null) return mhz to "KGSL频率"
    }

    val gedFreqPaths = listOf(
        "/sys/kernel/ged/hal/current_freq",
        "/sys/kernel/ged/hal/cur_freq"
    )
    for (p in gedFreqPaths) {
        val v = readAny(p)?.let { firstNumberToken(it)?.toLongOrNull() }
        val mhz = v?.let { normalizeToMHz(it) }
        if (mhz != null) return mhz to "GED频率"
    }

    val dump = readAny("/proc/gpufreq/gpufreq_var_dump")
    if (!dump.isNullOrBlank()) {
        val m = Regex("(\\d{2,5})\\s*MHz", RegexOption.IGNORE_CASE).find(dump)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (m != null) return m to "/proc/gpufreq频率"
    }

    return null to "未知"
}

private fun readGpu(gpuPrev: GpuPrev): GpuInfo {
    if (gpuPrev.cachedVendor == null) gpuPrev.cachedVendor = detectGpuVendor()
    updateGpuStaticIfNeeded(gpuPrev)

    val (u1, s1) = readKgslUsageSmart(gpuPrev)
    val (u2, s2) = readGedUtil()
    val (u3, s3) = readMaliUtil()
    val usage = (u1 ?: u2 ?: u3 ?: 0f).coerceIn(0f, 1f)
    val usageSrc = s1.ifBlank { s2.ifBlank { s3.ifBlank { "未知" } } }
    val usageText = "${(usage * 100f).roundToInt()}%"

    if (gpuPrev.history.size >= gpuPrev.historySize) gpuPrev.history.removeFirst()
    gpuPrev.history.addLast(usage)

    val (freq, freqSrc) = readGpuFreqMHzMulti(gpuPrev)

    val (temp, tempSrc) = readTempWithCache(
        cache = gpuPrev.tempCache,
        prefer = listOf("gpu", "gpuss", "gfx", "mali", "adreno", "kgsl", "vgpu"),
        avoid = listOf("battery", "charger", "skin", "usb", "wifi", "modem", "pmic", "cpu", "apss"),
        dumpsysPrefer = listOf("gpu", "gpuss", "gfx", "mali", "adreno"),
        dumpsysAvoid = listOf("battery", "skin", "usb"),
        label = "GPU"
    )

    val tempText = fmtTempText(temp)
    val extra = "频率 ${freq ?: "--"} MHz · 温度 $tempText"

    return GpuInfo(
        usage = usage,
        usageText = usageText,
        freqMHz = freq,
        tempC = temp,
        tempText = tempText,
        extraText = extra,
        history = gpuPrev.history.toList(),
        staticInfo = gpuPrev.staticInfo,
        params = gpuPrev.params,
        usageSource = usageSrc,
        freqSource = freqSrc,
        devfreqSource = gpuPrev.params.devfreqBase ?: "无",
        tempSource = tempSrc.ifBlank { "GPU 未命中温度节点" }
    )
}

/* ===================== RAM（Swap 永远有文本） ===================== */

private fun readMeminfoMap(): Map<String, Long> {
    val text = readAny("/proc/meminfo") ?: return emptyMap()
    val map = HashMap<String, Long>()
    text.lineSequence().forEach { line ->
        val parts = line.split(Regex("\\s+"))
        if (parts.size >= 2) {
            val key = parts[0]
            val v = parts[1].toLongOrNull()
            if (v != null) map[key] = v
        }
    }
    return map
}

private fun readZram(): ZramInfo {
    val zdir = File("/sys/block/zram0")
    if (!zdir.exists()) return ZramInfo(enabled = false)

    val algoRaw = readAny("/sys/block/zram0/comp_algorithm")
    val algoActive = algoRaw
        ?.split(Regex("\\s+"))
        ?.firstOrNull { it.startsWith("[") && it.endsWith("]") }
        ?.removePrefix("[")?.removeSuffix("]")

    val diskSizeBytes = readAny("/sys/block/zram0/disksize")?.toLongOrNull()
    val diskSizeGB = diskSizeBytes?.let { bytesToGiB(it) }

    val mm = readAny("/sys/block/zram0/mm_stat")
    val nums = mm?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }.orEmpty()

    val orig = nums.getOrNull(0)
    val compr = nums.getOrNull(1)
    val memUsed = nums.getOrNull(2)

    val origGB = orig?.let { bytesToGiB(it) }
    val comprGB = compr?.let { bytesToGiB(it) }
    val memUsedGB = memUsed?.let { bytesToGiB(it) }
    val ratio = if (compr != null && compr > 0 && orig != null) orig.toDouble() / compr.toDouble() else null

    return ZramInfo(
        enabled = true,
        algorithm = algoActive ?: algoRaw?.trim(),
        diskSizeGB = diskSizeGB,
        origDataGB = origGB,
        comprDataGB = comprGB,
        memUsedGB = memUsedGB,
        ratio = ratio
    )
}

private fun readRam(): RamInfo {
    val m = readMeminfoMap()
    val totalKb = m["MemTotal:"] ?: return RamInfo()
    val availKb = m["MemAvailable:"] ?: (m["MemFree:"] ?: 0L)

    val cachedKb = m["Cached:"] ?: 0L
    val sReclaimableKb = m["SReclaimable:"] ?: 0L
    val shmemKb = m["Shmem:"] ?: 0L
    val realCachedKb = max(0L, cachedKb + sReclaimableKb - shmemKb)

    val totalGB = kbToGiB(totalKb)
    val realAvailGB = kbToGiB(availKb)
    val usedGB = max(0.0, totalGB - realAvailGB)
    val cachedGB = kbToGiB(realCachedKb)
    val freeRealGB = max(0.0, realAvailGB - cachedGB)

    val swapTotalKb = m["SwapTotal:"] ?: 0L
    val swapFreeKb = m["SwapFree:"] ?: 0L
    val swapTotalGB = kbToGiB(swapTotalKb)
    val swapUsedGB = max(0.0, swapTotalGB - kbToGiB(swapFreeKb))
    val swapText = if (swapTotalGB <= 0.0001) "未启用" else "%.1f/%.1f GB".format(swapUsedGB, swapTotalGB)

    val usage = if (totalGB <= 0.0) 0f else (usedGB / totalGB).toFloat().coerceIn(0f, 1f)

    val valueText = "%.1f / %.1f GB".format(usedGB, totalGB)
    val extra = "可用 %.1f GB · Swap %s".format(realAvailGB, swapText)

    val algoText = """
算法口径（工具常用）
已用 = MemTotal - MemAvailable
缓存(估算) = Cached + SReclaimable - Shmem
真实空闲(估算) ≈ MemAvailable - 缓存(估算)
说明：MemAvailable 比 MemFree 更贴近“真实可用”。
    """.trimIndent()

    return RamInfo(
        totalGB = totalGB,
        usedGB = usedGB,
        cachedGB = cachedGB,
        realAvailGB = realAvailGB,
        freeRealGB = freeRealGB,
        swapUsedGB = swapUsedGB,
        swapTotalGB = swapTotalGB,
        swapText = swapText,
        usage = usage,
        valueText = valueText,
        extraText = extra,
        algorithmText = algoText,
        zram = readZram()
    )
}
