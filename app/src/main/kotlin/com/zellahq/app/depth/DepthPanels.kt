@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app.depth

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.zellahq.app.R
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Storage
import com.zellahq.app.editor.Tool
import com.zellahq.app.editor.mmss
import com.zellahq.core.PremiumGate
import com.zellahq.core.project.Clip
import com.zellahq.core.project.ClipTransform
import com.zellahq.core.project.ColorAdjust
import com.zellahq.core.project.Interp
import com.zellahq.core.project.KeyProperty
import com.zellahq.core.project.MaskRegion
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Overlay
import com.zellahq.core.project.Project
import com.zellahq.core.project.addKeyframe
import com.zellahq.core.project.colorAdjustOf
import com.zellahq.core.project.effects
import com.zellahq.core.project.keyframes
import com.zellahq.core.project.mapClipById
import com.zellahq.core.project.mask
import com.zellahq.core.project.newId
import com.zellahq.core.project.opacity
import com.zellahq.core.project.overlays
import com.zellahq.core.project.position
import com.zellahq.core.project.special
import com.zellahq.core.project.startTime
import com.zellahq.core.project.transform
import com.zellahq.core.project.updateOverlay
import com.zellahq.core.project.withColorAdjust
import com.zellahq.core.project.withKeyframes
import com.zellahq.core.project.withMask
import com.zellahq.core.project.withOpacity
import com.zellahq.core.project.withOverlays
import com.zellahq.core.project.withSpecial
import com.zellahq.core.project.withTransform
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

@Composable
private fun Labelled(
    label: Int,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    onChange: (Double) -> Unit,
) {
    val name = stringResource(label)
    Text(name, color = MaterialTheme.colorScheme.onBackground)
    Slider(
        modifier =
            Modifier.semantics {
                contentDescription = name
            },
        value = value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
        onValueChange = {
            onChange(it.toDouble())
        },
        valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
    )
}

@Composable
private fun Chips(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) { content() }
}

/** A second video over the main one, placed with sliders. */
@Composable
fun PipPanel(
    state: EditorState,
    project: Project,
    position: Double,
) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onBackground
    var picked by remember { mutableStateOf<String?>(null) }
    val list = project.overlays
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            Thread({
                val f = File(Storage.imports(context), "overlay-${newId()}.mp4")
                context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                val m = MediaMetadataRetriever()
                val (dur, aspect) =
                    try {
                        m.setDataSource(f.absolutePath)
                        val d = (m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000
                        val w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull() ?: 16.0
                        val h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull() ?: 9.0
                        val rot = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        d to if (rot % 180 == 0) w / h else h / w
                    } finally {
                        m.release()
                    }
                if (dur > 0.1) {
                    val o = Overlay.create(MediaRef.Absolute(f.toURI().toString()), dur, position, aspect)
                    state.onMain {
                        state.edit("add picture in picture") { it.withOverlays(it.overlays + o) }
                        picked = o.id
                    }
                }
            }, "add-overlay").start()
        }

    fun update(
        id: String,
        f: (Overlay) -> Overlay,
    ) = state.edit("picture in picture") { it.updateOverlay(id, f) }

    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.pp_detail), color = color)
        Tool(stringResource(R.string.pp_add), ZellaColors.CardViolet) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        if (list.isNotEmpty()) {
            Text(stringResource(R.string.pp_on), color = color)
            Chips {
                list.forEachIndexed { i, o ->
                    Tool(stringResource(R.string.pp_item, i + 1, mmss(o.start)), if (o.id == picked) ZellaColors.CardSun else null) {
                        picked = if (picked == o.id) null else o.id
                        if (picked != null) state.seek(o.start)
                    }
                }
            }
        }
        val o = list.firstOrNull { it.id == picked } ?: return@Column
        Chips {
            listOf("rectangle" to R.string.pp_rect, "roundedRect" to R.string.pp_round, "circle" to R.string.pp_circle).forEach {
                    (raw, label) ->
                Tool(
                    stringResource(label),
                    if (o.style == raw) ZellaColors.CardSun else null,
                ) { update(o.id) { it.with("style" to JsonPrimitive(raw)) } }
            }
            Tool(stringResource(R.string.pp_mirror), if (o.mirrored) ZellaColors.CardSun else null) {
                update(o.id) { it.with("mirrored" to JsonPrimitive(!o.mirrored)) }
            }
            Tool(stringResource(if (o.audioMuted) R.string.pp_unmute else R.string.pp_mute)) {
                update(o.id) { it.with("audioMuted" to JsonPrimitive(!o.audioMuted)) }
            }
        }
        Labelled(R.string.pp_across, o.x, 0.0..1.0) { v -> update(o.id) { it.with("position" to position(v, it.y)) } }
        Labelled(R.string.pp_down, o.y, 0.0..1.0) { v -> update(o.id) { it.with("position" to position(it.x, v)) } }
        Labelled(R.string.pp_wide, o.scale, 0.1..1.0) { v -> update(o.id) { it.with("scale" to JsonPrimitive(v)) } }
        if (o.style != "circle") {
            val free = o.scaleY != null
            Tool(stringResource(if (free) R.string.pp_keep_shape else R.string.pp_free_size)) {
                update(o.id) { it.with("scaleY" to if (free) null else JsonPrimitive(0.3)) }
            }
            o.scaleY?.let { h -> Labelled(R.string.pp_tall, h, 0.1..1.0) { v -> update(o.id) { it.with("scaleY" to JsonPrimitive(v)) } } }
        }
        Tool(stringResource(R.string.pp_remove), ZellaColors.CardCoral) {
            state.edit("remove picture in picture") { p -> p.withOverlays(p.overlays.filterNot { it.id == o.id }) }
            picked = null
        }
    }
}

