package com.rafeeq.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Stateless screen for the assistant: a status line plus the "listen" action.
 */
@Composable
fun MainScreen(
    state: AssistantState,
    onListenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = statusTextFor(state),
            fontSize = 28.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onListenClick,
            enabled = state == AssistantState.Ready
        ) {
            Text(text = LISTEN_LABEL, fontSize = 18.sp)
        }
    }
}

private fun statusTextFor(state: AssistantState): String = when (state) {
    AssistantState.Ready -> "المساعد جاهز"
    AssistantState.Listening -> "جارٍ الاستماع..."
    AssistantState.Processing -> "جارٍ المعالجة..."
}

private const val LISTEN_LABEL = "استمع"
