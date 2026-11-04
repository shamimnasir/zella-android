package com.zellahq.core.sensitive

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Port of the iPhone's ZellaCore/SensitiveText.swift. tools/ios-conformance/sensitive.swift pins the two together. */
enum class SensitiveKind(val displayName: String) {
    Query("matched text"),
    ApiKey("API key"),
    GithubToken("GitHub token"),
    AwsKey("AWS key"),
    SlackToken("Slack token"),
    Bearer("bearer token"),
    Jwt("JWT"),
    LabeledSecret("secret value"),
    SecretLabel("secret label"),
    LongHex("long hex string"),
    LongBase64("long key-like string"),
    Email("email address"),
    CardNumber("card number"),
}

data class SensitiveMatch(val kind: SensitiveKind, val location: Int, val length: Int, val matchedText: String)

class SensitiveMatcher(query: String? = null, val secrets: Boolean = true, val includeLabels: Boolean = false) {
    val query: String? = query?.trim()?.ifEmpty { null }

    fun matches(input: String): List<SensitiveMatch> {
        val text = foldConfusables(input)
        val out = ArrayList<SensitiveMatch>()

        fun add(
            kind: SensitiveKind,
            r: IntRange?,
        ) {
            if (r == null || r.isEmpty()) return
            out += SensitiveMatch(kind, r.first, r.last - r.first + 1, text.substring(r))
        }
        if (secrets) {
            LABELED.findAll(text).forEach { m ->
                val value = m.groups[2]
                if (value == null || looksLikeUiWord(value.value)) return@forEach
                add(SensitiveKind.LabeledSecret, value.range)
                if (includeLabels) add(SensitiveKind.SecretLabel, m.groups[1]?.range)
            }
            SHAPES.forEach { (kind, rule, group) ->
                rule.findAll(text).forEach { m ->
                    val g = m.groups[group] ?: return@forEach
                    if (kind == SensitiveKind.LongBase64 && !isKeyLike(g.value)) return@forEach
                    if (kind == SensitiveKind.LongHex && g.value.none { it.isDigit() }) return@forEach
                    add(kind, g.range)
                }
            }
            CARD.findAll(text).forEach { m ->
                val digits = m.value.filter { it.isDigit() }
                if (luhnValid(digits) || GROUPED.matches(m.value)) add(SensitiveKind.CardNumber, m.range)
            }
        }
        query?.let { q ->
            queryRanges(q, text).forEach { r ->
                add(SensitiveKind.Query, r)
                val tail = text.substring(r.last + 1)
                FOLLOWING.find(tail)?.groups?.get(1)?.let { v ->
                    if (!looksLikeUiWord(v.value)) {
                        add(SensitiveKind.LabeledSecret, (v.range.first + r.last + 1)..(v.range.last + r.last + 1))
                    }
                }
            }
        }
        return collapse(out)
    }

