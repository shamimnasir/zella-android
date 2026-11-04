package com.zellahq.app.looks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zellahq.app.Entitlements
import com.zellahq.app.R
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Tool
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate
import com.zellahq.core.looks.ColorBoard
import com.zellahq.core.project.Clip
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing

/** Looks, strength, colour wheels and imported LUTs for the picked clip. Grading is its own step. */
@Composable
fun LookPanel(
    state: EditorState,
    clip: Clip,
) {
    val context = LocalContext.current
    val text = MaterialTheme.colorScheme.onBackground
    // Founder grades are shown only to Founder owners (the iPhone gates the reveal the same way).
    val founder = remember { PremiumGate.isFounder(Entitlements.load(context)) || Entitlements.load(context).purchased == Plan.Founder }
    val looks = remember { Looks.all(context).filter { !it.founder || founder } }
    val categories = Looks.CATEGORY_ORDER.filter { c -> looks.any { it.category == c } }
    var category by rememberSaveable { mutableStateOf(categories.first()) }
    var note by remember { mutableStateOf<Int?>(null) }
    val current = Looks.presetName(clip)
    val strength = Looks.intensity(clip)

    fun change(
        label: String,
        f: (Clip) -> Clip,
    ) = state.edit(label) { p -> p.copy(clips = p.clips.map { if (it.id == clip.id) f(it) else it }) }

    val cubePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val textCube =
                runCatching {
                    context.contentResolver.openInputStream(
                        uri,
                    )!!.use { it.readBytes().decodeToString() }
                }.getOrNull()
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "LUT"
            val updated = textCube?.let { Looks.importCube(context, clip, name, it) }
            if (updated == null) {
                note = R.string.look_cube_bad
            } else {
                note = null
                change("import LUT") { updated }
            }
        }

    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.look_title), color = text)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            categories.forEach { c -> Tool(c, if (c == category) ZellaColors.CardSun else null) { category = c } }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(stringResource(R.string.look_none), if (current == null) ZellaColors.CardSun else null) {
                change("look") { Looks.applyLook(it, null) }
            }
            looks.filter { it.category == category }.forEach { look ->
                Tool(look.name, if (look.name == current) ZellaColors.CardSun else ZellaColors.CardViolet) {
                    change("look") { Looks.applyLook(it, look, if (current == null) Looks.DEFAULT_INTENSITY else strength) }
                }
            }
        }
        if (current != null) {
            Text(stringResource(R.string.look_strength), color = text)
            Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                listOf(0.25f, 0.5f, 0.75f, 1f).forEach { t ->
                    Tool("${(t * 100).toInt()}%", if (kotlin.math.abs(strength - t) < 0.01f) ZellaColors.CardSun else null) {
                        change("look strength") { Looks.setIntensity(it, t) }
                    }
                }
            }
            Tool(stringResource(R.string.look_all_clips)) {
                val look = Looks.byName(context, current)
                state.edit("look all") { p -> p.copy(clips = p.clips.map { Looks.applyLook(it, look, strength) }) }
            }
        }

        // Colour wheels in words: each part of the picture brighter or darker, warmer or cooler.
        Text(stringResource(R.string.look_wheels), color = text)
        val board = Looks.board((clip.extras["effects"] as? kotlinx.serialization.json.JsonObject)?.get("colorBoard"))
        wheels.forEach { wheel ->
            Text(stringResource(wheel.label), color = text)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                Tool(stringResource(R.string.look_darker)) { change("colour") { Looks.setBoard(it, wheel.light(board, -0.05f)) } }
                Tool(stringResource(R.string.look_brighter)) { change("colour") { Looks.setBoard(it, wheel.light(board, 0.05f)) } }
                Tool(stringResource(R.string.look_cooler)) { change("colour") { Looks.setBoard(it, wheel.warm(board, -0.03f)) } }
                Tool(stringResource(R.string.look_warmer)) { change("colour") { Looks.setBoard(it, wheel.warm(board, 0.03f)) } }
            }
        }
        if (!board.isIdentity) Tool(stringResource(R.string.look_reset_wheels)) { change("colour") { Looks.setBoard(it, ColorBoard()) } }

        val hasCube = (clip.extras["effects"] as? kotlinx.serialization.json.JsonObject)?.containsKey("lut") == true
        Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(stringResource(R.string.look_cube_add)) { cubePicker.launch(arrayOf("*/*")) }
            if (hasCube) Tool(stringResource(R.string.look_cube_remove)) { change("remove LUT") { Looks.removeCube(it) } }
        }
        note?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

/** One colour wheel in words: lift for the dark parts, gamma for the middle, gain for the bright parts. */
private class Wheel(
    val label: Int,
    val light: (ColorBoard, Float) -> ColorBoard,
    val warm: (ColorBoard, Float) -> ColorBoard,
)

private val wheels =
    listOf(
        Wheel(
            R.string.look_shadows,
            { b, d -> b.copy(liftL = (b.liftL + d).coerceIn(-0.3f, 0.3f)) },
            { b, d -> b.copy(liftR = (b.liftR + d).coerceIn(-0.3f, 0.3f), liftB = (b.liftB - d).coerceIn(-0.3f, 0.3f)) },
        ),
        Wheel(
            R.string.look_midtones,
            { b, d -> b.copy(gammaL = (b.gammaL + d).coerceIn(-0.5f, 0.5f)) },
            { b, d -> b.copy(gammaR = (b.gammaR + d).coerceIn(-0.5f, 0.5f), gammaB = (b.gammaB - d).coerceIn(-0.5f, 0.5f)) },
        ),
        Wheel(
            R.string.look_highlights,
            { b, d -> b.copy(gainL = (b.gainL + d).coerceIn(-0.4f, 0.4f)) },
            { b, d -> b.copy(gainR = (b.gainR + d).coerceIn(-0.4f, 0.4f), gainB = (b.gainB - d).coerceIn(-0.4f, 0.4f)) },
        ),
    )
