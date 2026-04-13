package com.emailchat.data

import android.content.Context
import android.util.Log
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.UUID
import javax.mail.*
import javax.mail.event.MessageCountAdapter
import javax.mail.Message
import javax.mail.event.MessageCountEvent
import javax.mail.internet.*

data class ReceivedAttachment(
    val fileName: String, 
    val mimeType: String, 
    val fileSize: Long,
    val localPath: String, 
    val isImage: Boolean = mimeType.startsWith("image/")
)

data class ReceivedMessage(
    val messageId: String, 
    val fromEmail: String, 
    val fromName: String,
    val toEmail: String, 
    val text: String, 
    val subject: String,
    val timestamp: Long, 
    val serverUid: String, 
    val isChatMessage: Boolean,
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
            
            // Уменьшение варнингов hiddenapi
            put("mail.imap.ssl.protocols", "TLSv1.2 TLSv1.3")
            put("mail.imap.ssl.trust", account.imapHost)
        }
        val session = Session.getInstance(props)

        store = session.getStore("imap") as IMAPStore
        Log.d(TAG, "Логин: ${account.email}")
        store?.connect(account.imapHost, account.email, account.password)

        folder = store?.getFolder("INBOX") as? IMAPFolder ?: throw IllegalStateException("INBOX not found")
        folder?.open(Folder.READ_ONLY)

        folder?.addMessageCountListener(object : MessageCountAdapter() {
            override fun messagesAdded(e: MessageCountEvent) {
                if (!isRunning) return
                scope.launch {
                    try {
                        val new = e.messages.mapNotNull { msg -> parseMessage(msg) }
                        if (new.isNotEmpty()) onNewMessages(new)
                    } catch (ex: Exception) {
                        Log.e(TAG, "Error processing new messages: ${ex.message}")
                    }
                }
            }
        })
    }

    private fun parseMessage(msg: Message): ReceivedMessage? {
        return try {
            val fromStr = msg.from?.firstOrNull()?.toString() ?: return null
            val from = InternetAddress.parse(fromStr)[0]
            val to = msg.getRecipients(javax.mail.Message.RecipientType.TO)?.firstOrNull()?.let {
                InternetAddress.parse(it.toString())[0]
            }

            val isChat = msg.getHeader("X-Email-Chat")?.isNotEmpty() == true
            if (!isChat) return null

            val content = extractContentAndAttachments(msg)
            val msgId = msg.getHeader("Message-ID")?.firstOrNull()?.trim('<', '>', ' ')?.lowercase() ?: ""
            
            Log.d(TAG, "Parsed msgId=$msgId, textLen=${content.text.length}, attCount=${content.attachments.size}")

            ReceivedMessage(
                messageId = msgId,
                fromEmail = from.address.lowercase(),
                fromName = from.personal ?: from.address.substringBefore("@"),
                toEmail = to?.address?.lowercase() ?: "",
                text = content.text,
                subject = msg.subject ?: "",
                timestamp = msg.sentDate?.time ?: System.currentTimeMillis(),
                serverUid = folder?.getUID(msg)?.toString() ?: "",
                isChatMessage = isChat,
                attachments = content.attachments
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            null
        }
    }

    private fun extractContentAndAttachments(msg: Message): ContentExtractionResult {
        val textBuilder = StringBuilder()
        val attachments = mutableListOf<ReceivedAttachment>()
        val attDir = File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }

        fun parsePart(part: Part) {
            val contentType = part.contentType?.lowercase() ?: ""
            val disposition = part.disposition
            val rawFileName = part.fileName
            val fileName = rawFileName?.let { 
                try { MimeUtility.decodeText(it) } catch (e: Exception) { it }
            }
            
            val isAttachment = Part.ATTACHMENT.equals(disposition, true)
            val isInline = Part.INLINE.equals(disposition, true)
            
            if (part.isMimeType("text/plain") && !isAttachment && fileName == null) {
                val content = part.content
                val text = when (content) {
                    is String -> content
                    is InputStream -> content.bufferedReader().use { it.readText() }
                    else -> content.toString()
                }
                textBuilder.append(text)
            } else if (part.isMimeType("text/html") && !isAttachment && fileName == null) {
                if (textBuilder.isEmpty()) {
                    val content = part.content
                    val html = when (content) {
                        is String -> content
                        is InputStream -> content.bufferedReader().use { it.readText() }
                        else -> content.toString()
                    }
                    val stripped = html.replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ").trim()
                    textBuilder.append(stripped)
                }
            } else if (part.isMimeType("multipart/*")) {
                val mp = part.content as MimeMultipart
                for (i in 0 until mp.count) {
                    parsePart(mp.getBodyPart(i))
                }
            } else if (part.isMimeType("message/rfc822")) {
                parsePart(part.content as Part)
            } else {
                if (isAttachment || isInline || fileName != null) {
                    val mimeType = contentType.split(";").firstOrNull()?.trim() ?: "application/octet-stream"
                    val extension = when {
                        mimeType.contains("jpeg") || mimeType.contains("jpg") -> ".jpg"
                        mimeType.contains("png") -> ".png"
                        mimeType.contains("gif") -> ".gif"
                        mimeType.contains("pdf") -> ".pdf"
                        else -> ""
                    }
                    val actualName = fileName ?: "file_${System.currentTimeMillis()}$extension"
                    val safeName = actualName.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
                    val file = File(attDir, "${UUID.randomUUID()}_$safeName")
                    try {
                        part.inputStream.use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                        attachments.add(ReceivedAttachment(
                            fileName = actualName,
                            mimeType = mimeType,
                            fileSize = file.length(),
                            localPath = file.absolutePath,
                            isImage = mimeType.startsWith("image/")
                        ))
                    } catch (e: Exception) {
                        Log.e(TAG, "Save error $actualName: ${e.message}")
                    }
                }
            }
        }
        try { parsePart(msg) } catch (e: Exception) { Log.e(TAG, "Extract error: ${e.message}") }
        return ContentExtractionResult(textBuilder.toString().trim(), attachments)
    }
}