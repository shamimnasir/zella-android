@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zellahq.core.EntitlementState
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Reads and stores the facts PremiumGate decides from. The install time is written once, on first
 * launch, and never again. [setPurchased] is called by billing after it reads what Google Play says.
 *
 * The stored facts carry a seal: an HMAC under a key that cannot leave Android Keystore. Editing the
 * prefs file by hand on a rooted phone breaks the seal, and a broken seal reads as the free plan with
 * Pro Week over. Google Play still decides the plan at the next launch.
 */
object Entitlements {
    private const val PREFS = "entitlement"
    private const val ALIAS = "zella-entitlement-seal"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }

    private fun seal(
        plan: String,
        install: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key())
        return Base64.encodeToString(mac.doFinal("$plan|$install".toByteArray()), Base64.NO_WRAP)
    }

    fun load(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): EntitlementState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedInstall = prefs.getLong("install", 0L)
        if (storedInstall == 0L) {
            prefs.edit().putLong("install", nowMs).putString("plan", Plan.Free.name).putString("seal", seal(Plan.Free.name, nowMs)).apply()
        }
        val install = if (storedInstall > 0L) storedInstall else nowMs
        val planName = prefs.getString("plan", Plan.Free.name)!!
        val good = prefs.getString("seal", null)?.let { MessageDigest.isEqual(it.toByteArray(), seal(planName, install).toByteArray()) } ?: false
        val state =
            if (good) {
                val plan = runCatching { Plan.valueOf(planName) }.getOrDefault(Plan.Free)
                PremiumGate.touch(EntitlementState(plan, install, prefs.getLong("seen", install)), nowMs)
            } else {
                // Tampered, or a Keystore that was wiped. Free, with Pro Week over; Play restores a real purchase.
                EntitlementState(Plan.Free, 0L, nowMs)
            }
        prefs.edit().putLong("seen", state.lastSeenEpochMs).apply()
        return state
    }

    fun setPurchased(
        context: Context,
        plan: Plan,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val install = prefs.getLong("install", 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
        prefs.edit().putLong("install", install).putString("plan", plan.name).putString("seal", seal(plan.name, install)).apply()
    }
}
