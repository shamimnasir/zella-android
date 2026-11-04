@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.core

import com.zellahq.core.project.Beats
import com.zellahq.core.project.CaptionCue
import com.zellahq.core.project.CaptionTrack
import com.zellahq.core.project.CaptionWord
import com.zellahq.core.project.Clip
import com.zellahq.core.project.ClipTransform
import com.zellahq.core.project.ColorAdjust
import com.zellahq.core.project.Interp
import com.zellahq.core.project.KeyProperty
import com.zellahq.core.project.Keyframe
import com.zellahq.core.project.KeyframeTrack
import com.zellahq.core.project.LookTemplate
import com.zellahq.core.project.MaskRegion
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Overlay
import com.zellahq.core.project.Project
import com.zellahq.core.project.Transition
import com.zellahq.core.project.addKeyframe
import com.zellahq.core.project.cutRangesFor
import com.zellahq.core.project.cutWords
import com.zellahq.core.project.duration
import com.zellahq.core.project.effects
import com.zellahq.core.project.insertClip
import com.zellahq.core.project.insertFreeze
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.keyframes
import com.zellahq.core.project.mask
import com.zellahq.core.project.opacity
import com.zellahq.core.project.overlays
import com.zellahq.core.project.removeRanges
import com.zellahq.core.project.rippleDelete
import com.zellahq.core.project.setSpeed
import com.zellahq.core.project.split
import com.zellahq.core.project.spokenWords
import com.zellahq.core.project.transform
import com.zellahq.core.project.trimEnd
import com.zellahq.core.project.trimStart
import com.zellahq.core.project.withBackground
import com.zellahq.core.project.withColorAdjust
import com.zellahq.core.project.withEffect
import com.zellahq.core.project.withMask
import com.zellahq.core.project.withOpacity
import com.zellahq.core.project.withOverlays
import com.zellahq.core.project.withSpecial
import com.zellahq.core.project.withTransform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DepthTest {
    private val fixture =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/ios/depth.json")!!.readText()).jsonObject

    private val interps =
        mapOf(
            "hold" to Interp.Hold,
            "linear" to Interp.Linear,
            "easeIn" to Interp.EaseIn,
            "easeOut" to Interp.EaseOut,
            "easeInOut" to Interp.EaseInOut,
            "bezier" to Interp.Bezier(0.2, 0.9, 0.3, 1.0),
        )

    @Test fun curvesEqualTheIphone() {
        fixture["eased"]!!.jsonObject.forEach { (name, values) ->
            values.jsonArray.forEachIndexed { i, v ->
                val t = i * 0.05
                assertEquals("$name at $t", v.jsonPrimitive.double, interps.getValue(name).eased(t), 1e-9)
            }
        }
    }

    @Test fun curvesEncodeAsSwiftWritesThem() {
        fixture["encoded"]!!.jsonObject.forEach { (name, json) ->
            // Same shape; the bezier's numbers are compared as numbers, since Swift prints them longer.
            assertEquals(name, json.jsonObject.keys, interps.getValue(name).json().keys)
            val read = Interp.of(json)
            for (i in 0..20) assertEquals(name, interps.getValue(name).eased(i / 20.0), read.eased(i / 20.0), 1e-9)
        }
    }

    @Test fun aTrackEqualsTheIphone() {
        var tr = KeyframeTrack()
        tr = tr.upsert(Keyframe("a", 0.0, 1.0, Interp.EaseInOut))
        tr = tr.upsert(Keyframe("b", 1.0, 2.0, Interp.Linear))
        tr = tr.upsert(Keyframe("c", 2.0, 0.5, Interp.Hold))
        tr = tr.upsert(Keyframe("d", 3.0, 3.0, Interp.EaseOut))
        tr = tr.upsert(Keyframe("e", 1.01, 2.5, Interp.Linear))
        assertEquals(fixture["keysAfterUpsert"]!!.jsonArray.map { it.jsonPrimitive.double }, tr.keys.map { it.time })
        fixture["values"]!!.jsonArray.forEachIndexed { i, v ->
            val t = -0.5 + i * 0.125
            assertEquals("at $t", v.jsonPrimitive.double, tr.value(t, 9.0), 1e-9)
        }
    }

    @Test fun beatPunchesEqualTheIphone() {
        val t = Beats.scaleTrack(listOf(0.5, 1.0, 1.1, 3.0), Beats.peak(0.5), 3.1)
        assertEquals(fixture["peak"]!!.jsonPrimitive.double, Beats.peak(0.5), 1e-12)
        assertEquals(fixture["punchTimes"]!!.jsonArray.map { it.jsonPrimitive.double }, t.keys.map { it.time })
        assertEquals(fixture["punchValues"]!!.jsonArray.map { it.jsonPrimitive.double }, t.keys.map { it.value })
    }

    /** Thumps of low noise every half second over silence: each must be found, and only those. */
    @Test fun beatDetectorFindsThumps() {
        val rate = Beats.RATE
        val pcm = FloatArray(rate * 6)
        val every = rate / 2
        for (b in 1 until 12) {
            val at = b * every
            for (i in 0 until 2000) {
                pcm[at + i] += (sin(2 * PI * 80 * i / rate) * (1 - i / 2000.0)).toFloat() * 0.8f
            }
        }
        // The iPhone's threshold at the default setting catches the hits that fall squarely in a window.
        assertTrue(Beats.detect(pcm).size >= 8)
        val beats = Beats.detect(pcm, kick = 1.0, snare = 0.0, hats = 0.0)
        assertEquals(11, beats.size)
        beats.forEachIndexed { i, t -> assertEquals((i + 1) * 0.5, t, 0.1) }
        assertEquals(emptyList<Double>(), Beats.detect(FloatArray(rate * 2)))
        assertEquals(emptyList<Double>(), Beats.detect(pcm, 0.0, 0.0, 0.0))
    }

    private fun clip(
        id: String,
        d: Double = 10.0,
    ) = Clip(id, MediaRef.Absolute("file:///$id.mp4"), d, trimEnd = d)

    private fun project(vararg c: Clip) = Project(id = "P", name = "x", createdAt = isoNow(), modifiedAt = isoNow(), clips = c.toList())

    @Test fun keyframesSurviveASplitAndKeepTheirPlace() {
        val a = clip("A").addKeyframe(KeyProperty.Scale, 2.0, 1.5).addKeyframe(KeyProperty.Scale, 8.0, 2.0)
        val p = project(a).split(5.0)
        assertEquals(listOf(2.0), p.clips[0].keyframes.scale.keys.map { it.time })
        assertEquals(listOf(3.0), p.clips[1].keyframes.scale.keys.map { it.time })
    }

    @Test fun aKeyframeValueStaysInItsRange() {
        val c = clip("A").addKeyframe(KeyProperty.Opacity, 1.0, 5.0)
        assertEquals(1.0, c.keyframes.opacity.keys[0].value, 0.0)
    }

    @Test fun placeAndOpacityReadBack() {
        val c =
            clip(
                "A",
            ).withTransform(ClipTransform(0.1, -0.2, 45.0, 1.5)).withOpacity(0.5).withMask(MaskRegion(shape = "rect", invert = true))
        assertEquals(ClipTransform(0.1, -0.2, 45.0, 1.5), c.transform)
        assertEquals(0.5, c.opacity, 0.0)
        assertEquals("rect", c.mask!!.shape)
        assertTrue(c.mask!!.invert)
        assertNull(clip("B").mask)
    }

    @Test fun plainAdjustChangesNothing() {
        val c = floatArrayOf(0.2f, 0.5f, 0.8f)
        ColorAdjust().apply(c)
        assertEquals(0.2f, c[0], 1e-5f)
        assertEquals(0.5f, c[1], 1e-5f)
        assertEquals(0.8f, c[2], 1e-5f)
        val brighter = floatArrayOf(0.2f, 0.2f, 0.2f)
        ColorAdjust(exposure = 1.0).apply(brighter)
        assertEquals(0.4f, brighter[1], 1e-3f)
        val warm = floatArrayOf(0.5f, 0.5f, 0.5f)
        ColorAdjust(temperature = 9000.0).apply(warm)
        assertTrue(warm[0] > warm[2])
        val grey = floatArrayOf(0.9f, 0.1f, 0.1f)
        ColorAdjust(saturation = 0.0).apply(grey)
        assertEquals(grey[0], grey[1], 1e-4f)
    }

    private fun spoken(): Project {
        fun cue(
            s: Double,
            vararg w: String,
        ) = CaptionCue("c$s", w.mapIndexed { i, x -> CaptionWord(x, s + i, s + i + 0.8) })
        return project(clip("A", 20.0)).copy(captions = CaptionTrack(listOf(cue(1.0, "one", "two", "three"), cue(10.0, "four", "five"))))
    }

    @Test fun deletingWordsCutsTheVideoAndMovesTheRest() {
        val p = spoken()
        assertEquals(listOf("one", "two", "three", "four", "five"), p.spokenWords().map { it.text })
        assertEquals(listOf(2.0..3.8, 11.0..11.8), cutRangesFor(p.spokenWords(), setOf(1, 2, 4)))
        val out = p.cutWords(setOf(1, 2))
        assertEquals(20.0 - 1.8, out.duration, 1e-6)
        assertEquals(listOf("one", "four", "five"), out.spokenWords().map { it.text })
        assertEquals(10.0 - 1.8, out.spokenWords()[1].start, 1e-6)
    }

    @Test fun aFreezeFrameIsSilentAndMovesWhatFollows() {
        val p = spoken().insertFreeze("file:///f.mp4", 5.0, 2.0)
        assertEquals(22.0, p.duration, 1e-6)
        assertEquals(0.0, p.clips[1].volume, 0.0)
        assertEquals(12.0, p.spokenWords()[3].start, 1e-6)
    }

    @Test fun aTemplateCarriesTheRecipeAndNoMedia() {
        val p =
            project(clip("A"), clip("B"), clip("C"), clip("D")).copy(
                transitions =
                    listOf(
                        Transition("1", "A", "Spin", 0.7),
                        Transition("2", "B", "Spin", 0.7),
                        Transition("3", "C", "Warp", 0.4),
                    ),
            )
        val t = LookTemplate.capture(p, "Mine", "lofi")
        assertEquals("Spin", t.transitionType)
        assertEquals(0.7, t.transitionDuration, 0.0)
        assertEquals("lofi", t.musicMoodId)
        assertFalse(t.json().toString().contains("file:"))
        assertEquals(t.copy(raw = t.json()), LookTemplate.of(t.json()))
    }

    @Test fun overlaysMoveWithEdits() {
        val o = Overlay.create(MediaRef.Absolute("file:///o.mp4"), 3.0, 6.0, 1.0)
        val p = project(clip("A", 20.0)).withOverlays(listOf(o))
        val cut = p.removeRanges(listOf(1.0..3.0))
        assertEquals(4.0, cut.overlays[0].start, 1e-6)
    }

    @Test fun anOverlayKeepsWhatItDoesNotKnow() {
        val o = Overlay.create(MediaRef.Absolute("file:///o.mp4"), 3.0, 0.0, 1.0)
        val raw = kotlinx.serialization.json.JsonObject(o.raw + ("newField" to kotlinx.serialization.json.JsonPrimitive(7)))
        val moved = Overlay(raw).with("scale" to kotlinx.serialization.json.JsonPrimitive(0.5))
        assertEquals(0.5, moved.scale, 0.0)
        assertTrue(moved.raw.containsKey("newField"))
        assertTrue(moved.raw["source"] is kotlinx.serialization.json.JsonObject)
        assertFalse(moved.raw["id"] is JsonArray)
    }
}

