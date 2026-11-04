package com.zellahq.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.captions.ModelDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The optional model download against a real HTTP server on the developer's Mac (10.0.2.2:8765,
 * started by hand, see BUILDLOG). Parts are checked, a half-downloaded part is picked up again, and
 * the joined file must match the model's checksum. Skipped meaningfully only when the server is off.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloaderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun downloadsChecksAndResumes() {
        val list = ModelDownloader.catalog("http://10.0.2.2:8765/manifest.json")
        assertNotNull("the test server on the Mac is not running", list)
        val m = list!!.single()
        ModelDownloader.file(context, m).delete()
        // A first part left half done, as if the phone lost signal.
        val parts = File(context.filesDir, "models/${m.id}.parts").apply { mkdirs() }
        File("${parts.path}/0").writeBytes(ByteArray((m.parts[0].bytes / 2).toInt()))
        val t0 = System.currentTimeMillis()
        val f = ModelDownloader.download(context, m, {})
        Log.i("ModelDownloaderTest", "downloaded ${m.bytes} bytes in ${System.currentTimeMillis() - t0} ms")
        assertNotNull(f)
        assertEquals(m.sha256, ModelDownloader.sha(f!!))
        assertTrue(ModelDownloader.installed(context, m))
        assertTrue("parts are cleaned up", !parts.exists())
    }

    @Test fun aWrongChecksumIsRefused() {
        val m = ModelDownloader.catalog("http://10.0.2.2:8765/manifest.json")!!.single()
        val bad = m.copy(id = "bad", sha256 = "0".repeat(64))
        assertNull(ModelDownloader.download(context, bad, {}))
        assertTrue(!ModelDownloader.file(context, bad).exists())
    }

    @Test fun anUnreachableListOffersNothing() {
        assertNull(ModelDownloader.catalog("http://10.0.2.2:1/nothing.json"))
    }
}
