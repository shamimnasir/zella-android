// Media3's composition preview and export are marked unstable and experimental. Zella depends on
// them on purpose: they are the only way to preview exactly what exports. Pinned to 1.10.1.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.zellahq.app.editor

import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
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
import com.zellahq.core.project.reverse
import com.zellahq.core.project.rewind
import com.zellahq.core.project.rippleDelete
import com.zellahq.core.project.setSpeed
import com.zellahq.core.project.setVolume
import com.zellahq.core.project.split
import com.zellahq.core.project.trimEnd
import com.zellahq.core.project.trimStart
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaRadius
import com.zellahq.ui.ZellaSpacing

private enum class Sheet {
    None,
    Speed,
    Volume,
    Captions,
    Look,
    Sound,
    Text,
    Transition,
    Auto,
    Callouts,
    Assistant,
    Background,
    BRoll,
    Pip,
    Effects,
    Motion,
}

/** The editor's five groups of tools. */
private enum class ToolGroup(val label: Int) {
    Ai(R.string.ed_group_ai),
    Cut(R.string.ed_group_cut),
    Words(R.string.ed_group_words),
    Sound(R.string.ed_group_sound),
    Picture(R.string.ed_group_picture),
}

@OptIn(UnstableApi::class)
@Composable
fun EditorScreen(
    state: EditorState,
    onBack: () -> Unit,
    onExport: (Project) -> Unit,
) {
    val context = LocalContext.current
    val note by state.note.collectAsState()
    val assistant = remember(state) { com.zellahq.app.assistant.AssistantSession(context, state) }
    DisposableEffect(assistant) { onDispose { assistant.close() } }
    val project by state.project.collectAsState()
    val position by state.position.collectAsState()
    val playing by state.playing.collectAsState()
    val selectedId by state.selected.collectAsState()
    val history by state.history.collectAsState()
    val missing by state.missing.collectAsState()
    val reversing by state.reversing.collectAsState()
    val currentPlayer by state.playerFlow.collectAsState()
    val baking by state.baking.collectAsState()
    var sheet by remember { mutableStateOf(Sheet.None) }
    var group by remember { mutableStateOf(ToolGroup.Ai) }
    val selected = project.clips.firstOrNull { it.id == selectedId }
    val store = remember { Storage.store(context) }

    // The panels share the screen with the picture: a tall panel used to leave only a thumbnail to watch.
    val panelMax = (LocalConfiguration.current.screenHeightDp * 0.3f).dp.coerceAtLeast(160.dp)

    BackHandler {
        state.saveNow()
        onBack()
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val at = position
            // A long video takes a while to copy, so not on the main thread.
            Thread({
                val file = Storage.importUri(context, uri)
                val len = file?.let { Storage.durationSeconds(it) }
                if (file != null && len != null) {
                    val clip =
                        com.zellahq.core.project.Clip(
                            com.zellahq.core.project.newId(),
                            com.zellahq.core.project.MediaRef.Absolute(file.toURI().toString()),
                            len,
                        )
                    state.onMain { state.edit("add clip") { it.insertClip(clip, at) } }
                }
            }, "add-clip").start()
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
                state.saveBlocking()
                onBack()
            }
            Text(project.name, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            Tool(stringResource(R.string.ed_export), ZellaColors.CardViolet) {
                state.player.pause()
                state.saveBlocking()
                onExport(project)
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            var playerView by remember { mutableStateOf<PlayerView?>(null) }
            // A panel opening resizes the picture, and a paused player shows black until it has the surface again and
            // is told to redraw. Either one alone left it black.
            val redraw =
                remember {
                    Runnable {
                        playerView?.let { v ->
                            val p = v.player
                            v.player = null
                            v.player = p
                        }
                        state.redrawAtPlayhead()
                    }
                }
            AndroidView(
                modifier = Modifier.aspectRatio(project.aspect.ratio.toFloat()),
                factory = { ctx ->
                    PlayerView(ctx).also { playerView = it }.apply {
                        useController = false
                        // The default shutter is an opaque black sheet that only lifts on a "first frame" event,
                        // which a paused composition does not send again. It hid the picture after every edit.
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setKeepContentOnPlayerReset(true)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        // When the surface itself has changed, not on a timer: a timer sometimes ran first and left it black.
                        (videoSurfaceView as? SurfaceView)?.holder?.addCallback(
                            object : SurfaceHolder.Callback {
                                private var size: Pair<Int, Int>? = null

                                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    val before = size
                                    size = width to height
                                    if (before != null && before != size) {
                                        removeCallbacks(redraw)
                                        postDelayed(redraw, 50)
                                    }
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    size = null
                                }
                            },
                        )
                    }
                },
                update = { it.player = currentPlayer },
            )
            if (project.clips.isEmpty()) {
                // The player keeps its last picture when emptied.
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background))
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
        if (baking) Text(stringResource(R.string.ed_baking), color = MaterialTheme.colorScheme.onBackground)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.M),
        ) {
            Tool(
                stringResource(if (playing) R.string.ed_pause else R.string.ed_play),
                ZellaColors.CardMint,
            ) { state.togglePlay() }
            Text(
                "${mmss(position)} / ${mmss(project.duration)}",
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            // Always in the same place, so they can be reached from any tool and never slide under a finger.
            Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.Xs)) {
                Tool(stringResource(R.string.ed_undo), enabled = history.canUndo, sidePadding = ZellaSpacing.S) { state.undo() }
                Tool(stringResource(R.string.ed_redo), enabled = history.canRedo, sidePadding = ZellaSpacing.S) { state.redo() }
            }
        }

        val clipLabel = stringResource(R.string.ed_clip_label)
        val fadeLabel = stringResource(R.string.ed_fade_chip)
        val timelineDescription = stringResource(R.string.ed_timeline_description, project.clips.size)
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(if (sheet == Sheet.None) 120.dp else 92.dp),
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

        note?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }

        // Every tool is a labelled word. Tools sit in five groups, so a beginner is never scrolling through thirty.
        // All five groups fit on a 360 dp phone in English. A longer language can still scroll this row.
        // Tools that need a clip act on the picked one.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.Xs),
        ) {
            ToolGroup.entries.forEach { g ->
                Tool(stringResource(g.label), if (group == g) ZellaColors.CardViolet else null, sidePadding = 5.dp) {
                    group = g
                    sheet = Sheet.None
                }
            }
        }

        @Composable
        fun SheetTool(
            label: Int,
            which: Sheet,
            colour: Color? = null,
        ) {
            Tool(stringResource(label), if (sheet == which) ZellaColors.CardSun else colour) {
                sheet = if (sheet == which) Sheet.None else which
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S),
        ) {
            val index = selected?.let { project.clips.indexOf(it) } ?: -1
            when (group) {
                ToolGroup.Ai -> {
                    SheetTool(R.string.ed_auto, Sheet.Auto, ZellaColors.CardMint)
                    SheetTool(R.string.ed_assistant, Sheet.Assistant, ZellaColors.CardViolet)
                    SheetTool(R.string.ed_broll, Sheet.BRoll)
                }
                ToolGroup.Cut -> {
                    Tool(stringResource(R.string.ed_split)) { state.edit("split") { it.split(position) } }
                    if (selected != null) {
                        Tool(stringResource(R.string.ed_delete), ZellaColors.CardCoral) {
                            state.edit("delete") { it.rippleDelete(selected.id) }
                        }
                        if (index > 0) {
                            Tool(
                                stringResource(R.string.ed_move_earlier),
                            ) { state.edit("move") { it.move(selected.id, index - 1) } }
                        }
                        if (index < project.clips.size - 1) {
                            Tool(stringResource(R.string.ed_move_later)) { state.edit("move") { it.move(selected.id, index + 1) } }
                        }
                        SheetTool(R.string.ed_speed, Sheet.Speed)
                        Tool(stringResource(R.string.ed_freeze)) { state.freezeFrame(position) }
                        Tool(stringResource(R.string.ed_duplicate)) { state.edit("duplicate") { it.duplicate(selected.id) } }
                        Tool(stringResource(R.string.ed_replay)) { state.edit("replay") { it.instantReplay(selected.id) } }
                        Tool(stringResource(R.string.ed_rewind)) { state.edit("rewind") { it.rewind(selected.id) } }
                        Tool(stringResource(if (selected.reversed) R.string.ed_play_forward else R.string.ed_reverse)) {
                            state.edit("reverse") { it.reverse(selected.id) }
                        }
                        if (index < project.clips.size - 1) SheetTool(R.string.ed_transition, Sheet.Transition)
                    }
                    Tool(stringResource(R.string.ed_add_clip)) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    }
                }
                ToolGroup.Words -> {
                    SheetTool(R.string.ed_captions, Sheet.Captions, ZellaColors.CardMint)
                    SheetTool(R.string.ed_text, Sheet.Text)
                    SheetTool(R.string.ed_callouts, Sheet.Callouts)
                }
                ToolGroup.Sound -> {
                    SheetTool(R.string.ed_sound, Sheet.Sound, ZellaColors.CardMint)
                    if (selected != null) SheetTool(R.string.ed_volume, Sheet.Volume)
                }
                ToolGroup.Picture -> {
                    if (selected != null) SheetTool(R.string.ed_look, Sheet.Look, ZellaColors.CardMint)
                    SheetTool(R.string.ed_background, Sheet.Background)
                    if (selected != null) {
                        SheetTool(R.string.ed_effects, Sheet.Effects)
                        SheetTool(R.string.ed_motion, Sheet.Motion)
                    }
                    SheetTool(R.string.ed_pip, Sheet.Pip)
                }
            }
        }

        if (sheet == Sheet.Captions) {
            // Captions can be long; the panel scrolls on its own so the tools above stay in place.
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                com.zellahq.app.captions.CaptionsPanel(state, project)
            }
        }
        if (selected != null && sheet == Sheet.Transition && selected.id != project.clips.lastOrNull()?.id) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                TransitionPanel(state, project, selected.id)
            }
        }
        if (sheet == Sheet.Auto) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                com.zellahq.app.auto.AutoPanel(state, project)
            }
        }
        if (sheet == Sheet.Text) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                com.zellahq.app.titles.TitlesPanel(state, project, position)
            }
        }
        if (sheet == Sheet.Background) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                com.zellahq.app.cutout.BackgroundPanel(state, project)
            }
        }
        if (sheet == Sheet.BRoll) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                com.zellahq.app.broll.BRollPanel(state, project, selectedId)
            }
        }
        if (sheet == Sheet.Pip) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                com.zellahq.app.depth.PipPanel(state, project, position)
            }
        }
        if (selected != null && sheet == Sheet.Effects) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                com.zellahq.app.depth.EffectsPanel(state, selected)
            }
        }
        if (selected != null && sheet == Sheet.Motion) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                com.zellahq.app.depth.MotionPanel(state, project, selected, position)
            }
        }
        if (sheet == Sheet.Callouts) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                com.zellahq.app.callouts.CalloutsPanel(state, project, position)
            }
        }
        if (sheet == Sheet.Assistant) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax + 60.dp)) {
                com.zellahq.app.assistant.AssistantPanel(assistant)
            }
        }
        if (sheet == Sheet.Sound) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                com.zellahq.app.sound.SoundPanel(state, project, selected, position)
            }
        }
        if (selected != null && sheet == Sheet.Look) {
            androidx.compose.foundation.layout.Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                com.zellahq.app.looks.LookPanel(state, selected)
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
    enabled: Boolean = true,
    sidePadding: Dp = ZellaSpacing.M,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(min = ZellaSpacing.MinTap)
            .widthIn(min = ZellaSpacing.MinTap)
            .alpha(if (enabled) 1f else 0.4f)
            .background(color ?: ZellaColors.NightCard, RoundedCornerShape(ZellaRadius.Card))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = sidePadding),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (color == null) Color.White else ZellaColors.Ink) }
}

fun mmss(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    return "%d:%02d".format(s / 60, s % 60)
}
