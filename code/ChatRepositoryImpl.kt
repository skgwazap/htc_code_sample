package com.someproject.app.features.chat.data.repository

import com.someproject.app.base.Failure
import com.someproject.app.base.Result
import com.someproject.app.base.Success
import com.someproject.app.extensions.wrapInResult
import com.someproject.app.network.api.endpoint.ChatApi
import com.someproject.api.model.chat.ChatMessage
import com.someproject.api.model.chat.ChatSendMessageBody
import com.someproject.app.storage.database.dao.ChatMessagesDao
import com.someproject.app.storage.database.dao.ChatMetadataDao
import com.someproject.app.storage.database.dao.getModels
import com.someproject.app.storage.database.dao.saveModels
import com.someproject.app.storage.database.entity.ChatMetadata
import com.someproject.app.storage.database.entity.ChatUnsentMessage
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

class ChatRepositoryImpl(
    override val chatId: String,
    private val currentUserId: String,
    private val chatApi: ChatApi,
    private val messagesDao: ChatMessagesDao,
    private val metadataDao: ChatMetadataDao
) : ChatRepository {

    override suspend fun loadMessages(): Result<List<ChatMessage>> {
        return try {
            val metadata = metadataDao.metadataByChatId(chatId) ?: ChatMetadata(chatId, "")
            var lastLoadedTag = ""
            var lastMessageText: String? = null

            loadUpdatesSince(metadata.lastTag).collect {
                val (loadedMessages, nextTag) = it
                lastLoadedTag = nextTag
                lastMessageText = loadedMessages.findLast {
                    it.deleted.not() && it.type != ChatMessage.Type.Moderation
                }?.shortContentText
                messagesDao.saveModels(loadedMessages, chatId)
            }

            metadataDao.insert(
                metadata.copy(
                    lastTag = lastLoadedTag,
                    lastMessageText = lastMessageText ?: metadata.lastMessageText
                )
            )

            Success(messagesDao.getModels(chatId))
        } catch (e: Exception) {
            Failure(e)
        }
    }

    override suspend fun loadUnsentMessages(): List<ChatUnsentMessage> =
        metadataDao.unsentMessagesForChat(chatId = chatId, senderId = currentUserId)

    private suspend fun loadUpdatesSince(tag: String) = flow {
        var nextTag = tag
        do {
            val feed = chatApi.loadMessages(chatId, nextTag)
            val lastReadTimestamp = feed.lastReadTimestamp
            val items = feed.items.map {
                val messageTimeStamp = it.createdOn
                it.isRead = if (lastReadTimestamp != null) messageTimeStamp <= lastReadTimestamp else true
                it
            }
            emit(items to feed.nextTag)
            nextTag = feed.nextTag
        } while (feed.hasMoreItems && nextTag.isNotEmpty())
    }

    override suspend fun sendTextMessage(localId: String, text: String): Result<ChatMessage> {
        val body = ChatSendMessageBody(
            listOf(
                ChatSendMessageBody.Item(text)
            )
        )

        val unsentMessage = ChatUnsentMessage(
            localId = localId,
            chatId = chatId,
            senderId = currentUserId,
            text = text
        )

        metadataDao.insertUnsentMessage(unsentMessage)

        return try {
            val result = chatApi.sendMessage(chatId, body)
            metadataDao.deleteUnsentMessage(unsentMessage.localId)
            metadataDao.updateLastMessageText(chatId, text)
            Success(result)
        } catch (e: Exception) {
            Failure(e)
        }
    }

    override suspend fun cancelMessageSend(localId: String) = metadataDao.deleteUnsentMessage(localId)

    private suspend fun markRead(messageIds: List<String>, lastReadItemTimestamp: String): Result<Unit> {
        return wrapInResult {
            messagesDao.markAllRead(messageIds)
            metadataDao.recalculateUnreadCount(chatId = chatId, activeUserId = currentUserId)
            chatApi.markMessageRead(
                chatId = chatId,
                lastReadTimestamp = lastReadItemTimestamp
            )
        }
    }

    override fun markReadIgnoringResult(messageIds: List<String>, lastReadItemTimestamp: String) {
        GlobalScope.launch {
            markRead(messageIds, lastReadItemTimestamp)
        }
    }
}