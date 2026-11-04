package com.zellahq.app.captions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zellahq.app.R
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Tool
import com.zellahq.app.editor.mmss
import com.zellahq.core.captions.CaptionBuilder
import com.zellahq.core.project.CaptionCue
import com.zellahq.core.project.Project
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private fun languageLabel(code: String?) =
    when (code) {
        null -> R.string.cap_lang_detect
        "en" -> R.string.cap_lang_en
        "bn" -> R.string.cap_lang_bn
        "hi" -> R.string.cap_lang_hi
        "es" -> R.string.cap_lang_es
        "fr" -> R.string.cap_lang_fr
        "vi" -> R.string.cap_lang_vi
        "ar" -> R.string.cap_lang_ar
        "ur" -> R.string.cap_lang_ur
        "zh" -> R.string.cap_lang_zh
        else -> R.string.cap_lang_detect
    }

/** Everything about captions in one panel under the editor's tools. */
@Composable
fun CaptionsPanel(
    state: EditorState,
    project: Project,
) {
    val progress by state.captioning.collectAsState()
    val note by state.captionNote.collectAsState()
    // The saved language is the starting choice, so it survives closing the editor.
    var language by rememberSaveable(project.id) { mutableStateOf(project.captionLanguage) }
    var english by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CaptionCue?>(null) }
    val track = project.captions
    val text = MaterialTheme.colorScheme.onBackground

    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        progress?.let {
            Text(stringResource(R.string.cap_making, (it * 100).toInt()), color = text)
            Tool(stringResource(R.string.cap_stop), ZellaColors.CardCoral) { state.stopCaptions() }
            return@Column
        }
        when (val n = note) {
            is EditorState.CaptionNote.Made -> Text(stringResource(R.string.cap_made, n.words), color = text)
            is EditorState.CaptionNote.NoWords -> Text(stringResource(R.string.cap_no_words), color = text)
            EditorState.CaptionNote.NoSound -> Text(stringResource(R.string.cap_no_sound), color = text)
            EditorState.CaptionNote.Stopped -> Text(stringResource(R.string.cap_stopped), color = text)
            is EditorState.CaptionNote.Failed -> Text(n.message, color = MaterialTheme.colorScheme.error)
            null -> {}
        }

        if (track == null || track.cues.isEmpty()) {
            Text(stringResource(R.string.cap_language_question), color = text)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                (listOf<String?>(null) + CaptionEngine.LANGUAGES).forEach { code ->
                    Tool(stringResource(languageLabel(code)), if (language == code) ZellaColors.CardSun else null) { language = code }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Switch) { english = !english },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.cap_translate), color = text, modifier = Modifier.weight(1f))
                Switch(checked = english, onCheckedChange = null)
            }
            Tool(stringResource(R.string.cap_make), ZellaColors.CardViolet) { state.makeCaptions(language, english && language != "en") }
            Text(stringResource(R.string.cap_privacy), color = text)
            return@Column
        }

        val preset = track.extras["preset"]?.jsonPrimitive?.contentOrNull ?: CaptionBuilder.DEFAULT_PRESET
        val hidden = track.extras["hidden"]?.jsonPrimitive?.contentOrNull == "true"
        Text(stringResource(R.string.cap_style), color = text)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            CaptionBuilder.PRESETS.forEach { name ->
                Tool(name, if (name == preset) ZellaColors.CardSun else null) {
                    state.edit(
                        "caption style",
                    ) { p ->
                        p.copy(
                            captions = p.captions?.copy(extras = JsonObject(p.captions!!.extras + ("preset" to JsonPrimitive(name)))),
                        )
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(stringResource(if (hidden) R.string.cap_show else R.string.cap_hide)) {
                state.edit("caption hide") { p ->
                    val t = p.captions ?: return@edit p
                    val ex = if (hidden) t.extras - "hidden" else t.extras + ("hidden" to JsonPrimitive(true))
                    p.copy(captions = t.copy(extras = JsonObject(ex)))
                }
            }
            Tool(stringResource(R.string.cap_again)) { state.makeCaptions(project.captionLanguage, false) }
            Tool(stringResource(R.string.cap_remove), ZellaColors.CardCoral) { state.edit("captions") { it.copy(captions = null) } }
        }
        FontRow(state, track)
        BetterModels()

        Text(stringResource(R.string.cap_edit_hint), color = text)
        // The cues as a list of words: tap one to fix it.
        Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            track.cues.take(200).forEach { cue ->
                Text(
                    "${mmss(cue.start)}  ${cue.text}",
                    color = text,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { editing = cue }
                            .padding(vertical = ZellaSpacing.S),
                )
            }
        }
    }

    editing?.let { cue ->
        var value by remember(cue.id) { mutableStateOf(cue.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.cap_edit_title)) },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }) },
            confirmButton = {
                Tool(stringResource(R.string.cap_save)) {
                    state.edit("caption text") { p ->
                        val t = p.captions ?: return@edit p
                        val fixed = CaptionBuilder.editCue(cue, value)
                        p.copy(captions = t.copy(cues = t.cues.mapNotNull { if (it.id == cue.id) fixed else it }))
                    }
                    editing = null
                }
            },
            dismissButton = { Tool(stringResource(R.string.cap_cancel)) { editing = null } },
        )
    }
}