/** Colour sliders and effects for one clip. */
@Composable
fun EffectsPanel(
    state: EditorState,
    clip: Clip,
) {
    val color = MaterialTheme.colorScheme.onBackground
    val a = colorAdjustOf(clip.effects["colorAdjustments"])
    val s = clip.special

    fun adjust(n: ColorAdjust) = state.edit("colour") { p -> p.mapClipById(clip.id) { it.withColorAdjust(n) } }

    fun fx(f: (com.zellahq.core.project.SpecialEffects) -> com.zellahq.core.project.SpecialEffects) =
        state.edit(
            "effect",
        ) { p ->
            p.mapClipById(clip.id) {
                it.withSpecial(f(it.special))
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.fx_adjust), color = color)
        Labelled(R.string.fx_exposure, a.exposure, -2.0..2.0) { adjust(a.copy(exposure = it)) }
        Labelled(R.string.fx_contrast, a.contrast, 0.0..2.0) { adjust(a.copy(contrast = it)) }
        Labelled(R.string.fx_saturation, a.saturation, 0.0..2.0) { adjust(a.copy(saturation = it)) }
        Labelled(R.string.fx_warmth, a.temperature, 2000.0..10000.0) { adjust(a.copy(temperature = it)) }
        Labelled(R.string.fx_tint, a.tint, -100.0..100.0) { adjust(a.copy(tint = it)) }
        Labelled(R.string.fx_highlights, a.highlights, 0.0..2.0) { adjust(a.copy(highlights = it)) }
        Labelled(R.string.fx_shadows, a.shadows, -1.0..1.0) { adjust(a.copy(shadows = it)) }
        Tool(stringResource(R.string.fx_reset)) { adjust(ColorAdjust()) }
        Text(stringResource(R.string.fx_title), color = color)
        Chips {
            Tool(stringResource(R.string.fx_mono), if (s.blackAndWhite) ZellaColors.CardSun else null) {
                fx { it.copy(blackAndWhite = !it.blackAndWhite) }
            }
            Tool(
                stringResource(R.string.fx_bars),
                if (s.letterbox) ZellaColors.CardSun else null,
            ) { fx { it.copy(letterbox = !it.letterbox) } }
        }
        Labelled(R.string.fx_vignette, s.vignette, 0.0..1.0) { v -> fx { it.copy(vignette = v) } }
        Labelled(R.string.fx_grain, s.grain, 0.0..1.0) { v -> fx { it.copy(grain = v) } }
        Labelled(R.string.fx_glow, s.glow, 0.0..1.0) { v -> fx { it.copy(glow = v) } }
        Labelled(R.string.fx_pixels, s.pixelate, 0.0..1.0) { v -> fx { it.copy(pixelate = v) } }
        Labelled(R.string.fx_fringe, s.chromatic, 0.0..1.0) { v -> fx { it.copy(chromatic = v) } }
        Labelled(R.string.fx_blur, s.blur, 0.0..40.0) { v -> fx { it.copy(blur = v) } }
    }
}

private val rampNames = listOf("None", "Slow-Fast-Slow", "Fast-Slow-Fast", "Ramp Up", "Ramp Down")

