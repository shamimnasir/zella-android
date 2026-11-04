package com.zellahq.core

import com.zellahq.core.auto.AutoEdit
import com.zellahq.core.auto.Polish
import com.zellahq.core.auto.PolishStep
import com.zellahq.core.auto.SilenceDetector
import com.zellahq.core.auto.Span
import com.zellahq.core.auto.SpokenWord
import com.zellahq.core.auto.TranscriptAnalysis
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * One-tap editing against the iPhone's own code. The fixture is what its planning functions answered
 * on a battery of inputs (tools/ios-conformance/plans-main.swift). The gates of Phase 6 are here too:
 * a second run stacks nothing, density stays under the cap, the plan shown is the plan run.
 */
class AutoEditTest {
    private val fixture: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/ios/plans.json")!!.readBytes().decodeToString()).jsonObject
    }

    private fun d(e: JsonElement) = e.jsonPrimitive.double

    private fun spans(e: JsonElement) = e.jsonArray.map { Span(d(it.jsonArray[0]), d(it.jsonArray[1])) }

    private fun assertSpans(
        label: String,
        want: List<Span>,
        got: List<Span>,
    ) {
        assertEquals("$label count", want.size, got.size)
        want.zip(got).forEach { (a, b) ->
            assertEquals(label, a.start, b.start, 1e-6)
            assertEquals(label, a.end, b.end, 1e-6)
        }
    }

    @Test fun silenceMatchesTheIphone() {
        val cases = fixture["silence"]!!.jsonArray
        assertEquals(8, cases.size)
        cases.forEachIndexed { i, c ->
            val o = c.jsonObject
            val energies = o["energies"]!!.jsonArray.map { SilenceDetector.Energy(d(it.jsonArray[0]), d(it.jsonArray[1]).toFloat()) }
            val det = SilenceDetector()
            val th = det.adaptiveThreshold(energies)
            assertEquals("threshold $i", d(o["threshold"]!!), th.toDouble(), 1e-3)
            assertSpans("silence $i", spans(o["ranges"]!!), det.collapse(energies, th))
        }
    }

    @Test fun fillersMatchTheIphone() {
        fixture["fillers"]!!.jsonArray.forEachIndexed { i, c ->
            val o = c.jsonObject
            val words =
                o["words"]!!.jsonArray.map {
                    val a = it.jsonArray
                    SpokenWord(a[0].jsonPrimitive.content, d(a[1]), d(a[2]))
                }
            assertSpans("fillers $i", spans(o["ranges"]!!), TranscriptAnalysis.allFillerRanges(words))
        }
    }

    @Test fun witnessWordsMatchTheIphone() {
        fixture["witness"]!!.jsonArray.forEachIndexed { i, c ->
            val o = c.jsonObject
            val words = o["words"]!!.jsonArray.map { SpokenWord("w", d(it.jsonArray[0]), d(it.jsonArray[1])) }
            assertSpans("witness $i", spans(o["kept"]!!), AutoEdit.subtractingWitnessWords(spans(o["ranges"]!!), words))
        }
    }

    @Test fun interruptsMatchTheIphone() {
        fixture["interrupts"]!!.jsonArray.forEachIndexed { i, c ->
            val o = c.jsonObject
            val plan = AutoEdit.interruptPlan(d(o["duration"]!!), spans(o["covered"]!!))
            val want = o["plan"]!!.jsonArray
            assertEquals("interrupts $i", want.size, plan.size)
            want.zip(plan).forEach { (a, b) ->
                assertEquals(d(a.jsonArray[0]), b.first, 1e-6)
                assertEquals(d(a.jsonArray[1]), b.second, 1e-9)
                assertEquals(d(a.jsonArray[2]), b.third, 1e-9)
            }
        }
    }

    @Test fun emphasisZoomsMatchTheIphone() {
        fixture["emphasis"]!!.jsonArray.forEachIndexed { i, c ->
            val o = c.jsonObject
            val candidates = o["candidates"]!!.jsonArray.map { d(it.jsonArray[0]) to d(it.jsonArray[1]).toInt() }
            val got =
                AutoEdit.emphasisZoomStarts(
                    candidates,
                    d(o["duration"]!!),
                    o["cuts"]!!.jsonArray.map { d(it) },
                    spans(o["occupied"]!!),
                )
            val want = o["starts"]!!.jsonArray.map { d(it) }
            assertEquals("emphasis $i", want.size, got.size)
            want.zip(got).forEach { (a, b) -> assertEquals(a, b, 1e-6) }
        }
    }

    @Test fun cutSoundsMatchTheIphone() {
        fixture["sounds"]!!.jsonArray.forEachIndexed { i, c ->
            val o = c.jsonObject
            val cuts = o["cuts"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to d(it.jsonArray[1]) }
            val got = AutoEdit.cutSoundPlan(cuts, spans(o["pauses"]!!), d(o["duration"]!!))
            val want = o["plan"]!!.jsonArray
            assertEquals("sounds $i", want.size, got.size)
            want.zip(got).forEach { (a, b) ->
                assertEquals(a.jsonArray[0].jsonPrimitive.content, b.effect)
                assertEquals(d(a.jsonArray[1]), b.time, 1e-6)
                assertEquals(d(a.jsonArray[2]), b.volume, 1e-9)
            }
        }
    }

    @Test fun zoomScaleAndMoodMatchTheIphone() {
        fixture["zoomScale"]!!.jsonArray.forEach {
            val a = it.jsonArray
            assertEquals(d(a[2]), AutoEdit.smartZoomScale(d(a[0]), d(a[1])) ?: -1.0, 1e-9)
        }
        fixture["mood"]!!.jsonArray.forEach {
            val a = it.jsonArray
            assertEquals(a[2].jsonPrimitive.content, AutoEdit.musicMood(d(a[0]), d(a[1])))
        }
    }

    private fun take(seconds: Double) =
        Project(
            id = newId(),
            name = "take",
            createdAt = isoNow(),
            modifiedAt = isoNow(),
            clips = listOf(Clip(newId(), MediaRef.Absolute("file:///take.mp4"), seconds)),
        )

    /** A pause every four seconds, and a word every half second around them. */
    private fun sample(seconds: Double): Triple<Project, List<Span>, List<SpokenWord>> {
        val silences = ArrayList<Span>()
        val words = ArrayList<SpokenWord>()
        var t = 0.0
        while (t + 4.0 < seconds) {
            var w = t
            while (w < t + 3.0) {
                words += SpokenWord(if (words.size % 9 == 8) "strength" else "and", w, w + 0.3)
                w += 0.45
            }
            silences += Span(t + 3.1, t + 3.9)
            t += 4.0
        }
        return Triple(take(seconds), silences, words)
    }

    /** The whole pure part of a run, in plan order. */
    private fun run(
        start: Project,
        silences: List<Span>,
        words: List<SpokenWord>,
    ): Project {
        var p = start
        val (cut, ranges) = Polish.cutSilences(p, silences, words)
        p = cut
        val remapped = TranscriptAnalysis.remapWords(words, ranges)
        p = Polish.cutFillers(p, remapped, "en").first
        p = Polish.addHookTitle(p, remapped.map { it.text })
        p = Polish.cutAtNaturalPauses(p, emptyList())
        p = Polish.placeDissolves(p).first
        p = Polish.addOpeningPunch(p)
        p = Polish.addEmphasisZooms(p, remapped).first
        p = Polish.applyPatternInterrupts(p).first
        p = Polish.placeCutSounds(p).first
        return Polish.capDensity(p)
    }

    @Test fun aSecondRunStacksNothing() {
        for (seconds in listOf(8.0, 30.0, 90.0, 300.0, 1200.0)) {
            val (p, silences, words) = sample(seconds)
            val once = run(p, silences, words)
            // The second run starts from the first run's result, with no pauses left to find.
            val twice = run(once, emptyList(), TranscriptAnalysis.remapWords(words, Polish.cutSilences(p, silences, words).second))
            assertEquals("titles at $seconds s", once.titles.orEmpty().size, twice.titles.orEmpty().size)
            assertEquals("transitions at $seconds s", once.transitions.orEmpty().size, twice.transitions.orEmpty().size)
            assertEquals("zooms at $seconds s", once.zoomBlocks.orEmpty().size, twice.zoomBlocks.orEmpty().size)
            assertEquals("sounds at $seconds s", once.soundBites.orEmpty().size, twice.soundBites.orEmpty().size)
            assertEquals("length at $seconds s", once.duration, twice.duration, 1e-6)
        }
    }

    @Test fun effectDensityStaysUnderTheCap() {
        for (seconds in listOf(8.0, 15.0, 30.0, 60.0, 90.0, 300.0, 1200.0)) {
            val (p, silences, words) = sample(seconds)
            val out = run(p, silences, words)
            val density = Polish.effectDensity(out)
            val n = "${out.transitions.orEmpty().size} transitions, ${out.zoomBlocks.orEmpty().size} zooms"
            println("density at $seconds s: ${"%.3f".format(density)} with $n, length ${"%.1f".format(out.duration)}")
            assertTrue("density $density at $seconds s", density <= Polish.MAX_EFFECT_DENSITY)
        }
    }

    @Test fun aQuietMicrophoneNeverEmptiesTheVideo() {
        val p = take(60.0)
        val (out, cut) = Polish.cutSilences(p, listOf(Span(0.1, 59.0)), emptyList())
        assertEquals(p, out)
        assertEquals(0, cut.size)
    }

    @Test fun fillersAreLeftAloneOutsideEnglish() {
        val p = take(10.0)
        val words = listOf(SpokenWord("um", 1.0, 1.2), SpokenWord("খুব", 2.0, 2.3), SpokenWord("খুব", 2.4, 2.7))
        assertEquals(0, Polish.cutFillers(p, words, "bn").second.size)
        assertEquals(1, Polish.cutFillers(p, words, "en").second.size.coerceAtMost(1))
    }

    @Test fun aPassThatWantsAFifthOfTheVideoDeclines() {
        val p = take(10.0)
        val words = List(20) { SpokenWord("um", it * 0.5, it * 0.5 + 0.2) }
        assertEquals(0, Polish.cutFillers(p, words, "en").second.size)
    }

    @Test fun thePlanIsOneListInOneOrder() {
        assertEquals(
            listOf(
                PolishStep.Silences,
                PolishStep.Fillers,
                PolishStep.HookAndTransitions,
                PolishStep.Voice,
                PolishStep.Framing,
                PolishStep.Music,
                PolishStep.PatternInterrupts,
            ),
            PolishStep.plan(viral = false, reframing = false),
        )
        val viral = PolishStep.plan(viral = true, reframing = true)
        assertTrue(
            PolishStep.Captions in viral && PolishStep.ColorsPop in viral && PolishStep.CutSounds in viral && PolishStep.Reframe in viral,
        )
        assertEquals(viral.sortedBy { it.ordinal }, viral)
    }

    @Test fun dissolvesStayAFewWhateverTheLength() {
        assertEquals(emptyList<Int>(), AutoEdit.dissolveJoins(0))
        assertEquals(listOf(0, 1, 2), AutoEdit.dissolveJoins(3))
        for (n in listOf(8, 9, 100, 500)) assertTrue("$n joins", AutoEdit.dissolveJoins(n).size <= AutoEdit.MAX_DISSOLVES)
        assertTrue(abs(AutoEdit.dissolveJoins(500).last() - 500) < 70)
    }
}
