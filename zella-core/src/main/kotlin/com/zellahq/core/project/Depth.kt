@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.core.project

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

private fun num(v: Double) = JsonPrimitive(v)

val Clip.effects: JsonObject get() = extras["effects"] as? JsonObject ?: JsonObject(emptyMap())

fun Clip.withEffect(
    key: String,
    value: JsonElement?,
): Clip {
    val fx = effects
    return copy(extras = JsonObject(extras + ("effects" to JsonObject(if (value == null) fx - key else fx + (key to value)))))
}

fun Project.mapClipById(
    id: String,
    f: (Clip) -> Clip,
) = copy(clips = clips.map { if (it.id == id) f(it) else it })

/** The curve to the next keyframe, as Swift encodes the enum: `{"easeInOut":{}}`, `{"bezier":{"_0":..}}`. */
sealed interface Interp {
    fun eased(t: Double): Double

    fun json(): JsonObject

    data object Hold : Interp {
        override fun eased(t: Double) = 0.0

        override fun json() = JsonObject(mapOf("hold" to JsonObject(emptyMap())))
    }

    data object Linear : Interp {
        override fun eased(t: Double) = t.coerceIn(0.0, 1.0)

        override fun json() = JsonObject(mapOf("linear" to JsonObject(emptyMap())))
    }

    data object EaseIn : Interp {
        override fun eased(t: Double) = t.coerceIn(0.0, 1.0).let { it * it }

        override fun json() = JsonObject(mapOf("easeIn" to JsonObject(emptyMap())))
    }

    data object EaseOut : Interp {
        override fun eased(t: Double) = t.coerceIn(0.0, 1.0).let { 1 - (1 - it) * (1 - it) }

        override fun json() = JsonObject(mapOf("easeOut" to JsonObject(emptyMap())))
    }

    data object EaseInOut : Interp {
        override fun eased(t: Double) = t.coerceIn(0.0, 1.0).let { if (it < 0.5) 2 * it * it else 1 - (-2 * it + 2).pow(2) / 2 }

        override fun json() = JsonObject(mapOf("easeInOut" to JsonObject(emptyMap())))
    }

    data class Bezier(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Interp {
        override fun eased(t: Double): Double {
            val x = t.coerceIn(0.0, 1.0)

            fun cx(u: Double) = (1 - u).let { m -> 3 * m * m * u * x1 + 3 * m * u * u * x2 + u * u * u }

            fun cy(u: Double) = (1 - u).let { m -> 3 * m * m * u * y1 + 3 * m * u * u * y2 + u * u * u }
            var u = x
            repeat(8) {
                val xe = cx(u) - x
                if (abs(xe) < 1e-4) return cy(u)
                val d = 3 * (1 - u) * (1 - u) * x1 + 6 * (1 - u) * u * (x2 - x1) + 3 * u * u * (1 - x2)
                if (abs(d) < 1e-6) return@repeat
                u -= xe / d
            }
            var lo = 0.0
            var hi = 1.0
            var mt = x
            for (i in 0 until 20) {
                mt = (lo + hi) / 2
                val xe = cx(mt) - x
                if (abs(xe) < 1e-4) break
                if (xe > 0) hi = mt else lo = mt
            }
            return cy(mt)
        }

        override fun json() =
            JsonObject(mapOf("bezier" to JsonObject(mapOf("_0" to num(x1), "_1" to num(y1), "_2" to num(x2), "_3" to num(y2)))))
    }

    companion object {
        fun of(e: JsonElement?): Interp {
            val o = e as? JsonObject ?: return EaseInOut
            return when {
                "hold" in o -> Hold
                "linear" in o -> Linear
                "easeIn" in o -> EaseIn
                "easeOut" in o -> EaseOut
                "bezier" in o -> {
                    val b = o["bezier"] as? JsonObject ?: return EaseInOut
                    Bezier(b.d("_0") ?: 0.0, b.d("_1") ?: 0.0, b.d("_2") ?: 1.0, b.d("_3") ?: 1.0)
                }
                else -> EaseInOut
            }
        }
    }
}

data class Keyframe(val id: String, val time: Double, val value: Double, val interp: Interp = Interp.EaseInOut) {
    fun json() = JsonObject(mapOf("id" to JsonPrimitive(id), "time" to num(time), "value" to num(value), "interp" to interp.json()))
}

data class KeyframeTrack(val keys: List<Keyframe> = emptyList()) {
    fun value(
        t: Double,
        default: Double,
    ): Double {
        if (keys.isEmpty()) return default
        if (keys.size == 1) return keys[0].value
        if (t <= keys.first().time) return keys.first().value
        if (t >= keys.last().time) return keys.last().value
        for (i in 1 until keys.size) {
            if (keys[i].time >= t) {
                val a = keys[i - 1]
                val b = keys[i]
                val f = a.interp.eased((t - a.time) / max(0.0001, b.time - a.time))
                return a.value + (b.value - a.value) * f
            }
        }
        return keys.last().value
    }

