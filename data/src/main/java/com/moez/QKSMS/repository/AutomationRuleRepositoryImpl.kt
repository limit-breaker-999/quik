package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.AutomationLog
import dev.octoshrimpy.quik.model.AutomationReplyState
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.FailedAutomationMessage
import dev.octoshrimpy.quik.model.OutboundSmsTracker
import io.realm.Realm
import io.realm.RealmResults
import io.realm.Sort
import timber.log.Timber
import javax.inject.Inject

class AutomationRuleRepositoryImpl(
    private val realmProvider: () -> Realm
) : AutomationRuleRepository {

    @Inject
    constructor() : this({ Realm.getDefaultInstance() })

    private var rulesRealm: Realm? = null
    private var failedMessagesRealm: Realm? = null
    private var logsRealm: Realm? = null

    override fun getRules(): RealmResults<AutomationRule> {
        val realm = rulesRealm?.takeIf { !it.isClosed } ?: realmProvider().also { rulesRealm = it }
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
        try {
            failedMessagesRealm?.takeIf { !it.isClosed }?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing failedMessages Realm instance")
        } finally {
            failedMessagesRealm = null
        }
        try {
            logsRealm?.takeIf { !it.isClosed }?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing logs Realm instance")
        } finally {
            logsRealm = null
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

    override fun tryAcquireForwardSlot(destination: String, rateLimitMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val normalizedDest = destination.filter { it.isDigit() || it == '+' }
        val key = "forward:${if (normalizedDest.isNotEmpty()) normalizedDest else destination.trim()}"
        return realmProvider().use { realm ->
            var acquired = false
            realm.executeTransaction { r ->
                val state = r.where(AutomationReplyState::class.java)
                    .equalTo("id", key)
                    .findFirst()

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

    override fun checkOutboundBudget(maxMessages: Int, windowMs: Long): Boolean {
        val cutoff = System.currentTimeMillis() - windowMs
        return realmProvider().use { realm ->
            val count = realm.where(OutboundSmsTracker::class.java)
                .greaterThanOrEqualTo("timestamp", cutoff)
                .count()
            count < maxMessages
        }
    }

    override fun recordOutboundSms(destination: String) {
        try {
            realmProvider().use { realm ->
                realm.executeTransaction { r ->
                    // Prune records older than 24 hours to keep the table compact
                    val pruneCutoff = System.currentTimeMillis() - 86400000L
                    r.where(OutboundSmsTracker::class.java)
                        .lessThan("timestamp", pruneCutoff)
                        .findAll()
                        .deleteAllFromRealm()

                    val id = (r.where(OutboundSmsTracker::class.java).max("id")?.toLong() ?: 0L) + 1L
                    val tracker = r.createObject(OutboundSmsTracker::class.java, id)
                    tracker.timestamp = System.currentTimeMillis()
                    tracker.destinationPhone = destination.trim()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to record outbound SMS in OutboundSmsTracker")
        }
    }

    override fun saveRule(rule: AutomationRule) {
        realmProvider().use { realm ->
            realm.refresh()
            realm.executeTransaction {
                // ID generation inside the transaction: atomic against
                // concurrent saves, and doesn't mutate the caller's object
                // (BUG-038/BUG-070)
                val id = if (rule.id <= 0L) {
                    (realm.where(AutomationRule::class.java).max("id")?.toLong() ?: 0L) + 1L
                } else {
                    rule.id
                }
                val existing = if (rule.id > 0L) {
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

                val priorityToSave = rule.priority
                val stopOnMatchToSave = rule.stopOnMatch
                val forwardRateLimitMsToSave = if (rule.forwardRateLimitMs > 0L) rule.forwardRateLimitMs else (existing?.forwardRateLimitMs ?: 60000L)

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
                        priority = priorityToSave,
                        stopOnMatch = stopOnMatchToSave,
                        forwardRateLimitMs = forwardRateLimitMsToSave,
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
                .sort("priority", Sort.DESCENDING)
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

    override fun getFailedMessages(): RealmResults<FailedAutomationMessage> {
        val realm = failedMessagesRealm?.takeIf { !it.isClosed } ?: realmProvider().also { failedMessagesRealm = it }
        return realm
            .where(FailedAutomationMessage::class.java)
            .sort("timestamp", Sort.DESCENDING)
            .findAllAsync()
    }

    override fun getFailedMessagesList(): List<FailedAutomationMessage> {
        return realmProvider().use { realm ->
            realm.where(FailedAutomationMessage::class.java)
                .sort("timestamp", Sort.DESCENDING)
                .findAll()
                .map { realm.copyFromRealm(it) }
        }
    }

    override fun saveFailedMessage(failedMessage: FailedAutomationMessage) {
        try {
            realmProvider().use { realm ->
                realm.refresh()
                realm.executeTransaction { r ->
                    val id = if (failedMessage.id <= 0L) {
                        (r.where(FailedAutomationMessage::class.java).max("id")?.toLong() ?: 0L) + 1L
                    } else {
                        failedMessage.id
                    }
                    val toSave = FailedAutomationMessage(
                        id = id,
                        messageId = failedMessage.messageId,
                        threadId = failedMessage.threadId,
                        subId = failedMessage.subId,
                        sender = failedMessage.sender,
                        body = failedMessage.body,
                        ruleId = failedMessage.ruleId,
                        ruleName = failedMessage.ruleName,
                        failedStepIndex = failedMessage.failedStepIndex,
                        failedActionType = failedMessage.failedActionType,
                        errorMessage = failedMessage.errorMessage,
                        timestamp = if (failedMessage.timestamp > 0L) failedMessage.timestamp else System.currentTimeMillis(),
                        canRetry = failedMessage.canRetry,
                        actionsJson = failedMessage.actionsJson
                    )
                    r.copyToRealmOrUpdate(toSave)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist failed automation message")
        }
    }

    override fun deleteFailedMessage(id: Long) {
        realmProvider().use { realm ->
            realm.executeTransaction { r ->
                r.where(FailedAutomationMessage::class.java)
                    .equalTo("id", id)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
    }

    override fun clearFailedMessages() {
        realmProvider().use { realm ->
            realm.executeTransaction { r ->
                r.where(FailedAutomationMessage::class.java)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
    }

    override fun getLogs(type: String?): RealmResults<AutomationLog> {
        val realm = logsRealm?.takeIf { !it.isClosed } ?: realmProvider().also { logsRealm = it }
        val query = realm.where(AutomationLog::class.java)
        if (!type.isNullOrBlank()) {
            query.equalTo("type", type)
        }
        return query
            .sort("timestamp", Sort.DESCENDING)
            .findAllAsync()
    }

    override fun getLogsList(type: String?): List<AutomationLog> {
        return realmProvider().use { realm ->
            val query = realm.where(AutomationLog::class.java)
            if (!type.isNullOrBlank()) {
                query.equalTo("type", type)
            }
            query.sort("timestamp", Sort.DESCENDING)
                .findAll()
                .map { realm.copyFromRealm(it) }
        }
    }

    override fun log(
        ruleId: Long,
        ruleName: String,
        messageId: Long,
        type: String,
        actionType: String,
        details: String
    ) {
        try {
            realmProvider().use { realm ->
                realm.refresh()
                realm.executeTransaction { r ->
                    val id = (r.where(AutomationLog::class.java).max("id")?.toLong() ?: 0L) + 1L
                    val logEntry = AutomationLog(
                        id = id,
                        ruleId = ruleId,
                        ruleName = ruleName,
                        messageId = messageId,
                        type = type,
                        actionType = actionType,
                        details = details,
                        timestamp = System.currentTimeMillis()
                    )
                    r.copyToRealmOrUpdate(logEntry)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist automation log")
        }
    }

    override fun clearLogs() {
        realmProvider().use { realm ->
            realm.executeTransaction { r ->
                r.where(AutomationLog::class.java)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
    }

    override fun pruneOldLogs(maxAgeDays: Int) {
        val cutoff = System.currentTimeMillis() - (maxAgeDays.toLong() * 24 * 60 * 60 * 1000)
        realmProvider().use { realm ->
            realm.executeTransaction { r ->
                r.where(AutomationLog::class.java)
                    .lessThan("timestamp", cutoff)
                    .findAll()
                    .deleteAllFromRealm()

                r.where(OutboundSmsTracker::class.java)
                    .lessThan("timestamp", cutoff)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
    }
}
