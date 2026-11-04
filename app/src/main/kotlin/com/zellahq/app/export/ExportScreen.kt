@file:Suppress("ktlint:standard:max-line-length")

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
    project: Project,
    onChange: (Project) -> Unit,
    onPlans: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { Storage.store(context) }
    var shortSide by remember { mutableIntStateOf(1080) }
    var fps by remember { mutableIntStateOf(30) }
    var saved by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(Formats.VIDEO) }
    var bitrate by remember { mutableStateOf<Int?>(null) }
    var also by remember { mutableStateOf(emptySet<com.zellahq.core.project.Aspect>()) }
    val anyStatus by ExportService.status.collectAsState()
    val mine = ExportService.statusProject == project.id
    // Another project's export is not this one's: its result stays with it.
    val status = if (mine) anyStatus else ExportStatus.Idle
    val otherRunning = !mine && anyStatus is ExportStatus.Running
    DisposableEffect(Unit) { onDispose { ExportService.leftScreen() } }
    var saveFailed by remember { mutableStateOf(false) }
    var earlierTick by remember { mutableIntStateOf(0) }
    val isPro = PremiumGate.isPro(Entitlements.load(context), System.currentTimeMillis())

    // The same question the service will ask, so the screen shows the real answer before Export is tapped.
    val decision =
        PremiumGate.authorize(
            ExportRequest(
                durationSeconds = project.duration,
                height = shortSide,
                fps = fps,
                customBitrate = bitrate != null,
                multiAspectQueue = also.isNotEmpty(),
                usesFounderGrade = com.zellahq.app.looks.Looks.usesFounderGrade(context, project),
            ),
            Entitlements.load(context),
            System.currentTimeMillis(),
        )

    fun setPreset(p: Preset) {
        onChange(project.copy(aspect = p.aspect, platformPresetId = p.id))
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

        Text(stringResource(R.string.ex_format), color = MaterialTheme.colorScheme.onBackground)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            listOf(
                Formats.VIDEO to R.string.ex_format_video,
                Formats.GIF to R.string.ex_format_gif,
                Formats.SOUND to R.string.ex_format_sound,
            ).forEach {
                    (f, label) ->
                Tool(stringResource(label), if (format == f) ZellaColors.CardSun else null) { format = f }
            }
        }
        if (format == Formats.GIF) Text(stringResource(R.string.ex_format_gif_note), color = MaterialTheme.colorScheme.onBackground)

        Text(stringResource(R.string.ex_quality), color = MaterialTheme.colorScheme.onBackground)
        Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(
                stringResource(R.string.ex_1080),
                if (shortSide == 1080) ZellaColors.CardSun else null,
            ) { shortSide = 1080 }
            Tool(
                stringResource(if (isPro) R.string.ex_4k_plain else R.string.ex_4k),
                if (shortSide == 2160) ZellaColors.CardSun else null,
            ) { shortSide = 2160 }
        }
        Text(stringResource(R.string.ex_smoothness), color = MaterialTheme.colorScheme.onBackground)
        Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(stringResource(R.string.ex_30), if (fps == 30) ZellaColors.CardSun else null) { fps = 30 }
            Tool(
                stringResource(if (isPro) R.string.ex_60_plain else R.string.ex_60),
                if (fps == 60) ZellaColors.CardSun else null,
            ) { fps = 60 }
        }

        // Pro's extras are shown to Pro only, so nothing here is a dead end for a free export.
        if (isPro) {
            Text(stringResource(R.string.ex_file_size), color = MaterialTheme.colorScheme.onBackground)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                listOf(
                    null to R.string.ex_size_auto,
                    4_000_000 to R.string.ex_size_small,
                    16_000_000 to R.string.ex_size_high,
                    40_000_000 to R.string.ex_size_highest,
                ).forEach {
                        (v, label) ->
                    Tool(stringResource(label), if (bitrate == v) ZellaColors.CardSun else null) { bitrate = v }
                }
            }
            if (format == Formats.VIDEO) {
                Text(stringResource(R.string.ex_also), color = MaterialTheme.colorScheme.onBackground)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                    listOf(
                        com.zellahq.core.project.Aspect.Vertical to R.string.au_vertical,
                        com.zellahq.core.project.Aspect.Square to R.string.au_square,
                        com.zellahq.core.project.Aspect.Landscape to R.string.au_wide,
                    )
                        .filter { it.first != project.aspect }
                        .forEach { (a, label) ->
                            Tool(
                                stringResource(label),
                                if (a in also) ZellaColors.CardSun else null,
                            ) { also = if (a in also) also - a else also + a }
                        }
                }
            }
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
                if (s.missed > 0) Text(stringResource(R.string.ex_queue_missed, s.missed), color = MaterialTheme.colorScheme.error)
                if (!saved) {
                    Tool(
                        stringResource(R.string.ex_save),
                        ZellaColors.CardMint,
                    ) {
                        val files = listOf(s.file) + s.more
                        saveFailed = false
                        Thread({
                            val ok = files.map { ExportService.saveToGallery(context, it) }.all { it }
                            saved = ok
                            saveFailed = !ok
                        }, "gallery").start()
                    }
                } else {
                    Text(stringResource(savedText(s.file)), color = MaterialTheme.colorScheme.onBackground)
                }
                if (saveFailed) Text(stringResource(R.string.ex_save_failed), color = MaterialTheme.colorScheme.error)
                Tool(stringResource(R.string.ex_share), ZellaColors.CardViolet) {
                    context.startActivity(ExportService.shareIntent(context, s.file, s.more))
                }
                Tool(stringResource(R.string.ex_again)) { ExportService.reset() }
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
                    Tool(stringResource(R.string.ex_see_plans), ZellaColors.CardViolet, onClick = onPlans)
                } else if (otherRunning) {
                    Text(stringResource(R.string.ex_other_running), color = MaterialTheme.colorScheme.onBackground)
                } else {
                    Tool(stringResource(R.string.ex_start), ZellaColors.CardViolet) {
                        saved = false
                        saveFailed = false
                        store.save(project, allowCollapse = true)
                        // The progress notice needs this on Android 13 and up. The export runs either way.
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            (context as? android.app.Activity)?.requestPermissions(
                                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                                7,
                            )
                        }
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, ExportService::class.java)
                                .putExtra(ExportService.EXTRA_PROJECT, project.id)
                                .putExtra(ExportService.EXTRA_SHORT_SIDE, shortSide)
                                .putExtra(ExportService.EXTRA_FPS, fps)
                                .putExtra(ExportService.EXTRA_FORMAT, format)
                                .putExtra(ExportService.EXTRA_BITRATE, bitrate ?: 0)
                                .putStringArrayListExtra(
                                    ExportService.EXTRA_ALSO,
                                    ArrayList(if (format == Formats.VIDEO) also.map { it.raw } else emptyList()),
                                ),
                        )
                    }
                }
            }
        }
        // A finished file is never lost by leaving the screen.
        val shown = (status as? ExportStatus.Done)?.let { listOf(it.file) + it.more }.orEmpty().toSet()
        val earlier =
            remember(
                project.name,
                anyStatus,
                earlierTick,
            ) { ExportService.earlier(context, project.name).filter { it !in shown }.take(MAX_EARLIER) }
        if (earlier.isNotEmpty()) {
            Text(stringResource(R.string.ex_earlier), color = MaterialTheme.colorScheme.onBackground)
            earlier.forEach { f ->
                Text(f.name, color = MaterialTheme.colorScheme.onBackground)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                    Tool(stringResource(R.string.ex_save)) {
                        saveFailed = false
                        Thread({ saveFailed = !ExportService.saveToGallery(context, f) }, "gallery").start()
                    }
                    Tool(stringResource(R.string.ex_share)) { context.startActivity(ExportService.shareIntent(context, f)) }
                    Tool(stringResource(R.string.ex_delete_copy)) {
                        f.delete()
                        earlierTick++
                    }
                }
            }
            Text(stringResource(R.string.ex_earlier_note), color = MaterialTheme.colorScheme.onBackground)
            if (saveFailed && status !is ExportStatus.Done) {
                Text(
                    stringResource(R.string.ex_save_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        // Hand the project itself to another phone or a Mac: a .zella package with its media and fonts.
        var packing by remember { mutableStateOf(false) }
        var packFailed by remember { mutableStateOf(false) }
        if (packing) {
            Text(stringResource(R.string.ex_packing), color = MaterialTheme.colorScheme.onBackground)
        } else {
            Tool(stringResource(R.string.ex_share_project)) {
                packing = true
                packFailed = false
                val title = context.getString(R.string.ex_share_project)
                // Copies every video in the project: minutes on a big one, and a full disk must not crash it.
                Thread({
                    val intent =
                        runCatching {
                            val handoff =
                                java.io.File(context.getExternalFilesDir(null), "handoff").apply {
                                    deleteRecursively()
                                    mkdirs()
                                }
                            // Every font the person imported that this project uses travels in fonts/, as the iPhone does.
                            val names =
                                (
                                    listOfNotNull((project.captions?.extras?.get("fontName") as? kotlinx.serialization.json.JsonPrimitive)?.content) +
                                        project.titles.orEmpty().flatMap { t ->
                                            listOfNotNull(
                                                (t.extras["fontName"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                                                ((t.extras["accent"] as? kotlinx.serialization.json.JsonObject)?.get("fontName") as? kotlinx.serialization.json.JsonPrimitive)?.content,
                                            )
                                        }
                                ).toSet()
                            val fontsDir = java.io.File(store.packageFor(project.id), com.zellahq.core.project.ProjectStore.FONTS)
                            java.io.File(context.filesDir, "fonts").listFiles { f -> f.nameWithoutExtension in names }?.forEach {
                                fontsDir.mkdirs()
                                it.copyTo(java.io.File(fontsDir, it.name), overwrite = true)
                            }
                            store.exportSelfContained(project, handoff)
                            val zip = handoff.listFiles { f -> f.name.endsWith(".zella.zip") }!!.first()
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType("application/zip")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                title,
                            )
                        }.getOrNull()
                    packing = false
                    if (intent != null) context.startActivity(intent) else packFailed = true
                }, "pack-project").start()
            }
        }
        if (packFailed) Text(stringResource(R.string.ex_pack_failed), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.ex_privacy), color = MaterialTheme.colorScheme.onBackground)
    }
}

private const val MAX_EARLIER = 5

private fun savedText(f: java.io.File) =
    when (f.extension) {
        "gif" -> R.string.ex_saved_gif
        "m4a" -> R.string.ex_saved_sound
        else -> R.string.ex_saved
    }

private fun reason(f: ProFeature) =
    when (f) {
        ProFeature.FounderGrade -> R.string.ex_needs_founder
        ProFeature.Length -> R.string.ex_needs_length
        ProFeature.Export4K -> R.string.ex_needs_4k
        ProFeature.Fps60 -> R.string.ex_needs_60
        else -> R.string.ex_needs_other
    }
