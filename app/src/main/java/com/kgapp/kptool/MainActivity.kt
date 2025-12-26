@file:OptIn(ExperimentalMaterial3Api::class)

package com.kgapp.kptool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {

            val context = LocalContext.current
            val darkTheme = isSystemInDarkTheme()

            val colorScheme = if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }

            MaterialTheme(
                colorScheme = colorScheme,
                typography = MaterialTheme.typography
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("KPTool") }
                        )
                    }
                ) { innerPadding ->

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {

                        // ===== CPU 卡片 =====
                        StatCard(
                            title = "CPU",
                            subtitle = "使用率",
                            progress = 0.45f,
                            valueText = "45%",
                            extraInfo = "频率 1800 MHz · 温度 46℃"
                        )

                        // ===== GPU 卡片 =====
                        StatCard(
                            title = "GPU",
                            subtitle = "使用率",
                            progress = 0.28f,
                            valueText = "28%",
                            extraInfo = "频率 650 MHz · 温度 44℃"
                        )

                        // ===== 内存 卡片 =====
                        StatCard(
                            title = "内存",
                            subtitle = "已用",
                            progress = 0.62f,
                            valueText = "7.4 / 12 GB",
                            extraInfo = "可用 4.6 GB · Swap 0 GB"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    subtitle: String,
    progress: Float,          // 0f..1f
    valueText: String,
    extraInfo: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // 标题 + 数值
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = valueText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // 进度条
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )

            // 0% - 100%
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "0%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "100%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 附加信息
            Text(
                text = extraInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}