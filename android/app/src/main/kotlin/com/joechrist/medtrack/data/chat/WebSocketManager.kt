package com.joechrist.medtrack.data.chat

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// =============================================================================
// MedTrack – WebSocketManager
//
// Manages a single OkHttp WebSocket connection per chat room.
// Responsibilities:
//  • Attach Firebase token as ?token= query param (WS can't set headers)
//  • Emit incoming messages as a Flow<IncomingMessage>
//  • Expose connection state as a StateFlow<WsState>
//  • Reconnect with exponential backoff on unexpected closes
//  • Gracefully disconnect on sign-out or screen exit
// =============================================================================

@Singleton
class WebSocketManager @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) {
    companion object {
        private const val TAG = "MedTrack.WebSocket"
        private val BASE_WS_URL = System.getenv("ANDROID_API_BASE_URL")
            ?.replace("http://", "ws://")
            ?.replace("https://", "wss://")
            ?.trimEnd('/')
            ?: "ws://10.0.2.2:8080"

        private const val MAX_RETRIES = 6
        // Backoff: 1s, 2s, 4s, 8s, 16s, 32s
        private fun backoffMs(attempt: Int) = (1000L * (1 shl minOf(attempt, 5)))
    }

    // ── Public state ──────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<WsState>(WsState.Disconnected)
    val connectionState: StateFlow<WsState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(
        extraBufferCapacity = 64
    )
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages.asSharedFlow()

    // ── Internal state ────────────────────────────────────────────────────────

    private var activeSocket: WebSocket? = null
    private var currentRoomId: String?   = null
    private var retryJob: Job?           = null
    private var retryAttempt = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)   // keep connection alive through Doze
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)     // 0 = no read timeout for WebSocket
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── Connect ───────────────────────────────────────────────────────────────

    /**
     * Opens a WebSocket connection to [roomId].
     * If already connected to the same room, this is a no-op.
     * If connected to a different room, disconnects first.
     */
    fun connect(roomId: String) {
        if (currentRoomId == roomId && _connectionState.value is WsState.Connected) return
        disconnect()
        currentRoomId = roomId
        retryAttempt  = 0
        openSocket(roomId)
    }

    /** Gracefully closes the WebSocket. */
    fun disconnect() {
        retryJob?.cancel()
        activeSocket?.close(1000, "Client disconnect")
        activeSocket  = null
        currentRoomId = null
        _connectionState.value = WsState.Disconnected
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Sends a text message to the current room.
     * @return true if the message was enqueued by OkHttp (delivery not guaranteed).
     */
    fun send(text: String): Boolean {
        val socket = activeSocket ?: return false
        if (_connectionState.value !is WsState.Connected) return false
        return socket.send(text)
    }

    // ── Internal socket lifecycle ─────────────────────────────────────────────

    private fun openSocket(roomId: String) {
        scope.launch {
            _connectionState.value = WsState.Connecting

            // Fetch a fresh Firebase token for the query param
            val token = runCatching {
                firebaseAuth.currentUser
                    ?.getIdToken(false)
                    ?.await()
                    ?.token
            }.getOrNull() ?: run {
                Log.e(TAG, "No Firebase token — cannot connect")
                _connectionState.value = WsState.Error("Authentication required")
                return@launch
            }

            val url = "$BASE_WS_URL/api/v1/chat/ws/$roomId?token=$token"
            val request = Request.Builder().url(url).build()

            activeSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WS connected to room $roomId")
                    retryAttempt = 0
                    _connectionState.value = WsState.Connected(roomId)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "WS message: $text")
                    runCatching {
                        val msg = json.decodeFromString<WsEnvelope>(text)
                        _incomingMessages.tryEmit(
                            IncomingMessage(
                                roomId      = roomId,
                                senderAlias = msg.sender,
                                senderRole  = msg.role,
                                content     = msg.content,
                                sentAtMs    = runCatching {
                                    java.time.Instant.parse(msg.ts).toEpochMilli()
                                }.getOrElse { System.currentTimeMillis() }
                            )
                        )
                    }.onFailure { Log.w(TAG, "Failed to parse WS message: $text") }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WS closing: $code $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WS closed: $code $reason")
                    _connectionState.value = WsState.Disconnected
                    // Normal close (1000) or going away (1001) — no retry
                    if (code != 1000 && code != 1001) scheduleReconnect(roomId)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WS failure: ${t.message}")
                    _connectionState.value = WsState.Error(t.message ?: "Connection failed")
                    scheduleReconnect(roomId)
                }
            })
        }
    }

    private fun scheduleReconnect(roomId: String) {
        if (retryAttempt >= MAX_RETRIES) {
            Log.w(TAG, "Max reconnect attempts reached for room $roomId")
            _connectionState.value = WsState.Error("Could not reconnect after $MAX_RETRIES attempts")
            return
        }
        val delay = backoffMs(retryAttempt++)
        Log.d(TAG, "Reconnecting in ${delay}ms (attempt $retryAttempt)")
        _connectionState.value = WsState.Reconnecting(delayMs = delay, attempt = retryAttempt)

        retryJob = scope.launch {
            delay(delay)
            if (currentRoomId == roomId) openSocket(roomId)
        }
    }
}

// ── Domain models ─────────────────────────────────────────────────────────────

sealed class WsState {
    data object Disconnected : WsState()
    data object Connecting   : WsState()
    data class  Connected(val roomId: String) : WsState()
    data class  Reconnecting(val delayMs: Long, val attempt: Int) : WsState()
    data class  Error(val reason: String)    : WsState()
}

data class IncomingMessage(
    val roomId: String,
    val senderAlias: String,
    val senderRole: String,
    val content: String,
    val sentAtMs: Long
)

@Serializable
data class WsEnvelope(
    val sender: String,
    val role: String,
    val content: String,
    val ts: String
)
