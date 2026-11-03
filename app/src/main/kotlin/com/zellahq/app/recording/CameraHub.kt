package com.zellahq.app.recording

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Owns the one open camera for the whole app. The camera screen hands it a preview surface and the
 * recorder hands it an encoder surface; whenever either changes, the capture session is rebuilt with
 * whatever is present. So recording survives the screen going away (encoder only) and coming back.
 *
 * Every camera call runs on one thread. Session rebuilds are serialised: close the old session, wait
 * for it to report closed, open the new one. Callers that must know frames have stopped (the stop
 * sequence) use the blocking variants, which return only once the new session is running.
 */
object CameraHub {
    private const val TAG = "ZellaCamera"

    data class Info(
        val id: String,
        val front: Boolean,
        val sensorOrientation: Int,
        /** Size the encoder gets, in sensor orientation (landscape on almost every phone). */
        val recordSize: Size,
        /** Size of the on-screen preview stream, same shape as [recordSize]. */
        val previewSize: Size,
        val realtimeTimestamps: Boolean,
        val fps: Range<Int>?,
    )

    sealed interface State {
        data object Closed : State

        data object Opening : State

        data class Open(val info: Info) : State

        /** The camera was taken by another app, disconnected, or failed. */
        data class Lost(val reason: String) : State
    }

    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private val mutableState = MutableStateFlow<State>(State.Closed)
    val state: StateFlow<State> = mutableState

    // Touched only on the camera thread.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    private var encoder: Surface? = null
    private var busy = false
    private var dirty = false

    // The preview stream always runs into a hidden texture, and the visible preview shares that
    // stream. Adding or removing the visible preview then needs no session rebuild, so leaving the
    // app or coming back does not freeze the recording (it froze 388 ms and 221 ms before).
    private var hiddenTexture: SurfaceTexture? = null
    private var hidden: Surface? = null
    private var previewConfig: OutputConfiguration? = null
    private var sessionPreview: Surface? = null
    private val waiters = ArrayList<CountDownLatch>()

    val info: Info? get() = (state.value as? State.Open)?.info

    /** Camera ids that face the person (front) and away (back), when the phone has them. */
    fun available(context: Context): Map<Boolean, String> {
        val cm = context.getSystemService(CameraManager::class.java)
        val out = LinkedHashMap<Boolean, String>()
        for (id in cm.cameraIdList) {
            val facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            val front = facing == CameraMetadata.LENS_FACING_FRONT
            if (facing == CameraMetadata.LENS_FACING_EXTERNAL) continue
            out.putIfAbsent(front, id)
        }
        return out
    }

