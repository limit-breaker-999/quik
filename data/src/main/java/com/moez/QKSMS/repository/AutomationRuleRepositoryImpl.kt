package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.AutomationReplyState
import dev.octoshrimpy.quik.model.AutomationRule
import io.realm.Realm
import io.realm.RealmResults
import javax.inject.Inject

class AutomationRuleRepositoryImpl @Inject constructor() : AutomationRuleRepository {

    override fun getRules(): RealmResults<AutomationRule> {
        return Realm.getDefaultInstance()
            .where(AutomationRule::class.java)
            .findAllAsync()
    }

    override fun getRule(id: Long): AutomationRule? {
        // Return an unmanaged copy: callers mutate the result (e.g. toggling
        // `enabled`) outside a Realm transaction, which throws on managed objects.
        return Realm.getDefaultInstance().use { realm ->
            realm.where(AutomationRule::class.java)
                .equalTo("id", id)
                .findFirst()
                ?.let { realm.copyFromRealm(it) }
        }
    }

    override fun tryAcquireReplySlot(ruleId: Long, threadId: Long, rateLimitMs: Long): Boolean {
        val now = System.currentTimeMillis()
        Realm.getDefaultInstance().use { realm ->
            val key = "$ruleId:$threadId"
            val state = realm.where(AutomationReplyState::class.java)
                .equalTo("id", key)
                .findFirst()

            // A limit of 0 means "always reply" (matches AutomationRule semantics)
            if (rateLimitMs > 0 && state != null && now - state.lastReplyAt < rateLimitMs) {
                return false
            }

            realm.executeTransaction {
                val s = state ?: it.createObject(AutomationReplyState::class.java, key)
                s.lastReplyAt = now
            }
            return true
        }
    }

    override fun saveRule(rule: AutomationRule) {
        Realm.getDefaultInstance().use { realm ->
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
                val toSave = realm.copyToRealmOrUpdate(
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
                        action = rule.action,
                        forwardToPhone = rule.forwardToPhone,
                        forwardToUrl = rule.forwardToUrl,
                        appendSenderToForward = rule.appendSenderToForward,
                        replyTemplate = rule.replyTemplate,
                        replyRateLimitMs = rule.replyRateLimitMs,
                        delayMs = rule.delayMs,
                        createdAt = rule.createdAt,
                        hitCount = rule.hitCount
                    )
                )
                if (toSave.createdAt == 0L) toSave.createdAt = System.currentTimeMillis()
            }
        }
    }

    override fun deleteRule(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                realm.where(AutomationRule::class.java)
                    .equalTo("id", id)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
    }

    override fun getEnabledRules(): List<AutomationRule> {
        return Realm.getDefaultInstance().use { realm ->
            realm.where(AutomationRule::class.java)
                .equalTo("enabled", true)
                .findAll()
                .map { realm.copyFromRealm(it) }
        }
    }

    override fun incrementHitCount(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                val rule = realm.where(AutomationRule::class.java).equalTo("id", id).findFirst()
                rule?.hitCount = (rule?.hitCount ?: 0) + 1
            }
        }
    }
}
