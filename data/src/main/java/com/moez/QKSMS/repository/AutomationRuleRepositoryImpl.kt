package dev.octoshrimpy.quik.repository

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
        return Realm.getDefaultInstance()
            .where(AutomationRule::class.java)
            .equalTo("id", id)
            .findFirst()
    }

    override fun saveRule(rule: AutomationRule) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            val id = if (rule.id == 0L) {
                (realm.where(AutomationRule::class.java).max("id")?.toLong() ?: -1L) + 1L
            } else {
                rule.id
            }
            realm.executeTransaction {
                val managed = realm.copyToRealmOrUpdate(rule.apply { this.id = id })
                if (managed.createdAt == 0L) managed.createdAt = System.currentTimeMillis()
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
