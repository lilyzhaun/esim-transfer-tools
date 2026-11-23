// SPDX-License-Identifier: Apache-2.0
package dev.quicktransfer.app.local

import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** ETSI TS 102 221 SELECT/READ BINARY/READ RECORD and 3GPP TS 31.102 read-only USIM file access. */
internal class UsbUsim(private val trace: UsbReaderTrace = noUsbReaderTrace, private val transmit: (ByteArray) -> ByteArray) {
    private fun apdu(command: ByteArray, correctLe: Boolean = false, allowNotFound: Boolean = false): ByteArray? {
        var request = command
        var mayCorrect = correctLe
        val data = ByteArrayOutputStream()
        repeat(8) {
            val reply = transmit(request)
            check(reply.size in 2..258) { "SIM 响应长度不正确" }
            val sw1 = reply[reply.lastIndex - 1].unsigned()
            val sw2 = reply.last().unsigned()
            if (sw1 == 0x6c && mayCorrect) {
                request = request.copyOf().apply { this[lastIndex] = sw2.toByte() }
                mayCorrect = false
                return@repeat
            }
            if (allowNotFound && sw1 == 0x6a && sw2 == 0x82) return null
            data.write(reply, 0, reply.size - 2)
            if (sw1 == 0x61) {
                request = byteArrayOf(0, 0xc0.toByte(), 0, 0, sw2.toByte()); mayCorrect = true
                return@repeat
            }
            check((sw1 == 0x90 && sw2 == 0) || sw1 == 0x91) {
                if (sw1 == 0x69 && sw2 == 0x82) "SIM 未开放读取权限；请先在手机上检查 SIM PIN"
                else "SIM 拒绝了读取操作（%02X%02X）".format(sw1, sw2)
            }
            return data.toByteArray()
        }
        error("SIM 响应续读未完成")
    }

    private fun select(id: Int, optional: Boolean = false): ByteArray? {
        trace("sim-file", JSONObject().put("fid", "%04X".format(id)).put("stage", "选择文件").put("optional", optional))
        return apdu(byteArrayOf(0, 0xa4.toByte(), 0, 4, 2, (id ushr 8).toByte(), id.toByte(), 0), correctLe = true, allowNotFound = optional)
    }

    private fun file(id: Int, requested: Int = 0, optional: Boolean = false): ByteArray? {
        val fcp = select(id, optional) ?: return null
        val size = tlv(tlv(fcp, 0x62), 0x80).fold(0) { n, byte -> (n shl 8) or byte.unsigned() }
        val length = if (requested == 0) size else if (size == 0) requested else minOf(size, requested)
        trace("sim-file", JSONObject().put("fid", "%04X".format(id)).put("stage", "读取文件").put("fileBytes", size).put("requestedBytes", length))
        check(length in 1..32) { "SIM 文件长度不受支持" }
        return requireNotNull(apdu(byteArrayOf(0, 0xb0.toByte(), 0, 0, length.toByte()), correctLe = true))
    }

    private fun selectUsim() {
        // TS 102 221 §§11.1.1, 11.1.5, 13.1: EF.DIR is linear fixed under the MF.
        val directory = select(0x2f00, optional = true) ?: error("SIM 未提供应用目录（EF.DIR，6A82）")
        val descriptor = tlv(tlv(directory, 0x62), 0x82)
        check(descriptor.size == 5 && (descriptor[0].unsigned() and 7) == 2) { "SIM 应用目录不是有效的定长记录文件" }
        val recordBytes = (descriptor[2].unsigned() shl 8) or descriptor[3].unsigned()
        val recordCount = descriptor[4].unsigned()
        check(recordBytes in 1..255 && recordCount in 1..254) { "SIM 应用目录的记录长度或数量不正确" }

        val aids = (1..recordCount).mapNotNull { record ->
            trace("sim-file", JSONObject().put("fid", "2F00").put("stage", "读取应用目录记录")
                .put("record", record).put("recordCount", recordCount).put("recordBytes", recordBytes))
            val data = requireNotNull(apdu(byteArrayOf(0, 0xb2.toByte(), record.toByte(), 4, recordBytes.toByte()), correctLe = true))
            if (data.isNotEmpty() && data.all { it.unsigned() == 0xff }) return@mapNotNull null
            val aid = tlv(tlv(data, 0x61), 0x4f)
            check(aid.size in 1..16) { "SIM 应用目录第 $record 条记录没有有效的 AID" }
            // TS 101 220: use the RID and application code to recognize USIM, not as a partial SELECT.
            aid.takeIf { it.size >= USIM_PREFIX.size && USIM_PREFIX.indices.all { i -> aid[i] == USIM_PREFIX[i] } }
        }.distinctBy(ByteArray::hexForReader)
        trace("sim-file", JSONObject().put("fid", "2F00").put("stage", "完成应用目录读取").put("count", aids.size))
        check(aids.isNotEmpty()) { "SIM 应用目录未列出 USIM，无法读取线路" }
        check(aids.size == 1) { "SIM 应用目录列出多个 USIM，无法自动确定线路" }
        val aid = aids.single()
        trace("sim-file", JSONObject().put("stage", "按目录 AID 选择 USIM 应用").put("aid", aid.hexForReader()))
        val selected = apdu(byteArrayOf(0, 0xa4.toByte(), 4, 4, aid.size.toByte()) + aid + byteArrayOf(0),
            correctLe = true, allowNotFound = true)
        check(selected != null) { "卡未接受应用目录公布的 USIM（6A82）" }
    }

