package dev.octoshrimpy.quik.feature.settings.automations

import android.graphics.Color
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.common.base.QkRealmAdapter
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.databinding.AutomationLogListItemBinding
import dev.octoshrimpy.quik.model.AutomationLog

class AutomationLogsAdapter : QkRealmAdapter<AutomationLog, QkBindingViewHolder<AutomationLogListItemBinding>>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<AutomationLogListItemBinding> {
        val binding = AutomationLogListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QkBindingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<AutomationLogListItemBinding>, position: Int) {
        val log = getItem(position) ?: return
        val context = holder.binding.root.context

        holder.binding.logTypeBadge.text = log.type
        when (log.type) {
            AutomationLog.TYPE_ERROR -> {
                holder.binding.logTypeBadge.setTextColor(Color.parseColor("#D32F2F"))
            }
            AutomationLog.TYPE_SUCCESS -> {
                holder.binding.logTypeBadge.setTextColor(Color.parseColor("#2E7D32"))
            }
            else -> {
                holder.binding.logTypeBadge.setTextColor(context.resolveThemeColor(android.R.attr.textColorSecondary))
            }
        }

        holder.binding.logActionType.text = log.actionType
        holder.binding.logTimestamp.text = DateFormat.format("MMM d, HH:mm:ss", log.timestamp)
        holder.binding.logRuleName.text = log.ruleName.ifBlank { "Automation" }
        holder.binding.logDetails.text = log.details
    }
}