    companion object {
        private val SHAPES: List<Triple<SensitiveKind, Regex, Int>> =
            listOf(
                Triple(SensitiveKind.GithubToken, Regex("""\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"""), 0),
                Triple(
                    SensitiveKind.ApiKey,
                    Regex("""\b(?:sk|pk|rk)[-_](?:live_|test_|proj-|ant-[a-z0-9]{2,5}-|svcacct-)?[A-Za-z0-9_\-]{16,}"""),
                    0,
                ),
                Triple(SensitiveKind.ApiKey, Regex("""\bAIza[0-9A-Za-z_\-]{30,}"""), 0),
                Triple(SensitiveKind.AwsKey, Regex("""\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"""), 0),
                Triple(SensitiveKind.SlackToken, Regex("""\bxox[abposr]-[A-Za-z0-9\-]{10,}"""), 0),
                Triple(SensitiveKind.Jwt, Regex("""\beyJ[A-Za-z0-9_\-]{6,}\.[A-Za-z0-9_\-]{6,}(?:\.[A-Za-z0-9_\-]{4,})?"""), 0),
                Triple(SensitiveKind.Bearer, Regex("""(?i)\bbearer\s+([A-Za-z0-9._~+/=\-]{12,})"""), 1),
                Triple(SensitiveKind.Email, Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""), 0),
                Triple(SensitiveKind.LongHex, Regex("""\b[0-9a-fA-F]{24,}\b"""), 0),
                Triple(SensitiveKind.LongBase64, Regex("""[A-Za-z0-9+/_\-]{32,}={0,2}"""), 0),
            )
        private val LABELED =
            Regex(
                """(?i)\b((?:access|refresh|auth|bearer|session|api|secret|personal)?[ _\-]?""" +
                    """(?:token|api[ _\-]?key|apikey|secret[ _\-]?key|client[ _\-]?secret|secret|password|passwd|pwd|passcode|""" +
                    """pass[ _\-]?phrase|private[ _\-]?key|key))\b\s*(?:[:=]\s*|\s+(?=\S{12,}))["'`]?([^\s"'`]{4,})""",
            )
        private val CARD = Regex("""(?<![0-9])(?:[0-9][ \-]?){12,18}[0-9](?![0-9])""")
        private val GROUPED = Regex("""^\d{4}[ \-]\d{4}[ \-]\d{4}[ \-]\d{1,7}$""")
        private val FOLLOWING = Regex("""^\s*(?:[:=]|is\b|-)?\s*["'`]?([^\s"'`]{4,})""", RegexOption.IGNORE_CASE)
        private val WORD = Regex("""\S+""")
        private val UI_WORDS =
            setOf(
                "enter", "hidden", "required", "optional", "none", "null", "empty", "here", "show", "hide", "copy", "reset",
                "change", "forgot", "your", "the", "value", "manager", "generate", "create", "settings",
            )
        private val CONFUSABLES: Map<Char, Char> =
            (
                "аaеeоoрpсcуyхxкkмmтtнhвbіiјjѕsԁdνvκkιiαaοoρp" +
                    "АAВBЕEКKМMНHОOРPСCТTХXУYІIЈJЅSΑAΒBΕEΖZΗHΙIΚKΜMΝNΟOΡPΤTΥYΧX"
            ).chunked(2).associate { it[0] to it[1] }

        fun foldConfusables(s: String): String =
            if (s.any { it in CONFUSABLES }) {
                String(
                    CharArray(s.length) { CONFUSABLES[s[it]] ?: s[it] },
                )
            } else {
                s
            }

