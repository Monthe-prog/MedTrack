package com.joechrist.medtrack.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joechrist.medtrack.data.chat.WsState
import com.joechrist.medtrack.data.local.entity.ChatMessageEntity
import com.joechrist.medtrack.data.repository.ChatRepository
import com.joechrist.medtrack.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// =============================================================================
// MedTrack – ChatViewModel
// =============================================================================

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val session: SessionManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Passed via nav argument: "roomId"
    val roomId: String = checkNotNull(savedStateHandle["roomId"])

    // ── Connection state ──────────────────────────────────────────────────────
    val connectionState: StateFlow<WsState> = chatRepository.connectionState

    // ── Messages (live Room Flow) ─────────────────────────────────────────────
    val messages: StateFlow<List<ChatMessageEntity>> = chatRepository
        .observeMessages(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Composer state ────────────────────────────────────────────────────────
    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    val canSend: StateFlow<Boolean> = combine(draftText, connectionState) { text, state ->
        text.isNotBlank() && state is WsState.Connected
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ── Pagination ────────────────────────────────────────────────────────────
    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()

    // ── One-off events (scroll to bottom) ────────────────────────────────────
    private val _scrollToBottom = MutableSharedFlow<Unit>()
    val scrollToBottom: SharedFlow<Unit> = _scrollToBottom.asSharedFlow()

    // ── My identity ──────────────────────────────────────────────────────────
    private val _myFirebaseUid = MutableStateFlow("")
    val myFirebaseUid: StateFlow<String> = _myFirebaseUid.asStateFlow()

    init {
        viewModelScope.launch {
            val cached = session.getSession()
            _myFirebaseUid.value = cached?.firebaseUid ?: ""
            chatRepository.markRoomRead(roomId)
            chatRepository.connect(roomId)
        }

        // Scroll to bottom whenever new messages arrive
        viewModelScope.launch {
            messages.drop(1).collect { _scrollToBottom.emit(Unit) }
        }
    }

    fun onDraftChange(text: String) {
        _draftText.value = text
    }

    fun sendMessage() {
        val text = _draftText.value.trim()
        if (text.isBlank()) return
        _draftText.value = ""
        viewModelScope.launch {
            chatRepository.sendMessage(roomId, text)
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            chatRepository.retryMessage(messageId, roomId)
        }
    }

    fun loadHistory() {
        if (_isLoadingHistory.value) return
        viewModelScope.launch {
            _isLoadingHistory.value = true
            val oldest = messages.value.firstOrNull()?.sentAtMs ?: return@launch
            chatRepository.loadBefore(roomId, oldest)
            _isLoadingHistory.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        chatRepository.disconnect()
    }
}
