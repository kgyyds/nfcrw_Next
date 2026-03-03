package com.app33.sofw.nfc

object ValuePayload {
    fun build(value: Int, k: Int): ByteArray {
        val v16 = value.coerceIn(0, 0xFFFF)
        val bytes = ByteArray(16)
        bytes[0] = (v16 and 0xFF).toByte()
        bytes[1] = ((v16 shr 8) and 0xFF).toByte()
        bytes[2] = 0x00
        bytes[3] = 0x00
        bytes[4] = 0x40
        bytes[5] = 0x1F
        bytes[6] = 0x00
        bytes[7] = 0x00
        bytes[8] = 0x00
        bytes[9] = 0x00
        bytes[10] = 0x01
        bytes[11] = 0x00
        bytes[12] = 0x00
        bytes[13] = 0x00

        var sum = 0
        for (i in 0..13) {
            sum += bytes[i].toInt() and 0xFF
        }
        val sLow = sum and 0xFF
        bytes[14] = (k and 0xFF).toByte()
        bytes[15] = ((sLow + (k and 0xFF)) and 0xFF).toByte()
        return bytes
    }

    fun toHex32(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
