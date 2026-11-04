@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app.billing

import android.app.Activity
import android.content.Context
import android.util.Base64
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.zellahq.app.BuildConfig
import com.zellahq.app.Entitlements
import com.zellahq.core.Plan
import com.zellahq.core.PlayPurchase
import com.zellahq.core.PurchaseLedger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Checks a purchase's signature on the phone. There is no server, by design. */
object PurchaseCheck {
    fun valid(
        publicKeyBase64: String,
        originalJson: String,
        signature: String,
    ): Boolean =
        runCatching {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.DEFAULT)))
            Signature.getInstance("SHA1withRSA").run {
                initVerify(key)
                update(originalJson.toByteArray())
                verify(Base64.decode(signature, Base64.DEFAULT))
            }
        }.getOrDefault(false)
}

data class StoreState(
    val connected: Boolean = false,
    val prices: Map<String, String> = emptyMap(),
    val pending: Boolean = false,
    /** Goes up each time Play's answer is applied, so a screen reads the stored plan again. */
    val applied: Int = 0,
)

/** What a look at Play's list found. "Could not answer" is not the same as "you own nothing". */
enum class RestoreResult { Found, None, NoAnswer }

/**
 * Google Play, one-time purchases. Plan and refunds come from Play's own list, read at every start
 * and after every purchase. Nothing here has run against the real Play service: that needs a Play
 * Console app and a test account, which only the owner has.
 */
class PlayStore(context: Context) : PurchasesUpdatedListener {
    private val app = context.applicationContext
    private val mutable = MutableStateFlow(StoreState())
    val state: StateFlow<StoreState> = mutable
    private var details: Map<String, ProductDetails> = emptyMap()
    private var onRestored: ((Boolean) -> Unit)? = null

    private val client =
        BillingClient
            .newBuilder(app)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()

    fun connect() {
        if (client.isReady) {
            refresh()
            return
        }
        client.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        mutable.value = mutable.value.copy(connected = true)
                        loadPrices()
                        refresh()
                    }
                }

                override fun onBillingServiceDisconnected() {
                    mutable.value = mutable.value.copy(connected = false)
                }
            },
        )
    }

    private fun loadPrices() {
        val products =
            listOfNotNull(Plan.Pro.productId, Plan.Founder.productId).map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
            }
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, found ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            details = found.productDetailsList.associateBy { it.productId }
            mutable.value = mutable.value.copy(prices = details.mapValues { it.value.oneTimePurchaseOfferDetails?.formattedPrice.orEmpty() })
        }
    }

    /** Re-reads what Play says and applies it. A refund shows up here as the purchase being gone. */
    fun refresh(done: ((RestoreResult) -> Unit)? = null) {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                // Play could not answer: keep what is stored. A person offline keeps what they paid for.
                done?.invoke(RestoreResult.NoAnswer)
                return@queryPurchasesAsync
            }
            apply(purchases)
            done?.invoke(
                if (purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }) RestoreResult.Found else RestoreResult.None,
            )
        }
    }

    private fun apply(purchases: List<Purchase>) {
        val facts =
            purchases.flatMap { p ->
                p.products.map { id ->
                    PlayPurchase(id, p.purchaseState == Purchase.PurchaseState.PURCHASED, signatureOk(p))
                }
            }
        val r = PurchaseLedger.reconcile(facts)
        Entitlements.setPurchased(app, r.plan)
        mutable.value = mutable.value.copy(pending = r.pending, applied = mutable.value.applied + 1)
        // Play refunds a purchase that is not acknowledged within three days.
        purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged && signatureOk(it) }
            .forEach {
                    p ->
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {}
            }
    }

    private fun signatureOk(p: Purchase): Boolean {
        val key = BuildConfig.PLAY_KEY
        // No key in a debug build, on purpose. A release with no key can never unlock anything.
        if (key.isEmpty()) return BuildConfig.DEBUG
        return PurchaseCheck.valid(key, p.originalJson, p.signature)
    }

    fun buy(
        activity: Activity,
        plan: Plan,
    ): Boolean {
        val d = details[plan.productId] ?: return false
        val params =
            BillingFlowParams
                .newBuilder()
                .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
                .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(
        result: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        // Already owned means this phone's stored plan is behind Play. Read the list and catch up.
        if (result.responseCode == BillingClient.BillingResponseCode.OK ||
            result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
        ) {
            refresh()
        }
    }

    fun close() = client.endConnection()
}
