package dev.octoshrimpy.quik.feature.settings.automations

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.app.AlertDialog
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import com.bluelinelabs.conductor.RouterTransaction
import com.jakewharton.rxbinding2.view.clicks
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.QkChangeHandler
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.databinding.AutomationsControllerBinding
import dev.octoshrimpy.quik.feature.settings.automations.edit.EditAutomationController
import dev.octoshrimpy.quik.injection.appComponent
import io.reactivex.Observable
import javax.inject.Inject

class AutomationsController : QkController<AutomationsControllerBinding, AutomationsView, AutomationsState, AutomationsPresenter>(), AutomationsView {

    @Inject override lateinit var presenter: AutomationsPresenter
    @Inject lateinit var colors: Colors
    private val deleteConfirmedSubject: Subject<Long> = PublishSubject.create()
    private val adapter = AutomationRulesAdapter()

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
    }

    override fun onViewCreated() {
        super.onViewCreated()
        binding.fab.setBackgroundTint(colors.theme().theme)
        binding.fab.setTint(colors.theme().textPrimary)
        adapter.emptyView = binding.empty
        binding.rulesRecycler.adapter = adapter
        binding.rulesRecycler.itemAnimator = null
    }

    override fun render(state: AutomationsState) {
        adapter.updateData(state.rules)
    }

    override fun addRuleClicks(): Observable<*> = binding.fab.clicks()

    override fun editRuleClicks(): Observable<Long> = adapter.editClicks

    override fun deleteRuleConfirmed(): Observable<Long> = deleteConfirmedSubject
    override fun deleteRuleClicks(): Observable<Long> = adapter.deleteClicks
    override fun toggleRuleClicks(): Observable<Long> = adapter.toggleClicks

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
