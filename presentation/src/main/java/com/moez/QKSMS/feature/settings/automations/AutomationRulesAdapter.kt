package dev.octoshrimpy.quik.feature.settings.automations

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.common.base.QkRealmAdapter
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
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
                val position = adapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val rule = getItem(position) ?: return@setOnClickListener
                editClicks.onNext(rule.id)
            }
            binding.automationRuleEdit.setOnClickListener {
                val position = adapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val rule = getItem(position) ?: return@setOnClickListener
                editClicks.onNext(rule.id)
            }
            binding.automationRuleDelete.setOnClickListener {
                val position = adapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val rule = getItem(position) ?: return@setOnClickListener
                deleteClicks.onNext(rule.id)
            }
        }
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<AutomationRuleListItemBinding>, position: Int) {
        val rule = getItem(position) ?: return
        val context = holder.binding.root.context

        holder.binding.automationRuleName.text = rule.name.ifBlank {
            context.getString(R.string.automation_rule_unnamed)
        }

        // Status Chip
        if (rule.enabled) {
            holder.binding.automationRuleStatusChip.text = context.getString(R.string.automation_status_active)
            holder.binding.automationRuleStatusChip.setBackgroundResource(R.drawable.status_chip_active_background)
            holder.binding.automationRuleStatusChip.setTextColor(context.resolveThemeColor(R.attr.colorOnPrimaryContainer))
        } else {
            holder.binding.automationRuleStatusChip.text = context.getString(R.string.automation_status_paused)
            holder.binding.automationRuleStatusChip.setBackgroundResource(R.drawable.status_chip_paused_background)
            holder.binding.automationRuleStatusChip.setTextColor(context.resolveThemeColor(R.attr.colorOnSurfaceVariant))
        }

        // Switch
        holder.binding.automationRuleEnabled.setOnCheckedChangeListener(null)
        if (holder.binding.automationRuleEnabled.isChecked != rule.enabled) {
            holder.binding.automationRuleEnabled.isChecked = rule.enabled
        }
        holder.binding.automationRuleEnabled.setOnCheckedChangeListener { _, isChecked ->
            val pos = holder.adapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
            val r = getItem(pos) ?: return@setOnCheckedChangeListener
            if (r.enabled != isChecked) {
                toggleClicks.onNext(r.id)
            }
        }

        // Hit count
        holder.binding.automationRuleHitCount.text = context.getString(R.string.automation_hits_format, rule.hitCount)

        // Flow Chips
        bindFlowChips(context, holder.binding.flowChipsContainer, rule)
    }

    private fun bindFlowChips(context: Context, container: LinearLayout, rule: AutomationRule) {
        container.removeAllViews()

        val chips = mutableListOf<String>()

        // Trigger badges
        if (rule.matchSender.isNotBlank()) {
            chips.add("From: ${rule.matchSender.take(16)}")
        }
        if (rule.matchBody.isNotBlank()) {
            chips.add("Body: ${rule.matchBody.take(16)}")
        }
        if (rule.matchSender.isBlank() && rule.matchBody.isBlank()) {
            chips.add("Any message")
        }

        // Timing badge
        chips.add("Delay: ${if (rule.delayMs > 0) "${rule.delayMs / 1000}s" else "0s"}")

        // Action chain badges
        val steps = rule.getActionSteps()
        steps.forEachIndexed { i, step ->
            val label = when (step.action) {
                AutomationRule.ACTION_DELETE -> "Delete"
                AutomationRule.ACTION_ARCHIVE -> "Archive"
                AutomationRule.ACTION_MARK_READ -> "Mark read"
                AutomationRule.ACTION_FORWARD_SMS -> "Forward SMS"
                AutomationRule.ACTION_FORWARD_HTTP -> "Forward HTTP"
                AutomationRule.ACTION_AUTO_REPLY -> "Auto-reply"
                else -> step.action
            }
            chips.add("${i + 1}. $label")
        }

        chips.forEachIndexed { index, text ->
            if (index > 0) {
                addArrow(context, container)
            }
            addFlowChip(context, container, text)
        }
    }

    private fun addFlowChip(context: Context, container: LinearLayout, text: String) {
        val tv = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(context.resolveThemeColor(android.R.attr.textColorSecondary))
            setBackgroundResource(R.drawable.flow_chip_background)
            val horiz = (8 * context.resources.displayMetrics.density).toInt()
            val vert = (3 * context.resources.displayMetrics.density).toInt()
            setPadding(horiz, vert, horiz, vert)
        }
        container.addView(tv)
    }

    private fun addArrow(context: Context, container: LinearLayout) {
        val tv = TextView(context).apply {
            text = "→"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(context.resolveThemeColor(android.R.attr.textColorSecondary))
            val margin = (4 * context.resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = margin
                marginEnd = margin
            }
            layoutParams = lp
        }
        container.addView(tv)
    }
}
