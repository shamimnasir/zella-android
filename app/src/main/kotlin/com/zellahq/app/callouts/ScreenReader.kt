package com.zellahq.app.callouts

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.googlecode.tesseract.android.TessBaseAPI
import com.zellahq.app.editor.ExportOutcome
import com.zellahq.app.editor.ExportSettings
import com.zellahq.app.editor.Exporter
import com.zellahq.app.editor.appComposition
import com.zellahq.core.project.Callout
import com.zellahq.core.project.CalloutKind
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.duration
import com.zellahq.core.sensitive.BlurPlan
import com.zellahq.core.sensitive.Box
import com.zellahq.core.sensitive.OcrLine
import com.zellahq.core.sensitive.SensitiveBlurPlanner
import com.zellahq.core.sensitive.SensitiveHit
import com.zellahq.core.sensitive.SensitiveMatcher
import com.zellahq.core.sensitive.SensitiveScanner
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the text in the video, on this phone, with no network. Frames come from a real render of the
 * timeline, so a box lands in the same place on the canvas as the blur it becomes, whatever the
 * crop, zoom, speed or transitions. Latin script only.
 */
class ScreenReader(private val context: Context) : AutoCloseable {
    private val api = TessBaseAPI()

    init {
        val dir = File(context.filesDir, "ocr")
        val data = File(dir, "tessdata/eng.traineddata")
        if (!data.isFile || data.length() == 0L) {
            // Through a temporary name: a copy cut short by the app being killed must not pass for the real file.
            data.parentFile?.mkdirs()
            val part = File(data.path + ".part")
            context.assets.open("ocr/eng.traineddata").use { i -> part.outputStream().use { i.copyTo(it) } }
            check(part.renameTo(data)) { "The text reader's data could not be saved" }
        }
        check(api.init(dir.absolutePath, "eng")) { "The text reader could not start" }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
    }

    class Line(val line: OcrLine, private val chars: List<Box?>) {
        /** The box over these characters, or null when the reader did not place them. */
        fun charBox(
            location: Int,
            length: Int,
        ): Box? {
            val boxes = chars.subList(location.coerceAtMost(chars.size), (location + length).coerceAtMost(chars.size)).filterNotNull()
            return boxes.reduceOrNull { a, b -> a.union(b) }
        }
    }

    fun read(frame: Bitmap): List<Line> {
        val w = frame.width.toDouble()
        val h = frame.height.toDouble()
        api.setImage(frame)
        api.getUTF8Text()
        val it = api.getResultIterator() ?: return emptyList()
        val out = ArrayList<Line>()
        val text = StringBuilder()
        val chars = ArrayList<Box?>()
        var lineBox: Box? = null
        var confidence = 0f

        fun flush() {
            val b = lineBox
            if (b != null && text.isNotBlank()) out += Line(OcrLine(text.toString(), b, confidence / 100f), chars.toList())
            text.clear()
            chars.clear()
            lineBox = null
        }
        it.begin()
        do {
            if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) {
                flush()
                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                lineBox = Box(r.left / w, r.top / h, r.width() / w, r.height() / h)
                confidence = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
            } else if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_WORD)) {
                text.append(' ')
                chars.add(null)
            }
            val s = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL).orEmpty()
            val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL)
            text.append(s)
            repeat(s.length) { chars.add(Box(r.left / w, r.top / h, r.width() / w, r.height() / h)) }
        } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL))
        flush()
        it.delete()
        return out
    }

    override fun close() = api.recycle()

    companion object {
        /** How different two frames must be, 0 to 255 on average, before reading the second again. */
        private const val SAME_FRAME = 1.2
        private const val SCAN_SHORT_SIDE = 1080

        /** Renders the timeline, without callouts, to read from. The caller deletes the file. */
        fun renderForReading(
            context: Context,
            p: Project,
        ): File? {
            val store = ProjectStore(File(context.getExternalFilesDir(null), "projects"))
            val builder = appComposition(context, store)
            val out = File(context.cacheDir, "reading-${p.id}.mp4")
            val plain = p.copy(extras = kotlinx.serialization.json.JsonObject(p.extras - "callouts"))
            val outcome = Exporter(context, builder).render(builder.build(plain).composition, ExportSettings(SCAN_SHORT_SIDE), out)
            return (outcome as? ExportOutcome.Done)?.file
        }

        private fun difference(
            a: Bitmap,
            b: Bitmap,
        ): Double {
            var sum = 0L
            var n = 0
            val stepX = max(1, a.width / 48)
            val stepY = max(1, a.height / 48)
            var y = 0
            while (y < a.height) {
                var x = 0
                while (x < a.width) {
                    val p = a.getPixel(x, y)
                    val q = b.getPixel(x, y)
                    sum += abs((p shr 8 and 255) - (q shr 8 and 255))
                    n++
                    x += stepX
                }
                y += stepY
            }
            return sum.toDouble() / max(1, n)
        }
    }

    data class Scan(
        val samples: List<SensitiveBlurPlanner.Sample>,
        val lines: List<Pair<Double, OcrLine>>,
        val framesScanned: Int,
        val framesFailed: Int,
        val scannedUntil: Double,
        val hitLimit: Boolean,
    )

    /** Reads the frames at [times]. A frame the same as the last is not read twice, so a still screen is quick. */
    fun scan(
        video: File,
        times: List<Double>,
        matcher: SensitiveMatcher?,
        budgetSeconds: Double,
        progress: (Float) -> Unit = {},
    ): Scan {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(video.absolutePath)
        val samples = ArrayList<SensitiveBlurPlanner.Sample>()
        val lines = ArrayList<Pair<Double, OcrLine>>()
        var failed = 0
        var scanned = 0
        var until = 0.0
        var hitLimit = false
        var last: Bitmap? = null
        var lastRead: List<Line> = emptyList()
        val began = System.nanoTime()
        try {
            for ((i, t) in times.withIndex()) {
                if ((System.nanoTime() - began) / 1e9 > budgetSeconds) {
                    hitLimit = true
                    break
                }
                progress(i.toFloat() / times.size)
                val raw = retriever.getFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                if (raw == null) {
                    failed++
                    continue
                }
                val frame = raw.copy(Bitmap.Config.ARGB_8888, false)
                if (frame !== raw) raw.recycle()
                val prev = last
                val read = if (prev != null && difference(prev, frame) < SAME_FRAME) lastRead else read(frame)
                prev?.recycle()
                last = frame
                lastRead = read
                scanned++
                until = t
                read.forEach { lines += t to it.line }
                val hits: List<SensitiveHit> =
                    if (matcher == null) {
                        emptyList()
                    } else {
                        read.flatMap {
                                l ->
                            SensitiveScanner.hits(l.line, matcher) { a, b -> l.charBox(a, b) }
                        }
                    }
                samples += SensitiveBlurPlanner.Sample(t, hits)
            }
        } finally {
            last?.recycle()
            retriever.release()
        }
        return Scan(samples, lines, scanned, failed, until, hitLimit)
    }
}

