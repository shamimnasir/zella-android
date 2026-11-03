package com.zellahq.core.project

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Projects on disk, one `ID.zella` folder each, the same layout as the iPhone.
 *
 * Saving is crash-safe: write a temporary file, flush it to disk, rename over the old one. A rename
 * is atomic, so a crash leaves either the old project or the new one, never half of each. The
 * previous version is kept in `snapshots/` first, and a damaged project heals from the newest
 * snapshot that still reads.
 */
class ProjectStore(private val root: File) {
    companion object {
        const val PROJECT_FILE = "project.json"
        const val SNAPSHOTS = "snapshots"
        const val MEDIA = "media"
        const val FONTS = "fonts"
        const val KEEP_SNAPSHOTS = 20
        const val SNAPSHOT_EVERY_MS = 60_000L
    }

    class CollapseRefused(val had: Int) : Exception("Refused to replace a project with $had edits by an empty one")

    /** Result of reading every project: the ones that load, and the folders that could not be read at all. */
    data class Listing(val projects: List<Pair<File, Project>>, val unreadable: List<File>)

    private val trash = File(root, ".trash")

    fun packageFor(id: String) = File(root, "$id.zella")

    fun mediaDir(id: String) = File(packageFor(id), MEDIA)

    /**
     * Saves [p]. Refuses to overwrite a project that has real edits with one that has none, unless
     * [allowCollapse]: a stale or blank write once flattened a real Mac project (12,609 bytes to 1,344).
     */
    fun save(
        p: Project,
        allowCollapse: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): File {
        val pkg = packageFor(p.id).apply { mkdirs() }
        val file = File(pkg, PROJECT_FILE)
        if (file.exists()) {
            val existing = runCatching { ProjectJson.decode(file.readText()) }.getOrNull()
            if (!allowCollapse && p.editWeight == 0 && existing != null && existing.editWeight >= 4) {
                throw CollapseRefused(existing.editWeight)
            }
            snapshot(pkg, file, nowMs)
        }
        writeAtomically(file, ProjectJson.encode(p))
        return pkg
    }

    fun load(pkg: File): Project {
        val file = File(pkg, PROJECT_FILE)
        try {
            val p = ProjectJson.decode(file.readText())
            // The folder is named by the id when it is saved. A damaged file that decodes with a
            // different id is corruption, not a different project.
            val dirId = pkg.name.removeSuffix(".zella")
            if (looksLikeId(dirId) && !dirId.equals(p.id, ignoreCase = true)) {
                throw ProjectFormatException(
                    "id does not match its folder",
                )
            }
            return p
        } catch (e: NewerFormatException) {
            throw e // never heal a newer document from an older snapshot: that destroys it
        } catch (e: Exception) {
            val healed =
                snapshots(pkg).firstNotNullOfOrNull {
                        s ->
                    runCatching { ProjectJson.decode(s.readText()) }.getOrNull()
                } ?: throw e
            writeAtomically(file, ProjectJson.encode(healed))
            return healed
        }
    }

    fun load(id: String) = load(packageFor(id))

    fun list(): Listing {
        val ok = ArrayList<Pair<File, Project>>()
        val bad = ArrayList<File>()
        root.listFiles { f -> f.isDirectory && f.name.endsWith(".zella") }.orEmpty().forEach { pkg ->
            runCatching { load(pkg) }.onSuccess { ok += pkg to it }.onFailure { bad += pkg }
        }
        return Listing(ok.sortedByDescending { it.second.modifiedAt }, bad)
    }

    /** Moves the project to a trash folder. Nothing is erased; [restore] brings it back. */
    fun delete(id: String): Boolean {
        trash.mkdirs()
        val pkg = packageFor(id)
        return pkg.exists() && pkg.renameTo(File(trash, pkg.name))
    }

    fun restore(id: String): Boolean {
        val t = File(trash, "$id.zella")
        return t.exists() && t.renameTo(packageFor(id))
    }

