package com.zellahq.app.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zellahq.app.R
import com.zellahq.core.project.Project
import com.zellahq.core.project.TransitionTypes
import com.zellahq.core.project.removeTransition
import com.zellahq.core.project.setTransition
import com.zellahq.core.project.transitionAfter
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing

/** How one clip joins the next: 25 transitions in five groups, a length, or a plain cut. */
@Composable
fun TransitionPanel(
    state: EditorState,
    project: Project,
    clipId: String,
) {
    val current = project.transitionAfter(clipId)
    val startGroup = TransitionTypes.categories.firstOrNull { (_, names) -> current?.type in names }?.first ?: "Classic"
    var group by remember(clipId) { mutableStateOf(startGroup) }
    val text = MaterialTheme.colorScheme.onBackground
    Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
        Text(stringResource(R.string.tr_title), color = text)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            TransitionTypes.categories.forEach { (name, _) ->
                Tool(name, if (group == name) ZellaColors.CardMint else null) { group = name }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            TransitionTypes.categories.first { it.first == group }.second.forEach { name ->
                Tool(name, if (current?.type == name) ZellaColors.CardSun else ZellaColors.CardViolet) {
                    state.edit("transition") { it.setTransition(clipId, name, current?.duration ?: 0.5) }
                }
            }
        }
        if (current != null) {
            Text(stringResource(R.string.tr_length), color = text)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
                listOf(R.string.tr_short to 0.25, R.string.tr_medium to 0.5, R.string.tr_long to 1.0).forEach { (label, d) ->
                    Tool(stringResource(label), if (kotlin.math.abs(current.duration - d) < 0.01) ZellaColors.CardSun else null) {
                        state.edit("transition") { it.setTransition(clipId, current.type, d) }
                    }
                }
                Tool(stringResource(R.string.tr_remove), ZellaColors.CardCoral) {
                    state.edit("transition") { it.removeTransition(clipId) }
                }
            }
        }
    }
}