/** Plans to callouts: no animation, since wiping a blur on would show what it hides. */
fun List<BlurPlan>.toCallouts(pixelate: Boolean): List<Callout> =
    map { p ->
        val r = p.rect
        Callout.create(
            if (pixelate) CalloutKind.Pixelate else CalloutKind.Blur,
            r.x,
            r.y,
            r.w,
            r.h,
            max(0.0, p.start),
            max(0.1, p.end - max(0.0, p.start)),
            track =
                if (p.trackTimes != null && p.trackX != null && p.trackY != null && p.trackTimes!!.size > 1) {
                    Triple(p.trackTimes!!, p.trackX!!, p.trackY!!)
                } else {
                    null
                },
        )
    }

/** What a scan for sensitive text found and did, in words safe to show or send on: secrets stay masked. */
data class BlurReport(val callouts: List<Callout>, val text: String)

fun describe(m: SensitiveMatcher): String =
    listOfNotNull(
        m.query?.let { "text matching \"$it\"" },
        if (m.secrets) "secrets (API keys, tokens, passwords, emails, card numbers)" else null,
    ).joinToString(" or ")

/** Reads the video and plans the blurs. Works away from the main thread; the caller applies them as one undo. */
fun findSensitiveText(
    context: Context,
    p: Project,
    matcher: SensitiveMatcher,
    start: Double?,
    end: Double?,
    pixelate: Boolean,
    progress: (Float) -> Unit = {},
    maxFrames: Int = 80,
    budgetSeconds: Double = 75.0,
): BlurReport {
    val dur = p.duration
    val s = (start ?: 0.0).coerceIn(0.0, dur)
    val e = (end ?: dur).coerceIn(s, dur)
    val video = ScreenReader.renderForReading(context, p) ?: return BlurReport(emptyList(), "The video could not be prepared for reading.")
    try {
        val (times, step) = SensitiveBlurPlanner.sampleTimes(s, e, 0.5, maxFrames)
        val scan = ScreenReader(context).use { it.scan(video, times, matcher, budgetSeconds, progress) }
        val coveredEnd = if (scan.hitLimit) scan.scannedUntil else e
        val plans = SensitiveBlurPlanner.plans(scan.samples, s, coveredEnd)
        val coverage =
            buildString {
                append("Scanned %d frames from %.2fs to %.2fs, one every %.2fs".format(scan.framesScanned, s, coveredEnd, step))
                if (scan.framesFailed > 0) append(" (${scan.framesFailed} frames could not be read)")
                if (scan.hitLimit) append(". Stopped early at the time limit: %.2fs to %.2fs was not scanned".format(coveredEnd, e))
                append('.')
            }
        val searched = describe(matcher)
        if (plans.isEmpty()) {
            return BlurReport(
                emptyList(),
                "No blur added: found no $searched on screen. $coverage Text that is very small, blurry, moving fast or not in Latin " +
                    "script may be missed. If you know where it is, add a blur box by hand.",
            )
        }
        val rows =
            plans.mapIndexed { i, b ->
                "%d. %s %s from %.2fs to %.2fs%s".format(
                    i + 1,
                    b.kind.displayName,
                    b.masked,
                    b.start,
                    b.end,
                    if (b.trackTimes != null) ", follows the text as it moves" else "",
                )
            }
        return BlurReport(
            plans.toCallouts(pixelate),
            "Added ${plans.size} ${if (pixelate) "pixelate" else "blur"} region${if (plans.size == 1) "" else "s"} over $searched. " +
                "One undo removes them all. $coverage\n" + rows.joinToString("\n"),
        )
    } finally {
        video.delete()
    }
}

/** Keeps a callout's box on the canvas. */
fun clampRect(
    x: Double,
    y: Double,
    w: Double,
    h: Double,
): List<Double> {
    val ww = w.coerceIn(0.02, 1.0)
    val hh = h.coerceIn(0.02, 1.0)
    return listOf(x.coerceIn(0.0, 1 - ww), y.coerceIn(0.0, 1 - hh), min(ww, 1.0), min(hh, 1.0))
}
