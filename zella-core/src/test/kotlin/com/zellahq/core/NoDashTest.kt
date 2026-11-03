package com.zellahq.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** House rule: no em dashes or en dashes anywhere in source, copy or docs. */
class NoDashTest {
    private val banned = listOf(0x2014.toChar(), 0x2013.toChar())
    private val skipDirs = setOf("build", ".gradle", ".git", ".idea", "third_party")
    private val exts = setOf("kt", "kts", "xml", "md", "toml", "yml", "yaml", "properties", "txt", "json")

    @Test fun noEmOrEnDashesInTheRepo() {
        val root = File(System.getProperty("repo.root") ?: error("repo.root not set"))
        val hits =
            root.walkTopDown()
                .onEnter { it.name !in skipDirs }
                .filter { it.isFile && it.extension in exts }
                .flatMap { f ->
                    f.readLines().withIndex().filter { (_, l) -> banned.any { it in l } }
                        .map { "${f.relativeTo(root)}:${it.index + 1}" }
                }.toList()
        assertTrue("Dashes found: $hits", hits.isEmpty())
    }

    @Test fun theScanWouldCatchADash() {
        val sample = "a " + 0x2014.toChar() + " b"
        assertTrue(banned.any { it in sample })
    }
}
