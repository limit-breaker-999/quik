package dev.octoshrimpy.quik.feature.settings.automations

import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.repository.AutomationRuleRepository
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject

class AutomationsPresenter @Inject constructor(
    private val automationRepo: AutomationRuleRepository
) : QkPresenter<AutomationsView, AutomationsState>(
    AutomationsState(rules = automationRepo.getRules())
) {

    override fun bindIntents(view: AutomationsView) {
        super.bindIntents(view)

        view.addRuleClicks()
            .autoDisposable(view.scope())
            .subscribe { view.showEditRule(-1L) }

        view.editRuleClicks()
            .autoDisposable(view.scope())
            .subscribe { ruleId -> view.showEditRule(ruleId) }

        view.deleteRuleClicks()
            .observeOn(Schedulers.io())
            .doOnNext(automationRepo::deleteRule)
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe()

        view.toggleRuleClicks()
            .observeOn(Schedulers.io())
            .subscribeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe { ruleId ->
                val rule = automationRepo.getRule(ruleId) ?: return@subscribe
                automationRepo.saveRule(rule.apply { enabled = !enabled })
            }
    }
}

