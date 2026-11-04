package com.zellahq.app.titles

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zellahq.app.R
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Tool
import com.zellahq.app.editor.mmss
import com.zellahq.core.motion.TextAnimationSpec
import com.zellahq.core.motion.TextMotionCatalog
import com.zellahq.core.motion.TextMotionPhase
import com.zellahq.core.motion.TextMotionPhaseKind
import com.zellahq.core.project.Project
import com.zellahq.core.project.Title
import com.zellahq.core.project.TitleLook
import com.zellahq.core.project.duration
import com.zellahq.core.project.latestOverlayStart
import com.zellahq.core.project.position
import com.zellahq.core.project.withField
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing
import kotlinx.serialization.json.JsonPrimitive

private enum class Motion { In, Stay, Out }

/** Add words to the video, pick a look, and choose how they move. Every control is a labelled word. */
@Composable
fun TitlesPanel(
    state: EditorState,
    project: Project,
    position: Double,
) {
    val context = LocalContext.current
    val text = MaterialTheme.colorScheme.onBackground
    val titles = project.titles.orEmpty()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var look by remember { mutableStateOf<TitleLookPreset?>(null) }
    val looks = remember { TitleLooks.all(context) }
    val selected = titles.firstOrNull { it.id == selectedId }
    var draft by remember(selectedId) { mutableStateOf(selected?.text.orEmpty()) }

    fun update(change: (Title) -> Title) {
        val id = selected?.id ?: return
        state.edit("text") { p -> p.copy(titles = p.titles?.map { if (it.id == id) change(it) else it }) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                if (selected != null && it.isNotBlank()) update { t -> t.copy(text = it) }
            },
            label = { Text(stringResource(if (selected == null) R.string.tt_new else R.string.tt_edit)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            if (selected == null) {
                Tool(stringResource(R.string.tt_add), ZellaColors.CardMint) {
                    if (draft.isBlank()) return@Tool
                    val preset = look ?: looks.first()
                    val start = minOf(position, latestOverlayStart(project.duration, 3.0))
                    val t = preset.title(draft.trim(), start)
                    state.edit("add text") { it.copy(titles = it.titles.orEmpty() + t) }
                    selectedId = t.id
                    // A title's entrance starts from nothing, so land just inside it to show it.
                    state.seek(minOf(start + 0.6, project.duration))
                }
            } else {
                Tool(stringResource(R.string.tt_done)) { selectedId = null }
                Tool(stringResource(R.string.tt_remove), ZellaColors.CardCoral) {
                    state.edit("remove text") { p -> p.copy(titles = p.titles?.filterNot { it.id == selected.id }?.ifEmpty { null }) }
                    selectedId = null
                }
                Tool(stringResource(R.string.tt_to_playhead)) {
                    update { it.copy(start = minOf(position, latestOverlayStart(project.duration, it.duration))) }
                }
            }
            titles.forEachIndexed { i, t ->
                Tool(
                    stringResource(R.string.tt_item, i + 1, t.text.take(12), mmss(t.start)),
                    if (t.id == selectedId) ZellaColors.CardSun else null,
                ) { selectedId = t.id }
            }
        }

        Text(stringResource(R.string.tt_style), color = text)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            looks.forEach { preset ->
                Tool(preset.label, if (look === preset) ZellaColors.CardSun else ZellaColors.CardViolet) {
                    look = preset
                    if (selected != null) {
                        // Keep where the title sits and how it moves; change only how it looks.
                        val keep = selected.extras.filterKeys { it == "position" || it == "textAnimation" || it == "subtitle" }
                        val fields = preset.fields().filterKeys { it !in keep }
                        update { it.copy(extras = kotlinx.serialization.json.JsonObject(keep + fields)) }
                    }
                }
            }
        }

        if (selected != null) {
            val tl = TitleLook(selected)
            Text(stringResource(R.string.tt_place), color = text)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                listOf(R.string.tt_top to 0.14, R.string.tt_middle to 0.5, R.string.tt_bottom to 0.82).forEach { (label, y) ->
                    Tool(stringResource(label), if (kotlin.math.abs(tl.positionY - y) < 0.01) ZellaColors.CardSun else null) {
                        update { it.withField("position", position(tl.positionX, y)) }
                    }
                }
                listOf(R.string.tt_small to 60.0, R.string.tt_medium to 90.0, R.string.tt_big to 130.0).forEach { (label, size) ->
                    Tool(stringResource(label), if (kotlin.math.abs(tl.fontSize - size) < 0.5) ZellaColors.CardSun else null) {
                        update { it.withField("fontSize", JsonPrimitive(size)) }
                    }
                }
                listOf(2.0, 3.0, 5.0, 8.0).forEach { d ->
                    Tool(
                        stringResource(R.string.tt_seconds, d.toInt()),
                        if (kotlin.math.abs(selected.duration - d) < 0.01) ZellaColors.CardSun else null,
                    ) {
                        update { it.copy(duration = d) }
                    }
                }
            }
            Text(stringResource(R.string.tt_font), color = text)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                TitleFonts.BUNDLED.forEach { (name, label) ->
                    Tool(label, if (tl.fontName.orEmpty() == name) ZellaColors.CardSun else null) {
                        update { it.withField("fontName", if (name.isEmpty()) null else JsonPrimitive(name)) }
                    }
                }
            }
            MotionPicker(selected, ::update)
        }
    }
}

