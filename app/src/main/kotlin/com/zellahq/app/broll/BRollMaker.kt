@file:Suppress("ktlint:standard:max-line-length")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zellahq.app.broll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.zellahq.app.editor.Storage
import com.zellahq.core.auto.BRoll
import com.zellahq.core.auto.StockClip
import com.zellahq.core.project.Aspect
import com.zellahq.core.project.Clip
import com.zellahq.core.project.Project
import com.zellahq.core.project.duration
import com.zellahq.core.project.startTime
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

enum class StockProvider(val raw: String, val displayName: String, val keyPage: String) {
    Pexels("pexels", "Pexels", "https://www.pexels.com/api/"),
    Pixabay("pixabay", "Pixabay", "https://pixabay.com/api/docs/"),
}

sealed class StockProblem(message: String) : IOException(message) {
    class BadKey : StockProblem("That key was not accepted. Copy it again from the provider's page.")

    class Busy : StockProblem("The stock site says you have asked too much for now. Wait a little and try fewer clips.")

    class Network(detail: String) : StockProblem("Could not reach the stock site: $detail")
}

/** The network for stock clips, one place, so a test can stand in for it. */
interface StockNetwork {
    fun text(
        url: String,
        headers: Map<String, String>,
    ): String

    fun download(
        url: String,
        to: File,
    )
}

object HttpsStock : StockNetwork {
    private fun open(
        url: String,
        headers: Map<String, String>,
    ): HttpURLConnection {
        require(url.startsWith("https://")) { "Stock clips only come over HTTPS" }
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 45_000
        // Pixabay takes its key in the address. No cache, so that address is never written to disk.
        c.useCaches = false
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        when (c.responseCode) {
            400, 401, 403 -> throw StockProblem.BadKey()
            429 -> throw StockProblem.Busy()
            200 -> return c
            else -> throw StockProblem.Network("error ${c.responseCode}")
        }
    }

