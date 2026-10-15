package dev.octoshrimpy.quik.feature.settings.automations.edit

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.checkedChanges
import com.jakewharton.rxbinding2.widget.textChanges
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.databinding.EditAutomationControllerBinding
import dev.octoshrimpy.quik.databinding.ItemAutomationActionStepBinding
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.model.AutomationActionStep
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.AutomationStepHelper
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import timber.log.Timber
import javax.inject.Inject

class EditAutomationController(private val ruleId: Long = -1L)
    : QkController<EditAutomationControllerBinding, EditAutomationView, EditAutomationState, EditAutomationPresenter>(),
    EditAutomationView {

    constructor(bundle: Bundle) : this(bundle.getLong(KEY_RULE_ID, -1L))

    companion object {
        const val KEY_RULE_ID = "ruleId"
        private const val REQUEST_PICK_CONTACT = 2001
    }

    @Inject override lateinit var presenter: EditAutomationPresenter
    @Inject lateinit var colors: Colors

    private var isRendering = false
    private var pendingContactStepIndex: Int = -1
    private var currentSteps: List<AutomationActionStep> = emptyList()

    private val delaySubject: Subject<Long> = PublishSubject.create()
    private val presetSubject: Subject<Pair<String, Boolean>> = PublishSubject.create()
    private val addStepSubject: Subject<Unit> = PublishSubject.create()
    private val removeStepSubject: Subject<Int> = PublishSubject.create()
    private val moveUpSubject: Subject<Int> = PublishSubject.create()
    private val moveDownSubject: Subject<Int> = PublishSubject.create()
    private val stepUpdatedSubject: Subject<Pair<Int, AutomationActionStep>> = PublishSubject.create()

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        args.putLong(KEY_RULE_ID, ruleId)
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

        binding.delayNone.setOnClickListener { delaySubject.onNext(0L) }
        binding.delay30s.setOnClickListener { delaySubject.onNext(30_000L) }
        binding.delay1m.setOnClickListener { delaySubject.onNext(60_000L) }
        binding.delay5m.setOnClickListener { delaySubject.onNext(300_000L) }
        binding.delay1h.setOnClickListener { delaySubject.onNext(3_600_000L) }

        binding.addActionStepButton.setOnClickListener {
            addStepSubject.onNext(Unit)
        }
    }

    override fun render(state: EditAutomationState) {
        currentSteps = state.actionSteps
        isRendering = true
        try {
            if (binding.ruleName.text.toString() != state.name) {
                binding.ruleName.setText(state.name)
            }
            if (binding.matchSenderField.text.toString() != state.matchSender) {
                binding.matchSenderField.setText(state.matchSender)
            }
            if (binding.matchBodyField.text.toString() != state.matchBody) {
                binding.matchBodyField.setText(state.matchBody)
            }

            if (binding.senderRegexSwitch.isChecked != state.matchSenderIsRegex) {
                binding.senderRegexSwitch.isChecked = state.matchSenderIsRegex
            }
            if (binding.senderCaseSwitch.isChecked != state.matchSenderCaseSensitive) {
                binding.senderCaseSwitch.isChecked = state.matchSenderCaseSensitive
            }
            if (binding.bodyRegexSwitch.isChecked != state.matchBodyIsRegex) {
                binding.bodyRegexSwitch.isChecked = state.matchBodyIsRegex
            }
            if (binding.bodyCaseSwitch.isChecked != state.matchBodyCaseSensitive) {
                binding.bodyCaseSwitch.isChecked = state.matchBodyCaseSensitive
            }
            if (binding.skipContactsSwitch.isChecked != state.skipContacts) {
                binding.skipContactsSwitch.isChecked = state.skipContacts
            }

            // Visually mark the selected delay chip
            val selectedChip = when (state.delayMs) {
                0L -> binding.delayNone
                30_000L -> binding.delay30s
                60_000L -> binding.delay1m
                300_000L -> binding.delay5m
                3_600_000L -> binding.delay1h
                else -> null
            }
            val accent = colors.theme().theme
            listOf(binding.delayNone, binding.delay30s, binding.delay1m, binding.delay5m, binding.delay1h)
                .forEach { chip ->
                    chip.isActivated = (chip == selectedChip)
                    chip.setTextColor(if (chip == selectedChip) accent
                        else chip.context.resolveThemeColor(android.R.attr.textColorSecondary))
                }

            val ctx = binding.root.context
            binding.delaySummary.text = when (state.delayMs) {
                0L -> ctx.getString(R.string.automation_delay_selected_none)
                30_000L -> ctx.getString(R.string.automation_delay_selected_30s)
                60_000L -> ctx.getString(R.string.automation_delay_selected_1m)
                300_000L -> ctx.getString(R.string.automation_delay_selected_5m)
                3_600_000L -> ctx.getString(R.string.automation_delay_selected_1h)
                else -> ctx.getString(R.string.automation_delay_selected_custom, state.delayMs / 1000)
            }

            // Render modular action steps
            renderActionSteps(state.actionSteps)

            // Disable + Add Action button if all 6 actions are already used
            val canAddMore = state.actionSteps.size < AutomationStepHelper.ALL_ACTIONS.size
            binding.addActionStepButton.isEnabled = canAddMore
            binding.addActionStepButton.alpha = if (canAddMore) 1f else 0.5f

        } finally {
            isRendering = false
        }
    }

    private fun renderActionSteps(steps: List<AutomationActionStep>) {
        val container = binding.actionStepsContainer
        val inflater = LayoutInflater.from(container.context)
        container.removeAllViews()

        val usedActions = steps.map { it.action }.toSet()

        steps.forEachIndexed { index, step ->
            val stepBinding = ItemAutomationActionStepBinding.inflate(inflater, container, false)
            val ctx = stepBinding.root.context

            // Badge
            stepBinding.stepNumberBadge.text = ctx.getString(R.string.automation_step_title, index + 1)

            // Title
            val titleRes = when (step.action) {
                AutomationRule.ACTION_FORWARD_SMS -> R.string.automation_action_forward_sms
                AutomationRule.ACTION_FORWARD_HTTP -> R.string.automation_action_forward_http
                AutomationRule.ACTION_AUTO_REPLY -> R.string.automation_action_auto_reply
                AutomationRule.ACTION_MARK_READ -> R.string.automation_action_mark_read
                AutomationRule.ACTION_ARCHIVE -> R.string.automation_action_archive
                AutomationRule.ACTION_DELETE -> R.string.automation_action_delete
                else -> R.string.automation_action_delete
            }
            stepBinding.actionTypeSelector.text = ctx.getString(titleRes)

            // Reorder & Remove buttons
            val isDelete = step.action == AutomationRule.ACTION_DELETE
            val isFirst = index == 0
            val isLast = index == steps.size - 1
            val nextIsDelete = !isLast && steps[index + 1].action == AutomationRule.ACTION_DELETE

            stepBinding.btnMoveUp.isEnabled = !isFirst && !isDelete
            stepBinding.btnMoveUp.alpha = if (!isFirst && !isDelete) 1f else 0.3f
            stepBinding.btnMoveUp.setOnClickListener { moveUpSubject.onNext(index) }

            stepBinding.btnMoveDown.isEnabled = !isLast && !nextIsDelete
            stepBinding.btnMoveDown.alpha = if (!isLast && !nextIsDelete) 1f else 0.3f
            stepBinding.btnMoveDown.setOnClickListener { moveDownSubject.onNext(index) }

            val canRemove = steps.size > 1
            stepBinding.btnRemoveStep.isEnabled = canRemove
            stepBinding.btnRemoveStep.alpha = if (canRemove) 1f else 0.3f
            stepBinding.btnRemoveStep.setOnClickListener { removeStepSubject.onNext(index) }

            // Action type selector dialog
            stepBinding.actionTypeSelector.setOnClickListener {
                showActionTypePicker(index, step, usedActions)
            }

            // Contextual inputs
            stepBinding.forwardSmsContainer.isVisible = step.action == AutomationRule.ACTION_FORWARD_SMS
            stepBinding.forwardHttpContainer.isVisible = step.action == AutomationRule.ACTION_FORWARD_HTTP
            stepBinding.autoReplyContainer.isVisible = step.action == AutomationRule.ACTION_AUTO_REPLY
            stepBinding.stepDescriptionText.isVisible = step.action == AutomationRule.ACTION_MARK_READ ||
                step.action == AutomationRule.ACTION_ARCHIVE ||
                step.action == AutomationRule.ACTION_DELETE

            when (step.action) {
                AutomationRule.ACTION_FORWARD_SMS -> {
                    if (stepBinding.forwardPhoneField.text.toString() != step.forwardToPhone) {
                        stepBinding.forwardPhoneField.setText(step.forwardToPhone)
                    }
                    stepBinding.forwardPhoneField.doAfterTextChanged { s ->
                        if (!isRendering) {
                            stepUpdatedSubject.onNext(index to step.copy(forwardToPhone = s?.toString().orEmpty()))
                        }
                    }
                    if (stepBinding.appendSenderSwitch.isChecked != step.appendSenderToForward) {
                        stepBinding.appendSenderSwitch.isChecked = step.appendSenderToForward
                    }
                    stepBinding.appendSenderSwitch.setOnCheckedChangeListener { _, isChecked ->
                        if (!isRendering) {
                            stepUpdatedSubject.onNext(index to step.copy(appendSenderToForward = isChecked))
                        }
                    }
                    stepBinding.btnPickContact.setOnClickListener {
                        pendingContactStepIndex = index
                        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                        startActivityForResult(intent, REQUEST_PICK_CONTACT)
                    }
                }
                AutomationRule.ACTION_FORWARD_HTTP -> {
                    if (stepBinding.forwardUrlField.text.toString() != step.forwardToUrl) {
                        stepBinding.forwardUrlField.setText(step.forwardToUrl)
                    }
                    stepBinding.forwardUrlField.doAfterTextChanged { s ->
                        if (!isRendering) {
                            stepUpdatedSubject.onNext(index to step.copy(forwardToUrl = s?.toString().orEmpty()))
                        }
                    }
                }
                AutomationRule.ACTION_AUTO_REPLY -> {
                    if (stepBinding.replyTemplateField.text.toString() != step.replyTemplate) {
                        stepBinding.replyTemplateField.setText(step.replyTemplate)
                    }
                    stepBinding.replyTemplateField.doAfterTextChanged { s ->
                        if (!isRendering) {
                            stepUpdatedSubject.onNext(index to step.copy(replyTemplate = s?.toString().orEmpty()))
                        }
                    }
                }
                AutomationRule.ACTION_MARK_READ -> {
                    stepBinding.stepDescriptionText.setText(R.string.automation_action_mark_read_desc)
                }
                AutomationRule.ACTION_ARCHIVE -> {
                    stepBinding.stepDescriptionText.setText(R.string.automation_action_archive_desc)
                }
                AutomationRule.ACTION_DELETE -> {
                    stepBinding.stepDescriptionText.setText(R.string.automation_action_delete_desc)
                }
            }

            container.addView(stepBinding.root)
        }
    }

    private fun showActionTypePicker(stepIndex: Int, currentStep: AutomationActionStep, usedActions: Set<String>) {
        val act = activity ?: return
        // Available actions: unused actions plus the current step's action
        val available = AutomationStepHelper.ALL_ACTIONS.filter { it == currentStep.action || it !in usedActions }
        val labels = available.map { action ->
            when (action) {
                AutomationRule.ACTION_FORWARD_SMS -> act.getString(R.string.automation_action_forward_sms)
                AutomationRule.ACTION_FORWARD_HTTP -> act.getString(R.string.automation_action_forward_http)
                AutomationRule.ACTION_AUTO_REPLY -> act.getString(R.string.automation_action_auto_reply)
                AutomationRule.ACTION_MARK_READ -> act.getString(R.string.automation_action_mark_read)
                AutomationRule.ACTION_ARCHIVE -> act.getString(R.string.automation_action_archive)
                AutomationRule.ACTION_DELETE -> act.getString(R.string.automation_action_delete)
                else -> action
            }
        }.toTypedArray()

        AlertDialog.Builder(act)
            .setTitle(R.string.automation_select_action_title)
            .setItems(labels) { _, which ->
                val selectedAction = available[which]
                if (selectedAction != currentStep.action) {
                    stepUpdatedSubject.onNext(stepIndex to currentStep.copy(action = selectedAction))
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_CONTACT && resultCode == Activity.RESULT_OK && data?.data != null) {
            val contactUri = data.data ?: return
            val act = activity ?: return
            try {
                act.contentResolver.query(
                    contactUri,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val number = cursor.getString(0).orEmpty()
                        if (pendingContactStepIndex >= 0 && number.isNotBlank()) {
                            // Extract numbers and + if present
                            val cleanNumber = number.replace(Regex("[^0-9+]"), "")
                            if (pendingContactStepIndex in currentSteps.indices) {
                                val step = currentSteps[pendingContactStepIndex]
                                stepUpdatedSubject.onNext(pendingContactStepIndex to step.copy(forwardToPhone = cleanNumber))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error reading contact phone number")
            }
        }
    }

    override fun close() {
        router.popCurrentController()
    }

    override fun nameChanges(): Observable<String> =
        binding.ruleName.textChanges().skipInitialValue().filter { !isRendering }.map { it.toString() }

    override fun matchSenderChanges(): Observable<String> =
        binding.matchSenderField.textChanges().skipInitialValue().filter { !isRendering }.map { it.toString() }

    override fun matchSenderRegexToggle(): Observable<Boolean> =
        binding.senderRegexSwitch.checkedChanges().skipInitialValue().filter { !isRendering }

    override fun matchSenderCaseSensitiveToggle(): Observable<Boolean> =
        binding.senderCaseSwitch.checkedChanges().skipInitialValue().filter { !isRendering }

    override fun matchBodyChanges(): Observable<String> =
        binding.matchBodyField.textChanges().skipInitialValue().filter { !isRendering }.map { it.toString() }

    override fun matchBodyRegexToggle(): Observable<Boolean> =
        binding.bodyRegexSwitch.checkedChanges().skipInitialValue().filter { !isRendering }

    override fun matchBodyCaseSensitiveToggle(): Observable<Boolean> =
        binding.bodyCaseSwitch.checkedChanges().skipInitialValue().filter { !isRendering }

    override fun skipContactsToggle(): Observable<Boolean> =
        binding.skipContactsSwitch.checkedChanges().skipInitialValue().filter { !isRendering }

    override fun delaySelected(): Observable<Long> = delaySubject
    override fun saveClicks(): Observable<*> = binding.saveButton.clicks()
    override fun presetClicks(): Observable<*> = binding.presetsButton.clicks()

    override fun presetSelected(): Observable<Pair<String, Boolean>> = presetSubject

    override fun addStepClicks(): Observable<*> = addStepSubject
    override fun removeStepClicks(): Observable<Int> = removeStepSubject
    override fun moveStepUpClicks(): Observable<Int> = moveUpSubject
    override fun moveStepDownClicks(): Observable<Int> = moveDownSubject
    override fun stepUpdated(): Observable<Pair<Int, AutomationActionStep>> = stepUpdatedSubject

    override fun showPresets(presets: List<Pair<String, String>>) {
        val act = activity ?: return
        val labels = presets.map { it.first }.toTypedArray()
        AlertDialog.Builder(act)
            .setTitle(R.string.automation_presets_title)
            .setItems(labels) { _, index ->
                // Emit atomic preset selection to presenter; avoid direct UI mutation to eliminate toggle loops
                presetSubject.onNext(presets[index].second to true)
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showValidationError(error: String) {
        val act = activity ?: return
        val message = when (error) {
            EditAutomationPresenter.VALIDATION_URL ->
                act.getString(R.string.automation_validation_url)
            EditAutomationPresenter.VALIDATION_PHONE ->
                act.getString(R.string.automation_validation_phone)
            EditAutomationPresenter.VALIDATION_TEMPLATE ->
                act.getString(R.string.automation_validation_template)
            else -> return
        }
        AlertDialog.Builder(act)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