        private fun stripMarks(s: String): String =
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "")

        internal fun queryRanges(
            q0: String,
            text0: String,
        ): List<IntRange> {
            val q = foldConfusables(q0)
            val text = foldConfusables(text0)
            val needle = Regex(Regex.escape(q), setOf(RegexOption.IGNORE_CASE))
            val found = needle.findAll(text).map { it.range }.toList()
            if (found.isNotEmpty()) return found
            // A marked letter counts as its plain twin, when that does not change the text's length.
            val plain = stripMarks(text)
            if (plain.length == text.length) {
                val hits = Regex(Regex.escape(stripMarks(q)), RegexOption.IGNORE_CASE).findAll(plain).map { it.range }.toList()
                if (hits.isNotEmpty()) return hits
            }
            val qn = q.lowercase()
            val qWords = qn.split(Regex("\\s+")).count { it.isNotEmpty() }
            if (qn.length < 4 || qWords < 1) return emptyList()
            val allowed = max(1, qn.length / 5)
            val words = WORD.findAll(text).map { it.range }.toList()
            if (words.size < qWords) return emptyList()
            var best: Pair<IntRange, Int>? = null
            for (i in 0..(words.size - qWords)) {
                val span = words[i].first..words[i + qWords - 1].last
                val cand =
                    text.substring(
                        span,
                    ).lowercase().trim { !it.isLetterOrDigit() && it != '_' && !it.isWhitespace() && it.isPunct() }
                val d = levenshtein(cand, qn)
                if (d <= allowed && d < (best?.second ?: Int.MAX_VALUE)) best = span to d
            }
            return best?.let { listOf(it.first) }.orEmpty()
        }

        private fun Char.isPunct() = !isLetterOrDigit() && !isWhitespace()

        fun levenshtein(
            a: String,
            b: String,
        ): Int {
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                val t = prev
                prev = cur
                cur = t
            }
            return prev[b.length]
        }

        internal fun luhnValid(digits: String): Boolean {
            if (digits.length !in 13..19) return false
            var sum = 0
            digits.reversed().forEachIndexed { i, ch ->
                var d = ch.digitToIntOrNull() ?: return false
                if (i % 2 == 1) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            return sum % 10 == 0
        }

        private fun isKeyLike(s: String) = s.any { it.isDigit() } && s.any { it.isLetter() } && s.count { it.isDigit() } >= 3

        private fun looksLikeUiWord(s: String) = s.lowercase().trim { it.isPunct() } in UI_WORDS

        private fun collapse(ms: List<SensitiveMatch>): List<SensitiveMatch> {
            val sorted = ms.sortedWith(compareBy({ -it.length }, { it.kind.ordinal }))
            val kept = ArrayList<SensitiveMatch>()
            for (m in sorted) {
                val clash = kept.any { k -> m.location < k.location + k.length && k.location < m.location + m.length }
                if (!clash) kept += m
            }
            return kept.sortedBy { it.location }
        }

        /** A safe description for text sent to the language model: never the value itself. */
        fun mask(
            s: String,
            kind: SensitiveKind,
        ): String =
            when (kind) {
                SensitiveKind.Email -> "email (${s.length} chars)"
                SensitiveKind.Query, SensitiveKind.SecretLabel -> "\"$s\""
                SensitiveKind.CardNumber -> "card number ending ${s.filter { it.isDigit() }.takeLast(2)}"
                else -> {
                    val keep =
                        when (kind) {
                            SensitiveKind.GithubToken -> if (s.startsWith("github_pat_")) 11 else 4
                            SensitiveKind.ApiKey -> min(3, s.length / 6)
                            SensitiveKind.AwsKey -> 4
                            SensitiveKind.SlackToken -> 5
                            SensitiveKind.Jwt -> 3
                            else -> 0
                        }
                    "${s.take(keep)}••• (${s.length} chars)"
                }
            }

        fun maskedLine(
            text: String,
            masker: SensitiveMatcher,
        ): String {
            var out = text
            masker.matches(text).reversed().forEach { m ->
                out = out.substring(0, m.location) + "[" + mask(m.matchedText, m.kind) + "]" + out.substring(m.location + m.length)
            }
            return out
        }
    }
}

/** A rectangle, 0 to 1, top-left origin. */
data class Box(val x: Double, val y: Double, val w: Double, val h: Double) {
    val midX get() = x + w / 2
    val midY get() = y + h / 2
    val maxX get() = x + w
    val maxY get() = y + h

    fun union(o: Box): Box {
        val l = min(x, o.x)
        val t = min(y, o.y)
        return Box(l, t, max(maxX, o.maxX) - l, max(maxY, o.maxY) - t)
    }
}

data class OcrLine(val text: String, val box: Box, val confidence: Float = 1f)

data class SensitiveHit(val kind: SensitiveKind, val box: Box, val masked: String, val key: String)

data class BlurPlan(
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val start: Double,
    val end: Double,
    val kind: SensitiveKind,
    val masked: String,
    val trackTimes: List<Double>? = null,
    val trackX: List<Double>? = null,
    val trackY: List<Double>? = null,
) {
    val rect get() = Box(centerX - width / 2, centerY - height / 2, width, height)
}

