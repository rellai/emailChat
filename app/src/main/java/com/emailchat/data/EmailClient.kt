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
    }

    /**
     * Отправляет письмо через SMTP.
     * ВСЯ сетевая работа выполняется в Dispatchers.IO
     */
    suspend fun sendMessage(toEmail: String, text: String, attachmentUris: List<Uri>): String =
        withContext(Dispatchers.IO) {  // ✅ КЛЮЧЕВОЕ: переключение на фоновый поток
            val TAG = "EmailClient"
            Log.d(TAG, "📤 [IO-Thread] Начинаем отправку: to=$toEmail")

            val messageId = "${System.currentTimeMillis()}.${(1000..9999).random()}@${account.email.substringAfter("@")}"
            val session = Session.getInstance(smtpProperties)

            try {
                // Формирование сообщения (это можно делать в любом потоке)
                val message = MimeMessage(session).apply {
                    Log.d(TAG, "📝 Формируем MimeMessage...")
                    setFrom(InternetAddress(account.email, account.displayName))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                    subject = if (text.isBlank()) "📎 Файл" else "💬 ${text.take(40)}"
                    sentDate = Date()
                    setHeader("X-Email-Chat", "true")
                    setHeader("Message-ID", "<$messageId>")

                    val multipart = MimeMultipart("mixed")

                    // Текст
                    val textPart = MimeBodyPart()
                    textPart.setText(text, "utf-8")
                    multipart.addBodyPart(textPart)

                    // Вложения
                    for ((idx, uri) in attachmentUris.withIndex()) {
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            if (inputStream == null) {
                                Log.w(TAG, "⚠️ Не удалось открыть InputStream для $uri")
                                continue
                            }
                            val bytes = inputStream.use { it.readBytes() }
                            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            val fileName = getFileName(uri) ?: "attachment_$idx"

                            val dataSource = ByteArrayDataSource(bytes, mimeType)
                            val attachmentPart = MimeBodyPart()
                            attachmentPart.dataHandler = DataHandler(dataSource)
                            attachmentPart.fileName = MimeUtility.encodeText(fileName)
                            attachmentPart.disposition = Part.ATTACHMENT
                            multipart.addBodyPart(attachmentPart)
                            Log.d(TAG, "✅ Вложение #$idx добавлено: $fileName")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Ошибка вложения #$idx: ${e.message}", e)
                        }
                    }
                    setContent(multipart)
                }

                // 🔥 САМЫЙ ВАЖНЫЙ МОМЕНТ: отправка в фоне
                Log.d(TAG, "🔌 [IO-Thread] Вызов Transport.send()...")
                Transport.send(message, account.email, account.password)
                Log.d(TAG, "✅ [IO-Thread] Отправлено успешно! Message-ID: $messageId")

                messageId // возвращаем результат

            } catch (e: AuthenticationFailedException) {
                Log.e(TAG, "🔐 SMTP Auth failed: ${e.message}", e)
                throw Exception("Неверный логин/пароль для SMTP. Используйте «Пароль приложения».")
            } catch (e: SendFailedException) {
                Log.e(TAG, "📮 Send failed: ${e.message}", e)
                throw Exception("Не удалось отправить: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "💥 SMTP error: ${e.message}", e)
                throw e
            }
        } // конец withContext(Dispatchers.IO)

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