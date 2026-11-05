package com.zellahq.core

import com.zellahq.core.auto.BRoll
import com.zellahq.core.project.CaptionCue
import com.zellahq.core.project.CaptionTrack
import com.zellahq.core.project.CaptionWord
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.Title
import com.zellahq.core.project.isoNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BRollTest {
    private fun cue(
        start: Double,
        text: String,
    ) = CaptionCue("c$start", text.split(" ").mapIndexed { i, w -> CaptionWord(w, start + i * 0.2, start + i * 0.2 + 0.18) })

    @Test fun aPickedClipWithNoWordsFallsBackToTheWholeVideo() {
        // As found on the emulator: the editor had a 0.15 s clip picked, and the button reported no useful words.
        val cues = listOf(cue(1.0, "mountain river"), cue(7.0, "beautiful sunset"))
        val tiny = 0.0..0.15
        assertEquals(listOf("mountain", "beautiful"), BRoll.plan(cues, tiny, BRoll.DEFAULT_COUNT, 13.0).map { it.keyword })
    }

    @Test fun aPickedClipWithWordsStaysScoped() {
        val cues = listOf(cue(1.0, "mountain river"), cue(7.0, "beautiful sunset"))
        assertEquals(listOf("beautiful"), BRoll.plan(cues, 6.5..9.0, BRoll.DEFAULT_COUNT, 13.0).map { it.keyword })
    }

    @Test fun noUsefulWordsAnywhereStaysEmpty() {
        assertEquals(0, BRoll.plan(listOf(cue(1.0, "the and but")), 0.0..0.15, BRoll.DEFAULT_COUNT, 13.0).size)
    }

    @Test fun keywordsSkipSmallWordsAndPutLongOnesFirst() {
        val k = BRoll.keywords("The mountain was really beautiful, and we could see it from there")
        assertEquals("beautiful", k[0])
        assertEquals("mountain", k[1])
        assertFalse(k.contains("really"))
        assertFalse(k.contains("the"))
        assertEquals(emptyList<String>(), BRoll.keywords("the and but so we"))
    }

    @Test fun banglaWordsCountAsWords() {
        assertTrue(BRoll.keywords("আমি পাহাড়ে গিয়েছিলাম").isNotEmpty())
    }

    @Test fun placementsKeepApartAndNeverRepeatAWord() {
        val cues =
            listOf(
                cue(0.1, "mountain sunrise"),
                cue(1.0, "ocean waves"),
                cue(2.0, "beach holiday"),
                cue(7.0, "beach holiday"),
                cue(12.0, "forest walk"),
                cue(19.8, "ending words"),
            )
        val p = BRoll.placements(cues, 10, 20.0)
        assertEquals(listOf(1.0, 7.0, 12.0), p.map { it.time })
        assertEquals(p.size, p.map { it.keyword }.distinct().size)
    }

    @Test fun theLimitAndTheScopeAreRespected() {
        val cues = (1..8).map { cue(it * 5.0, "word$it special$it") }
        assertEquals(2, BRoll.placements(cues, 2, 60.0).size)
        val inScope = BRoll.scoped(cues, 9.0..21.0)
        assertEquals(listOf(10.0, 15.0, 20.0), inScope.map { it.start })
        assertEquals(1, BRoll.budget(4, 9.0..12.0))
        assertEquals(4, BRoll.budget(4, 0.0..100.0))
        assertEquals(4, BRoll.budget(4, null))
    }

    private fun project(): Project {
        val a = Clip("A", MediaRef.Absolute("file:///a.mp4"), 20.0, trimEnd = 20.0)
        return Project(
            id = "P",
            name = "x",
            createdAt = isoNow(),
            modifiedAt = isoNow(),
            clips = listOf(a),
            captions = CaptionTrack(listOf(cue(10.0, "later words here"))),
            titles = listOf(Title("T", "hi", 12.0, 2.0)),
        )
    }

    @Test fun anInsertMovesEverythingPinnedAfterIt() {
        val p = project()
        val card = BRoll.clip("file:///card.mp4", 2.5, 2.5)
        val card2 = BRoll.clip("file:///card2.mp4", 2.5, 2.5)
        val out = BRoll.insert(p, listOf(card to 5.0, card2 to 8.0))
        assertEquals(25.0, out.clips.sumOf { it.timelineDuration }, 1e-6)
        assertEquals(15.0, out.captions!!.cues[0].start, 1e-6)
        assertEquals(17.0, out.titles!![0].start, 1e-6)
        // The second landed at its own time, after the first pushed it along.
        assertEquals(listOf(5.0, 2.5, 3.0, 2.5, 12.0), out.clips.map { it.timelineDuration })
        assertTrue(out.clips[1].isBRoll)
        assertTrue(out.clips[3].isBRoll)
    }

    @Test fun removingNeverEmptiesTheTimeline() {
        val p = project()
        val out = BRoll.insert(p, listOf(BRoll.clip("file:///c.mp4", 2.5, 2.5) to 5.0))
        val (cleared, n) = BRoll.removeAll(out)
        assertEquals(1, n)
        assertTrue(cleared.clips.none { it.isBRoll })
        val onlyBroll = p.copy(clips = listOf(BRoll.clip("file:///c.mp4", 2.5, 2.5)))
        assertEquals(0, BRoll.removeAll(onlyBroll).second)
    }

    @Test fun pexelsPicksAReasonableFileAndSkipsOnesWithoutMp4() {
        val r =
            BRoll.parsePexels(
                """{"videos":[
                {"id":1,"duration":12,"width":3840,"height":2160,"video_files":[
                  {"file_type":"video/mp4","width":3840,"height":2160,"link":"https://x/4k.mp4"},
                  {"file_type":"video/mp4","width":1280,"height":720,"link":"https://x/hd.mp4"},
                  {"file_type":"video/mp4","width":640,"height":360,"link":"https://x/sd.mp4"}]},
                {"id":2,"duration":1,"width":10,"height":10,"video_files":[{"file_type":"video/webm","width":1280,"link":"https://x/w.webm"}]},
                {"id":3,"duration":1,"width":10,"height":10,"video_files":[{"file_type":"video/mp4","width":640,"height":360,"link":"https://x/3.mp4"}]}
                ]}""",
            )
        assertEquals(listOf("https://x/hd.mp4", "https://x/3.mp4"), r.map { it.url })
        assertEquals(12.0, r[0].duration, 0.0)
        assertEquals(2.0, r[1].duration, 0.0)
    }

    @Test fun pixabayPrefersMedium() {
        val r =
            BRoll.parsePixabay(
                """{"hits":[{"id":9,"duration":7,"videos":{
                "large":{"url":"https://x/l.mp4","width":1920,"height":1080},
                "medium":{"url":"https://x/m.mp4","width":1280,"height":720},
                "tiny":{"url":"https://x/t.mp4","width":640,"height":360}}}]}""",
            )
        assertEquals("https://x/m.mp4", r.single().url)
        assertEquals(7.0, r.single().duration, 0.0)
    }

    @Test fun theCardColoursMatchTheIphoneHash() {
        // djb2, the same answer Swift gives for these bytes.
        assertEquals(5381L, BRoll.stableHash(""))
        assertEquals(5381L * 33 + 'A'.code, BRoll.stableHash("A"))
    }
}
