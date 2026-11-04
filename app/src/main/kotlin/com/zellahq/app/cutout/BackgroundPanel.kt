package com.zellahq.app.cutout

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zellahq.app.R
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Storage
import com.zellahq.app.editor.Tool
import com.zellahq.core.project.BackgroundChoice
import com.zellahq.core.project.Project
import com.zellahq.core.project.background
import com.zellahq.core.project.newId
import com.zellahq.core.project.setBackgroundEverywhere
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing
import java.io.File

private val colours =
    listOf(
        "#FFFFFF" to R.string.bg_c_white,
        "#00B140" to R.string.bg_c_green,
        "#2B2E33" to R.string.bg_c_charcoal,
        "#3A4A63" to R.string.bg_c_slate,
        "#C9A87C" to R.string.bg_c_sand,
    )

/** Blur, colour, photo and the ready-made backgrounds. Applies to the whole video, like the iPhone's chat path. */
@Composable
fun BackgroundPanel(
    state: EditorState,
    project: Project,
) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onBackground
    val finding by state.finding.collectAsState()
    val current = project.clips.firstOrNull()?.background

    fun set(choice: BackgroundChoice?) = state.edit("background") { it.setBackgroundEverywhere(choice) }
    val photo =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val f = File(Storage.imports(context), "background-${newId()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
            set(BackgroundChoice("image", imagePath = f.absolutePath))
        }

    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.bg_detail), color = color)
        finding?.let { f ->
            Text(stringResource(R.string.bg_finding, (f * 100).toInt()), color = color)
            Tool(stringResource(R.string.bg_stop)) { state.stopFinding() }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(stringResource(R.string.bg_original), if (current == null) ZellaColors.CardSun else null) { set(null) }
            Tool(stringResource(R.string.bg_blur), if (current?.style == "blur") ZellaColors.CardSun else ZellaColors.CardViolet) {
                set(BackgroundChoice("blur", blurRadius = 18.0))
            }
            Tool(stringResource(R.string.bg_black), if (current?.style == "cutout") ZellaColors.CardSun else null) {
                set(BackgroundChoice("cutout", colorHex = "#000000"))
            }
            Tool(stringResource(R.string.bg_photo), if (current?.style == "image") ZellaColors.CardSun else null) {
                photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        if (current?.style == "blur") {
            Text(stringResource(R.string.bg_strength), color = color)
            Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                listOf(10.0 to R.string.bg_soft, 18.0 to R.string.bg_medium, 32.0 to R.string.bg_strong).forEach { (v, label) ->
                    Tool(stringResource(label), if (current.blurRadius == v) ZellaColors.CardSun else null) {
                        set(BackgroundChoice("blur", blurRadius = v))
                    }
                }
            }
        }
        Text(stringResource(R.string.bg_color), color = color)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            colours.forEach { (hex, name) ->
                Tool(stringResource(name), if (current?.style == "color" && current.colorHex == hex) ZellaColors.CardSun else null) {
                    set(BackgroundChoice("color", colorHex = hex))
                }
            }
        }
        Text(stringResource(R.string.bg_ready), color = color)
        Presets(BackgroundPainter.images, current) { set(BackgroundChoice("preset", presetId = it)) }
        Text(stringResource(R.string.bg_moving), color = color)
        Presets(BackgroundPainter.videos, current) { set(BackgroundChoice("preset", presetId = it)) }
    }
}

@Composable
private fun Presets(
    list: List<BackdropPreset>,
    current: BackgroundChoice?,
    pick: (String) -> Unit,
) {
    val color = MaterialTheme.colorScheme.onBackground
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        list.forEach { p ->
            val swatch = remember(p.id) { BackgroundPainter.swatch(p.id).asImageBitmap() }
            Column(
                Modifier.clickable(role = Role.Button, onClickLabel = p.title) { pick(p.id) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(swatch, null, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
                Text(
                    p.title,
                    color = if (current?.presetId == p.id) ZellaColors.CardSun else color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.size(width = 72.dp, height = 36.dp),
                )
            }
        }
    }
}