    @SuppressLint("MissingPermission") // callers check CAMERA first
    fun open(
        context: Context,
        front: Boolean,
    ) {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = available(context)[front] ?: available(context).values.firstOrNull() ?: return
        val info = describe(cm, id)
        handler.post {
            closeOnThread()
            mutableState.value = State.Opening
            cm.openCamera(
                id,
                executor,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        mutableState.value = State.Open(info)
                        rebuild(null)
                    }

                    override fun onDisconnected(d: CameraDevice) = lost(d, "disconnected")

                    override fun onError(
                        d: CameraDevice,
                        error: Int,
                    ) = lost(d, "error $error")
                },
            )
        }
    }

    /** Opens and waits. For the service and tests, never the main thread. */
    fun openBlocking(
        context: Context,
        front: Boolean,
        timeoutMs: Long = 5_000,
    ): Info? {
        open(context, front)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            (state.value as? State.Open)?.let { return it.info }
            Thread.sleep(20)
        }
        return null
    }

    fun close() = handler.post { closeOnThread() }

    /** After a recording ends: close the camera unless the camera screen is still showing it. */
    fun closeIfUnused() = handler.post { if (preview == null && encoder == null) closeOnThread() }

    fun setPreview(surface: Surface?) =
        handler.post {
            preview = surface
            if (!swapPreview(surface, null)) rebuild(null)
        }

    /** Use from SurfaceHolder.surfaceDestroyed: the surface must be out of the session before it dies. */
    fun setPreviewBlocking(surface: Surface?) =
        blocking {
            preview = surface
            if (!swapPreview(surface, it)) rebuild(it)
        }

    /**
     * Swaps the visible preview inside the running session, then counts down [waiter]. Returns false
     * when a rebuild is needed instead. A surface can only leave the shared stream once no frame for
     * it is in flight, so removal waits for the first frame of the new request (results come in order).
     */
    private fun swapPreview(
        new: Surface?,
        waiter: CountDownLatch?,
    ): Boolean {
        val s = session ?: return false
        val pc = previewConfig ?: return false
        if (busy) return false
        val old = sessionPreview
        if (old == new) {
            waiter?.countDown()
            return true
        }

        fun addNew() {
            if (new != null) {
                pc.addSurface(new)
                s.updateOutputConfiguration(pc)
                sessionPreview = new
                repeat(s)
            }
            waiter?.countDown()
        }
        return try {
            if (old == null) {
                addNew()
            } else {
                sessionPreview = null
                repeat(s) {
                    // Only now is nothing in flight for the old surface.
                    try {
                        if (session === s) {
                            pc.removeSurface(old)
                            s.updateOutputConfiguration(pc)
                            addNew()
                        } else {
                            waiter?.countDown()
                        }
                    } catch (t: Exception) {
                        Log.w(TAG, "preview removal failed, rebuilding", t)
                        rebuild(waiter)
                    }
                }
            }
            true
        } catch (t: Exception) {
            Log.w(TAG, "preview swap without rebuild failed, rebuilding", t)
            false
        }
    }

    private fun repeat(
        s: CameraCaptureSession,
        onFirstFrame: (() -> Unit)? = null,
    ) {
        val d = device ?: return
        val template = if (encoder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val req = d.createCaptureRequest(template)
        listOfNotNull(hidden, sessionPreview, encoder).forEach { req.addTarget(it) }
        info?.fps?.let { req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        val callback =
            onFirstFrame?.let { first ->
                object : CameraCaptureSession.CaptureCallback() {
                    private var fired = false

                    private fun once() {
                        if (!fired) {
                            fired = true
                            first()
                        }
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) = once()

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) = once()
                }
            }
        s.setRepeatingRequest(req.build(), callback, handler)
    }

    /** Returns once frames flow to [surface], or once they have stopped flowing to the old one. */
    fun setEncoderBlocking(surface: Surface?) =
        blocking {
            encoder = surface
            rebuild(it)
        }

    private fun blocking(action: (CountDownLatch) -> Unit): Boolean {
        val done = CountDownLatch(1)
        handler.post { action(done) }
        val ok = done.await(3, TimeUnit.SECONDS)
        if (!ok) Log.e(TAG, "camera session change timed out")
        return ok
    }

    private fun lost(
        d: CameraDevice,
        why: String,
    ) {
        Log.e(TAG, "camera lost: $why")
        d.close()
        if (device == d) device = null
        session = null
        previewConfig = null
        sessionPreview = null
        mutableState.value = State.Lost(why)
        releaseWaiters()
    }

    private fun closeOnThread() {
        session?.close()
        session = null
        previewConfig = null
        sessionPreview = null
        hidden?.release()
        hiddenTexture?.release()
        hidden = null
        hiddenTexture = null
        device?.close()
        device = null
        busy = false
        dirty = false
        releaseWaiters()
        mutableState.value = State.Closed
    }

    private fun rebuild(waiter: CountDownLatch?) {
        waiter?.let { waiters += it }
        if (busy) {
            dirty = true
            return
        }
        busy = true
        val old = session
        session = null
        previewConfig = null
        sessionPreview = null
        if (old == null) {
            openSession()
            return
        }
        // Wait for the old session to say closed before opening the next, or the camera may still
        // be writing to a surface the caller is about to release.
        var proceeded = false
        val proceed = {
            if (!proceeded) {
                proceeded = true
                openSession()
            }
        }
        closedCallbacks[old] = proceed
        runCatching { old.stopRepeating() }
        runCatching { old.abortCaptures() }
        old.close()
        handler.postDelayed({ proceed() }, 1_000) // a dead device never reports closed
    }

    private val closedCallbacks = HashMap<CameraCaptureSession, () -> Unit>()

    private fun openSession() {
        val d = device
        val size = info?.previewSize
        if (d == null || size == null) {
            finish()
            return
        }
        if (hidden == null) {
            hiddenTexture = SurfaceTexture(false).apply { setDefaultBufferSize(size.width, size.height) }
            hidden = Surface(hiddenTexture)
        }
        val shared = OutputConfiguration(hidden!!).apply { enableSurfaceSharing() }
        val p = preview
        val withPreview = p != null && runCatching { shared.addSurface(p) }.isSuccess
        val outputs = listOfNotNull(shared, encoder?.let { OutputConfiguration(it) })
        val config =
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (device != d) {
                            s.close()
                            finish()
                            return
                        }
                        session = s
                        previewConfig = shared
                        sessionPreview = if (withPreview) p else null
                        runCatching { repeat(s) }.onFailure { Log.e(TAG, "repeating request", it) }
                        finish()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "session configure failed for ${outputs.size} outputs")
                        mutableState.value = State.Lost("configure failed")
                        finish()
                    }

                    override fun onClosed(s: CameraCaptureSession) {
                        closedCallbacks.remove(s)?.invoke()
                    }
                },
            )
        runCatching { d.createCaptureSession(config) }.onFailure {
            Log.e(TAG, "create session", it)
            finish()
        }
    }

    private fun finish() {
        busy = false
        if (dirty) {
            dirty = false
            rebuild(null)
        } else {
            releaseWaiters()
        }
    }

    private fun releaseWaiters() {
        waiters.forEach { it.countDown() }
        waiters.clear()
    }

    private fun describe(
        cm: CameraManager,
        id: String,
    ): Info {
        val c = cm.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val wide = { s: Size -> s.width * 9 == s.height * 16 }
        val encSizes = map.getOutputSizes(MediaCodec::class.java).orEmpty().toList()
        // 1080p is free forever; Pro 4K camera capture is a later phase.
        val record =
            encSizes.filter { wide(it) && it.width <= 1920 }.maxByOrNull { it.width * it.height }
                ?: encSizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width * it.height }
                ?: Size(1280, 720)
        val sameShape = { s: Size -> s.width * record.height == s.height * record.width }
        val previewSize =
            map.getOutputSizes(SurfaceHolder::class.java).orEmpty()
                .filter { sameShape(it) && it.width <= 1280 }
                .maxByOrNull { it.width * it.height } ?: record
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        // A fixed 30 fps keeps frame timing even; fall back to the widest range that reaches 30.
        val fps =
            ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                ?: ranges.filter { it.upper == 30 }.minByOrNull { it.lower }
        return Info(
            id = id,
            front = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            recordSize = record,
            previewSize = previewSize,
            realtimeTimestamps =
                c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            fps = fps,
        )
    }
}

/** Feeds the encoder from [CameraHub]. Attaching and detaching rebuild the camera session. */
class CameraVideoSource : VideoSource {
    override fun attach(
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        CameraHub.setEncoderBlocking(surface)
    }

    override fun detach() {
        CameraHub.setEncoderBlocking(null)
    }
}
