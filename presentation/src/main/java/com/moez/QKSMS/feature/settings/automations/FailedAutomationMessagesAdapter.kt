package dev.octoshrimpy.quik.feature.settings.automations

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.common.base.QkRealmAdapter
import dev.octoshrimpy.quik.databinding.FailedAutomationMessageListItemBinding
import dev.octoshrimpy.quik.model.FailedAutomationMessage
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject

class FailedAutomationMessagesAdapter : QkRealmAdapter<FailedAutomationMessage, QkBindingViewHolder<FailedAutomationMessageListItemBinding>>() {

    val retryClicks: Subject<Long> = PublishSubject.create()
    val dismissClicks: Subject<Long> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<FailedAutomationMessageListItemBinding> {
        val binding = FailedAutomationMessageListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QkBindingViewHolder(binding).apply {
            binding.btnRetry.setOnClickListener {
                val position = adapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = getItem(position) ?: return@setOnClickListener
                retryClicks.onNext(item.id)
            }
            binding.btnDismiss.setOnClickListener {
                val position = adapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = getItem(position) ?: return@setOnClickListener
                dismissClicks.onNext(item.id)
            }
        }
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<FailedAutomationMessageListItemBinding>, position: Int) {
        val item = getItem(position) ?: return

        holder.binding.failedRuleName.text = item.ruleName.ifBlank { "Automation Rule" }
        holder.binding.failedTimestamp.text = DateFormat.format("MMM d, HH:mm", item.timestamp)
        holder.binding.failedSender.text = "From: ${item.sender}"
        holder.binding.failedBody.text = item.body
        holder.binding.failedStepInfo.text = "Failed at Step ${item.failedStepIndex + 1}: ${item.failedActionType}"
        holder.binding.failedReason.text = "Reason: ${item.errorMessage}"
    }
}
