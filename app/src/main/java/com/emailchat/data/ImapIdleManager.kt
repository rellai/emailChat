package com.emailchat.data

import android.content.Context
import android.util.Log
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.*
import java.util.UUID
import javax.mail.*
import javax.mail.event.MessageCountAdapter
import javax.mail.Message
import javax.mail.event.MessageCountEvent
import javax.mail.internet.*

data class ReceivedAttachment(
    val fileName: String, val mimeType: String, val fileSize: Long,
    val localPath: String, val isImage: Boolean = mimeType.startsWith("image/")
)

data class ReceivedMessage(
    val messageId: String, val fromEmail: String, val fromName: String,
    val toEmail: String, val text: String, val subject: String,
    val timestamp: Long, val serverUid: String, val isChatMessage: Boolean,
    val attachments: List<ReceivedAttachment> = emptyList()
)

private data class ContentExtractionResult(val text: String, val attachments: List<ReceivedAttachment>)

class ImapIdleManager(
    private val account: EmailAccount,
    private val context: Context,
    private val onNewMessages: suspend (List<ReceivedMessage>) -> Unit
) {
    private val TAG = "ImapIdle"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var store: IMAPStore? = null
    private var folder: IMAPFolder? = null
    private val _status = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val statusFlow = _status.asSharedFlow()

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch { connectLoop() }
    }

    suspend fun stop() {
        Log.d(TAG, "🛑 Остановка IDLE")
        isRunning = false
        folder?.close(false)
        store?.close()
        store = null; folder = null
        scope.cancel()
    }

    private suspend fun connectLoop() {
        var delay = 2000L
        while (isRunning) {
            try {
                Log.d(TAG, "Подключение к ${account.imapHost}:${account.imapPort}...")
                _status.emit("Подключение...")
                connect()
                Log.d(TAG, "Подключено. Начинаем IDLE...")
                _status.emit("Ожидание сообщений...")
                delay = 2000L

                while (isRunning && folder?.isOpen == true) {
                    try {
                        folder?.idle()
                    } catch (e: Exception) {
                        Log.w(TAG, "IDLE прерван: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Соединение упало: ${e.message}", e)
                _status.emit("Переподключение...")
                delay(delay)
                delay = minOf(delay * 2, 60000L)
            }
        }
    }

    private fun connect() {
        val props = Properties().apply {
            put("mail.imap.host", account.imapHost)
            put("mail.imap.port", account.imapPort.toString())
            put("mail.imap.ssl.enable", account.imapUseSSL.toString())
            put("mail.imap.auth", "true")
            put("mail.imap.starttls.enable", "true")
            put("mail.imap.connectiontimeout", "20000")
            put("mail.imap.timeout", "30000")
            put("mail.imap.idle.timeout", "600000")
        }
        val session = Session.getInstance(props)

        store = session.getStore("imap") as IMAPStore
        Log.d(TAG, "Логин: ${account.email}")
        store?.connect(account.imapHost, account.email, account.password)
        Log.d(TAG, "Авторизация успешна")

        folder = store?.getFolder("INBOX") as? IMAPFolder ?: throw IllegalStateException("INBOX folder not found")

        folder?.open(Folder.READ_ONLY)
        Log.d(TAG, "Папка INBOX открыта. Всего сообщений: ${folder?.messageCount}")

        folder?.addMessageCountListener(object : MessageCountAdapter() {
            override fun messagesAdded(e: MessageCountEvent) {
                Log.d(TAG, "Событиe messagesAdded: ${e.messages.size} новых")
                if (!isRunning) {
                    Log.w(TAG, "Менеджер остановлен, пропускаем обработку")
                    return
                }
                scope.launch {
                    try {
                        val new = e.messages.mapNotNull { msg ->
                            Log.d(TAG, "Парсинг сообщения...")
                            parseMessage(msg)
                        }
                        Log.d(TAG, "Распарсено ${new.size} сообщений (после фильтрации)")
                        if (new.isNotEmpty()) {
                            Log.d(TAG, "Передача в callback...")
                            onNewMessages(new)
                        }
                    } catch (ex: Exception) {
                        Log.e(TAG, "Ошибка обработки: ${ex.message}", ex)
                    }
                }
            }
        })
    }

    private fun parseMessage(msg: Message): ReceivedMessage? {
        return try {
            val fromStr = msg.from?.firstOrNull()?.toString() ?: run {
                Log.w(TAG, "Нет отправителя, пропускаем")
                return null
            }
            val from = InternetAddress.parse(fromStr)[0]
            val to = msg.getRecipients(javax.mail.Message.RecipientType.TO)?.firstOrNull()?.let {
                InternetAddress.parse(it.toString())[0]
            }

            val contactEmail = if (from.address == account.email && to != null) {
                to.address
            } else {
                from.address
            }

            if (contactEmail == account.email && msg.getHeader("X-Email-Chat").isNullOrEmpty()) {
                Log.d(TAG, "Пропущено письмо самому себе без маркера чата")
                return null
            }

            val content = extractContentAndAttachments(msg)
            val isChat = msg.getHeader("X-Email-Chat")?.isNotEmpty() == true
            val acceptMessage = isChat

            if (!acceptMessage) {
                Log.d(TAG, "Пропущено письмо без маркера X-Email-Chat")
                return null
            }

            Log.d(TAG, "Сообщение принято: from=${from.address}, to=${to?.address}, chat=$isChat")

            ReceivedMessage(
                messageId = msg.getHeader("Message-ID")?.firstOrNull()?.trim('<', '>') ?: "",
                fromEmail = from.address,
                fromName = from.personal ?: from.address.substringBefore("@"),
                toEmail = to?.address ?: "",
                text = content.text,
                subject = msg.subject ?: "",
                timestamp = msg.sentDate?.time ?: System.currentTimeMillis(),
                serverUid = folder?.getUID(msg)?.toString() ?: "",
                isChatMessage = isChat,
                attachments = content.attachments
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}", e)
            null
        }
    }

    private fun extractContentAndAttachments(msg: Message): ContentExtractionResult {
        var text = ""
        val attachments = mutableListOf<ReceivedAttachment>()
        val attDir = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }

        try {
            when (val content = msg.content) {
                is String -> text = content
                is MimeMultipart -> {
                    for (i in 0 until content.count) {
                        val part = content.getBodyPart(i)
                        if (part.isMimeType("text/plain")) {
                            text = part.content.toString()
                            Log.d(TAG, "Извлечён текст: ${text.take(50)}...")
                        } else if (Part.ATTACHMENT.equals(part.disposition, true) || part.fileName != null) {
                            val rawFileName = part.fileName ?: "attachment_$i"
                            val fileName = MimeUtility.decodeText(rawFileName)
                            val mimeType = part.contentType?.split(";")?.firstOrNull()?.trim() ?: "application/octet-stream"
                            val safeName = fileName.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(80)
                            val file = File(attDir, "${UUID.randomUUID()}_$safeName")

                            part.inputStream?.use { input ->
                                file.outputStream().use { output -> input.copyTo(output) }
                            }
                            attachments.add(ReceivedAttachment(fileName, mimeType, file.length(), file.absolutePath, mimeType.startsWith("image/")))
                            Log.d(TAG, "Вложение сохранено: $fileName (${file.length()} байт)")
                        }
                    }
                }
                else -> Log.w(TAG, "Неизвестный тип контента: ${content::class}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extract error: ${e.message}", e)
        }
        return ContentExtractionResult(text, attachments)
    }
}