    fun identify(): UsbCardInfo {
        select(0x3f00)
        val iccid = bcd(requireNotNull(file(0x2fe2, 10))).trimEnd('f')
        check(iccid.matches(Regex("[0-9]{19,20}"))) { "无法读取有效的 SIM 卡号" }
        selectUsim()
        val identity = requireNotNull(file(0x6f07, 9))
        val count = identity.firstOrNull()?.unsigned() ?: 0
        check(count in 7..8 && count < identity.size) { "SIM 的 IMSI 文件长度不正确" }
        val digits = bcd(identity.copyOfRange(1, 1 + count))
        val imsi = digits.drop(1).trimEnd('f')
        check(imsi.matches(Regex("[0-9]{14,15}")) && digits.first() == (if (imsi.length % 2 == 0) '1' else '9')) {
            "无法读取有效的 SIM IMSI"
        }
        val mnc = file(0x6fad, 4)?.getOrNull(3)?.unsigned()?.and(15)
        check(mnc == 2 || mnc == 3) { "SIM 未提供有效的运营商网络编号" }
        val name = spn(file(0x6f46, 17, optional = true))
        return UsbCardInfo(iccid.takeLast(4), imsi.take(3 + mnc), name)
    }
}

internal data class UsbCardInfo(val iccidTail: String, val operator: String, val spn: String)

private val USIM_PREFIX = byteArrayOf(0xa0.toByte(), 0, 0, 0, 0x87.toByte(), 0x10, 2)

private fun bcd(bytes: ByteArray) = buildString(bytes.size * 2) {
    val hex = "0123456789abcdef"
    for (byte in bytes) { append(hex[byte.unsigned() and 15]); append(hex[byte.unsigned() shr 4]) }
}

private fun tlv(bytes: ByteArray, tag: Int): ByteArray {
    var p = 0
    while (p + 2 <= bytes.size) {
        val current = bytes[p++].unsigned()
        val first = bytes[p++].unsigned()
        val size = when {
            first < 128 -> first
            first == 0x81 && p < bytes.size -> bytes[p++].unsigned()
            else -> error("SIM 文件描述符长度不正确")
        }
        check(p + size <= bytes.size) { "SIM 文件描述符不完整" }
        if (current == tag) return bytes.copyOfRange(p, p + size)
        p += size
    }
    return byteArrayOf()
}

private const val GSM_ALPHABET = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

private fun spn(data: ByteArray?): String {
    val bytes = data?.drop(1) ?: return ""
    if (bytes.firstOrNull()?.unsigned() == 0x80) {
        val body = bytes.drop(1).chunked(2).takeWhile { it.size == 2 && it.any { byte -> byte.unsigned() != 255 } }
        return body.map { (it[0].unsigned() * 256 + it[1].unsigned()).toChar() }.joinToString("").trim()
    }
    return bytes.takeWhile { it.unsigned() != 255 }.map { GSM_ALPHABET.getOrNull(it.unsigned()) ?: return "" }
        .joinToString("").takeUnless { it.any(Char::isISOControl) }.orEmpty().trim()
}
