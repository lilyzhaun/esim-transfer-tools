// SPDX-License-Identifier: Apache-2.0
package dev.quicktransfer.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quicktransfer.app.core.TaskLog
import dev.quicktransfer.app.data.DeviceLogRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun UsbReaderLogPage(initialId: String, back: () -> Unit, loadRuns: suspend () -> List<DeviceLogRun>,
    load: suspend (String) -> TaskLog, exportLog: suspend (String, Uri) -> Unit) =
    DeviceOperationLogPage(LogPageCopy.USB_READER, initialId, back, loadRuns, load, exportLog)

private enum class LogPageCopy(val title: String, val key: String, val description: String, val emptyMessage: String) {
    USB_READER("读卡器日志", "usb-reader", "详细日志原样保留读卡报文和异常，可能包含卡号、IMSI 或鉴权数据。日志保存在本机，请勿公开分享。",
        "读取读卡器时会自动记录。拔掉读卡器后，也可以查看和导出历史。")
}

@Composable
private fun DeviceOperationLogPage(copy: LogPageCopy, initialId: String, back: () -> Unit, loadRuns: suspend () -> List<DeviceLogRun>,
    load: suspend (String) -> TaskLog, exportLog: suspend (String, Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active = LocalRouteActive.current
    var selected by rememberSaveable(initialId) { mutableStateOf(initialId) }
    var runs by remember { mutableStateOf(emptyList<DeviceLogRun>()) }
    var summary by remember { mutableStateOf<TaskLog?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var history by rememberSaveable { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val id = exportId; exportId = null
        if (uri != null && id != null) scope.launch {
            exporting = true
            try { exportLog(id, uri); feedback = "详细日志已导出" }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                feedback = "导出失败，请检查保存位置与存储空间后重试"
            } finally { exporting = false }
        }
    }
    LaunchedEffect(selected, refresh) {
        loading = true; summary = null
        try {
            runs = loadRuns()
            if (runs.none { it.id == selected }) selected = runs.firstOrNull()?.id.orEmpty()
            else summary = load(selected)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            feedback = "日志暂时无法读取，可刷新或尝试导出原始记录"
        } finally { loading = false }
    }
    val current = runs.firstOrNull { it.id == selected }
    val format = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val runFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    PageFrame(copy.title, onBack = back, message = feedback, dismissMessage = { feedback = "" },
        actions = { RefreshAction("刷新${copy.title}", active && !loading, { feedback = ""; refresh++ }, loading) },
        scrollKey = selected, footer = if (current == null) null else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryAction(active && !exporting && exportId == null, {
                        exportId = selected
                        save.launch("eSIM-${copy.key}-${current.createdAt}.txt")
                    }, Modifier.fillMaxWidth()) { Text(if (exporting) "正在导出…" else "导出详细日志") }
                    InlineAction("复制日志摘要", active && summary?.entries?.isNotEmpty() == true, {
                        val text = summary?.text() ?: return@InlineAction
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("${copy.title}摘要", text))
                        feedback = "日志摘要已复制"
                    }, Modifier.fillMaxWidth())
                }
            }
        }) {
        item { Text(copy.description, color = uiColors().secondary, fontSize = 14.sp, lineHeight = 21.sp) }
        if (runs.isNotEmpty()) item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            DisclosureRow(title = current?.title ?: "历史记录", expanded = history,
                summary = current?.let { "${runFormat.format(Date(it.createdAt))} · ${it.id.takeLast(6)}" }.orEmpty(),
                onClick = { history = !history }, modifier = Modifier)
            if (history) Column(Modifier.selectableGroup()) {
                runs.forEach { run ->
                    ChoiceRow(run.title, selected == run.id, { selected = run.id; summary = null; history = false; feedback = "" },
                        summary = "${runFormat.format(Date(run.createdAt))} · ${run.id.takeLast(6)}", enabled = active)
                }
            }
        } }
        if (loading) item { Text("正在读取日志…", modifier = Modifier.statusLiveRegion()) }
        else if (runs.isEmpty() && feedback.isEmpty()) item { EmptyState("暂无${copy.title}", copy.emptyMessage) }
        summary?.let { log ->
            if (log.dropped > 0) item { Text("当前显示最近 ${log.entries.size} 条摘要；导出包含已保留的完整记录。", color = uiColors().secondary, fontSize = 13.sp) }
            items(log.entries) { entry ->
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${format.format(Date(entry.time))} · ${entry.title}", fontSize = 16.sp)
                        if (entry.detail.isNotEmpty()) SelectionContainer { Text(entry.detail, color = uiColors().secondary, fontSize = 14.sp, lineHeight = 21.sp) }
                    }
                }
            }
        }
        if (selected.isNotEmpty()) item { Text("操作进行中可刷新日志，查看最新已记录的阶段。", color = uiColors().secondary, fontSize = 13.sp) }
    }
}
