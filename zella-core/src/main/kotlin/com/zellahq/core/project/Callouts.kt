package com.zellahq.core.project

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Raw values match the iPhone and Mac exactly, so projects speak one language. */
enum class CalloutKind(val raw: String, val label: String) {
    Arrow("arrow", "Arrow"),
    Box("box", "Box"),
    Ellipse("ellipse", "Circle"),
    Label("label", "Label"),
    Keystroke("keystroke", "Key press"),
    Spotlight("spotlight", "Spotlight"),
    Number("number", "Number"),
    Blur("blur", "Blur"),
    LeaderLine("leaderLine", "Pointer line"),
    Popup("popup", "Pop-up"),
    Magnifier("magnifier", "Magnifier"),
    Highlight("highlight", "Highlighter"),
    Underline("underline", "Underline"),
    Pixelate("pixelate", "Pixelate"),
    Emoji("emoji", "Emoji"),
    StickyNote("stickyNote", "Sticky note"),
    ScribbleCircle("scribbleCircle", "Scribble"),
    Timer("timer", "Timer"),
    Glitch("glitch", "Glitch"),
    ;

    /** These change the video's own pixels, so they run on the picture rather than on a drawn layer. */
    val isFrameEffect get() = this == Blur || this == Pixelate || this == Glitch || this == Magnifier

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

data class CalloutPhase(val alpha: Double = 1.0, val scale: Double = 1.0, val dx: Double = 0.0, val dy: Double = 0.0)

/**
 * A callout, read from the fields the iPhone stores in `project.callouts`. [raw] keeps the object as
 * written, so fields this build does not know survive a save. Rect is 0 to 1, top-left origin.
 */
class Callout(val raw: JsonObject) {
    private fun num(k: String) = (raw[k] as? JsonPrimitive)?.doubleOrNull

    private fun str(k: String) = (raw[k] as? JsonPrimitive)?.contentOrNull

    val id get() = str("id").orEmpty()
    val kind get() = CalloutKind.of(str("kind"))
    val text get() = str("text")
    val start get() = num("start") ?: 0.0
    val duration get() = num("duration") ?: 3.0
    val end get() = start + duration
    val color get() = Rgba.of(raw["color"]) ?: ACCENT
    val strokeScale get() = num("strokeScale") ?: 1.0
    val opacity get() = (num("opacity") ?: 1.0).coerceIn(0.2, 1.0)
    val rotationDegrees get() = num("rotationDegrees")
    val magnifyScale get() = num("magnifyScale") ?: 2.0
    val entrance get() = str("animIn") ?: "none"
    val exit get() = str("animOut") ?: "none"

    /** x, y, width, height. Swift's CGRect is `[[x, y], [w, h]]`. */
    val rect: List<Double>
        get() {
            val r = raw["rect"] as? JsonArray ?: return listOf(0.3, 0.35, 0.4, 0.2)
            val o = r.getOrNull(0)?.jsonArray
            val s = r.getOrNull(1)?.jsonArray

            fun v(
                a: JsonArray?,
                i: Int,
            ) = a?.getOrNull(i)?.jsonPrimitive?.doubleOrNull ?: 0.0
            return listOf(v(o, 0), v(o, 1), v(s, 0), v(s, 1))
        }

    private fun list(k: String) = (raw[k] as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull }

    /** Top-left of the rect at [t], following a tracked path when there is one. */
    fun rectAt(t: Double): List<Double> {
        val r = rect
        val c = trackedCenter(t) ?: return r
        return listOf(c.first - r[2] / 2, c.second - r[3] / 2, r[2], r[3])
    }

    fun trackedCenter(at: Double): Pair<Double, Double>? {
        val ts = list("trackTimes") ?: return null
        val xs = list("trackX") ?: return null
        val ys = list("trackY") ?: return null
        if (ts.size < 2 || ts.size != xs.size || ts.size != ys.size) return null
        val t = at.coerceIn(ts.first(), ts.last())
        var i = 0
        while (i + 1 < ts.size && ts[i + 1] < t) i++
        val j = min(i + 1, ts.size - 1)
        val f = if (ts[j] > ts[i]) (t - ts[i]) / (ts[j] - ts[i]) else 0.0
        return (xs[i] + (xs[j] - xs[i]) * f) to (ys[i] + (ys[j] - ys[i]) * f)
    }

    fun isActive(t: Double) = t >= start && t <= end

    /** Same clock as the iPhone: a 0.15 s settle, then 1.1 s of drawing. */
    fun drawOnProgress(time: Double): Double {
        if (entrance != "drawOn") return 1.0
        val settled = time - start - 0.15
        return if (settled <= 0) 0.0 else min(1.0, settled / 1.1)
    }

