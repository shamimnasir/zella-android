package com.zellahq.app.export

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.zellahq.app.Entitlements
import com.zellahq.app.R
import com.zellahq.app.editor.Storage
import com.zellahq.app.editor.Tool
import com.zellahq.app.editor.mmss
import com.zellahq.core.ExportDecision
import com.zellahq.core.ExportRequest
import com.zellahq.core.PremiumGate
import com.zellahq.core.ProFeature
import com.zellahq.core.project.Aspect
import com.zellahq.core.project.Project
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing

/** Where the video is going. Each sets the shape; the iPhone stores the same preset ids. */
private enum class Preset(val id: String, val aspect: Aspect, val label: Int) {
    Tall("tiktok", Aspect.Vertical, R.string.ex_preset_tall),
    Wide("youtube", Aspect.Landscape, R.string.ex_preset_wide),
    Feed("instagramFeed", Aspect.Portrait45, R.string.ex_preset_feed),
    Square("square", Aspect.Square, R.string.ex_preset_square),
}

@Composable
fun ExportScreen(
    initial: Project,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { Storage.store(context) }
    var project by remember { mutableStateOf(initial) }
    var shortSide by remember { mutableIntStateOf(1080) }
    var fps by remember { mutableIntStateOf(30) }
    var saved by remember { mutableStateOf(false) }
    val status by ExportService.status.collectAsState()
    DisposableEffect(Unit) { onDispose { ExportService.reset() } }

    // The same question the service will ask, so the screen shows the real answer before Export is tapped.
    val decision =
        PremiumGate.authorize(
            ExportRequest(durationSeconds = project.duration, height = shortSide, fps = fps),
            Entitlements.load(context),
            System.currentTimeMillis(),
        )

    fun setPreset(p: Preset) {
        project = project.copy(aspect = p.aspect, platformPresetId = p.id, modifiedAt = isoNow())
        store.save(project, allowCollapse = true)
    }

    Column(
        Modifier.fillMaxSize().padding(ZellaSpacing.M).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(ZellaSpacing.M),
    ) {
        Tool(stringResource(R.string.ed_back)) { onBack() }
        Text(stringResource(R.string.ex_title, mmss(project.duration)), color = MaterialTheme.colorScheme.onBackground)

        Text(stringResource(R.string.ex_where), color = MaterialTheme.colorScheme.onBackground)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S),
        ) {
            Preset.entries.forEach {
                    p ->
                Tool(
                    stringResource(p.label),
                    if (project.aspect == p.aspect) ZellaColors.CardSun else null,
                ) { setPreset(p) }
            }
        }

        Text(stringResource(R.string.ex_quality), color = MaterialTheme.colorScheme.onBackground)
        Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(
                stringResource(R.string.ex_1080),
                if (shortSide == 1080) ZellaColors.CardSun else null,
            ) { shortSide = 1080 }
            Tool(
                stringResource(R.string.ex_4k),
                if (shortSide == 2160) ZellaColors.CardSun else null,
            ) { shortSide = 2160 }
        }
        Text(stringResource(R.string.ex_smoothness), color = MaterialTheme.colorScheme.onBackground)
        Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(stringResource(R.string.ex_30), if (fps == 30) ZellaColors.CardSun else null) { fps = 30 }
            Tool(stringResource(R.string.ex_60), if (fps == 60) ZellaColors.CardSun else null) { fps = 60 }
        }

        when (val s = status) {
            is ExportStatus.Running -> {
                Text(stringResource(R.string.ex_running, s.percent), color = MaterialTheme.colorScheme.onBackground)
                if (s.hot) Text(stringResource(R.string.ex_hot), color = MaterialTheme.colorScheme.onBackground)
                Tool(stringResource(R.string.ex_cancel), ZellaColors.CardCoral) {
                    context.startService(
                        Intent(context, ExportService::class.java).setAction(ExportService.ACTION_CANCEL),
                    )
                }
            }
            is ExportStatus.Done -> {
                Text(stringResource(R.string.ex_done), color = MaterialTheme.colorScheme.onBackground)
                if (!saved) {
                    Tool(
                        stringResource(R.string.ex_save),
                        ZellaColors.CardMint,
                    ) { saved = ExportService.saveToGallery(context, s.file) }
                } else {
                    Text(stringResource(R.string.ex_saved), color = MaterialTheme.colorScheme.onBackground)
                }
                Tool(stringResource(R.string.ex_share), ZellaColors.CardViolet) {
                    context.startActivity(ExportService.shareIntent(context, s.file))
                }
            }
            else -> {
                when (s) {
                    is ExportStatus.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    is ExportStatus.Cancelled ->
                        Text(
                            stringResource(R.string.ex_cancelled),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    else -> {}
                }
                if (decision is ExportDecision.Blocked) {
                    decision.needs.forEach {
                        Text(
                            stringResource(reason(it), mmss(project.duration)),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(stringResource(R.string.ex_pro_later), color = MaterialTheme.colorScheme.onBackground)
                } else {
                    Tool(stringResource(R.string.ex_start), ZellaColors.CardViolet) {
                        saved = false
                        store.save(project, allowCollapse = true)
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, ExportService::class.java)
                                .putExtra(ExportService.EXTRA_PROJECT, project.id)
                                .putExtra(ExportService.EXTRA_SHORT_SIDE, shortSide)
                                .putExtra(ExportService.EXTRA_FPS, fps),
                        )
                    }
                }
            }
        }
        // Hand the project itself to another phone or a Mac: a .zella package with its media and fonts.
        Tool(stringResource(R.string.ex_share_project)) {
            val handoff =
                java.io.File(context.getExternalFilesDir(null), "handoff").apply {
                    deleteRecursively()
                    mkdirs()
                }
            // A caption font the person imported travels in fonts/, as the iPhone does.
            val fontName = (project.captions?.extras?.get("fontName") as? kotlinx.serialization.json.JsonPrimitive)?.content
            val fontsDir = java.io.File(store.packageFor(project.id), com.zellahq.core.project.ProjectStore.FONTS)
            java.io.File(context.filesDir, "fonts").listFiles { f -> f.nameWithoutExtension == fontName }?.forEach {
                fontsDir.mkdirs()
                it.copyTo(java.io.File(fontsDir, it.name), overwrite = true)
            }
            store.exportSelfContained(project, handoff)
            val zip = handoff.listFiles { f -> f.name.endsWith(".zella.zip") }!!.first()
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, context.getString(R.string.ex_share_project)))
        }
        Text(stringResource(R.string.ex_privacy), color = MaterialTheme.colorScheme.onBackground)
    }
}

private fun reason(f: ProFeature) =
    when (f) {
        ProFeature.Length -> R.string.ex_needs_length
        ProFeature.Export4K -> R.string.ex_needs_4k
        ProFeature.Fps60 -> R.string.ex_needs_60
        else -> R.string.ex_needs_other
    }