    fun upsert(k: Keyframe): KeyframeTrack {
        val i = keys.indexOfFirst { abs(it.time - k.time) < 0.02 }
        val next = if (i >= 0) keys.mapIndexed { n, o -> if (n == i) k else o } else keys + k
        return KeyframeTrack(next.sortedBy { it.time })
    }

    fun json() = JsonObject(mapOf("keys" to JsonArray(keys.map { it.json() })))

    companion object {
        fun of(e: JsonElement?): KeyframeTrack {
            val keys = (e as? JsonObject)?.get("keys") as? JsonArray ?: return KeyframeTrack()
            return KeyframeTrack(
                keys.mapNotNull { k ->
                    val o = k as? JsonObject ?: return@mapNotNull null
                    Keyframe(
                        o.s("id") ?: newId(),
                        o.d("time") ?: return@mapNotNull null,
                        o.d("value") ?: return@mapNotNull null,
                        Interp.of(o["interp"]),
                    )
                }.sortedBy { it.time },
            )
        }
    }
}

/** posX, posY are fractions of the canvas, scale a multiplier, rotation degrees, opacity 0 to 1. Clip-local time. */
data class TransformKeyframes(
    val posX: KeyframeTrack = KeyframeTrack(),
    val posY: KeyframeTrack = KeyframeTrack(),
    val scale: KeyframeTrack = KeyframeTrack(),
    val rotation: KeyframeTrack = KeyframeTrack(),
    val opacity: KeyframeTrack = KeyframeTrack(),
) {
    val isEmpty get() = posX.keys.isEmpty() && posY.keys.isEmpty() && scale.keys.isEmpty() && rotation.keys.isEmpty() && opacity.keys.isEmpty()

    fun rescaled(k: Double): TransformKeyframes {
        fun r(t: KeyframeTrack) = KeyframeTrack(t.keys.map { it.copy(time = it.time * k) })
        return TransformKeyframes(r(posX), r(posY), r(scale), r(rotation), r(opacity))
    }

    fun track(p: KeyProperty) =
        when (p) {
            KeyProperty.PosX -> posX
            KeyProperty.PosY -> posY
            KeyProperty.Scale -> scale
            KeyProperty.Rotation -> rotation
            KeyProperty.Opacity -> opacity
        }

    fun with(
        p: KeyProperty,
        t: KeyframeTrack,
    ) = when (p) {
        KeyProperty.PosX -> copy(posX = t)
        KeyProperty.PosY -> copy(posY = t)
        KeyProperty.Scale -> copy(scale = t)
        KeyProperty.Rotation -> copy(rotation = t)
        KeyProperty.Opacity -> copy(opacity = t)
    }

    fun json() =
        JsonObject(
            mapOf(
                "posX" to posX.json(),
                "posY" to posY.json(),
                "scale" to scale.json(),
                "rotation" to rotation.json(),
                "opacity" to opacity.json(),
            ),
        )

    companion object {
        fun of(e: JsonElement?): TransformKeyframes {
            val o = e as? JsonObject ?: return TransformKeyframes()
            return TransformKeyframes(
                KeyframeTrack.of(o["posX"]),
                KeyframeTrack.of(o["posY"]),
                KeyframeTrack.of(o["scale"]),
                KeyframeTrack.of(o["rotation"]),
                KeyframeTrack.of(o["opacity"]),
            )
        }
    }
}

enum class KeyProperty(val raw: String, val default: Double, val range: ClosedFloatingPointRange<Double>) {
    PosX("Position X", 0.0, -0.5..0.5),
    PosY("Position Y", 0.0, -0.5..0.5),
    Scale("Scale", 1.0, 0.2..3.0),
    Rotation("Rotation", 0.0, -180.0..180.0),
    Opacity("Opacity", 1.0, 0.0..1.0),
}

val Clip.keyframes get() = TransformKeyframes.of(effects["keyframes"])

fun Clip.withKeyframes(k: TransformKeyframes) = withEffect("keyframes", if (k.isEmpty) null else k.json())

fun Clip.addKeyframe(
    p: KeyProperty,
    local: Double,
    value: Double,
    interp: Interp = Interp.EaseInOut,
): Clip {
    val all = keyframes
    return withKeyframes(all.with(p, all.track(p).upsert(Keyframe(newId(), local, value.coerceIn(p.range), interp))))
}

/** The clip's steady position, on top of which keyframes move it. */
data class ClipTransform(val x: Double = 0.0, val y: Double = 0.0, val rotation: Double = 0.0, val scale: Double = 1.0) {
    val isIdentity get() = x == 0.0 && y == 0.0 && rotation == 0.0 && scale == 1.0
}

val Clip.transform: ClipTransform
    get() {
        val t = effects["transform"] as? JsonObject ?: return ClipTransform()
        return ClipTransform(
            t.d("positionX") ?: 0.0,
            t.d("positionY") ?: 0.0,
            t.d("rotation") ?: 0.0,
            (t.d("scaleAll") ?: 1.0) * (t.d("scaleX") ?: 1.0),
        )
    }

fun Clip.withTransform(t: ClipTransform): Clip {
    val old = effects["transform"] as? JsonObject ?: JsonObject(emptyMap())
    return withEffect(
        "transform",
        JsonObject(
            old + mapOf("positionX" to num(t.x), "positionY" to num(t.y), "rotation" to num(t.rotation), "scaleAll" to num(t.scale)),
        ),
    )
}

val Clip.opacity: Double get() = ((effects["compositing"] as? JsonObject)?.d("opacity") ?: 1.0).coerceIn(0.0, 1.0)

fun Clip.withOpacity(v: Double) = withEffect("compositing", JsonObject(mapOf("opacity" to num(v.coerceIn(0.0, 1.0)))))

/** The grade inside a soft shape, the original outside it, or the other way round. */
data class MaskRegion(
    val shape: String = "ellipse",
    val x: Double = 0.5,
    val y: Double = 0.5,
    val w: Double = 0.4,
    val h: Double = 0.4,
    val feather: Double = 0.15,
    val invert: Boolean = false,
) {
    fun json() =
        JsonObject(
            mapOf(
                "shape" to JsonPrimitive(shape),
                "x" to num(x),
                "y" to num(y),
                "w" to num(w),
                "h" to num(h),
                "feather" to num(feather),
                "invert" to JsonPrimitive(invert),
            ),
        )
}

val Clip.mask: MaskRegion?
    get() {
        val m = effects["mask"] as? JsonObject ?: return null
        return MaskRegion(
            m.s("shape") ?: "ellipse",
            m.d("x") ?: 0.5,
            m.d("y") ?: 0.5,
            m.d("w") ?: 0.4,
            m.d("h") ?: 0.4,
            m.d("feather") ?: 0.15,
            m.b("invert") ?: false,
        )
    }

fun Clip.withMask(m: MaskRegion?) = withEffect("mask", m?.json())

/** The per-clip effects the iPhone keeps in `effects.special`. */
data class SpecialEffects(
    val blackAndWhite: Boolean = false,
    val vignette: Double = 0.0,
    val grain: Double = 0.0,
    val glow: Double = 0.0,
    val pixelate: Double = 0.0,
    val chromatic: Double = 0.0,
    val blur: Double = 0.0,
    val letterbox: Boolean = false,
) {
    val isPlain get() = this == SpecialEffects()
}

val Clip.special: SpecialEffects
    get() {
        val o = effects["special"] as? JsonObject ?: return SpecialEffects()
        return SpecialEffects(
            o.b("blackAndWhite") ?: false,
            o.d("vignette") ?: 0.0,
            o.d("grain") ?: 0.0,
            o.d("glow") ?: 0.0,
            o.d("pixelate") ?: 0.0,
            o.d("chromaticAberration") ?: 0.0,
            o.d("blurRadius") ?: 0.0,
            o.b("letterbox") ?: false,
        )
    }

fun Clip.withSpecial(s: SpecialEffects): Clip {
    val old = effects["special"] as? JsonObject ?: JsonObject(emptyMap())
    return withEffect(
        "special",
        JsonObject(
            old +
                mapOf(
                    "blackAndWhite" to JsonPrimitive(s.blackAndWhite),
                    "vignette" to num(s.vignette),
                    "grain" to num(s.grain),
                    "glow" to num(s.glow),
                    "pixelate" to num(s.pixelate),
                    "chromaticAberration" to num(s.chromatic),
                    "blurRadius" to num(s.blur),
                    "letterbox" to JsonPrimitive(s.letterbox),
                ),
        ),
    )
}

/** Exposure, contrast and the rest. Applied on top of a look as a colour change. */
data class ColorAdjust(
    val exposure: Double = 0.0,
    val brightness: Double = 0.0,
    val contrast: Double = 1.0,
    val saturation: Double = 1.0,
    val temperature: Double = 6500.0,
    val tint: Double = 0.0,
    val highlights: Double = 1.0,
    val shadows: Double = 0.0,
) {
    val isPlain get() = this == ColorAdjust()

    /** One RGB colour, each 0 to 1, in place. */
    fun apply(c: FloatArray) {
        val gain = 2.0.pow(exposure).toFloat()
        val warm = ((temperature - 6500.0) / 4500.0).toFloat()
        val tintV = (tint / 100.0).toFloat()
        var r = c[0] * gain + brightness.toFloat()
        var g = c[1] * gain + brightness.toFloat()
        var b = c[2] * gain + brightness.toFloat()
        r *= 1 + 0.25f * warm
        b *= 1 - 0.25f * warm
        g *= 1 - 0.20f * tintV
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val sh = (shadows * (1 - luma.toDouble().coerceIn(0.0, 1.0)).pow(2) * 0.5).toFloat()
        val hi = ((highlights - 1) * luma.toDouble().coerceIn(0.0, 1.0).pow(2) * 0.5).toFloat()
        r += sh + hi
        g += sh + hi
        b += sh + hi
        val l2 = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val sat = saturation.toFloat()
        r = l2 + (r - l2) * sat
        g = l2 + (g - l2) * sat
        b = l2 + (b - l2) * sat
        val con = contrast.toFloat()
        c[0] = ((r - 0.5f) * con + 0.5f).coerceIn(0f, 1f)
        c[1] = ((g - 0.5f) * con + 0.5f).coerceIn(0f, 1f)
        c[2] = ((b - 0.5f) * con + 0.5f).coerceIn(0f, 1f)
    }
}

/** What a look stored, so only what the person changed since is applied on top of it. */
fun colorAdjustOf(e: JsonElement?): ColorAdjust {
    val o = e as? JsonObject ?: return ColorAdjust()
    return ColorAdjust(
        o.d("exposure") ?: 0.0,
        o.d("brightness") ?: 0.0,
        o.d("contrast") ?: 1.0,
        o.d("saturation") ?: 1.0,
        o.d("temperature") ?: 6500.0,
        o.d("tint") ?: 0.0,
        o.d("highlights") ?: 1.0,
        o.d("shadows") ?: 0.0,
    )
}

fun Clip.withColorAdjust(a: ColorAdjust): Clip {
    val old = effects["colorAdjustments"] as? JsonObject ?: JsonObject(emptyMap())
    return withEffect(
        "colorAdjustments",
        JsonObject(
            old +
                mapOf(
                    "exposure" to num(a.exposure),
                    "brightness" to num(a.brightness),
                    "contrast" to num(a.contrast),
                    "saturation" to num(a.saturation),
                    "temperature" to num(a.temperature),
                    "tint" to num(a.tint),
                    "highlights" to num(a.highlights),
                    "shadows" to num(a.shadows),
                ),
        ),
    )
}

/** A short still of one frame, spliced in at [at]: freeze frame. */
fun Project.insertFreeze(
    file: String,
    at: Double,
    seconds: Double,
) = insertClip(Clip(newId(), MediaRef.Absolute(file), seconds, trimEnd = seconds, volume = 0.0), at)

// Transcript editing: delete words, and the video they were spoken in goes with them.

data class SpokenAt(val cue: Int, val word: Int, val text: String, val start: Double, val end: Double)

fun Project.spokenWords(): List<SpokenAt> =
    captions?.cues.orEmpty().flatMapIndexed { c, cue -> cue.words.mapIndexed { w, x -> SpokenAt(c, w, x.text, x.start, x.end) } }

/** The ranges to cut for the picked words: a run of neighbours is one cut, including the pause inside it. */
fun cutRangesFor(
    words: List<SpokenAt>,
    picked: Set<Int>,
): List<ClosedFloatingPointRange<Double>> {
    val out = ArrayList<ClosedFloatingPointRange<Double>>()
    var i = 0
    while (i < words.size) {
        if (i !in picked) {
            i++
            continue
        }
        var j = i
        while (j + 1 < words.size && (j + 1) in picked) j++
        out += words[i].start..words[j].end
        i = j + 1
    }
    return out
}

/** Removes the picked words, and everything after moves up by exactly what was cut. */
fun Project.cutWords(picked: Set<Int>): Project = removeRanges(cutRangesFor(spokenWords(), picked))

// Saved looks: caption style, grade, transition, music. No media and no timing, so one applies to any project.

data class LookTemplate(
    val id: String,
    val name: String,
    val createdAt: String,
    val captionPreset: String?,
    val captionFontName: String?,
    val gradeName: String?,
    val gradeIntensity: Double,
    val transitionType: String?,
    val transitionDuration: Double,
    val musicMoodId: String?,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val summary get() =
        listOfNotNull(
            captionPreset,
            gradeName,
            transitionType,
            musicMoodId,
        ).ifEmpty { listOf("Empty look") }.joinToString(", ")

    fun json() =
        JsonObject(
            raw +
                buildMap {
                    put("id", JsonPrimitive(id))
                    put("name", JsonPrimitive(name))
                    put("createdAt", JsonPrimitive(createdAt))
                    captionPreset?.let { put("captionPreset", JsonPrimitive(it)) }
                    captionFontName?.let { put("captionFontName", JsonPrimitive(it)) }
                    gradeName?.let { put("gradeName", JsonPrimitive(it)) }
                    put("gradeIntensity", num(gradeIntensity))
                    transitionType?.let { put("transitionType", JsonPrimitive(it)) }
                    put("transitionDuration", num(transitionDuration))
                    musicMoodId?.let { put("musicMoodID", JsonPrimitive(it)) }
                },
        )

    companion object {
        fun of(e: JsonElement): LookTemplate? {
            val o = e as? JsonObject ?: return null
            return LookTemplate(
                o.s("id") ?: return null,
                o.s("name") ?: return null,
                o.s("createdAt") ?: isoNow(),
                o.s("captionPreset"),
                o.s("captionFontName"),
                o.s("gradeName"),
                o.d("gradeIntensity") ?: 1.0,
                o.s("transitionType"),
                o.d("transitionDuration") ?: 0.5,
                o.s("musicMoodID"),
                o,
            )
        }

        /** The whole video's recipe. The commonest transition wins, ties to the alphabetically first. */
        fun capture(
            p: Project,
            name: String,
            musicMoodId: String?,
        ): LookTemplate {
            val graded = p.clips.firstOrNull { (it.effects["presetName"] as? JsonPrimitive)?.contentOrNull != null }
            val counts = p.transitions.orEmpty().groupingBy { it.type }.eachCount()
            val common = counts.entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.key
            return LookTemplate(
                newId(),
                name,
                isoNow(),
                p.captions?.extras?.get("preset")?.let { (it as? JsonPrimitive)?.contentOrNull },
                p.captions?.extras?.get("fontName")?.let { (it as? JsonPrimitive)?.contentOrNull },
                graded?.effects?.s("presetName"),
                graded?.effects?.d("gradeIntensity") ?: 1.0,
                common,
                p.transitions.orEmpty().firstOrNull { it.type == common }?.duration ?: 0.5,
                musicMoodId,
            )
        }
    }
}

// Picture-in-picture, as the iPhone stores it in `overlayClips`.

class Overlay(val raw: JsonObject) {
    private fun d(k: String) = raw.d(k)

