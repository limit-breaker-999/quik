package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.AutomationReplyState
import dev.octoshrimpy.quik.model.AutomationRule
import io.realm.Realm
import io.realm.RealmResults
import timber.log.Timber
import javax.inject.Inject

class AutomationRuleRepositoryImpl(
    private val realmProvider: () -> Realm
) : AutomationRuleRepository {

    @Inject
    constructor() : this({ Realm.getDefaultInstance() })

    private var rulesRealm: Realm? = null

    override fun getRules(): RealmResults<AutomationRule> {
        try {
            rulesRealm?.takeIf { !it.isClosed }?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing previous rules Realm instance")
        }
        val realm = realmProvider()
        rulesRealm = realm
        return realm
            .where(AutomationRule::class.java)
            .findAllAsync()
    }

    override fun close() {
        try {
            rulesRealm?.takeIf { !it.isClosed }?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing rules Realm instance")
        } finally {
            rulesRealm = null
        }
    }

    override fun getRule(id: Long): AutomationRule? {
        // Return an unmanaged copy: callers mutate the result (e.g. toggling
        // `enabled`) outside a Realm transaction, which throws on managed objects.
        return realmProvider().use { realm ->
            realm.where(AutomationRule::class.java)
                .equalTo("id", id)
                .findFirst()
                ?.let { realm.copyFromRealm(it) }
        }
    }

    override fun tryAcquireReplySlot(ruleId: Long, threadId: Long, rateLimitMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val key = "$ruleId:$threadId"
        return realmProvider().use { realm ->
            var acquired = false
            realm.executeTransaction { r ->
                val state = r.where(AutomationReplyState::class.java)
                    .equalTo("id", key)
                    .findFirst()

                // A limit of 0 means "always reply" (matches AutomationRule semantics)
                if (rateLimitMs > 0 && state != null && now - state.lastReplyAt < rateLimitMs) {
                    acquired = false
                } else {
                    val s = state ?: r.createObject(AutomationReplyState::class.java, key)
                    s.lastReplyAt = now
                    acquired = true
                }
            }
            acquired
        }
    }

    override fun saveRule(rule: AutomationRule) {
        realmProvider().use { realm ->
            realm.refresh()
            realm.executeTransaction {
                // ID generation inside the transaction: atomic against
                // concurrent saves, and doesn't mutate the caller's object
                // (BUG-038/BUG-070)
                val id = if (rule.id == 0L) {
                    (realm.where(AutomationRule::class.java).max("id")?.toLong() ?: -1L) + 1L
                } else {
                    rule.id
                }
                val existing = if (rule.id != 0L) {
                    realm.where(AutomationRule::class.java).equalTo("id", rule.id).findFirst()
                } else null

                val actionsJsonToSave = when {
                    rule.actionsJson.isNotBlank() -> rule.actionsJson
                    existing?.actionsJson?.isNotBlank() == true -> existing.actionsJson
                    else -> dev.octoshrimpy.quik.model.AutomationActionStepSerializer.toJson(rule.getActionSteps())
                }

                val parsedSteps = dev.octoshrimpy.quik.model.AutomationActionStepSerializer.fromJson(actionsJsonToSave)
                val firstStep = parsedSteps.firstOrNull()

                val actionToSave = firstStep?.action ?: rule.action
                val forwardPhoneToSave = firstStep?.forwardToPhone ?: rule.forwardToPhone
                val forwardUrlToSave = firstStep?.forwardToUrl ?: rule.forwardToUrl
                val appendSenderToSave = firstStep?.appendSenderToForward ?: rule.appendSenderToForward
                val replyTemplateToSave = firstStep?.replyTemplate ?: rule.replyTemplate

                val createdAtToSave = when {
                    rule.createdAt != 0L -> rule.createdAt
                    existing != null && existing.createdAt != 0L -> existing.createdAt
                    else -> System.currentTimeMillis()
                }

                val hitCountToSave = when {
                    rule.hitCount != 0L -> rule.hitCount
                    existing != null -> existing.hitCount
                    else -> 0L
                }

                realm.copyToRealmOrUpdate(
                    AutomationRule(
                        id = id,
                        name = rule.name,
                        enabled = rule.enabled,
                        matchSender = rule.matchSender,
                        matchSenderIsRegex = rule.matchSenderIsRegex,
                        matchSenderCaseSensitive = rule.matchSenderCaseSensitive,
                        matchBody = rule.matchBody,
                        matchBodyIsRegex = rule.matchBodyIsRegex,
                        matchBodyCaseSensitive = rule.matchBodyCaseSensitive,
                        skipContacts = rule.skipContacts,
                        actionsJson = actionsJsonToSave,
                        action = actionToSave,
                        forwardToPhone = forwardPhoneToSave,
                        forwardToUrl = forwardUrlToSave,
                        appendSenderToForward = appendSenderToSave,
                        replyTemplate = replyTemplateToSave,
                        replyRateLimitMs = rule.replyRateLimitMs,
                        delayMs = rule.delayMs,
                        createdAt = createdAtToSave,
                        hitCount = hitCountToSave
                    )
                )
            }
        }
    }

    override fun deleteRule(id: Long) {
        realmProvider().use { realm ->
            realm.executeTransaction {
                realm.where(AutomationRule::class.java)
                    .equalTo("id", id)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
    }

    override fun getEnabledRules(): List<AutomationRule> {
        return realmProvider().use { realm ->
            realm.where(AutomationRule::class.java)
                .equalTo("enabled", true)
                .findAll()
                .map { realm.copyFromRealm(it) }
        }
    }

    override fun incrementHitCount(id: Long) {
        realmProvider().use { realm ->
            realm.executeTransaction {
                val rule = realm.where(AutomationRule::class.java).equalTo("id", id).findFirst()
                rule?.hitCount = (rule?.hitCount ?: 0) + 1
            }
        }
    }
}
