package com.zellahq.app.editor

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import com.zellahq.app.titles.TitlesOverlay
import com.zellahq.core.project.Clip
import com.zellahq.core.project.ProjectStore
import java.io.File

/**
 * A composition builder with everything it needs from the app: fonts, looks, sounds and text. The
 * preview and the export both start here, so what plays is what saves.
 */
@OptIn(UnstableApi::class)
fun appComposition(
    context: Context,
    store: ProjectStore,
    reversedFile: (Clip) -> File? = { null },
) = CompositionBuilder(store, reversedFile).apply {
    typeface = { captionTypeface(context, it) }
    clipLook = { clip, size -> com.zellahq.app.looks.Looks.effects(context, clip, size.width, size.height) }
    voiceWindows = { com.zellahq.app.sound.Sounds.voiceWindows(it) }
    soundEffectFile = { id ->
        val s = com.zellahq.app.sound.Sounds
        s.effects(context).firstOrNull { it.id == id }?.let { s.file(context, it, "sfx") }
    }
    transitionFile = { p, join, size -> TransitionBaker.cached(context, p, join, size) }
    calloutEffects = { p, size -> com.zellahq.app.callouts.calloutEffects(p, size) }
    titleEffect = { p, size ->
        p.titles?.takeIf { it.isNotEmpty() }?.let { OverlayEffect(listOf(TitlesOverlay(context, it, size.width, size.height))) }
    }
}
