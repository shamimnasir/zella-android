package com.zellahq.core.auto

/**
 * Filler words and false starts in a transcript, the iPhone's TranscriptAnalysis. Removal deletes
 * video, so it is careful: a token longer than 0.7 s is a real word the speech model mislabelled.
 */
object FillerLexicon {
    val hard = setOf("um", "uh", "uhh", "umm", "erm", "er", "hmm", "mm")
    val bigrams = listOf(listOf("you", "know"), listOf("i", "mean"), listOf("sort", "of"), listOf("kind", "of"))

    fun normalize(s: String) = s.lowercase().trim { !it.isLetterOrDigit() }
}

object TranscriptAnalysis {
    private const val PADDING = 0.02
    private const val MAX_FILLER = 0.7

    private fun safe(
        a: Double,
        b: Double,
    ): Span? {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return if (lo < hi) Span(lo, hi) else null
    }

    fun fillerRanges(words: List<SpokenWord>): List<Span> {
        val out = ArrayList<Span>()
        val norm = words.map { FillerLexicon.normalize(it.text) }
        var i = 0
        while (i < words.size) {
            if (i + 1 < words.size && listOf(norm[i], norm[i + 1]) in FillerLexicon.bigrams &&
                words[i + 1].end - words[i].start <= MAX_FILLER * 2
            ) {
                safe(words[i].start - PADDING, words[i + 1].end + PADDING)?.let { out += it }
                i += 2
                continue
            }
            if (norm[i] in FillerLexicon.hard && words[i].end - words[i].start <= MAX_FILLER) {
                safe(words[i].start - PADDING, words[i].end + PADDING)?.let { out += it }
            }
            i++
        }
        return clampNonNegative(out)
    }

    /** "I I went": removes the earlier copies and keeps the last. */
    fun repetitionRanges(words: List<SpokenWord>): List<Span> {
        val out = ArrayList<Span>()
        val norm = words.map { FillerLexicon.normalize(it.text) }
        var i = 0
        while (i < words.size) {
            var j = i
            while (j + 1 < words.size && norm[j].isNotEmpty() && norm[j] == norm[j + 1]) j++
            if (j > i) {
                for (k in i until j) safe(words[k].start, words[k].end)?.let { out += it }
                i = j + 1
            } else {
                i++
            }
        }
        return clampNonNegative(out)
    }

    fun allFillerRanges(words: List<SpokenWord>) = merge(fillerRanges(words) + repetitionRanges(words))

    fun merge(
        ranges: List<Span>,
        mergeGap: Double = 0.05,
    ): List<Span> {
        val out = ArrayList<Span>()
        for (r in ranges.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && r.start <= last.end + mergeGap) {
                out[out.size - 1] = Span(last.start, maxOf(last.end, r.end))
            } else {
                out += r
            }
        }
        return out
    }

    private fun clampNonNegative(ranges: List<Span>) =
        ranges.mapNotNull { r ->
            val lo = maxOf(0.0, r.start)
            if (lo < r.end) Span(lo, r.end) else null
        }

    /** Word times after cuts: words inside a cut go, the rest slide back. */
    fun remapWords(
        words: List<SpokenWord>,
        removing: List<Span>,
    ): List<SpokenWord> {
        if (removing.isEmpty()) return words
        val cuts = removing.sortedBy { it.start }

        fun shift(t: Double): Double {
            var moved = 0.0
            for (c in cuts) {
                if (t >= c.end) {
                    moved += c.length
                } else if (t > c.start) {
                    moved += t - c.start
                }
            }
            return t - moved
        }
        return words.mapNotNull { w ->
            val mid = (w.start + w.end) / 2
            if (cuts.any { mid >= it.start && mid < it.end }) return@mapNotNull null
            val a = shift(w.start)
            SpokenWord(w.text, a, maxOf(a, shift(w.end)))
        }
    }
}

/**
 * Pulls a word's times in to where there is actually sound. The speech model places words well but
 * lets them run across pauses. A word's span cannot begin or end in silence, so quiet at either edge
 * comes off. If the whole span is quiet the word is left short, at its end, rather than dropped.
 */
object WordTiming {
    private const val SHORTEST = 0.06

    fun tighten(
        words: List<SpokenWord>,
        mono: FloatArray,
        rate: Int = 16_000,
        offset: Double = 0.0,
    ): List<SpokenWord> {
        if (words.isEmpty() || mono.isEmpty()) return words
        val detector = SilenceDetector()
        val e = detector.energies(mono, rate)
        if (e.isEmpty()) return words
        val threshold = detector.adaptiveThreshold(e)
        val step = 0.03

        fun quiet(t: Double): Boolean {
            val i = (t / step).toInt()
            return i !in e.indices || e[i].db < threshold
        }
        return words.map { w ->
            var s = w.start - offset
            var en = w.end - offset
            if (en <= s) return@map w
            var a = s
            while (a < en && quiet(a)) a += step
            var b = en
            while (b > a && quiet(b - step)) b -= step
            if (a >= en) {
                // All quiet: nothing to hold on to but the aligner's end.
                s = maxOf(s, en - 0.15)
            } else {
                s = a
                en = maxOf(b, a + SHORTEST).coerceAtMost(en)
            }
            SpokenWord(w.text, s + offset, en + offset)
        }
    }
}