    val id get() = raw.s("id").orEmpty()
    val source: MediaRef? get() = raw["source"]?.let { ProjectJson.ref(it) }
    val sourceDuration get() = d("sourceDuration") ?: 0.0
    val sourceOffset get() = d("sourceOffset") ?: 0.0
    val start get() = d("start") ?: 0.0
    val duration get() = d("duration") ?: 0.0
    val end get() = start + duration
    val x get() = (raw["position"] as? JsonObject)?.d("x") ?: 0.72
    val y get() = (raw["position"] as? JsonObject)?.d("y") ?: 0.72
    val scale get() = d("scale") ?: 0.4
    val scaleY get() = d("scaleY")
    val style get() = raw.s("style") ?: "rectangle"
    val sourceAspect get() = d("sourceAspect") ?: (16.0 / 9.0)
    val cornerRadius get() = d("cornerRadius") ?: 0.16
    val mirrored get() = raw.b("mirrored") ?: false
    val z get() = (raw["z"] as? JsonPrimitive)?.intOrNull ?: 0
    val volume get() = d("volume") ?: 1.0
    val audioMuted get() = raw.b("audioMuted") ?: false

    fun with(vararg f: Pair<String, JsonElement?>): Overlay {
        var o: Map<String, JsonElement> = raw
        f.forEach { (k, v) -> o = if (v == null) o - k else o + (k to v) }
        return Overlay(JsonObject(o))
    }

