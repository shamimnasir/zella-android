package com.zellahq.app.callouts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.zellahq.app.R
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Tool
import com.zellahq.core.project.Callout
import com.zellahq.core.project.CalloutKind
import com.zellahq.core.project.Project
import com.zellahq.core.project.addCallouts
import com.zellahq.core.project.callouts
import com.zellahq.core.project.duration
import com.zellahq.core.project.latestOverlayStart
import com.zellahq.core.project.removeCallout
import com.zellahq.core.project.updateCallout
import com.zellahq.core.sensitive.SensitiveMatcher
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing
import kotlinx.serialization.json.JsonPrimitive

private val texted =
    setOf(
        CalloutKind.Label,
        CalloutKind.Keystroke,
        CalloutKind.Number,
        CalloutKind.Emoji,
        CalloutKind.StickyNote,
        CalloutKind.LeaderLine,
        CalloutKind.Popup,
    )

/** Arrows, boxes, labels, blur boxes and the rest, plus the scan that hides private text. */
@Composable
fun CalloutsPanel(
    state: EditorState,
    project: Project,
    position: Double,
) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onBackground
    val list = project.callouts.filter { it.kind != null }
    var picked by remember { mutableStateOf<String?>(null) }
    var hideWords by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf<Float?>(null) }
    var report by remember { mutableStateOf<String?>(null) }

    fun hide(pixelate: Boolean) {
        val start = state.project.value
        val matcher = SensitiveMatcher(hideWords, true)
        reading = 0f
        report = null
        Thread({
            val out =
                runCatching {
                    findSensitiveText(context, start, matcher, null, null, pixelate, { f -> state.onMain { reading = f } })
                }
            state.onMain {
                reading = null
                out.getOrNull()?.let { r ->
                    report = r.text
                    if (r.callouts.isNotEmpty()) state.edit("blur") { it.addCallouts(r.callouts) }
                }
            }
        }, "read-screen").start()
    }

    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.co_add), color = color)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            CalloutKind.entries.forEach { k ->
                Tool(k.label, ZellaColors.CardViolet) {
                    val d = 3.0
                    val c =
                        Callout.create(
                            k,
                            start = minOf(position, latestOverlayStart(project.duration, d)),
                            duration = d,
                            text = if (k in texted) k.label else null,
                        )
                    state.edit("mark up") { it.addCallouts(listOf(c)) }
                    picked = c.id
                }
            }
        }
        if (list.isEmpty()) {
            Text(stringResource(R.string.co_none), color = color)
        } else {
            Text(stringResource(R.string.co_on_video), color = color)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                list.forEach { c ->
                    Tool(c.kind!!.label + " " + com.zellahq.app.editor.mmss(c.start), if (c.id == picked) ZellaColors.CardSun else null) {
                        picked = if (picked == c.id) null else c.id
                        if (picked != null) state.seek(c.start)
                    }
                }
            }
        }
        list.firstOrNull { it.id == picked }?.let { c -> Editor(state, c) { picked = null } }

        Text(stringResource(R.string.co_hide_title), color = color)
        Text(stringResource(R.string.co_hide_detail), color = color)
        OutlinedTextField(
            value = hideWords,
            onValueChange = { hideWords = it },
            label = { Text(stringResource(R.string.co_hide_words)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (reading != null) {
            Text(stringResource(R.string.co_reading), color = color)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                Tool(stringResource(R.string.co_hide_blur), ZellaColors.CardMint) { hide(false) }
                Tool(stringResource(R.string.co_hide_pixel)) { hide(true) }
            }
        }
        report?.let { Text(it, color = color) }
    }
}

@Composable
private fun Editor(
    state: EditorState,
    c: Callout,
    onRemoved: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onBackground
    val r = c.rect

    fun setRect(
        x: Double = r[0],
        y: Double = r[1],
        w: Double = r[2],
        h: Double = r[3],
    ) {
        val k = clampRect(x, y, w, h)
        state.edit("move mark up") { p -> p.updateCallout(c.id) { it.withFields("rect" to Callout.rectJson(k[0], k[1], k[2], k[3])) } }
    }
    if (c.kind in texted) {
        OutlinedTextField(
            value = c.text.orEmpty(),
            onValueChange = {
                    v ->
                state.edit("mark up words") { p -> p.updateCallout(c.id) { it.withFields("text" to JsonPrimitive(v)) } }
            },
            label = { Text(stringResource(R.string.co_words)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(stringResource(R.string.co_left), color = color)
    Slider(value = r[0].toFloat(), onValueChange = { setRect(x = it.toDouble()) }, valueRange = 0f..1f)
    Text(stringResource(R.string.co_up), color = color)
    Slider(value = r[1].toFloat(), onValueChange = { setRect(y = it.toDouble()) }, valueRange = 0f..1f)
    Text(stringResource(R.string.co_wide), color = color)
    Slider(value = r[2].toFloat(), onValueChange = { setRect(w = it.toDouble()) }, valueRange = 0.02f..1f)
    Text(stringResource(R.string.co_tall), color = color)
    Slider(value = r[3].toFloat(), onValueChange = { setRect(h = it.toDouble()) }, valueRange = 0.02f..1f)
    Text(stringResource(R.string.co_long), color = color)
    Slider(
        value = c.duration.toFloat(),
        onValueChange = {
                v ->
            state.edit("mark up length") { p -> p.updateCallout(c.id) { it.withFields("duration" to JsonPrimitive(v.toDouble())) } }
        },
        valueRange = 0.5f..15f,
    )
    Tool(stringResource(R.string.co_remove), ZellaColors.CardCoral) {
        state.edit("remove mark up") { it.removeCallout(c.id) }
        onRemoved()
    }
}
