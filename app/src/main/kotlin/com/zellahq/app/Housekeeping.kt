package com.zellahq.app

import android.content.Context
import com.zellahq.app.editor.Storage
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.overlays
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tidies up once per run of the app, before anything can be working: partial files from a kill, temporary
 * renders, and the rendered-ahead caches, which can always be made again. Files in imports that no project
 * uses go after a week. Nothing runs at all if any project could not be read, since its files may be in there.
 */
object Housekeeping {
    private val done = AtomicBoolean(false)
    private const val CACHE_LIMIT = 1024L * 1024 * 1024
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    fun once(context: Context) {
        if (!done.compareAndSet(false, true)) return
        runCatching { run(context) }.onFailure { android.util.Log.w("ZellaHousekeeping", "tidy", it) }
    }

    private fun run(context: Context) {
        val cache = context.cacheDir
        val dirs =
            listOf(
                File(cache, "mattes"),
                File(cache, "transitions"),
                File(cache, "reversed"),
                Storage.exports(context),
                Storage.imports(context),
            )
        dirs.forEach { d -> d.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { it.delete() } }
        cache.listFiles { f -> f.name.startsWith("reading-") || f.name.startsWith("import-") }?.forEach { it.delete() }
        listOf(File(cache, "mattes"), File(cache, "transitions"), File(cache, "reversed")).forEach { trim(it) }
        tidyImports(context)
    }

    /** Oldest first, down to the limit. Each of these is made again when next needed. */
    private fun trim(dir: File) {
        val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_LIMIT) break
            total -= f.length()
            f.delete()
        }
    }

    private fun tidyImports(context: Context) {
        val store = Storage.store(context)
        val listing = store.list()
        if (listing.unreadable.isNotEmpty()) return
        val used = HashSet<String>()
        for ((_, p) in listing.projects) {
            fun note(r: MediaRef?) {
                if (r != null) runCatching { used += store.resolve(r, p.id).name }
            }
            p.clips.forEach { note(it.source) }
            p.musicBeds?.forEach { note(it.source) }
            p.overlays.forEach { note(it.source) }
            // A background picture is stored by path. Any mention keeps the file.
            used += Regex("background-[0-9A-Fa-f-]+\\.jpg").findAll(p.clips.joinToString { it.extras.toString() }).map { it.value }
        }
        // A deleted project waits in the trash and can come back, so its text counts as a mention too.
        val trash = File(Storage.projects(context).parentFile, "projects/.trash")
        val mentions =
            trash.walkTopDown().filter {
                it.name == "project.json"
            }.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        val now = System.currentTimeMillis()
        Storage.imports(context).listFiles()?.forEach { f ->
            if (f.isFile && f.name !in used && now - f.lastModified() > WEEK_MS && !mentions.contains(f.name)) f.delete()
        }
    }
}
