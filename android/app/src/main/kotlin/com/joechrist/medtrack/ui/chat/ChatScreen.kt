package com.joechrist.medtrack.ui.chat

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.joechrist.medtrack.data.chat.WsState
import com.joechrist.medtrack.data.local.entity.ChatMessageEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// =============================================================================
// MedTrack – Chat Screen
//
// Features:
//  • Message bubbles (mine right / theirs left) with tail shapes
//  • Role badge on each received message ("Doctor" / "Patient")
//  • PENDING spinner, SENT tick, FAILED retry button per message
//  • Connection status banner (animates in/out)
//  • Scroll-to-bottom FAB when not at the bottom
//  • Scroll-to-top triggers loadHistory() pagination
//  • Typing-aware keyboard — TextField expands with multi-line content
// =============================================================================

private val TimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DateSeparatorFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages     by viewModel.messages.collectAsState()
    val draft        by viewModel.draftText.collectAsState()
    val canSend      by viewModel.canSend.collectAsState()
    val wsState      by viewModel.connectionState.collectAsState()
    val myUid        by viewModel.myFirebaseUid.collectAsState()
    val isLoadingHistory by viewModel.isLoadingHistory.collectAsState()

    val listState    = rememberLazyListState()
    val showScrollFab by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex < messages.size - 3
        }
    }

    // Scroll to bottom on new messages
    LaunchedEffect(Unit) {
        viewModel.scrollToBottom.collect {
            if (messages.isNotEmpty())
                listState.animateScrollToItem(messages.lastIndex)
        }
    }

    // Load more history when scrolled to the very top
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (listState.firstVisibleItemIndex == 0 && messages.isNotEmpty()) {
            viewModel.loadHistory()
        }
    }

    Scaffold(
        topBar = {
            ChatTopBar(wsState = wsState, onBack = onBack)
        },
        bottomBar = {
            ChatComposer(
                draft     = draft,
                canSend   = canSend,
                wsState   = wsState,
                onDraftChange = viewModel::onDraftChange,
                onSend    = viewModel::sendMessage
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {

            // ── Message list ──────────────────────────────────────────────────
            LazyColumn(
                state           = listState,
                modifier        = Modifier.fillMaxSize(),
                contentPadding  = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // History loading indicator
                if (isLoadingHistory) {
                    item { LoadingHistoryItem() }
                }

                // Messages with date separators
                val grouped = groupByDay(messages)
                grouped.forEach { (dayLabel, dayMessages) ->
                    item(key = "sep_$dayLabel") {
                        DateSeparator(dayLabel)
                    }
                    items(dayMessages, key = { it.id }) { message ->
                        MessageBubble(
                            message    = message,
                            isMine     = message.senderFirebaseUid == myUid ||
                                         (message.localOnly && message.senderFirebaseUid.isEmpty()),
                            onRetry    = { viewModel.retryMessage(message.id) }
                        )
                    }
                }
            }

            // ── Scroll-to-bottom FAB ──────────────────────────────────────────
            AnimatedVisibility(
                visible = showScrollFab && messages.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 8.dp),
                enter = scaleIn(),
                exit  = scaleOut()
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        if (messages.isNotEmpty()) {
                            // Use coroutine scope to scroll
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "Scroll to bottom",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

// ── Top app bar ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(wsState: WsState, onBack: () -> Unit) {
    Column {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Avatar circle
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF1DB98A), Color(0xFF085041))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MedicalServices, null,
                            modifier = Modifier.size(18.dp), tint = Color.White)
                    }
                    Column {
                        Text("Chat", style = MaterialTheme.typography.titleMedium)
                        Text(
                            wsStateLabel(wsState),
                            style = MaterialTheme.typography.bodySmall,
                            color = wsStateColor(wsState)
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
            }
        )

        // Reconnecting banner (slides in below top bar)
        AnimatedVisibility(
            visible = wsState is WsState.Reconnecting || wsState is WsState.Error,
            enter   = expandVertically(),
            exit    = shrinkVertically()
        ) {
            ConnectionBanner(wsState)
        }
    }
}

