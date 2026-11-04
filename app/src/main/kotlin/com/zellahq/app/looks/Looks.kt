package com.zellahq.app.looks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.SingleColorLut
import com.zellahq.core.looks.ColorBoard
import com.zellahq.core.looks.Lut
import com.zellahq.core.project.Clip
import com.zellahq.core.project.mask
import com.zellahq.core.project.newId
import com.zellahq.core.project.special
import com.zellahq.core.project.withEffect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** One look from the iPhone catalogue, as baked by tools/ios-conformance/bake-looks.swift. */
data class Look(
    val index: Int,
    val name: String,
    val category: String,
    val founder: Boolean,
    /** The iPhone's exact ClipEffects for this look: stored in the clip so the iPhone renders it too. */
    val effects: JsonObject,
    val vignette: Float,
    val blur: Float,
)

/**
 * The 95 looks (92 for everyone, 3 Founder grades) and how a clip's colour is turned into one LUT.
 * Colour is stored the iPhone's way, in the clip's `effects`: presetName, gradeIntensity, colorBoard
 * and lut. Android reads those and renders the baked LUT, so a project looks the same on both.
 */
object Looks {
    const val DEFAULT_INTENSITY = 0.75f // the iPhone's ColorPreset.defaultIntensity

    /** Picker order of the family chips, the iPhone's categoryOrder. */
    val CATEGORY_ORDER =
        listOf(
            "Studio", "Portrait", "Modern", "Bright & Airy", "Cinematic", "Moody", "Film", "Vintage", "Retro Digital",
            "Black & White", "Duotone", "Food", "Landscape", "Night & Neon", "Special", "Founder",
        )

    @Volatile private var catalog: List<Look>? = null
    private val luts = ConcurrentHashMap<Int, Lut>()
    private val cubes = ConcurrentHashMap<String, Lut>()