    override fun text(
        url: String,
        headers: Map<String, String>,
    ): String {
        val c = open(url, headers)
        try {
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    override fun download(
        url: String,
        to: File,
    ) {
        val c = open(url, emptyMap())
        try {
            c.inputStream.use { i -> to.outputStream().use { i.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }
}

/** A search for one word. The word and the person's key are all that is sent. */
fun stockSearch(
    net: StockNetwork,
    provider: StockProvider,
    key: String,
    word: String,
    orientation: String,
): List<StockClip> {
    val q = URLEncoder.encode(word, "UTF-8")
    return when (provider) {
        StockProvider.Pexels ->
            BRoll.parsePexels(
                net.text(
                    "https://api.pexels.com/videos/search?query=$q&per_page=8&size=medium&orientation=$orientation",
                    mapOf("Authorization" to key),
                ),
            )
        StockProvider.Pixabay ->
            BRoll.parsePixabay(
                net.text(
                    "https://pixabay.com/api/videos/?key=${URLEncoder.encode(key, "UTF-8")}&q=$q&per_page=8&safesearch=true",
                    emptyMap(),
                ),
            )
    }
}

/** Makes cutaways. Blocking; call away from the main thread. */
class BRollMaker(private val context: Context) {
    data class Outcome(val project: Project, val placed: Int, val note: String)

    private fun scope(
        p: Project,
        selected: String?,
    ): ClosedFloatingPointRange<Double>? {
        val c = p.clips.firstOrNull { it.id == selected } ?: return null
        // A single clip is the whole video: no real selection to scope to.
        if (p.clips.size < 2) return null
        val s = p.startTime(c.id)
        return s..(s + c.timelineDuration)
    }

    private fun orientation(p: Project) =
        when (p.aspect) {
            Aspect.Vertical, Aspect.Portrait45 -> "portrait"
            Aspect.Square -> "square"
            Aspect.Landscape -> "landscape"
        }

    private fun canvas(a: Aspect) =
        when (a) {
            Aspect.Vertical, Aspect.Portrait45 -> 1080 to 1920
            Aspect.Square -> 1080 to 1080
            Aspect.Landscape -> 1920 to 1080
        }

    /** The picture for a word. Honest about what it is: a generated card, not stock footage. */
    fun card(
        keyword: String,
        w: Int,
        h: Int,
    ): Bitmap {
        val word = keyword.trim().uppercase()
        val pal = PALETTES[(BRoll.stableHash(word) % PALETTES.size).toInt()]
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val g = Paint(Paint.DITHER_FLAG)
        g.shader = LinearGradient(w * 0.15f, 0f, w * 0.85f, h.toFloat(), pal[0], pal[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
        val maxW = (w * 0.82f).toInt()
        val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
        t.color = android.graphics.Color.WHITE
        t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        t.letterSpacing = 0.02f
        var size = w * 0.15f
        t.textSize = size
        while (t.measureText(word) > maxW && size > 30) {
            size -= 6
            t.textSize = size
        }
        val layout = StaticLayout.Builder.obtain(word, 0, word.length, t, maxW).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val top = (h - layout.height) / 2f
        c.save()
        c.translate((w - maxW) / 2f, top)
        layout.draw(c)
        c.restore()
        val uw = maxW * 0.42f
        val under = Paint().apply { color = pal[2] }
        val y = top + layout.height + h * 0.02f
        c.drawRect((w - uw) / 2, y, (w + uw) / 2, y + maxOf(6f, w * 0.009f), under)
        return b
    }

    /** Word cards, on this phone, no network. */
    fun cards(
        p: Project,
        selected: String?,
        count: Int = BRoll.DEFAULT_COUNT,
    ): Outcome {
        val cues = p.captions?.cues.orEmpty()
        if (cues.isEmpty()) return Outcome(p, 0, context.getString(com.zellahq.app.R.string.br_note_no_captions))
        val s = scope(p, selected)
        val places = BRoll.plan(cues, s, count, p.duration)
        if (places.isEmpty()) return Outcome(p, 0, context.getString(com.zellahq.app.R.string.br_note_no_words))
        val (w, h) = canvas(p.aspect)
        val made =
            places.mapNotNull { pl ->
                val base = "cutaway-${BRoll.stableHash(pl.keyword.uppercase())}-${w}x$h"
                val png = File(Storage.imports(context), "$base.png")
                val mp4 = File(Storage.imports(context), "$base.mp4")
                if (!mp4.isFile || mp4.length() == 0L) {
                    val bmp = card(pl.keyword, w, h)
                    png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                    val ok = com.zellahq.app.depth.Stills.encode(context, png, BRoll.CARD_SECONDS, mp4, minOf(w, h))
                    png.delete()
                    if (!ok) return@mapNotNull null
                }
                BRoll.clip(mp4.toURI().toString(), BRoll.CARD_SECONDS, BRoll.CARD_SECONDS) to pl.time
            }
        if (made.isEmpty()) return Outcome(p, 0, context.getString(com.zellahq.app.R.string.br_note_no_cards))
        return Outcome(BRoll.insert(p, made), made.size, context.getString(com.zellahq.app.R.string.br_note_cards, made.size))
    }

    /** Stock clips matched to words from the captions. Sends only each word and the person's own key. */
    fun stock(
        p: Project,
        selected: String?,
        provider: StockProvider,
        key: String,
        net: StockNetwork = HttpsStock,
        count: Int = BRoll.DEFAULT_COUNT,
        status: (String) -> Unit = {},
    ): Outcome {
        val cues = p.captions?.cues.orEmpty()
        if (cues.isEmpty()) return Outcome(p, 0, context.getString(com.zellahq.app.R.string.br_note_no_captions))
        val s = scope(p, selected)
        val places = BRoll.plan(cues, s, count, p.duration)
        if (places.isEmpty()) return Outcome(p, 0, context.getString(com.zellahq.app.R.string.br_note_no_words))
        val made = ArrayList<Pair<Clip, Double>>()
        var problem: String? = null
        for (pl in places) {
            status(pl.keyword)
            try {
                val hit = stockSearch(net, provider, key, pl.keyword, orientation(p)).firstOrNull() ?: continue
                val dest = File(Storage.imports(context), "broll-${com.zellahq.core.project.newId()}.mp4")
                net.download(hit.url, dest)
                made += BRoll.clip(dest.toURI().toString(), hit.duration) to pl.time
            } catch (e: StockProblem.BadKey) {
                return Outcome(p, 0, context.getString(com.zellahq.app.R.string.br_note_bad_key))
            } catch (e: StockProblem.Busy) {
                problem = context.getString(com.zellahq.app.R.string.br_note_busy)
                break
            } catch (e: IOException) {
                problem = context.getString(com.zellahq.app.R.string.br_note_offline)
            }
        }
        if (made.isEmpty()) return Outcome(p, 0, problem ?: context.getString(com.zellahq.app.R.string.br_note_none))
        return Outcome(BRoll.insert(p, made), made.size, context.getString(com.zellahq.app.R.string.br_note_stock, made.size))
    }

    private companion object {
        fun rgb(
            r: Double,
            g: Double,
            b: Double,
        ) = android.graphics.Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())

        val PALETTES =
            listOf(
                intArrayOf(rgb(0.10, 0.12, 0.22), rgb(0.04, 0.05, 0.09), rgb(1.00, 0.79, 0.24)),
                intArrayOf(rgb(0.16, 0.09, 0.20), rgb(0.06, 0.03, 0.09), rgb(0.98, 0.35, 0.55)),
                intArrayOf(rgb(0.07, 0.16, 0.20), rgb(0.03, 0.07, 0.09), rgb(0.24, 0.85, 0.78)),
                intArrayOf(rgb(0.09, 0.14, 0.10), rgb(0.03, 0.06, 0.04), rgb(0.36, 0.90, 0.44)),
                intArrayOf(rgb(0.12, 0.11, 0.22), rgb(0.05, 0.05, 0.11), rgb(0.42, 0.55, 1.00)),
                intArrayOf(rgb(0.20, 0.12, 0.06), rgb(0.09, 0.05, 0.02), rgb(1.00, 0.56, 0.20)),
            )
    }
}
