package dev.octoshrimpy.quik.feature.settings.automations

import android.view.LayoutInflater
import android.view.ViewGroup
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.common.base.QkRealmAdapter
import dev.octoshrimpy.quik.databinding.AutomationRuleListItemBinding
import dev.octoshrimpy.quik.model.AutomationRule
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject

class AutomationRulesAdapter : QkRealmAdapter<AutomationRule, QkBindingViewHolder<AutomationRuleListItemBinding>>() {

    val editClicks: Subject<Long> = PublishSubject.create()
    val deleteClicks: Subject<Long> = PublishSubject.create()
    val toggleClicks: Subject<Long> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<AutomationRuleListItemBinding> {
        val binding = AutomationRuleListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QkBindingViewHolder(binding).apply {
            binding.root.setOnClickListener {
                val rule = getItem(adapterPosition) ?: return@setOnClickListener
                editClicks.onNext(rule.id)
            }
            binding.automationRuleDelete.setOnClickListener {
                val rule = getItem(adapterPosition) ?: return@setOnClickListener
                deleteClicks.onNext(rule.id)
            }
        }
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<AutomationRuleListItemBinding>, position: Int) {
        val rule = getItem(position) ?: return
        holder.binding.automationRuleName.text = rule.name.ifBlank { "Unnamed rule" }
        holder.binding.automationRuleSummary.text = buildSummary(rule)
        holder.binding.automationRuleEnabled.setOnCheckedChangeListener(null)
        holder.binding.automationRuleEnabled.isChecked = rule.enabled
        holder.binding.automationRuleEnabled.setOnCheckedChangeListener { _, isChecked ->
            val r = getItem(holder.adapterPosition) ?: return@setOnCheckedChangeListener
            if (r.enabled != isChecked) {
                toggleClicks.onNext(r.id)
            }
        }
    }

    private fun buildSummary(rule: AutomationRule): String {
        val trigger = when {
            rule.matchSender.isNotBlank() && rule.matchBody.isNotBlank() ->
                "Sender: ${rule.matchSender.take(20)} & Body: ${rule.matchBody.take(20)}"
            rule.matchSender.isNotBlank() -> "Sender: ${rule.matchSender.take(30)}"
            rule.matchBody.isNotBlank() -> "Body: ${rule.matchBody.take(30)}"
            else -> "Any message"
        }
        val action = when (rule.action) {
            AutomationRule.ACTION_DELETE -> "→ Delete"
            AutomationRule.ACTION_ARCHIVE -> "→ Archive"
            AutomationRule.ACTION_MARK_READ -> "→ Mark read"
            AutomationRule.ACTION_FORWARD_SMS -> "→ Forward (SMS)"
            AutomationRule.ACTION_FORWARD_HTTP -> "→ Forward (HTTP)"
            AutomationRule.ACTION_AUTO_REPLY -> "→ Auto-reply"
            else -> "→ ?"
        }
        val delay = if (rule.delayMs > 0) " (${rule.delayMs / 1000}s delay)" else ""
        return "$trigger $action$delay • ${rule.hitCount} hits"
    }
}

