package com.aidotble.widget.ble

import java.util.UUID

/**
 * Command framing + attribute (TLV) encoding for the runtime channel (b001/b002).
 * Port of aidot_control.py's build_frame / encode_attrs / decode_attrs / Response.
 */
object Protocol {

    private const val BASE = "5cfefeab-cdc1-4a6d-b1af-6f18294c"
    val SERVICE: UUID = UUID.fromString("5cfefeab-cdc1-4a6d-b1af-6f18294c99bc")
    val CH_DEVINFO: UUID = UUID.fromString(BASE + "a00b") // getDeviceInfo (read)
    val CH_CMD_WRITE: UUID = UUID.fromString(BASE + "b001") // command write
    val CH_CMD_NOTIFY: UUID = UUID.fromString(BASE + "b002") // command notify
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Manufacturer-data company id (already stripped from the payload by the scan record). */
    const val MFR_ID = 0x6811

    // categories / commands
    const val CAT_SIGNIN = 0x01
    const val CMD_SIGNIN = 0x10
    const val CMD_SIGNIN_ACK = 0x90
    const val CAT_ATTR = 0x03
    const val CMD_SET_ATTRS = 0x02

    // attribute ids / byte lengths (type: 0x00 bool, 0x01 number)
    private const val T_BOOL = 0x00
    private const val T_NUM = 0x01

    /** frame = [seq, 0x11 (1 chunk of 1), dst, dst, category, command] + payload */
    fun buildFrame(seq: Int, category: Int, command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val head = byteArrayOf(
            (seq and 0xFF).toByte(), 0x11, 0x00, 0x00,
            (category and 0xFF).toByte(), (command and 0xFF).toByte(),
        )
        return head + payload
    }

    sealed class Attr(val id: Int, val type: Int, val nbytes: Int) {
        abstract fun valueHex(): String
        class OnOff(val on: Boolean) : Attr(0x0028, T_BOOL, 1) {
            override fun valueHex() = if (on) "01" else "00"
        }
        class Dimming(val pct: Int) : Attr(0x0027, T_NUM, 1) {
            override fun valueHex() = "%02x".format(pct.coerceIn(0, 100))
        }
        class Cct(val kelvin: Int) : Attr(0x0038, T_NUM, 2) {
            override fun valueHex() = "%04x".format(kelvin)
        }
        class Rgb(val r: Int, val g: Int, val b: Int) : Attr(0x0056, T_NUM, 3) {
            override fun valueHex() = "%02x%02x%02x".format(r and 0xFF, g and 0xFF, b and 0xFF)
        }
    }

    /** TLV payload: count(1B) + per attr id(2B) type(1B) len(1B) value. */
    fun encodeAttrs(attrs: List<Attr>): ByteArray {
        val sb = StringBuilder("%02x".format(attrs.size))
        for (a in attrs) {
            sb.append("%04x".format(a.id))
            sb.append("%02x".format(a.type))
            sb.append("%02x".format(a.nbytes))
            sb.append(a.valueHex())
        }
        return hexToBytes(sb.toString())
    }

    data class Response(val seq: Int, val category: Int, val command: Int, val status: Int, val payload: ByteArray) {
        val ok: Boolean get() = status == 0x00
    }

    /** [seq, flag, srcId, dstId, category, command, status] + payload */
    fun parseResponse(raw: ByteArray): Response = Response(
        seq = raw[0].toInt() and 0xFF,
        category = raw[4].toInt() and 0xFF,
        command = raw[5].toInt() and 0xFF,
        status = raw[6].toInt() and 0xFF,
        payload = if (raw.size > 7) raw.copyOfRange(7, raw.size) else ByteArray(0),
    )

    fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 1) "0$hex" else hex
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
