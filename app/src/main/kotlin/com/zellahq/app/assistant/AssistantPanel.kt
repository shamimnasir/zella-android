package com.zellahq.app.assistant

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zellahq.ai.Agent
import com.zellahq.ai.AgentEvent
import com.zellahq.ai.LlmException
import com.zellahq.ai.LlmMessage
import com.zellahq.ai.ProviderKind
import com.zellahq.ai.clientFor
import com.zellahq.app.R
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Tool
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaRadius
import com.zellahq.ui.ZellaSpacing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Bubble(val mine: Boolean, val text: String)

/** Chat and settings for the Assistant. Everything that reaches the network is said so on this screen. */
@Composable
fun AssistantPanel(state: EditorState) {
    val context = LocalContext.current
    val keys = remember { AssistantKeys(context) }
    val settings = remember { AssistantSettings(context) }
    var provider by remember { mutableStateOf(settings.provider) }
    var showSettings by remember { mutableStateOf(!keys.has(settings.provider)) }
    val text = MaterialTheme.colorScheme.onBackground

    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.as_network, provider.displayName.substringBefore(" (")), color = text)
        if (showSettings) {
            Settings(keys, settings, provider, { provider = it }) { showSettings = false }
        } else {
            Chat(state, keys, settings, provider) { showSettings = true }
        }
    }
}

@Composable
private fun Settings(
    keys: AssistantKeys,
    settings: AssistantSettings,
    provider: ProviderKind,
    onProvider: (ProviderKind) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val color = MaterialTheme.colorScheme.onBackground
    var key by remember(provider) { mutableStateOf("") }
    var model by remember(provider) { mutableStateOf(settings.model(provider)) }
    var has by remember(provider) { mutableStateOf(keys.has(provider)) }
    var checking by remember { mutableStateOf(false) }
    var note by remember(provider) { mutableStateOf<String?>(null) }
    val okText = stringResource(R.string.as_key_ok)

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.as_setup), color = color)
        Text(stringResource(R.string.as_provider), color = color)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            ProviderKind.entries.forEach { k ->
                Tool(k.displayName, if (k == provider) ZellaColors.CardSun else null) {
                    settings.provider = k
                    onProvider(k)
                }
            }
        }
        Text(provider.keyHint, color = color)
        Tool(stringResource(R.string.as_get_key)) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyPage)))
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.as_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text(stringResource(R.string.as_model)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (has) Text(stringResource(R.string.as_key_saved, provider.displayName.substringBefore(" (")), color = color)
        if (checking) {
            Text(stringResource(R.string.as_checking), color = color)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                Tool(stringResource(R.string.as_save_key), ZellaColors.CardMint) {
                    val typed = key.trim()
                    val use = typed.ifEmpty { keys.get(provider).orEmpty() }
                    settings.setModel(provider, model)
                    if (use.isEmpty()) return@Tool
                    checking = true
                    note = null
                    scope.launch {
                        val failure =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    clientFor(provider, use, settings.model(provider), HttpsTransport).check()
                                }.exceptionOrNull()
                            }
                        checking = false
                        if (failure == null) {
                            keys.set(provider, use)
                            has = true
                            key = ""
                            note = okText
                            onDone()
                        } else {
                            note = (failure as? LlmException)?.settingsText ?: context.getString(R.string.as_offline)
                        }
                    }
                }
                if (has) {
                    Tool(stringResource(R.string.as_chat)) { onDone() }
                    Tool(stringResource(R.string.as_remove_key), ZellaColors.CardCoral) {
                        keys.remove(provider)
                        has = false
                    }
                }
            }
        }
        note?.let { Text(it, color = color) }
    }
}

@Composable
private fun Chat(
    state: EditorState,
    keys: AssistantKeys,
    settings: AssistantSettings,
    provider: ProviderKind,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val color = MaterialTheme.colorScheme.onBackground
    val bubbles = remember(state) { mutableStateListOf<Bubble>() }
    var history by remember(state) { mutableStateOf<List<LlmMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var working by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var asking by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(bubbles.size) { if (bubbles.isNotEmpty()) list.animateScrollToItem(bubbles.size - 1) }

    fun send() {
        val words = input.trim()
        val key = keys.get(provider)
        if (words.isEmpty() || key == null || job != null) return
        input = ""
        bubbles += Bubble(true, words)
        val client = clientFor(provider, key, settings.model(provider), HttpsTransport)
        val tools =
            assistantTools(context, state) { question ->
                val answer = CompletableDeferred<Boolean>()
                asking = question to answer
                answer.await().also { asking = null }
            }
        val agent =
            Agent(tools, { assistantPrompt() }) { system, messages, schemas ->
                withContext(Dispatchers.IO) { client.send(system, messages, schemas) }
            }
        job =
            scope.launch {
                try {
                    history =
                        agent.run(history, words) { e ->
                            when (e) {
                                is AgentEvent.Said -> bubbles += Bubble(false, e.text)
                                is AgentEvent.Using -> working = e.tool.replace('_', ' ')
                                is AgentEvent.Did -> working = null
                            }
                        }
                } catch (e: LlmException) {
                    bubbles += Bubble(false, e.message)
                } catch (e: java.io.IOException) {
                    bubbles += Bubble(false, context.getString(R.string.as_offline))
                } finally {
                    working = null
                    job = null
                }
            }
    }

    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp), state = list, verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        items(bubbles) { b ->
            Text(
                b.text,
                color = if (b.mine) ZellaColors.Ink else androidx.compose.ui.graphics.Color.White,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(if (b.mine) ZellaColors.CardMint else ZellaColors.NightCard, RoundedCornerShape(ZellaRadius.Card))
                        .padding(ZellaSpacing.S),
            )
        }
    }
    working?.let { Text(stringResource(R.string.as_working, it), color = color) }
    asking?.let { (question, answer) ->
        Text(question, color = color)
        Row(horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            Tool(stringResource(R.string.as_yes), ZellaColors.CardMint) { answer.complete(true) }
            Tool(stringResource(R.string.as_no), ZellaColors.CardCoral) { answer.complete(false) }
        }
    }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text(stringResource(R.string.as_ask)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        if (job == null) {
            Tool(stringResource(R.string.as_send), ZellaColors.CardMint) { send() }
        } else {
            Tool(stringResource(R.string.as_stop), ZellaColors.CardCoral) { job?.cancel() }
        }
        if (bubbles.isNotEmpty() && job == null) {
            Tool(stringResource(R.string.as_clear)) {
                bubbles.clear()
                history = emptyList()
            }
        }
        Tool(stringResource(R.string.as_settings), onClick = onSettings)
    }
}
