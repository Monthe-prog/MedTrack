package com.joechrist.medtrack.data.local.dao

import androidx.room.*
import com.joechrist.medtrack.data.local.entity.ChatMessageEntity
import com.joechrist.medtrack.data.local.entity.ChatRoomEntity
import kotlinx.coroutines.flow.Flow

// =============================================================================
// MedTrack – Chat DAOs
// =============================================================================

@Dao
interface ChatRoomDao {

    @Upsert
    suspend fun upsert(room: ChatRoomEntity)

    @Query("SELECT * FROM chat_rooms ORDER BY lastMessageMs DESC")
    fun observeAll(): Flow<List<ChatRoomEntity>>

    @Query("SELECT * FROM chat_rooms WHERE id = :roomId LIMIT 1")
    suspend fun findById(roomId: String): ChatRoomEntity?

    @Query("""
        UPDATE chat_rooms
        SET lastMessagePreview = :preview, lastMessageMs = :ms
        WHERE id = :roomId
    """)
    suspend fun updateLastMessage(roomId: String, preview: String, ms: Long)

    @Query("UPDATE chat_rooms SET unreadCount = 0 WHERE id = :roomId")
    suspend fun clearUnread(roomId: String)

    @Query("UPDATE chat_rooms SET unreadCount = unreadCount + 1 WHERE id = :roomId")
    suspend fun incrementUnread(roomId: String)
}

@Dao
interface ChatMessageDao {

    @Upsert
    suspend fun upsert(message: ChatMessageEntity)

    @Upsert
    suspend fun upsertAll(messages: List<ChatMessageEntity>)

    /** Live observable — the chat list auto-updates as new messages arrive. */
    @Query("""
        SELECT * FROM chat_messages
        WHERE roomId = :roomId
        ORDER BY sentAtMs ASC
    """)
    fun observeMessages(roomId: String): Flow<List<ChatMessageEntity>>

    /** Load a page of older messages for pagination on scroll-to-top. */
    @Query("""
        SELECT * FROM chat_messages
        WHERE roomId = :roomId AND sentAtMs < :beforeMs
        ORDER BY sentAtMs DESC
        LIMIT :pageSize
    """)
    suspend fun loadBefore(roomId: String, beforeMs: Long, pageSize: Int = 40): List<ChatMessageEntity>

    @Query("""
        UPDATE chat_messages
        SET sendStatus = :state
        WHERE id = :id
    """)
    suspend fun updateStatus(id: String, state: String)

    /** Mark all messages in a room as read. */
    @Query("UPDATE chat_messages SET isRead = 1 WHERE roomId = :roomId")
    suspend fun markAllRead(roomId: String)

    @Query("""
        SELECT COUNT(*) FROM chat_messages
        WHERE roomId = :roomId AND isRead = 0
    """)
    fun observeUnreadCount(roomId: String): Flow<Int>

    @Query("DELETE FROM chat_messages WHERE roomId = :roomId AND sentAtMs < :beforeMs")
    suspend fun evictOld(roomId: String, beforeMs: Long)
}
