package com.zellahq.core

import java.io.File

/**
 * Crash recovery without a database. A running recording writes `NAME.zjournal` (a [SampleJournal]).
 * A clean stop turns it into `NAME.mp4` and deletes it. So any journal still present when nothing is
 * recording belongs to a recording that was cut short, and the next launch rebuilds its MP4.
 */
class RecordingJournal(private val dir: File) {
    companion object {
        const val SUFFIX = ".zjournal"
    }

    fun journalFor(video: File) = File(video.parentFile, video.nameWithoutExtension + SUFFIX)

    fun videoFor(journal: File) = File(journal.parentFile, journal.name.removeSuffix(SUFFIX) + ".mp4")

    /** Journals left by recordings that did not stop cleanly, oldest first. Only call when no recording runs. */
    fun interrupted(): List<File> =
        dir.listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty().sortedBy { it.lastModified() }
}
