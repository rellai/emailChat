package com.emailchat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.emailchat.MainActivity
import com.emailchat.data.Attachment as DbAttachment
import com.emailchat.data.ChatDao
import com.emailchat.data.ChatDatabase
import com.emailchat.data.Conversation as DbConversation
import com.emailchat.data.EmailAccount
import com.emailchat.data.ImapIdleManager
import com.emailchat.data.Message as DbMessage
import com.emailchat.data.PreferencesKeys
import com.emailchat.data.ReceivedMessage
import com.emailchat.data.dataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID

class EmailSyncService : Service() {

    private val TAG = "SyncService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var idleManager: ImapIdleManager? = null
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🔧 Сервис создан")
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🚀 onStartCommand")
        startForegroundNotification()
        startIdleSync()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "email_sync_channel")
            .setContentTitle("Email Chat")
            .setContentText("Синхронизация сообщений активна")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startIdleSync() {
        serviceScope.launch {
            try {
                val prefs = applicationContext.dataStore.data.first()
                val email = prefs[PreferencesKeys.EMAIL] ?: run {
                    stopSelf()
                    return@launch
                }
                val password = prefs[PreferencesKeys.PASSWORD] ?: return@launch
                
                val account = EmailAccount(
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

                val db = ChatDatabase.getInstance(applicationContext)

                idleManager = ImapIdleManager(account, applicationContext) { newMessages ->
                    serviceScope.launch {
                        saveMessages(newMessages, account.email, db.chatDao())
                    }
                }

                idleManager?.start()
            } catch (e: Exception) {
                Log.e(TAG, "Sync error: ${e.message}")
            }
        }
    }

    private suspend fun saveMessages(msgs: List<ReceivedMessage>, myEmail: String, dao: ChatDao) {
        if (msgs.isEmpty()) return
        val myEmailLower = myEmail.lowercase()

        for (msg in msgs) {
            val msgId = msg.messageId.lowercase()
            val fromEmail = msg.fromEmail.lowercase()
            val toEmail = msg.toEmail.lowercase()

            // 1. Определяем, является ли сообщение исходящим
            val isOutgoing = fromEmail == myEmailLower

            // 2. Определяем ID беседы (с кем общаемся)
            val effectiveConversationId = if (isOutgoing) {
                // Если я отправил сам себе, то беседа со мной
                if (toEmail == myEmailLower) myEmailLower else toEmail
            } else {
                // Если пришло мне, то беседа с отправителем
                fromEmail
            }

            if (effectiveConversationId.isBlank()) continue

            // 3. Проверяем дубликат
            val existing = dao.getMessage(msgId)
            if (existing != null) {
                // Если сообщение уже есть (было сохранено при отправке), 
                // обновляем только serverUid (для IMAP)
                if (existing.serverUid.isBlank() && msg.serverUid.isNotBlank()) {
                    dao.insertMessage(existing.copy(serverUid = msg.serverUid))
                    Log.d(TAG, "🔄 UID обновлен для $msgId")
                }
                continue 
            }

            // 4. Сохраняем новое сообщение
            val dbMsg = DbMessage(
                id = msgId,
                conversationId = effectiveConversationId,
                text = msg.text,
                timestamp = msg.timestamp,
                isOutgoing = isOutgoing,
                isRead = isOutgoing, // Свои сообщения прочитаны
                serverUid = msg.serverUid,
                fromEmail = fromEmail,
                toEmail = toEmail
            )
            dao.insertMessage(dbMsg)

            // 5. Обновляем беседу
            val existingConv = dao.getConversation(effectiveConversationId)
            dao.insertConversation(DbConversation(
                id = effectiveConversationId,
                lastMessage = if (msg.text.isBlank() && msg.attachments.isNotEmpty()) "📎 Вложение" else msg.text.take(100),
                lastMessageDate = msg.timestamp,
                unreadCount = if (isOutgoing) (existingConv?.unreadCount ?: 0) else (existingConv?.unreadCount ?: 0) + 1,
                contactName = existingConv?.contactName ?: effectiveConversationId.substringBefore("@")
            ))

            // 6. Сохраняем вложения
            for (att in msg.attachments) {
                dao.insertAttachment(DbAttachment(
                    messageId = msgId,
                    fileName = att.fileName,
                    mimeType = att.mimeType,
                    fileSize = att.fileSize,
                    localPath = att.localPath,
                    isImage = att.isImage
                ))
            }
            Log.d(TAG, "✅ Новое сообщение сохранено: $msgId (беседа: $effectiveConversationId)")
        }
    }

    private fun registerNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                serviceScope.launch { idleManager?.stop() }
            }
            override fun onAvailable(network: Network) {
                serviceScope.launch {
                    idleManager?.stop()
                    idleManager?.start()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            idleManager?.stop()
            idleManager = null
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {}
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "email_sync_channel",
                "Email Sync Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, EmailSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmailSyncService::class.java))
        }
    }
}