    companion object {
        fun create(
            source: MediaRef,
            sourceDuration: Double,
            start: Double,
            aspect: Double,
        ) = Overlay(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(newId()),
                    "source" to ProjectJson.refJson(source),
                    "sourceDuration" to num(sourceDuration),
                    "sourceOffset" to num(0.0),
                    "start" to num(start),
                    "duration" to num(sourceDuration),
                    "position" to position(0.72, 0.72),
                    "scale" to num(0.4),
                    "style" to JsonPrimitive("rectangle"),
                    "sourceAspect" to num(aspect),
                    "cornerRadius" to num(0.16),
                    "mirrored" to JsonPrimitive(false),
                    "z" to JsonPrimitive(0),
                    "volume" to num(1.0),
                    "audioMuted" to JsonPrimitive(false),
                ),
            ),
        )
    }
}

val Project.overlays: List<Overlay> get() = (extras["overlayClips"] as? JsonArray)?.map { Overlay(it.jsonObject) }.orEmpty()

fun Project.withOverlays(list: List<Overlay>) =
    copy(
        extras =
            if (list.isEmpty()) {
                JsonObject(extras - "overlayClips")
            } else {
                JsonObject(
                    extras + (
                        "overlayClips" to
                            JsonArray(
                                list.map {
                                    it.raw
                                },
                            )
                    ),
                )
            },
    )

