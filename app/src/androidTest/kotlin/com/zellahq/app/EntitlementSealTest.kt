@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.billing.PurchaseCheck
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.Signature

/** The stored plan cannot be edited by hand, and a purchase's signature is really checked. */
@RunWith(AndroidJUnit4::class)
class EntitlementSealTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("entitlement", Context.MODE_PRIVATE)

    private fun reset() = prefs.edit().clear().commit()

    @Test fun aNewInstallIsInProWeekOnTheFreePlan() {
        reset()
        val now = System.currentTimeMillis()
        val s = Entitlements.load(context, now)
        assertEquals(Plan.Free, s.purchased)
        assertTrue(PremiumGate.isInProWeek(s, now))
        assertEquals(7, PremiumGate.proWeekDaysLeft(s, now))
        assertFalse(
            "Pro Week is over a week later",
            PremiumGate.isInProWeek(Entitlements.load(context, now + 8L * 86_400_000), now + 8L * 86_400_000),
        )
    }

    @Test fun aRealSetSurvivesAndAHandEditDoesNot() {
        reset()
        val now = System.currentTimeMillis()
        Entitlements.load(context, now)
        Entitlements.setPurchased(context, Plan.Pro)
        assertEquals(Plan.Pro, Entitlements.load(context, now).purchased)

        prefs.edit().putString("plan", Plan.Founder.name).commit()
        val forged = Entitlements.load(context, now)
        assertEquals("an edited plan must read as free", Plan.Free, forged.purchased)
        assertFalse("and it must not give Pro Week back", PremiumGate.isInProWeek(forged, now))

        Entitlements.setPurchased(context, Plan.Free)
        prefs.edit().putLong("install", now + 365L * 86_400_000).commit()
        assertFalse("an edited install date must not restart Pro Week", PremiumGate.isInProWeek(Entitlements.load(context, now), now))
    }

    @Test fun rollingTheClockBackDoesNotExtendProWeek() {
        reset()
        val now = System.currentTimeMillis()
        Entitlements.load(context, now)
        val later = now + 8L * 86_400_000
        Entitlements.load(context, later)
        assertFalse(PremiumGate.isInProWeek(Entitlements.load(context, now + 1000), now + 1000))
    }

    @Test fun aPurchaseSignatureIsChecked() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = android.util.Base64.encodeToString(pair.public.encoded, android.util.Base64.NO_WRAP)
        val json = """{"productId":"zella_pro_lifetime","purchaseState":0}"""
        val sig =
            Signature.getInstance("SHA1withRSA").run {
                initSign(pair.private)
                update(json.toByteArray())
                android.util.Base64.encodeToString(sign(), android.util.Base64.NO_WRAP)
            }
        assertTrue(PurchaseCheck.valid(key, json, sig))
        assertFalse("a changed purchase fails", PurchaseCheck.valid(key, json.replace("pro", "founder"), sig))
        assertFalse("rubbish fails rather than crashes", PurchaseCheck.valid("not base64 at all", json, sig))
        assertFalse(PurchaseCheck.valid(key, json, "AAAA"))
    }
}
