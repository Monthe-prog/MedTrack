package com.joechrist.medtrack.data.local.entity

import androidx.room.*

// =============================================================================
// MedTrack – Chat Entities
// Messages are persisted locally so the history is readable offline.
// The server is the source of truth; local rows sync on reconnect.
// =============================================================================

@Entity(
    tableName = "chat_rooms",
    indices = [Index("doctorId"), Index("patientId")]
)
data class ChatRoomEntity(
    @PrimaryKey val id: String,
    val doctorId: String,
    val patientId: String,
    val doctorName: String,
    val patientName: String,
    val patientAnonAlias: String,
    val lastMessagePreview: String = "",
    val lastMessageMs: Long = 0,
    val unreadCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity        = ChatRoomEntity::class,
            parentColumns = ["id"],
            childColumns  = ["roomId"],
            onDelete      = ForeignKey.CASCADE
        )
    ],
    indices = [Index("roomId"), Index("sentAtMs")]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val roomId: String,
    val senderFirebaseUid: String,
    val senderAlias: String,
    val senderRole: String,
    val content: String,
    val isRead: Boolean = false,
    val sentAtMs: Long = System.currentTimeMillis(),
    val sendStatus: String = STATUS_SENT,
    val localOnly: Boolean = false
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT    = "SENT"
        const val STATUS_FAILED  = "FAILED"
    }
}
