// Media3's composition preview and export are marked unstable and experimental. Zella depends on
// them on purpose: they are the only way to preview exactly what exports. Pinned to 1.10.1.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.zellahq.app.editor

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.zellahq.app.R
import com.zellahq.core.project.Project
import com.zellahq.core.project.clipAt
import com.zellahq.core.project.duplicate
import com.zellahq.core.project.duration
import com.zellahq.core.project.insertClip
import com.zellahq.core.project.instantReplay
import com.zellahq.core.project.move
import com.zellahq.core.project.removeTransition
import com.zellahq.core.project.reverse
import com.zellahq.core.project.rewind
import com.zellahq.core.project.rippleDelete
import com.zellahq.core.project.setSpeed
import com.zellahq.core.project.setTransition
import com.zellahq.core.project.setVolume
import com.zellahq.core.project.split
import com.zellahq.core.project.transitionAfter
import com.zellahq.core.project.trimEnd
import com.zellahq.core.project.trimStart
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaRadius
import com.zellahq.ui.ZellaSpacing

private enum class Sheet { None, Speed, Volume, Captions }

@OptIn(UnstableApi::class)
@Composable
fun EditorScreen(
    initial: Project,
    onBack: () -> Unit,
    onExport: (Project) -> Unit,
) {
    val context = LocalContext.current
    val state = remember(initial.id) { EditorState(context, initial) }
    DisposableEffect(state) { onDispose { state.release() } }
    val project by state.project.collectAsState()
    val position by state.position.collectAsState()
    val playing by state.playing.collectAsState()
    val selectedId by state.selected.collectAsState()
    val history by state.history.collectAsState()
    val missing by state.missing.collectAsState()
    val reversing by state.reversing.collectAsState()
    val currentPlayer by state.playerFlow.collectAsState()
    val still by state.still.collectAsState()
    var sheet by remember { mutableStateOf(Sheet.None) }
    val selected = project.clips.firstOrNull { it.id == selectedId }
    val store = remember { Storage.store(context) }

    BackHandler {
        state.saveNow()
        onBack()
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val file = Storage.importUri(context, uri) ?: return@rememberLauncherForActivityResult
            val len = Storage.durationSeconds(file) ?: return@rememberLauncherForActivityResult
            val clip =
                com.zellahq.core.project.Clip(
                    com.zellahq.core.project.newId(),
                    com.zellahq.core.project.MediaRef.Absolute(file.toURI().toString()),
                    len,
                )
            state.edit("add clip") { it.insertClip(clip, position) }
        }

    Column(
        Modifier.fillMaxSize().padding(ZellaSpacing.S),
        verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S),
        ) {
            Tool(stringResource(R.string.ed_back)) {
                state.saveNow()
                onBack()
            }
            Text(project.name, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            Tool(stringResource(R.string.ed_export), ZellaColors.CardViolet) {
                state.player.pause()
                state.saveNow()
                onExport(project)
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.aspectRatio(project.aspect.ratio.toFloat()),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                // A project that gains or loses a fade gets a different player; attach whichever is current.
                update = { it.player = currentPlayer },
            )
            // While paused in a project with a fade, the frame under the playhead comes from the file.
            still?.let {
                androidx.compose.foundation.Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.aspectRatio(project.aspect.ratio.toFloat()),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
            if (project.clips.isEmpty()) {
                Text(
                    stringResource(R.string.ed_empty),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        if (missing.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.ed_missing_plural, missing.size, missing.size),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (reversing) Text(stringResource(R.string.ed_reversing), color = MaterialTheme.colorScheme.onBackground)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.M),
        ) {
            Tool(
                stringResource(if (playing) R.string.ed_pause else R.string.ed_play),
                ZellaColors.CardMint,
            ) { state.togglePlay() }
            Text("${mmss(position)} / ${mmss(project.duration)}", color = MaterialTheme.colorScheme.onBackground)
        }

        val clipLabel = stringResource(R.string.ed_clip_label)
        val fadeLabel = stringResource(R.string.ed_fade_chip)
        val timelineDescription = stringResource(R.string.ed_timeline_description, project.clips.size)
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            factory = { ctx ->
                TimelineView(ctx, store).apply {
                    onScrub = { state.seek(it) }
                    onSelect = { state.select(it) }
                    onTrim = {
                            id,
                            start,
                            d,
                        ->
                        state.edit("trim") { p -> if (start) p.trimStart(id, d) else p.trimEnd(id, d) }
                    }
                }
            },
            update = { v ->
                v.project = project
                v.position = position
                v.selectedId = selectedId
                v.fadeLabel = fadeLabel
                v.contentDescription = timelineDescription
                v.labelFor = { i, c -> clipLabel.format(i + 1, mmss(c.timelineDuration)) }
            },
        )

        // Every tool is a labelled word. Tools that need a clip act on the picked one.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S),
        ) {
            Tool(stringResource(R.string.ed_captions), if (sheet == Sheet.Captions) ZellaColors.CardSun else ZellaColors.CardMint) {
                sheet = if (sheet == Sheet.Captions) Sheet.None else Sheet.Captions
            }
            Tool(stringResource(R.string.ed_split)) { state.edit("split") { it.split(position) } }
            if (selected != null) {
                val index = project.clips.indexOf(selected)
                Tool(stringResource(R.string.ed_delete), ZellaColors.CardCoral) {
                    state.edit("delete") { it.rippleDelete(selected.id) }
                }
                if (index > 0) {
                    Tool(
                        stringResource(R.string.ed_move_earlier),
                    ) { state.edit("move") { it.move(selected.id, index - 1) } }
                }
                if (index < project.clips.size - 1) {
                    Tool(stringResource(R.string.ed_move_later)) {
                        state.edit("move") { it.move(selected.id, index + 1) }
                    }
                }
                Tool(
                    stringResource(R.string.ed_speed),
                ) { sheet = if (sheet == Sheet.Speed) Sheet.None else Sheet.Speed }
                Tool(
                    stringResource(R.string.ed_volume),
                ) { sheet = if (sheet == Sheet.Volume) Sheet.None else Sheet.Volume }
                Tool(stringResource(R.string.ed_duplicate)) { state.edit("duplicate") { it.duplicate(selected.id) } }
                Tool(stringResource(R.string.ed_replay)) { state.edit("replay") { it.instantReplay(selected.id) } }
                Tool(stringResource(R.string.ed_rewind)) { state.edit("rewind") { it.rewind(selected.id) } }
                Tool(stringResource(if (selected.reversed) R.string.ed_play_forward else R.string.ed_reverse)) {
                    state.edit("reverse") { it.reverse(selected.id) }
                }
                if (index < project.clips.size - 1) {
                    if (project.transitionAfter(selected.id) == null) {
                        Tool(stringResource(R.string.ed_add_fade)) {
                            state.edit("fade") { it.setTransition(selected.id, "Cross Dissolve", 0.5) }
                        }
                    } else {
                        Tool(
                            stringResource(R.string.ed_remove_fade),
                        ) { state.edit("fade") { it.removeTransition(selected.id) } }
                    }
                }
            }
            Tool(stringResource(R.string.ed_add_clip)) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
            if (history.canUndo) Tool(stringResource(R.string.ed_undo)) { state.undo() }
            if (history.canRedo) Tool(stringResource(R.string.ed_redo)) { state.redo() }
        }

        if (sheet == Sheet.Captions) {
            // Captions can be long; the panel scrolls on its own so the tools above stay in place.
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                com.zellahq.app.captions.CaptionsPanel(state, project)
            }
        }
        if (selected != null && sheet == Sheet.Speed) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S),
            ) {
                listOf(
                    0.5 to R.string.ed_speed_half,
                    1.0 to R.string.ed_speed_normal,
                    1.5 to R.string.ed_speed_faster,
                    2.0 to R.string.ed_speed_double,
                    3.0 to R.string.ed_speed_triple,
                )
                    .forEach {
                            (v, label) ->
                        Tool(stringResource(label), if (selected.speed == v) ZellaColors.CardSun else null) {
                            state.edit("speed") { it.setSpeed(selected.id, v) }
                        }
                    }
            }
        }
        if (selected != null && sheet == Sheet.Volume) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S),
            ) {
                listOf(0.0 to R.string.ed_volume_mute, 0.5 to R.string.ed_volume_half, 1.0 to R.string.ed_volume_full)
                    .forEach {
                            (v, label) ->
                        Tool(stringResource(label), if (selected.volume == v) ZellaColors.CardSun else null) {
                            state.edit("volume") { it.setVolume(selected.id, v) }
                        }
                    }
            }
        }
        if (selected == null && project.clips.isNotEmpty()) {
            Text(stringResource(R.string.ed_hint_pick), color = MaterialTheme.colorScheme.onBackground)
        }
    }
    // Keep the picked clip in step with the playhead when nothing is picked.
    if (selectedId == null) project.clipAt(position)?.first?.id?.let { state.select(it) }
}

@Composable
internal fun Tool(
    label: String,
    color: Color? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(min = ZellaSpacing.MinTap)
            .background(color ?: ZellaColors.NightCard, RoundedCornerShape(ZellaRadius.Card))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = ZellaSpacing.M),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (color == null) Color.White else ZellaColors.Ink) }
}

fun mmss(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    return "%d:%02d".format(s / 60, s % 60)
}
