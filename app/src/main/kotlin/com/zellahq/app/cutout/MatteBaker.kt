@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app.cutout

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.tensorflow.lite.Interpreter
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Finds the person in every frame of a video file, on this phone, and keeps the result in a file:
 * one small grey picture per frame, 15 a second, up to 256 on the long side. It is worked out once per source file, so
 * the pieces of a cut clip share it, a seek needs no thinking and preview and export use exactly the
 * same answer. The model is MediaPipe's multiclass selfie segmenter (Apache 2.0).
 */
class MatteBaker(
    private val context: Context,
    /** How much of the last frame a still pixel keeps. See BUILDLOG Phase 7 for the measurement. */
    private val hold: Float = HOLD,
) {
    @Volatile private var stopped = false

    fun stop() {
        stopped = true
    }

    private fun load(): ByteBuffer {
        val fd = context.assets.openFd(MODEL)
        return FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    /** Works out the file for [video]. Blocking. Returns false when stopped or the video could not be read. */
    fun bake(
        video: File,
        progress: (Float) -> Unit = {},
    ): Boolean {
        val out = fileFor(context, video, hold)
        if (out.isFile && out.length() > 0) return true
        stopped = false
        out.parentFile?.mkdirs()
        val part = File(out.path + ".part")
        val interpreter = Interpreter(load(), Interpreter.Options().setNumThreads(4))
        val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val shape = interpreter.getOutputTensor(0).shape()
        val classes = shape.last()
        val output = ByteBuffer.allocateDirect(SIZE * SIZE * classes * 4).order(ByteOrder.nativeOrder())
        val smooth = FloatArray(SIZE * SIZE)
        var first = true
        val offsets = ArrayList<Long>()
        val deflater = Deflater(Deflater.BEST_SPEED)
        val packed = ByteArray(SIZE * SIZE + 1024)
        var done = false
        try {
            DataOutputStream(part.outputStream().buffered()).use { w ->
                var at = 0L
                var raw = ByteArray(0)
                done =
                    decode(video, progress) { rgb, box ->
                        if (first) {
                            w.writeInt(MAGIC)
                            w.writeInt(box.w)
                            w.writeInt(box.h)
                            w.writeInt(FPS)
                            at += 16
                            raw = ByteArray(box.w * box.h)
                        }
                        input.clear()
                        input.asFloatBuffer().put(rgb)
                        output.clear()
                        interpreter.run(input, output)
                        output.rewind()
                        foreground(output.asFloatBuffer(), classes, smooth, first)
                        first = false
                        // Only the picture itself is kept, without the bars the model was shown.
                        var k = 0
                        for (y in box.y until box.y + box.h) {
                            for (x in box.x until box.x + box.w) raw[k++] = (smooth[y * SIZE + x] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
                        }
                        deflater.reset()
                        deflater.setInput(raw)
                        deflater.finish()
                        val n = deflater.deflate(packed)
                        offsets += at
                        w.writeInt(n)
                        w.write(packed, 0, n)
                        at += 4 + n
                        !stopped
                    }
                offsets.forEach { w.writeLong(it) }
                w.writeInt(offsets.size)
            }
        } finally {
            deflater.end()
            interpreter.close()
        }
        if (!done || offsets.isEmpty()) {
            part.delete()
            return false
        }
        return part.renameTo(out)
    }

    /**
     * One minus the chance it is background. Where a pixel barely changed it is held steady, so the
     * edge does not crawl on a still speaker; a real move goes straight through, so it does not trail.
     */
    private fun foreground(
        o: java.nio.FloatBuffer,
        classes: Int,
        smooth: FloatArray,
        first: Boolean,
    ) {
        val scores = FloatArray(classes)
        for (i in 0 until SIZE * SIZE) {
            o.position(i * classes)
            o.get(scores)
            // The model gives raw scores. Softmax, without the rounding trouble of large values.
            val top = scores.max()
            var total = 0f
            var bg = 0f
            for (k in 0 until classes) {
                val e = exp(scores[k] - top)
                total += e
                if (k == 0) bg = e
            }
            val fg = 1f - bg / total
            if (first) {
                smooth[i] = fg
            } else {
                val d = abs(fg - smooth[i])
                val keep =
                    if (d < STILL) {
                        hold
                    } else if (d > MOVED) {
                        0f
                    } else {
                        hold * (MOVED - d) / (MOVED - STILL)
                    }
                smooth[i] = smooth[i] * keep + fg * (1 - keep)
            }
        }
    }

    /** Where the picture sits inside the model's square, with bars around it. */
    private class Inset(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * Calls [frame] with the model's input at 15 frames a second: upright RGB, -1 to 1, the whole
     * picture shrunk to fit a 256 square with black bars. Squashing it to fill the square gave phantom
     * people and holes in faces on real footage. [frame] returns false to stop.
     */
    private fun decode(
        video: File,
        progress: (Float) -> Unit,
        frame: (FloatArray, Inset) -> Boolean,
    ): Boolean {
        val ex = MediaExtractor()
        ex.setDataSource(video.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        if (track == null) {
            ex.release()
            return false
        }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 1L
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val fw = format.getInteger(MediaFormat.KEY_WIDTH)
        val fh = format.getInteger(MediaFormat.KEY_HEIGHT)
        val upW = if (rotation % 180 == 0) fw else fh
        val upH = if (rotation % 180 == 0) fh else fw
        val scale = SIZE.toFloat() / maxOf(upW, upH)
        val iw = (upW * scale).roundToInt().coerceIn(2, SIZE)
        val ih = (upH * scale).roundToInt().coerceIn(2, SIZE)
        val inset = Inset((SIZE - iw) / 2, (SIZE - ih) / 2, iw, ih)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val rgb = FloatArray(SIZE * SIZE * 3)
        var inputDone = false
        var nextUs = 0L
        var index = 0
        var ok = true
        try {
            while (ok) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                // Frames arrive in display order. Each slot takes the first frame at or after its time.
                if (info.size > 0 && info.presentationTimeUs >= nextUs) {
                    codec.getOutputImage(o)?.let { image ->
                        sample(image, rotation, inset, rgb)
                        while (nextUs <= info.presentationTimeUs) {
                            ok = frame(rgb, inset) && ok
                            index++
                            nextUs = index * 1_000_000L / FPS
                        }
                        image.close()
                        progress((info.presentationTimeUs.toFloat() / duration).coerceIn(0f, 1f))
                    }
                }
                codec.releaseOutputBuffer(o, false)
                if (eos) break
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        return ok
    }

    private fun sample(
        image: Image,
        rotation: Int,
        box: Inset,
        rgb: FloatArray,
    ) {
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val w = image.width
        val h = image.height
        val yb = y.buffer
        val ub = u.buffer
        val vb = v.buffer
        var k = 0
        for (row in 0 until SIZE) {
            val fy = (row - box.y + 0.5f) / box.h
            for (col in 0 until SIZE) {
                val fx = (col - box.x + 0.5f) / box.w
                if (fx < 0 || fx > 1 || fy < 0 || fy > 1) {
                    rgb[k++] = -1f
                    rgb[k++] = -1f
                    rgb[k++] = -1f
                    continue
                }
                // Where this upright point sits in the frame as stored.
                val sx: Float
                val sy: Float
                when (rotation) {
                    90 -> {
                        sx = fy
                        sy = 1 - fx
                    }
                    180 -> {
                        sx = 1 - fx
                        sy = 1 - fy
                    }
                    270 -> {
                        sx = 1 - fy
                        sy = fx
                    }
                    else -> {
                        sx = fx
                        sy = fy
                    }
                }
                val px = (sx * w).toInt().coerceIn(0, w - 1)
                val py = (sy * h).toInt().coerceIn(0, h - 1)
                val luma = (yb.get(py * y.rowStride + px * y.pixelStride).toInt() and 255) - 16
                val cu = (ub.get((py / 2) * u.rowStride + (px / 2) * u.pixelStride).toInt() and 255) - 128
                val cv = (vb.get((py / 2) * v.rowStride + (px / 2) * v.pixelStride).toInt() and 255) - 128
                val l = 1.164f * luma
                rgb[k++] = ((l + 1.596f * cv) / 255f).coerceIn(0f, 1f) * 2 - 1
                rgb[k++] = ((l - 0.392f * cu - 0.813f * cv) / 255f).coerceIn(0f, 1f) * 2 - 1
                rgb[k++] = ((l + 2.017f * cu) / 255f).coerceIn(0f, 1f) * 2 - 1
            }
        }
    }

    companion object {
        const val SIZE = 256
        const val FPS = 15
        private const val MODEL = "cutout/selfie_multiclass_256x256.tflite"
        private const val MAGIC = 0x5A4D5432

        /** Below this change a pixel counts as still. */
        private const val STILL = 0.10f
        private const val MOVED = 0.30f
        private const val HOLD = 0.75f

        fun fileFor(
            context: Context,
            video: File,
            hold: Float = HOLD,
        ): File {
            val text = "${video.absolutePath}|${video.length()}|${video.lastModified()}|$MODEL|$MAGIC|$hold"
            val name = MessageDigest.getInstance("SHA-1").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
            return File(context.cacheDir, "mattes/$name.matte")
        }

        fun isBaked(
            context: Context,
            video: File,
        ) = fileFor(context, video).let { it.isFile && it.length() > 0 }
    }
}

/** Reads frames back out of a matte file. Single thread at a time. */
class MatteFile(file: File) : AutoCloseable {
    private val f = RandomAccessFile(file, "r")
    val width: Int
    val height: Int
    val fps: Int
    val count: Int
    private val offsets: LongArray
    private val inflater = Inflater()
    private var packed = ByteArray(1 shl 16)

    init {
        f.seek(4)
        width = f.readInt()
        height = f.readInt()
        fps = f.readInt()
        f.seek(f.length() - 4)
        count = f.readInt()
        f.seek(f.length() - 4 - count * 8L)
        offsets = LongArray(count) { f.readLong() }
    }

    /** Frame [index] into [into], which holds width times height bytes, top row first. */
    fun read(
        index: Int,
        into: ByteArray,
    ) {
        f.seek(offsets[index.coerceIn(0, count - 1)])
        val n = f.readInt()
        if (packed.size < n) packed = ByteArray(n)
        f.readFully(packed, 0, n)
        inflater.reset()
        inflater.setInput(packed, 0, n)
        inflater.inflate(into)
    }

    override fun close() {
        inflater.end()
        f.close()
    }
}
