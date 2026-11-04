package com.zellahq.app.broll

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.zellahq.app.R
import com.zellahq.app.assistant.AssistantKeys
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Tool
import com.zellahq.core.auto.BRoll
import com.zellahq.core.project.Project
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing

/** Cutaways. Cards stay on the phone. Stock clips reach a site, and this screen says exactly what goes there. */
@Composable
fun BRollPanel(
    state: EditorState,
    project: Project,
    selectedId: String?,
) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onBackground
    val keys = remember { AssistantKeys(context) }
    var provider by remember { mutableStateOf(StockProvider.Pexels) }
    var typed by remember { mutableStateOf("") }
    var hasKey by remember(provider) { mutableStateOf(keys.hasNamed(provider.raw)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val count = project.clips.count { it.isBRoll }
    val working = stringResource(R.string.br_working)

    fun run(work: (BRollMaker, Project, (String) -> Unit) -> BRollMaker.Outcome) {
        val start = state.project.value
        busy = working
        note = null
        Thread({
            val out = runCatching { work(BRollMaker(context), start) { w -> state.onMain { busy = w } } }
            state.onMain {
                busy = null
                val r = out.getOrNull()
                note = r?.note ?: out.exceptionOrNull()?.message
                if (r != null && r.placed > 0) state.edit("cutaways") { r.project }
            }
        }, "b-roll").start()
    }

    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.br_detail), color = color)
        busy?.let { Text(it, color = color) }
        if (busy == null) {
            Tool(stringResource(R.string.br_cards), ZellaColors.CardMint) {
                run { m, p, _ -> m.cards(p, selectedId) }
            }
            Text(stringResource(R.string.br_cards_note), color = color)
            Text(stringResource(R.string.br_stock_title), color = color)
            Text(stringResource(R.string.br_stock_network, provider.displayName), color = color)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                StockProvider.entries.forEach { k ->
                    Tool(k.displayName, if (k == provider) ZellaColors.CardSun else null) { provider = k }
                }
                Tool(stringResource(R.string.as_get_key)) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyPage)))
                }
            }
            if (!hasKey) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.as_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                Tool(stringResource(R.string.br_stock), ZellaColors.CardViolet) {
                    val word = typed.trim()
                    if (word.isNotEmpty()) {
                        keys.setNamed(provider.raw, word)
                        typed = ""
                        hasKey = true
                    }
                    val key = keys.getNamed(provider.raw)
                    if (key != null) {
                        val chosen = provider
                        run { m, p, status -> m.stock(p, selectedId, chosen, key, status = status) }
                    }
                }
                if (hasKey) {
                    Tool(stringResource(R.string.as_remove_key), ZellaColors.CardCoral) {
                        keys.removeNamed(provider.raw)
                        hasKey = false
                    }
                }
            }
        }
        note?.let { Text(it, color = color) }
        if (count > 0) {
            Tool(stringResource(R.string.br_remove, count), ZellaColors.CardCoral) {
                state.edit("remove cutaways") { BRoll.removeAll(it).first }
            }
        }
    }
}
