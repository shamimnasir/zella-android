package com.zellahq.core

import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

/**
 * Append-only file of encoded samples with exact timestamps. This is what a recording writes while
 * it runs. A clean stop turns it into an MP4; after a crash the next launch does the same with
 * whatever reached disk. A record that was half-written when the process died fails its checksum
 * and is dropped, along with anything after it.
 *
 * Why not fragmented MP4: Media3 1.10.1 writes no fragment start times and guesses the length of
 * the last sample in each fragment, so variable-rate screen video drifted 4 s in 27 s
 * (tasks/lessons.md, 2026-11-04).
 *
 * Layout: MAGIC, then records of [type u8][payload length u32][payload][crc32 of payload u32].
 */
object SampleJournal {
    private val MAGIC = "ZJRN1\n".toByteArray()
    private const val TRACK: Byte = 1
    private const val SAMPLE: Byte = 2

    /** A track's format as plain values: String, Int, Long or ByteArray. */
    data class Track(val id: Int, val format: Map<String, Any>)

    class Sample(val track: Int, val ptsUs: Long, val flags: Int, val data: ByteArray)

    class Writer(file: File, private val syncEveryMs: Long = 1_000) : Closeable {
        private val ch: FileChannel =
            FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        private var nextTrack = 0
        private var lastSyncNs = System.nanoTime()

        init {
            write(ByteBuffer.wrap(MAGIC))
        }

        @Synchronized
        fun addTrack(format: Map<String, Any>): Int {
            val id = nextTrack++
            record(TRACK, encodeFormat(id, format))
            return id
        }

        /** Copies [size] bytes from [data] at its current position. Every call is one write to the file. */
        @Synchronized
        fun writeSample(
            track: Int,
            ptsUs: Long,
            flags: Int,
            data: ByteBuffer,
            size: Int,
        ) {
            val payload = ByteBuffer.allocate(1 + 8 + 4 + size)
            payload.put(track.toByte()).putLong(ptsUs).putInt(flags)
            val slice = data.duplicate()
            slice.limit(slice.position() + size)
            payload.put(slice)
            record(SAMPLE, payload.array())
            // Writes reach the kernel at once, so a killed process loses nothing. force() also
            // covers a phone that powers off, at the cost of one flush a second.
            if (System.nanoTime() - lastSyncNs > syncEveryMs * 1_000_000) {
                ch.force(false)
                lastSyncNs = System.nanoTime()
            }
        }

        private fun record(
            type: Byte,
            payload: ByteArray,
        ) {
            val crc = CRC32().apply { update(payload) }.value.toInt()
            val b = ByteBuffer.allocate(1 + 4 + payload.size + 4)
            b.put(type).putInt(payload.size).put(payload).putInt(crc).flip()
            write(b)
        }

        private fun write(b: ByteBuffer) {
            while (b.hasRemaining()) ch.write(b)
        }

        @Synchronized
        override fun close() {
            ch.force(true)
            ch.close()
        }
    }

    /** Everything readable in [file]. [tornBytes] is how much of the tail was unusable and skipped. */
    class Contents(val tracks: List<Track>, val sampleCount: Int, val tornBytes: Long)

    /** Calls [onSample] for every intact sample, in file order. Tracks are always known before their samples. */
    fun read(
        file: File,
        onTrack: (Track) -> Unit = {},
        onSample: (Sample) -> Unit = {},
    ): Contents {
        val tracks = ArrayList<Track>()
        var samples = 0
        val total = file.length()
        var good = 0L
        DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(file), 1 shl 16)).use { input ->
            val magic = ByteArray(MAGIC.size)
            if (total < MAGIC.size) return Contents(emptyList(), 0, total)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return Contents(emptyList(), 0, total)
            good = MAGIC.size.toLong()
            while (good + 9 <= total) {
                val type = input.readByte()
                val len = input.readInt()
                if (len < 0 || good + 9 + len > total) break
                val payload = ByteArray(len)
                input.readFully(payload)
                val crc = input.readInt()
                if (CRC32().apply { update(payload) }.value.toInt() != crc) break
                val p = ByteBuffer.wrap(payload)
                when (type) {
                    TRACK ->
                        decodeFormat(p).also {
                            tracks += it
                            onTrack(it)
                        }
                    SAMPLE -> {
                        val track = p.get().toInt()
                        val pts = p.getLong()
                        val flags = p.getInt()
                        val data = ByteArray(p.remaining()).also { p.get(it) }
                        samples++
                        onSample(Sample(track, pts, flags, data))
                    }
                    else -> break
                }
                good += 9 + len
            }
        }
        return Contents(tracks, samples, total - good)
    }

    private fun encodeFormat(
        id: Int,
        format: Map<String, Any>,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeByte(id)
        d.writeShort(format.size)
        for ((k, v) in format) {
            d.writeUTF(k)
            when (v) {
                is String -> d.writeByte(0).also { d.writeUTF(v) }
                is Int -> d.writeByte(1).also { d.writeInt(v) }
                is Long -> d.writeByte(2).also { d.writeLong(v) }
                is ByteArray ->
                    d.writeByte(3).also {
                        d.writeInt(v.size)
                        d.write(v)
                    }
                else -> error("unsupported format value for $k: ${v::class}")
            }
        }
        return out.toByteArray()
    }

    private fun decodeFormat(p: ByteBuffer): Track {
        val d = DataInputStream(java.io.ByteArrayInputStream(p.array(), p.position(), p.remaining()))
        val id = d.readUnsignedByte()
        val n = d.readUnsignedShort()
        val m = LinkedHashMap<String, Any>()
        repeat(n) {
            val k = d.readUTF()
            m[k] =
                when (d.readUnsignedByte()) {
                    0 -> d.readUTF()
                    1 -> d.readInt()
                    2 -> d.readLong()
                    else -> ByteArray(d.readInt()).also { d.readFully(it) }
                }
        }
        return Track(id, m)
    }
}
