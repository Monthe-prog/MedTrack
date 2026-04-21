package com.joechrist.medtrack.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joechrist.medtrack.data.local.entity.ChatRoomEntity
import com.joechrist.medtrack.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// =============================================================================
// MedTrack – Chat Room List Screen
// Shows all active doctor-patient conversations with last message preview,
// unread badge, and relative timestamps.
// =============================================================================

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {
    val rooms: StateFlow<List<ChatRoomEntity>> = chatRepository
        .observeRooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomListScreen(
    onRoomClick: (roomId: String) -> Unit,
    viewModel: ChatListViewModel = hiltViewModel()
) {
    val rooms by viewModel.rooms.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Messages", style = MaterialTheme.typography.titleLarge)
                        if (rooms.isNotEmpty()) {
                            Text(
                                "${rooms.size} conversation${if (rooms.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (rooms.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ChatBubbleOutline, null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("No conversations yet",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Patient chats will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(rooms, key = { it.id }) { room ->
                    ChatRoomRow(
                        room    = room,
                        onClick = { onRoomClick(room.id) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
                }
            }
        }
    }
}

// ── Chat room row ─────────────────────────────────────────────────────────────

@Composable
private fun ChatRoomRow(
    room: ChatRoomEntity,
    onClick: () -> Unit
) {
    val hasUnread = room.unreadCount > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (hasUnread) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Avatar with gradient
        Box(
            modifier         = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF0F3460), Color(0xFF378ADD)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                room.patientName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color      = Color.White
            )
        }

        // Content
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    room.patientName,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal
                )
                if (room.lastMessageMs > 0) {
                    Text(
                        relativeTime(room.lastMessageMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasUnread) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                // Alias + last message
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        room.patientAnonAlias,
                        style      = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color      = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (room.lastMessagePreview.isNotBlank()) {
                        Text(
                            room.lastMessagePreview,
                            style    = MaterialTheme.typography.bodySmall,
                            color    = if (hasUnread) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Unread badge
                if (hasUnread) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            if (room.unreadCount > 99) "99+" else "${room.unreadCount}",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ── Relative time helper ──────────────────────────────────────────────────────

private fun relativeTime(epochMs: Long): String {
    val now      = System.currentTimeMillis()
    val diffMs   = now - epochMs
    val diffMins = diffMs / 60_000
    return when {
        diffMins < 1   -> "now"
        diffMins < 60  -> "${diffMins}m"
        diffMins < 1440 -> "${diffMins / 60}h"
        else -> Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d"))
    }
}
