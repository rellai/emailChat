package com.emailchat.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// ═══════════════════════════════════════════════════════════
// 📦 СУЩНОСТИ (ENTITIES)
// ═══════════════════════════════════════════════════════════

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String = "",            // Email собеседника
    val lastMessage: String = "",
    val lastMessageDate: Long = 0,
    val unreadCount: Int = 0,
    val contactName: String = ""
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"])]
)
data class Message(
    @PrimaryKey val id: String = "",            // Message-ID из email
    val conversationId: String = "",            // Email собеседника
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = false,
    val isRead: Boolean = false,
    val serverUid: String = "",                 // UID на IMAP-сервере
    val fromEmail: String = "",
    val toEmail: String = ""
)

@Entity(
    tableName = "attachments",
    indices = [Index(value = ["messageId"])]
)
data class Attachment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val localPath: String,
    val isImage: Boolean = mimeType.startsWith("image/")
)

// ═══════════════════════════════════════════════════════════
// 🗃️ DAO (Data Access Object)
// ═══════════════════════════════════════════════════════════

@Dao
interface ChatDao {

    // ── Чаты / Диалоги ──
    @Query("SELECT * FROM conversations ORDER BY lastMessageDate DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: Conversation)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun markAsRead(id: String)

    // ── Сообщения ──
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: String): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<Message>)

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessage(id: String): Message?

    @Query("DELETE FROM messages WHERE conversationId = :id")
    suspend fun deleteMessagesByConversation(id: String)

    // ── Вложения ──
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttachment(attachment: Attachment)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttachments(attachments: List<Attachment>)

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    suspend fun getAttachmentsForMessage(messageId: String): List<Attachment>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    fun getAttachmentsForMessageFlow(messageId: String): Flow<List<Attachment>>

    @Query("SELECT * FROM attachments WHERE messageId IN (:messageIds)")
    suspend fun getAttachmentsForMessages(messageIds: List<String>): List<Attachment>

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun deleteAttachmentsByMessage(messageId: String)
}

// ═══════════════════════════════════════════════════════════
// 🔄 МИГРАЦИЯ (Версия 1 → 2)
// ═══════════════════════════════════════════════════════════

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `attachments` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `messageId` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL,
                `fileSize` INTEGER NOT NULL,
                `localPath` TEXT NOT NULL,
                `isImage` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachments_messageId` ON `attachments` (`messageId`)"
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 🏗️ DATABASE INSTANCE
// ═══════════════════════════════════════════════════════════

@Database(
    entities = [Conversation::class, Message::class, Attachment::class],
    version = 2,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "email_chat.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // Включить только на время разработки:
                    // .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}