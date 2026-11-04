@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import com.zellahq.app.Entitlements
import com.zellahq.app.R
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Storage
import com.zellahq.app.editor.Tool
import com.zellahq.app.looks.Looks
import com.zellahq.app.sound.Sounds
import com.zellahq.core.PremiumGate
import com.zellahq.core.project.Aspect
import com.zellahq.core.project.Clip
import com.zellahq.core.project.LookTemplate
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.MusicBed
import com.zellahq.core.project.Project
import com.zellahq.core.project.Rgba
import com.zellahq.core.project.duration
import com.zellahq.core.project.insertClip
import com.zellahq.core.project.newId
import com.zellahq.core.project.newTitle
import com.zellahq.core.project.position
import com.zellahq.core.project.setTransition
import com.zellahq.core.project.withField
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.File

/** Intro and outro cards, the iPhone's: a plain background the length of the card, and a title over it. */
object Cards {
    enum class Style(val background: Int, val text: Rgba) {
        Dark(Color.rgb(13, 13, 15), Rgba.White),
        Light(Color.rgb(245, 245, 247), Rgba(0.06, 0.06, 0.08)),
        Brand(Color.rgb(26, 23, 41), Rgba.White),
    }

    private fun canvas(a: Aspect) =
        when (a) {
            Aspect.Vertical -> 1080 to 1920
            Aspect.Square -> 1080 to 1080
            Aspect.Landscape -> 1920 to 1080
            Aspect.Portrait45 -> 1080 to 1350
        }

    fun add(
        context: Context,
        p: Project,
        atEnd: Boolean,
        style: Style,
        text: String?,
        seconds: Double = 3.0,
    ): Project? {
        val (w, h) = canvas(p.aspect)
        val name = "card-${style.name}-$w-$h-${(seconds * 100).toInt()}"
        val dir = Storage.imports(context)
        val mp4 = File(dir, "$name.mp4")
        if (!mp4.isFile || mp4.length() == 0L) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(style.background)
            val png = File(dir, "$name.png")
            png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
            val ok = Stills.encode(context, png, seconds, mp4, minOf(w, h))
            png.delete()
            if (!ok) return null
        }
        val at = if (atEnd) p.duration else 0.0
        val clip = Clip(newId(), MediaRef.Absolute(mp4.toURI().toString()), seconds, trimEnd = seconds, volume = 0.0)
        val out = p.insertClip(clip, at)
        val words = text?.trim().orEmpty()
        if (words.isEmpty()) return out
        val title =
            newTitle(
                words,
                at,
                seconds,
                mapOf("position" to position(0.5, 0.5), "fontSize" to JsonPrimitive(96.0), "fill" to style.text.json()),
            ).withField("entrance", JsonPrimitive("fade"))
        return out.copy(titles = out.titles.orEmpty() + title)
    }
}

/** Saved looks, in a file. An entry this build cannot read is kept and written back, so the file never shrinks by accident. */
class LookStore(context: Context) {
    private val file = File(context.filesDir, "templates/looks.json")

    private fun raw(): List<kotlinx.serialization.json.JsonElement> =
        runCatching { Json.parseToJsonElement(file.readText()).jsonArray.toList() }.getOrDefault(emptyList())

    fun all() = raw().mapNotNull { LookTemplate.of(it) }

    private fun write(list: List<kotlinx.serialization.json.JsonElement>) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        part.writeText(JsonArray(list).toString())
        part.renameTo(file)
    }

    fun add(t: LookTemplate) = write(raw() + t.json())

    fun remove(id: String) = write(raw().filterNot { LookTemplate.of(it)?.id == id })
}

/** Puts a saved look on a project: caption style, the grade on every clip, a transition on every cut, the music. */
fun applyLook(
    context: Context,
    p0: Project,
    t: LookTemplate,
): Project {
    var p = p0
    t.captionPreset?.let {
            pr ->
        p.captions?.let { c -> p = p.copy(captions = c.copy(extras = JsonObject(c.extras + ("preset" to JsonPrimitive(pr))))) }
    }
    t.gradeName?.let { name ->
        Looks.byName(context, name)?.let {
                look ->
            p = p.copy(clips = p.clips.map { Looks.applyLook(it, look, t.gradeIntensity.toFloat()) })
        }
    }
    t.transitionType?.let { type -> p.clips.dropLast(1).forEach { c -> p = p.setTransition(c.id, type, t.transitionDuration) } }
    t.musicMoodId?.let { id ->
        Sounds.music(context).firstOrNull { it.id == id }?.let { m ->
            val f = Sounds.file(context, m, "music")
            p = p.copy(musicBeds = listOf(MusicBed(newId(), MediaRef.Absolute(f.toURI().toString()), m.seconds, volume = 0.4, name = m.id)))
        }
    }
    return p
}

@Composable
fun SavedLooks(
    state: EditorState,
    project: Project,
) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onBackground
    val store = remember { LookStore(context) }
    var list by remember { mutableStateOf(store.all()) }
    var name by remember { mutableStateOf("") }
    val canSave = remember { PremiumGate.canSaveTemplates(Entitlements.load(context), System.currentTimeMillis()) }
    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.au_templates), color = color)
        Text(stringResource(R.string.au_template_detail), color = color)
        list.forEach { t ->
            Text(t.summary, color = color)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                Tool(
                    stringResource(R.string.au_template_use, t.name),
                    ZellaColors.CardMint,
                ) { state.edit("use look") { applyLook(context, it, t) } }
                Tool(stringResource(R.string.au_template_delete, t.name), ZellaColors.CardCoral) {
                    store.remove(t.id)
                    list = store.all()
                }
            }
        }
        if (canSave) {
            OutlinedTextField(value = name, onValueChange = {
                name = it
            }, label = { Text(stringResource(R.string.au_template_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Tool(stringResource(R.string.au_template_save)) {
                val n = name.trim()
                if (n.isNotEmpty()) {
                    store.add(LookTemplate.capture(project, n, project.musicBeds?.firstOrNull()?.name))
                    list = store.all()
                    name = ""
                }
            }
        }
    }
}
