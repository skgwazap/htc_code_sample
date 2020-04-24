package com.someproject.app.features.chat.ui

import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.RatingBar
import android.widget.TextView
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.*
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING
import com.google.android.material.snackbar.Snackbar
import com.someproject.app.R
import com.someproject.app.base.BaseFragment
import com.someproject.uikit.util.AvatarStubGenerator
import com.someproject.uikit.util.VideoPreviewExtractor
import com.someproject.uikit.view.images.extractMediaType
import com.someproject.app.extensions.px
import com.someproject.app.extensions.view.hideSoftKeyboard
import com.someproject.app.extensions.view.snackbar
import com.someproject.app.extensions.view.toast
import com.someproject.app.features.chat.data.service.ChatItemsBuilder
import com.someproject.app.features.chat.ui.adapter.ChatAdapter
import com.someproject.app.features.chat.ui.adapter.items.ChatIncomingTextItem
import com.someproject.app.features.chat.ui.adapter.items.ChatOutgoingTextItem
import com.someproject.chatting.ui.EventInfo
import com.someproject.app.features.reporting.ReportReason
import com.someproject.app.features.reporting.applyStyle
import com.someproject.app.network.api.model.feed.someprojectEvent
import com.someproject.app.utils.DismissDialog
import com.someproject.app.utils.MapOpenerImpl
import com.someproject.app.utils.showDialogIfNotShown
import com.someproject.chatting.ui.ToolbarData
import com.someproject.uikit.view.ext.setIncludeFontPadding
import com.someproject.uikit.view.ext.setTextViewTopMargin
import kotlinx.android.synthetic.main.chat_fragment.*
import org.koin.android.ext.android.inject
import org.koin.android.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class ChatFragment : BaseFragment(R.layout.chat_fragment) {

    companion object {

        private const val ARG_EVENT = "event_data"

        fun newInstance(event: someprojectEvent) = ChatFragment().apply {
            arguments = bundleOf(ARG_EVENT to event)
        }
    }

    override val screenName: String = "Chat"

    private val avatarStubGenerator: AvatarStubGenerator by inject()
    private val viewModel: ChatViewModelImpl by viewModel {
        parametersOf(arguments?.getParcelable(ARG_EVENT))
    }

    private var dismissReportDialog: DismissDialog? = null

    private lateinit var adapterObserver: RecyclerView.AdapterDataObserver
    private var isFirstListLoaded = false

    private var snackbar: Snackbar? = null

    private val collapseToolbar = {
        chat_header.isExpanded = false
        Unit
    }

    private var isScreenRecreated = false
    private var isScreenShownFirstTime = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isScreenRecreated = savedInstanceState != null
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val chatAdapter = ChatAdapter(avatarStubGenerator).apply {
            onOutgoingFailedItemClick = this@ChatFragment::showResendMenu
            onItemDisplayed = viewModel::onItemDisplayed
            onIncomingMsgLongClick = { item, view ->
                viewModel.onIncomingMessageLongPressed(
                    incomingMessage = item,
                    showReportOption = {
                        showReportMenu(item, view)
                    }
                )
            }
        }

        adapterObserver = object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (!isFirstListLoaded) return
                // TODO check current scroll position
                //  and notify that there is new content at the bottom of the list if needed
                if (positionStart + itemCount == chatAdapter.itemCount) {
                    chat_recycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
                }
            }
        }

        viewModel.restoreState(savedInstanceState)

        setupView(chatAdapter)
        subscribeToModel(chatAdapter)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        viewModel.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        val hasToRefreshData = isScreenRecreated || isScreenShownFirstTime.not()
        if (hasToRefreshData) {
            viewModel.onScreenReOpened()
        }
        isScreenShownFirstTime = false
    }

    override fun onResume() {
        super.onResume()
        requireNotNull(chat_recycler.adapter).registerAdapterDataObserver(adapterObserver)
        viewModel.onResumed()
    }

    override fun onPause() {
        requireNotNull(chat_recycler.adapter).unregisterAdapterDataObserver(adapterObserver)
        dismissReportDialog?.invoke()
        dismissReportDialog = null
        super.onPause()
    }

    override fun onDestroyView() {
        if (chat_input_text.isFocused) {
            chat_input_text.hideSoftKeyboard()
        }
        snackbar?.dismiss()
        chat_recycler.clearOnScrollListeners()
        chat_fragment_view.removeCallbacks(collapseToolbar)
        super.onDestroyView()
    }

    private fun subscribeToModel(chatAdapter: ChatAdapter) {
        viewModel.items.observe(viewLifecycleOwner, Observer { list ->
            chatAdapter.submitList(list)
            if (list.isEmpty()) {
                chat_is_empty_message.isVisible = true
            } else {
                chat_is_empty_message.isVisible = false
                ViewCompat.setNestedScrollingEnabled(chat_recycler, true)
            }

            if (isFirstListLoaded) return@Observer
            isFirstListLoaded = true
            val indexOfNewMessagesItem = list.indexOfLast { it.id == ChatItemsBuilder.NEW_MESSAGES_DIVIDER_ID }
            if (indexOfNewMessagesItem != -1) {
                (chat_recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                    indexOfNewMessagesItem,
                    0
                )
            }
        })

        viewModel.loading.observe(viewLifecycleOwner, Observer {
            if (it) progress_bar.show() else progress_bar.hide()
        })

        viewModel.errors.observe(viewLifecycleOwner, Observer {
            it?.let {
                snackbar = snackbar(
                    text = getString(R.string.ChatUI_chat_load_failed_message),
                    actionText = getString(R.string.ChatUI_sending_retry),
                    action = viewModel::reloadChat
                )
            }
        })

        viewModel.canSend.observe(viewLifecycleOwner, Observer {
            chat_send_button.isEnabled = it
        })

        viewModel.newContentAvailable.observe(viewLifecycleOwner, Observer {
            chat_recycler.layoutManager?.let {
                val targetPosition = it.itemCount - 1
                if (targetPosition > 0) {
                    chat_recycler.smoothScrollToPosition(targetPosition)
                }
            }
        })

        viewModel.toolbarData.observe(viewLifecycleOwner, Observer {
            setupToolbar(it)
        })
        viewModel.eventInfo.observe(viewLifecycleOwner, Observer {
            setHeaderData(it)
        })
        viewModel.eventInfoRefreshing.observe(viewLifecycleOwner, Observer {
            chat_header_updating.isVisible = it
        })
        viewModel.eventWasEdited.observeForever {
            arguments = requireArguments().apply {
                putParcelable(ARG_EVENT, it)
            }
        }
        viewModel.errorText.observe(viewLifecycleOwner, Observer { toast(it) })

        viewModel.confirmVideoOpenSignal.observe(viewLifecycleOwner, Observer {
            showVideoChatOpenConfirmation()
        })

        viewModel.needToAskToRateVideoQuality.observe(viewLifecycleOwner, Observer { need ->
            if (need) {
                showVideoQualityQuestion()
            }
        })
    }

    private fun setupView(chatAdapter: ChatAdapter) {
        chat_recycler.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
                .apply {
                    stackFromEnd = true
                }
            adapter = chatAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    val isScrolledByUser = newState == SCROLL_STATE_DRAGGING
                    if (isScrolledByUser) {
                        hideSoftKeyboard()
                    }
                }
            })
        }

        chat_send_button.setOnClickListener {
            viewModel.send()
            chat_input_text.text.clear()
        }

        chat_input_text.doAfterTextChanged {
            viewModel.changeText(it?.toString() ?: "")
        }
        chat_input_container.isVisible = viewModel.isReadOnlyChat.not()
        initHeader()
    }

    private fun setupToolbar(data: ToolbarData) {
        toolbar.title = data.title
        toolbar.setSubtitle(data.subtitleResId)
        toolbar.setIncludeFontPadding(false)
        toolbar.setTextViewTopMargin((-8).px, toolbar.subtitle)
        toolbar.setNavigationOnClickListener {
            viewModel.exit()
        }
        toolbar.setOnClickListener {
            viewModel.showEventDetails()
        }

        event_preview.setOnClickListener {
            viewModel.showEventDetails()
        }
        val previewUrl = data.previewUrl
        if (previewUrl != null) {
            val extractor: VideoPreviewExtractor by inject { parametersOf(viewLifecycleOwner.lifecycleScope) }
            event_preview.providePreviewExtractor(extractor)
            event_preview.visibility = View.VISIBLE

            val previewSize = resources.getDimensionPixelSize(R.dimen.media_preview_small)
            event_preview.loadPreview(
                url = data.previewUrl,
                mimeType = extractMediaType(data.previewMimeType),
                placeholder = avatarStubGenerator.get(data.title, data.title),
                size = Size(previewSize, previewSize)
            )
        } else {
            event_preview.visibility = View.GONE
        }
    }

    private fun setHeaderData(eventInfo: EventInfo) {
        chat_header.apply {
            setup(
                eventInfo = eventInfo,
                attendeesClickListener = { viewModel.showEventAttendees() },
                inviteClickListener = { viewModel.onInviteClicked() },
                dateTimeClickListener = { viewModel.onDateTimeClicked() },
                locationClickListener = {
                    viewModel.onLocationClicked(
                        mapOpener = MapOpenerImpl(requireContext())
                    )
                },
                videoChatClickListener = { viewModel.onVideoCallClicked() }
            )
        }
    }

    private fun initHeader() {
        chat_fragment_view.onScreenHeightDecreased = {
            chat_header.postDelayed(
                collapseToolbar,
                TOOLBAR_COLLAPSING_DELAY_MS
            )
        }
    }

    private fun showResendMenu(item: ChatOutgoingTextItem, anchor: View) {
        anchor.showPopup(
            menuRes = R.menu.chat_send_retry_menu,
            clickListener = {
                when (it) {
                    R.id.retry -> viewModel.retrySend(item)
                    R.id.cancel -> viewModel.removeFailedItem(item)
                    else -> {
                    }
                }
            }
        )
    }

    private fun showReportMenu(item: ChatIncomingTextItem, anchor: View) {
        anchor.showPopup(
            menuRes = R.menu.chat_report_message_reasons,
            clickListener = {
                when (it) {
                    R.id.report -> {
                        view?.post {
                            showReportReasonMenu(item)
                        }
                    }
                    else -> error("Unexpected menu item ID: $it")
                }
            }
        )
    }

    private fun showReportReasonMenu(item: ChatIncomingTextItem) {
        dismissReportDialog = childFragmentManager.showDialogIfNotShown {
            ReportReasonSelectionDialog(
                onReasonSelected = { reportReason: ReportReason ->
                    showReportConfirmationDialog(item, reportReason)
                }
            )
        }
    }

    private fun showReportConfirmationDialog(item: ChatIncomingTextItem, reportReason: ReportReason) {
        AlertDialog.Builder(requireContext())
            .setPositiveButton(R.string.ReportUI_report_confirmation_confirm) { _, _ ->
                viewModel.onMessageReported(item, reportReason)
                view?.post {
                    showReportCompletionDialog()
                }
            }
            .setNegativeButton(R.string.ReportUI_report_confirmation_cancel) { _, _ -> }
            .setMessage(R.string.ReportUI_report_confirmation_message_title)
            .create()
            .applyStyle(requireContext())
            .show()
    }

    private fun showReportCompletionDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ReportUI_report_completion_title)
            .setMessage(R.string.ReportUI_report_completion_message)
            .setPositiveButton(R.string.ReportUI_ok) { _, _ -> }
            .create()
            .applyStyle(requireContext())
            .show()
    }

    private fun showVideoChatOpenConfirmation() {
        videoAlertWithTitle(R.string.ChatUI_video_call_confirmation_message)
            .setNegativeButton(R.string.ChatUI_video_call_confirmation_cancel) { _, _ ->}
            .setPositiveButton(R.string.ChatUI_video_call_confirmation_continue) { _, _ ->
                viewModel.onOpenVideoConfirmed()
            }
            .create()
            .show()
    }

    private fun showVideoQualityQuestion() {
        val builder = videoAlertWithTitle(R.string.ChatUI_video_call_rate_title)
        val ratingContainer = LayoutInflater.from(builder.context)
            .inflate(R.layout.chat_video_quality_rating_view, null, false)
        val ratingView = ratingContainer.findViewById<RatingBar>(R.id.rating)

        val dialog = builder
            .setPositiveButton(R.string.ChatUI_video_call_rate_cancel) { _, _ ->
                viewModel.skipVideoQualityQuestion()
            }
            .setView(ratingContainer)
            .setOnCancelListener {
                viewModel.skipVideoQualityQuestion()
            }
            .create()

        ratingView.setOnRatingBarChangeListener { _, rating, fromUser ->
            if (fromUser && rating > 0) {
                viewModel.rateVideoQuality(rating.toInt())
                dialog.dismiss()
                snackbar = snackbar(
                    text = resources.getString(R.string.ChatUI_rating_accepted),
                    duration = Snackbar.LENGTH_SHORT
                )
            }
        }

        dialog.show()
    }

    private fun videoAlertWithTitle(@StringRes titleResId: Int): AlertDialog.Builder {
        val builder = AlertDialog.Builder(requireContext(), R.style.AlertDialog_VideoChat)
        val titleView = TextView(builder.context).apply {
            TextViewCompat.setTextAppearance(this, R.style.H4Text)
            setText(titleResId)
            setLineSpacing(0f, 0.8f)
            24.px.also {
                setPadding(it, it, it, 0)
            }
        }

        return builder
            .setCustomTitle(titleView)
    }

    override fun onBackPressed() = viewModel.exit()

}

private fun View.showPopup(@MenuRes menuRes: Int, clickListener: (Int) -> Unit) {
    val popup = PopupMenu(this.context, this, Gravity.NO_GRAVITY)
    popup.menuInflater.inflate(menuRes, popup.menu)
    popup.setOnMenuItemClickListener {
        clickListener.invoke(it.itemId)
        true
    }
    popup.show()
}

private const val TOOLBAR_COLLAPSING_DELAY_MS = 133L
