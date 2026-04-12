package com.emailchat.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emailchat.data.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val ctx: Context,
    private val db: ChatDatabase
) : ViewModel() {

    // ═══════════════════════════════════════════════════════════
    // 📊 СОСТОЯНИЕ (STATE)
    // ═══════════════════════════════════════════════════════════

    private val _currentConversation = MutableStateFlow<String?>(null)
    val currentConversation: StateFlow<String?> = _currentConversation

    val conversations: Flow<List<Conversation>> = db.chatDao().getAllConversations()

    // Вложения, выбранные для отправки
    private val _pendingAtt = MutableStateFlow<List<Uri>>(emptyList())
    val pendingAtt: StateFlow<List<Uri>> = _pendingAtt.asStateFlow()

    // ═══════════════════════════════════════════════════════════
    // 📎 УПРАВЛЕНИЕ ВЛОЖЕНИЯМИ
    // ═══════════════════════════════════════════════════════════

    fun addAtt(uris: List<Uri>) {
        _pendingAtt.value = _pendingAtt.value + uris
    }

    fun rmAtt(uri: Uri) {
        _pendingAtt.value = _pendingAtt.value - uri
    }

    fun clearAtt() {
        _pendingAtt.value = emptyList()
    }

    // ═══════════════════════════════════════════════════════════
    // 💬 РАБОТА С СООБЩЕНИЯМИ
    // ═══════════════════════════════════════════════════════════

    fun getMessages(conversationId: String): Flow<List<Message>> =
        db.chatDao().getMessages(conversationId)

    fun getAttachmentsForMessage(messageId: String): Flow<List<Attachment>> =
        db.chatDao().getAttachmentsForMessageFlow(messageId)

    fun selectConversation(id: String) {
        _currentConversation.value = id
        viewModelScope.launch {
            db.chatDao().markAsRead(id)
        }
    }

    fun send(conversationId: String, text: String, attachments: List<Uri> = _pendingAtt.value) {
        // Пустое сообщение без вложений не отправляем
        if (text.isBlank() && attachments.isEmpty()) return

        viewModelScope.launch {
            val account = getAccount() ?: run {
                Log.e("ChatVM", "❌ Аккаунт не найден в DataStore")
                return@launch
            }

            val client = EmailClient(account, ctx)

            // 1. Генерируем уникальный ID сообщения
            val msgId = "msg_${System.currentTimeMillis()}_${(1000..9999).random()}"

            // 2. Создаём объект сообщения
            val newMessage = Message(
                id = msgId,
                conversationId = conversationId,
                text = text,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                isRead = true,
                serverUid = "",
                fromEmail = account.email,
                toEmail = conversationId
            )

            // 3. СРАЗУ сохраняем в БД (OnConflictStrategy.REPLACE обновит, если есть дубль)
            db.chatDao().insertMessage(newMessage)
            Log.d("ChatVM", "💾 Сообщение сохранено локально: $msgId")

            // 4. Сохраняем вложения в БД с копированием во внутреннее хранилище
            val attachmentEntities = mutableListOf<Attachment>()
            if (attachments.isNotEmpty()) {
                for (uri in attachments) {
                    try {
                        // Копируем файл во внутреннее хранилище
                        val inputStream = ctx.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            val fileName = uri.toString().substringAfterLast("/")
                            val cacheDir = ctx.cacheDir.resolve("attachments")
                            if (!cacheDir.exists()) cacheDir.mkdirs()
                            val outFile = cacheDir.resolve("${msgId}_$fileName")
                            
                            inputStream.use { input ->
                                outFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            
                            val mimeType = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                            val fileSize = outFile.length()
                            
                            attachmentEntities.add(
                                Attachment(
                                    messageId = msgId,
                                    fileName = fileName,
                                    mimeType = mimeType,
                                    fileSize = fileSize,
                                    localPath = outFile.absolutePath,
                                    isImage = mimeType.startsWith("image/")
                                )
                            )
                            Log.d("ChatVM", "📎 Файл скопирован: ${outFile.absolutePath}")
                        }
                    } catch (e: Exception) {
                        Log.e("ChatVM", "❌ Ошибка копирования файла: ${e.message}", e)
                    }
                }
                if (attachmentEntities.isNotEmpty()) {
                    db.chatDao().insertAttachments(attachmentEntities)
                    Log.d("ChatVM", "📎 Вложения сохранены в БД: ${attachmentEntities.size}")
                }
            }

            // 5. СРАЗУ обновляем список чатов (чтобы пользователь видел сообщение мгновенно)
            updateConversation(conversationId, text, isOutgoing = true)

            try {
                // 6. Отправляем через SMTP в фоне
                Log.d("ChatVM", "📤 Отправка на $conversationId...")
                val realMessageId = client.sendMessage(conversationId, text, attachments)
                Log.d("ChatVM", "✅ Отправлено успешно. Message-ID: $realMessageId")

                // 7. Опционально: обновляем ID в БД на реальный (если сервер вернул другой)
                if (realMessageId != msgId && realMessageId.isNotBlank()) {
                    db.chatDao().insertMessage(newMessage.copy(id = realMessageId))
                    // Обновляем messageId у вложений
                    if (attachmentEntities.isNotEmpty()) {
                        val updatedAttachments = attachmentEntities.map {
                            it.copy(messageId = realMessageId)
                        }
                        db.chatDao().insertAttachments(updatedAttachments)
                        db.chatDao().deleteAttachmentsByMessage(msgId)
                    }
                }

                // 8. Очищаем вложения после успешной отправки
                clearAtt()

            } catch (e: Exception) {
                // ⚠️ Сообщение УЖЕ в БД — пользователь его видит, даже если отправка упала
                // При следующем запуске можно добавить повторную отправку (retry queue)
                Log.e("ChatVM", "❌ Ошибка отправки: ${e.message}", e)
                clearAtt() // Всё равно очищаем, чтобы не блокировать интерфейс
            }
        }
    }

    // Добавлено для отображения вложений в исходящих сообщениях
    fun getPendingAttachments(): List<Uri> = _pendingAtt.value

    // ═══════════════════════════════════════════════════════════
    // 🗃️ ОБНОВЛЕНИЕ СПИСКА ЧАТОВ
    // ═══════════════════════════════════════════════════════════

    private suspend fun updateConversation(contactEmail: String, text: String?, isOutgoing: Boolean) {
        val existing = db.chatDao().getConversation(contactEmail)

        db.chatDao().insertConversation(
            Conversation(
                id = contactEmail,
                lastMessage = text?.takeIf { it.isNotBlank() } ?: "📎 Вложение",
                lastMessageDate = System.currentTimeMillis(),
                // Если сообщение исходящее — не увеличиваем счётчик непрочитанных
                // Если входящее — +1 к непрочитанным
                unreadCount = if (isOutgoing) {
                    existing?.unreadCount ?: 0
                } else {
                    (existing?.unreadCount ?: 0) + 1
                },
                contactName = contactEmail.substringBefore("@")
            )
        )
        Log.d("ChatVM", "🗃️ Чат обновлён: $contactEmail")
    }

    // ═══════════════════════════════════════════════════════════
    // 🔐 ПОЛУЧЕНИЕ АККАУНТА ИЗ DATASTORE
    // ═══════════════════════════════════════════════════════════

    private suspend fun getAccount(): EmailAccount? {
        val prefs = ctx.dataStore.data.first()

        val email = prefs[PreferencesKeys.EMAIL] ?: return null
        val password = prefs[PreferencesKeys.PASSWORD] ?: return null
        val displayName = prefs[PreferencesKeys.DISPLAY_NAME] ?: email.substringBefore("@")

        return EmailAccount(
            email = email,
            password = password,
            displayName = displayName,
            imapHost = prefs[PreferencesKeys.IMAP_HOST]?.takeIf { it.isNotBlank() }
                ?: "imap.${email.substringAfter("@")}",
            imapPort = prefs[PreferencesKeys.IMAP_PORT] ?: 993,
            imapUseSSL = prefs[PreferencesKeys.IMAP_USE_SSL] ?: true,
            smtpHost = prefs[PreferencesKeys.SMTP_HOST]?.takeIf { it.isNotBlank() }
                ?: "smtp.${email.substringAfter("@")}",
            smtpPort = prefs[PreferencesKeys.SMTP_PORT] ?: 465,
            smtpUseSSL = prefs[PreferencesKeys.SMTP_USE_SSL] ?: true
        )
    }

    // ═══════════════════════════════════════════════════════════
    // 🔄 ПРИНУДИТЕЛЬНАЯ СИНХРОНИЗАЦИЯ (опционально)
    // ═══════════════════════════════════════════════════════════

    fun forceSync() {
        viewModelScope.launch {
            Log.d("ChatVM", "🔄 Принудительная синхронизация запрошена")
            // Синхронизация работает автоматически через EmailSyncService (IMAP IDLE)
            // Этот метод можно использовать для кнопки "Обновить" в UI
        }
    }
}