fun Project.updateOverlay(
    id: String,
    f: (Overlay) -> Overlay,
) = withOverlays(overlays.map { if (it.id == id) f(it) else it })

// Beat sync.

/** The iPhone's BeatDetector, on mono audio at 22,050 Hz. */
object Beats {
    const val RATE = 22_050
    private const val WINDOW = 1024
    private const val MAX_PUNCH = 0.12

    fun peak(strength: Double) = 1.0 + strength.coerceIn(0.0, 1.0) * MAX_PUNCH

    /** Beat times in seconds for the three bands' sensitivities, 0 to 1 (0 is off). */
    fun detect(
        pcm: FloatArray,
        kick: Double = 0.5,
        snare: Double = 0.5,
        hats: Double = 0.5,
        minSpacing: Double = 0.28,
    ): List<Double> {
        val windows = pcm.size / WINDOW
        if (windows <= 4) return emptyList()
        val full = FloatArray(windows)
        val low = FloatArray(windows)
        val mid = FloatArray(windows)
        val high = FloatArray(windows)
        var lpLow = 0.0
        var lpHigh = 0.0
        for (w in 0 until windows) {
            var sF = 0.0
            var sL = 0.0
            var sM = 0.0
            var sH = 0.0
            for (k in w * WINDOW until (w + 1) * WINDOW) {
                val x = pcm[k].toDouble()
                lpLow += 0.043 * (x - lpLow)
                lpHigh += 0.5 * (x - lpHigh)
                val m = lpHigh - lpLow
                val h = x - lpHigh
                sF += x * x
                sL += lpLow * lpLow
                sM += m * m
                sH += h * h
            }
            full[w] = sqrt(sF / WINDOW).toFloat()
            low[w] = sqrt(sL / WINDOW).toFloat()
            mid[w] = sqrt(sM / WINDOW).toFloat()
            high[w] = sqrt(sH / WINDOW).toFloat()
        }

        fun flux(e: FloatArray) = FloatArray(e.size) { i -> if (i == 0) 0f else max(0f, e[i] - e[i - 1]) }
        val fFull = flux(full).sorted()
        val scale = fFull[min(fFull.size - 1, (0.98 * fFull.size).toInt())].toDouble()
        if (scale <= 0.0001) return emptyList()
        val hop = WINDOW.toDouble() / RATE
        val all = ArrayList<Double>()
        listOf(low to kick, mid to snare, high to hats).filter { it.second > 0.001 }.forEach { (env, sens) ->
            val f = flux(env)
            val thr = (scale * (1.0 - 0.9 * sens.coerceIn(0.0, 1.0))).toFloat()
            var last = -minSpacing
            for (i in f.indices) {
                val isPeak = (i == 0 || f[i] >= f[i - 1]) && (i == f.size - 1 || f[i] > f[i + 1])
                if (isPeak && f[i] > thr) {
                    val t = i * hop
                    if (t - last >= minSpacing) {
                        all += t
                        last = t
                    }
                }
            }
        }
        val merged = ArrayList<Double>()
        var last = -minSpacing
        all.sorted().forEach { t ->
            if (t - last >= minSpacing) {
                merged += t
                last = t
            }
        }
        return merged
    }

    /** A quick rise to the peak on each beat and a short fall back, with nothing colliding. */
    fun scaleTrack(
        beats: List<Double>,
        peak: Double,
        clipDuration: Double,
        attack: Double = 0.05,
    ): KeyframeTrack {
        var track = KeyframeTrack()
        if (peak <= 1.0001 || beats.isEmpty()) return track
        val sorted = beats.sorted()
        sorted.forEachIndexed { i, t ->
            val next = if (i + 1 < sorted.size) sorted[i + 1] else clipDuration + 1
            val gap = max(0.001, next - t)
            val release = min(0.20, max(0.06, gap * 0.6))
            track = track.upsert(Keyframe(newId(), max(0.0, t - attack), 1.0, Interp.EaseIn))
            track = track.upsert(Keyframe(newId(), t, peak, Interp.EaseOut))
            track = track.upsert(Keyframe(newId(), min(clipDuration, t + release), 1.0, Interp.Linear))
        }
        return track
    }
}
