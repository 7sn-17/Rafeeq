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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rafeeq.assistant.BuildConfig
import com.rafeeq.assistant.R
import com.rafeeq.assistant.core.AssistantState
import com.rafeeq.assistant.core.ChatMessage
import com.rafeeq.assistant.core.MessageRole
import com.rafeeq.assistant.skills.PendingConfirmation
import com.rafeeq.assistant.skills.PendingPermissionRequest
import com.rafeeq.assistant.ui.theme.rafeeqHeaderGradient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: AssistantState,
    messages: List<ChatMessage>,
    statusText: String?,
    ttsMuted: Boolean,
    pendingPermission: PendingPermissionRequest? = null,
    pendingConfirmation: PendingConfirmation? = null,
    onSendText: (String) -> Unit,
    onToggleMic: () -> Unit,
    onToggleMute: () -> Unit,
    onNewChat: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onPermissionShown: (String) -> Unit = {},
    onPermissionResult: (Map<String, Boolean>) -> Unit = {},
    onConfirmationResult: (String, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAbout by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                onNewChat = {
                    scope.launch { drawerState.close() }
                    onNewChat()
                },
                onClearHistory = {
                    scope.launch { drawerState.close() }
                    onClearHistory()
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
                onAbout = {
                    scope.launch { drawerState.close() }
                    showAbout = true
                }
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            ChatHeader(
                ttsMuted = ttsMuted,
                onMenuClick = { scope.launch { drawerState.open() } },
                onToggleMute = onToggleMute
            )

            ChatList(
                messages = messages,
                statusText = statusText,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            InputBar(
                isRecording = state == AssistantState.LISTENING,
                isBusy = state == AssistantState.PROCESSING || state == AssistantState.ANALYZING,
                onSendText = onSendText,
                onToggleMic = onToggleMic
            )
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    PermissionDialog(
        pendingPermission = pendingPermission,
        onShown = onPermissionShown,
        onResult = onPermissionResult
    )

    ConfirmationDialog(
        pendingConfirmation = pendingConfirmation,
        onResult = onConfirmationResult
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHeader(
    ttsMuted: Boolean,
    onMenuClick: () -> Unit,
    onToggleMute: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(rafeeqHeaderGradient())
    ) {
        TopAppBar(
            modifier = Modifier.statusBarsPadding(),
            title = {
                Text(text = "رفيق", fontWeight = FontWeight.Bold, color = Color.White)
            },
            navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "القائمة",
                        tint = Color.White
                    )
                }
            },
            actions = {
                IconButton(onClick = onToggleMute) {
                    Icon(
                        painter = volumePainter(ttsMuted),
                        contentDescription = if (ttsMuted) "تشغيل الصوت" else "كتم الصوت",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun volumePainter(ttsMuted: Boolean): Painter =
    painterResource(id = if (ttsMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)

@Composable
private fun ChatList(
    messages: List<ChatMessage>,
    statusText: String?,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val showTyping = statusText != null
    // The typing indicator occupies a trailing slot, so include it when counting.
    val itemCount = messages.size + if (showTyping) 1 else 0

    LaunchedEffect(itemCount) {
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        items(messages) { message ->
            ChatBubble(message = message)
        }
        if (showTyping) {
            item {
                ThinkingIndicator(text = statusText.orEmpty())
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    if (message.role == MessageRole.SYSTEM) {
        SystemBubble(text = message.text)
        return
    }

    val isUser = message.role == MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomStart = if (isUser) 24.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 24.dp
            ),
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .widthIn(max = 300.dp)
        ) {
            Text(
                text = message.text,
                fontSize = 16.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

/** Quiet centred line recording which skill is running right now. */
@Composable
private fun SystemBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = text, fontSize = 13.sp)
            }
        }
    }
}

/** Shown while the model is thinking: three bouncing dots plus the current progress line. */
@Composable
private fun ThinkingIndicator(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BouncingDots()
                if (text.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = text, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun BouncingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val offsetY by transition.animateFloat(
                initialValue = 0f,
                targetValue = -6f,
                animationSpec = infiniteRepeatable(
                    animation = tween<Float>(
                        durationMillis = 320,
                        delayMillis = index * 140,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .offset(y = offsetY.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

@Composable
private fun InputBar(
    isRecording: Boolean,
    isBusy: Boolean,
    onSendText: (String) -> Unit,
    onToggleMic: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = text.isNotBlank() && !isBusy

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("اكتب رسالتك هنا...") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )

            Spacer(modifier = Modifier.width(8.dp))

            MicButton(
                isRecording = isRecording,
                isBusy = isBusy,
                onClick = onToggleMic
            )

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(
                enabled = canSend,
                onClick = {
                    if (canSend) {
                        onSendText(text)
                        text = ""
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = "إرسال",
                    tint = if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    }
                )
            }
        }
    }
}

/** Mic toggle; pulses red while a recording is in progress. */
@Composable
private fun MicButton(
    isRecording: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .size(44.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(if (isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary)
            .clickable(enabled = !isBusy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_mic),
            contentDescription = if (isRecording) "إيقاف التسجيل" else "بدء التسجيل",
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDrawer(
    onNewChat: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onAbout: () -> Unit
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(rafeeqHeaderGradient())
                    .padding(20.dp)
                    .padding(top = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "ر", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "رفيق",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "مساعدك الصوتي الذكي",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            DrawerItem(icon = Icons.Filled.Edit, label = "محادثة جديدة", onClick = onNewChat)
            DrawerItem(icon = Icons.Filled.Settings, label = "الإعدادات", onClick = onOpenSettings)
            DrawerItem(icon = Icons.Filled.Info, label = "حول التطبيق", onClick = onAbout)

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            DrawerItem(icon = Icons.Filled.Delete, label = "مسح السجل", onClick = onClearHistory)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    // `selected` has no default in the Material 3 signature; these drawer entries are
    // one-shot actions rather than persistent destinations, so none of them is ever active.
    NavigationDrawerItem(
        label = { Text(label) },
        selected = false,
        icon = { Icon(icon, contentDescription = label) },
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حول رفيق") },
        text = {
            Text(
                text = "رفيق مساعد صوتي ذكي باللغة العربية.\nالإصدار ${BuildConfig.VERSION_NAME}\nتم التطوير بواسطة Hassan Al-Mohammad"
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("حسناً") }
        }
    )
}

@Composable
private fun PermissionDialog(
    pendingPermission: PendingPermissionRequest?,
    onShown: (String) -> Unit,
    onResult: (Map<String, Boolean>) -> Unit
) {
    if (pendingPermission == null) return

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        onResult(grantResults)
    }

    androidx.compose.runtime.LaunchedEffect(pendingPermission.id) {
        onShown(pendingPermission.id)
        launcher.launch(pendingPermission.permissions.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmationDialog(
    pendingConfirmation: PendingConfirmation?,
    onResult: (String, Boolean) -> Unit
) {
    if (pendingConfirmation == null) return

    AlertDialog(
        onDismissRequest = { onResult(pendingConfirmation.actionId, false) },
        title = { Text("تأكيد") },
        text = { Text(pendingConfirmation.message) },
        confirmButton = {
            TextButton(onClick = { onResult(pendingConfirmation.actionId, true) }) {
                Text("نعم")
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(pendingConfirmation.actionId, false) }) {
                Text("لا")
            }
        }
    )
}