    fun phase(time: Double): CalloutPhase {
        val inKind = entrance
        val outKind = exit
        if ((inKind == "none" && outKind == "none") || duration <= 0) return CalloutPhase()
        val t = time - start
        if (t < 0 || t > duration) return CalloutPhase()
        if (inKind != "none") {
            val d = min(0.45, duration / 3)
            if (d > 0 && t < d) {
                val p = (t / d).coerceIn(0.0, 1.0)
                when (inKind) {
                    "fade" -> return CalloutPhase(alpha = p)
                    "pop" -> {
                        val u = p - 1
                        val back = 1 + 2.70158 * u * u * u + 1.70158 * u * u
                        return CalloutPhase(alpha = min(1.0, p * 1.6), scale = 0.75 + 0.25 * back)
                    }
                    "slideUp", "slideDown" -> {
                        val eased = 1 - (1 - p).pow(3)
                        val from = if (inKind == "slideUp") SLIDE else -SLIDE
                        return CalloutPhase(alpha = min(1.0, p * 1.5), dy = from * (1 - eased))
                    }
                }
            }
        }
        if (outKind != "none") {
            val d = min(0.35, duration / 4)
            if (d > 0 && t > duration - d) return CalloutPhase(alpha = max(0.0, (duration - t) / d))
        }
        return CalloutPhase()
    }

    val isCounting get() = kind == CalloutKind.Number && num("countFrom") != null && num("countTo") != null

    fun numberText(time: Double): String {
        if (!isCounting) return text ?: "1"
        val a = num("countFrom") ?: 0.0
        val b = num("countTo") ?: 0.0
        val span = max(0.05, num("countSeconds") ?: (duration * 0.6))
        val p = (max(0.0, time - start) / span).coerceIn(0.0, 1.0)
        val v = a + (b - a) * (1 - (1 - p).pow(3))
        val decimals = ((raw["countDecimals"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 3)
        val grouping = (raw["countGrouping"] as? JsonPrimitive)?.booleanOrNull ?: true
        val body = String.format(java.util.Locale.ROOT, if (grouping) "%,.${decimals}f" else "%.${decimals}f", v)
        return str("countPrefix").orEmpty() + body + str("countSuffix").orEmpty()
    }

    fun timerText(time: Double): String {
        val total = max(1.0, num("timerSeconds") ?: duration)
        val elapsed = (time - start).coerceIn(0.0, total)
        val up = (raw["timerCountsUp"] as? JsonPrimitive)?.booleanOrNull ?: false
        val v = if (up) floor(elapsed).toInt() else ceil(total - elapsed).toInt()
        return "%02d:%02d".format(v / 60, v % 60)
    }

    fun withFields(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) = Callout(JsonObject(raw + fields))

    companion object {
        val ACCENT = Rgba(0.8314, 0.6863, 0.2157)
        private const val SLIDE = 0.08

        fun rectJson(
            x: Double,
            y: Double,
            w: Double,
            h: Double,
        ) = JsonArray(
            listOf(
                JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))),
                JsonArray(listOf(JsonPrimitive(w), JsonPrimitive(h))),
            ),
        )

        /** A new callout. Marking-up kinds draw themselves on and everything else pops, like the iPhone; redactions just appear. */
        fun create(
            kind: CalloutKind,
            x: Double = 0.3,
            y: Double = 0.35,
            w: Double = 0.4,
            h: Double = 0.2,
            start: Double = 0.0,
            duration: Double = 3.0,
            text: String? = null,
            color: Rgba = ACCENT,
            track: Triple<List<Double>, List<Double>, List<Double>>? = null,
        ): Callout {
            val redaction = kind == CalloutKind.Blur || kind == CalloutKind.Pixelate
            val annotation =
                kind == CalloutKind.Highlight || kind == CalloutKind.Underline || kind == CalloutKind.Box || kind == CalloutKind.Ellipse
            return Callout(
                JsonObject(
                    buildMap {
                        put("id", JsonPrimitive(newId()))
                        put("kind", JsonPrimitive(kind.raw))
                        put("rect", rectJson(x, y, w, h))
                        put("color", color.json())
                        text?.let { put("text", JsonPrimitive(it)) }
                        put("start", JsonPrimitive(start))
                        put("duration", JsonPrimitive(duration))
                        if (!redaction) {
                            put("animIn", JsonPrimitive(if (annotation) "drawOn" else "pop"))
                            put("animOut", JsonPrimitive("fade"))
                        }
                        track?.let { (ts, xs, ys) ->
                            put("trackTimes", JsonArray(ts.map { JsonPrimitive(it) }))
                            put("trackX", JsonArray(xs.map { JsonPrimitive(it) }))
                            put("trackY", JsonArray(ys.map { JsonPrimitive(it) }))
                        }
                    },
                ),
            )
        }
    }
}

val Project.callouts: List<Callout>
    get() = (extras["callouts"] as? JsonArray)?.map { Callout(it.jsonObject) }.orEmpty()

fun Project.withCallouts(list: List<Callout>): Project =
    copy(
        extras =
            if (list.isEmpty()) {
                JsonObject(extras - "callouts")
            } else {
                JsonObject(extras + ("callouts" to JsonArray(list.map { it.raw })))
            },
    )

fun Project.addCallouts(list: List<Callout>) = withCallouts(callouts + list)

fun Project.removeCallout(id: String) = withCallouts(callouts.filterNot { it.id == id })

fun Project.updateCallout(
    id: String,
    f: (Callout) -> Callout,
) = withCallouts(callouts.map { if (it.id == id) f(it) else it })
