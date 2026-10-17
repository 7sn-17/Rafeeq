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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rafeeq.assistant.R
import com.rafeeq.assistant.core.AssistantState
import com.rafeeq.assistant.core.ChatMessage

private val ScreenGradient = listOf(
    Color(0xFF0F1E4B),
    Color(0xFF1E3A8A),
    Color(0xFF0E7490)
)

private val MicrophoneGradient = listOf(
    Color(0xFF4A6BFF),
    Color(0xFF2AC1FF)
)

@Composable
fun MainScreen(
    state: AssistantState,
    messages: List<ChatMessage>,
    errorMessage: String,
    onListenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    // The trailing item is the optional inline error bubble, so include it in the count.
    val itemCount = messages.size + if (errorMessage.isNotEmpty()) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(ScreenGradient)),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "رفيق",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    ChatBubble(message = message)
                }
                if (errorMessage.isNotEmpty()) {
                    item {
                        ChatBubble(ChatMessage(errorMessage, isUser = false))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            ProcessingStatus(state = state)
            Spacer(modifier = Modifier.height(16.dp))
            MicrophoneButton(state = state, onListenClick = onListenClick)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 4.dp),
            color = if (message.isUser) Color(0xFF4A6BFF) else Color(0xFF1E293B),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            )
        ) {
            Text(
                text = message.text,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.padding(12.dp),
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
fun ProcessingStatus(state: AssistantState) {
    val text = when (state) {
        AssistantState.ANALYZING -> "جاري التحليل..."
        AssistantState.SEARCHING_FILES -> "جاري البحث عن الصور..."
        AssistantState.MOVING_FILES -> "جاري نقل الصور..."
        AssistantState.COMPLETED -> "تم الإنتهاء"
        AssistantState.PROCESSING -> "جاري المعالجة..."
        AssistantState.LISTENING -> "يستمع..."
        else -> ""
    }
    if (text.isNotEmpty()) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun MicrophoneButton(state: AssistantState, onListenClick: () -> Unit) {
    val isListening = state == AssistantState.LISTENING
    val isEnabled = state != AssistantState.PROCESSING && state != AssistantState.LISTENING
    val infiniteTransition = rememberInfiniteTransition(label = "mic")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(pulseScale)
            .alpha(if (isEnabled) 1f else 0.6f)
            .background(
                brush = Brush.radialGradient(MicrophoneGradient),
                shape = CircleShape
            )
            .clickable(enabled = isEnabled) { onListenClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_mic),
            contentDescription = "Microphone",
            tint = Color.White,
            modifier = Modifier.size(56.dp)
        )
    }
}
