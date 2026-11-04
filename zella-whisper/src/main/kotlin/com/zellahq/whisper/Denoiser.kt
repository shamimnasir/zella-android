package com.zellahq.whisper

/**
 * RNNoise, frames of 480 samples at 48 kHz. Its output lags its input by exactly one frame, so a
 * wet and dry mix without alignment would comb-filter the voice and move it against the picture.
 */
class Denoiser(private val wet: Float = 1f) : AutoCloseable {
    companion object {
        const val FRAME = 480
        const val RATE = 48_000

        init {
            System.loadLibrary("zella_audio")
        }

        @JvmStatic external fun create(): Long

        @JvmStatic external fun destroy(h: Long)

        @JvmStatic external fun process(
            h: Long,
            buf: FloatArray,
            n: Int,
        ): Float
    }

    private var handle = create()

    /** One frame in place, with the frame of lag still in it. For a streaming caller that aligns by itself. */
    fun frame(buf: FloatArray) {
        process(handle, buf, FRAME)
    }

    /** A whole signal: same length as [x], lined up with it, mixed with the original by [wet]. */
    fun processAll(x: FloatArray): FloatArray {
        val frames = (x.size + FRAME - 1) / FRAME + 1
        val buf = x.copyOf(frames * FRAME)
        process(handle, buf, buf.size)
        return FloatArray(x.size) { wet * buf[it + FRAME] + (1 - wet) * x[it] }
    }

    override fun close() {
        if (handle != 0L) destroy(handle)
        handle = 0
    }
}
