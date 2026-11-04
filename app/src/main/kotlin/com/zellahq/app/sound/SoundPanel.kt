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
