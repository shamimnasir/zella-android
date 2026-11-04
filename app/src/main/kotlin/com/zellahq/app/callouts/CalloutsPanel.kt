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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

private fun calloutLabel(k: CalloutKind) =
    when (k) {
        CalloutKind.Arrow -> R.string.co_k_arrow
        CalloutKind.Box -> R.string.co_k_box
        CalloutKind.Ellipse -> R.string.co_k_ellipse
        CalloutKind.Label -> R.string.co_k_label
        CalloutKind.Keystroke -> R.string.co_k_keystroke
        CalloutKind.Spotlight -> R.string.co_k_spotlight
        CalloutKind.Number -> R.string.co_k_number
        CalloutKind.Blur -> R.string.co_k_blur
        CalloutKind.LeaderLine -> R.string.co_k_leader
        CalloutKind.Popup -> R.string.co_k_popup
        CalloutKind.Magnifier -> R.string.co_k_magnifier
        CalloutKind.Highlight -> R.string.co_k_highlight
        CalloutKind.Underline -> R.string.co_k_underline
        CalloutKind.Pixelate -> R.string.co_k_pixelate
        CalloutKind.Emoji -> R.string.co_k_emoji
        CalloutKind.StickyNote -> R.string.co_k_sticky
        CalloutKind.ScribbleCircle -> R.string.co_k_scribble
        CalloutKind.Timer -> R.string.co_k_timer
        CalloutKind.Glitch -> R.string.co_k_glitch
    }

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
                if (out.isFailure) report = context.getString(R.string.co_hide_failed)
                out.getOrNull()?.let { r ->
                    report =
                        if (r.failed) {
                            context.getString(R.string.co_hide_failed)
                        } else if (r.callouts.isEmpty()) {
                            context.getString(R.string.co_hide_none)
                        } else {
                            context.getString(R.string.co_hide_done, r.callouts.size) +
                                if (r.text.contains("Stopped early")) " " + context.getString(R.string.co_hide_short) else ""
                        }
                    if (r.callouts.isNotEmpty()) state.applyResult(start, "blur", start.addCallouts(r.callouts))
                }
            }
        }, "read-screen").start()
    }

    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.co_add), color = color)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            CalloutKind.entries.forEach { k ->
                Tool(stringResource(calloutLabel(k)), ZellaColors.CardViolet) {
                    val d = 3.0
                    val c =
                        Callout.create(
                            k,
                            start = minOf(position, latestOverlayStart(project.duration, d)),
                            duration = d,
                            text = if (k in texted) context.getString(calloutLabel(k)) else null,
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
                    Tool(
                        stringResource(calloutLabel(c.kind!!)) + " " + com.zellahq.app.editor.mmss(c.start),
                        if (c.id == picked) ZellaColors.CardSun else null,
                    ) {
                        picked = if (picked == c.id) null else c.id
                        if (picked != null) state.seek(c.start)
                    }
                }
            }
        }
        list.firstOrNull { it.id == picked }?.let { c -> Editor(state, c) { picked = null } }

        Text(stringResource(R.string.co_hide_title), color = color)
        Text(stringResource(R.string.co_hide_detail), color = color)
        // Above the buttons: below them it landed out of sight, and the tap looked as if it did nothing.
        report?.let { Text(it, color = color) }
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
    val leftName = stringResource(R.string.co_left)
    Text(leftName, color = color)
    Slider(
        modifier = Modifier.semantics { contentDescription = leftName },
        value = r[0].toFloat(),
        onValueChange = { setRect(x = it.toDouble()) },
        valueRange = 0f..1f,
    )
    val upName = stringResource(R.string.co_up)
    Text(upName, color = color)
    Slider(
        modifier = Modifier.semantics { contentDescription = upName },
        value = r[1].toFloat(),
        onValueChange = { setRect(y = it.toDouble()) },
        valueRange = 0f..1f,
    )
    val wideName = stringResource(R.string.co_wide)
    Text(wideName, color = color)
    Slider(
        modifier = Modifier.semantics { contentDescription = wideName },
        value = r[2].toFloat(),
        onValueChange = { setRect(w = it.toDouble()) },
        valueRange = 0.02f..1f,
    )
    val tallName = stringResource(R.string.co_tall)
    Text(tallName, color = color)
    Slider(
        modifier = Modifier.semantics { contentDescription = tallName },
        value = r[3].toFloat(),
        onValueChange = { setRect(h = it.toDouble()) },
        valueRange = 0.02f..1f,
    )
    val longName = stringResource(R.string.co_long)
    Text(longName, color = color)
    Slider(
        modifier = Modifier.semantics { contentDescription = longName },
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
