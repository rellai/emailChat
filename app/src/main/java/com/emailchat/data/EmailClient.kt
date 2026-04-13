package com.emailchat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.mail.*
import javax.mail.Message
import javax.mail.internet.*

class EmailClient(
    private val account: EmailAccount,
    private val context: Context
) {

    private val smtpProperties = Properties().apply {
        put("mail.smtp.host", account.smtpHost)
        put("mail.smtp.port", account.smtpPort.toString())
        put("mail.smtp.ssl.enable", account.smtpUseSSL.toString())
        put("mail.smtp.auth", "true")
        put("mail.smtp.starttls.enable", "true")
        put("mail.smtp.connectiontimeout", "20000")
        put("mail.smtp.timeout", "30000")
        
        // Явное указание протоколов для уменьшения варнингов hiddenapi
        put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
        put("mail.smtp.ssl.trust", account.smtpHost)
    }

    /**
     * Отправляет письмо через SMTP.
     * messageId - должен быть уникальным (без скобок < >).
     */
    suspend fun sendMessage(toEmail: String, text: String, attachmentUris: List<Uri>, providedMsgId: String): String =
        withContext(Dispatchers.IO) {
            val TAG = "EmailClient"
            Log.d(TAG, "📤 [IO-Thread] Отправка письма: id=$providedMsgId, to=$toEmail")

            val session = Session.getInstance(smtpProperties)

            try {
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(account.email, account.displayName))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                    
                    subject = when {
                        text.isNotBlank() -> "💬 ${text.take(50)}"
                        attachmentUris.isNotEmpty() -> "📎 Вложение (${attachmentUris.size})"
                        else -> "Сообщение из Email Chat"
                    }
                    
                    sentDate = Date()
                    setHeader("X-Email-Chat", "true")
                    setHeader("Message-ID", "<$providedMsgId>")
                    setHeader("X-Email-Chat-ID", providedMsgId)

                    val multipart = MimeMultipart("mixed")

                    // 1. Текстовая часть
                    val textPart = MimeBodyPart()
                    textPart.setText(text, "utf-8")
                    multipart.addBodyPart(textPart)

                    // 2. Вложения
                    for ((idx, uri) in attachmentUris.withIndex()) {
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            if (inputStream == null) {
                                Log.w(TAG, "⚠️ Не удалось открыть URI: $uri")
                                continue
                            }
                            val bytes = inputStream.use { it.readBytes() }
                            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            val fileName = getFileName(uri) ?: "file_${System.currentTimeMillis()}_$idx"

                            val dataSource = ByteArrayDataSource(bytes, mimeType)
                            val attachmentPart = MimeBodyPart()
                            attachmentPart.dataHandler = DataHandler(dataSource)
                            attachmentPart.fileName = MimeUtility.encodeText(fileName)
                            attachmentPart.disposition = Part.ATTACHMENT
                            multipart.addBodyPart(attachmentPart)
                            Log.d(TAG, "✅ Вложение добавлено: $fileName ($mimeType)")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Ошибка вложения $idx: ${e.message}")
                        }
                    }
                    setContent(multipart)
                }

                Transport.send(message, account.email, account.password)
                Log.d(TAG, "✅ Письмо успешно отправлено")

                providedMsgId 

            } catch (e: Exception) {
                Log.e(TAG, "💥 Ошибка SMTP: ${e.message}", e)
                throw e
            }
        }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) result = cursor.getString(idx)
                }
            }
        }
        return result ?: uri.lastPathSegment
    }
}