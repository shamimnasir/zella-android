package com.zellahq.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectStoreTest {
    private fun tmp() = java.nio.file.Files.createTempDirectory("ps").toFile()

    private fun project(
        clips: Int = 3,
        id: String = newId(),
    ) = Project(
        id = id,
        name = "Test",
        createdAt = isoNow(),
        modifiedAt = isoNow(),
        clips = List(clips) { Clip(newId(), MediaRef.Packaged("c$it.mp4"), 5.0) },
        titles = listOf(Title(newId(), "a", 0.0, 1.0), Title(newId(), "b", 1.0, 1.0)),
    )

    @Test fun savesAndLoads() {
        val s = ProjectStore(tmp())
        val p = project()
        s.save(p)
        assertEquals(p, s.load(p.id))
    }

    @Test fun refusesToFlattenARealProject() {
        val s = ProjectStore(tmp())
        val p = project()
        s.save(p)
        try {
            s.save(p.copy(clips = emptyList(), titles = null))
            fail("should refuse")
        } catch (e: ProjectStore.CollapseRefused) {
            assertEquals(5, e.had)
        }
        assertEquals(p, s.load(p.id))
        s.save(p.copy(clips = emptyList(), titles = null), allowCollapse = true) // an explicit "clear" still works
    }

    @Test fun damagedFileHealsFromTheNewestSnapshot() {
        val s = ProjectStore(tmp())
        val p = project()
        s.save(p, nowMs = 1_000)
        val p2 = p.copy(name = "Second")
        s.save(p2, nowMs = 100_000) // snapshots p
        File(s.packageFor(p.id), ProjectStore.PROJECT_FILE).writeText("{ broken")
        assertEquals(p, s.load(p.id))
    }

    @Test fun anIdThatDoesNotMatchItsFolderIsTreatedAsDamage() {
        val s = ProjectStore(tmp())
        val p = project()
        s.save(p, nowMs = 1_000)
        s.save(p.copy(name = "x"), nowMs = 100_000)
        val f = File(s.packageFor(p.id), ProjectStore.PROJECT_FILE)
        f.writeText(f.readText().replace(p.id, newId()))
        assertEquals(p.id, s.load(p.id).id)
    }

    @Test fun aNewerFormatIsNeverHealedAway() {
        val s = ProjectStore(tmp())
        val p = project()
        s.save(p, nowMs = 1_000)
        s.save(p.copy(name = "x"), nowMs = 100_000)
        val f = File(s.packageFor(p.id), ProjectStore.PROJECT_FILE)
        val newer = f.readText().replace("\"formatVersion\":1", "\"formatVersion\":9")
        f.writeText(newer)
        try {
            s.load(p.id)
            fail("should refuse")
        } catch (e: NewerFormatException) {
            assertEquals(9, e.found)
        }
        assertEquals(newer, f.readText()) // untouched
    }

    @Test fun snapshotsAreRateLimitedAndCapped() {
        val s = ProjectStore(tmp())
        val p = project()
        s.save(p, nowMs = 0)
        repeat(30) { s.save(p.copy(name = "v$it"), nowMs = (it + 1) * 61_000L) }
        s.save(p.copy(name = "soon"), nowMs = 30 * 61_000L + 5_000) // under a minute: no new snapshot
        assertEquals(ProjectStore.KEEP_SNAPSHOTS, s.snapshots(s.packageFor(p.id)).size)
    }

    @Test fun deleteIsRecoverable() {
        val s = ProjectStore(tmp())
        val p = project()
        s.save(p)
        assertTrue(s.delete(p.id))
        assertTrue(s.list().projects.isEmpty())
        assertTrue(s.restore(p.id))
        assertEquals(p, s.load(p.id))
    }

    @Test fun exportThenImportCarriesTheMedia() {
        val root = tmp()
        val s = ProjectStore(root)
        val media = File(tmp(), "clip.mp4").apply { writeText("video bytes") }
        val p = project(clips = 0).copy(clips = listOf(Clip(newId(), MediaRef.Absolute(media.toURI().toString()), 5.0)))
        s.save(p)
        val out = tmp()
        val pkg = s.exportSelfContained(p, out)
        val exported = ProjectJson.decode(File(pkg, "project.json").readText())
        assertEquals(MediaRef.Packaged("clip.mp4"), exported.clips.single().source)
        assertTrue(File(out, "Test.zella.zip").isFile)

        val other = ProjectStore(tmp())
        val imported = other.import(File(out, "Test.zella.zip"))
        assertEquals("video bytes", other.resolve(imported.clips.single().source, imported.id).readText())
    }

    @Test fun importingTwiceNeverOverwrites() {
        val s = ProjectStore(tmp())
        val p = project()
        s.save(p)
        val pkg = s.exportSelfContained(p, tmp(), zip = false)
        val again = s.import(pkg)
        assertNotEquals(p.id, again.id)
        assertEquals(2, s.list().projects.size)
    }

    @Test fun zipSlipIsRefused() {
        val evil = File(tmp(), "evil.zella.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../../outside.txt"))
            z.write("x".toByteArray())
            z.closeEntry()
        }
        val root = tmp()
        try {
            ProjectStore(root).import(evil)
            fail("should refuse")
        } catch (e: ProjectFormatException) {
            assertTrue(e.message!!.contains("unsafe"))
        }
        assertFalse(File(root.parentFile.parentFile, "outside.txt").exists())
    }
}
