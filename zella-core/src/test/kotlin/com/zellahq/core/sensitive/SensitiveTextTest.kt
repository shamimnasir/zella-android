package com.zellahq.core.sensitive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iPhone's own answers, from tools/ios-conformance/sensitive.swift. */
class SensitiveTextTest {
    private val fixture =
        Json.parseToJsonElement(
            javaClass.getResource("/fixtures/ios/sensitive.json")!!.readText(),
        ).jsonObject

    private val kinds =
        mapOf(
            "query" to SensitiveKind.Query, "apiKey" to SensitiveKind.ApiKey, "githubToken" to SensitiveKind.GithubToken,
            "awsKey" to SensitiveKind.AwsKey, "slackToken" to SensitiveKind.SlackToken, "bearer" to SensitiveKind.Bearer,
            "jwt" to SensitiveKind.Jwt, "labeledSecret" to SensitiveKind.LabeledSecret, "secretLabel" to SensitiveKind.SecretLabel,
            "longHex" to SensitiveKind.LongHex, "longBase64" to SensitiveKind.LongBase64, "email" to SensitiveKind.Email,
            "cardNumber" to SensitiveKind.CardNumber,
        )

    @Test fun matchesAgreeWithTheIphone() {
        var compared = 0
        fixture["matches"]!!.jsonArray.forEach { row ->
            val o = row.jsonObject
            val q = o["query"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
            val m =
                SensitiveMatcher(q, o["secrets"]!!.jsonPrimitive.booleanOrNull!!, o["labels"]!!.jsonPrimitive.booleanOrNull!!)
            val line = o["line"]!!.jsonPrimitive.content
            val want =
                o["matches"]!!.jsonArray.map {
                    val w = it.jsonObject
                    listOf(
                        kinds[w["kind"]!!.jsonPrimitive.content],
                        w["location"]!!.jsonPrimitive.int,
                        w["length"]!!.jsonPrimitive.int,
                        w["masked"]!!.jsonPrimitive.content,
                    )
                }
            val got = m.matches(line).map { listOf(it.kind, it.location, it.length, SensitiveMatcher.mask(it.matchedText, it.kind)) }
            assertEquals("q=$q line=$line", want, got)
            compared++
        }
        assertTrue(compared > 100)
    }

    private fun box(j: JsonObject?) = j

    @Test fun plansAgreeWithTheIphone() {
        fun hit(
            key: String,
            x: Double,
            y: Double,
            w: Double = 0.2,
            h: Double = 0.03,
            kind: SensitiveKind = SensitiveKind.ApiKey,
        ) = SensitiveHit(kind, Box(x, y, w, h), "m-$key", key)

        fun s(
            n: Int,
            f: (Int) -> List<SensitiveHit>,
        ) = (0 until n).map { SensitiveBlurPlanner.Sample(it * 0.5, f(it)) }
        val a16 = "a".repeat(16)
        val z16 = "z".repeat(16)
        val scenarios =
            mapOf(
                "still" to Triple(s(6) { if (it in 2..3) listOf(hit("abc", 0.3, 0.4)) else emptyList() }, 0.0, 2.5),
                "fromStart" to Triple(s(4) { if (it <= 1) listOf(hit("abc", 0.3, 0.4)) else emptyList() }, 0.0, 1.5),
                "toEnd" to Triple(s(4) { if (it >= 2) listOf(hit("abc", 0.3, 0.4)) else emptyList() }, 0.0, 2.0),
                "scroll" to Triple(s(6) { listOf(hit("abc", 0.3, 0.8 - it * 0.1)) }, 0.0, 2.5),
                "missedOne" to Triple(s(6) { if (it == 2) emptyList() else listOf(hit("abc", 0.3, 0.4)) }, 0.0, 2.5),
                "missedThree" to Triple(s(8) { if (it in 2..4) emptyList() else listOf(hit("abc", 0.3, 0.4)) }, 0.0, 3.5),
                "newWhereOldWas" to Triple(s(4) { listOf(hit(if (it < 2) a16 else z16, 0.3, 0.4)) }, 0.0, 1.5),
                "outlier" to
                    Triple(s(5) { listOf(hit("abc", 0.3, 0.4, if (it == 2) 0.8 else 0.2, if (it == 2) 0.2 else 0.03)) }, 0.0, 2.0),
                "two" to
                    Triple(s(3) { listOf(hit("aaa", 0.1, 0.1), hit("b".repeat(12), 0.5, 0.6, kind = SensitiveKind.Email)) }, 0.0, 1.0),
                "labelAndValue" to
                    Triple(s(3) { listOf(hit(a16, 0.3, 0.4), hit("z".repeat(22), 0.302, 0.401, kind = SensitiveKind.Email)) }, 0.0, 1.0),
                "edge" to Triple(s(3) { listOf(hit("abc", 0.9, 0.97)) }, 0.0, 1.0),
            )
        fixture["plans"]!!.jsonArray.forEach { row ->
            val o = row.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val (samples, a, b) = scenarios.getValue(name)
            val got = SensitiveBlurPlanner.plans(samples, a, b)
            val want = o["plans"]!!.jsonArray.map { it.jsonObject }
            assertEquals(name, want.size, got.size)
            want.zip(got).forEach { (w, g) ->
                fun d(k: String) = w[k]!!.jsonPrimitive.double
                assertEquals("$name centerX", d("centerX"), g.centerX, 1e-9)
                assertEquals("$name centerY", d("centerY"), g.centerY, 1e-9)
                assertEquals("$name width", d("width"), g.width, 1e-9)
                assertEquals("$name height", d("height"), g.height, 1e-9)
                assertEquals("$name start", d("start"), g.start, 1e-9)
                assertEquals("$name end", d("end"), g.end, 1e-9)
                assertEquals("$name kind", kinds[w["kind"]!!.jsonPrimitive.content], g.kind)
                val times = (w["trackTimes"] as? JsonArray)?.map { it.jsonPrimitive.double }
                assertEquals("$name track", times, g.trackTimes)
            }
        }
    }

    @Test fun sampleTimesAgreeWithTheIphone() {
        fixture["times"]!!.jsonArray.forEach { row ->
            val o = row.jsonObject
            val (t, st) = SensitiveBlurPlanner.sampleTimes(o["start"]!!.jsonPrimitive.double, o["end"]!!.jsonPrimitive.double)
            assertEquals(o["step"]!!.jsonPrimitive.double, st, 1e-9)
            assertEquals(o["times"]!!.jsonArray.map { it.jsonPrimitive.double }, t)
        }
    }

    @Test fun aMaskNeverCarriesTheSecret() {
        val secret = "ghp_abcdefghijklmnopqrstuvwxyz123456"
        val line = SensitiveMatcher.maskedLine("Access token: $secret", SensitiveMatcher())
        assertFalse(line, line.contains("abcdefgh"))
        assertTrue(line, line.contains("ghp_"))
    }
}