/** Regressions from the 2026-11-04 audit. */
class AuditEditsTest {
    private fun clip(
        id: String,
        d: Double = 10.0,
    ) = Clip(id, MediaRef.Absolute("file:///$id.mp4"), d, trimEnd = d)

    private fun project(vararg c: Clip) = Project(id = "P", name = "x", createdAt = isoNow(), modifiedAt = isoNow(), clips = c.toList())

    private val cap = { t: Double -> CaptionTrack(listOf(CaptionCue("c", listOf(CaptionWord("w", t, t + 0.5))))) }

    @Test fun removingACutawayPutsEverythingBack() {
        val p = project(clip("A", 20.0)).copy(captions = cap(10.0))
        val with = com.zellahq.core.auto.BRoll.insert(p, listOf(com.zellahq.core.auto.BRoll.clip("file:///c.mp4", 2.5, 2.5) to 5.0))
        assertEquals(12.5, with.captions!!.cues[0].start, 1e-6)
        assertEquals(10.0, com.zellahq.core.auto.BRoll.removeAll(with).first.captions!!.cues[0].start, 1e-6)
    }

    @Test fun deletingAClipWithATransitionKeepsLaterItemsInPlace() {
        val p = project(clip("A"), clip("B"), clip("C")).copy(transitions = listOf(Transition("t", "B", "Spin", 0.5)), captions = cap(25.0))
        // C starts at 19.5. A caption 5.5 s into C must stay 5.5 s into C.
        val out = p.rippleDelete("B")
        assertEquals(10.0 + 5.5, out.captions!!.cues[0].start, 1e-6)
    }

