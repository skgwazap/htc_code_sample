package com.someproject.app.features.chat.ui.adapter

import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.toSpannable
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.someproject.app.R
import com.someproject.app.extensions.px
import com.someproject.app.extensions.view.stripUnderlines
import com.someproject.app.features.chat.ui.adapter.ChatAdapter.ItemType
import com.someproject.app.features.chat.ui.adapter.items.*
import com.someproject.common.logger.Logger
import com.someproject.uikit.util.AvatarStubGenerator
import com.someproject.uikit.view.images.RemoteImageRequest
import com.someproject.uikit.view.images.ScaleType
import com.someproject.uikit.view.images.applyCircleOutline
import com.someproject.uikit.view.images.loadImg
import kotlinx.android.synthetic.main.chat_item_divider.view.*
import kotlinx.android.synthetic.main.chat_item_text_message_incoming.view.*
import kotlinx.android.synthetic.main.chat_item_text_message_outgoing.view.*
import java.text.DateFormat

class ChatAdapter(private val avatarStubGenerator: AvatarStubGenerator) :
    ListAdapter<ChatViewItem, RecyclerView.ViewHolder>(DiffItemCallback()) {

    private val logger = Logger.get(ChatAdapter::class.java.simpleName)
    private val dateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)

    var onOutgoingFailedItemClick: (item: ChatOutgoingTextItem, anchor: View) -> Unit = { _, _ -> }
    var onItemDisplayed: (item: ChatViewItem) -> Unit = {}
    var onIncomingMsgLongClick: (item: ChatIncomingTextItem, pressedItem: View) -> Unit = { _, _ -> }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            ItemType.OUTGOING_TEXT.ordinal -> OutgoingTextVH(
                inflater.inflate(R.layout.chat_item_text_message_outgoing, parent, false)
            )
            ItemType.INCOMING_TEXT.ordinal -> IncomingTextVH(
                inflater.inflate(R.layout.chat_item_text_message_incoming, parent, false)
            )
            ItemType.SYSTEM_MESSAGE.ordinal -> SystemTextVH(
                inflater.inflate(R.layout.chat_item_message_system, parent, false)
            )
            ItemType.SYSTEM_MESSAGE_AS_INCOMING_TEXT.ordinal -> SystemTextAsIncomingTextVH(
                inflater.inflate(R.layout.chat_item_text_message_incoming, parent, false)
            )
            ItemType.DIVIDER.ordinal -> DividerVH(
                inflater.inflate(R.layout.chat_item_divider, parent, false)
            )
            else -> throw Exception("View type $viewType is not supported")
        }
    }

    override fun getItemViewType(position: Int): Int {
        return getItem(position).itemType.ordinal
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChatOutgoingTextItem -> (holder as OutgoingTextVH).bind(item)
            is ChatIncomingTextItem -> (holder as IncomingTextVH).bind(item)
            is ChatSystemMessageItem -> {
                if (item.showAsIncomingMessage) {
                    (holder as SystemTextAsIncomingTextVH).bind(item)
                } else {
                    (holder as SystemTextVH).bind(item)
                }
            }
            is ChatDividerItem -> (holder as DividerVH).bind(item)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        val item = getItem(holder.adapterPosition)
        onItemDisplayed(item)
    }

    private inner class OutgoingTextVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView = itemView.outgoing_message_text
        private val notDeliveredGroup = itemView.group_not_delivered
        private val messageRoot = itemView.message_root

        val popupMenuAnchor: View = itemView.message_not_delivered_icon

        fun bind(item: ChatOutgoingTextItem) {
            if (item.isBeingModerated || item.isRemoved) {
                val textId = if (item.isBeingModerated) {
                    R.string.ChatUI_message_state_in_review
                } else {
                    R.string.ChatUI_message_state_deleted
                }
                textView.setText(textId)
            } else {
                textView.text = item.text
                Linkify.addLinks(textView, Linkify.ALL)
            }

            textView.showTimeStamp(item.date)

            messageRoot.alpha = 1f
            notDeliveredGroup.visibility = View.GONE
            when (item.status) {
                ChatOutgoingTextItem.Status.PENDING -> {
                    messageRoot.alpha = .5f
                }
                ChatOutgoingTextItem.Status.ERROR -> {
                    notDeliveredGroup.visibility = View.VISIBLE
                }
                ChatOutgoingTextItem.Status.DELIVERED -> {
                }
                ChatOutgoingTextItem.Status.READ -> {
                }
            }

            itemView.setOnClickListener {
                if (item.status != ChatOutgoingTextItem.Status.ERROR) return@setOnClickListener
                onOutgoingFailedItemClick(item, popupMenuAnchor)
            }
        }
    }

    private inner class IncomingTextVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView = itemView.incoming_message_text
        private val imageView = itemView.sender_avatar
        private val nameView = itemView.sender_name
        private val bubble = itemView.bubble

        init {
            imageView.applyCircleOutline()
        }

        fun bind(item: ChatIncomingTextItem) {
            if (item.isBeingModerated || item.isRemoved) {
                val textId = if (item.isBeingModerated) {
                    R.string.ChatUI_message_state_in_review
                } else {
                    R.string.ChatUI_message_state_deleted
                }
                val context = textView.context
                textView.text =
                    context.getString(textId).toSpannable().apply {
                        setSpan(
                            ForegroundColorSpan(ContextCompat.getColor(context, R.color.black_30)),
                            0,
                            this.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
            } else {
                textView.text = item.text
                Linkify.addLinks(textView, Linkify.ALL)
            }

            textView.showTimeStamp(item.date)

            imageView.loadImg(RemoteImageRequest.fromUrl(item.avatarUrl ?: "").apply {
                scaleType = ScaleType.CenterCrop
                placeholderDrawable = avatarStubGenerator.get(item.userName, item.userName, size = 45.px)
                errorDrawable = placeholderDrawable
            })

            if (!item.fromParentEventOwner) {
                nameView.text = item.userName
                bubble.setBackgroundResource(R.drawable.chat_item_incoming_message_back)
            } else {
                nameView.text = nameView.resources.getString(R.string.ChatUI_sender_name_parent_owner, item.userName)
                bubble.setBackgroundResource(R.drawable.chat_item_owner_incoming_message_back)
            }

            when (item.groupPosition) {
                ChatIncomingTextItem.GroupPosition.SINGLE -> {
                    imageView.isVisible = true
                    nameView.isVisible = true
                }
                ChatIncomingTextItem.GroupPosition.FIRST -> {
                    imageView.isVisible = false
                    nameView.isVisible = true
                }
                ChatIncomingTextItem.GroupPosition.MIDDLE -> {
                    imageView.isVisible = false
                    nameView.isVisible = false
                }
                ChatIncomingTextItem.GroupPosition.LAST -> {
                    imageView.isVisible = true
                    nameView.isVisible = false
                }
            }
            bubble.setOnLongClickListener {
                onIncomingMsgLongClick.invoke(item, bubble)
                true
            }
        }
    }

    private class SystemTextVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val textView = itemView.findViewById<TextView>(R.id.message_text)

        fun bind(item: ChatSystemMessageItem) {
            textView.text = item.buildText(itemView.context)
            Linkify.addLinks(textView, Linkify.ALL)
        }

    }

    private inner class SystemTextAsIncomingTextVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val bubbleView = itemView.bubble
        private val textView = itemView.incoming_message_text
        private val imageView = itemView.sender_avatar
        private val nameView = itemView.sender_name

        init {
            imageView.applyCircleOutline()
        }

        fun bind(item: ChatSystemMessageItem) {
            textView.apply {
                text = item.buildText(itemView.context)
                showTimeStamp(item.date)
                movementMethod = LinkMovementMethod.getInstance()
                setLinkTextColor(
                    ContextCompat.getColor(
                        itemView.context,
                        R.color.beetroot_pink
                    )
                )
                stripUnderlines()
            }
            nameView.isVisible = false
            imageView.setImageResource(R.drawable.ic_someproject_system_message)
            bubbleView.setBackgroundResource(R.drawable.chat_item_system_message_back)
        }
    }

    private class DividerVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val textView = itemView.divider_text

        fun bind(item: ChatDividerItem) {
            textView.text = item.text
        }

    }

    internal enum class ItemType {
        OUTGOING_TEXT,
        INCOMING_TEXT,
        SYSTEM_MESSAGE,
        SYSTEM_MESSAGE_AS_INCOMING_TEXT,
        DIVIDER
    }
}

private val ChatViewItem.itemType: ItemType
    get() = when (this) {
        is ChatOutgoingTextItem -> ItemType.OUTGOING_TEXT
        is ChatIncomingTextItem -> ItemType.INCOMING_TEXT
        is ChatSystemMessageItem -> if (this.showAsIncomingMessage) {
            ItemType.SYSTEM_MESSAGE_AS_INCOMING_TEXT
        } else {
            ItemType.SYSTEM_MESSAGE
        }
        is ChatDividerItem -> ItemType.DIVIDER
        else -> error("No view type provided for ${this::class.java.canonicalName}")
    }

private class DiffItemCallback : DiffUtil.ItemCallback<ChatViewItem>() {

    override fun areItemsTheSame(oldItem: ChatViewItem, newItem: ChatViewItem): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: ChatViewItem, newItem: ChatViewItem): Boolean {
        return true
    }

}