package com.example.floodapptest

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Таблица ЧАТОВ (для бокового меню)
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

// Таблица СООБЩЕНИЙ
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val chatId: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface ChatDao {
    // Работа с сессиями
    @Query("SELECT * FROM chat_sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    // --- НОВОЕ: Удаление сессии чата ---
    @Query("DELETE FROM chat_sessions WHERE id = :chatId")
    suspend fun deleteSessionById(chatId: String)

    // Работа с сообщениями
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    // Удаление переписки
    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteChatHistory(chatId: String)
}

@Database(entities = [ChatSessionEntity::class, MessageEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flood_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}