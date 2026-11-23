// SPDX-License-Identifier: Apache-2.0
package dev.quicktransfer.app.local

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class UsbReaderState(val card: UsbCardInfo? = null, val reading: Boolean = false,
    val message: String = "", val logId: String = "", val logIncomplete: Boolean = false)

internal class UsbSimReader(private val app: Context) {
    private val usb by lazy { app.getSystemService(UsbManager::class.java) }
    val logs by lazy { UsbReaderLogs(app) }
    private val io = CoroutineScope(Dispatchers.IO)
    private val mutable = MutableStateFlow(UsbReaderState())
    val state = mutable.asStateFlow()
    private data class Selection(val device: UsbDevice, val trace: UsbReaderTrace)
    @Volatile private var selected: Selection? = null

    init {
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val removed = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                val session = selected
                if (removed != null && session != null && session.device.deviceName == removed.deviceName) {
                    selected = null
                    mutable.update { it.copy(card = null, message = "读卡器已断开，请重新连接并读取") }
                    val pending = goAsync()
                    io.launch {
                        try { session.trace("usb-detached", JSONObject().put("vidPid", removed.vidPid())) }
                        finally { pending.finish() }
                    }
                }
            }
        }, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), Context.RECEIVER_NOT_EXPORTED)
    }

    suspend fun scan() {
        if (mutable.value.reading) return
        val id = "usb-read-${UUID.randomUUID()}"
        selected = null
        mutable.value = UsbReaderState(reading = true, logId = id)
        try {
            withContext(Dispatchers.IO) {
                val trace = logs.begin(id) { mutable.update { if (it.logId == id) it.copy(logIncomplete = true) else it } }
                try { trace.reading("读取 SIM 卡") { scan(trace) } }
                catch (error: Exception) {
                    val message = when (error) {
                        is TimeoutCancellationException -> "等待 USB 授权超时，请再次读取"
                        is CancellationException -> throw error
                        is SecurityException -> "未能访问 USB 读卡器，请重新授权"
                        is IllegalArgumentException, is IllegalStateException -> error.message ?: "USB 读卡失败"
                        else -> "USB 读卡失败，请查看读卡器日志"
                    }
                    mutable.update { it.copy(message = message) }
                }
            }
        } finally {
            mutable.update { it.copy(reading = false) }
        }
    }

    private suspend fun scan(trace: UsbReaderTrace) {
        val devices = usb.deviceList.values.map { it to (0 until it.interfaceCount).map(it::getInterface) }
        val readers = devices.flatMap { (device, channels) -> channels
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_CSCID && it.alternateSetting == 0 }
            .map { device to it } }
        trace("usb-devices", JSONObject().put("count", devices.size).put("candidates", readers.size)
            .put("devices", JSONArray(devices.map { (device, channels) -> JSONObject().put("vidPid", device.vidPid())
                .put("deviceName", device.deviceName).put("manufacturerName", device.manufacturerName).put("productName", device.productName)
                .put("class", device.deviceClass).put("subclass", device.deviceSubclass).put("protocol", device.deviceProtocol)
                .put("interfaces", JSONArray(channels.map { it.diagnostic() })) })))
        check(readers.isNotEmpty()) { "未检测到 CCID 读卡器，请检查 USB 或 OTG 连接" }
        check(readers.size == 1) { "请只连接一个单槽 CCID 读卡器，再读取线路" }
        val (device, channel) = readers.single()
        trace("usb-selected", JSONObject().put("vidPid", device.vidPid()).put("interface", channel.id))
        grant(device, trace)
        val card = onCard(device, channel, trace, UsbUsim::identify)
        trace("usb-source", JSONObject().put("mccMnc", card.operator))
        withContext(Dispatchers.Main.immediate) {
            check(usb.hasPermission(device) && attached(device)) { "读卡器已断开，请重新连接并读取" }
            selected = Selection(device, trace)
            mutable.update { it.copy(card = card) }
        }
    }

    private fun attached(device: UsbDevice) = usb.deviceList[device.deviceName]?.deviceId == device.deviceId

    private suspend fun grant(device: UsbDevice, trace: UsbReaderTrace) {
        val granted = usb.hasPermission(device)
        trace("usb-permission", JSONObject().put("stage", if (granted) "已有授权" else "请求授权").put("granted", granted))
        if (granted) return
        val action = "${app.packageName}.USB_READER_PERMISSION.${UUID.randomUUID()}"
        val done = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == action || (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED &&
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)?.deviceName == device.deviceName)) done.complete(Unit)
            }
        }
        val reply = PendingIntent.getBroadcast(app, 0, Intent(action).setPackage(app.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        app.registerReceiver(receiver, IntentFilter(action).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }, Context.RECEIVER_NOT_EXPORTED)
        try {
            usb.requestPermission(device, reply)
            withTimeout(120_000) { done.await() }
            check(attached(device)) { "读卡器已断开，请重新连接并读取" }
            // The result intent is an immutable wake-up, not the source of permission or device identity.
            val allowed = usb.hasPermission(device)
            trace("usb-permission", JSONObject().put("stage", "授权结果").put("granted", allowed))
            check(allowed) { "未授予 USB 权限，可再次读取并选择允许" }
        } finally {
            app.unregisterReceiver(receiver)
            reply.cancel()
        }
    }

    private fun <T> onCard(device: UsbDevice, channel: UsbInterface, trace: UsbReaderTrace, use: (UsbUsim) -> T): T {
        check(attached(device)) { "读卡器已断开，请重新连接并读取" }
        check(usb.hasPermission(device)) { "USB 授权已失效，请重新读取读卡器" }
        val bulk = (0 until channel.endpointCount).map(channel::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val input = checkNotNull(bulk.singleOrNull { it.direction == UsbConstants.USB_DIR_IN }) { "读卡器缺少 CCID 输入端点" }
        val output = checkNotNull(bulk.singleOrNull { it.direction == UsbConstants.USB_DIR_OUT }) { "读卡器缺少 CCID 输出端点" }
        val opened = usb.openDevice(device)
        trace("usb-open", JSONObject().put("vidPid", device.vidPid()).put("success", opened != null))
        val connection = checkNotNull(opened) { "无法打开 USB 读卡器，请检查连接" }
        try {
            val raw = connection.rawDescriptors
            trace("usb-descriptors", JSONObject().put("dataHex", raw.hexForReader()))
            val descriptor = readCcidDescriptor(raw, channel.id, trace)
            val claimed = connection.claimInterface(channel, false)
            trace("usb-claim", JSONObject().put("interface", channel.id).put("success", claimed))
            check(claimed) { "读卡器正被占用，请关闭其他读卡应用" }
            val result = try { CcidSlot(descriptor, { data, timeout -> connection.bulkTransfer(output, data, data.size, timeout) },
                { data, offset, length, timeout -> connection.bulkTransfer(input, data, offset, length, timeout) }, trace).use { slot ->
                slot.powerOn()
                use(UsbUsim(trace, slot::transmit))
            } } finally { trace("usb-close", JSONObject().put("interface", channel.id).put("success", connection.releaseInterface(channel))) }
            check(attached(device)) { "读卡器已断开，请重新连接并读取" }
            return result
        } finally { connection.close() }
    }
}

private fun UsbDevice.vidPid() = "%04X:%04X".format(vendorId, productId)

private fun UsbInterface.diagnostic() = JSONObject().put("interface", id).put("alternate", alternateSetting)
    .put("class", interfaceClass).put("subclass", interfaceSubclass).put("protocol", interfaceProtocol)
    .put("endpoints", JSONArray((0 until endpointCount).map { getEndpoint(it).let { ep -> JSONObject()
        .put("address", ep.address).put("type", ep.type).put("packetSize", ep.maxPacketSize).put("interval", ep.interval) } }))
