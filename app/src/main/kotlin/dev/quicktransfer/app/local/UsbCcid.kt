// SPDX-License-Identifier: Apache-2.0
package dev.quicktransfer.app.local

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val HEADER = 10
internal fun Byte.unsigned() = toInt() and 0xff
private fun little(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

internal data class CcidDescriptor(val features: Int, val protocols: Int, val voltageSupport: Int, val maxMessage: Int) {
    val t0Tpdu get() = (features and 0x00070000) == 0x00010000
    // CCID bPowerSelect: 0 = automatic. ESTKme-RED advertises only 3 V and no automatic voltage selection.
    val powerSelect get() = when {
        features and 0x0c != 0 -> 0
        voltageSupport and 4 != 0 -> 3
        voltageSupport and 2 != 0 -> 2
        voltageSupport and 1 != 0 -> 1
        else -> 0
    }
}

/** USB CCID 1.1 §§5–6. One contact slot; short APDUs or T=0 TPDUs. */
internal fun readCcidDescriptor(descriptors: ByteArray, interfaceId: Int, trace: UsbReaderTrace = noUsbReaderTrace): CcidDescriptor {
    var cursor = 0
    var matches = false
    while (cursor + 2 <= descriptors.size) {
        val len = descriptors[cursor].unsigned()
        require(len >= 2 && cursor + len <= descriptors.size) { "USB 读卡器描述符无效" }
        when (descriptors[cursor + 1].unsigned()) {
            0x02 -> matches = false
            0x04 -> matches = len >= 9 && descriptors[cursor + 2].unsigned() == interfaceId &&
                descriptors[cursor + 3].unsigned() == 0 && descriptors[cursor + 5].unsigned() == 0x0b
            0x21 -> if (matches) {
                require(len >= 54) { "CCID 描述符不完整" }
                val bytes = descriptors.copyOfRange(cursor, cursor + 54)
                val header = little(bytes)
                val slots = bytes[4].unsigned() + 1
                val spec = CcidDescriptor(header.getInt(40), header.getInt(6), bytes[5].unsigned(), header.getInt(44))
                val exchange = spec.features and 0x00070000
                trace("ccid-descriptor", JSONObject().put("interface", interfaceId).put("slots", slots)
                    .put("descriptorHex", bytes.hexForReader()).put("ccidFeatures", "%08X".format(spec.features))
                    .put("protocols", spec.protocols).put("exchange", when (exchange) {
                        0x10000 -> "TPDU"
                        0x20000 -> "APDU"
                        0x40000 -> "Extended APDU"
                        else -> "0x%05X".format(exchange)
                    }).put("maxMessage", spec.maxMessage).put("voltageSupport", spec.voltageSupport).put("powerSelect", spec.powerSelect))
                require(slots == 1 && when (exchange) {
                    0x10000 -> spec.protocols and 1 != 0
                    0x20000, 0x40000 -> spec.protocols and 3 != 0
                    else -> false
                }) { "仅支持单槽 CCID 的 APDU 或 T=0 TPDU 模式" }
                require(spec.maxMessage >= 271) { "CCID 读卡器的报文长度不足" }
                return spec
            }
        }
        cursor += len
    }
    error("未找到读卡器的 CCID 描述符")
}

internal class CcidSlot(private val spec: CcidDescriptor, private val write: (ByteArray, Int) -> Int,
    private val read: (ByteArray, Int, Int, Int) -> Int, private val trace: UsbReaderTrace = noUsbReaderTrace) : AutoCloseable {
    private var sequence = 0
    private var powered = false

    fun powerOn() {
        val atr = request(0x62, 0x80, parameter = spec.powerSelect)
        powered = true
        trace("ccid-atr", JSONObject().put("responseBytes", atr.size).put("atrHex", atr.hexForReader()))
        check(atr.isNotEmpty()) { "读卡器未返回卡片复位信息" }
        if (spec.t0Tpdu) {
            val timing = if (spec.features and 0x42 != 0) request(0x6c, 0x82) else request(0x61, 0x82, t0ResetTiming(atr))
            check(timing.size == 5) { "读卡器未确认 T=0 通信参数" }
            trace("ccid-timing", JSONObject().put("protocol", "T=0").put("parameters", timing.hexForReader()))
        }
    }

    override fun close() {
        if (!powered) return
        powered = false
        request(0x63, 0x81)
    }

    fun transmit(apdu: ByteArray): ByteArray {
        val wire = if (spec.t0Tpdu) t0Tpdu(apdu) else apdu
        val ins = "%02X".format(apdu[1].unsigned())
        trace("apdu-send", JSONObject().put("apduHex", apdu.hexForReader()).put("transportHex", wire.hexForReader())
            .put("ins", ins).put("commandBytes", apdu.size).put("wireBytes", wire.size))
        return request(0x6f, 0x80, wire).also { reply ->
            val event = JSONObject().put("ins", ins).put("responseBytes", reply.size).put("responseHex", reply.hexForReader())
            if (reply.size >= 2) event.put("sw", reply.copyOfRange(reply.size - 2, reply.size).hexForReader())
            trace("apdu-response", event)
        }
    }

    private fun request(command: Int, response: Int, data: ByteArray = byteArrayOf(), parameter: Int = 0): ByteArray {
        val seq = (sequence++ and 255).toByte()
        val packet = ByteArray(HEADER + data.size)
        little(packet).put(command.toByte()).putInt(data.size).put(0).put(seq).put(parameter.toByte())
        data.copyInto(packet, HEADER)
        val started = System.nanoTime()
        val deadline = started + 5_000_000_000L
        fun remaining(): Int = ((deadline - System.nanoTime()) / 1_000_000).toInt().also {
            check(it > 0) { "USB 读卡器响应超时" }
        }
        trace("ccid-send", JSONObject().put("frameHex", packet.hexForReader()).put("command", "%02X".format(command))
            .put("seq", seq.unsigned()).put("commandBytes", packet.size))
        val written = write(packet, remaining())
        trace("usb-bulk-out", JSONObject().put("seq", seq.unsigned()).put("result", written).put("commandBytes", packet.size))
        if (written != packet.size) trace("ccid-io", JSONObject().put("stage", "USB 写入").put("result", written))
        check(written == packet.size) { "无法向 USB 读卡器发送命令，请重新插接" }
        val input = ByteArray(spec.maxMessage.coerceAtMost(4096))
        repeat(12) {
            var count = 0
            var size = HEADER
            while (count < size) {
                val n = read(input, count, input.size - count, remaining())
                val chunk = JSONObject().put("seq", seq.unsigned()).put("offset", count).put("result", n)
                if (n in 1..(input.size - count)) chunk.put("dataHex", input.copyOfRange(count, count + n).hexForReader())
                trace("usb-bulk-in", chunk)
                if (n <= 0) trace("ccid-io", JSONObject().put("stage", "USB 读取").put("result", n).put("responseBytes", count))
                check(n in 1..(input.size - count)) { "USB 读卡器未响应，请检查连接与插卡方向" }
                val headerReceived = count < HEADER && count + n >= HEADER
                count += n
                if (headerReceived) {
                    val length = little(input).getInt(1)
                    trace("ccid-response", JSONObject().put("header", input.copyOf(HEADER).hexForReader())
                        .put("ccidStatus", "%02X".format(input[7].unsigned())).put("ccidError", "%02X".format(input[8].unsigned()))
                        .put("seq", input[6].unsigned()).put("responseBytes", length).put("durationMs", (System.nanoTime() - started) / 1_000_000))
                    check(length in 0..(input.size - HEADER)) { "CCID 响应长度不正确" }
                    size = HEADER + length
                }
            }
            check(count == size && input[0].unsigned() == response && input[5] == 0.toByte() && input[6] == seq) {
                "CCID 响应不属于当前命令"
            }
            val status = input[7].unsigned()
            if (status and 3 == 2) error("读卡器中没有可用的 SIM，请重新插卡")
            if (status and 0xc0 == 0x80) return@repeat
            check(status and 0xc0 == 0 && (response == 0x81 || status and 3 == 0)) {
                "读卡器拒绝了卡片操作（CCID %02X/%02X）".format(status, input[8].unsigned())
            }
            if (response == 0x82) check(input[9] == 0.toByte()) { "读卡器当前未使用 T=0" }
            if (response == 0x80) check(input[9] == 0.toByte()) { "不支持分链的 CCID 响应" }
            return input.copyOfRange(HEADER, size)
        }
        error("USB 读卡器响应超时")
    }
}

/** CCID 1.1 §3.2.1: the reader inserts P3 for form 1; case 4S becomes form 3 without Le. */
internal fun t0Tpdu(apdu: ByteArray): ByteArray = when (apdu.size) {
    4, 5 -> apdu
    else -> {
        require(apdu.size >= 6 && apdu[4] != 0.toByte()) { "T=0 读卡器仅支持短 APDU" }
        val dataEnd = 5 + apdu[4].unsigned()
        require(apdu.size in dataEnd..dataEnd + 1) { "短 APDU 长度不正确" }
        if (apdu.size == dataEnd) apdu else apdu.copyOf(dataEnd)
    }
}

/** Use reset speed in negotiable mode; this path does not negotiate another speed or protocol. */
private fun t0ResetTiming(atr: ByteArray): ByteArray {
    val ts = atr[0].unsigned()
    check(atr.size >= 2 && ts in setOf(0x3b, 0x3f)) { "读卡器返回的 ATR 无效" }
    var index = 2
    var present = atr[1].unsigned() shr 4
    var ta1 = 0x11
    var guard = 0
    var wait = 10
    var firstProtocol = 0
    var specific: Int? = null
    fun byte(): Int {
        check(index < atr.size) { "读卡器返回的 ATR 不完整" }
        return atr[index++].unsigned()
    }
    for (group in 1..2) {
        val ta = if (present and 1 != 0) byte() else null
        if (present and 2 != 0) byte()
        val tc = if (present and 4 != 0) byte() else null
        if (group == 1) { ta1 = ta ?: 0x11; guard = tc ?: 0 }
        else { specific = ta; if (firstProtocol == 0) wait = tc ?: 10 }
        if (present and 8 == 0) break
        val td = byte()
        if (group == 1) firstProtocol = td and 15
        present = td shr 4
    }
    check((specific?.and(15) ?: firstProtocol) == 0) { "SIM 复位后未使用 T=0，当前 TPDU 读卡器不能读取此卡" }
    check(specific == null || specific and 0x10 == 0) { "不支持带隐式参数的 T=0 卡片复位模式" }
    val rate = if (specific == null) 0x11 else ta1
    return byteArrayOf(rate.toByte(), (if (ts == 0x3f) 2 else 0).toByte(), guard.toByte(), wait.toByte(), 0)
}
