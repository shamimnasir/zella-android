package com.zellahq.app.billing

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zellahq.app.Entitlements
import com.zellahq.app.R
import com.zellahq.app.editor.Tool
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing

/** The plans in plain words. Prices come from Google Play. No countdown, no pressure, and always a way back. */
@Composable
fun PlansScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onBackground
    val store = remember { PlayStore(context) }
    val state by store.state.collectAsState()
    var refreshed by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        store.connect()
        onDispose { store.close() }
    }
    val now = System.currentTimeMillis()
    val owned = remember(state, refreshed) { Entitlements.load(context) }
    val weekLeft = if (owned.purchased == Plan.Free) PremiumGate.proWeekDaysLeft(owned, now) else 0

    Column(
        Modifier.fillMaxSize().padding(ZellaSpacing.M).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(ZellaSpacing.M),
    ) {
        Tool(stringResource(R.string.pl_back), onClick = onBack)
        Text(stringResource(R.string.pl_title), color = color, style = MaterialTheme.typography.headlineSmall)
        if (weekLeft > 0) Text(stringResource(R.string.pl_week, weekLeft), color = color)
        Text(stringResource(R.string.pl_free), color = color, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.pl_free_detail), color = color)

        @Composable
        fun Tier(
            plan: Plan,
            title: Int,
            detail: Int,
        ) {
            Text(stringResource(title), color = color, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(detail), color = color)
            val price = state.prices[plan.productId].orEmpty()
            when {
                owned.purchased == plan || (plan == Plan.Pro && owned.purchased == Plan.Founder) ->
                    Text(
                        stringResource(R.string.pl_have),
                        color = color,
                    )
                price.isNotEmpty() ->
                    Tool(stringResource(R.string.pl_buy, price), ZellaColors.CardViolet) {
                        note = null
                        if ((context as? Activity)?.let { store.buy(it, plan) } != true) note = R.string.pl_restored_no_answer
                    }
            }
        }
        Tier(Plan.Pro, R.string.pl_pro, R.string.pl_pro_detail)
        Tier(Plan.Founder, R.string.pl_founder, R.string.pl_founder_detail)
        if (state.prices.isEmpty()) Text(stringResource(R.string.pl_unavailable), color = color)
        if (state.pending) Text(stringResource(R.string.pl_pending), color = color)
        Tool(stringResource(R.string.pl_restore)) {
            store.refresh { result ->
                note =
                    when (result) {
                        RestoreResult.Found -> R.string.pl_restored
                        RestoreResult.None -> R.string.pl_restored_none
                        RestoreResult.NoAnswer -> R.string.pl_restored_no_answer
                    }
                refreshed++
            }
        }
        note?.let { Text(stringResource(it), color = color) }
        Text(stringResource(R.string.pl_separate), color = color)
    }
}
