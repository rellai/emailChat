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
// ✅ Явные импорты с алиасами для избежания конфликтов
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
        Log.d(TAG, "🚀 onStartCommand вызван")
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
            .setContentText("Слушаем входящие сообщения...")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        startForeground(1, notification)
        Log.d(TAG, "📢 Foreground уведомление показано")
    }

    private fun startIdleSync() {
        serviceScope.launch {
            try {
                val prefs = applicationContext.dataStore.data.first()
                val email = prefs[PreferencesKeys.EMAIL] ?: run {
                    Log.w(TAG, "⚠️ Email не найден в настройках. Сервис остановлен.")
                    stopSelf()
                    return@launch
                }
                val password = prefs[PreferencesKeys.PASSWORD] ?: run {
                    Log.w(TAG, "⚠️ Пароль не найден в настройках.")
                    return@launch
                }
                val displayName = prefs[PreferencesKeys.DISPLAY_NAME] ?: email.substringBefore("@")

                val account = EmailAccount(
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

                Log.d(TAG, "📡 Инициализация IDLE: ${account.imapHost}:${account.imapPort}")
                val db = ChatDatabase.getInstance(applicationContext)

                idleManager = ImapIdleManager(account, applicationContext) { newMessages ->
                    Log.d(TAG, "📥 Callback: получено ${newMessages.size} сообщений от IDLE")
                    serviceScope.launch {
                        saveMessages(newMessages, account.email, db.chatDao())
                    }
                }

                idleManager?.start()
                Log.d(TAG, "✅ ImapIdleManager запущен")

            } catch (e: Exception) {
                Log.e(TAG, "💥 Критическая ошибка инициализации IDLE: ${e.message}", e)
            }
        }
    }

    private suspend fun saveMessages(msgs: List<ReceivedMessage>, myEmail: String, dao: ChatDao) {
        Log.d(TAG, "💾 Начинаем сохранение ${msgs.size} сообщений...")
        if (msgs.isEmpty()) return

        val dbMessages = mutableListOf<DbMessage>()

        for (msg in msgs) {
            // Проверка на дубликаты
            if (dao.getMessage(msg.messageId) != null) {
                Log.d(TAG, "⏭ Дубликат, пропускаем: ${msg.messageId}")
                continue
            }

            val isOutgoing = msg.fromEmail == myEmail
            val conversationId = if (isOutgoing) msg.toEmail else msg.fromEmail

            if (conversationId.isBlank()) {
                Log.w(TAG, "⚠️ Пустой conversationId для сообщения ${msg.messageId}, пропускаем")
                continue
            }

            val dbMsg = DbMessage(
                id = msg.messageId,
                conversationId = conversationId,
                text = msg.text,
                timestamp = msg.timestamp,
                isOutgoing = isOutgoing,
                isRead = false,
                serverUid = msg.serverUid,
                fromEmail = msg.fromEmail,
                toEmail = msg.toEmail
            )
            dbMessages.add(dbMsg)

            // Обновляем чат (увеличиваем unread только для входящих)
            val existingConv = dao.getConversation(conversationId)
            dao.insertConversation(DbConversation(
                id = conversationId,
                lastMessage = msg.text.take(100),
                lastMessageDate = msg.timestamp,
                unreadCount = if (isOutgoing) (existingConv?.unreadCount ?: 0) else (existingConv?.unreadCount ?: 0) + 1,
                contactName = conversationId.substringBefore("@")
            ))

            // Сохраняем вложения
            for (att in msg.attachments) {
                dao.insertAttachment(DbAttachment(
                    id = UUID.randomUUID().toString(),
                    messageId = msg.messageId,
                    fileName = att.fileName,
                    mimeType = att.mimeType,
                    fileSize = att.fileSize,
                    localPath = att.localPath,
                    isImage = att.isImage
                ))
            }
        }

        if (dbMessages.isNotEmpty()) {
            dao.insertMessages(dbMessages)
            Log.d(TAG, "✅ Успешно сохранено ${dbMessages.size} сообщений в Room")
        } else {
            Log.d(TAG, "⚠️ Нет новых сообщений для сохранения (все дубли или ошибки)")
        }
    }

    private fun registerNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w(TAG, "📉 Сеть потеряна. Останавливаем IDLE до восстановления.")
                serviceScope.launch { idleManager?.stop() }
            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "📈 Сеть доступна. Перезапускаем IDLE.")
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
        Log.d(TAG, "🌐 NetworkCallback зарегистрирован")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🛑 Сервис уничтожается")
        serviceScope.launch {
            idleManager?.stop()
            idleManager = null
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка отписки от NetworkCallback: ${e.message}")
        }
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "email_sync_channel",
                "Email Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Держит IMAP IDLE соединение для мгновенной доставки сообщений"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            Log.d(TAG, "📢 Канал уведомлений создан")
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