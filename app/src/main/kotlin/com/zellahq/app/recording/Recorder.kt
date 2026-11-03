package com.zellahq.app.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.zellahq.core.CameraMath
import com.zellahq.core.RecordingClock
import com.zellahq.core.RecordingJournal
import com.zellahq.core.SampleJournal
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Something that draws frames onto the encoder's input surface: the screen, or a test pattern. */
interface VideoSource {
    fun attach(
        surface: Surface,
        width: Int,
        height: Int,
    )

    /** Must stop all drawing before it returns. */
    fun detach()
}

/** Builds an AudioRecord. Returns null when the source cannot be opened. */
fun interface AudioSourceFactory {
    fun open(
        sampleRate: Int,
        channelMask: Int,
        bufferBytes: Int,
    ): AudioRecord?
}

data class RecorderConfig(
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    val videoBitrate: Int = 8_000_000,
    val audioSampleRate: Int = 48_000,
    val audioBitrate: Int = 128_000,
    /** Measured by [AacDelay]. Audio is moved this much earlier so it lines up with the picture. */
    val audioEncoderDelayUs: Long = 0,
    /** Stored in the MP4 so players turn the video upright. Pixels are never rotated. */
    val rotationDegrees: Int = 0,
    /** The camera stamps frames with time since boot including deep sleep; convert to monotonic. */
    val videoRealtimeTimestamps: Boolean = false,
    /**
     * Repeat the last frame when no new one arrives for this long, so a still screen has no long gaps.
     * Null for the camera: it delivers every 33 ms with jitter, and a 33 ms repeat fired just before
     * each real frame, doubling a third of all frames (tasks/lessons.md, 2026-11-04).
     */
    val repeatFrameAfterUs: Long? = null,
)

data class RecorderReport(
    val file: File,
    val audioTracks: List<String>,
    /** Names of audio tracks that were silent from start to end. */
    val silentTracks: List<String>,
    val drainTimeouts: Int,
    /** 20 ms audio chunks lost because an encoder was too slow. Each one is a short silent hole. */
    val droppedAudioChunks: Int,
    /** First video frame time minus the recorder's start time, in ms. Large values mean the clocks differ. */
    val videoClockOffsetMs: Long?,
    /** Time to turn the journal into the MP4 after Stop. */
    val saveMs: Long,
)

/**
 * Screen or pattern video plus up to two audio tracks. While recording, samples go to a
 * [SampleJournal] next to [out]; Stop turns the journal into [out] and deletes it.
 *
 * Every encoder is fed raw CLOCK_MONOTONIC timestamps. [RecordingClock] maps them to
 * presentation times as samples are written, so pause and resume treat picture and sound identically.
 *
 * Stop sequence (docs/STOP_SEQUENCE.md, do not reorder without re-running the loop test):
 *  1. stop audio readers and join them; each queues end-of-stream into its encoder
 *  2. detach the video source, then signal end-of-stream to the video encoder
 *  3. join every drain thread (each exits on its end-of-stream buffer)
 *  4. close the journal
 *  5. release codecs and AudioRecords
 *  6. copy the journal into the MP4, then delete the journal
 */
