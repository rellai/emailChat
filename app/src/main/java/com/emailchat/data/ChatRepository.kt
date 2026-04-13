package com.emailchat.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.emailchat.data.Attachment as DbAttachment
import com.emailchat.data.Message as DbMessage
import com.emailchat.data.Conversation as DbConversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

class ChatRepository(
    private val context: Context,
    private val db: ChatDatabase
) {
    private val TAG = "ChatRepository"
    private val dao = db.chatDao()

    // --- Account Logic ---

    suspend fun getAccount(): EmailAccount? {
        val prefs = context.dataStore.data.first()
        val email = prefs[PreferencesKeys.EMAIL] ?: return null
        val password = prefs[PreferencesKeys.PASSWORD] ?: return null
        
        return EmailAccount(
            email = email,
            password = password,
            displayName = prefs[PreferencesKeys.DISPLAY_NAME] ?: email.substringBefore("@"),
            imapHost = prefs[PreferencesKeys.IMAP_HOST]?.takeIf { it.isNotBlank() } ?: "imap.${email.substringAfter("@")}",
            imapPort = prefs[PreferencesKeys.IMAP_PORT] ?: 993,
            imapUseSSL = prefs[PreferencesKeys.IMAP_USE_SSL] ?: true,
            smtpHost = prefs[PreferencesKeys.SMTP_HOST]?.takeIf { it.isNotBlank() } ?: "smtp.${email.substringAfter("@")}",
            smtpPort = prefs[PreferencesKeys.SMTP_PORT] ?: 465,
            smtpUseSSL = prefs[PreferencesKeys.SMTP_USE_SSL] ?: true
        )
    }

    // --- Chat & Messages ---

    fun getConversations(): Flow<List<DbConversation>> = dao.getAllConversations()
    
    fun getMessages(conversationId: String): Flow<List<DbMessage>> = dao.getMessages(conversationId)
    
    fun getAttachments(messageId: String): Flow<List<DbAttachment>> = dao.getAttachmentsForMessageFlow(messageId)

    suspend fun markAsRead(conversationId: String) = dao.markAsRead(conversationId)

    /**
     * Основной метод отправки
     */
    suspend fun sendMessage(conversationId: String, text: String, attachments: List<Uri>) {
        val account = getAccount() ?: throw Exception("Аккаунт не настроен")
        val client = EmailClient(account, context)

        // 1. Создаем уникальный ID
        val msgId = "${System.currentTimeMillis()}.${(1000..9999).random()}@${account.email.substringAfter("@")}".lowercase()
        val timestamp = System.currentTimeMillis()

        // 2. Предварительно сохраняем сообщение (Local Echo)
        val newMessage = DbMessage(
            id = msgId,
            conversationId = conversationId,
            text = text.trim(),
            timestamp = timestamp,
            isOutgoing = true,
            isRead = true,
            fromEmail = account.email,
            toEmail = conversationId
        )
        dao.insertMessage(newMessage)

        // 3. Обрабатываем вложения
        val savedAttachments = processAttachments(msgId, attachments)
        if (savedAttachments.isNotEmpty()) {
            dao.insertAttachments(savedAttachments)
        }

        // 4. Обновляем беседу
        updateConversationMetadata(conversationId, text, timestamp, true)

        // 5. Отправляем в сеть
        try {
            client.sendMessage(conversationId, text, attachments, msgId)
            Log.d(TAG, "✅ Сообщение отправлено успешно: $msgId")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки: ${e.message}")
            // Здесь можно добавить статус ERROR в БД для сообщения
            throw e
        }
    }

    private suspend fun processAttachments(messageId: String, uris: List<Uri>): List<DbAttachment> {
        val entities = mutableListOf<DbAttachment>()
        for (uri in uris) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: continue
                val fileName = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
                
                val cacheDir = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }
                val outFile = File(cacheDir, "${UUID.randomUUID()}_$fileName")
                
                inputStream.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }

                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                entities.add(DbAttachment(
                    messageId = messageId,
                    fileName = fileName,
                    mimeType = mimeType,
                    fileSize = outFile.length(),
                    localPath = outFile.absolutePath,
                    isImage = mimeType.startsWith("image/")
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Error processing attachment: ${e.message}")
            }
        }
        return entities
    }

    private suspend fun updateConversationMetadata(contactEmail: String, text: String, time: Long, isOutgoing: Boolean) {
        val existing = dao.getConversation(contactEmail)
        dao.insertConversation(DbConversation(
            id = contactEmail,
            lastMessage = if (text.isBlank()) "📎 Вложение" else text.take(100),
            lastMessageDate = time,
            unreadCount = if (isOutgoing) (existing?.unreadCount ?: 0) else (existing?.unreadCount ?: 0) + 1,
            contactName = existing?.contactName ?: contactEmail.substringBefore("@")
        ))
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) it.getString(idx) else null
                } else null
            }
        } catch (e: Exception) { null } ?: uri.lastPathSegment
    }
}