    @Test fun aReversedClipTrimsTheRightEnd() {
        val c = clip("A").copy(reversed = true).addKeyframe(KeyProperty.Scale, 1.0, 1.5)
        val p = project(c).copy(captions = cap(1.0), titles = listOf(com.zellahq.core.project.Title("T", "t", 9.0, 0.5)))
        // A later in-point takes time off the tail of a reversed clip. The head and its keyframe stay put.
        val out = p.trimStart("A", 2.0)
        assertEquals(1.0, out.captions!!.cues[0].start, 1e-6)
        assertEquals(1.0, out.clips[0].keyframes.scale.keys[0].time, 1e-6)
        assertTrue(out.titles.isNullOrEmpty())
        // An earlier out-point takes time off its head.
        val head = p.trimEnd("A", -0.5)
        assertEquals(0.5, head.captions!!.cues[0].start, 1e-6)
        assertEquals(0.5, head.clips[0].keyframes.scale.keys[0].time, 1e-6)
    }

    @Test fun keyframesStretchWithSpeed() {
        val c = clip("A").addKeyframe(KeyProperty.Scale, 8.0, 2.0)
        val out = project(c).setSpeed("A", 2.0)
        assertEquals(4.0, out.clips[0].keyframes.scale.keys[0].time, 1e-6)
    }