class Recorder(
    private val out: File,
    private val config: RecorderConfig,
    private val video: VideoSource,
    audioSources: Map<String, AudioSourceFactory>,
) {
    private val tag = "ZellaRecorder"
    private val startNs = System.nanoTime()
    val clock = RecordingClock()
    private val journalFile = RecordingJournal(out.parentFile!!).journalFor(out)
    private lateinit var journal: SampleJournal.Writer
    private lateinit var videoCodec: MediaCodec
    private val audio = ArrayList<AudioTrackCapture>()
    private val drains = ArrayList<Thread>()
    private var formatsReady: CountDownLatch? = null

    @Volatile private var drainTimeouts = 0

    /** Set when a drain thread missed its deadline, so it exits before its codec is released. */
    @Volatile private var abandon = false

    @Volatile private var firstVideoRawUs: Long? = null
    private var pendingAudio = audioSources

    fun start() {
        journal = SampleJournal.Writer(journalFile)

        val opened = pendingAudio.mapNotNull { (name, factory) -> AudioTrackCapture.open(name, factory, config) }
        audio.addAll(opened)
        formatsReady = CountDownLatch(1 + audio.size)

        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val vf =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                config.repeatFrameAfterUs?.let { setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, it) }
            }
        videoCodec.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = videoCodec.createInputSurface()
        videoCodec.start()
        drains += drainThread("video", videoCodec, isVideo = true)
        audio.forEach { a ->
            drains += drainThread(a.name, a.codec, isVideo = false)
            a.startReading(startNs)
        }
        drains.forEach { it.start() }
        video.attach(surface, config.width, config.height)
    }

    /** Audio tracks that really opened. Call after [start]. */
    fun openedTracks(): List<String> = audio.map { it.name }

    fun pause() {
        clock.pause(System.nanoTime())
        videoCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 1) })
    }

    fun resume() {
        videoCodec.setParameters(
            Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 0)
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            },
        )
        clock.resume(System.nanoTime())
    }

    fun stop(): RecorderReport {
        audio.forEach { it.stopReading() }
        video.detach()
        runCatching { videoCodec.signalEndOfInputStream() }.onFailure { Log.w(tag, "video EOS", it) }
        drains.forEach { t ->
            t.join(5_000)
            if (t.isAlive) {
                drainTimeouts++
                Log.e(tag, "drain ${t.name} did not finish")
            }
        }
        if (drainTimeouts > 0) {
            abandon = true
            drains.forEach { it.join(1_000) }
        }
        runCatching { journal.close() }.onFailure { Log.e(tag, "journal close", it) }
        runCatching { videoCodec.stop() }
        videoCodec.release()
        audio.forEach { it.release() }
        val saved = Remuxer.toMp4(journalFile, out)
        journalFile.delete()
        Log.i(tag, "saved ${out.name}: $saved")
        val offset = firstVideoRawUs?.let { it / 1000 - startNs / 1_000_000 }
        return RecorderReport(
            file = out,
            audioTracks = audio.map { it.name },
            silentTracks = audio.filter { it.peak == 0 }.map { it.name },
            drainTimeouts = drainTimeouts,
            droppedAudioChunks = audio.sumOf { it.droppedChunks },
            videoClockOffsetMs = offset,
            saveMs = saved.millis,
        )
    }

    private fun drainThread(
        name: String,
        codec: MediaCodec,
        isVideo: Boolean,
    ) = Thread({
        val info = MediaCodec.BufferInfo()
        var track = -1
        var lastUs = -1L
        while (!abandon) {
            val i = codec.dequeueOutputBuffer(info, 10_000)
            when {
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val map = JournalFormats.toMap(codec.outputFormat).toMutableMap()
                    if (isVideo && config.rotationDegrees != 0) map[JournalFormats.ROTATION] = config.rotationDegrees
                    track = journal.addTrack(map)
                    formatsReady?.countDown()
                    // The MP4 needs every track before the first sample, so samples wait for all formats.
                    if (formatsReady?.await(3, TimeUnit.SECONDS) == false) Log.w(tag, "$name: not all tracks ready")
                }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)!!
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) {
                        val rawNs =
                            if (isVideo) {
                                CameraMath.toMonotonicNs(
                                    info.presentationTimeUs * 1_000,
                                    config.videoRealtimeTimestamps,
                                    SystemClock.elapsedRealtimeNanos(),
                                    System.nanoTime(),
                                )
                            } else {
                                info.presentationTimeUs * 1_000
                            }
                        if (isVideo && firstVideoRawUs == null) {
                            firstVideoRawUs = rawNs / 1_000
                            clock.startAt(rawNs)
                        }
                        val shift = if (isVideo) 0 else config.audioEncoderDelayUs
                        val pts = clock.presentationUs(rawNs)?.minus(shift)?.takeIf { it >= 0 }
                        if (pts != null && pts > lastUs) {
                            buf.position(info.offset)
                            val flags = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME
                            journal.writeSample(track, pts, flags, buf, info.size)
                            lastUs = pts
                        }
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (eos) break
                }
            }
        }
    }, "drain-$name")
}

/** One AudioRecord feeding one AAC encoder on its own thread. */
internal class AudioTrackCapture private constructor(
    val name: String,
    private val record: AudioRecord,
    val codec: MediaCodec,
    private val rate: Int,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var peak = 0
        private set

    @Volatile var droppedChunks = 0
        private set

    fun startReading(startNs: Long) {
        running = true
        record.startRecording()
        thread =
            Thread({ readLoop(startNs) }, "read-$name").also { it.start() }
    }

    private fun readLoop(startNs: Long) {
        val chunk = ShortArray(rate / 50) // 20 ms mono
        val ts = AudioTimestamp()
        var frames = 0L
        var anchorNs = -1L
        while (running) {
            val n = record.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            // Prefer the hardware timestamp: it tracks the audio clock's drift against the system clock.
            val chunkNs =
                if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    ts.nanoTime + (frames - ts.framePosition) * 1_000_000_000L / rate
                } else {
                    if (anchorNs < 0) anchorNs = System.nanoTime() - n * 1_000_000_000L / rate
                    anchorNs + frames * 1_000_000_000L / rate
                }
            frames += n
            for (k in 0 until n) peak = maxOf(peak, abs(chunk[k].toInt()))
            if (chunkNs < startNs) continue
            queue(chunk, n, chunkNs / 1_000)
        }
    }

    private fun queue(
        pcm: ShortArray,
        n: Int,
        ptsUs: Long,
    ) {
        // Wait up to 100 ms: AudioRecord buffers 200 ms, so a slow encoder costs nothing. Dropping
        // a chunk leaves an audible hole, so it is counted and reported.
        val i = codec.dequeueInputBuffer(100_000)
        if (i < 0) {
            droppedChunks++
            return
        }
        val buf = codec.getInputBuffer(i)!!
        buf.clear()
        buf.asShortBuffer().put(pcm, 0, n)
        codec.queueInputBuffer(i, 0, n * 2, ptsUs, 0)
    }

    /**
     * Stops the reader, then delivers end-of-stream from the calling thread. End-of-stream must never
     * be dropped: without it the encoder never finishes and the stop sequence times out. This was the
     * first failure the loop test caught (lessons.md, 2026-11-04).
     */
    fun stopReading() {
        running = false
        runCatching { record.stop() } // unblocks a read() that is waiting for audio
        thread?.join(2_000)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            val i = codec.dequeueInputBuffer(20_000)
            if (i >= 0) {
                codec.queueInputBuffer(i, 0, 0, System.nanoTime() / 1_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
        }
        Log.e("ZellaRecorder", "$name: no input buffer for end-of-stream")
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
        record.release()
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun open(
            name: String,
            factory: AudioSourceFactory,
            config: RecorderConfig,
        ): AudioTrackCapture? {
            val rate = config.audioSampleRate
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = factory.open(rate, AudioFormat.CHANNEL_IN_MONO, maxOf(min, rate / 5 * 2)) ?: return null
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val f =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, rate / 50 * 2)
                }
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            return AudioTrackCapture(name, record, codec, rate)
        }
    }
}
