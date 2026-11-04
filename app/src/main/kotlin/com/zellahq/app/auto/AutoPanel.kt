package com.zellahq.app.auto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zellahq.app.R
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Tool
import com.zellahq.core.auto.PolishStep
import com.zellahq.core.project.Aspect
import com.zellahq.core.project.Project
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing

/** The words for a step: what it is called before you tap, what it does, and what it says while running. */
private fun stepTitle(step: PolishStep) =
    when (step) {
        PolishStep.Silences -> R.string.ps_silences
        PolishStep.Fillers -> R.string.ps_fillers
        PolishStep.HookAndTransitions -> R.string.ps_hook
        PolishStep.Voice -> R.string.ps_voice
        PolishStep.Captions -> R.string.ps_captions
        PolishStep.Framing -> R.string.ps_framing
        PolishStep.Music -> R.string.ps_music
        PolishStep.PatternInterrupts -> R.string.ps_interrupts
        PolishStep.ColorsPop -> R.string.ps_colors
        PolishStep.CutSounds -> R.string.ps_sounds
        PolishStep.Reframe -> R.string.ps_reframe
    }

private fun stepDetail(step: PolishStep) =
    when (step) {
        PolishStep.Silences -> R.string.ps_silences_detail
        PolishStep.Fillers -> R.string.ps_fillers_detail
        PolishStep.HookAndTransitions -> R.string.ps_hook_detail
        PolishStep.Voice -> R.string.ps_voice_detail
        PolishStep.Captions -> R.string.ps_captions_detail
        PolishStep.Framing -> R.string.ps_framing_detail
        PolishStep.Music -> R.string.ps_music_detail
        PolishStep.PatternInterrupts -> R.string.ps_interrupts_detail
        PolishStep.ColorsPop -> R.string.ps_colors_detail
        PolishStep.CutSounds -> R.string.ps_sounds_detail
        PolishStep.Reframe -> R.string.ps_reframe_detail
    }

private fun stepDoing(step: PolishStep) =
    when (step) {
        PolishStep.Silences -> R.string.ps_silences_doing
        PolishStep.Fillers -> R.string.ps_fillers_doing
        PolishStep.HookAndTransitions -> R.string.ps_hook_doing
        PolishStep.Voice -> R.string.ps_voice_doing
        PolishStep.Captions -> R.string.ps_captions_doing
        PolishStep.Framing -> R.string.ps_framing_doing
        PolishStep.Music -> R.string.ps_music_doing
        PolishStep.PatternInterrupts -> R.string.ps_interrupts_doing
        PolishStep.ColorsPop -> R.string.ps_colors_doing
        PolishStep.CutSounds -> R.string.ps_sounds_doing
        PolishStep.Reframe -> R.string.ps_reframe_doing
    }

/** Auto-Polish, Make it Viral and the shape buttons. The list you read is the list that runs. */
@Composable
fun AutoPanel(
    state: EditorState,
    project: Project,
) {
    val text = MaterialTheme.colorScheme.onBackground
    val running by state.auto.collectAsState()
    val receipt by state.receipt.collectAsState()
    val before by state.beforeAuto.collectAsState()
    var showPolish by remember { mutableStateOf(false) }
    var showViral by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        val now = running
        if (now != null) {
            val label = now.step?.let { stringResource(R.string.au_working, now.index, now.total, stringResource(stepDoing(it))) }
            Text(label ?: stringResource(R.string.au_starting), color = text)
            if (now.total > 0) {
                LinearProgressIndicator(
                    progress = { (now.index - 1).coerceAtLeast(0).toFloat() / now.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Tool(stringResource(R.string.au_stop), ZellaColors.CardCoral) { state.stopAuto() }
            return@Column
        }
        receipt?.let { r ->
            Text(
                stringResource(
                    R.string.au_receipt,
                    r.silencesCut,
                    "%.1f".format(r.silenceSeconds),
                    r.fillersCut,
                    r.dissolves,
                    r.zooms,
                    r.interrupts,
                    r.sounds,
                ),
                color = text,
            )
        }
        if (before != null) Tool(stringResource(R.string.au_undo), ZellaColors.CardCoral) { state.undoAuto() }

        Hero(R.string.au_polish, R.string.au_polish_note, ZellaColors.CardMint) { state.runAuto(viral = false) }
        Tool(stringResource(if (showPolish) R.string.au_hide else R.string.au_see)) { showPolish = !showPolish }
        if (showPolish) PlanList(PolishStep.plan(viral = false, reframing = false))

        Hero(R.string.au_viral, R.string.au_viral_note, ZellaColors.CardSun) { state.runAuto(viral = true) }
        Tool(stringResource(if (showViral) R.string.au_hide else R.string.au_see)) { showViral = !showViral }
        if (showViral) PlanList(PolishStep.plan(viral = true, reframing = false))

        Text(stringResource(R.string.au_shape_note), color = text)
        Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(stringResource(R.string.au_vertical), if (project.aspect == Aspect.Vertical) ZellaColors.CardSun else null) {
                state.reshape(Aspect.Vertical)
            }
            Tool(stringResource(R.string.au_square), if (project.aspect == Aspect.Square) ZellaColors.CardSun else null) {
                state.reshape(Aspect.Square)
            }
            Tool(stringResource(R.string.au_wide), if (project.aspect == Aspect.Landscape) ZellaColors.CardSun else null) {
                state.reshape(Aspect.Landscape)
            }
        }
        Text(stringResource(R.string.au_stays_on_phone), color = text)
    }
}

@Composable
private fun Hero(
    title: Int,
    note: Int,
    color: androidx.compose.ui.graphics.Color,
    onTap: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.Xs)) {
        Tool(stringResource(title), color, onTap)
        Text(stringResource(note), color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun PlanList(steps: List<PolishStep>) {
    Column(Modifier.padding(start = ZellaSpacing.S), verticalArrangement = Arrangement.spacedBy(ZellaSpacing.Xs)) {
        steps.forEach { step ->
            Text(stringResource(stepTitle(step)), color = MaterialTheme.colorScheme.onBackground)
            Text(stringResource(stepDetail(step)), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f))
        }
    }
}
