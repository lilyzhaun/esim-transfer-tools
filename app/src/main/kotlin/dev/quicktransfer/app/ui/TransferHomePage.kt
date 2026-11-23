// SPDX-License-Identifier: Apache-2.0
package dev.quicktransfer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quicktransfer.app.core.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun TransferHomePage(state: TransferUiState, records: List<TransferRecord>, target: TargetDevice?,
    local: () -> Unit, targets: () -> Unit, modes: () -> Unit, openTask: (String) -> Unit,
    usb: (() -> Unit)? = null) {
    val active = LocalRouteActive.current
    val current = records.firstOrNull { it.blocksNewTransfer }
        ?: records.maxByOrNull { it.createdAt }
    PageFrame("转移", message = state.message) {
        if (current != null) {
            item { SectionLabel(if (current.blocksNewTransfer) "当前转移" else "最近一次转移") }
            item { TaskSummaryCard(current, { openTask(current.id) }, active) }
        }
        if (state.supportsLocalSim || usb != null) item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            if (state.supportsLocalSim) ArrowPreference(title = "从本机 SIM 转出", summary = "读取此手机的 SIM，申请目标设备的 eSIM",
                onClick = local.takeIf { active && !state.busy }, modifier = Modifier)
            if (usb != null) ArrowPreference(title = "USB 读卡器", summary = "只读 SIM 卡信息，查看读卡日志",
                onClick = usb.takeIf { active }, modifier = Modifier)
        } }
        if (!state.supportsLocalSim && usb == null) item { Text("此设备没有可用的本机 SIM 转出功能。", color = uiColors().secondary, fontSize = 13.sp) }
        item { SectionLabel("接收设备") }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = target?.name ?: "选择已保存的设备",
                summary = target?.let { "EID 尾号 ${it.eid.takeLast(6)} · 可在下一步更换" } ?: "保存设备身份，转移时直接使用",
                onClick = targets.takeIf { active && !state.busy })
        } }
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = state.mode.title,
                summary = if (state.host.connected && state.host.supported) "服务已连接" else "设置连接方式与授权",
                onClick = modes.takeIf { active && !state.busy })
        } }
        item { Text("转移结果会保存在「记录」中。", color = uiColors().secondary, fontSize = 13.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
    }
}