object SensitiveScanner {
    /**
     * Where a match sits in a recognised line. Character boxes come from the reader when it has them;
     * otherwise a slice of the line in proportion to the characters.
     */
    fun hits(
        line: OcrLine,
        matcher: SensitiveMatcher,
        charBox: (Int, Int) -> Box? = { _, _ -> null },
    ): List<SensitiveHit> =
        matcher.matches(line.text).map { m ->
            val n = max(1, line.text.length).toDouble()
            val raw =
                charBox(m.location, m.length)
                    ?: Box(line.box.x + line.box.w * m.location / n, line.box.y, line.box.w * m.length / n, line.box.h)
            val minH = min(line.box.h, 0.12)
            val box = if (raw.h < minH * 0.6) Box(raw.x, raw.midY - minH / 2, raw.w, minH) else raw
            SensitiveHit(m.kind, box, SensitiveMatcher.mask(m.matchedText, m.kind), m.matchedText.lowercase())
        }
}

object SensitiveBlurPlanner {
    data class Sample(val time: Double, val hits: List<SensitiveHit>)

    private class Track(
        val kind: SensitiveKind,
        val masked: String,
        var key: String,
        val firstIndex: Int,
        var lastIndex: Int,
        val points: MutableList<Pair<Double, Box>>,
    )

    /**
     * Groups per-frame hits into continuous blurs. A blur runs from the sample before the first sighting
     * to the sample after the last, so a secret never shows between samples.
     */
    fun plans(
        samples0: List<Sample>,
        rangeStart: Double,
        rangeEnd: Double,
        pad: Double = 0.35,
        moveThreshold: Double = 0.012,
    ): List<BlurPlan> {
        val samples = samples0.sortedBy { it.time }
        if (samples.isEmpty()) return emptyList()
        var open = ArrayList<Track>()
        val closed = ArrayList<Track>()
        samples.forEachIndexed { i, s ->
            val still = ArrayList<Track>()
            open.forEach { if (i - it.lastIndex > 2) closed += it else still += it }
            open = still
            val taken = HashSet<Int>()
            s.hits.forEach { h ->
                var bestIdx = -1
                var bestScore = Double.MAX_VALUE
                open.forEachIndexed { k, tr ->
                    if (k in taken || tr.lastIndex >= i) return@forEachIndexed
                    val last = tr.points.last().second
                    val dx = h.box.midX - last.midX
                    val dy = h.box.midY - last.midY
                    val dist = sqrt(dx * dx + dy * dy)
                    val wr = h.box.w / max(0.0001, last.w)
                    val hr = h.box.h / max(0.0001, last.h)
                    val same = tr.key == h.key || SensitiveMatcher.levenshtein(tr.key, h.key) <= max(2, h.key.length / 8)
                    val reach = if (same) 0.25 else 0.04
                    if (dist >= reach || wr <= 0.4 || wr >= 2.5 || hr <= 0.4 || hr >= 2.5) return@forEachIndexed
                    val score = dist + (if (same) 0.0 else 0.05) + (if (tr.kind == h.kind) 0.0 else 0.02)
                    if (score < bestScore) {
                        bestScore = score
                        bestIdx = k
                    }
                }
                if (bestIdx >= 0) {
                    open[bestIdx].points += s.time to h.box
                    open[bestIdx].lastIndex = i
                    open[bestIdx].key = h.key
                    taken += bestIdx
                } else {
                    open += Track(h.kind, h.masked, h.key, i, i, mutableListOf(s.time to h.box))
                    taken += open.size - 1
                }
            }
        }
        closed += open
        val out =
            closed.map { tr ->
                val start = if (tr.firstIndex > 0) samples[tr.firstIndex - 1].time else rangeStart
                val end = if (tr.lastIndex + 1 < samples.size) samples[tr.lastIndex + 1].time else rangeEnd
                val maxH = tr.points.maxOf { it.second.h }
                val p = maxH * pad + 0.006
                val cx = tr.points.map { it.second.midX }
                val cy = tr.points.map { it.second.midY }
                val moved = cx.max() - cx.min() > moveThreshold || cy.max() - cy.min() > moveThreshold
                if (moved) {
                    val w = tr.points.maxOf { it.second.w } + 2 * p
                    val h = maxH + 2 * p
                    val times = tr.points.map { it.first }.toMutableList()
                    val xs = cx.toMutableList()
                    val ys = cy.toMutableList()
                    if (start < times[0]) {
                        times.add(0, start)
                        xs.add(0, xs[0])
                        ys.add(0, ys[0])
                    }
                    if (end > times.last()) {
                        times += end
                        xs += xs.last()
                        ys += ys.last()
                    }
                    clamp(BlurPlan(cx[0], cy[0], w, h, start, end, tr.kind, tr.masked, times, xs, ys))
                } else {
                    val areas = tr.points.map { it.second.w * it.second.h }.sorted()
                    val median = areas[areas.size / 2]
                    val kept = tr.points.filter { it.second.w * it.second.h <= median * 2.5 }.ifEmpty { tr.points }
                    val u = kept.map { it.second }.reduce { a, b -> a.union(b) }
                    val r = Box(u.x - p, u.y - p, u.w + 2 * p, u.h + 2 * p)
                    clamp(BlurPlan(r.midX, r.midY, r.w, r.h, start, end, tr.kind, tr.masked))
                }
            }
        return mergeOverlapping(out).sortedWith(compareBy({ it.start }, { it.centerY }))
    }

