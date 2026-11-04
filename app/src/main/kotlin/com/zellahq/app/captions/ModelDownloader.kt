package com.zellahq.app.captions

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Optional bigger caption models, downloaded once and kept on the phone. This is one of Zella's
 * three network paths: it fetches model files only and sends nothing about the person or their video.
 *
 * The manifest lists each model's parts with SHA-256 checksums. Downloads resume from where they
 * stopped (HTTP Range), every part is checked, and nothing is used until the whole file checks out.
 *
 * DECISION (BUILDLOG): Android needs ggml files hosted at [MANIFEST]. Until they are, the manifest
 * does not load and the download option stays hidden.
 */
object ModelDownloader {
    const val MANIFEST = "https://models.zellahq.com/android/manifest.json"

    data class Part(val url: String, val sha256: String, val bytes: Long)

    data class Model(val id: String, val name: String, val bytes: Long, val sha256: String, val parts: List<Part>)

    fun parse(text: String): List<Model> =
        Json.parseToJsonElement(text).jsonObject["models"]!!.jsonArray.map { m ->
            val o = m.jsonObject
            Model(
                id = o["id"]!!.jsonPrimitive.content,
                name = o["name"]!!.jsonPrimitive.content,
                bytes = o["bytes"]!!.jsonPrimitive.long,
                sha256 = o["sha256"]!!.jsonPrimitive.content,
                parts =
                    o["parts"]!!.jsonArray.map {
                        val p = it.jsonObject
                        Part(p["url"]!!.jsonPrimitive.content, p["sha256"]!!.jsonPrimitive.content, p["bytes"]!!.jsonPrimitive.long)
                    },
            )
        }

    /** Null when the manifest cannot be reached: then no download is offered. */
    fun catalog(url: String = MANIFEST): List<Model>? =
        runCatching {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 10_000
            c.inputStream.use { parse(it.readBytes().decodeToString()) }
        }.getOrNull()

    fun file(
        context: Context,
        m: Model,
    ) = File(context.filesDir, "models/${m.id}.bin")

    fun installed(
        context: Context,
        m: Model,
    ) = file(context, m).let { it.isFile && it.length() == m.bytes }

    /** True on Wi-Fi or another unmetered network. On mobile data the person is asked first. */
    fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Blocking. Resumes any part already partly downloaded. Returns the model file, or null. */
    fun download(
        context: Context,
        m: Model,
        onProgress: (Float) -> Unit,
        cancelled: () -> Boolean = { false },
    ): File? {
        val dir = File(context.filesDir, "models/${m.id}.parts").apply { mkdirs() }
        val total = m.parts.sumOf { it.bytes }.coerceAtLeast(1)
        var done = 0L
        for ((i, part) in m.parts.withIndex()) {
            val f = File(dir, "$i")
            if (!(f.isFile && f.length() == part.bytes && sha(f) == part.sha256)) {
                if (f.length() > part.bytes || (f.length() == part.bytes && sha(f) != part.sha256)) f.delete()
                val have = f.length()
                val c = URI(part.url).toURL().openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                // 206 means the server resumed; 200 means it sent the whole part again.
                val append = have > 0 && c.responseCode == 206
                RandomAccessFile(f, "rw").use { out ->
                    if (append) out.seek(have) else out.setLength(0)
                    c.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (cancelled()) return null
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            onProgress((done + out.filePointer).toFloat() / total)
                        }
                    }
                }
                if (sha(f) != part.sha256) {
                    f.delete()
                    return null
                }
            }
            done += part.bytes
            onProgress(done.toFloat() / total)
        }
        val out = file(context, m)
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.outputStream().use { o -> m.parts.indices.forEach { i -> File(dir, "$i").inputStream().use { it.copyTo(o) } } }
        if (sha(tmp) != m.sha256) {
            tmp.delete()
            return null
        }
        tmp.renameTo(out)
        dir.deleteRecursively()
        return out
    }

    fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