/** Caption font: standard bold, or one the person imported. */
@Composable
private fun FontRow(
    state: EditorState,
    track: com.zellahq.core.project.CaptionTrack,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val text = MaterialTheme.colorScheme.onBackground
    var fonts by remember { mutableStateOf(FontLibrary.names(context)) }
    var fontNote by remember { mutableStateOf<Int?>(null) }
    val fontName = track.extras["fontName"]?.jsonPrimitive?.contentOrNull

    fun setFont(name: String?) =
        state.edit("caption font") { p ->
            val t = p.captions ?: return@edit p
            val ex = if (name == null) t.extras - "fontName" else t.extras + ("fontName" to JsonPrimitive(name))
            p.copy(captions = t.copy(extras = JsonObject(ex)))
        }
    val picker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            when (val r = FontLibrary.import(context, uri)) {
                is FontLibrary.Result.Added -> {
                    fonts = FontLibrary.names(context)
                    fontNote = null
                    setFont(r.name)
                }
                FontLibrary.Result.NotAFont -> fontNote = R.string.cap_font_not_font
                FontLibrary.Result.TooBig -> fontNote = R.string.cap_font_too_big
            }
        }
    Text(stringResource(R.string.cap_font), color = text)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Tool(stringResource(R.string.cap_font_standard), if (fontName == null) ZellaColors.CardSun else null) { setFont(null) }
        fonts.forEach { f -> Tool(f, if (f == fontName) ZellaColors.CardSun else null) { setFont(f) } }
        Tool(stringResource(R.string.cap_font_add)) {
            picker.launch(arrayOf("font/*", "application/octet-stream", "application/x-font-ttf", "application/font-sfnt"))
        }
    }
    fontNote?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    Text(stringResource(R.string.cap_font_private), color = text)
}

/**
 * Bigger caption models to download, shown only when Zella's model list can be reached. Labelled as
 * a download, sized, and asked about on mobile data.
 */
@Composable
private fun BetterModels() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var models by remember { mutableStateOf<List<ModelDownloader.Model>?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var askData by remember { mutableStateOf<ModelDownloader.Model?>(null) }
    var failed by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("captions", android.content.Context.MODE_PRIVATE) }
    var chosen by remember { mutableStateOf(prefs.getString("model", null)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        models = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ModelDownloader.catalog() }
    }
    val list = models?.takeIf { it.isNotEmpty() } ?: return
    val text = MaterialTheme.colorScheme.onBackground

    fun start(m: ModelDownloader.Model) {
        failed = false
        progress = 0f
        Thread {
            val f = ModelDownloader.download(context, m, { p -> progress = p })
            progress = null
            if (f == null) {
                failed = true
            } else {
                prefs.edit().putString("model", m.id).apply()
                chosen = m.id
            }
        }.start()
    }
    Text(stringResource(R.string.cap_better_title), color = text)
    progress?.let { Text(stringResource(R.string.cap_better_progress, (it * 100).toInt()), color = text) }
    if (failed) Text(stringResource(R.string.cap_better_failed), color = MaterialTheme.colorScheme.error)
    list.forEach { m ->
        when {
            ModelDownloader.installed(context, m) ->
                Tool(stringResource(R.string.cap_better_use, m.name), if (chosen == m.id) ZellaColors.CardSun else null) {
                    prefs.edit().putString("model", m.id).apply()
                    chosen = m.id
                }
            progress == null ->
                Tool(stringResource(R.string.cap_better_download, m.name, (m.bytes / 1_000_000).toInt())) {
                    if (ModelDownloader.unmetered(context)) start(m) else askData = m
                }
        }
    }
    Text(stringResource(R.string.cap_better_privacy), color = text)
    askData?.let { m ->
        AlertDialog(
            onDismissRequest = { askData = null },
            title = { Text(stringResource(R.string.cap_better_data_title)) },
            text = { Text(stringResource(R.string.cap_better_data_body, (m.bytes / 1_000_000).toInt())) },
            confirmButton = {
                Tool(stringResource(R.string.cap_better_data_yes)) {
                    askData = null
                    start(m)
                }
            },
            dismissButton = { Tool(stringResource(R.string.cap_cancel)) { askData = null } },
        )
    }
}
