package com.joechrist.medtrack.data.repository

import com.joechrist.medtrack.data.chat.IncomingMessage
import com.joechrist.medtrack.data.chat.WebSocketManager
import com.joechrist.medtrack.data.chat.WsState
import com.joechrist.medtrack.data.local.dao.ChatMessageDao
import com.joechrist.medtrack.data.local.dao.ChatRoomDao
import com.joechrist.medtrack.data.local.entity.ChatMessageEntity
import com.joechrist.medtrack.data.local.entity.ChatRoomEntity
import com.joechrist.medtrack.data.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// =============================================================================
// MedTrack – ChatRepository
//
// Single source of truth for all chat data. Bridges:
//   WebSocketManager  → inbound messages from server
//   Room (local DB)   → persistent message storage + offline reads
//   SessionManager    → resolve current user's identity for sending
//
// Message lifecycle:
//   SEND:  insert PENDING to Room → UI shows immediately → send via WS
//          on WS confirm: mark SENT in Room
//          on WS failure: mark FAILED in Room (UI shows retry option)
//
//   RECEIVE: incoming WS envelope → map to ChatMessageEntity → upsert Room
//            → Flow emits → UI scrolls to new message
// =============================================================================

@Singleton
class ChatRepository @Inject constructor(
    private val wsManager: WebSocketManager,
    private val chatRoomDao: ChatRoomDao,
    private val chatMessageDao: ChatMessageDao,
    private val session: SessionManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Connection state passthrough ──────────────────────────────────────────
    val connectionState: StateFlow<WsState> = wsManager.connectionState

    // ── Room list ─────────────────────────────────────────────────────────────
    fun observeRooms(): Flow<List<ChatRoomEntity>> = chatRoomDao.observeAll()

    // ── Message stream for a room ─────────────────────────────────────────────
    fun observeMessages(roomId: String): Flow<List<ChatMessageEntity>> =
        chatMessageDao.observeMessages(roomId)

    // ── Connect to a room ─────────────────────────────────────────────────────
    fun connect(roomId: String) {
        wsManager.connect(roomId)
        // Start collecting inbound messages while connected
        scope.launch {
            wsManager.incomingMessages
                .filter { it.roomId == roomId }
                .collect { incoming -> persistIncoming(incoming, roomId) }
        }
    }

    fun disconnect() = wsManager.disconnect()

    // ── Mark messages as read on entering screen ──────────────────────────────
    suspend fun markRoomRead(roomId: String) {
        chatMessageDao.markAllRead(roomId)
        chatRoomDao.clearUnread(roomId)
    }

    // ── Send a message ────────────────────────────────────────────────────────
    /**
     * Optimistic send:
     *  1. Persist with STATUS_PENDING → UI shows immediately with spinner
     *  2. Send over WebSocket
     *  3. Mark SENT on success, FAILED on error
     *
     * @return The local message ID (used to retry / cancel)
     */
    suspend fun sendMessage(roomId: String, content: String): String {
        val cached = session.getSession()
        val localId = UUID.randomUUID().toString()
        val nowMs   = System.currentTimeMillis()

        val localMsg = ChatMessageEntity(
            id                = localId,
            roomId            = roomId,
            senderFirebaseUid = cached?.firebaseUid ?: "",
            senderAlias       = cached?.displayName ?: "Me",
            senderRole        = cached?.role?.name ?: "PATIENT",
            content           = content,
            sentAtMs          = nowMs,
            sendStatus        = ChatMessageEntity.STATUS_PENDING,
            localOnly         = true
        )

        // Step 1: Persist optimistically
        chatMessageDao.upsert(localMsg)
        updateRoomPreview(roomId, content, nowMs)

        // Step 2: Try WebSocket send
        scope.launch {
            val sent = wsManager.send(content)
            chatMessageDao.updateStatus(
                localId,
                if (sent) ChatMessageEntity.STATUS_SENT else ChatMessageEntity.STATUS_FAILED
            )
        }

        return localId
    }

    /** Retry a FAILED message. */
    suspend fun retryMessage(messageId: String, roomId: String) {
        val msg = chatMessageDao.loadBefore(roomId, Long.MAX_VALUE, 200)
            .firstOrNull { it.id == messageId } ?: return
        chatMessageDao.updateStatus(messageId, ChatMessageEntity.STATUS_PENDING)
        scope.launch {
            val sent = wsManager.send(msg.content)
            chatMessageDao.updateStatus(
                messageId,
                if (sent) ChatMessageEntity.STATUS_SENT else ChatMessageEntity.STATUS_FAILED
            )
        }
    }

    // ── Upsert a chat room (call when entering a conversation) ────────────────
    suspend fun ensureRoom(
        roomId: String,
        doctorId: String,
        patientId: String,
        doctorName: String,
        patientName: String,
        patientAnonAlias: String
    ) {
        val existing = chatRoomDao.findById(roomId)
        if (existing == null) {
            chatRoomDao.upsert(
                ChatRoomEntity(
                    id               = roomId,
                    doctorId         = doctorId,
                    patientId        = patientId,
                    doctorName       = doctorName,
                    patientName      = patientName,
                    patientAnonAlias = patientAnonAlias
                )
            )
        }
    }

    // ── Load older messages for pagination ────────────────────────────────────
    suspend fun loadBefore(roomId: String, beforeMs: Long): List<ChatMessageEntity> =
        chatMessageDao.loadBefore(roomId, beforeMs)

    // ── Internal helpers ──────────────────────────────────────────────────────

    private suspend fun persistIncoming(incoming: IncomingMessage, roomId: String) {
        val entity = ChatMessageEntity(
            id                = "${incoming.senderAlias}_${incoming.sentAtMs}",
            roomId            = roomId,
            senderFirebaseUid = "",             // server doesn't send uid in envelope
            senderAlias       = incoming.senderAlias,
            senderRole        = incoming.senderRole,
            content           = incoming.content,
            sentAtMs          = incoming.sentAtMs,
            sendStatus        = ChatMessageEntity.STATUS_SENT,
            localOnly         = false
        )
        chatMessageDao.upsert(entity)
        chatRoomDao.incrementUnread(roomId)
        updateRoomPreview(roomId, incoming.content, incoming.sentAtMs)
    }

    private suspend fun updateRoomPreview(roomId: String, content: String, ms: Long) {
        val preview = if (content.length > 50) content.take(47) + "…" else content
        chatRoomDao.updateLastMessage(roomId, preview, ms)
    }
}