    fun all(context: Context): List<Look> =
        catalog ?: synchronized(this) {
            catalog ?: Json
                .parseToJsonElement(context.assets.open("looks/looks.json").use { it.readBytes().decodeToString() })
                .jsonObject["looks"]!!
                .jsonArray
                .map { e ->
                    val o = e.jsonObject
                    Look(
                        index = o["index"]!!.jsonPrimitive.content.toInt(),
                        name = o["name"]!!.jsonPrimitive.content,
                        category = o["category"]!!.jsonPrimitive.content,
                        founder = o["founder"]!!.jsonPrimitive.boolean,
                        effects = o["effects"]!!.jsonObject,
                        vignette = o["vignette"]?.jsonPrimitive?.floatOrNull ?: 0f,
                        blur = o["blur"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    )
                }.also { catalog = it }
        }

    fun byName(
        context: Context,
        name: String?,
    ) = all(context).firstOrNull { it.name == name }

    fun lut(
        context: Context,
        look: Look,
    ): Lut = luts.getOrPut(look.index) { Lut.fromBytes(context.assets.open("looks/%03d.lut".format(look.index)).use { it.readBytes() }) }

    private fun effects(clip: Clip) = clip.extras["effects"] as? JsonObject

    /**
     * A look stores its own effects on the clip and has them baked into its LUT and drawn in [effects].
     * Only what the person changed since then is added, or Film Noir would go black and white twice.
     */
    fun extraSpecial(
        context: Context,
        clip: Clip,
    ): com.zellahq.core.project.SpecialEffects {
        val now = clip.special
        val look = byName(context, presetName(clip)) ?: return now
        val base = clip.withEffect("special", look.effects["special"]).special
        return com.zellahq.core.project.SpecialEffects(
            now.blackAndWhite && !base.blackAndWhite,
            if (now.vignette != base.vignette) now.vignette else 0.0,
            if (now.grain != base.grain) now.grain else 0.0,
            if (now.glow != base.glow) now.glow else 0.0,
            if (now.pixelate != base.pixelate) now.pixelate else 0.0,
            if (now.chromatic != base.chromatic) now.chromatic else 0.0,
            if (now.blur != base.blur) now.blur else 0.0,
            now.letterbox && !base.letterbox,
        )
    }

    /** True when any clip wears a Founder grade: the export boundary asks this. */
    fun usesFounderGrade(
        context: Context,
        p: com.zellahq.core.project.Project,
    ) = p.clips.any { c -> byName(context, presetName(c))?.founder == true }

    fun presetName(clip: Clip) = effects(clip)?.get("presetName")?.jsonPrimitive?.contentOrNull

    fun intensity(clip: Clip) = effects(clip)?.get("gradeIntensity")?.jsonPrimitive?.floatOrNull ?: 1f

    fun board(o: JsonElement?): ColorBoard {
        val b = o as? JsonObject ?: return ColorBoard()

        fun w(
            wheel: String,
            k: String,
        ) = (b[wheel] as? JsonObject)?.get(k)?.jsonPrimitive?.floatOrNull ?: 0f
        return ColorBoard(
            w("lift", "red"), w("lift", "green"), w("lift", "blue"), w("lift", "luma"),
            w("gamma", "red"), w("gamma", "green"), w("gamma", "blue"), w("gamma", "luma"),
            w("gain", "red"), w("gain", "green"), w("gain", "blue"), w("gain", "luma"),
        )
    }

    fun boardJson(b: ColorBoard): JsonObject {
        fun wheel(
            r: Float,
            g: Float,
            bl: Float,
            l: Float,
        ) = JsonObject(
            mapOf("red" to JsonPrimitive(r), "green" to JsonPrimitive(g), "blue" to JsonPrimitive(bl), "luma" to JsonPrimitive(l)),
        )
        return JsonObject(
            mapOf(
                "lift" to wheel(b.liftR, b.liftG, b.liftB, b.liftL),
                "gamma" to wheel(b.gammaR, b.gammaG, b.gammaB, b.gammaL),
                "gain" to wheel(b.gainR, b.gainG, b.gainB, b.gainL),
            ),
        )
    }

    /** The clip's whole colour as one LUT, or null when it has none. */
    fun clipLut(
        context: Context,
        clip: Clip,
    ): Lut? {
        val fx = effects(clip) ?: return null
        val look = byName(context, presetName(clip))
        var lut: Lut? = look?.let { lut(context, it).atIntensity(intensity(clip)) }
        // Exposure and the rest, only as far as they differ from what the look baked in.
        val adjust = com.zellahq.core.project.colorAdjustOf(fx["colorAdjustments"])
        val bakedAdjust = com.zellahq.core.project.colorAdjustOf(look?.effects?.get("colorAdjustments"))
        if (adjust != bakedAdjust) {
            val delta =
                com.zellahq.core.project.ColorAdjust(
                    adjust.exposure - bakedAdjust.exposure,
                    adjust.brightness - bakedAdjust.brightness,
                    1 + adjust.contrast - bakedAdjust.contrast,
                    1 + adjust.saturation - bakedAdjust.saturation,
                    6500 + adjust.temperature - bakedAdjust.temperature,
                    adjust.tint - bakedAdjust.tint,
                    1 + adjust.highlights - bakedAdjust.highlights,
                    adjust.shadows - bakedAdjust.shadows,
                )
            lut =
                (lut ?: Lut.identity()).then { c ->
                    val o = c.copyOf()
                    delta.apply(o)
                    o
                }
        }
        // A colour board the person changed after picking the look: the look's own board is already baked in.
        val board = board(fx["colorBoard"])
        val baked = look?.let { board(it.effects["colorBoard"]) } ?: ColorBoard()
        if (board != baked) {
            val delta =
                ColorBoard(
                    board.liftR - baked.liftR, board.liftG - baked.liftG, board.liftB - baked.liftB, board.liftL - baked.liftL,
                    board.gammaR - baked.gammaR, board.gammaG - baked.gammaG, board.gammaB - baked.gammaB, board.gammaL - baked.gammaL,
                    board.gainR - baked.gainR, board.gainG - baked.gainG, board.gainB - baked.gainB, board.gainL - baked.gainL,
                )
            lut = (lut ?: Lut.identity()).then { delta.apply(it) }
        }
        // An imported .cube, after the look, at its own strength.
        (fx["lut"] as? JsonObject)?.let { ref ->
            val path = ref["fileURL"]?.jsonPrimitive?.contentOrNull ?: return@let
            val file = runCatching { File(URI(path)) }.getOrNull() ?: File(path)
            val cube = cubes.getOrPut(file.path) { file.takeIf { it.isFile }?.readText()?.let { Lut.fromCube(it) } ?: return@let }
            val t = ref["intensity"]?.jsonPrimitive?.floatOrNull ?: 1f
            lut = (lut ?: Lut.identity()).then(cube.atIntensity(t))
        }
        return lut
    }

    /** The clip's colour as Media3 effects: the LUT, then the look's blur and vignette. */
    @OptIn(UnstableApi::class)
    fun effects(
        context: Context,
        clip: Clip,
        width: Int,
        height: Int,
    ): List<Effect> {
        val out = ArrayList<Effect>()
        val mask = clip.mask
        clipLut(context, clip)?.let { out += if (mask != null) com.zellahq.app.depth.MaskedGradeEffect(it, mask) else media3(it) }
        // The look's blur and dark corners cover the whole picture, so they cannot sit inside a mask's area.
        if (mask != null) return out
        val look = byName(context, presetName(clip)) ?: return out
        val t = intensity(clip)
        if (look.blur > 0.01f) out += androidx.media3.effect.GaussianBlur(look.blur * t * 4)
        if (look.vignette > 0.01f) out += OverlayEffect(listOf(Vignette(look.vignette * t, width, height)))
        return out
    }

    /** Media3 wants lutCube[r][g][b] as packed RGB ints. Checked by pixel in LookRenderTest. */
    @OptIn(UnstableApi::class)
    fun media3(lut: Lut): SingleColorLut {
        val n = lut.size
        val cube =
            Array(n) { r ->
                Array(n) { g ->
                    IntArray(n) { b ->
                        val i = (r + n * g + n * n * b) * 3
                        Color.rgb((lut.data[i] * 255).toInt(), (lut.data[i + 1] * 255).toInt(), (lut.data[i + 2] * 255).toInt())
                    }
                }
            }
        return SingleColorLut.createFromCube(cube)
    }

    /** Applies [look] to [clip] the iPhone's way: its full effects, at [strength]. Keeps everything else. */
    fun applyLook(
        clip: Clip,
        look: Look?,
        strength: Float = DEFAULT_INTENSITY,
    ): Clip {
        val old = effects(clip) ?: JsonObject(emptyMap())
        val lookKeys = setOf("presetName", "gradeIntensity", "colorAdjustments", "colorBoard", "special", "normalize")
        val kept = old.filterKeys { it !in lookKeys }
        val next =
            if (look == null) {
                kept
            } else {
                kept + look.effects.filterKeys { it in lookKeys } + ("gradeIntensity" to JsonPrimitive(strength))
            }
        return clip.copy(extras = JsonObject(clip.extras + ("effects" to JsonObject(next))))
    }

    fun setIntensity(
        clip: Clip,
        t: Float,
    ): Clip {
        val old = effects(clip) ?: return clip
        return clip.copy(extras = JsonObject(clip.extras + ("effects" to JsonObject(old + ("gradeIntensity" to JsonPrimitive(t))))))
    }

    fun setBoard(
        clip: Clip,
        b: ColorBoard,
    ): Clip {
        val old = effects(clip) ?: JsonObject(emptyMap())
        return clip.copy(extras = JsonObject(clip.extras + ("effects" to JsonObject(old + ("colorBoard" to boardJson(b))))))
    }

    /** Copies a .cube file into Zella's storage and points the clip at it, the iPhone's LUTFilter shape. */
    fun importCube(
        context: Context,
        clip: Clip,
        name: String,
        text: String,
    ): Clip? {
        Lut.fromCube(text) ?: return null
        val dir = File(context.filesDir, "luts").apply { mkdirs() }
        val f = File(dir, "${newId()}.cube").apply { writeText(text) }
        val ref =
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(newId()),
                    "name" to JsonPrimitive(name),
                    "fileURL" to JsonPrimitive(f.toURI().toString()),
                    "intensity" to JsonPrimitive(1f),
                ),
            )
        val old = effects(clip) ?: JsonObject(emptyMap())
        return clip.copy(extras = JsonObject(clip.extras + ("effects" to JsonObject(old + ("lut" to ref)))))
    }

    fun removeCube(clip: Clip): Clip {
        val old = effects(clip) ?: return clip
        return clip.copy(extras = JsonObject(clip.extras + ("effects" to JsonObject(old - "lut"))))
    }

    @OptIn(UnstableApi::class)
    private class Vignette(
        amount: Float,
        private val w: Int,
        private val h: Int,
    ) : CanvasOverlay(false) {
        private val paint = Paint()

        init {
            setCanvasSize(w, h)
            val edge = (amount.coerceIn(0f, 1f) * 200).toInt()
            paint.shader =
                RadialGradient(
                    w / 2f, h / 2f, maxOf(w, h) * 0.75f,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(edge, 0, 0, 0)),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
        }

        override fun onDraw(
            canvas: Canvas,
            presentationTimeUs: Long,
        ) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }
}
