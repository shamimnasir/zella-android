@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zellahq.app.depth

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import com.zellahq.app.editor.ExportOutcome
import com.zellahq.app.editor.ExportSettings
import com.zellahq.app.editor.Exporter
import com.zellahq.app.editor.Storage
import com.zellahq.app.editor.appComposition
import com.zellahq.core.project.Project
import com.zellahq.core.project.clipAt
import com.zellahq.core.project.newId
import com.zellahq.core.project.sourceTimeAt
import java.io.File

/** Short silent videos made from one still picture. */
object Stills {
    const val FREEZE_SECONDS = 2.0

    fun encode(
        context: Context,
        png: File,
        seconds: Double,
        out: File,
        shortSide: Int,
    ): Boolean {
        val builder = appComposition(context, Storage.store(context))
        val item =
            EditedMediaItem
                .Builder(MediaItem.Builder().setUri(Uri.fromFile(png)).setImageDurationMs((seconds * 1000).toLong()).build())
                .setFrameRate(30)
                .build()
        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(item).build()
        return Exporter(
            context,
            builder,
        ).render(Composition.Builder(sequence).build(), ExportSettings(shortSide), out) is ExportOutcome.Done
    }

    /** The frame at timeline time [at], as a short video. The clip's look is applied later, like any other clip's. */
    fun freeze(
        context: Context,
        p: Project,
        at: Double,
    ): File? {
        val (clip, local) = p.clipAt(at) ?: return null
        val src = Storage.store(context).resolve(clip.source, p.id)
        if (!src.isFile) return null
        val m = MediaMetadataRetriever()
        val frame =
            try {
                m.setDataSource(src.absolutePath)
                m.getFrameAtTime((clip.sourceTimeAt(local) * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
            } finally {
                m.release()
            } ?: return null
        val name = "freeze-${newId()}"
        val png = File(Storage.imports(context), "$name.png")
        val out = File(Storage.imports(context), "$name.mp4")
        png.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val short = minOf(frame.width, frame.height)
        frame.recycle()
        val ok = encode(context, png, FREEZE_SECONDS, out, short)
        png.delete()
        return out.takeIf { ok }
    }
}
