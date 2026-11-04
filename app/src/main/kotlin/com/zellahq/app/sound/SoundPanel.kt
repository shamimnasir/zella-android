@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app.sound

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zellahq.app.R
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Tool
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.MusicBed
import com.zellahq.core.project.Project
import com.zellahq.core.project.SoundBite
import com.zellahq.core.project.newId
import com.zellahq.core.project.overlays
import com.zellahq.core.project.setVolume
import com.zellahq.core.project.updateOverlay
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Every sound level and source in one place: music, effects, voice clean-up and loudness. */
@Composable
fun SoundPanel(
    state: EditorState,
    project: Project,
    selected: Clip?,
    position: Double,
) {
    val context = LocalContext.current
    val text = MaterialTheme.colorScheme.onBackground
    val music = remember { Sounds.music(context) }
    val effects = remember { Sounds.effects(context) }
    var showAllEffects by remember { mutableStateOf(false) }
    var analysing by remember { mutableStateOf(false) }
    val bed = project.musicBeds?.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Levels(state, project, selected)
        BeatPunch(state)
        Text(stringResource(R.string.snd_voice_fx), color = text)
        val currentFx = (project.extras["voiceChanger"] as? JsonPrimitive)?.content
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(stringResource(R.string.snd_voice_none), if (currentFx == null) ZellaColors.CardSun else null) {
                state.edit("voice effect") { it.copy(extras = JsonObject(it.extras - "voiceChanger")) }
            }
            VoiceEffect.entries.forEach { fx ->
                Tool(fx.raw, if (currentFx == fx.raw) ZellaColors.CardSun else null) {
                    state.edit("voice effect") { it.copy(extras = JsonObject(it.extras + ("voiceChanger" to JsonPrimitive(fx.raw)))) }
                }
            }
        }
        Text(stringResource(R.string.snd_music), color = text)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            music.forEach { m ->
                Tool(m.id, if (bed?.name == m.id) ZellaColors.CardSun else ZellaColors.CardViolet) {
                    val f = Sounds.file(context, m, "music")
                    state.edit("music") {
                        it.copy(
                            musicBeds =
                                listOf(
                                    MusicBed(
                                        newId(),
                                        MediaRef.Absolute(f.toURI().toString()),
                                        m.seconds,
                                        start = 0.0,
                                        volume = bed?.volume ?: 0.4,
                                        duck = bed?.duck ?: true,
                                        name = m.id,
                                    ),
                                ),
                        )
                    }
                    // Music that should step back for the voice needs to know where the voice is.
                    if (!Sounds.hasVoiceWindows(state.project.value)) state.findVoice()
                }
            }
        }
        if (bed != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                listOf(0.2 to R.string.snd_quiet, 0.4 to R.string.snd_medium, 0.7 to R.string.snd_loud).forEach { (v, label) ->
                    Tool(stringResource(label), if (kotlin.math.abs(bed.volume - v) < 0.01) ZellaColors.CardSun else null) {
                        state.edit("music volume") { p -> p.copy(musicBeds = p.musicBeds?.map { it.copy(volume = v) }) }
                    }
                }
                Tool(stringResource(R.string.snd_remove_music), ZellaColors.CardCoral) { state.edit("music") { it.copy(musicBeds = null) } }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Switch) {
                    state.edit("duck") { p -> p.copy(musicBeds = p.musicBeds?.map { it.copy(duck = !bed.duck) }) }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.snd_duck), color = text, modifier = Modifier.weight(1f))
                Switch(checked = bed.duck, onCheckedChange = null)
            }
            Text(stringResource(if (bed.duck) R.string.snd_music_note else R.string.snd_music_once), color = text)
        }

        Text(stringResource(R.string.snd_effect_here), color = text)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            (if (showAllEffects) effects else effects.take(16)).forEach { fx ->
                Tool(fx.id) {
                    state.edit("sound effect") { p -> p.copy(soundBites = p.soundBites.orEmpty() + SoundBite(newId(), fx.id, position)) }
                }
            }
            if (!showAllEffects) Tool(stringResource(R.string.snd_more, effects.size)) { showAllEffects = true }
        }
        val bites = project.soundBites.orEmpty()
        if (bites.isNotEmpty()) {
            Tool(stringResource(R.string.snd_remove_effects, bites.size)) { state.edit("sound effect") { it.copy(soundBites = null) } }
        }

        if (selected != null) {
            val on = (selected.extras["voiceNoise"] as? JsonPrimitive)?.content == "true"
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Switch) {
                    state.edit("clean voice") { p ->
                        p.copy(
                            clips =
                                p.clips.map { c ->
                                    if (c.id != selected.id) {
                                        c
                                    } else {
                                        c.copy(
                                            extras =
                                                JsonObject(
                                                    if (on) c.extras - "voiceNoise" else c.extras + ("voiceNoise" to JsonPrimitive(true)),
                                                ),
                                        )
                                    }
                                },
                        )
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.snd_clean), color = text, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = null)
            }
        }

        val gain = project.extras[Sounds.LOUDNESS_KEY]?.jsonPrimitive?.doubleOrNull
        if (analysing) {
            Text(stringResource(R.string.snd_listening), color = text)
        } else {
            Tool(stringResource(if (gain == null) R.string.snd_even else R.string.snd_even_again), ZellaColors.CardMint) {
                analysing = true
                state.analyseSound { analysing = false }
            }
        }
        gain?.let { Text(stringResource(R.string.snd_even_done, "%+.1f".format(it)), color = text) }
    }
}