    @Test fun anInsertJustAfterACutGoesAtTheCutWithItsGap() {
        val p = project(clip("A"), clip("B")).copy(captions = cap(15.0))
        val out = p.insertClip(clip("N", 3.0), 10.005)
        assertEquals(listOf("A", "N", "B"), out.clips.map { it.id })
        assertEquals(18.0, out.captions!!.cues[0].start, 1e-6)
    }

    @Test fun subtitleTimesAreLatinDigitsWhateverThePhoneLanguage() {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("bn-BD-u-nu-beng"))
        try {
            val srt = com.zellahq.core.captions.CaptionExport.text(cap(1.5), com.zellahq.core.captions.CaptionExport.Format.Srt)
            assertTrue(srt, srt.contains("00:00:01,500 --> 00:00:02,000"))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test fun aNoOpEditIsNotAnUndoStep() {
        val stack = com.zellahq.core.project.UndoStack(project(clip("A")))
        assertTrue(stack.apply(stack.current.copy(name = "y"), "rename"))
        val saved = stack.current.copy(modifiedAt = "2030-01-01T00:00:00Z")
        assertFalse(stack.apply(saved, "nothing"))
    }

    @Test fun noWordsMeansNoHookTitle() {
        assertTrue(com.zellahq.core.auto.Polish.addHookTitle(project(clip("A")), emptyList()).titles.isNullOrEmpty())
        val once = com.zellahq.core.auto.Polish.addHookTitle(project(clip("A")), listOf("hello", "there"))
        val shortened = once.copy(titles = once.titles!!.map { it.copy(duration = 1.2) })
        assertEquals(1, com.zellahq.core.auto.Polish.addHookTitle(shortened, listOf("hello")).titles!!.size)
    }

    @Test fun whatAndroidWritesHoldsEverythingTheIphoneRequires() {
        val c =
            clip("A")
                .withBackground(com.zellahq.core.project.BackgroundChoice.REMOVED)
                .withOpacity(0.5)
                .withTransform(ClipTransform(0.1))
                .withSpecial(com.zellahq.core.project.SpecialEffects(vignette = 0.2))
                .withColorAdjust(ColorAdjust(exposure = 0.1))
                .withMask(MaskRegion())
                .addKeyframe(KeyProperty.Scale, 1.0, 2.0)
        val p = project(c, clip("B").withBackground(null)).copy(zoomBlocks = listOf(com.zellahq.core.project.ZoomBlock("Z", 1.0)))
        val text = com.zellahq.core.project.ProjectJson.encode(p)
        val back = Json.parseToJsonElement(text).jsonObject
        val fx = back["clips"]!!.jsonArray[0].jsonObject["effects"]!!.jsonObject
        listOf(
            "colorAdjustments",
            "colorBoard",
            "special",
            "transform",
            "compositing",
            "gradeIntensity",
        ).forEach { assertTrue(it, it in fx) }
        assertTrue("noiseReduction" in fx["colorAdjustments"]!!.jsonObject)
        assertTrue("anchorX" in fx["transform"]!!.jsonObject)
        assertTrue("motionBlur" in fx["special"]!!.jsonObject)
        assertEquals(0.5, fx["compositing"]!!.jsonObject["opacity"]!!.jsonPrimitive.double, 0.0)
        assertFalse("an emptied effects object is dropped", "effects" in back["clips"]!!.jsonArray[1].jsonObject)
        assertTrue("motionBlur" in back["zoomBlocks"]!!.jsonArray[0].jsonObject)
        // For the iPhone's own decoder, tools/ios-conformance/read-android.swift.
        val dir = java.io.File(System.getProperty("repo.root"), "zella-core/build/ios-check").apply { mkdirs() }
        java.io.File(
            dir,
            "all-effects.json",
        ).writeText(
            text.replace(
                "\"id\":\"P\"",
                "\"id\":\"11111111-1111-1111-1111-111111111111\"",
            ).replace(
                "\"A\"",
                "\"22222222-2222-2222-2222-222222222222\"",
            ).replace("\"B\"", "\"33333333-3333-3333-3333-333333333333\"").replace("\"Z\"", "\"44444444-4444-4444-4444-444444444444\""),
        )
    }

    @Test fun anImportCannotEscapeOrReachOtherFiles() {
        val root = java.nio.file.Files.createTempDirectory("store").toFile()
        val secret = java.io.File(root, "secret.bin").apply { writeText("private") }
        val pkg = java.io.File(root, "in.zella").apply { mkdirs() }
        val store = com.zellahq.core.project.ProjectStore(java.io.File(root, "projects").apply { mkdirs() })
        val bad = project(Clip("A", MediaRef.Absolute(secret.toURI().toString()), 5.0, trimEnd = 5.0)).copy(id = "../../evil")
        java.io.File(pkg, "project.json").writeText(com.zellahq.core.project.ProjectJson.encode(bad))
        val out = store.import(pkg)
        assertFalse(out.id.contains(".."))
        assertTrue(store.packageFor(out.id).parentFile == java.io.File(root, "projects"))
        assertFalse(java.io.File(root, "evil.zella").exists())
        assertFalse("a file on this phone is not reachable by a package", store.resolve(out.clips[0].source, out.id).isFile)
    }

    @Test fun aHandOffCarriesOverlaysPicturesAndColourFiles() {
        val root = java.nio.file.Files.createTempDirectory("store").toFile()

        fun f(n: String) = java.io.File(root, n).apply { writeText(n) }
        val store = com.zellahq.core.project.ProjectStore(java.io.File(root, "projects").apply { mkdirs() })
        val c =
            Clip("A", MediaRef.Absolute(f("a.mp4").toURI().toString()), 5.0, trimEnd = 5.0)
                .withBackground(com.zellahq.core.project.BackgroundChoice("image", imagePath = f("bg.jpg").absolutePath))
                .withEffect("lut", JsonObject(mapOf("fileURL" to JsonPrimitive(f("x.cube").toURI().toString()))))
        val p =
            project(
                c,
            ).copy(
                id = "11111111-1111-1111-1111-111111111111",
            ).withOverlays(listOf(Overlay.create(MediaRef.Absolute(f("o.mp4").toURI().toString()), 3.0, 0.0, 1.0)))
        val pkg = store.exportSelfContained(p, java.io.File(root, "out").apply { mkdirs() }, zip = false)
        listOf("a.mp4", "bg.jpg", "x.cube", "o.mp4").forEach { assertTrue(it, java.io.File(pkg, "media/$it").isFile) }
        val back = com.zellahq.core.project.ProjectStore(java.io.File(root, "other").apply { mkdirs() }).import(pkg)
        assertTrue(back.overlays[0].source is MediaRef.Packaged)
        val bg = back.clips[0].effects["background"]!!.jsonObject["imagePath"]!!.jsonPrimitive.content
        assertTrue(bg, bg.contains("other") && java.io.File(bg).isFile)
    }
}
