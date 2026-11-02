package dev.octoshrimpy.quik.feature.settings.automations

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.bluelinelabs.conductor.RouterTransaction
import com.google.android.material.tabs.TabLayout
import com.jakewharton.rxbinding2.view.clicks
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.QkChangeHandler
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.databinding.AutomationsControllerBinding
import dev.octoshrimpy.quik.feature.settings.automations.edit.EditAutomationController
import dev.octoshrimpy.quik.injection.appComponent
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class AutomationsController : QkController<AutomationsControllerBinding, AutomationsView, AutomationsState, AutomationsPresenter>(), AutomationsView {

    @Inject override lateinit var presenter: AutomationsPresenter
    @Inject lateinit var colors: Colors

    private val deleteConfirmedSubject: Subject<Long> = PublishSubject.create()
    private val tabSelectedSubject: Subject<Int> = PublishSubject.create()
    private val clearFailedSubject: Subject<Unit> = PublishSubject.create()
    private val clearLogsSubject: Subject<Unit> = PublishSubject.create()
    private val filterLogsSubject: Subject<String> = PublishSubject.create()
    private val exportLogsSubject: Subject<Unit> = PublishSubject.create()

    companion object {
        private const val REQUEST_SAVE_EXPORT = 2002
    }

    private val adapter = AutomationRulesAdapter()
    private val failedAdapter = FailedAutomationMessagesAdapter()
    private val logsAdapter = AutomationLogsAdapter()

    private var currentTab: Int = AutomationsState.TAB_RULES
    private var pendingExportJson: String? = null

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): AutomationsControllerBinding =
        AutomationsControllerBinding.inflate(inflater, container, false)

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.settings_automations_title)
        showBackButton(true)
        setHasOptionsMenu(true)
    }

    override fun onViewCreated() {
        super.onViewCreated()
        binding.fab.setBackgroundTint(colors.theme().theme)
        binding.fab.setTint(colors.theme().textPrimary)

        // SAF save handled via startActivityForResult/onActivityResult (Conductor
        // controllers don't support the androidx ActivityResult API)

        logsAdapter.clicks
            .subscribe { log -> showLogDetails(log) }

        adapter.emptyView = binding.empty
        binding.rulesRecycler.adapter = adapter
        binding.rulesRecycler.itemAnimator = null

        failedAdapter.emptyView = binding.failedEmpty
        binding.failedRecycler.adapter = failedAdapter
        binding.failedRecycler.itemAnimator = null

        logsAdapter.emptyView = binding.logsEmpty
        binding.logsRecycler.adapter = logsAdapter
        binding.logsRecycler.itemAnimator = null

        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.automations_tab_rules))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.automations_tab_failed))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.automations_tab_logs))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.position?.let {
                    currentTab = it
                    tabSelectedSubject.onNext(it)
                    activity?.invalidateOptionsMenu()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.chipFilterAll.setOnClickListener { filterLogsSubject.onNext(AutomationsState.FILTER_ALL) }
        binding.chipFilterErrors.setOnClickListener { filterLogsSubject.onNext("ERROR") }
        binding.chipFilterSuccess.setOnClickListener { filterLogsSubject.onNext("SUCCESS") }
        binding.exportLogsButton.setOnClickListener { exportLogsSubject.onNext(Unit) }
    }

    override fun onDestroyView(view: View) {
        adapter.emptyView = null
        failedAdapter.emptyView = null
        logsAdapter.emptyView = null
        super.onDestroyView(view)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.automations, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.action_clear_all)?.isVisible = (currentTab == AutomationsState.TAB_FAILED)
        menu.findItem(R.id.action_clear_logs)?.isVisible = (currentTab == AutomationsState.TAB_LOGS)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_all -> {
                val act = activity ?: return true
                AlertDialog.Builder(act)
                    .setTitle(R.string.failed_automation_clear_all)
                    .setMessage(R.string.failed_automation_clear_confirm)
                    .setPositiveButton(R.string.button_delete) { _, _ ->
                        clearFailedSubject.onNext(Unit)
                    }
                    .setNegativeButton(R.string.button_cancel, null)
                    .show()
                true
            }
            R.id.action_clear_logs -> {
                val act = activity ?: return true
                AlertDialog.Builder(act)
                    .setTitle(R.string.automation_logs_clear)
                    .setMessage(R.string.automation_logs_clear_confirm)
                    .setPositiveButton(R.string.button_delete) { _, _ ->
                        clearLogsSubject.onNext(Unit)
                    }
                    .setNegativeButton(R.string.button_cancel, null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun render(state: AutomationsState) {
        adapter.updateData(state.rules)
        failedAdapter.updateData(state.failedMessages)
        logsAdapter.updateData(state.logs)

        currentTab = state.selectedTab
        if (binding.tabLayout.selectedTabPosition != state.selectedTab) {
            binding.tabLayout.getTabAt(state.selectedTab)?.select()
        }

        when (state.selectedTab) {
            AutomationsState.TAB_RULES -> {
                binding.rulesContainer.visibility = View.VISIBLE
                binding.failedContainer.visibility = View.GONE
                binding.logsContainer.visibility = View.GONE
                binding.fab.show()
            }
            AutomationsState.TAB_FAILED -> {
                binding.rulesContainer.visibility = View.GONE
                binding.failedContainer.visibility = View.VISIBLE
                binding.logsContainer.visibility = View.GONE
                binding.fab.hide()
            }
            AutomationsState.TAB_LOGS -> {
                binding.rulesContainer.visibility = View.GONE
                binding.failedContainer.visibility = View.GONE
                binding.logsContainer.visibility = View.VISIBLE
                binding.fab.hide()
            }
        }

        // Filter chips style
        val activeTextColor = binding.root.context.resolveThemeColor(R.attr.colorOnPrimaryContainer)
        val inactiveTextColor = binding.root.context.resolveThemeColor(android.R.attr.textColorSecondary)

        fun updateChipStyle(chip: TextView, isActive: Boolean) {
            if (isActive) {
                chip.setBackgroundResource(R.drawable.status_chip_active_background)
                chip.setTextColor(activeTextColor)
            } else {
                chip.setBackgroundResource(R.drawable.flow_chip_background)
                chip.setTextColor(inactiveTextColor)
            }
        }

        updateChipStyle(binding.chipFilterAll, state.logFilter == null)
        updateChipStyle(binding.chipFilterErrors, state.logFilter == "ERROR")
        updateChipStyle(binding.chipFilterSuccess, state.logFilter == "SUCCESS")

        activity?.invalidateOptionsMenu()
    }

    override fun addRuleClicks(): Observable<*> = binding.fab.clicks()

    override fun editRuleClicks(): Observable<Long> = adapter.editClicks

    override fun deleteRuleConfirmed(): Observable<Long> = deleteConfirmedSubject
    override fun deleteRuleClicks(): Observable<Long> = adapter.deleteClicks
    override fun toggleRuleClicks(): Observable<Long> = adapter.toggleClicks

    override fun tabSelectedClicks(): Observable<Int> = tabSelectedSubject
    override fun retryFailedClicks(): Observable<Long> = failedAdapter.retryClicks
    override fun dismissFailedClicks(): Observable<Long> = failedAdapter.dismissClicks
    override fun clearFailedClicks(): Observable<*> = clearFailedSubject
    override fun filterLogsClicks(): Observable<String> = filterLogsSubject
    override fun clearLogsClicks(): Observable<*> = clearLogsSubject
    override fun exportLogsClicks(): Observable<Unit> = exportLogsSubject

    override fun showExport(json: String) {
        val act = activity ?: return
        AlertDialog.Builder(act)
            .setTitle(R.string.automation_export_title)
            .setItems(
                arrayOf(
                    act.getString(R.string.automation_export_copy),
                    act.getString(R.string.automation_export_save)
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        val capped = if (json.length > 500_000) json.take(500_000) + "\n\"…truncated\"" else json
                        val clipboard = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("quik-automation-diagnostics", capped)
                        )
                        android.widget.Toast.makeText(act, R.string.automation_export_copied, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val fileName = "quik-automation-logs-" +
                            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                                .format(java.util.Date()) + ".json"
                        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            type = "application/json"
                            putExtra(android.content.Intent.EXTRA_TITLE, fileName)
                        }
                        startActivityForResult(intent, REQUEST_SAVE_EXPORT)
                    }
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
        pendingExportJson = json
    }

    override fun showLogDetails(log: dev.octoshrimpy.quik.model.AutomationLog) {
        val act = activity ?: return
        val pretty = try {
            org.json.JSONObject(log.jsonPayload.ifBlank { "{}" }).toString(2)
        } catch (e: Exception) {
            log.jsonPayload
        }
        val message = buildString {
            append("Type: ").append(log.type).append('\n')
            append("Action: ").append(log.actionType).append('\n')
            append("Rule: ").append(log.ruleName.ifBlank { "Automation" }).append('\n')
            append("Message ID: ").append(log.messageId).append('\n')
            append("Timestamp: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(log.timestamp))).append('\n')
            append("\nDetails:\n").append(log.details).append('\n')
            append("\nPayload:\n").append(pretty)
        }
        AlertDialog.Builder(act)
            .setTitle(R.string.automation_log_details)
            .setMessage(message)
            .setPositiveButton(R.string.automation_log_copy_payload) { _, _ ->
                val entryJson = org.json.JSONObject()
                    .put("id", log.id)
                    .put("ts", log.timestamp)
                    .put("type", log.type)
                    .put("actionType", log.actionType)
                    .put("ruleId", log.ruleId)
                    .put("ruleName", log.ruleName)
                    .put("messageId", log.messageId)
                    .put("details", log.details)
                    .put("data", org.json.JSONObject(log.jsonPayload.ifBlank { "{}" }))
                    .toString(2)
                val clipboard = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("quik-automation-log", entryJson)
                )
                android.widget.Toast.makeText(act, R.string.automation_export_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SAVE_EXPORT && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val json = pendingExportJson ?: return
            pendingExportJson = null
            try {
                activity?.contentResolver?.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                android.widget.Toast.makeText(activity, R.string.automation_export_copied, android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.w("AutomationsController", "Failed to write export file", e)
            }
        }
    }

    override fun confirmDelete(ruleId: Long) {
        val act = activity ?: return
        AlertDialog.Builder(act)
            .setTitle(R.string.automation_delete_confirm_title)
            .setMessage(R.string.automation_delete_confirm_message)
            .setPositiveButton(R.string.button_delete) { _, _ ->
                deleteConfirmedSubject.onNext(ruleId)
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showEditRule(ruleId: Long) {
        router.pushController(
            RouterTransaction.with(EditAutomationController(ruleId))
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler())
        )
    }
}
