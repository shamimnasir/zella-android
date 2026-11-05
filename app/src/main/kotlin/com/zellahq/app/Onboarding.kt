package com.zellahq.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zellahq.app.editor.Tool
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing

object Onboarding {
    private const val PREFS = "first-run"

    fun isDone(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("done", false)

    fun finish(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("done", true).apply()
}

/** Three short pages: how it works, what stays on the phone, what is free. No questions and no paywall. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val color = MaterialTheme.colorScheme.onBackground
    Column(
        Modifier.fillMaxSize().padding(ZellaSpacing.M).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(ZellaSpacing.M),
    ) {
        when (page) {
            0 -> {
                Text(stringResource(R.string.ob_welcome), color = color, style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.ob_welcome_detail), color = color)
                Text(stringResource(R.string.ob_how), color = color, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ob_how_1), color = color)
                Text(stringResource(R.string.ob_how_2), color = color)
                Text(stringResource(R.string.ob_how_3), color = color)
            }
            1 -> {
                Text(stringResource(R.string.ob_private), color = color, style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.ob_private_detail), color = color)
                Text(stringResource(R.string.ob_private_extras), color = color)
            }
            else -> {
                Text(stringResource(R.string.ob_free), color = color, style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.ob_free_detail), color = color)
                Text(stringResource(R.string.ob_week), color = color)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            if (page < 2) {
                Tool(stringResource(R.string.ob_next), ZellaColors.CardMint) { page++ }
                Tool(stringResource(R.string.ob_skip), onClick = onDone)
            } else {
                Tool(stringResource(R.string.ob_start), ZellaColors.CardMint, onClick = onDone)
            }
        }
    }
}
