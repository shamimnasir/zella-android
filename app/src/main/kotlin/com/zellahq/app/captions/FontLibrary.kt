package com.zellahq.app.captions

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.zellahq.core.fonts.FontNames
import java.io.File

/**
 * Fonts the person imported, kept in Zella's own storage and used only by Zella. Never uploaded.
 * Each file is stored under its PostScript name, the name the iPhone and Mac use for the same font.
 */
object FontLibrary {
    sealed interface Result {
        data class Added(val name: String) : Result

        data object NotAFont : Result

        data object TooBig : Result
    }

    private fun dir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    fun names(context: Context): List<String> = dir(context).listFiles().orEmpty().map { it.nameWithoutExtension }.sorted()

    fun typeface(
        context: Context,
        name: String?,
    ): Typeface? {
        if (name.isNullOrEmpty()) return null
        val f = dir(context).listFiles { x -> x.nameWithoutExtension == name }?.firstOrNull() ?: return null
        return runCatching { Typeface.createFromFile(f) }.getOrNull()
    }

    fun import(
        context: Context,
        uri: Uri,
    ): Result {
        val cr = context.contentResolver
        val (display, size) =
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() to c.getLong(1) else "" to -1L
            } ?: ("" to -1L)
        val ext = display.substringAfterLast('.', "").lowercase()
        if (ext !in FontNames.EXTENSIONS) return Result.NotAFont
        if (size > FontNames.MAX_BYTES) return Result.TooBig
        val tmp = File(context.cacheDir, "font-import.$ext")
        cr.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return Result.NotAFont
        try {
            if (tmp.length() > FontNames.MAX_BYTES) return Result.TooBig
            val name = FontNames.postScriptName(tmp.readBytes()) ?: return Result.NotAFont
            // Android must be able to draw with it, or it is no use as a caption font.
            runCatching { Typeface.createFromFile(tmp) }.getOrNull() ?: return Result.NotAFont
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            tmp.copyTo(File(dir(context), "$safe.$ext"), overwrite = true)
            return Result.Added(safe)
        } finally {
            tmp.delete()
        }
    }
}