@Composable
private fun ConnectionBanner(wsState: WsState) {
    val (bgColor, icon, text) = when (wsState) {
        is WsState.Reconnecting -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            Icons.Default.Sync,
            "Reconnecting… (attempt ${wsState.attempt})"
        )
        is WsState.Error -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Default.WifiOff,
            "Connection lost — messages will send when reconnected"
        )
        else -> Triple(Color.Transparent, Icons.Default.Check, "")
    }

    Surface(color = bgColor) {
        Row(
            modifier  = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (wsState is WsState.Reconnecting) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, null, modifier = Modifier.size(14.dp))
            }
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── Message bubble ────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: ChatMessageEntity,
    isMine: Boolean,
    onRetry: () -> Unit
) {
    val bubbleColor = if (isMine)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant

    val textColor = if (isMine)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    // Bubble shape: rounded with one "tail" corner less rounded
    val bubbleShape = if (isMine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = if (isMine) 64.dp else 12.dp,
                end    = if (isMine) 12.dp else 64.dp,
                top    = 2.dp,
                bottom = 2.dp
            ),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {

            // Sender badge (only on received messages)
            if (!isMine) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                ) {
                    RoleBadge(role = message.senderRole)
                    Text(
                        message.senderAlias,
                        style      = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color      = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Bubble
            Surface(
                shape = bubbleShape,
                color = bubbleColor
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text(
                        text  = message.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Timestamp + status row
            Row(
                modifier              = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    formatTime(message.sentAtMs),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isMine) {
                    SendStatusIndicator(
                        status  = message.sendStatus,
                        onRetry = onRetry
                    )
                }
            }
        }
    }
}

// ── Send status indicator ─────────────────────────────────────────────────────

@Composable
private fun SendStatusIndicator(status: String, onRetry: () -> Unit) {
    when (status) {
        ChatMessageEntity.STATUS_PENDING -> {
            CircularProgressIndicator(
                modifier    = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color       = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ChatMessageEntity.STATUS_SENT -> {
            Icon(Icons.Default.DoneAll, "Sent",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.secondary)
        }
        ChatMessageEntity.STATUS_FAILED -> {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.clickable(onClick = onRetry)
            ) {
                Icon(Icons.Default.ErrorOutline, "Failed",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.error)
                Text(
                    "Retry",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ── Role badge ────────────────────────────────────────────────────────────────

@Composable
private fun RoleBadge(role: String) {
    val (color, label) = when (role.uppercase()) {
        "DOCTOR" -> Color(0xFF0F3460) to "Doctor"
        "ADMIN"  -> Color(0xFF534AB7) to "Admin"
        else     -> Color(0xFF1D9E75) to "Patient"
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style      = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color      = color,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Chat composer ─────────────────────────────────────────────────────────────

@Composable
private fun ChatComposer(
    draft: String,
    canSend: Boolean,
    wsState: WsState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Text field
            OutlinedTextField(
                value         = draft,
                onValueChange = onDraftChange,
                placeholder   = {
                    Text(
                        when (wsState) {
                            is WsState.Connected -> "Type a message…"
                            is WsState.Connecting -> "Connecting…"
                            else -> "Offline — messages queue until reconnected"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Default   // allows multi-line
                ),
                maxLines = 5,
                shape    = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f)
            )

            // Send button
            val sendBg = if (canSend) MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.surfaceVariant

            FloatingActionButton(
                onClick           = { if (canSend) onSend() },
                modifier          = Modifier.size(48.dp),
                containerColor    = sendBg,
                contentColor      = if (canSend) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                elevation         = FloatingActionButtonDefaults.elevation(0.dp)
            ) {
                Icon(Icons.Default.Send, "Send", modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── Date separator ────────────────────────────────────────────────────────────

@Composable
private fun DateSeparator(label: String) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoadingHistoryItem() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────────

private fun formatTime(epochMs: Long): String = runCatching {
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(TimeFormatter)
}.getOrElse { "" }

private fun groupByDay(
    messages: List<ChatMessageEntity>
): List<Pair<String, List<ChatMessageEntity>>> {
    if (messages.isEmpty()) return emptyList()
    return messages.groupBy { msg ->
        val date = Instant.ofEpochMilli(msg.sentAtMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val today     = java.time.LocalDate.now()
        val yesterday = today.minusDays(1)
        when (date) {
            today     -> "Today"
            yesterday -> "Yesterday"
            else      -> date.format(DateSeparatorFormatter)
        }
    }.entries.map { it.key to it.value }
}

private fun wsStateLabel(state: WsState) = when (state) {
    is WsState.Connected    -> "Online"
    is WsState.Connecting   -> "Connecting…"
    is WsState.Reconnecting -> "Reconnecting…"
    is WsState.Error        -> "Offline"
    is WsState.Disconnected -> "Disconnected"
}

private fun wsStateColor(state: WsState): Color = when (state) {
    is WsState.Connected    -> Color(0xFF1D9E75)
    is WsState.Connecting   -> Color(0xFFBA7517)
    is WsState.Reconnecting -> Color(0xFFBA7517)
    else                    -> Color(0xFFA32D2D)
}
