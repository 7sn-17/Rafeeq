package com.rafeeq.assistant.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rafeeq.assistant.R

/** Background gradient of the whole screen. */
private val ScreenGradient = listOf(
    Color(0xFF0F1E4B),
    Color(0xFF1E3A8A),
    Color(0xFF0E7490)
)

/** Gradient of the microphone button. */
private val MicrophoneGradient = listOf(
    Color(0xFF4A6BFF),
    Color(0xFF2AC1FF)
)

/**
 * Stateless home screen: status, the animated microphone button and the conversation bubbles.
 */
@Composable
fun MainScreen(
    state: AssistantState,
    recognizedText: String,
    responseText: String,
    onListenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(ScreenGradient)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = APP_TITLE,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = statusTextFor(state),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            MicrophoneButton(state = state, onListenClick = onListenClick)

            Spacer(modifier = Modifier.height(40.dp))

            ConversationBubbles(
                recognizedText = recognizedText,
                responseText = responseText
            )
        }
    }
}

@Composable
private fun MicrophoneButton(
    state: AssistantState,
    onListenClick: () -> Unit
) {
    val isListening = state == AssistantState.LISTENING
    val isBusy = isListening || state == AssistantState.PROCESSING

    // The animations run continuously; they are only applied while the assistant listens.
    val infiniteTransition = rememberInfiniteTransition(label = "microphone")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "microphonePulse"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "microphoneGlow"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            // Halo that pulses while listening.
            Box(
                modifier = Modifier
                    .size(168.dp)
                    .scale(if (isListening) pulseScale else 1f)
                    .background(
                        color = Color.White.copy(alpha = if (isListening) glowAlpha else 0f),
                        shape = CircleShape
                    )
            )

            Box(
                modifier = Modifier
                    .size(136.dp)
                    .scale(if (isListening) pulseScale else 1f)
                    .background(brush = Brush.verticalGradient(MicrophoneGradient), shape = CircleShape)
                    .clickable(enabled = !isBusy, onClick = onListenClick),
                contentAlignment = Alignment.Center
            ) {
                if (state == AssistantState.PROCESSING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(52.dp),
                        color = Color.White,
                        strokeWidth = 4.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_mic),
                        contentDescription = LISTEN_LABEL,
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = LISTEN_LABEL,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}

@Composable
private fun ConversationBubbles(
    recognizedText: String,
    responseText: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (recognizedText.isNotBlank()) {
            Bubble(label = RECOGNIZED_LABEL, text = recognizedText)
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (responseText.isNotBlank()) {
            Bubble(label = RESPONSE_LABEL, text = responseText)
        }
    }
}

@Composable
private fun Bubble(label: String, text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
        }
    }
}

private fun statusTextFor(state: AssistantState): String = when (state) {
    AssistantState.IDLE -> "المساعد جاهز"
    AssistantState.LISTENING -> "جارٍ الاستماع..."
    AssistantState.PROCESSING -> "جارٍ المعالجة..."
    AssistantState.SUCCESS -> "تمت المهمة بنجاح"
    AssistantState.ERROR -> "حدث خطأ ما"
}

private const val APP_TITLE = "رفيق"
private const val LISTEN_LABEL = "استمع"
private const val RECOGNIZED_LABEL = "سمعت"
private const val RESPONSE_LABEL = "رفيق"