@Composable
private fun MotionPicker(
    title: Title,
    update: ((Title) -> Title) -> Unit,
) {
    val text = MaterialTheme.colorScheme.onBackground
    var part by remember { mutableStateOf(Motion.In) }
    var family by remember { mutableStateOf(TextMotionCatalog.families.first().first) }
    val spec = TitleLook(title).animation ?: TextAnimationSpec()
    val kind =
        when (part) {
            Motion.In -> TextMotionPhaseKind.In
            Motion.Stay -> TextMotionPhaseKind.Loop
            Motion.Out -> TextMotionPhaseKind.Out
        }
    val current =
        when (part) {
            Motion.In -> spec.inPhase
            Motion.Stay -> spec.loop
            Motion.Out -> spec.outPhase
        }

    fun set(phase: TextMotionPhase?) {
        val next =
            when (part) {
                Motion.In -> spec.copy(inPhase = phase)
                Motion.Stay -> spec.copy(loop = phase)
                Motion.Out -> spec.copy(outPhase = phase)
            }
        update { it.withField("textAnimation", if (next.isEmpty) null else next.toJson()) }
    }
    Text(stringResource(R.string.tt_motion), color = text)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Tool(stringResource(R.string.tt_motion_in), if (part == Motion.In) ZellaColors.CardSun else null) { part = Motion.In }
        Tool(stringResource(R.string.tt_motion_stay), if (part == Motion.Stay) ZellaColors.CardSun else null) { part = Motion.Stay }
        Tool(stringResource(R.string.tt_motion_out), if (part == Motion.Out) ZellaColors.CardSun else null) { part = Motion.Out }
        Tool(stringResource(R.string.tt_motion_none), if (current == null) ZellaColors.CardSun else null) { set(null) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        TextMotionCatalog.families.forEach { (id, name) ->
            Tool(name, if (family == id) ZellaColors.CardMint else null) { family = id }
        }
    }
    val presets = TextMotionCatalog.all.filter { it.family == family && it.supports(kind) }
    if (presets.isEmpty()) {
        Text(stringResource(R.string.tt_motion_empty), color = text)
    } else {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            presets.forEach { p ->
                Tool(p.name, if (current?.preset == p.id) ZellaColors.CardSun else ZellaColors.CardViolet) {
                    set(TextMotionPhase(p.id))
                }
            }
        }
    }
}
