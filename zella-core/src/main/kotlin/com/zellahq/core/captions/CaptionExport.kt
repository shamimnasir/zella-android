package com.zellahq.core.captions

import com.zellahq.core.project.CaptionTrack
import kotlin.math.floor

/** Subtitle files, the same text the iPhone's CaptionExport writes. */
object CaptionExport {
    enum class Format(val raw: String, val extension: String) {
        Srt("srt", "srt"),
        Vtt("vtt", "vtt"),
        Csv("csv", "csv"),
    }

    private fun code(
        t: Double,
        comma: Boolean,
    ): String {
        if (!t.isFinite() || t < 0 || t >= 86_400) return "00:00:00" + (if (comma) "," else ".") + "000"
        val s = t.toInt()
        val ms = ((t - floor(t)) * 1000).toInt()
        return "%02d:%02d:%02d%s%03d".format(s / 3600, s % 3600 / 60, s % 60, if (comma) "," else ".", ms)
    }

    fun text(
        track: CaptionTrack,
        format: Format,
    ): String =
        when (format) {
            Format.Srt ->
                track.cues.mapIndexed {
                        i,
                        c,
                    ->
                    "${i + 1}\n${code(c.start, true)} --> ${code(c.end, true)}\n${c.text}\n"
                }.joinToString("\n")
            Format.Vtt ->
                "WEBVTT\n\n" + track.cues.joinToString("\n\n") { "${code(it.start, false)} --> ${code(it.end, false)}\n${it.text}" } + "\n"
            Format.Csv ->
                "start,end,text\n" +
                    track.cues.joinToString("\n") {
                        "%.3f,%.3f,\"%s\"".format(java.util.Locale.ROOT, it.start, it.end, it.text.replace("\"", "\"\""))
                    } + "\n"
        }
}
