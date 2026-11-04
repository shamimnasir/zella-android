package com.zellahq.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.editor.Storage
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Leaves a project in place for driving the real screens by hand or with adb. Not a check of anything. */
@RunWith(AndroidJUnit4::class)
class SeedProjectTest {
    @Test fun seed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(context.getExternalFilesDir(null), "tos.mp4")
        assumeTrue(f.isFile)
        Storage.newProject(context, f, "Demo")
    }
}
