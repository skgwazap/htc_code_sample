package com.someproject.app.features.chat.ui

import android.os.Bundle
import androidx.annotation.IntRange
import androidx.annotation.StringRes
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import com.hadilq.liveevent.LiveEvent
import com.someproject.app.R
import com.someproject.app.analytic.AnalyticLogging
import com.someproject.app.analytic.events.AnalyticEvent
import com.someproject.app.base.BaseViewModel
import com.someproject.app.base.Failure
import com.someproject.app.base.StatefulViewModel
import com.someproject.app.base.Success
import com.someproject.uikit.view.images.MimeType
import com.someproject.uikit.view.images.extractMediaType
import com.someproject.app.extensions.onError
import com.someproject.app.extensions.onSuccess
import com.someproject.app.features.chat.data.repository.ChatRepository
import com.someproject.app.features.chat.data.repository.ChatUsersRepository
import com.someproject.app.features.chat.data.service.ChatItemsBuilder
import com.someproject.app.features.chat.data.service.ChatLiveFeed
import com.someproject.app.features.chat.data.service.VideoChatURLComposer
import com.someproject.app.features.chat.data.service.VideoChatURLComposer.Service.GoTalkTo
import com.someproject.app.features.chat.ui.adapter.items.ChatIncomingTextItem
import com.someproject.app.features.chat.ui.adapter.items.ChatOutgoingTextItem
import com.someproject.app.features.chat.ui.adapter.items.ChatViewItem
import com.someproject.app.features.chat.ui.adapter.items.RemoteContentViewItem
import com.someproject.app.features.feed.data.model.Location
import com.someproject.app.features.feed.data.service.EventAcceptance
import com.someproject.app.features.feed.data.service.EventLiveUpdates
import com.someproject.app.features.feed.ui.create_event.EventEditedSignaller
import com.someproject.app.features.feed.ui.event_detail.DetailMode
import com.someproject.app.features.invitation.ui.InvitationToEventViewModel.Mode.ForExistingEvent
import com.someproject.app.features.reporting.ReportInteractor
import com.someproject.app.features.reporting.ReportReason
import com.someproject.app.network.api.model.feed.EventDetails.Origin.Admin
import com.someproject.app.network.api.model.feed.EventDetails.Origin.TicketMaster
import com.someproject.app.network.api.model.feed.EventType
import com.someproject.app.network.api.model.feed.someprojectEvent
import com.someproject.app.network.api.model.feed.previewMimeType
import com.someproject.app.network.api.model.feed.previewUrl
import com.someproject.app.network.services.EventsRepository
import com.someproject.app.network.state.ConnectionState
import com.someproject.app.notifications.services.PushNotificationService
import com.someproject.app.notifications.services.PushNotificationService.Notification.*
import com.someproject.app.storage.token.UserId
import com.someproject.app.utils.MapOpener
import com.someproject.app.utils.ScreenMetrics
import com.someproject.common.logger.error
import com.someproject.chatting.ui.EventInfo
import com.someproject.chatting.ui.ToolbarData
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.*

interface ChatViewModel : StatefulViewModel {

    val eventInfo: LiveData<EventInfo>
    val eventInfoRefreshing: LiveData<Boolean>
    val eventWasEdited: LiveData<someprojectEvent>
    val isReadOnlyChat: Boolean
    val confirmVideoOpenSignal: LiveData<Unit>
    val needToAskToRateVideoQuality: LiveData<Boolean>

    fun onInviteClicked()
    fun onDateTimeClicked()
    fun onLocationClicked(mapOpener: MapOpener)
    fun onScreenReOpened()
    fun onIncomingMessageLongPressed(incomingMessage: ChatIncomingTextItem, showReportOption: () -> Unit)
    fun onMessageReported(incomingMessage: ChatIncomingTextItem, reason: ReportReason)
    fun rateVideoQuality(@IntRange(from = 1, to = 5) rating: Int)
    fun skipVideoQualityQuestion()
    fun onVideoCallClicked()
    fun onOpenVideoConfirmed()
}

