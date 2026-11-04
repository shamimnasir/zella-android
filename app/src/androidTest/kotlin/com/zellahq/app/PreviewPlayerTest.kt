package com.zellahq.app

import android.graphics.Color
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.recording.PatternSource
import com.zellahq.app.recording.Recorder
import com.zellahq.app.recording.RecorderConfig
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.setTransition
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The preview path. ExportRenderTest passed while the preview crashed on open, because the preview
 * player needs things export does not (each source's length). This loads the same kind of project
 * into the real CompositionPlayer and requires it to become ready with the timeline's length.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PreviewPlayerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun previewBecomesReadyWithTheTimelineLength() {
        val dir = File(context.cacheDir, "preview").apply { mkdirs() }
        val files =
            listOf(Color.RED, Color.GREEN).mapIndexed { i, c ->
                File(dir, "p$i.mp4").also { f ->
                    if (!f.isFile) {
                        val r = Recorder(f, RecorderConfig(720, 1280), PatternSource(30, background = c), emptyMap())
                        r.start()
                        Thread.sleep(2_300)
                        r.stop()
                    }
                }
            }
        val clips = files.map { Clip(newId(), MediaRef.Absolute(it.toURI().toString()), 2.0, trimEnd = 2.0) }
        val p =
            Project(id = newId(), name = "preview", createdAt = isoNow(), modifiedAt = isoNow(), clips = clips)
                .setTransition(clips[0].id, "Cross Dissolve", 0.5)
        val composition =
            com.zellahq.app.editor.appComposition(
                context,
                ProjectStore(File(context.filesDir, "projects-test")),
            ).build(p).composition

        val ready = CountDownLatch(1)
        var durationMs = -1L
        var error: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = com.zellahq.app.editor.previewPlayer(context)
            player.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            durationMs = player.duration
                            ready.countDown()
                        }
                    }

                    override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                        error = e
                        ready.countDown()
                    }
                },
            )
            runCatching {
                player.setComposition(composition)
                player.prepare()
            }.onFailure {
                error = it
                ready.countDown()
            }
        }
        assertTrue("preview never became ready", ready.await(20, TimeUnit.SECONDS))
        assertTrue("preview failed: $error", error == null)
        assertTrue("preview length $durationMs ms vs ${p.duration * 1000} ms", abs(durationMs - p.duration * 1000) < 50)
    }

    /** Every shape of project the editor can build must prepare and play in the preview, with no crash. */
    @Test fun aProjectWithMusicEffectsAndTitlesPlaysInThePreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "preview").apply { mkdirs() }
        val picture =
            File(dir, "music-clip.mp4").also { f ->
                if (!f.isFile) {
                    val r = Recorder(f, RecorderConfig(720, 1280), PatternSource(30, background = Color.BLUE), emptyMap())
                    r.start()
                    Thread.sleep(3_300)
                    r.stop()
                }
            }
        val music = com.zellahq.app.sound.Sounds.music(context).first()
        val musicFile = com.zellahq.app.sound.Sounds.file(context, music, "music")
        val bed =
            com.zellahq.core.project.MusicBed(
                newId(),
                MediaRef.Absolute(musicFile.toURI().toString()),
                music.seconds,
                volume = 0.4,
                duck = true,
                name = music.id,
            )
        val bite = com.zellahq.core.project.SoundBite(newId(), "Whoosh", 0.5)
        val look = com.zellahq.app.titles.TitleLooks.all(context).first()
        val clipA = Clip(newId(), MediaRef.Absolute(picture.toURI().toString()), 3.0, trimEnd = 3.0)
        val clipB = Clip(newId(), MediaRef.Absolute(picture.toURI().toString()), 3.0, trimEnd = 3.0)
        val plain =
            Project(
                id = newId(),
                name = "with sound",
                createdAt = isoNow(),
                modifiedAt = isoNow(),
                clips = listOf(clipA, clipB),
                musicBeds = listOf(bed),
                soundBites = listOf(bite),
                titles = listOf(look.title("Hello", 0.2)),
            )
        for ((name, p) in listOf("cuts" to plain, "transition" to plain.setTransition(clipA.id, "Spin", 0.5))) {
            val composition =
                com.zellahq.app.editor.appComposition(context, ProjectStore(File(context.filesDir, "projects-test"))).build(p).composition
            val ready = CountDownLatch(1)
            val played = CountDownLatch(1)
            var error: Throwable? = null
            lateinit var player: androidx.media3.transformer.CompositionPlayer
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player = com.zellahq.app.editor.previewPlayer(context)
                player.addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) ready.countDown()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) played.countDown()
                        }

                        override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                            error = e
                            ready.countDown()
                            played.countDown()
                        }
                    },
                )
                player.setComposition(composition)
                player.prepare()
            }
            assertTrue("$name: never ready", ready.await(30, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player.play() }
            assertTrue("$name: never played", played.await(20, TimeUnit.SECONDS))
            Thread.sleep(1_500)
            var at = 0L
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                at = player.currentPosition
                player.release()
            }
            assertTrue("$name failed: $error", error == null)
            assertTrue("$name: playhead stayed at $at ms", at > 200)
        }
    }
}
