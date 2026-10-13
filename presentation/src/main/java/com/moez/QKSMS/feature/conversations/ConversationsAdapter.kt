/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.feature.conversations

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.common.base.QkRealmAdapter
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.DateFormatter
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.databinding.ConversationListItemBinding
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import io.reactivex.disposables.CompositeDisposable
import javax.inject.Inject

class ConversationsAdapter @Inject constructor(
    private val colors: Colors,
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val navigator: Navigator,
    private val phoneNumberUtils: PhoneNumberUtils
) : QkRealmAdapter<Conversation, QkBindingViewHolder<ConversationListItemBinding>>() {
    private val disposables = CompositeDisposable()

    var hasScheduledConversation: Set<Long> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    init {
        // This is how we access the threadId for the swipe actions
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<ConversationListItemBinding> {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = ConversationListItemBinding.inflate(layoutInflater, parent, false)

        return QkBindingViewHolder(binding).apply {
            binding.root.setOnClickListener {
                val conversation = getItem(adapterPosition) ?: return@setOnClickListener
                when (toggleSelection(conversation.id, false)) {
                    true -> binding.root.isActivated = isSelected(conversation.id)
                    false -> navigator.showConversation(conversation.id)
                }
            }
            binding.root.setOnLongClickListener {
                val conversation = getItem(adapterPosition) ?: return@setOnLongClickListener true
                toggleSelection(conversation.id)
                binding.root.isActivated = isSelected(conversation.id)
                true
            }
        }
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<ConversationListItemBinding>, position: Int) {
        val conversation = getItem(position) ?: return
        val binding = holder.binding

        holder.itemView.isActivated = isSelected(conversation.id)

        binding.avatars.recipients = conversation.recipients
        binding.title.collapseEnabled = conversation.recipients.size > 1
        binding.title.text = buildSpannedString {
            append(conversation.getTitle())
        }
        binding.date.text = conversation.date.takeIf { it > 0 }?.let(dateFormatter::getConversationTimestamp)
        binding.snippet.text = when {
            conversation.draft.isNotEmpty() -> context.getString(R.string.main_sender_draft, conversation.draft)
            conversation.me -> context.getString(R.string.main_sender_you, conversation.snippet)
            else -> conversation.snippet
        }

        val isUnread = conversation.unread
        val colorPrimary = context.resolveThemeColor(R.attr.colorPrimary)
        val colorOnSurface = context.resolveThemeColor(R.attr.colorOnSurface)
        val colorOnSurfaceVariant = context.resolveThemeColor(R.attr.colorOnSurfaceVariant)

        if (isUnread) {
            binding.title.setTypeface(binding.title.typeface, Typeface.BOLD)
            binding.title.setTextColor(colorOnSurface)
            binding.snippet.setTypeface(null, if (conversation.draft.isNotEmpty()) Typeface.BOLD_ITALIC else Typeface.BOLD)
            binding.snippet.setTextColor(colorOnSurface)
            binding.date.setTypeface(binding.date.typeface, Typeface.BOLD)
            binding.date.setTextColor(colorPrimary)
            binding.unread.isVisible = true
            binding.unread.setTint(colorPrimary)
        } else {
            binding.title.setTypeface(Typeface.create(binding.title.typeface, Typeface.NORMAL), Typeface.NORMAL)
            binding.title.setTextColor(colorOnSurface)
            binding.snippet.setTypeface(null, if (conversation.draft.isNotEmpty()) Typeface.ITALIC else Typeface.NORMAL)
            binding.snippet.setTextColor(colorOnSurfaceVariant)
            binding.date.setTypeface(Typeface.create(binding.date.typeface, Typeface.NORMAL), Typeface.NORMAL)
            binding.date.setTextColor(colorOnSurfaceVariant)
            binding.unread.isVisible = false
        }

        binding.scheduled.isVisible = conversation.id in hasScheduledConversation
        binding.pinned.isVisible = conversation.pinned
    }

    override fun getItemId(position: Int): Long {
        return getItem(position)?.id ?: -1
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position)?.unread == false) 0 else 1
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        disposables.clear()
    }


}
