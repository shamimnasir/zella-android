package com.zellahq.app.editor

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import com.zellahq.core.project.Project
import com.zellahq.core.project.background
import com.zellahq.core.project.transitionAfter
import com.zellahq.core.project.transitionOverlap
import java.io.File
import java.security.MessageDigest

/**
 * Renders transitions ahead of time, one short video per join, kept in the cache. Each render has two
 * inputs and nothing else, the shape Media3's compositor handles reliably, and the main video stays a
 * single track. A transition is identified by everything that shows in it, so undo and redo reuse the
 * same file and any change to either clip makes a new one.
 */
@OptIn(UnstableApi::class)
object TransitionBaker {
    private fun matteReady(
        context: Context,
        p: Project,
        c: com.zellahq.core.project.Clip,
    ) = c.background != null && com.zellahq.app.cutout.MatteBaker.isBaked(context, Storage.store(context).resolve(c.source, p.id))

    private fun key(
        context: Context,
        p: Project,
        join: Int,
        canvas: Size,
    ): String {
        val a = p.clips[join]
        val b = p.clips[join + 1]
        val t = p.transitionAfter(a.id)
        val text =
            listOf(
                canvas.width, canvas.height, t?.type, p.transitionOverlap(join), p.extras["reframe"], p.startTime0(join),
                a.source, a.trimStart, a.trimEnd, a.speed, a.reversed, a.extras["effects"],
                b.source, b.trimStart, b.trimEnd, b.speed, b.reversed, b.extras["effects"],
                // A new background shows only once the person has been found. A render from before that is stale.
                matteReady(context, p, a), matteReady(context, p, b), a.extras["speedRamp"], b.extras["speedRamp"],
            ).joinToString("|")
        return MessageDigest.getInstance("SHA-1").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    /** With a shape-following crop, the same clips at another time are a different picture. */
    private fun Project.startTime0(join: Int) =
        if (extras.containsKey("reframe")) clips.take(join + 1).sumOf { it.timelineDuration } else 0.0

    fun file(
        context: Context,
        p: Project,
        join: Int,
        canvas: Size,
    ) = File(context.cacheDir, "transitions/${key(context, p, join, canvas)}.mp4")

    fun cached(
        context: Context,
        p: Project,
        join: Int,
        canvas: Size,
    ): File? = file(context, p, join, canvas).takeIf { it.isFile && it.length() > 0 }

    /** Blocking. False when it could not be made; the join then plays as a plain cut. */
    fun bake(
        context: Context,
        builder: CompositionBuilder,
        p: Project,
        join: Int,
        canvas: Size,
    ): Boolean {
        val out = file(context, p, join, canvas)
        if (out.isFile && out.length() > 0) return true
        val composition = builder.transitionComposition(p, join, canvas) ?: return false
        out.parentFile!!.mkdirs()
        val exporter = Exporter(context, builder)
        try {
            // Plenty of bits: this picture is encoded again in the final export.
            val bitrate = (canvas.width * canvas.height * 30 * 0.25).toInt()
            val result = exporter.render(composition, ExportSettings(canvas.width.coerceAtMost(canvas.height), bitrate = bitrate), out)
            if (result !is ExportOutcome.Done) Log.w("ZellaBake", "join $join did not render: $result")
            return result is ExportOutcome.Done
        } finally {
            exporter.release()
        }
    }
}
