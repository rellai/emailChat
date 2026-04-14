package com.emailchat.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emailchat.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    private val _currentConversation = MutableStateFlow<String?>(null)

    val conversations: Flow<List<Conversation>> = repository.getConversations()

    private val _pendingAtt = MutableStateFlow<List<Uri>>(emptyList())
    val pendingAtt: StateFlow<List<Uri>> = _pendingAtt.asStateFlow()

    fun addAtt(uris: List<Uri>) {
        _pendingAtt.value = _pendingAtt.value + uris
    }

    fun rmAtt(uri: Uri) {
        _pendingAtt.value = _pendingAtt.value - uri
    }

    fun clearAtt() {
        _pendingAtt.value = emptyList()
    }

    fun getMessages(conversationId: String): Flow<List<Message>> =
        repository.getMessages(conversationId)

    fun getAttachmentsForMessage(messageId: String): Flow<List<Attachment>> =
        repository.getAttachments(messageId)

    fun selectConversation(id: String) {
        _currentConversation.value = id
        viewModelScope.launch {
            repository.markAsRead(id)
        }
    }

    fun send(conversationId: String, text: String, attachments: List<Uri> = _pendingAtt.value) {
        if (text.isBlank() && attachments.isEmpty()) return

        // ✅ Очищаем список вложений МГНОВЕННО в UI
        clearAtt()

        viewModelScope.launch {
            try {
                // Отправляем сообщение через репозиторий
                repository.sendMessage(conversationId, text, attachments)
                Log.d("ChatVM", "✅ Message sent triggered from VM")
            } catch (e: Exception) {
                Log.e("ChatVM", "❌ Send error: ${e.message}")
                // В случае критической ошибки можно вернуть вложения обратно в _pendingAtt, 
                // если это необходимо, но обычно в мессенджерах они просто пропадают.
            }
        }
    }

    fun forceSync() {
        // Background sync is handled by EmailSyncService
    }
}