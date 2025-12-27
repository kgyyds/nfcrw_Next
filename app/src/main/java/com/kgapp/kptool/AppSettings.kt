package com.kgapp.kptool

import android.content.Context

object AppSettings {
    private const val PREFS = "kptool_prefs"
    private const val KEY_KEYS_TEXT = "keys_text"

    // 默认 key（你也可以加更多）
    private val DEFAULT_KEYS_TEXT = """
        # 每行一个 key：12位HEX（6字节）
        FFFFFFFFFFFF
        A0A1A2A3A4A5
        D3F7D3F7D3F7
    """.trimIndent()

    fun getKeysText(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_KEYS_TEXT, DEFAULT_KEYS_TEXT) ?: DEFAULT_KEYS_TEXT
    }

    fun setKeysText(context: Context, text: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_KEYS_TEXT, text).apply()
    }

    /** 把 keys 文本解析成 ByteArray 列表（自动过滤空行/注释/非法行） */
    fun parseKeysFromText(text: String): List<ByteArray> {
        val lines = text.lines()
        val keys = ArrayList<ByteArray>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val content = line.split("#")[0].trim()
            if (!content.matches(Regex("[0-9A-Fa-f]{12}"))) continue
            keys.add(hexToBytes(content))
        }
        return keys
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
