// SPDX-License-Identifier: Apache-2.0
package dev.quicktransfer.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.quicktransfer.app.local.UsbReaderState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun UsbReaderPage(state: UsbReaderState, back: () -> Unit, read: () -> Unit, openLog: () -> Unit) {
    val active = LocalRouteActive.current
    val card = state.card
    PageFrame("USB 读卡器", onBack = back, footer = {
        PrimaryAction(active && !state.reading, read, Modifier.fillMaxWidth()) {
            Text(if (state.reading) "正在读取…" else if (card != null) "重新读取" else "读取读卡器")
        }
    }) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = card?.spn?.ifBlank { "SIM 卡" } ?: "SIM 卡信息", summary = when {
                    state.reading -> "等待授权或读取 SIM…"
                    state.message.isNotBlank() -> state.message
                    card != null -> "ICCID 尾号 ${card.iccidTail} · 网络编号 ${card.operator}"
                    else -> "插卡并通过 USB 连接读卡器。此处只读取卡信息，不会发起转移。"
                }, modifier = Modifier.statusLiveRegion())
                ArrowPreference(title = "读卡器日志",
                    summary = if (state.logIncomplete) "日志未完整保存，请检查存储空间" else "查看本次诊断与历史记录",
                    onClick = openLog.takeIf { active })
            }
        }
    }
}