/** Where the clip sits, how it changes over time, and where its look applies. */
@Composable
fun MotionPanel(
    state: EditorState,
    project: Project,
    clip: Clip,
    position: Double,
) {
    val color = MaterialTheme.colorScheme.onBackground
    val t = clip.transform
    var prop by remember { mutableStateOf(KeyProperty.Scale) }
    var value by remember(prop, clip.id) { mutableStateOf(prop.default) }
    var curve by remember { mutableStateOf<Interp>(Interp.EaseInOut) }
    val local = (position - project.startTime(clip.id)).coerceIn(0.0, clip.timelineDuration)

    fun place(n: ClipTransform) = state.edit("place") { p -> p.mapClipById(clip.id) { it.withTransform(n) } }

    fun setMask(m: MaskRegion?) = state.edit("area") { p -> p.mapClipById(clip.id) { it.withMask(m) } }
    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.mo_place), color = color)
        Labelled(R.string.mo_across, t.x, -0.5..0.5) { place(t.copy(x = it)) }
        Labelled(R.string.mo_down, t.y, -0.5..0.5) { place(t.copy(y = it)) }
        Labelled(R.string.mo_size, t.scale, 0.2..3.0) { place(t.copy(scale = it)) }
        Labelled(R.string.mo_turn, t.rotation, -180.0..180.0) { place(t.copy(rotation = it)) }
        Labelled(
            R.string.mo_see,
            clip.opacity,
            0.0..1.0,
        ) { v -> state.edit("see through") { p -> p.mapClipById(clip.id) { it.withOpacity(v) } } }
        Tool(stringResource(R.string.mo_reset)) { place(ClipTransform()) }

        // Shown to Pro only. A project that already uses these still plays and exports for everyone.
        val context = LocalContext.current
        val pro = remember { PremiumGate.showsDepthTools(com.zellahq.app.Entitlements.load(context), System.currentTimeMillis()) }
        if (!pro) {
            Text(stringResource(R.string.mo_pro_note), color = color)
            return@Column
        }
        Text(stringResource(R.string.mo_keys_title), color = color)
        Text(stringResource(R.string.mo_keys_detail), color = color)
        Chips {
            listOf(
                KeyProperty.Scale to R.string.mo_size,
                KeyProperty.PosX to R.string.mo_across,
                KeyProperty.PosY to R.string.mo_down,
                KeyProperty.Rotation to R.string.mo_turn,
                KeyProperty.Opacity to R.string.mo_see,
            ).forEach {
                    (k, label) ->
                Tool(stringResource(label), if (prop == k) ZellaColors.CardSun else null) { prop = k }
            }
        }
        Slider(value = value.toFloat(), onValueChange = {
            value = it.toDouble()
        }, valueRange = prop.range.start.toFloat()..prop.range.endInclusive.toFloat())
        Text(stringResource(R.string.mo_glide), color = color)
        Chips {
            listOf<Pair<Interp, Int>>(
                Interp.EaseInOut to R.string.mo_smooth,
                Interp.Linear to R.string.mo_straight,
                Interp.EaseIn to R.string.mo_slow_start,
                Interp.EaseOut to R.string.mo_slow_end,
                Interp.Hold to R.string.mo_jump,
            ).forEach { (c, label) -> Tool(stringResource(label), if (curve == c) ZellaColors.CardSun else null) { curve = c } }
        }
        val all = clip.keyframes
        val points = KeyProperty.entries.sumOf { all.track(it).keys.size }
        Chips {
            Tool(stringResource(R.string.mo_set_point), ZellaColors.CardMint) {
                state.edit("point") { p -> p.mapClipById(clip.id) { it.addKeyframe(prop, local, value, curve) } }
            }
            if (points > 0) {
                Tool(stringResource(R.string.mo_clear_points), ZellaColors.CardCoral) {
                    state.edit(
                        "clear points",
                    ) { p -> p.mapClipById(clip.id) { it.withKeyframes(com.zellahq.core.project.TransformKeyframes()) } }
                }
            }
        }
        if (points > 0) Text(stringResource(R.string.mo_points, points), color = color)

        Text(stringResource(R.string.mo_ramp_title), color = color)
        Chips {
            rampNames.forEach { name ->
                Tool(name, if ((clip.speedRamp ?: "None") == name) ZellaColors.CardSun else null) {
                    state.edit("speed ramp") { p ->
                        p.mapClipById(clip.id) { c ->
                            c.copy(
                                extras =
                                    kotlinx.serialization.json.JsonObject(
                                        if (name == "None") c.extras - "speedRamp" else c.extras + ("speedRamp" to JsonPrimitive(name)),
                                    ),
                            )
                        }
                    }
                }
            }
        }

        Text(stringResource(R.string.mo_mask_title), color = color)
        Text(stringResource(R.string.mo_mask_detail), color = color)
        val m = clip.mask
        Chips {
            Tool(stringResource(R.string.mo_mask_off), if (m == null) ZellaColors.CardSun else null) { setMask(null) }
            Tool(stringResource(R.string.mo_mask_circle), if (m?.shape == "ellipse") ZellaColors.CardSun else null) {
                setMask((m ?: MaskRegion()).copy(shape = "ellipse"))
            }
            Tool(stringResource(R.string.mo_mask_box), if (m?.shape == "rect") ZellaColors.CardSun else null) {
                setMask((m ?: MaskRegion()).copy(shape = "rect"))
            }
            if (m != null) {
                Tool(stringResource(R.string.mo_mask_flip), if (m.invert) ZellaColors.CardSun else null) {
                    setMask(m.copy(invert = !m.invert))
                }
            }
        }
        if (m != null) {
            Labelled(R.string.mo_across, m.x, 0.0..1.0) { setMask(m.copy(x = it)) }
            Labelled(R.string.mo_down, m.y, 0.0..1.0) { setMask(m.copy(y = it)) }
            Labelled(R.string.pp_wide, m.w, 0.05..1.0) { setMask(m.copy(w = it)) }
            Labelled(R.string.pp_tall, m.h, 0.05..1.0) { setMask(m.copy(h = it)) }
            Labelled(R.string.mo_soft, m.feather, 0.0..0.95) { setMask(m.copy(feather = it)) }
        }
    }
}
