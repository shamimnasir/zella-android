package com.zellahq.core.project

import com.zellahq.core.motion.TextAnimationSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** A colour as the iPhone stores it: 0 to 1 per channel. */
data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double = 1.0) {
    fun toArgb(): Int {
        fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    fun json() = JsonObject(mapOf("r" to JsonPrimitive(r), "g" to JsonPrimitive(g), "b" to JsonPrimitive(b), "a" to JsonPrimitive(a)))

    companion object {
        val White = Rgba(1.0, 1.0, 1.0)
        val Black = Rgba(0.0, 0.0, 0.0)
        val HighlightGold = Rgba(0.949, 0.757, 0.306)

        fun of(e: JsonElement?): Rgba? {
            val o = e as? JsonObject ?: return null

            fun v(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            return Rgba(v("r") ?: return null, v("g") ?: return null, v("b") ?: return null, v("a") ?: 1.0)
        }
    }
}

/** One word of a title styled apart: the longest, or the last. */
data class TitleAccent(val pick: String, val scale: Double?, val color: Rgba?, val fontName: String?) {
    fun wordIndex(words: List<String>): Int? {
        if (words.isEmpty()) return null
        if (pick == "last") return words.size - 1
        var best = 0
        words.forEachIndexed { i, w -> if (w.length > words[best].length) best = i }
        return best
    }

    fun json() =
        JsonObject(
            buildMap {
                put("pick", JsonPrimitive(pick))
                scale?.let { put("scale", JsonPrimitive(it)) }
                color?.let { put("color", it.json()) }
                fontName?.let { put("fontName", JsonPrimitive(it)) }
            },
        )
}

/**
 * A title's look, read from the fields the iPhone's TitleOverlay stores. The model keeps them in
 * [Title.extras] so unknown ones survive a save; this is the typed view the renderer and editor use.
 */
class TitleLook(val title: Title) {
    private val x = title.extras

    private fun num(k: String) = (x[k] as? JsonPrimitive)?.doubleOrNull

    private fun str(k: String) = (x[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    val subtitle get() = str("subtitle")
    val positionX get() = ((x["position"] as? JsonObject)?.get("x") as? JsonPrimitive)?.doubleOrNull ?: 0.5
    val positionY get() = ((x["position"] as? JsonObject)?.get("y") as? JsonPrimitive)?.doubleOrNull ?: 0.8
    val fontSize get() = num("fontSize") ?: 90.0
    val fill get() = Rgba.of(x["fill"]) ?: Rgba.White
    val gradient get() = Rgba.of(x["gradient"])
    val stroke get() = Rgba.of(x["stroke"])
    val strokeWidth get() = num("strokeWidth") ?: 2.5
    val box get() = Rgba.of(x["box"])
    val entrance get() = str("entrance") ?: "fade"
    val fontName get() = str("fontName")
    val fontWeight get() = str("fontWeight")
    val shadow get() = num("shadow")
    val behindSubject get() = (x["behindSubject"] as? JsonPrimitive)?.booleanOrNull ?: false
    val card get() = (x["card"] as? JsonPrimitive)?.booleanOrNull ?: false
    val cardCorner get() = num("cardCorner")
    val effectIntensity get() = num("effectIntensity") ?: 1.0
    val neonColor get() = Rgba.of(x["neonColor"])
    val badge get() = str("badge")
    val highlightLine get() = (x["highlightLine"] as? JsonPrimitive)?.intOrNull
    val plateKind get() = str("plateKind")
    val rotationDegrees get() = num("rotationDegrees")
    val extrudeDepth get() = num("extrudeDepth")
    val extrudeColor get() = Rgba.of(x["extrudeColor"])
    val uppercase get() = (x["uppercase"] as? JsonPrimitive)?.booleanOrNull ?: false
    val accent: TitleAccent?
        get() {
            val o = x["accent"] as? JsonObject ?: return null
            return TitleAccent(
                (o["pick"] as? JsonPrimitive)?.contentOrNull ?: "longest",
                (o["scale"] as? JsonPrimitive)?.doubleOrNull,
                Rgba.of(o["color"]),
                (o["fontName"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    val animation get() = TextAnimationSpec.fromJson(x["textAnimation"])
    val isHighlight get() = entrance == "Marker Highlight" || entrance == "Underline Draw" || entrance == "Box Trace"
}

/** A new title with every field the iPhone requires when it reads one back. */
fun newTitle(
    text: String,
    start: Double,
    duration: Double = 3.0,
    fields: Map<String, JsonElement> = emptyMap(),
) = Title(
    newId(),
    text,
    start,
    duration,
    JsonObject(
        mapOf(
            "position" to position(0.5, 0.5),
            "fontSize" to JsonPrimitive(90.0),
            "fill" to Rgba.White.json(),
            "entrance" to JsonPrimitive("fade"),
            "behindSubject" to JsonPrimitive(false),
        ) + fields,
    ),
)

fun position(
    x: Double,
    y: Double,
) = JsonObject(mapOf("x" to JsonPrimitive(x), "y" to JsonPrimitive(y)))

/** Sets or clears one look field. */
fun Title.withField(
    key: String,
    value: JsonElement?,
) = copy(extras = JsonObject(if (value == null) extras - key else extras + (key to value)))

/** Where a new title lands: at the playhead, keeping at least a second to play (the iPhone's rule). */
fun latestOverlayStart(
    projectDuration: Double,
    overlayDuration: Double,
) = maxOf(0.0, projectDuration - minOf(1.0, overlayDuration))

/** The 25 transitions, named exactly as the iPhone and Mac store them. */
object TransitionTypes {
    val all =
        listOf(
            "Cross Dissolve", "Fade to Black", "Dip to White", "Zoom In", "Zoom Out", "Whip Pan", "Spin", "Glitch Cut",
            "Smooth Zoom", "Cube Flip", "Slide Push", "Blur Transition", "Velocity Cut", "Shake Cut", "Luma Fade",
            "Light Leak", "Lens Flare", "Warp", "Flashback", "Ink Bleed", "Film Burn", "Whip Up", "Zoom Punch",
            "Circle Wipe", "Pixelate",
        )

    /** Picker groups in the iPhone's order. */
    val categories =
        listOf(
            "Classic" to listOf("Cross Dissolve", "Fade to Black", "Dip to White", "Luma Fade", "Circle Wipe"),
            "Zoom" to listOf("Zoom In", "Zoom Out", "Smooth Zoom", "Zoom Punch"),
            "Motion" to listOf("Whip Pan", "Whip Up", "Spin", "Cube Flip", "Slide Push", "Warp", "Ink Bleed"),
            "Energy" to listOf("Glitch Cut", "Velocity Cut", "Shake Cut", "Pixelate"),
            "Cinematic" to listOf("Blur Transition", "Light Leak", "Lens Flare", "Flashback", "Film Burn"),
        )
}
