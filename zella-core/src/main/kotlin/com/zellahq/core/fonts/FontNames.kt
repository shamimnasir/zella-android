package com.zellahq.core.fonts

import java.nio.ByteBuffer

/**
 * Reads a font's PostScript name (name ID 6) from its bytes. The iPhone and Mac store fonts by that
 * name, so a project's font means the same thing on every platform. Handles .ttf, .otf, and the first
 * font of a .ttc or .otc collection. Returns null for anything that is not a font.
 */
object FontNames {
    const val MAX_BYTES = 160L * 1024 * 1024
    val EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    fun postScriptName(bytes: ByteArray): String? =
        runCatching {
            val b = ByteBuffer.wrap(bytes)
            var base = 0
            if (tag(b, 0) == "ttcf") base = b.getInt(12) // first font in a collection
            val tables = b.getShort(base + 4).toInt() and 0xFFFF
            if (tables !in 1..200) return null
            var nameOffset = -1
            for (i in 0 until tables) {
                val rec = base + 12 + i * 16
                if (tag(b, rec) == "name") nameOffset = b.getInt(rec + 8)
            }
            if (nameOffset < 0) return null
            val count = b.getShort(nameOffset + 2).toInt() and 0xFFFF
            val strings = nameOffset + (b.getShort(nameOffset + 4).toInt() and 0xFFFF)
            var mac: String? = null
            for (i in 0 until count) {
                val r = nameOffset + 6 + i * 12
                val platform = b.getShort(r).toInt() and 0xFFFF
                val nameId = b.getShort(r + 6).toInt() and 0xFFFF
                if (nameId != 6) continue
                val len = b.getShort(r + 8).toInt() and 0xFFFF
                val off = strings + (b.getShort(r + 10).toInt() and 0xFFFF)
                val raw = bytes.copyOfRange(off, off + len)
                when (platform) {
                    3, 0 -> return String(raw, Charsets.UTF_16BE).trim().ifEmpty { null }
                    1 -> mac = String(raw, Charsets.ISO_8859_1).trim()
                }
            }
            mac?.ifEmpty { null }
        }.getOrNull()

    private fun tag(
        b: ByteBuffer,
        at: Int,
    ) = String(ByteArray(4) { b.get(at + it) }, Charsets.US_ASCII)
}