/** The Audio tool: the clip, the music, the effects and each picture-in-picture, on one screen. */
@Composable
private fun Levels(
    state: EditorState,
    project: Project,
    selected: Clip?,
) {
    val text = MaterialTheme.colorScheme.onBackground

    @Composable
    fun Level(
        label: String,
        value: Double,
        max: Float,
        onChange: (Double) -> Unit,
    ) {
        Text("$label: ${(value * 100).toInt()}%", color = text)
        androidx.compose.material3.Slider(value = value.toFloat().coerceIn(0f, max), onValueChange = {
            onChange(it.toDouble())
        }, valueRange = 0f..max)
    }
    Text(stringResource(R.string.snd_levels), color = text)
    selected?.let { c ->
        Level(stringResource(R.string.snd_level_clip), c.volume, 2f) { v -> state.edit("volume") { it.setVolume(c.id, v) } }
        Text(stringResource(R.string.snd_clip_note), color = text)
    }
    project.musicBeds?.firstOrNull()?.let { b ->
        Level(
            stringResource(R.string.snd_level_music),
            b.volume,
            1f,
        ) { v ->
            state.edit("music volume") { p ->
                p.copy(
                    musicBeds =
                        p.musicBeds?.map {
                            it.copy(volume = v)
                        },
                )
            }
        }
    }
    project.soundBites?.firstOrNull()?.let { b ->
        Level(
            stringResource(R.string.snd_level_effects),
            b.volume,
            1f,
        ) { v ->
            state.edit("effects volume") { p ->
                p.copy(
                    soundBites =
                        p.soundBites?.map {
                            it.copy(volume = v)
                        },
                )
            }
        }
    }
    project.overlays.forEachIndexed { i, o ->
        Level(
            stringResource(R.string.snd_level_overlay, i + 1),
            o.volume,
            1f,
        ) { v ->
            state.edit("overlay volume") { p ->
                p.updateOverlay(o.id) {
                    it.with("volume" to JsonPrimitive(v))
                }
            }
        }
    }
}

@Composable
private fun BeatPunch(state: EditorState) {
    val context = LocalContext.current
    val text = MaterialTheme.colorScheme.onBackground
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Int?>(null) }
    var found by remember { mutableStateOf(0) }

    fun go(strength: Double) {
        busy = true
        note = null
        val start = state.project.value
        Thread({
            val out = runCatching { BeatSync.apply(context, start, strength) }.getOrNull()
            state.onMain {
                busy = false
                if (out != null && out.second > 0) {
                    found = out.second
                    note = R.string.snd_beat_done
                    state.edit("beat sync") { out.first }
                } else {
                    note = R.string.snd_beat_none
                }
            }
        }, "beats").start()
    }
    Text(stringResource(R.string.snd_beat), color = text)
    Text(stringResource(R.string.snd_beat_detail), color = text)
    if (busy) {
        Text(stringResource(R.string.snd_beat_finding), color = text)
    } else {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(stringResource(R.string.snd_beat_soft)) { go(0.25) }
            Tool(stringResource(R.string.snd_beat_mid)) { go(0.5) }
            Tool(stringResource(R.string.snd_beat_hard)) { go(1.0) }
            Tool(stringResource(R.string.snd_beat_clear), ZellaColors.CardCoral) { state.edit("beat sync") { BeatSync.clear(it) } }
        }
    }
    note?.let { Text(stringResource(it, found), color = text) }
}
