@file:OptIn(ExperimentalMaterial3Api::class)

package com.kgapp.kptool

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kgapp.kptool.toolact.KPToolStats
import com.kgapp.kptool.toolact.rememberKPToolStats
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()

            val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else {
                if (dark) darkColorScheme() else lightColorScheme()
            }

            val stats by rememberKPToolStats(refreshMs = 1000L, gpuHistorySize = 60)

            MaterialTheme(colorScheme = colors) {
                KPToolScreen(stats)
            }
        }
    }
}

@Composable
private fun KPToolScreen(stats: KPToolStats) {
    var cpuExpanded by rememberSaveable { mutableStateOf(false) }
    var gpuExpanded by rememberSaveable { mutableStateOf(false) }
    var ramExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("KPTool") }) }) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            GaugeCard(
                icon = Icons.Filled.Speed,
                title = "CPU",
                subtitle = "总使用率",
                progress = stats.cpu.usage,
                valueText = stats.cpu.usageText,
                chips = listOf(
                    "频率：${stats.cpu.freqMHz ?: "--"} MHz",
                    "温度：${stats.cpu.tempText}"   // ✅ 永不空
                ),
                expanded = cpuExpanded,
                onToggle = { cpuExpanded = !cpuExpanded }
            ) { CpuExpanded(stats) }

            GaugeCard(
                icon = Icons.Filled.GraphicEq,
                title = "GPU",
                subtitle = "使用率/频率",
                progress = stats.gpu.usage,
                valueText = stats.gpu.usageText,
                chips = listOf(
                    "频率：${stats.gpu.freqMHz ?: "--"} MHz",
                    "温度：${stats.gpu.tempText}"   // ✅ 永不空
                ),
                expanded = gpuExpanded,
                onToggle = { gpuExpanded = !gpuExpanded }
            ) { GpuExpanded(stats) }

            GaugeCard(
                icon = Icons.Filled.Memory,
                title = "内存",
                subtitle = "已用",
                progress = stats.ram.usage,
                valueText = stats.ram.valueText,
                chips = listOf(
                    "可用：%.1f GB".format(stats.ram.realAvailGB),
                    "Swap：${stats.ram.swapText}"  // ✅ 永不空
                ),
                expanded = ramExpanded,
                onToggle = { ramExpanded = !ramExpanded }
            ) { RamExpanded(stats) }
        }
    }
}

/* ===================== 通用卡片 ===================== */

@Composable
private fun GaugeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    progress: Float,
    valueText: String,
    chips: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .animateContentSize(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DonutGauge(
                    progress = progress,
                    centerText = if (valueText.contains("%")) valueText else "${(progress * 100).roundToInt()}%",
                    modifier = Modifier.size(78.dp)
                )
                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(10.dp)
                    )
                    FlowChips(chips)
                }
            }

            if (expanded) {
                Divider(Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(6.dp))
                expandedContent()
            } else {
                Text("点我展开细节 👇", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DonutGauge(progress: Float, centerText: String, modifier: Modifier = Modifier) {
    val p = progress.coerceIn(0f, 1f)
    val bg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    val fg = MaterialTheme.colorScheme.primary

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = Stroke(width = size.minDimension * 0.14f, cap = StrokeCap.Round)
            val pad = stroke.width / 2f
            val d = size.minDimension - pad * 2
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arcSize = Size(d, d)

            drawArc(
                color = bg,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
            drawArc(
                color = fg,
                startAngle = -90f,
                sweepAngle = 360f * p,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
        }
        Text(centerText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FlowChips(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { text ->
                    AssistChip(onClick = {}, label = { Text(text, maxLines = 1) })
                }
            }
        }
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/* ===================== 展开详情：把“来源”显示出来，确认命中哪个传感器 ===================== */

@Composable
private fun CpuExpanded(stats: KPToolStats) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("CPU 温度来源：${stats.cpu.tempSource}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("每核占用", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (stats.cpu.cores.isEmpty()) {
            Text("首秒可能为 0，等 1 秒就会有数据～", style = MaterialTheme.typography.bodySmall)
            return
        }
        stats.cpu.cores.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CPU${c.index}", modifier = Modifier.width(64.dp), fontWeight = FontWeight.Medium)
                LinearProgressIndicator(progress = { c.usage.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(8.dp))
                Spacer(Modifier.width(10.dp))
                Text(c.usageText, modifier = Modifier.width(56.dp), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GpuExpanded(stats: KPToolStats) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GPU 温度来源：${stats.gpu.tempSource}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("读取来源（调试用）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        KV("利用率来源", stats.gpu.usageSource)
        KV("频率来源", stats.gpu.freqSource)
        KV("Devfreq路径", stats.gpu.params.devfreqBase ?: "--")
    }
}

@Composable
private fun RamExpanded(stats: KPToolStats) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Swap：${stats.ram.swapText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KV("已用", "%.2f GB".format(stats.ram.usedGB))
        KV("缓存(估算)", "%.2f GB".format(stats.ram.cachedGB))
        KV("真实空闲(估算)", "%.2f GB".format(stats.ram.freeRealGB))
        KV("MemAvailable", "%.2f GB".format(stats.ram.realAvailGB))
    }
}
