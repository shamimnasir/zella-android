package com.zellahq.core.auto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which words in a caption line pop, and which one emoji it gets. The iPhone asks Apple's language
 * tagger for nouns, verbs, adjectives and numbers. Android has no such tagger on the device, so this
 * uses the iPhone's stop word list plus common connecting words, then the same ranking and cap:
 * numbers first, then longer words, at most 40 percent of the line.
 */
object CaptionEmphasis {
    /** The words of [line] to emphasise, as indices. */
    fun chosen(
        line: List<String>,
        maxFraction: Double = 0.4,
    ): List<Int> {
        if (line.isEmpty()) return emptyList()
        var candidates =
            line.indices.filter {
                val w = normalize(line[it])
                w.length >= 3 && w !in STOPWORDS && w !in CONNECTING
            }
        val fallback = candidates.isEmpty()
        if (fallback) candidates = line.indices.filter { normalize(line[it]).length >= 4 && normalize(line[it]) !in STOPWORDS }
        val cap = if (fallback) minOf(1, candidates.size) else maxOf(if (candidates.isEmpty()) 0 else 1, (line.size * maxFraction).toInt())
        return candidates.sortedByDescending { rank(line[it]) }.take(cap)
    }

    fun normalize(s: String) = s.lowercase().trim { !it.isLetterOrDigit() }

    /** Numbers beat words, longer words beat shorter. */
    fun rank(word: String): Int {
        val w = normalize(word)
        return (if (w.any { it.isDigit() }) 100 else 0) + w.length
    }

    fun emoji(word: String): String? {
        val w = normalize(word)
        return MAP[w] ?: (if (w.endsWith("s")) MAP[w.dropLast(1)] else null) ?: (if (w.endsWith("es")) MAP[w.dropLast(2)] else null)
    }

    private val MAP: Map<String, String> by lazy {
        val text = CaptionEmphasis::class.java.getResourceAsStream("/caption-emoji.json")!!.use { it.readBytes().decodeToString() }
        Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    private val STOPWORDS =
        setOf(
            "the", "and", "for", "are", "but", "not", "you", "your", "with", "this", "that", "have", "has", "had", "was",
            "were", "will", "can", "just", "get", "got", "its", "our", "out", "all", "any", "one", "two", "how", "why",
            "who", "what", "when", "where", "they", "them", "then", "than", "into", "some", "more", "most", "very", "much",
            "here", "there", "about", "like", "know", "make", "want", "need", "going", "gonna", "really", "actually",
        )

    /** Stand-in for the tagger: words that connect rather than carry the meaning. */
    private val CONNECTING =
        setOf(
            "from", "over", "under", "after", "before", "because", "while", "would", "could", "should", "been", "being",
            "these", "those", "their", "there", "which", "also", "only", "even", "still", "each", "every", "such", "both",
            "either", "neither", "again", "once", "ever", "never", "always", "maybe", "perhaps", "around", "through",
            "during", "without", "within", "between", "against", "across", "toward", "towards", "upon", "onto", "off",
            "his", "her", "him", "she", "myself", "yourself", "himself", "herself", "ourselves", "themselves", "did",
            "does", "doing", "done", "may", "might", "must", "shall", "let", "lets", "too", "yes", "yeah", "okay",
        )
}
