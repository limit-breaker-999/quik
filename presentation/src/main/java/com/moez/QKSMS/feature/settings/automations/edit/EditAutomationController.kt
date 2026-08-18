package dev.octoshrimpy.quik.feature.settings.automations.edit

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.core.view.isVisible
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.checkedChanges
import com.jakewharton.rxbinding2.widget.textChanges
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.databinding.EditAutomationControllerBinding
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.model.AutomationRule
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class EditAutomationController(private val ruleId: Long = -1L)
    : QkController<EditAutomationControllerBinding, EditAutomationView, EditAutomationState, EditAutomationPresenter>(),
    EditAutomationView {

    constructor(bundle: Bundle) : this(bundle.getLong(KEY_RULE_ID, -1L))

    companion object {
        const val KEY_RULE_ID = "ruleId"
    }

    @Inject override lateinit var presenter: EditAutomationPresenter

    private val actionSubject: Subject<String> = PublishSubject.create()
    private val delaySubject: Subject<Long> = PublishSubject.create()

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): EditAutomationControllerBinding =
        EditAutomationControllerBinding.inflate(inflater, container, false)

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.setRuleId(ruleId)
        presenter.bindIntents(this)
        setTitle(if (ruleId < 0) R.string.automation_edit_title_new else R.string.automation_edit_title_edit)
        showBackButton(true)
    }

    override fun onViewCreated() {
        super.onViewCreated()

        // Use RadioGroup's listener for proper mutual-exclusion behavior
        binding.actionGroup.setOnCheckedChangeListener { _, checkedId ->
            val action = when (checkedId) {
                R.id.actionDelete -> AutomationRule.ACTION_DELETE
                R.id.actionArchive -> AutomationRule.ACTION_ARCHIVE
                R.id.actionMarkRead -> AutomationRule.ACTION_MARK_READ
                R.id.actionForwardSms -> AutomationRule.ACTION_FORWARD_SMS
                R.id.actionForwardHttp -> AutomationRule.ACTION_FORWARD_HTTP
                R.id.actionAutoReply -> AutomationRule.ACTION_AUTO_REPLY
                else -> return@setOnCheckedChangeListener
            }
            actionSubject.onNext(action)
        }

        binding.delayNone.setOnClickListener { delaySubject.onNext(0L) }
        binding.delay30s.setOnClickListener { delaySubject.onNext(30_000L) }
        binding.delay1m.setOnClickListener { delaySubject.onNext(60_000L) }
        binding.delay5m.setOnClickListener { delaySubject.onNext(300_000L) }
        binding.delay1h.setOnClickListener { delaySubject.onNext(3_600_000L) }
    }

    override fun render(state: EditAutomationState) {
        if (binding.ruleName.text.toString() != state.name) binding.ruleName.setText(state.name)
        if (binding.matchSenderField.text.toString() != state.matchSender) binding.matchSenderField.setText(state.matchSender)
        if (binding.matchBodyField.text.toString() != state.matchBody) binding.matchBodyField.setText(state.matchBody)

        binding.senderRegexSwitch.isChecked = state.matchSenderIsRegex
        binding.senderCaseSwitch.isChecked = state.matchSenderCaseSensitive
        binding.bodyRegexSwitch.isChecked = state.matchBodyIsRegex
        binding.bodyCaseSwitch.isChecked = state.matchBodyCaseSensitive
        binding.skipContactsSwitch.isChecked = state.skipContacts

        // Check the correct radio button via the RadioGroup (avoids listener re-entrancy)
        val targetId = when (state.action) {
            AutomationRule.ACTION_DELETE       -> R.id.actionDelete
            AutomationRule.ACTION_ARCHIVE      -> R.id.actionArchive
            AutomationRule.ACTION_MARK_READ    -> R.id.actionMarkRead
            AutomationRule.ACTION_FORWARD_SMS  -> R.id.actionForwardSms
            AutomationRule.ACTION_FORWARD_HTTP -> R.id.actionForwardHttp
            AutomationRule.ACTION_AUTO_REPLY   -> R.id.actionAutoReply
            else -> R.id.actionDelete
        }
        if (binding.actionGroup.checkedRadioButtonId != targetId) {
            binding.actionGroup.check(targetId)
        }

        // Show/hide action-specific sections
        binding.forwardSmsSection.isVisible  = state.action == AutomationRule.ACTION_FORWARD_SMS
        binding.forwardHttpSection.isVisible = state.action == AutomationRule.ACTION_FORWARD_HTTP
        binding.autoReplySection.isVisible   = state.action == AutomationRule.ACTION_AUTO_REPLY

        if (binding.forwardPhoneField.text.toString() != state.forwardToPhone) binding.forwardPhoneField.setText(state.forwardToPhone)
        if (binding.forwardUrlField.text.toString() != state.forwardToUrl) binding.forwardUrlField.setText(state.forwardToUrl)
        binding.appendSenderSwitch.isChecked = state.appendSenderToForward
        if (binding.replyTemplateField.text.toString() != state.replyTemplate) binding.replyTemplateField.setText(state.replyTemplate)

        binding.delaySummary.text = when (state.delayMs) {
            0L -> "Selected: None (immediate)"
            30_000L -> "Selected: 30 seconds"
            60_000L -> "Selected: 1 minute"
            300_000L -> "Selected: 5 minutes"
            3_600_000L -> "Selected: 1 hour"
            else -> "Selected: ${state.delayMs / 1000}s"
        }
    }

    override fun close() {
        router.popCurrentController()
    }

    override fun nameChanges(): Observable<String> = binding.ruleName.textChanges().map { it.toString() }
    override fun matchSenderChanges(): Observable<String> = binding.matchSenderField.textChanges().map { it.toString() }
    override fun matchSenderRegexToggle(): Observable<Boolean> = binding.senderRegexSwitch.checkedChanges()
    override fun matchSenderCaseSensitiveToggle(): Observable<Boolean> = binding.senderCaseSwitch.checkedChanges()
    override fun matchBodyChanges(): Observable<String> = binding.matchBodyField.textChanges().map { it.toString() }
    override fun matchBodyRegexToggle(): Observable<Boolean> = binding.bodyRegexSwitch.checkedChanges()
    override fun matchBodyCaseSensitiveToggle(): Observable<Boolean> = binding.bodyCaseSwitch.checkedChanges()
    override fun skipContactsToggle(): Observable<Boolean> = binding.skipContactsSwitch.checkedChanges()
    override fun actionSelected(): Observable<String> = actionSubject
    override fun forwardPhoneChanges(): Observable<String> = binding.forwardPhoneField.textChanges().map { it.toString() }
    override fun forwardUrlChanges(): Observable<String> = binding.forwardUrlField.textChanges().map { it.toString() }
    override fun appendSenderToggle(): Observable<Boolean> = binding.appendSenderSwitch.checkedChanges()
    override fun replyTemplateChanges(): Observable<String> = binding.replyTemplateField.textChanges().map { it.toString() }
    override fun delaySelected(): Observable<Long> = delaySubject
    override fun saveClicks(): Observable<*> = binding.saveButton.clicks()
    override fun presetClicks(): Observable<*> = binding.presetsButton.clicks()

    override fun showPresets(presets: List<Pair<String, String>>) {
        val labels = presets.map { it.first }.toTypedArray()
        AlertDialog.Builder(activity!!)
            .setTitle(R.string.automation_presets_title)
            .setItems(labels) { _, index ->
                binding.matchBodyField.setText(presets[index].second)
                binding.bodyRegexSwitch.isChecked = true
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }
}
