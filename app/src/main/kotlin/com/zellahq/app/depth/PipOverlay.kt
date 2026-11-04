@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app.depth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import com.zellahq.core.project.Overlay
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Reads one video's frames in order, as bitmaps. Media3's two-track path was tried and dropped for
 * the transitions, so a picture-in-picture clip is drawn over the main picture instead. Only forward
 * reads are cheap; a jump back reopens the file at the nearest sync frame.
 */
private class FrameReader(file: File, private val maxSide: Int) {
    private val ex = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val rotation: Int
    private var inputDone = false
    private var outputDone = false
    private var lastUs = -1L
    private var frame: Bitmap? = null
    private var pixels = IntArray(0)

    init {
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ex.selectTrack(track)
            val f = ex.getTrackFormat(track)
            rotation = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
            codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec.configure(f, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            ex.release()
            throw e
        }
    }

    /** The newest frame at or before [us]. */
    fun at(us: Long): Bitmap? {
        // A jump either way goes straight there. Decoding forward through a long gap would hold up the picture.
        if (us < lastUs - 100_000 || us > lastUs + 2_000_000) seek(us)
        var guard = 0
        while (lastUs < us && !outputDone && guard++ < 300) step(us)
        return frame
    }

    private fun seek(us: Long) {
        ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        lastUs = -1
    }

    private fun step(target: Long) {
        if (!inputDone) {
            val i = codec.dequeueInputBuffer(2_000)
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
        val o = codec.dequeueOutputBuffer(info, 2_000)
        if (o < 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
        if (info.size > 0) {
            // Frames short of the target are not converted: only the last one is ever drawn.
            if (info.presentationTimeUs + 40_000 >= target) codec.getOutputImage(o)?.let { convert(it) }
            lastUs = info.presentationTimeUs
        }
        codec.releaseOutputBuffer(o, false)
    }

    private fun convert(image: android.media.Image) {
        val w0 = image.width
        val h0 = image.height
        val upW = if (rotation % 180 == 0) w0 else h0
        val upH = if (rotation % 180 == 0) h0 else w0
        val s = min(1f, maxSide.toFloat() / max(upW, upH))
        val w = max(2, (upW * s).toInt())
        val h = max(2, (upH * s).toInt())
        val b = frame?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { frame = it }
        if (pixels.size != w * h) pixels = IntArray(w * h)
        val yp = image.planes[0]
        val up = image.planes[1]
        val vp = image.planes[2]
        val yb = yp.buffer
        val ub = up.buffer
        val vb = vp.buffer
        var k = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val fx = (x + 0.5f) / w
                val fy = (y + 0.5f) / h
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
                val px = (sx * w0).toInt().coerceIn(0, w0 - 1)
                val py = (sy * h0).toInt().coerceIn(0, h0 - 1)
                val l = ((yb.get(py * yp.rowStride + px * yp.pixelStride).toInt() and 255) - 16) * 298
                val cu = (ub.get((py / 2) * up.rowStride + (px / 2) * up.pixelStride).toInt() and 255) - 128
                val cv = (vb.get((py / 2) * vp.rowStride + (px / 2) * vp.pixelStride).toInt() and 255) - 128
                val r = ((l + 409 * cv + 128) shr 8).coerceIn(0, 255)
                val g = ((l - 100 * cu - 208 * cv + 128) shr 8).coerceIn(0, 255)
                val bl = ((l + 516 * cu + 128) shr 8).coerceIn(0, 255)
                pixels[k++] = (255 shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        b.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
        ex.release()
        frame?.recycle()
    }
}

/** Picture-in-picture clips over the main video, lower z first. The same drawing for preview and export. */
@OptIn(UnstableApi::class)
class PipOverlay(
    overlays: List<Overlay>,
    private val fileFor: (Overlay) -> File?,
    private val w: Int,
    private val h: Int,
) : CanvasOverlay(false) {
    private val ordered = overlays.sortedBy { it.z }
    private val readers = HashMap<String, FrameReader>()
    private val unreadable = HashSet<String>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init {
        setCanvasSize(w, h)
    }

    override fun onDraw(
        canvas: Canvas,
        presentationTimeUs: Long,
    ) {
        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val t = presentationTimeUs / 1e6
        ordered.forEach { o ->
            if (t < o.start || t >= o.end) {
                readers.remove(o.id)?.release()
                return@forEach
            }
            if (o.id in unreadable) return@forEach
            val reader =
                readers.getOrPut(o.id) {
                    val f = fileFor(o)
                    val opened = f?.let { runCatching { FrameReader(it, MAX_SIDE) }.getOrNull() }
                    if (opened == null) {
                        // Trying again on every frame would stall the whole picture.
                        unreadable += o.id
                        return@forEach
                    }
                    opened
                }
            val frame = reader.at(((t - o.start + o.sourceOffset) * 1e6).toLong()) ?: return@forEach
            draw(canvas, o, frame)
        }
    }

    private fun draw(
        canvas: Canvas,
        o: Overlay,
        frame: Bitmap,
    ) {
        val rw = (o.scale * w).toFloat()
        val aspect = frame.width.toFloat() / frame.height
        val rh =
            when {
                o.style == "circle" -> rw
                o.scaleY != null -> (o.scaleY!! * h).toFloat()
                else -> rw / aspect
            }
        val cx = (o.x * w).toFloat()
        val cy = (o.y * h).toFloat()
        val dst = RectF(cx - rw / 2, cy - rh / 2, cx + rw / 2, cy + rh / 2)
        val saved = canvas.save()
        val clip = Path()
        when (o.style) {
            "circle" -> {
                clip.addOval(dst, Path.Direction.CW)
                canvas.clipPath(clip)
            }
            "roundedRect" -> {
                val r = (o.cornerRadius * min(rw, rh)).toFloat()
                clip.addRoundRect(dst, r, r, Path.Direction.CW)
                canvas.clipPath(clip)
            }
        }
        if (o.mirrored) canvas.scale(-1f, 1f, cx, cy)
        // Fill the box, cropping the picture rather than stretching it.
        val boxAspect = rw / rh
        val srcW: Float
        val srcH: Float
        if (aspect > boxAspect) {
            srcH = frame.height.toFloat()
            srcW = srcH * boxAspect
        } else {
            srcW = frame.width.toFloat()
            srcH = srcW / boxAspect
        }
        val l = (frame.width - srcW) / 2
        val top = (frame.height - srcH) / 2
        canvas.drawBitmap(frame, android.graphics.Rect(l.toInt(), top.toInt(), (l + srcW).toInt(), (top + srcH).toInt()), dst, paint)
        canvas.restoreToCount(saved)
    }

    override fun release() {
        super.release()
        readers.values.forEach { it.release() }
        readers.clear()
    }

    private companion object {
        const val MAX_SIDE = 720
    }
}
