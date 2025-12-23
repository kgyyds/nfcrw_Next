@file:OptIn(ExperimentalMaterial3Api::class)
package com.kgapp.nfcrw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text("NFC 读写工具")
                            }
                        )
                    }
                ) { innerPadding ->

                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {

                        Column(
                            modifier = Modifier
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {

                            // ===== 卡片 1 =====
                            Card(
                                shape = MaterialTheme.shapes.extraLarge,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "无障碍服务",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = "未启用 · 点击前往设置",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    Button(onClick = { }) {
                                        Text("前往设置")
                                    }
                                }
                            }

                            // ===== 卡片 2 =====
                            var enabled by remember { mutableStateOf(true) }

                            Card(
                                shape = MaterialTheme.shapes.extraLarge,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "悬浮窗",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = if (enabled) "已启用" else "已关闭",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = { enabled = it }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}