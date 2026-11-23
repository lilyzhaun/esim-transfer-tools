// SPDX-License-Identifier: Apache-2.0
package dev.quicktransfer.app.local

import android.content.Context
import android.net.Uri
import dev.quicktransfer.app.data.DeviceOperationLogStore
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream

internal typealias UsbReaderTrace = (String, JSONObject) -> Unit
internal val noUsbReaderTrace: UsbReaderTrace = { _, _ -> }
@OptIn(ExperimentalStdlibApi::class)
internal fun ByteArray.hexForReader() = toHexString(HexFormat.UpperCase)

/** Full reader evidence is private and export-only. The in-app summary is a separate view. */
internal class UsbReaderLogs(context: Context) {
    private val store = DeviceOperationLogStore(context, "usb-reader-logs", "读卡器详细日志",
        "原始 USB／CCID、APDU、卡片应答及完整异常。可能含卡号、IMSI、鉴权数据，请勿公开分享。",
        mapOf("usb-read" to "读取 USB 读卡器"), mapOf(
            "operation-start" to "开始记录", "usb-devices" to "枚举 USB 设备", "usb-selected" to "选定读卡器", "usb-permission" to "USB 授权",
            "usb-open" to "打开读卡器", "usb-claim" to "占用 CCID 接口", "usb-close" to "释放 CCID 接口",
            "usb-detached" to "读卡器已断开", "ccid-descriptor" to "识别读卡器协议", "ccid-atr" to "卡片复位响应",
            "ccid-timing" to "确认卡片通信参数", "ccid-send" to "发送 CCID 命令",
            "ccid-response" to "CCID 响应", "ccid-io" to "USB 读写异常", "sim-file" to "读取 SIM 文件",
            "apdu-send" to "发送卡命令", "apdu-response" to "卡命令响应", "usb-source" to "识别源线路",
            "usb-stage" to "开始读卡步骤", "usb-stage-end" to "读卡步骤结束", "operation-error" to "操作失败"),
        listOf("sdk", "vidPid", "count", "candidates", "interface", "slots", "protocols", "exchange", "powerSelect", "maxMessage",
            "granted", "stage", "result", "command", "ccidStatus", "ccidError", "seq", "ins", "fid", "record", "recordCount", "recordBytes", "sw", "durationMs",
            "commandBytes", "wireBytes", "responseBytes", "protocol", "success", "matched", "supported"))
    fun begin(id: String, writeFailed: () -> Unit): UsbReaderTrace = try {
        store.begin("usb-read", JSONObject(), id)
        trace(id, writeFailed)
    } catch (_: IOException) {
        writeFailed()
        noUsbReaderTrace
    }
    private fun trace(id: String, writeFailed: () -> Unit): UsbReaderTrace = { kind, data ->
        try { store.detailed.append(id, kind, data) }
        catch (_: IOException) { writeFailed() }
    }
    fun runs() = store.runs()
    fun summary(id: String) = store.summary(id)
    fun copyTo(id: String, output: OutputStream) = store.copyTo(id, output)
    fun export(id: String, uri: Uri) = store.export(id, uri)
}

internal fun UsbReaderTrace.failed(stage: String, error: Throwable) = this("operation-error", JSONObject()
    .put("stage", stage).put("exceptionClass", error.javaClass.name).put("exceptionMessage", error.message)
    .put("stackTrace", error.stackTraceToString()))

internal inline fun <T> UsbReaderTrace.reading(stage: String, block: () -> T): T {
    val started = System.nanoTime()
    this("usb-stage", JSONObject().put("stage", stage))
    var success = false
    try { return block().also { success = true } }
    catch (error: Exception) { failed(stage, error); throw error }
    finally { this("usb-stage-end", JSONObject().put("stage", stage).put("success", success)
        .put("durationMs", (System.nanoTime() - started) / 1_000_000)) }
}
