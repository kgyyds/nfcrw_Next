package com.kgapp.kptool.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import java.io.IOException

object MifareClassicTool {

    enum class AuthType { KEY_A, KEY_B }

    data class AuthResult(val ok: Boolean, val type: AuthType? = null)

    /** 写入详情（跟你 WriteActivity 对齐） */
    data class WriteDetail(val block: Int, val success: Boolean, val message: String? = null)

    /** 写入结果（跟你 WriteActivity 对齐） */
    data class WriteResult(val allSuccess: Boolean, val details: List<WriteDetail>)

    /** 每行 12 hex（6字节key），自动忽略空行与 # 注释 */
    fun parseKeys(lines: List<String>): List<ByteArray> =
        lines.mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val content = line.split("#")[0].trim()
            if (!content.matches(Regex("[0-9A-Fa-f]{12}"))) return@mapNotNull null
            hexToBytes(content)
        }

    /**
     * ✅ 一次连接读多个块（16 bytes），并缓存每个 sector 的认证结果
     * 返回：blockIndex -> 16字节数据
     */
    @Throws(IOException::class)
    fun readBlocks(tag: Tag, blockIndexes: List<Int>, keys: List<ByteArray>): Map<Int, ByteArray> {
        if (blockIndexes.isEmpty()) return emptyMap()

        val mfc = MifareClassic.get(tag) ?: throw IOException("Not a MIFARE Classic tag")
        val result = linkedMapOf<Int, ByteArray>()

        // 缓存 sector 是否已认证（以及用的是 A 还是 B）
        val authCache = HashMap<Int, AuthType>()

        try {
            mfc.connect()
            runCatching { mfc.timeout = 1500 }

            for (block in blockIndexes.distinct().sorted()) {
                if (block < 0 || block >= mfc.blockCount) {
                    throw IOException("Block out of range: $block (max=${mfc.blockCount - 1})")
                }

                val sector = mfc.blockToSector(block)

                if (!authCache.containsKey(sector)) {
                    val auth = authenticateSectorAB(mfc, sector, keys)
                    if (!auth.ok) throw IOException("Auth failed: sector=$sector")
                    authCache[sector] = auth.type!!
                }

                result[block] = mfc.readBlock(block)
            }
            return result
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }
    }

    /**
     * ✅ 一次连接写一个块（16 bytes）
     * 注意：写 sector trailer 很危险，这里默认禁止
     */
    @Throws(IOException::class)
    fun writeBlock(tag: Tag, blockIndex: Int, data16: ByteArray, keys: List<ByteArray>) {
        require(data16.size == 16) { "data must be exactly 16 bytes" }

        val mfc = MifareClassic.get(tag) ?: throw IOException("Not a MIFARE Classic tag")

        try {
            mfc.connect()
            runCatching { mfc.timeout = 1500 }

            if (blockIndex < 0 || blockIndex >= mfc.blockCount) {
                throw IOException("Block out of range: $blockIndex (max=${mfc.blockCount - 1})")
            }

            // 禁止写 block0（更安全）
            if (blockIndex == 0) {
                throw IOException("Refuse to write block0 (manufacturer block)")
            }

            val sector = mfc.blockToSector(blockIndex)
            val trailer = mfc.sectorToBlock(sector) + mfc.getBlockCountInSector(sector) - 1
            if (blockIndex == trailer) {
                throw IOException("Refuse to write sector trailer block=$blockIndex (dangerous)")
            }

            val auth = authenticateSectorAB(mfc, sector, keys)
            if (!auth.ok) throw IOException("Auth failed: sector=$sector")

            mfc.writeBlock(blockIndex, data16)
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }
    }

    /**
     * ✅ 一次连接写多个块（16 bytes），并缓存每个 sector 的认证结果
     * 你 WriteActivity 调的是这个：writeBlocks(tag, map, keys, allowTrailer)
     */
    @Throws(IOException::class)
    fun writeBlocks(
        tag: Tag,
        writeMap: Map<Int, ByteArray>,
        keys: List<ByteArray>,
        allowTrailer: Boolean = false
    ): WriteResult {
        if (writeMap.isEmpty()) return WriteResult(true, emptyList())

        val mfc = MifareClassic.get(tag) ?: throw IOException("Not a MIFARE Classic tag")
        val details = ArrayList<WriteDetail>()

        // 只缓存 sector“是否认证成功过”，同扇区写多块无需重复认证
        val authedSectors = HashSet<Int>()

        try {
            mfc.connect()
            runCatching { mfc.timeout = 1500 }

            val blocks = writeMap.keys.distinct().sorted()

            for (block in blocks) {
                val data16 = writeMap[block]
                if (data16 == null) {
                    details.add(WriteDetail(block, false, "Missing data"))
                    continue
                }
                if (data16.size != 16) {
                    details.add(WriteDetail(block, false, "Data must be exactly 16 bytes"))
                    continue
                }

                if (block < 0 || block >= mfc.blockCount) {
                    details.add(WriteDetail(block, false, "Block out of range: $block (max=${mfc.blockCount - 1})"))
                    continue
                }

                // block0 永久禁止
                if (block == 0) {
                    details.add(WriteDetail(block, false, "Refuse to write block0 (manufacturer block)"))
                    continue
                }

                val sector = mfc.blockToSector(block)
                val trailer = mfc.sectorToBlock(sector) + mfc.getBlockCountInSector(sector) - 1

                // trailer：默认禁止，除非 allowTrailer=true
                if (block == trailer && !allowTrailer) {
                    details.add(WriteDetail(block, false, "Refuse to write sector trailer (enable allowTrailer to override)"))
                    continue
                }

                // 认证：同扇区只做一次
                if (!authedSectors.contains(sector)) {
                    val auth = authenticateSectorAB(mfc, sector, keys)
                    if (!auth.ok) {
                        details.add(WriteDetail(block, false, "Auth failed: sector=$sector"))
                        continue
                    }
                    authedSectors.add(sector)
                }

                val ok = runCatching { mfc.writeBlock(block, data16) }.isSuccess
                if (ok) {
                    details.add(WriteDetail(block, true))
                } else {
                    details.add(WriteDetail(block, false, "writeBlock failed"))
                }
            }
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }

        return WriteResult(
            allSuccess = details.all { it.success },
            details = details
        )
    }

    /**
     * ✅ 像 MCT 一样：每个 key 都试一遍：KeyA -> KeyB
     * 命中就立刻返回命中的类型
     */
    private fun authenticateSectorAB(mfc: MifareClassic, sector: Int, keys: List<ByteArray>): AuthResult {
        for (k in keys) {
            val okA = runCatching { mfc.authenticateSectorWithKeyA(sector, k) }.getOrDefault(false)
            if (okA) return AuthResult(true, AuthType.KEY_A)

            val okB = runCatching { mfc.authenticateSectorWithKeyB(sector, k) }.getOrDefault(false)
            if (okB) return AuthResult(true, AuthType.KEY_B)
        }
        return AuthResult(false, null)
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