class ChatViewModelImpl(
    private var event: someprojectEvent,
    private val chatRepository: ChatRepository,
    private val reportInteractor: ReportInteractor,
    private val eventsRepository: EventsRepository,
    private val chatUsersRepository: ChatUsersRepository,
    chatLiveFeed: ChatLiveFeed,
    private val chatItemsBuilder: ChatItemsBuilder,
    private val appLifecycleOwner: LifecycleOwner,
    private val pushes: PushNotificationService,
    private val eventLiveUpdates: EventLiveUpdates,
    private val eventEditedSignaller: EventEditedSignaller,
    private val connectionState: ConnectionState,
    private val screenMetrics: ScreenMetrics,
    private val userId: UserId,
    private val analyticLogging: AnalyticLogging
) : BaseViewModel(), ChatViewModel {
    override val eventInfo: MutableLiveData<EventInfo>
    override val eventInfoRefreshing = MutableLiveData(false)
    override val eventWasEdited = MutableLiveData<someprojectEvent>()
    override val isReadOnlyChat: Boolean
    override val confirmVideoOpenSignal = LiveEvent<Unit>()
    override val needToAskToRateVideoQuality = MutableLiveData(false)

    enum class Error {
        NO_CONNECTION,
        MESSAGES_LOADING_FAILED
    }

    private var attendEvent = true
    private val _items = MutableLiveData<MutableList<ChatViewItem>>()
    private val _loading = MutableLiveData(true)
    private val _errors = MutableLiveData<Error?>()
    private val _inputText = MutableLiveData("")
    private val _newContentAvailable = LiveEvent<Unit>()
    private val _toolbarData = MutableLiveData<ToolbarData>()

    private val updates = chatLiveFeed.updateNotificationsForChat()
    private val updatesObserver = Observer<Unit> {
        reloadChat()
    }
    private val appStateListener = object : LifecycleObserver {

        @OnLifecycleEvent(Lifecycle.Event.ON_START)
        fun onActivated() = reloadChat()

    }
    private val unreadMessagesBuffer = UnreadMessagesBuffer(10)

    private val pushConsumer = object : PushNotificationService.Consumer {
        override fun onNewNotification(notification: PushNotificationService.Notification): Boolean {
            return when (notification) {
                is NewChatMessage,
                is InvitationAccepted,
                is InvitationDeclined -> notification.eventId == event.details.eventId
                else -> false
            }
        }

        override fun onNotificationAction(notification: PushNotificationService.Notification): Boolean {
            return when (notification) {
                is NewChatMessage,
                is InvitationAccepted,
                is InvitationDeclined -> notification.eventId == event.details.eventId
                else -> false
            }
        }
    }

    val items: LiveData<List<ChatViewItem>> = Transformations.map(_items) { it.toList() }
    val loading: LiveData<Boolean> = Transformations.distinctUntilChanged(_loading)
    val errors: LiveData<Error?> = _errors
    val canSend: LiveData<Boolean> = Transformations.map(_inputText) { it.trim().isNotEmpty() }
    val newContentAvailable: LiveData<Unit> = _newContentAvailable
    val toolbarData: LiveData<ToolbarData> = _toolbarData

    private var title: String = ""
    private var previewUrl: String? = ""
    private var previewMimeType: MimeType =
        extractMediaType(event.details.previewMimeType)
    private val isEventOwner: Boolean = userId.userID == event.event.owner.userId

    private val eventUpdatesObserver = Observer { acceptance: EventAcceptance ->
        if (event.event.eventId == acceptance.eventId) {
            attendEvent = acceptance is EventAcceptance.Attending
        }
    }

    private var needAskForVideoQualityOnResume = false

    init {
        updates.observeForever(updatesObserver)
        appLifecycleOwner.lifecycle.addObserver(appStateListener)
        eventLiveUpdates.attendingState.observeForever(eventUpdatesObserver)
        pushes.addConsumer(pushConsumer)
        eventInfo = MutableLiveData(ChatEventInfoFactory.make(event, isEventOwner, screenMetrics.isLargeScreen))
        isReadOnlyChat = (event.details.origin == Admin || event.details.origin == TicketMaster) && isEventOwner.not()

        viewModelScope.launch {
            var previous: Boolean? = null
            connectionState.isOnline.asFlow()
                .distinctUntilChanged()
                .collectLatest {
                    val previousState = previous
                    if (previousState == null) {
                        previous = it
                        return@collectLatest
                    }
                    val becomeConnected = it && previousState.not()
                    if (becomeConnected) {
                        reloadChat()
                        refreshEventInfo()
                    }
                    previous = it
                }
        }
        viewModelScope.launch {
            chatLiveFeed.eventUpdateNotifications()
                .asFlow()
                .collectLatest {
                    refreshEventInfo()
                }
        }

        listenIfEventIsChangedOnOtherScreen()
        initData()
    }

    override fun saveState(outState: Bundle) {
        outState.putBoolean("needAskForVideoQualityOnResume", needAskForVideoQualityOnResume)
        super.saveState(outState)
    }

    override fun restoreState(savedState: Bundle?) {
        super.restoreState(savedState)
        needAskForVideoQualityOnResume = savedState?.getBoolean("needAskForVideoQualityOnResume") ?: false
    }

    override fun onCleared() {
        super.onCleared()
        pushes.removeConsumer(pushConsumer)
        updates.removeObserver(updatesObserver)
        eventLiveUpdates.attendingState.removeObserver(eventUpdatesObserver)
        appLifecycleOwner.lifecycle.removeObserver(appStateListener)
        unreadMessagesBuffer.flush()
    }

    fun onItemDisplayed(item: ChatViewItem) {
        unreadMessagesBuffer.onItemDisplayed(item)
    }

    fun reloadChat() {
        _loading.value = true
        viewModelScope.launch {
            val messagesRequest = async { chatRepository.loadMessages() }
            val usersRequest = async { chatUsersRepository.loadUsers() }

            val messages = when (val result = messagesRequest.await()) {
                is Success -> result.data
                is Failure -> {
                    logger.error(result.error)
                    null
                }
            }
            val users = when (val result = usersRequest.await()) {
                is Success -> result.data
                is Failure -> {
                    logger.error(result.error)
                    null
                }
            }

            if (messages != null && users != null) {
                val builtItems = chatItemsBuilder.buildItemsFromMessages(
                    messages = messages,
                    users = users.associateBy { it.userId },
                    unsentMessages = chatRepository.loadUnsentMessages()
                )
                notifyItemsLoaded(builtItems)
            } else {
                _errors.value = Error.MESSAGES_LOADING_FAILED
            }

            _loading.value = false
        }
    }

    override fun onResumed() {
        super.onResumed()
        if (needAskForVideoQualityOnResume) {
            needAskForVideoQualityOnResume = false
            needToAskToRateVideoQuality.value = true
        }
    }

    override fun onInviteClicked() {
        appRouter.navigateTo(
            screens.invitationToEvent(
                ForExistingEvent(
                    event = event,
                    attendingUserIds = null
                )
            )
        )
    }

    private fun initData() {
        event.run {
            title = event.title
            previewUrl = event.previewUrl
            previewMimeType = extractMediaType(event.previewMimeType)
            _toolbarData.value = ChatEventInfoFactory.makeToolbarData(event = this)
        }
    }

    private fun notifyItemsLoaded(loadedItems: List<ChatViewItem>) {
        _items.value = loadedItems.toMutableList()
    }

    fun send() {
        val textToSend = _inputText.value?.trim()
        if (textToSend.isNullOrEmpty()) {
            return
        }

        val localId = UUID.randomUUID().toString()
        val message = ChatOutgoingTextItem(
            id = localId,
            date = Date(),
            isRead = true,
            isRemoved = false,
            isBeingModerated = false,
            status = ChatOutgoingTextItem.Status.PENDING,
            text = textToSend
        )
        appendItem(message)
        _newContentAvailable.value = Unit

        viewModelScope.launch {
            when (val result = chatRepository.sendTextMessage(localId, textToSend)) {
                is Success -> {
                    val newItem = chatItemsBuilder.outgoingContentItemsFromMessage(result.data)
                    replaceItem(message.id, if (newItem != null) listOf(newItem) else listOf())
                }
                is Failure -> {
                    val failedMessage = message.copy(status = ChatOutgoingTextItem.Status.ERROR)
                    replaceItem(message.id, listOf(failedMessage))
                }
            }
        }
    }

    fun changeText(text: String) {
        _inputText.value = text
    }

    fun showEventDetails() {
        appRouter.navigateTo(screens.eventDetail(DetailMode.Chat(event, attendEvent)))
    }

    fun showEventAttendees() {
        appRouter.navigateTo(screens.allAttendees(event))
    }

    override fun onDateTimeClicked() {
        if (isEventOwner) {
            appRouter.navigateTo(screens.editEvent(event = event))
        } else {
            showEventDetails()
        }
    }

    override fun onLocationClicked(mapOpener: MapOpener) {
        if (isEventOwner) {
            appRouter.navigateTo(screens.editEvent(event = event))
        } else {
            Location.fromEventDetails(event.details).let {
                if (it != null) {
                    val success = mapOpener.invoke(
                        it.latitude,
                        it.longitude
                    )
                    if (success) {
                        return@let
                    }
                    appRouter.navigateTo(screens.locationDetail(location = it))
                    return@let
                }
                showEventDetails()
            }
        }
    }

    override fun onScreenReOpened() {
        viewModelScope.launch {
            refreshEventInfo()
        }
    }

    override fun onIncomingMessageLongPressed(
        incomingMessage: ChatIncomingTextItem,
        showReportOption: () -> Unit
    ) {
        if (event.event.eventType != EventType.PUBLIC) {
            return
        }
        if (incomingMessage.isBeingModerated || incomingMessage.isRemoved) {
            return
        }
        showReportOption.invoke()
    }

    override fun onMessageReported(incomingMessage: ChatIncomingTextItem, reason: ReportReason) {
        replaceItem(
            incomingMessage.id, listOf(
                incomingMessage.copy(
                    isBeingModerated = true
                )
            )
        )

        viewModelScope.launch {
            reportInteractor.reportMessage(
                eventId = event.details.eventId,
                chatId = event.details.chatId,
                chatMessageId = incomingMessage.id,
                reason = reason
            ).onError {
                onError(it)
                replaceItem(
                    incomingMessage.id, listOf(
                        incomingMessage.copy(
                            isBeingModerated = false
                        )
                    )
                )
            }
        }
    }

    fun retrySend(failedItem: ChatOutgoingTextItem) {
        val beingSentItem = failedItem.copy(status = ChatOutgoingTextItem.Status.PENDING)
        replaceItem(failedItem.id, listOf(beingSentItem))

        viewModelScope.launch {
            when (val result = chatRepository.sendTextMessage(failedItem.id, failedItem.text)) {
                is Success -> {
                    val newItem = chatItemsBuilder.outgoingContentItemsFromMessage(result.data)
                    replaceItem(beingSentItem.id, if (newItem != null) listOf(newItem) else listOf())
                }
                is Failure -> {
                    replaceItem(beingSentItem.id, listOf(failedItem))
                }
            }
        }
    }

    fun removeFailedItem(item: ChatOutgoingTextItem) {
        replaceItem(item.id, emptyList())
        viewModelScope.launch {
            chatRepository.cancelMessageSend(item.id)
        }
    }

    override fun rateVideoQuality(rating: Int) {
        analyticLogging.logEvent(
            AnalyticEvent.make(
                name = "t_event_video_call_rating",
                params = mapOf("value" to rating.toString())
            )
        )
        needToAskToRateVideoQuality.value = false
    }

    override fun skipVideoQualityQuestion() {
        analyticLogging.logEvent("t_event_video_call_rating_skip")
        needToAskToRateVideoQuality.value = false
    }

    override fun onOpenVideoConfirmed() {
        analyticLogging.logEvent("t_event_video_call_ok")
        needAskForVideoQualityOnResume = true
        appRouter.navigateTo(
            screens.externalBrowser(
                url = VideoChatURLComposer.compose(event, GoTalkTo)
            )
        )
    }

    override fun onVideoCallClicked() {
        analyticLogging.logEvent("t_event_video_call")
        confirmVideoOpenSignal.value = Unit
    }

    private fun appendItem(item: ChatViewItem) {
        val newItems = _items.value ?: mutableListOf()
        newItems.add(item)
        _items.value = newItems
    }

    private fun replaceItem(itemToReplaceId: String, newItems: List<ChatViewItem>) {
        val currentItems = _items.value ?: mutableListOf()
        val index = currentItems.indexOfFirst { it.id == itemToReplaceId }

        if (index == -1) return

        val oldItems = _items.value ?: mutableListOf()
        val resultItems = oldItems.slice(0 until index) + newItems + oldItems.slice((index + 1) until oldItems.size)
        _items.value = resultItems.toMutableList()
    }

    private suspend fun refreshEventInfo() {
        eventInfoRefreshing.value = true
        eventsRepository.getEvent(event.event.eventId)
            .onSuccess {
                event = it
                eventInfoRefreshing.postValue(false)
                val newState = ChatEventInfoFactory.make(event, isEventOwner, screenMetrics.isLargeScreen)
                if (eventInfo.value == newState) {
                    return
                }
                eventInfo.postValue(
                    newState
                )
                initData()
            }
            .onError {
                eventInfoRefreshing.postValue(false)
            }
    }

    private inner class UnreadMessagesBuffer(private val bufferSize: Int) {

        private val itemIdsSet = TreeSet<RemoteContentViewItem> { item: ChatViewItem, item2: ChatViewItem ->
            item.date.compareTo(item2.date)
        }

        fun onItemDisplayed(item: ChatViewItem) {
            if (item is RemoteContentViewItem && !item.isRead) {
                item.isRead = true
                itemIdsSet.add(item)
                if (itemIdsSet.size >= bufferSize) {
                    flush()
                }
            }
        }

        fun flush() {
            if (itemIdsSet.isEmpty()) return

            val idsList = itemIdsSet.toList().map { it.id }
            val lastReadItemTimestamp = itemIdsSet.last().createdOn
            itemIdsSet.clear()
            chatRepository.markReadIgnoringResult(idsList, lastReadItemTimestamp)
        }

    }

    private fun listenIfEventIsChangedOnOtherScreen() {
        viewModelScope.launch {
            eventEditedSignaller
                .signals(event)
                .asFlow()
                .collect {
                    eventWasEdited.value = it
                    event = it
                    val newState = ChatEventInfoFactory.make(event, isEventOwner, screenMetrics.isLargeScreen)
                    eventInfo.postValue(
                        newState
                    )
                    initData()
                }
        }
    }

}