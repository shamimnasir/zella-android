package com.zellahq.app.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zellahq.ai.HttpReply
import com.zellahq.ai.ProviderKind
import com.zellahq.ai.Transport
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The person's own Assistant keys. Encrypted with a key that lives in Android Keystore and cannot be
 * read out of it. App backup is off in the manifest, so these never leave with a backup. A key leaves
 * the device only in the request to the provider it belongs to.
 */
class AssistantKeys(context: Context) {
    private val prefs = context.getSharedPreferences("assistant-keys", Context.MODE_PRIVATE)

    private fun master(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun set(
        kind: ProviderKind,
        key: String,
    ) = setNamed(kind.raw, key)

    fun get(kind: ProviderKind) = getNamed(kind.raw)

    fun has(kind: ProviderKind) = hasNamed(kind.raw)

    fun remove(kind: ProviderKind) = removeNamed(kind.raw)

    /** The same protection for the stock video sites' keys. */
    fun setNamed(
        name: String,
        key: String,
    ): Boolean =
        runCatching {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, master())
            val blob = c.iv + c.doFinal(key.toByteArray())
            prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        }.isSuccess

    fun getNamed(name: String): String? {
        val blob = prefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, master(), GCMParameterSpec(128, blob, 0, IV))
            String(c.doFinal(blob, IV, blob.size - IV))
        }.getOrNull()
    }

    /** Saved and readable. A key that can no longer be decrypted counts as gone, so the person is asked again. */
    fun hasNamed(name: String) = getNamed(name) != null

    fun removeNamed(name: String) = prefs.edit().remove(name).apply()

    private companion object {
        const val ALIAS = "zella-assistant-keys"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}

/** Which provider and model the person picked. Neither is a secret. */
class AssistantSettings(context: Context) {
    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    var provider: ProviderKind
        get() = ProviderKind.of(prefs.getString("provider", null))
        set(v) = prefs.edit().putString("provider", v.raw).apply()

    fun model(kind: ProviderKind) = prefs.getString("model-${kind.raw}", null)?.ifBlank { null } ?: kind.defaultModel

    fun setModel(
        kind: ProviderKind,
        model: String,
    ) = prefs.edit().putString("model-${kind.raw}", model.trim()).apply()
}

/** The one place the Assistant reaches the network. HTTPS only. */
object HttpsTransport : Transport {
    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpReply {
        require(url.startsWith("https://")) { "The Assistant only talks over HTTPS" }
        // A bad model name makes a bad address. To the caller that is one more thing that could not be reached.
        val c =
            try {
                URI(url).toURL().openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw java.io.IOException(e)
            }
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 30_000
            c.readTimeout = 180_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray()) }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            return HttpReply(status, text)
        } finally {
            c.disconnect()
        }
    }
}
