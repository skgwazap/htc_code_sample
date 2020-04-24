package com.someproject.app.features.chat.data.repository

import com.someproject.app.base.Result
import com.someproject.api.model.chat.ChatMessage
import com.someproject.app.storage.database.entity.ChatUnsentMessage

interface ChatRepository {

    val chatId: String

    suspend fun loadMessages(): Result<List<ChatMessage>>
    suspend fun loadUnsentMessages(): List<ChatUnsentMessage>

    suspend fun sendTextMessage(localId: String, text: String): Result<ChatMessage>
    suspend fun cancelMessageSend(localId: String)

    fun markReadIgnoringResult(
        messageIds: List<String>,
        lastReadItemTimestamp: String
    )

}