    private fun clamp(p0: BlurPlan): BlurPlan {
        val p = p0.copy(width = p0.width.coerceIn(0.01, 1.0), height = p0.height.coerceIn(0.01, 1.0))
        if (p.trackTimes != null) return p
        val l = max(0.0, p.centerX - p.width / 2)
        val t = max(0.0, p.centerY - p.height / 2)
        val r = min(1.0, p.centerX + p.width / 2)
        val b = min(1.0, p.centerY + p.height / 2)
        if (r <= l || b <= t) return p
        return p.copy(centerX = (l + r) / 2, centerY = (t + b) / 2, width = r - l, height = b - t)
    }

    private fun mergeOverlapping(plans: List<BlurPlan>): List<BlurPlan> {
        val out = ArrayList<BlurPlan>()
        plans.forEach { p ->
            val k =
                if (p.trackTimes != null) {
                    -1
                } else {
                    out.indexOfFirst { q ->
                        q.trackTimes == null && q.start <= p.end && p.start <= q.end && iou(q.rect, p.rect) > 0.5
                    }
                }
            if (k >= 0) {
                val u = out[k].rect.union(p.rect)
                out[k] =
                    out[k].copy(
                        centerX = u.midX,
                        centerY = u.midY,
                        width = u.w,
                        height = u.h,
                        start = min(out[k].start, p.start),
                        end = max(out[k].end, p.end),
                    )
            } else {
                out += p
            }
        }
        return out
    }

    private fun iou(
        a: Box,
        b: Box,
    ): Double {
        val w = min(a.maxX, b.maxX) - max(a.x, b.x)
        val h = min(a.maxY, b.maxY) - max(a.y, b.y)
        if (w <= 0 || h <= 0) return 0.0
        val inter = w * h
        return inter / (a.w * a.h + b.w * b.h - inter)
    }

    /** Even times across the range; long videos get a coarser step, and the caller says so. */
    fun sampleTimes(
        start: Double,
        end: Double,
        interval: Double = 0.5,
        maxFrames: Int = 80,
    ): Pair<List<Double>, Double> {
        if (end <= start) return listOf(max(0.0, start)) to interval
        val step = max(interval, (end - start) / max(1, maxFrames - 1))
        val ts = ArrayList<Double>()
        var t = start
        while (t <= end + 1e-6) {
            ts += t
            t += step
        }
        if (end - ts.last() > step * 0.25) ts += max(start, end - 0.05)
        return ts to step
    }
}
