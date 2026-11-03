package com.zellahq.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every user-visible string in the values folders must be plain words. Mirrors the iPhone BeginnerCopyTests. */
class BeginnerCopyTest {
    private val jargon =
        listOf(
            "ripple", "vad", "lufs", "codec", "mux", "transcode", "latency", "sample rate",
            "compositor", "matte", "denoise", "inference", "tensor", "shader", "bitrate", "keyframe",
        )

    private fun strings(): List<Pair<String, String>> {
        val dir = File(System.getProperty("res.dir") ?: error("res.dir not set"))
        return dir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .flatMap { it.listFiles { f -> f.name == "strings.xml" }.orEmpty().toList() }
            .flatMap { file ->
                Regex("<(?:string|item)[^>]*>([^<]*)</(?:string|item)>").findAll(file.readText())
                    .map { file.parentFile?.name.orEmpty() to it.groupValues[1] }.toList()
            }
    }

    @Test fun thereAreStringsToScan() = assertTrue(strings().size >= 5)

    @Test fun noJargonInAnyVisibleString() {
        val hits =
            strings().flatMap { (dir, s) ->
                jargon.filter { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) }
                    .map { "$dir: '$it' in \"$s\"" }
            }
        assertTrue("Jargon found: $hits", hits.isEmpty())
    }

    @Test fun theScanWouldCatchJargon() {
        assertTrue(Regex("\\bcodec\\b", RegexOption.IGNORE_CASE).containsMatchIn("Pick a Codec"))
    }

    @Test fun noPrivacyOverclaimOnPhaseZeroScreen() {
        // The home screen touches no network path yet, but copy must stay true once one exists.
        val bad = strings().filter { (_, s) -> s.contains("nothing is uploaded", ignoreCase = true) }
        assertTrue("Unqualified upload claim: $bad", bad.isEmpty())
    }
}