    /**
     * Imports a `.zella` folder or a `.zella.zip`. The project gets a fresh id if one with the same
     * id is already here, so an import never overwrites local work.
     */
    fun import(source: File): Project {
        val staging = File(root, ".import-${System.nanoTime()}").apply { mkdirs() }
        try {
            val pkg =
                if (source.isDirectory) {
                    source
                } else {
                    unzip(source, staging)
                    staging.walkTopDown().firstOrNull {
                        File(it, PROJECT_FILE).isFile
                    } ?: throw ProjectFormatException("no project.json in the package")
                }
            var p = ProjectJson.decode(File(pkg, PROJECT_FILE).readText())
            if (packageFor(p.id).exists()) p = p.copy(id = newId())
            val dest = packageFor(p.id).apply { mkdirs() }
            for (sub in listOf(MEDIA, FONTS)) File(pkg, sub).takeIf {
                it.isDirectory
            }?.copyRecursively(File(dest, sub), overwrite = true)
            writeAtomically(File(dest, PROJECT_FILE), ProjectJson.encode(p))
            return p
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * A self-contained copy for another device: every clip and music file is copied into media/
     * and pointed at there. The processed-voice file is dropped, as on the iPhone; the other app can
     * re-run it. Writes the folder and, when [zip] is set, a `.zella.zip` beside it for sharing.
     */
    fun exportSelfContained(
        p: Project,
        outDir: File,
        zip: Boolean = true,
    ): File {
        val safe = p.name.trim().replace(Regex("[/\\\\:]"), "-").ifEmpty { p.id }
        val pkg =
            File(outDir, "$safe.zella").apply {
                deleteRecursively()
                mkdirs()
            }
        val media = File(pkg, MEDIA).apply { mkdirs() }
        val used = HashSet<String>()
        val copied = HashMap<String, String>()

        fun pack(ref: MediaRef): MediaRef {
            val src = resolve(ref, p.id)
            copied[src.path]?.let { return MediaRef.Packaged(it) }
            if (!src.isFile) return ref // a missing file keeps its reference; nothing to copy
            var name = src.name
            var n = 1
            while (!used.add(name)) name = "${src.nameWithoutExtension}-${n++}.${src.extension}"
            src.copyTo(File(media, name), overwrite = true)
            copied[src.path] = name
            return MediaRef.Packaged(name)
        }
        val out =
            p
                .copy(
                    clips = p.clips.map { it.copy(source = pack(it.source)) },
                    musicBeds = p.musicBeds?.map { it.copy(source = pack(it.source)) },
                ).let { it.copy(extras = kotlinx.serialization.json.JsonObject(it.extras - "polishedAudioURL")) }
        File(packageFor(p.id), FONTS).takeIf { it.isDirectory }?.copyRecursively(File(pkg, FONTS), overwrite = true)
        writeAtomically(File(pkg, PROJECT_FILE), ProjectJson.encode(out))
        if (zip) zipFolder(pkg, File(outDir, "${pkg.name}.zip"))
        return pkg
    }

    /** Where a media reference points on this device. Packaged names never leave media/. */
    fun resolve(
        ref: MediaRef,
        projectId: String,
    ): File =
        when (ref) {
            is MediaRef.Packaged -> File(mediaDir(projectId), MediaRef.safeLeaf(ref.path))
            is MediaRef.Absolute -> File(java.net.URI(ref.url))
        }

    private fun snapshot(
        pkg: File,
        current: File,
        nowMs: Long,
    ) {
        val dir = File(pkg, SNAPSHOTS).apply { mkdirs() }
        val newest = snapshots(pkg).firstOrNull()
        // The time is in the name; a file's modified time can be touched by copying or syncing.
        val newestMs = newest?.nameWithoutExtension?.toLongOrNull()
        if (newestMs != null && nowMs - newestMs < SNAPSHOT_EVERY_MS) return
        current.copyTo(File(dir, "%013d.json".format(nowMs)), overwrite = true)
        snapshots(pkg).drop(KEEP_SNAPSHOTS).forEach { it.delete() }
    }

    /** Newest first. */
    fun snapshots(pkg: File) = File(pkg, SNAPSHOTS).listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.name }

    private fun writeAtomically(
        file: File,
        text: String,
    ) {
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(tmp).use {
            it.write(text.toByteArray())
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not save ${file.name}" }
        }
    }

    private fun looksLikeId(s: String) = s.matches(Regex("[0-9A-Fa-f]{8}-([0-9A-Fa-f]{4}-){3}[0-9A-Fa-f]{12}"))

    private fun zipFolder(
        folder: File,
        zip: File,
    ) {
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            folder.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(folder.name + "/" + f.relativeTo(folder).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    /** Unzips, refusing any entry that would land outside [dest] (a zip-slip attack). */
    private fun unzip(
        zip: File,
        dest: File,
    ) {
        val base = dest.canonicalFile
        var total = 0L
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(base, e.name).canonicalFile
                if (!out.path.startsWith(
                        base.path + File.separator,
                    )
                ) {
                    throw ProjectFormatException("unsafe path in package: ${e.name}")
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile.mkdirs()
                    out.outputStream().use { o -> total += z.copyTo(o) }
                    if (total > 8L * 1024 * 1024 * 1024) throw ProjectFormatException("package is too large")
                }
            }
        }
    }
}
