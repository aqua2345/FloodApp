package com.example.floodapptest

import ChatRequest
import RouteData
import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

// ── Модели сообщений ──────────────────────────────────────────────────────────

sealed class ChatMessage {
    data class Text(val text: String, val isUser: Boolean) : ChatMessage()
    data class Route(val routeData: RouteData, val introText: String) : ChatMessage()
}

// Для сохранения в БД используем плоскую модель (только текст)
fun ChatMessage.toDbText(): String = when (this) {
    is ChatMessage.Text -> text
    is ChatMessage.Route -> introText
}
fun ChatMessage.isUserMessage(): Boolean = when (this) {
    is ChatMessage.Text -> isUser
    is ChatMessage.Route -> false
}

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Новый чат",
    val messages: MutableList<ChatMessage> = mutableStateListOf()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).chatDao()

    val sessions = mutableStateMapOf<String, ChatSession>()
    var currentChatId by mutableStateOf<String?>(null)
    val isLoading = mutableStateOf(false)

    var lastLat by mutableStateOf<Double?>(null)
    var lastLon by mutableStateOf<Double?>(null)

    private var currentJob: Job? = null

    val currentMessages: List<ChatMessage>
        get() = currentChatId?.let { sessions[it]?.messages } ?: emptyList()

    init {
        viewModelScope.launch {
            dao.getAllSessions().collect { savedSessions ->
                savedSessions.forEach { entity ->
                    if (!sessions.containsKey(entity.id)) {
                        sessions[entity.id] = ChatSession(id = entity.id, title = entity.title)
                    }
                }
            }
        }
    }

    fun loadMessages(chatId: String) {
        currentChatId = chatId
        val session = sessions[chatId] ?: return
        if (session.messages.isEmpty()) {
            viewModelScope.launch {
                dao.getMessagesForChat(chatId).collect { entities ->
                    session.messages.clear()
                    session.messages.addAll(
                        entities.map { ChatMessage.Text(it.text, it.isUser) }
                    )
                }
            }
        }
    }

    fun sendQuestion(text: String) {
        val chatId = currentChatId ?: createNewChat(text)
        val session = sessions[chatId]!!

        session.messages.add(ChatMessage.Text(text, true))
        isLoading.value = true

        currentJob = viewModelScope.launch {
            dao.insertMessage(MessageEntity(chatId = chatId, text = text, isUser = true))
            try {
                val response = RetrofitClient.apiService.sendMessage(
                    ChatRequest(text, chatId, lastLat, lastLon)
                )

                // Если бэкенд вернул route_data — добавляем маршрутное сообщение
                if (response.route_data != null) {
                    val routeMsg = ChatMessage.Route(
                        routeData = response.route_data,
                        introText = response.response
                    )
                    session.messages.add(routeMsg)
                } else {
                    session.messages.add(ChatMessage.Text(response.response, false))
                }

                // В БД сохраняем текстовую версию
                dao.insertMessage(
                    MessageEntity(chatId = chatId, text = response.response, isUser = false)
                )

                if (session.title == "Новый чат" || session.title == text) {
                    val newTitle = text.take(25)
                    session.title = newTitle
                    dao.updateSession(ChatSessionEntity(id = chatId, title = newTitle))
                }
            } catch (e: CancellationException) {
                session.messages.add(ChatMessage.Text("Остановлено пользователем.", false))
                throw e
            } catch (e: Exception) {
                session.messages.add(ChatMessage.Text("Ошибка: ${e.message}", false))
            } finally {
                isLoading.value = false
            }
        }
    }

    fun cancelGeneration() {
        currentJob?.cancel()
        isLoading.value = false
    }

    fun createNewChat(title: String = "Новый чат"): String {
        val newSession = ChatSession(title = title)
        sessions[newSession.id] = newSession
        currentChatId = newSession.id
        viewModelScope.launch {
            dao.insertSession(ChatSessionEntity(id = newSession.id, title = title))
        }
        return newSession.id
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            dao.deleteChatHistory(chatId)
            dao.deleteSessionById(chatId)
            sessions.remove(chatId)
            if (currentChatId == chatId) currentChatId = null
        }
    }
}