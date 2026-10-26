package dev.octoshrimpy.quik.feature.settings.automations

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.octoshrimpy.quik.feature.settings.automations.edit.EditAutomationPresenter
import dev.octoshrimpy.quik.feature.settings.automations.edit.EditAutomationState
import dev.octoshrimpy.quik.feature.settings.automations.edit.EditAutomationView
import dev.octoshrimpy.quik.interactor.ExecuteAutomations
import dev.octoshrimpy.quik.model.AutomationActionStep
import dev.octoshrimpy.quik.model.AutomationActionStepSerializer
import dev.octoshrimpy.quik.model.AutomationLog
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.AutomationStepHelper
import dev.octoshrimpy.quik.model.FailedAutomationMessage
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.ContactGroup
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.model.SearchResult
import dev.octoshrimpy.quik.repository.AutomationRuleRepository
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.realm.RealmResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.util.UUID
import dev.octoshrimpy.quik.worker.AutomationActionWorker
import dev.octoshrimpy.quik.repository.AutomationRuleRepositoryImpl

class AutomationPipelineE2ETest {

    // ── Fake Repositories ───────────────────────────────────────────────

    open class FakeAutomationRuleRepo : AutomationRuleRepository {
        val rules = mutableListOf<AutomationRule>()
        val hitCounts = mutableMapOf<Long, Long>()
        var saveCount: Int = 0
        var throwOnSave: Boolean = false
        var throwOnDelete: Boolean = false

        @Suppress("UNCHECKED_CAST")
        override fun getRules(): RealmResults<AutomationRule> =
            Mockito.mock(RealmResults::class.java) as RealmResults<AutomationRule>
        override fun getRule(id: Long): AutomationRule? = rules.find { it.id == id }
        override fun saveRule(rule: AutomationRule) {
            if (throwOnSave) {
                throw RuntimeException("Simulated Realm write failure")
            }
            saveCount++
            rules.removeAll { it.id == rule.id && rule.id != 0L }
            rules.add(rule)
        }
        override fun deleteRule(id: Long) {
            if (throwOnDelete) {
                throw RuntimeException("Simulated Realm delete failure")
            }
            rules.removeAll { it.id == id }
        }
        override fun getEnabledRules(): List<AutomationRule> = rules.filter { it.enabled }
        override fun tryAcquireReplySlot(ruleId: Long, threadId: Long, rateLimitMs: Long): Boolean = true
        override fun incrementHitCount(id: Long) {
            hitCounts[id] = (hitCounts[id] ?: 0L) + 1L
        }

        val failedMessages = mutableListOf<FailedAutomationMessage>()
        val logs = mutableListOf<AutomationLog>()
        var nextFailedId = 1L
        var nextLogId = 1L

        override fun getFailedMessagesList(): List<FailedAutomationMessage> = failedMessages.toList()

        override fun saveFailedMessage(failedMessage: FailedAutomationMessage) {
            if (failedMessage.id == 0L) {
                failedMessage.id = nextFailedId++
            }
            failedMessages.removeAll { it.id == failedMessage.id }
            failedMessages.add(failedMessage)
        }

        override fun deleteFailedMessage(id: Long) {
            failedMessages.removeAll { it.id == id }
        }

        override fun clearFailedMessages() {
            failedMessages.clear()
        }

        override fun getLogsList(type: String?): List<AutomationLog> {
            return if (type == null || type.isBlank() || type == "ALL") {
                logs.toList()
            } else {
                logs.filter { it.type.equals(type, ignoreCase = true) }
            }
        }

        override fun log(ruleId: Long, ruleName: String, messageId: Long, type: String, actionType: String, details: String) {
            logs.add(
                AutomationLog(
                    id = nextLogId++,
                    ruleId = ruleId,
                    ruleName = ruleName,
                    messageId = messageId,
                    type = type,
                    actionType = actionType,
                    details = details,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        override fun clearLogs() {
            logs.clear()
        }

        override fun pruneOldLogs(maxAgeDays: Int) {
            val cutoff = System.currentTimeMillis() - (maxAgeDays.toLong() * 24 * 60 * 60 * 1000L)
            logs.removeAll { it.timestamp < cutoff }
        }

        override fun close() {}
    }

    class FakeContactRepo : ContactRepository {
        val contacts = mutableSetOf<String>()
        override fun isContact(address: String): Boolean = contacts.contains(address)
        override fun findContactUri(address: String) = throw UnsupportedOperationException()
        override fun getContacts(): RealmResults<Contact> = throw UnsupportedOperationException()
        override fun getUnmanagedContact(lookupKey: String) = null
        override fun getUnmanagedAllContacts() = emptyList<Contact>()
        override fun getUnmanagedContacts(starred: Boolean) = Observable.just(emptyList<Contact>())
        override fun getUnmanagedContactGroups() = Observable.just(emptyList<ContactGroup>())
        override fun setDefaultPhoneNumber(lookupKey: String, phoneNumberId: Long) {}
    }

    class FakeMessageRepo : MessageRepository {
        val deletedMessageIds = mutableListOf<Long>()
        val markedReadThreadIds = mutableListOf<Long>()

        override val deduplicationProgress: Observable<MessageRepository.DeduplicationProgress>
            get() = Observable.empty()

        override fun deleteMessages(messageIds: Collection<Long>) {
            deletedMessageIds.addAll(messageIds)
        }

        override fun markRead(threadIds: Collection<Long>): Int {
            markedReadThreadIds.addAll(threadIds)
            return threadIds.size
        }

        override fun getMessages(threadId: Long, query: String) = throw UnsupportedOperationException()
        override fun getMessagesSync(threadId: Long, query: String) = throw UnsupportedOperationException()
        override fun getMessage(messageId: Long) = null
        override fun getUnmanagedMessage(messageId: Long) = null
        override fun getMessages(messageIds: Collection<Long>) = throw UnsupportedOperationException()
        override fun getMessageForPart(id: Long) = null
        override fun getLastIncomingMessage(threadId: Long) = throw UnsupportedOperationException()
        override fun getUnreadCount() = 0L
        override fun getPart(id: Long) = null
        override fun getPartsForConversation(threadId: Long) = throw UnsupportedOperationException()
        override fun savePart(id: Long) = null
        override fun getUnreadUnseenMessages(threadId: Long) = throw UnsupportedOperationException()
        override fun getUnreadMessages(threadId: Long) = throw UnsupportedOperationException()
        override fun markAllSeen() = 0
        override fun markSeen(threadIds: Collection<Long>) = 0
        override fun markUnread(threadIds: Collection<Long>) = 0
        override fun markSending(messageId: Long) {}
        override fun markSent(messageId: Long) {}
        override fun markFailed(messageId: Long, resultCode: Int) = false
        override fun markDelivered(messageId: Long) {}
        override fun markDeliveryFailed(messageId: Long, resultCode: Int) {}
        val sentMessages = mutableListOf<Triple<Collection<String>, String, Int>>()
        var failSend: Boolean = false
        var throwOnSend: Exception? = null

        override fun sendNewMessages(subId: Int, toAddresses: Collection<String>, body: String, attachments: Collection<dev.octoshrimpy.quik.model.Attachment>, sendAsGroup: Boolean, delayMs: Int): Collection<Message> {
            throwOnSend?.let { throw it }
            if (failSend) {
                return emptyList()
            }
            sentMessages.add(Triple(toAddresses, body, subId))
            return listOf(Message().apply { this.id = 1234L; this.body = body })
        }
        override fun sendMessage(message: Message) = emptyList<Message>()
        override fun sendMessage(messageId: Long) = emptyList<Message>()
        override fun cancelDelayedSmsAlarm(messageId: Long) {}
        override fun insertReceivedSms(subId: Int, address: String, body: String, sentTime: Long) = throw UnsupportedOperationException()
        override fun getOldMessageCounts(maxAgeDays: Int) = emptyMap<Long, Int>()
        override fun deleteOldMessages(maxAgeDays: Int) {}
        override fun deduplicateMessages() = io.reactivex.Flowable.empty<MessageRepository.DeduplicationResult>()
        override fun markAsSendingNow(messageId: Long) {}
    }

    class FakeConvRepo : ConversationRepository {
        val archivedThreadIds = mutableListOf<Long>()

        override fun markArchived(vararg threadIds: Long) {
            archivedThreadIds.addAll(threadIds.toList())
        }

        override fun getConversations(unreadAtTop: Boolean, archived: Boolean) = throw UnsupportedOperationException()
        override fun getConversationsSnapshot(unreadAtTop: Boolean) = emptyList<Conversation>()
        override fun getTopConversations() = emptyList<Conversation>()
        override fun setConversationName(id: Long, name: String) = io.reactivex.Completable.complete()
        override fun searchConversations(query: CharSequence) = emptyList<SearchResult>()
        override fun getBlockedConversations() = throw UnsupportedOperationException()
        override fun getBlockedConversationsAsync() = throw UnsupportedOperationException()
        override fun getConversationAsync(threadId: Long) = throw UnsupportedOperationException()
        override fun getConversation(threadId: Long) = null
        override fun updateSendAsGroup(threadId: Long, sendAsGroup: Boolean): Unit? = Unit
        override fun getUnseenIds(archived: Boolean) = emptyList<Long>()
        override fun getUnreadIds(archived: Boolean) = emptyList<Long>()
        override fun getConversationAndLastSenderContactName(threadId: Long) = null
        override fun getConversations(vararg threadIds: Long) = throw UnsupportedOperationException()
        override fun getUnmanagedConversations() = Observable.just(emptyList<Conversation>())
        override fun getRecipients() = throw UnsupportedOperationException()
        override fun getUnmanagedRecipients() = Observable.just(emptyList<Recipient>())
        override fun getRecipient(recipientId: Long) = null
        override fun getOrCreateConversation(threadId: Long, sendAsGroup: Boolean) = null
        override fun createConversation(threadId: Long, sendAsGroup: Boolean) = null
        override fun getConversation(addresses: Collection<String>) = null
        override fun getOrCreateConversation(addresses: Collection<String>, sendAsGroup: Boolean) = null
        override fun createConversation(addresses: Collection<String>, sendAsGroup: Boolean) = null
        override fun saveDraft(threadId: Long, draft: String) {}
        override fun updateConversations(threadIds: Collection<Long>) {}
        override fun markUnarchived(threadIds: Collection<Long>) {}
        override fun markPinned(vararg threadIds: Long) {}
        override fun markUnpinned(vararg threadIds: Long) {}
        override fun markBlocked(threadIds: Collection<Long>, blockingClient: Int, blockReason: String?) {}
        override fun markUnblocked(vararg threadIds: Long) {}
        override fun deleteConversations(vararg threadIds: Long) {}
    }

    // ── Test View for Presenter Testing ─────────────────────────────────

    class TestEditAutomationView : LifecycleOwner, EditAutomationView {
        private val lifecycleRegistry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = lifecycleRegistry

        val nameSubject = PublishSubject.create<String>()
        val matchSenderSubject = PublishSubject.create<String>()
        val matchSenderRegexSubject = PublishSubject.create<Boolean>()
        val matchSenderCaseSubject = PublishSubject.create<Boolean>()
        val matchBodySubject = PublishSubject.create<String>()
        val matchBodyRegexSubject = PublishSubject.create<Boolean>()
        val matchBodyCaseSubject = PublishSubject.create<Boolean>()
        val skipContactsSubject = PublishSubject.create<Boolean>()
        val presetClicksSubject = PublishSubject.create<Unit>()
        val presetSelectedSubject = PublishSubject.create<Pair<String, Boolean>>()
        val delaySubject = PublishSubject.create<Long>()
        val addStepSubject = PublishSubject.create<Unit>()
        val removeStepSubject = PublishSubject.create<Int>()
        val moveUpSubject = PublishSubject.create<Int>()
        val moveDownSubject = PublishSubject.create<Int>()
        val stepUpdatedSubject = PublishSubject.create<Pair<Int, AutomationActionStep>>()
        val saveClicksSubject = PublishSubject.create<Unit>()

        var closed = false
        var lastValidationError: String? = null

        override fun nameChanges(): Observable<String> = nameSubject
        override fun matchSenderChanges(): Observable<String> = matchSenderSubject
        override fun matchSenderRegexToggle(): Observable<Boolean> = matchSenderRegexSubject
        override fun matchSenderCaseSensitiveToggle(): Observable<Boolean> = matchSenderCaseSubject
        override fun matchBodyChanges(): Observable<String> = matchBodySubject
        override fun matchBodyRegexToggle(): Observable<Boolean> = matchBodyRegexSubject
        override fun matchBodyCaseSensitiveToggle(): Observable<Boolean> = matchBodyCaseSubject
        override fun skipContactsToggle(): Observable<Boolean> = skipContactsSubject
        override fun presetClicks(): Observable<*> = presetClicksSubject
        override fun presetSelected(): Observable<Pair<String, Boolean>> = presetSelectedSubject
        override fun delaySelected(): Observable<Long> = delaySubject
        override fun addStepClicks(): Observable<*> = addStepSubject
        override fun removeStepClicks(): Observable<Int> = removeStepSubject
        override fun moveStepUpClicks(): Observable<Int> = moveUpSubject
        override fun moveStepDownClicks(): Observable<Int> = moveDownSubject
        override fun stepUpdated(): Observable<Pair<Int, AutomationActionStep>> = stepUpdatedSubject
        override fun saveClicks(): Observable<*> = saveClicksSubject

        override fun showPresets(presets: List<Pair<String, String>>) {}
        override fun showValidationError(error: String) { lastValidationError = error }
        override fun close() { closed = true }
        override fun render(state: EditAutomationState) {}
    }

    @org.junit.Before
    fun setUp() {
        io.reactivex.android.plugins.RxAndroidPlugins.setInitMainThreadSchedulerHandler { io.reactivex.schedulers.Schedulers.trampoline() }
        io.reactivex.plugins.RxJavaPlugins.setIoSchedulerHandler { io.reactivex.schedulers.Schedulers.trampoline() }
        com.uber.autodispose.android.AutoDisposeAndroidPlugins.setOnCheckMainThread { true }
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
    }

    @org.junit.After
    fun tearDown() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
        com.uber.autodispose.android.AutoDisposeAndroidPlugins.reset()
        io.reactivex.plugins.RxJavaPlugins.reset()
        io.reactivex.android.plugins.RxAndroidPlugins.reset()
    }

    private fun dummyContext(): Context {
        return object : android.content.ContextWrapper(null) {}
    }

    private fun createMessage(id: Long, threadId: Long, address: String, body: String): Message {
        return Message().apply {
            this.id = id
            this.threadId = threadId
            this.address = address
            this.body = body
            this.type = Message.TYPE_SMS
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 1. Atomic Preset Selection Produces 1 State Emission Without Loop
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testAtomicPresetSelection_emitsSingleStateWithoutLoop() {
        val repo = FakeAutomationRuleRepo()
        val presenter = EditAutomationPresenter(repo)
        val view = TestEditAutomationView()

        val stateObserver = presenter.stateObservable.test()
        presenter.bindIntents(view)

        // Initial state emission
        assertEquals(1, stateObserver.valueCount())
        val initial = stateObserver.values().last()
        assertEquals("", initial.matchBody)
        assertFalse(initial.matchBodyIsRegex)

        // Select preset atomically: pattern + isRegex=true
        val presetPattern = "(?i)\\b\\d{4,8}\\b"
        view.presetSelectedSubject.onNext(presetPattern to true)

        // Exactly 1 new state is emitted (total 2)
        assertEquals(2, stateObserver.valueCount())
        val updated = stateObserver.values().last()
        assertEquals(presetPattern, updated.matchBody)
        assertTrue(updated.matchBodyIsRegex)

        // User manually toggles regex switch off
        view.matchBodyRegexSubject.onNext(false)

        // Exactly 1 new state is emitted (total 3), pattern is preserved
        assertEquals(3, stateObserver.valueCount())
        val toggledOff = stateObserver.values().last()
        assertEquals(presetPattern, toggledOff.matchBody)
        assertFalse(toggledOff.matchBodyIsRegex)

        // User toggles regex switch back on
        view.matchBodyRegexSubject.onNext(true)
        assertEquals(4, stateObserver.valueCount())
        val toggledOn = stateObserver.values().last()
        assertEquals(presetPattern, toggledOn.matchBody)
        assertTrue(toggledOn.matchBodyIsRegex)

        // No runaway ping-pong loops
        assertEquals(4, stateObserver.valueCount())
    }

    // ────────────────────────────────────────────────────────────────────
    // 2. Multi-Action Sequential Execution (FORWARD_SMS -> MARK_READ -> DELETE)
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testMultiActionSequentialExecution_forwardSmsThenMarkReadThenDelete() {
        val steps = listOf(
            AutomationActionStep(
                action = AutomationRule.ACTION_FORWARD_SMS,
                forwardToPhone = "+919876543210",
                appendSenderToForward = true
            ),
            AutomationActionStep(
                action = AutomationRule.ACTION_MARK_READ
            ),
            AutomationActionStep(
                action = AutomationRule.ACTION_DELETE
            )
        )

        // Verify JSON roundtrip
        val json = AutomationActionStepSerializer.toJson(steps)
        val restored = AutomationActionStepSerializer.fromJson(json)
        assertEquals(3, restored.size)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, restored[0].action)
        assertEquals("+919876543210", restored[0].forwardToPhone)
        assertTrue(restored[0].appendSenderToForward)
        assertEquals(AutomationRule.ACTION_MARK_READ, restored[1].action)
        assertEquals(AutomationRule.ACTION_DELETE, restored[2].action)

        // Verify on AutomationRule
        val rule = AutomationRule(
            id = 50L,
            name = "Forward then Mark Read and Delete",
            enabled = true,
            matchBody = "Confidential OTP",
            delayMs = 0L
        )
        rule.setActionSteps(steps)

        assertEquals(AutomationRule.ACTION_FORWARD_SMS, rule.action)
        assertEquals("+919876543210", rule.forwardToPhone)
        val ruleSteps = rule.getActionSteps()
        assertEquals(3, ruleSteps.size)
        assertEquals(AutomationRule.ACTION_DELETE, ruleSteps.last().action)
    }

    // ────────────────────────────────────────────────────────────────────
    // 3. Terminal DELETE Invariant Enforcement
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testTerminalDeleteInvariantEnforcement() {
        val initial = listOf(
            AutomationActionStep(action = AutomationRule.ACTION_MARK_READ),
            AutomationActionStep(action = AutomationRule.ACTION_DELETE)
        )

        // 1. Adding a step when DELETE is terminal inserts the new step BEFORE DELETE
        val afterAdd = AutomationStepHelper.addStep(initial)
        assertEquals(3, afterAdd.size)
        assertEquals(AutomationRule.ACTION_MARK_READ, afterAdd[0].action)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, afterAdd[1].action)
        assertEquals(AutomationRule.ACTION_DELETE, afterAdd[2].action)

        // 2. Attempting to move DELETE up is blocked (returns unchanged)
        val attemptedMoveDeleteUp = AutomationStepHelper.moveUp(afterAdd, 2)
        assertEquals(afterAdd, attemptedMoveDeleteUp)
        assertEquals(AutomationRule.ACTION_DELETE, attemptedMoveDeleteUp.last().action)

        // 3. Attempting to move preceding action down past DELETE is blocked
        val attemptedMoveDownPastDelete = AutomationStepHelper.moveDown(afterAdd, 1)
        assertEquals(afterAdd, attemptedMoveDownPastDelete)
        assertEquals(AutomationRule.ACTION_DELETE, attemptedMoveDownPastDelete.last().action)

        // 4. Moving non-delete actions among each other succeeds
        val movedStep1Up = AutomationStepHelper.moveUp(afterAdd, 1)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, movedStep1Up[0].action)
        assertEquals(AutomationRule.ACTION_MARK_READ, movedStep1Up[1].action)
        assertEquals(AutomationRule.ACTION_DELETE, movedStep1Up[2].action)

        // 5. Duplicate action types are prohibited: each action type can appear at most once
        var fullChain = listOf(AutomationActionStep(action = AutomationRule.ACTION_DELETE))
        for (i in 1..6) {
            fullChain = AutomationStepHelper.addStep(fullChain)
        }
        assertEquals(6, fullChain.size)
        // All 6 actions distinct
        assertEquals(6, fullChain.map { it.action }.toSet().size)
        // Adding again when all 6 exist returns unchanged
        val noMoreAdd = AutomationStepHelper.addStep(fullChain)
        assertEquals(6, noMoreAdd.size)

        // 6. EnforceDeleteTerminal always puts DELETE at the end
        val scrambled = listOf(
            AutomationActionStep(action = AutomationRule.ACTION_DELETE),
            AutomationActionStep(action = AutomationRule.ACTION_FORWARD_SMS),
            AutomationActionStep(action = AutomationRule.ACTION_ARCHIVE)
        )
        val fixed = AutomationStepHelper.enforceDeleteTerminal(scrambled)
        assertEquals(3, fixed.size)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, fixed[0].action)
        assertEquals(AutomationRule.ACTION_ARCHIVE, fixed[1].action)
        // 7. Attempting to move DELETE down is blocked
        val attemptedMoveDeleteDown = AutomationStepHelper.moveDown(afterAdd, 2)
        assertEquals(afterAdd, attemptedMoveDeleteDown)

        // 8. addStep when DELETE is first correctly inserts before DELETE and keeps DELETE terminal
        val deleteFirst = listOf(AutomationActionStep(action = AutomationRule.ACTION_DELETE))
        val addWithDeleteFirst = AutomationStepHelper.addStep(deleteFirst)
        assertEquals(2, addWithDeleteFirst.size)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, addWithDeleteFirst[0].action)
        assertEquals(AutomationRule.ACTION_DELETE, addWithDeleteFirst[1].action)
    }

    // ────────────────────────────────────────────────────────────────────
    // 4. Instant Notification Suppression (delayMs == 0)
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testInstantNotificationSuppression_delay0VsTimedDelay() {
        val autoRepo = FakeAutomationRuleRepo()
        val contactsRepo = FakeContactRepo()
        val messageRepo = FakeMessageRepo()
        val convRepo = FakeConvRepo()

        val interactor = ExecuteAutomations(dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo)

        // Rule with delayMs = 0 containing DELETE: MUST return Dropped (notification & vibrate suppressed)
        val instantDeleteRule = AutomationRule(
            id = 1L,
            name = "Instant OTP Suppress & Delete",
            enabled = true,
            matchBody = "OTP",
            delayMs = 0L
        ).apply {
            setActionSteps(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_MARK_READ),
                AutomationActionStep(action = AutomationRule.ACTION_DELETE)
            ))
        }

        autoRepo.rules.clear()
        autoRepo.rules.add(instantDeleteRule)

        val message1 = createMessage(101L, 301L, "+919876543210", "Your secret OTP is 123456")
        val result1 = interactor.execute(message1)

        assertTrue("delayMs=0 with DELETE must return Dropped to suppress notifications",
            result1 is ExecuteAutomations.ExecutionResult.Dropped)
        assertEquals(listOf(101L), messageRepo.deletedMessageIds)
        assertEquals(listOf(301L), messageRepo.markedReadThreadIds)

        // Rule with delayMs = 0 containing ARCHIVE: MUST return Dropped (notification & vibrate suppressed)
        val instantArchiveRule = AutomationRule(
            id = 3L,
            name = "Instant OTP Suppress & Archive",
            enabled = true,
            matchBody = "ArchiveMe",
            delayMs = 0L
        ).apply {
            setActionSteps(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_ARCHIVE)
            ))
        }

        autoRepo.rules.clear()
        autoRepo.rules.add(instantArchiveRule)

        val messageArchive = createMessage(103L, 303L, "+919876543210", "ArchiveMe: Statement ready")
        val resultArchive = interactor.execute(messageArchive)

        assertTrue("delayMs=0 with ARCHIVE must set isArchived=true to suppress notifications in NotificationManager",
            resultArchive.isArchived)
        assertTrue("ARCHIVE preserves conversation in database rather than dropping message object",
            resultArchive is ExecuteAutomations.ExecutionResult.Normal)
        assertEquals(listOf(303L), convRepo.archivedThreadIds)

        // Rule with delayMs = 30_000L containing DELETE: MUST return Normal so notification initially displays
        val timedDeleteRule = AutomationRule(
            id = 2L,
            name = "Delayed Delete",
            enabled = true,
            matchBody = "BankAlert",
            delayMs = 30_000L
        ).apply {
            setActionSteps(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_DELETE)
            ))
        }

        autoRepo.rules.clear()
        autoRepo.rules.add(timedDeleteRule)

        val message2 = createMessage(102L, 302L, "+919876543210", "BankAlert: Account debited")
        val result2 = interactor.execute(message2)

        assertTrue("delayMs > 0 must return Normal so user receives notification before delayed action fires",
            result2 is ExecuteAutomations.ExecutionResult.Normal)
    }

    // ────────────────────────────────────────────────────────────────────
    // 5. Phone Number Normalization (+91 vs Local) & Alphanumeric Headers
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testPhoneNumberNormalization_countryCodesAndAlphanumeric() {
        // Full +91 format vs local 10-digit number
        assertTrue(ExecuteAutomations.comparePhoneNumbers("+919876543210", "9876543210"))
        assertTrue(ExecuteAutomations.comparePhoneNumbers("9876543210", "+919876543210"))

        // Leading 0 (domestic trunk) vs 10-digit number
        assertTrue(ExecuteAutomations.comparePhoneNumbers("09876543210", "9876543210"))
        assertTrue(ExecuteAutomations.comparePhoneNumbers("9876543210", "09876543210"))

        // Formatted with spaces and hyphens
        assertTrue(ExecuteAutomations.comparePhoneNumbers("+91 98765-43210", "9876543210"))
        assertTrue(ExecuteAutomations.comparePhoneNumbers("+1 (555) 234-5678", "5552345678"))

        // Unrelated numbers must not match
        assertFalse(ExecuteAutomations.comparePhoneNumbers("+919876543210", "+911234567890"))
        assertFalse(ExecuteAutomations.comparePhoneNumbers("9876543210", "1234567890"))

        // End-to-end matching in ExecuteAutomations
        val autoRepo = FakeAutomationRuleRepo()
        val contactsRepo = FakeContactRepo()
        val messageRepo = FakeMessageRepo()
        val convRepo = FakeConvRepo()

        val interactor = ExecuteAutomations(dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo)

        // Rule configured with local number 9876543210
        val ruleLocal = AutomationRule(
            id = 10L,
            name = "Local Number Rule",
            enabled = true,
            matchSender = "9876543210",
            action = AutomationRule.ACTION_MARK_READ
        )
        autoRepo.rules.clear()
        autoRepo.rules.add(ruleLocal)

        // Incoming message arrives with carrier country code +91
        val msgWithCountryCode = createMessage(1L, 10L, "+919876543210", "Hello from India")
        interactor.execute(msgWithCountryCode)
        assertEquals(1L, autoRepo.hitCounts[10L])

        // Rule configured with alphanumeric header "VK-HDFCBK"
        val ruleAlpha = AutomationRule(
            id = 20L,
            name = "Bank Header Rule",
            enabled = true,
            matchSender = "HDFCBK",
            action = AutomationRule.ACTION_MARK_READ
        )
        autoRepo.rules.clear()
        autoRepo.rules.add(ruleAlpha)

        val msgFromBank = createMessage(2L, 20L, "VK-HDFCBK", "INR 500 debited")
        interactor.execute(msgFromBank)
        assertEquals(1L, autoRepo.hitCounts[20L])
    }

    // ────────────────────────────────────────────────────────────────────
    // 6. Preservation of actionsJson in AutomationRuleRepositoryImpl
    // ────────────────────────────────────────────────────────────────────
    @Suppress("UNCHECKED_CAST")
    @Test
    fun testActionsJsonPreservedOnSaveRuleAndToggleInRepository() {
        val mockRealm = Mockito.mock(io.realm.Realm::class.java)
        val captor = ArgumentCaptor.forClass(AutomationRule::class.java)
        var currentStoredRule: AutomationRule? = null

        Mockito.doAnswer { invocation ->
            val transaction = invocation.arguments[0] as io.realm.Realm.Transaction
            transaction.execute(mockRealm)
            null
        }.`when`(mockRealm).executeTransaction(Mockito.any())

        val query = Mockito.mock(io.realm.RealmQuery::class.java) as io.realm.RealmQuery<AutomationRule>
        Mockito.`when`(mockRealm.where(AutomationRule::class.java)).thenReturn(query)
        Mockito.`when`(query.equalTo("id", 42L)).thenReturn(query)
        Mockito.`when`(query.findFirst()).thenAnswer { currentStoredRule }

        Mockito.`when`(mockRealm.copyToRealmOrUpdate(captor.capture())).thenAnswer {
            val saved = it.arguments[0] as AutomationRule
            currentStoredRule = saved
            saved
        }

        val repo = AutomationRuleRepositoryImpl { mockRealm }

        val initialSteps = listOf(
            AutomationActionStep(action = AutomationRule.ACTION_FORWARD_SMS, forwardToPhone = "+1234567890"),
            AutomationActionStep(action = AutomationRule.ACTION_DELETE)
        )
        val initialJson = AutomationActionStepSerializer.toJson(initialSteps)
        val rule = AutomationRule(
            id = 42L,
            name = "Forward & Delete",
            enabled = true,
            actionsJson = initialJson,
            createdAt = 1600000000000L,
            hitCount = 42L
        )

        // 1. Verify actionsJson, createdAt, and hitCount are preserved and written to Realm on saveRule
        repo.saveRule(rule)
        val saved1 = captor.value
        assertEquals("actionsJson must be preserved on saveRule()", initialJson, saved1.actionsJson)
        assertEquals(1600000000000L, saved1.createdAt)
        assertEquals(42L, saved1.hitCount)

        // 2. Verify toggle (simulating AutomationsPresenter toggle rule click)
        Mockito.`when`(mockRealm.copyFromRealm(saved1)).thenReturn(saved1)
        val retrieved = repo.getRule(42L)
        assertNotNull(retrieved)
        assertEquals(initialJson, retrieved!!.actionsJson)

        retrieved.enabled = !retrieved.enabled
        repo.saveRule(retrieved)

        val saved2 = captor.allValues.last()
        assertEquals("actionsJson must NOT be wiped out on toggle", initialJson, saved2.actionsJson)
        assertEquals(1600000000000L, saved2.createdAt)
        assertEquals(42L, saved2.hitCount)
        assertFalse(saved2.enabled)

        // 3. Fallback recovery: if caller passes empty actionsJson, createdAt=0, hitCount=0 on update, recover from existing Realm object
        val blankJsonRule = AutomationRule(id = 42L, name = "Blank JSON Rule", actionsJson = "", createdAt = 0L, hitCount = 0L)
        repo.saveRule(blankJsonRule)
        val saved3 = captor.allValues.last()
        assertEquals("actionsJson must be recovered from existing Realm object if blank on update", initialJson, saved3.actionsJson)
        assertEquals("createdAt must be preserved from existing Realm object", 1600000000000L, saved3.createdAt)
        assertEquals("hitCount must be preserved from existing Realm object", 42L, saved3.hitCount)
    }

    // ────────────────────────────────────────────────────────────────────
    // 7. Step-Level Retry Idempotency in AutomationActionWorker
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testStepLevelRetryIdempotencySkipsCompletedForwardActions() {
        val workId = UUID.randomUUID().toString()
        val mockContext = dummyContext()
        val mockMessageRepo = Mockito.mock(MessageRepository::class.java)
        val mockConvRepo = Mockito.mock(ConversationRepository::class.java)
        val mockNotificationManager = Mockito.mock(dev.octoshrimpy.quik.manager.NotificationManager::class.java)

        val steps = listOf(
            AutomationActionStep(action = AutomationRule.ACTION_FORWARD_SMS, forwardToPhone = "+15551234567"),
            AutomationActionStep(action = AutomationRule.ACTION_DELETE)
        )
        val actionsJson = AutomationActionStepSerializer.toJson(steps)
        val inputData = androidx.work.Data.Builder()
            .putString(AutomationActionWorker.KEY_ACTIONS_JSON, actionsJson)
            .putLong(AutomationActionWorker.KEY_MESSAGE_ID, 999L)
            .putLong(AutomationActionWorker.KEY_THREAD_ID, 888L)
            .putString(AutomationActionWorker.KEY_SENDER_ADDRESS, "12345")
            .putString(AutomationActionWorker.KEY_MESSAGE_BODY, "Hello world")
            .build()

        val workerParams = org.objenesis.ObjenesisStd().newInstance(androidx.work.WorkerParameters::class.java)
        androidx.work.WorkerParameters::class.java.getDeclaredField("mId").apply {
            isAccessible = true
            set(workerParams, UUID.fromString(workId))
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mInputData").apply {
            isAccessible = true
            set(workerParams, inputData)
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mRunAttemptCount").apply {
            isAccessible = true
            set(workerParams, 1) // retry attempt
        }

        // Simulate that Step 0 (FORWARD_SMS) already completed on previous attempt
        AutomationActionWorker.clearLedger()
        AutomationActionWorker.markStepCompleted(workId, 0, mockContext)

        val worker = AutomationActionWorker(mockContext, workerParams)
        worker.messageRepo = mockMessageRepo
        worker.conversationRepo = mockConvRepo
        worker.notificationManager = mockNotificationManager

        val result = worker.doWork()

        // Verify Step 0 was skipped: sendNewMessages was NOT re-executed on retry
        // Step 1 (DELETE) was executed, and no other messageRepo calls occurred
        Mockito.verify(mockMessageRepo).deleteMessages(listOf(999L))
        Mockito.verifyNoMoreInteractions(mockMessageRepo)
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)

        // Verify ledger was cleared on success
        assertTrue(AutomationActionWorker.getCompletedSteps(workId, mockContext).isEmpty())
    }

    @Test
    fun testWorkerHaltsAndRetriesWhenStopped() {
        val workId = UUID.randomUUID().toString()
        val mockContext = dummyContext()
        val mockMessageRepo = Mockito.mock(MessageRepository::class.java)
        val mockConvRepo = Mockito.mock(ConversationRepository::class.java)
        val mockNotificationManager = Mockito.mock(dev.octoshrimpy.quik.manager.NotificationManager::class.java)

        val steps = listOf(
            AutomationActionStep(action = AutomationRule.ACTION_DELETE)
        )
        val actionsJson = AutomationActionStepSerializer.toJson(steps)
        val inputData = androidx.work.Data.Builder()
            .putString(AutomationActionWorker.KEY_ACTIONS_JSON, actionsJson)
            .putLong(AutomationActionWorker.KEY_MESSAGE_ID, 999L)
            .putLong(AutomationActionWorker.KEY_THREAD_ID, 888L)
            .build()

        val workerParams = org.objenesis.ObjenesisStd().newInstance(androidx.work.WorkerParameters::class.java)
        androidx.work.WorkerParameters::class.java.getDeclaredField("mId").apply {
            isAccessible = true
            set(workerParams, UUID.fromString(workId))
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mInputData").apply {
            isAccessible = true
            set(workerParams, inputData)
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mRunAttemptCount").apply {
            isAccessible = true
            set(workerParams, 0)
        }

        val worker = AutomationActionWorker(mockContext, workerParams)
        worker.messageRepo = mockMessageRepo
        worker.conversationRepo = mockConvRepo
        worker.notificationManager = mockNotificationManager

        // Signal stop
        worker.stop()

        val result = worker.doWork()

        assertEquals("When stopped, worker must return Result.retry()",
            androidx.work.ListenableWorker.Result.retry(), result)
        // Verify deleteMessages was NOT executed
        Mockito.verifyNoMoreInteractions(mockMessageRepo)
    }

    // ────────────────────────────────────────────────────────────────────
    // 8. Pure Local Delete vs Async Forward + Delete Visibility Invariant
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testPureLocalDeleteDropsMessageWhileAsyncForwardKeepsMessageVisible() {
        val autoRepo = FakeAutomationRuleRepo()
        val contactsRepo = FakeContactRepo()
        val messageRepo = FakeMessageRepo()
        val convRepo = FakeConvRepo()

        val interactor = ExecuteAutomations(dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo)

        // Case A: Pure local delete rule (no async steps)
        val pureLocalDeleteRule = AutomationRule(
            id = 100L,
            name = "Pure Local Delete",
            enabled = true,
            matchBody = "SpamLocal",
            delayMs = 0L
        ).apply {
            setActionSteps(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_DELETE)
            ))
        }
        autoRepo.rules.clear()
        autoRepo.rules.add(pureLocalDeleteRule)

        val msgLocal = createMessage(1001L, 501L, "12345", "SpamLocal message")
        val resultLocal = interactor.execute(msgLocal)

        // Must be Dropped immediately so notification and UI are suppressed
        assertTrue("Pure local delete must drop message immediately",
            resultLocal is ExecuteAutomations.ExecutionResult.Dropped)
        assertEquals(listOf(1001L), messageRepo.deletedMessageIds)

        // Case B: Async forward + delete rule
        val asyncForwardDeleteRule = AutomationRule(
            id = 200L,
            name = "Forward SMS then Delete",
            enabled = true,
            matchBody = "ForwardAndDelete",
            delayMs = 0L
        ).apply {
            setActionSteps(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_FORWARD_SMS, forwardToPhone = "+15559876543"),
                AutomationActionStep(action = AutomationRule.ACTION_DELETE)
            ))
        }
        autoRepo.rules.clear()
        autoRepo.rules.add(asyncForwardDeleteRule)
        messageRepo.deletedMessageIds.clear()

        val msgAsync = createMessage(1002L, 502L, "12345", "ForwardAndDelete incoming OTP")
        val resultAsync = interactor.execute(msgAsync)

        // Must NOT be dropped! isDropped = false keeps message visible until worker completes forwarding and deletes
        assertTrue("Async forward + delete must keep message visible (ExecutionResult.Normal)",
            resultAsync is ExecuteAutomations.ExecutionResult.Normal)
        assertFalse("Message must NOT be deleted synchronously before forwarding finishes",
            messageRepo.deletedMessageIds.contains(1002L))
    }

    // ────────────────────────────────────────────────────────────────────
    // 9. Rapid Double-Save and Toggle Throttling
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testRapidDoubleSaveThrottlingEmitsOnlyOneSave() {
        val autoRepo = FakeAutomationRuleRepo()
        val presenter = EditAutomationPresenter(autoRepo)
        val view = TestEditAutomationView()
        presenter.bindIntents(view)

        view.nameSubject.onNext("Throttled Rule")
        view.matchSenderSubject.onNext("12345")

        // Fire two rapid save clicks immediately
        view.saveClicksSubject.onNext(Unit)
        view.saveClicksSubject.onNext(Unit)

        assertEquals("throttleFirst(500ms) must allow only 1 save event from rapid double-click",
            1, autoRepo.saveCount)
        assertEquals(1, autoRepo.rules.size)
        assertEquals("Throttled Rule", autoRepo.rules[0].name)
    }

    @Test
    fun testRapidToggleThrottlingEmitsOnlyOneToggle() {
        val rule = AutomationRule(id = 55L, name = "Rule 55", enabled = true)
        val autoRepo = FakeAutomationRuleRepo()
        autoRepo.rules.add(rule)

        val presenter = AutomationsPresenter(autoRepo)
        val toggleSubject = PublishSubject.create<Long>()
        val lifecycleOwner = object : LifecycleOwner {
            private val registry = LifecycleRegistry(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
            override val lifecycle: Lifecycle get() = registry
        }
        val view = object : AutomationsView {
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
            override fun addRuleClicks(): Observable<*> = Observable.empty<Unit>()
            override fun editRuleClicks(): Observable<Long> = Observable.empty<Long>()
            override fun deleteRuleClicks(): Observable<Long> = Observable.empty<Long>()
            override fun deleteRuleConfirmed(): Observable<Long> = Observable.empty<Long>()
            override fun toggleRuleClicks(): Observable<Long> = toggleSubject
            override fun showEditRule(ruleId: Long) {}
            override fun confirmDelete(ruleId: Long) {}
            override fun render(state: AutomationsState) {}
        }
        presenter.bindIntents(view)

        // Rapidly toggle rule twice in succession
        toggleSubject.onNext(55L)
        toggleSubject.onNext(55L)

        // Only 1 saveRule call should have passed through throttleFirst(300ms)
        assertEquals("throttleFirst(300ms) must allow only 1 toggle event from rapid double-click",
            1, autoRepo.saveCount)
    }

    // ────────────────────────────────────────────────────────────────────
    // 10. Blank Sender + Custom Bank Alert Regex + Forward & Delete End-to-End
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testSaveRuleWithBlankSenderAndCustomBankAlertRegexAndForwardThenDelete_successAndClosesOnMainThread() {
        val repo = FakeAutomationRuleRepo()
        val presenter = EditAutomationPresenter(repo)
        val view = TestEditAutomationView()

        presenter.setRuleId(-1L)
        presenter.bindIntents(view)

        // User sets custom bank alert regex in body with blank sender
        val customBankRegex = "(?i)\\b(debited|credited|balance|INR|transaction|account|spent)\\b"
        view.matchSenderSubject.onNext("")
        view.matchBodySubject.onNext(customBankRegex)
        view.matchBodyRegexSubject.onNext(true)
        view.delaySubject.onNext(0L)

        // Add Forward SMS step before Delete: [FORWARD_SMS, DELETE]
        view.addStepSubject.onNext(Unit)
        view.stepUpdatedSubject.onNext(0 to AutomationActionStep(
            action = AutomationRule.ACTION_FORWARD_SMS,
            forwardToPhone = "+919876543210",
            appendSenderToForward = true
        ))

        // Save rule
        view.saveClicksSubject.onNext(Unit)

        // Verify rule is saved to repo and view is closed cleanly
        assertEquals(1, repo.saveCount)
        assertTrue("View must close after saving successfully", view.closed)

        val savedRule = repo.rules.first()
        assertEquals("Forward SMS → Delete", savedRule.name)
        assertEquals("", savedRule.matchSender)
        assertEquals(customBankRegex, savedRule.matchBody)
        assertTrue(savedRule.matchBodyIsRegex)
        assertEquals(0L, savedRule.delayMs)
        val steps = savedRule.getActionSteps()
        assertEquals(2, steps.size)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, steps[0].action)
        assertEquals("+919876543210", steps[0].forwardToPhone)
        assertTrue(steps[0].appendSenderToForward)
        assertEquals(AutomationRule.ACTION_DELETE, steps[1].action)
    }

    // ────────────────────────────────────────────────────────────────────
    // 11. Database ID Math on Empty DB and Incremental Rule Allocation
    // ────────────────────────────────────────────────────────────────────
    @Suppress("UNCHECKED_CAST")
    @Test
    fun testRepositoryAssignsPositiveNonZeroIdOnEmptyDatabaseAndSequentialIncrements() {
        val mockRealm = Mockito.mock(io.realm.Realm::class.java)
        val captor = ArgumentCaptor.forClass(AutomationRule::class.java)
        var maxIdInDb: Number? = null

        Mockito.doAnswer { invocation ->
            val transaction = invocation.arguments[0] as io.realm.Realm.Transaction
            transaction.execute(mockRealm)
            null
        }.`when`(mockRealm).executeTransaction(Mockito.any())

        val query = Mockito.mock(io.realm.RealmQuery::class.java) as io.realm.RealmQuery<AutomationRule>
        Mockito.`when`(mockRealm.where(AutomationRule::class.java)).thenReturn(query)
        Mockito.`when`(query.max("id")).thenAnswer { maxIdInDb }
        Mockito.`when`(mockRealm.copyToRealmOrUpdate(captor.capture())).thenAnswer {
            val saved = it.arguments[0] as AutomationRule
            maxIdInDb = saved.id
            saved
        }

        val repo = AutomationRuleRepositoryImpl { mockRealm }

        // 1. First save on empty DB (max("id") is null): ID must be 1L, NOT 0L
        val rule1 = AutomationRule(id = 0L, name = "Rule 1")
        repo.saveRule(rule1)
        assertEquals(1L, captor.allValues.last().id)
        // Verify equalTo("id", ...) was NOT called since rule.id <= 0L
        Mockito.verify(query, Mockito.never()).equalTo(Mockito.eq("id"), Mockito.anyLong())

        // 2. Second save (max("id") is now 1L): ID must be 2L
        val rule2 = AutomationRule(id = 0L, name = "Rule 2")
        repo.saveRule(rule2)
        assertEquals(2L, captor.allValues.last().id)

        // 3. Updating existing rule with id = 1L preserves positive id
        Mockito.`when`(query.equalTo("id", 1L)).thenReturn(query)
        Mockito.`when`(query.findFirst()).thenReturn(captor.allValues.first())
        val ruleUpdate = AutomationRule(id = 1L, name = "Rule 1 Updated")
        repo.saveRule(ruleUpdate)
        assertEquals(1L, captor.allValues.last().id)
        Mockito.verify(query).equalTo("id", 1L)
    }

    // ────────────────────────────────────────────────────────────────────
    // 12. Incoming Message Matching with Blank Sender and Async Forward Visibility
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testBankAlertMatchingWithBlankSenderAndAsyncForwardKeepsMessageVisible() {
        val autoRepo = FakeAutomationRuleRepo()
        val contactsRepo = FakeContactRepo()
        val messageRepo = FakeMessageRepo()
        val convRepo = FakeConvRepo()

        val interactor = ExecuteAutomations(dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo)

        // Rule configured with blank sender and custom bank alert regex
        val bankRegex = "(?i)\\b(debited|credited|balance|INR|transaction|account|spent)\\b"
        val rule = AutomationRule(
            id = 1L,
            name = "Forward SMS → Delete",
            enabled = true,
            matchSender = "",
            matchBody = bankRegex,
            matchBodyIsRegex = true,
            delayMs = 0L
        ).apply {
            setActionSteps(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_FORWARD_SMS, forwardToPhone = "+919876543210"),
                AutomationActionStep(action = AutomationRule.ACTION_DELETE)
            ))
        }
        autoRepo.rules.add(rule)

        // Message 1: Bank alert from alphanumeric header
        val bankMessage = createMessage(1001L, 501L, "VK-HDFCBK", "INR 1,500.00 debited from account XX1234 on 16-OCT-26")
        val result1 = interactor.execute(bankMessage)

        // Must match rule (hitCount incremented)
        assertEquals(1L, autoRepo.hitCounts[1L])
        // Must return Normal (not Dropped) so message remains visible while forward is pending
        assertTrue("Message with async forward step must return Normal result",
            result1 is ExecuteAutomations.ExecutionResult.Normal)
        // Message must NOT be deleted yet by ExecuteAutomations directly
        assertFalse(messageRepo.deletedMessageIds.contains(1001L))

        // Message 2: Message from another sender that does NOT match bank alert keywords
        val promoMessage = createMessage(1002L, 502L, "VM-AIRTEL", "Flat 50% discount on your next recharge")
        val result2 = interactor.execute(promoMessage)

        // Hit count remains 1 (promo didn't match)
        assertEquals(1L, autoRepo.hitCounts[1L])
        assertTrue(result2 is ExecuteAutomations.ExecutionResult.Normal)
        assertFalse(messageRepo.deletedMessageIds.contains(1002L))
    }

    // ────────────────────────────────────────────────────────────────────
    // 13. Repository Failure Handled Gracefully Without Rx Uncaught Exception
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testSaveRuleRepositoryErrorHandledGracefullyWithoutCrashing() {
        val repo = FakeAutomationRuleRepo().apply { throwOnSave = true }
        val presenter = EditAutomationPresenter(repo)
        val view = TestEditAutomationView()

        presenter.setRuleId(-1L)
        presenter.bindIntents(view)

        // Click save - repo throws RuntimeException
        view.saveClicksSubject.onNext(Unit)

        // View should NOT be closed because save failed
        assertFalse("View must remain open if saving failed", view.closed)
    }

    // ────────────────────────────────────────────────────────────────────
    // 14. Validation Error Paths
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testSaveRuleValidationRejectsEmptyForwardPhone() {
        val repo = FakeAutomationRuleRepo()
        val presenter = EditAutomationPresenter(repo)
        val view = TestEditAutomationView()

        presenter.setRuleId(-1L)
        presenter.bindIntents(view)

        // Add Forward SMS step with empty phone number
        view.addStepSubject.onNext(Unit)
        view.stepUpdatedSubject.onNext(0 to AutomationActionStep(
            action = AutomationRule.ACTION_FORWARD_SMS,
            forwardToPhone = ""
        ))

        view.saveClicksSubject.onNext(Unit)

        assertEquals(EditAutomationPresenter.VALIDATION_PHONE, view.lastValidationError)
        assertEquals(0, repo.saveCount)
        assertFalse(view.closed)
    }

    @Test
    fun testSaveRuleValidationRejectsInvalidHttpUrl() {
        val repo = FakeAutomationRuleRepo()
        val presenter = EditAutomationPresenter(repo)
        val view = TestEditAutomationView()

        presenter.setRuleId(-1L)
        presenter.bindIntents(view)

        // Add Forward HTTP step with invalid URL
        view.addStepSubject.onNext(Unit)
        view.stepUpdatedSubject.onNext(0 to AutomationActionStep(
            action = AutomationRule.ACTION_FORWARD_HTTP,
            forwardToUrl = "ftp://invalid-url.com"
        ))

        view.saveClicksSubject.onNext(Unit)

        assertEquals(EditAutomationPresenter.VALIDATION_URL, view.lastValidationError)
        assertEquals(0, repo.saveCount)
        assertFalse(view.closed)
    }

    @Test
    fun testSaveRuleValidationRejectsEmptyReplyTemplate() {
        val repo = FakeAutomationRuleRepo()
        val presenter = EditAutomationPresenter(repo)
        val view = TestEditAutomationView()

        presenter.setRuleId(-1L)
        presenter.bindIntents(view)

        // Add Auto Reply step with empty template
        view.addStepSubject.onNext(Unit)
        view.stepUpdatedSubject.onNext(0 to AutomationActionStep(
            action = AutomationRule.ACTION_AUTO_REPLY,
            replyTemplate = ""
        ))

        view.saveClicksSubject.onNext(Unit)

        assertEquals(EditAutomationPresenter.VALIDATION_TEMPLATE, view.lastValidationError)
        assertEquals(0, repo.saveCount)
        assertFalse(view.closed)
    }

    // ────────────────────────────────────────────────────────────────────
    // 15. End-to-End Worker Step-by-Step Execution: Forward SMS then Delete
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testEndToEndWorkerPipelineExecutionForwardSmsThenDelete() {
        val workId = UUID.randomUUID().toString()
        val mockContext = dummyContext()
        val fakeMessageRepo = FakeMessageRepo()
        val mockConvRepo = Mockito.mock(ConversationRepository::class.java)
        val mockNotificationManager = Mockito.mock(dev.octoshrimpy.quik.manager.NotificationManager::class.java)

        val steps = listOf(
            AutomationActionStep(
                action = AutomationRule.ACTION_FORWARD_SMS,
                forwardToPhone = "+919876543210",
                appendSenderToForward = true
            ),
            AutomationActionStep(action = AutomationRule.ACTION_DELETE)
        )
        val actionsJson = AutomationActionStepSerializer.toJson(steps)
        val inputData = androidx.work.Data.Builder()
            .putString(AutomationActionWorker.KEY_ACTIONS_JSON, actionsJson)
            .putLong(AutomationActionWorker.KEY_MESSAGE_ID, 2026L)
            .putLong(AutomationActionWorker.KEY_THREAD_ID, 5050L)
            .putString(AutomationActionWorker.KEY_SENDER_ADDRESS, "VK-HDFCBK")
            .putString(AutomationActionWorker.KEY_MESSAGE_BODY, "INR 500 debited from AC 1234")
            .build()

        val workerParams = org.objenesis.ObjenesisStd().newInstance(androidx.work.WorkerParameters::class.java)
        androidx.work.WorkerParameters::class.java.getDeclaredField("mId").apply {
            isAccessible = true
            set(workerParams, UUID.fromString(workId))
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mInputData").apply {
            isAccessible = true
            set(workerParams, inputData)
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mRunAttemptCount").apply {
            isAccessible = true
            set(workerParams, 0)
        }

        AutomationActionWorker.clearLedger()
        val worker = AutomationActionWorker(mockContext, workerParams)
        worker.messageRepo = fakeMessageRepo
        worker.conversationRepo = mockConvRepo
        worker.notificationManager = mockNotificationManager

        val result = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)

        // Verify Step 0: Forward SMS sent with correct recipient, body, and sender appended
        val expectedBody = "From: VK-HDFCBK | INR 500 debited from AC 1234"
        assertEquals(1, fakeMessageRepo.sentMessages.size)
        val sent = fakeMessageRepo.sentMessages.first()
        assertEquals(listOf("+919876543210"), sent.first)
        assertEquals(expectedBody, sent.second)

        // Verify Step 1: deleteMessages executed with correct message ID
        assertEquals(listOf(2026L), fakeMessageRepo.deletedMessageIds)
    }

    // ────────────────────────────────────────────────────────────────────
    // 16. Idempotent setRuleId Does Not Wipe In-Progress Edits on Reattach
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testSetRuleIdIsIdempotentAndDoesNotWipeEditsOnReattach() {
        val repo = FakeAutomationRuleRepo()
        val presenter = EditAutomationPresenter(repo)
        val view = TestEditAutomationView()

        // First attach: set new rule (-1L)
        presenter.setRuleId(-1L)
        presenter.bindIntents(view)

        // User edits body and adds custom regex
        val customBankRegex = "(?i)\\b(debited|credited|balance|INR|transaction)\\b"
        view.matchBodySubject.onNext(customBankRegex)
        view.matchBodyRegexSubject.onNext(true)

        // Controller reattached (e.g. after returning from contact picker or rotation)
        // onAttach invokes setRuleId(-1L) again
        presenter.setRuleId(-1L)

        // Verify that custom body text and regex state are NOT wiped
        val currentState = presenter.stateObservable.blockingFirst()
        assertEquals(customBankRegex, currentState.matchBody)
        assertTrue(currentState.matchBodyIsRegex)
    }

    // ────────────────────────────────────────────────────────────────────
    // 17. AutomationsPresenter Handles Delete and Toggle Failures Gracefully
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testAutomationsPresenterHandlesDeleteAndToggleFailuresGracefully() {
        val repo = FakeAutomationRuleRepo().apply {
            rules.add(AutomationRule(id = 100L, name = "Rule 100", enabled = true))
            throwOnDelete = true
            throwOnSave = true
        }

        val presenter = AutomationsPresenter(repo)
        val deleteConfirmedSubject = PublishSubject.create<Long>()
        val toggleSubject = PublishSubject.create<Long>()

        val lifecycleOwner = object : LifecycleOwner {
            private val registry = LifecycleRegistry(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
            override val lifecycle: Lifecycle get() = registry
        }
        val view = object : AutomationsView {
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
            override fun addRuleClicks(): Observable<*> = Observable.empty<Unit>()
            override fun editRuleClicks(): Observable<Long> = Observable.empty<Long>()
            override fun deleteRuleClicks(): Observable<Long> = Observable.empty<Long>()
            override fun deleteRuleConfirmed(): Observable<Long> = deleteConfirmedSubject
            override fun toggleRuleClicks(): Observable<Long> = toggleSubject
            override fun showEditRule(ruleId: Long) {}
            override fun confirmDelete(ruleId: Long) {}
            override fun render(state: AutomationsState) {}
        }
        presenter.bindIntents(view)

        // Trigger delete which throws RuntimeException in repository
        // Must NOT crash with uncaught OnErrorNotImplementedException
        deleteConfirmedSubject.onNext(100L)

        // Trigger toggle which throws RuntimeException in repository
        // Must NOT crash with uncaught OnErrorNotImplementedException
        toggleSubject.onNext(100L)

        // Rule should still be in repo (since both operations failed and were swallowed gracefully)
        assertNotNull(repo.getRule(100L))
    }

    // ────────────────────────────────────────────────────────────────────
    // 18. Single Step Immediate Delete Pipeline with Bank Alert Regex
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testSingleStepImmediateDeletePipelineWithBankAlertRegexAndBlankSender() {
        val autoRepo = FakeAutomationRuleRepo()
        val contactsRepo = FakeContactRepo()
        val messageRepo = FakeMessageRepo()
        val convRepo = FakeConvRepo()

        val interactor = ExecuteAutomations(dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo)

        // Rule configured with blank sender, custom bank alert regex, 0s delay, single step DELETE
        val bankRegex = "(?i)\\b(debited|credited|balance|INR|transaction)\\b"
        val rule = AutomationRule(
            id = 1L,
            name = "Delete Bank Alerts",
            enabled = true,
            matchSender = "",
            matchBody = bankRegex,
            matchBodyIsRegex = true,
            delayMs = 0L
        ).apply {
            setActionSteps(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_DELETE)
            ))
        }
        autoRepo.rules.add(rule)

        // Incoming bank SMS
        val bankMessage = createMessage(3001L, 701L, "AD-ICICIB", "INR 2,499.00 debited from AC 9876 on 16-OCT-26")
        val result = interactor.execute(bankMessage)

        // Single immediate DELETE: result must be Dropped (instant suppression)
        assertTrue("Single immediate DELETE rule must return Dropped result",
            result is ExecuteAutomations.ExecutionResult.Dropped)
        // Message must be deleted immediately in messageRepo
        assertTrue("Message must be deleted immediately by ExecuteAutomations",
            messageRepo.deletedMessageIds.contains(3001L))
        // Hit count must be incremented
        assertEquals(1L, autoRepo.hitCounts[1L])
    }

    // ────────────────────────────────────────────────────────────────────
    // 19. Multi-Step Pipeline with Three Actions: Mark Read, Forward, Delete
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testMultiStepPipelineWithThreeActions_MarkReadForwardThenDelete() {
        val workId = UUID.randomUUID().toString()
        val mockContext = dummyContext()
        val fakeMessageRepo = FakeMessageRepo()
        val mockConvRepo = Mockito.mock(ConversationRepository::class.java)
        val mockNotificationManager = Mockito.mock(dev.octoshrimpy.quik.manager.NotificationManager::class.java)

        val steps = listOf(
            AutomationActionStep(action = AutomationRule.ACTION_MARK_READ),
            AutomationActionStep(
                action = AutomationRule.ACTION_FORWARD_SMS,
                forwardToPhone = "+15551234567",
                appendSenderToForward = false
            ),
            AutomationActionStep(action = AutomationRule.ACTION_DELETE)
        )
        val actionsJson = AutomationActionStepSerializer.toJson(steps)
        val inputData = androidx.work.Data.Builder()
            .putString(AutomationActionWorker.KEY_ACTIONS_JSON, actionsJson)
            .putLong(AutomationActionWorker.KEY_MESSAGE_ID, 4001L)
            .putLong(AutomationActionWorker.KEY_THREAD_ID, 888L)
            .putString(AutomationActionWorker.KEY_SENDER_ADDRESS, "ALERT-SMS")
            .putString(AutomationActionWorker.KEY_MESSAGE_BODY, "Transaction OTP is 492810")
            .build()

        val workerParams = org.objenesis.ObjenesisStd().newInstance(androidx.work.WorkerParameters::class.java)
        androidx.work.WorkerParameters::class.java.getDeclaredField("mId").apply {
            isAccessible = true
            set(workerParams, UUID.fromString(workId))
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mInputData").apply {
            isAccessible = true
            set(workerParams, inputData)
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mRunAttemptCount").apply {
            isAccessible = true
            set(workerParams, 0)
        }

        AutomationActionWorker.clearLedger()
        val worker = AutomationActionWorker(mockContext, workerParams)
        worker.messageRepo = fakeMessageRepo
        worker.conversationRepo = mockConvRepo
        worker.notificationManager = mockNotificationManager

        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)

        // Step 0: Mark read executed
        assertTrue(fakeMessageRepo.markedReadThreadIds.contains(888L))
        // Step 1: Forward SMS executed (without sender prepended per setting)
        assertEquals(1, fakeMessageRepo.sentMessages.size)
        val sent = fakeMessageRepo.sentMessages.first()
        assertEquals(listOf("+15551234567"), sent.first)
        assertEquals("Transaction OTP is 492810", sent.second)
        // Step 2: Delete executed
        assertTrue(fakeMessageRepo.deletedMessageIds.contains(4001L))
    }

    // ────────────────────────────────────────────────────────────────────
    // 20. Complete Start-to-End Flow: Rule Creation, Persistence, and Multi-Step Execution
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testCompleteStartToEndFlow_RuleCreationPersistenceAndMultiStepExecution() {
        val repo = FakeAutomationRuleRepo()
        val presenter = EditAutomationPresenter(repo)
        val view = TestEditAutomationView()

        // Phase 1: User creates new rule in EditAutomationPresenter
        presenter.setRuleId(-1L)
        presenter.bindIntents(view)

        val customBankRegex = "(?i)\\b(debited|credited|balance|INR|transaction)\\b"
        view.nameSubject.onNext("Bank Alert Pipeline")
        view.matchSenderSubject.onNext("") // blank sender
        view.matchBodySubject.onNext(customBankRegex)
        view.matchBodyRegexSubject.onNext(true)
        view.delaySubject.onNext(0L)

        // Add Forward SMS step: chain becomes [FORWARD_SMS, DELETE]
        view.addStepSubject.onNext(Unit)
        view.stepUpdatedSubject.onNext(0 to AutomationActionStep(
            action = AutomationRule.ACTION_FORWARD_SMS,
            forwardToPhone = "+919999988888",
            appendSenderToForward = true
        ))

        view.saveClicksSubject.onNext(Unit)

        // Phase 2: Verify rule is persisted and view is closed
        assertTrue("View must be closed after saving", view.closed)
        assertEquals(1, repo.saveCount)
        val savedRule = repo.rules.first()
        savedRule.id = 1L // Repo assigns ID 1L
        assertEquals("Bank Alert Pipeline", savedRule.name)

        // Phase 3: Incoming message matching bank regex arrives
        val contactsRepo = FakeContactRepo()
        val messageRepo = FakeMessageRepo()
        val convRepo = FakeConvRepo()
        val interactor = ExecuteAutomations(dummyContext(), repo, contactsRepo, messageRepo, convRepo)

        val incomingBankMsg = createMessage(9001L, 333L, "VM-HDFCBK", "INR 750.00 debited from account 1234")
        val execResult = interactor.execute(incomingBankMsg)

        // Must match rule and increment hit count
        assertEquals(1L, repo.hitCounts[1L])
        // Must return Normal so message stays visible until worker runs
        assertTrue(execResult is ExecuteAutomations.ExecutionResult.Normal)
        assertFalse(messageRepo.deletedMessageIds.contains(9001L))

        // Phase 4: Worker executes [FORWARD_SMS, DELETE]
        val workId = UUID.randomUUID().toString()
        val mockNotificationManager = Mockito.mock(dev.octoshrimpy.quik.manager.NotificationManager::class.java)
        val actionsJson = AutomationActionStepSerializer.toJson(savedRule.getActionSteps())
        val inputData = androidx.work.Data.Builder()
            .putString(AutomationActionWorker.KEY_ACTIONS_JSON, actionsJson)
            .putLong(AutomationActionWorker.KEY_MESSAGE_ID, 9001L)
            .putLong(AutomationActionWorker.KEY_THREAD_ID, 333L)
            .putString(AutomationActionWorker.KEY_SENDER_ADDRESS, "VM-HDFCBK")
            .putString(AutomationActionWorker.KEY_MESSAGE_BODY, incomingBankMsg.body)
            .build()

        val workerParams = org.objenesis.ObjenesisStd().newInstance(androidx.work.WorkerParameters::class.java)
        androidx.work.WorkerParameters::class.java.getDeclaredField("mId").apply {
            isAccessible = true
            set(workerParams, UUID.fromString(workId))
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mInputData").apply {
            isAccessible = true
            set(workerParams, inputData)
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mRunAttemptCount").apply {
            isAccessible = true
            set(workerParams, 0)
        }

        AutomationActionWorker.clearLedger()
        val worker = AutomationActionWorker(dummyContext(), workerParams)
        worker.messageRepo = messageRepo
        worker.conversationRepo = Mockito.mock(ConversationRepository::class.java)
        worker.notificationManager = mockNotificationManager

        val workerResult = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), workerResult)

        // Forward sent
        assertEquals(1, messageRepo.sentMessages.size)
        val sentMsg = messageRepo.sentMessages.first()
        assertEquals(listOf("+919999988888"), sentMsg.first)
        assertEquals("From: VM-HDFCBK | INR 750.00 debited from account 1234", sentMsg.second)

        // Message deleted
        assertEquals(listOf(9001L), messageRepo.deletedMessageIds)
    }

    // ────────────────────────────────────────────────────────────────────
    // 21. Multi-Threaded Concurrency & Isolation in AutomationActionWorker Ledger
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testAutomationActionWorkerLedgerConcurrentMarkAndRead() {
        val workId1 = UUID.randomUUID().toString()
        val workId2 = UUID.randomUUID().toString()
        val mockContext = dummyContext()

        AutomationActionWorker.clearLedger()

        val numThreads = 10
        val latch = java.util.concurrent.CountDownLatch(numThreads)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(numThreads)
        val exceptions = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        for (i in 0 until numThreads) {
            val step = i
            executor.submit {
                try {
                    AutomationActionWorker.markStepCompleted(workId1, step, mockContext)
                    val completed = AutomationActionWorker.getCompletedSteps(workId1, mockContext)
                    assertTrue(completed.contains(step))
                } catch (t: Throwable) {
                    exceptions.add(t)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue("Expected 0 exceptions during concurrent ledger updates, but got: $exceptions", exceptions.isEmpty())

        val finalCompleted1 = AutomationActionWorker.getCompletedSteps(workId1, mockContext)
        assertEquals(numThreads, finalCompleted1.size)

        // Verify isolation for workId2
        val completed2 = AutomationActionWorker.getCompletedSteps(workId2, mockContext)
        assertTrue(completed2.isEmpty())

        // Clear workId1 and verify workId2 unaffected
        AutomationActionWorker.clearLedger(workId1, mockContext)
        assertTrue(AutomationActionWorker.getCompletedSteps(workId1, mockContext).isEmpty())
    }

    // ────────────────────────────────────────────────────────────────────
    // 22. Action Failure in Chain with ACTION_DELETE: Deletes Original Message & Records Failure
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testActionFailureInChainWithDelete_deletesOriginalMessageAndCapturesFailure() {
        val autoRepo = FakeAutomationRuleRepo()
        val messageRepo = FakeMessageRepo()
        val convRepo = FakeConvRepo()
        val mockNotificationManager = Mockito.mock(dev.octoshrimpy.quik.manager.NotificationManager::class.java)

        val rule = AutomationRule(
            id = 101L,
            name = "Forward OTP then Delete",
            enabled = true
        ).apply {
            setActionSteps(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_FORWARD_SMS, forwardToPhone = "+15550001111"),
                AutomationActionStep(action = AutomationRule.ACTION_DELETE)
            ))
        }
        autoRepo.rules.add(rule)

        // Simulate SMS sending failure (e.g. carrier rejection or SIM missing)
        messageRepo.failSend = true

        val messageId = 7001L
        val threadId = 444L
        val sender = "+19998887777"
        val body = "Your secure OTP is 765432"

        val workId = UUID.randomUUID().toString()
        val actionsJson = AutomationActionStepSerializer.toJson(rule.getActionSteps())
        val inputData = androidx.work.Data.Builder()
            .putLong(ExecuteAutomations.KEY_RULE_ID, rule.id)
            .putString(ExecuteAutomations.KEY_RULE_NAME, rule.name)
            .putString(AutomationActionWorker.KEY_ACTIONS_JSON, actionsJson)
            .putLong(AutomationActionWorker.KEY_MESSAGE_ID, messageId)
            .putLong(AutomationActionWorker.KEY_THREAD_ID, threadId)
            .putString(AutomationActionWorker.KEY_SENDER_ADDRESS, sender)
            .putString(AutomationActionWorker.KEY_MESSAGE_BODY, body)
            .build()

        val workerParams = org.objenesis.ObjenesisStd().newInstance(androidx.work.WorkerParameters::class.java)
        androidx.work.WorkerParameters::class.java.getDeclaredField("mId").apply {
            isAccessible = true
            set(workerParams, UUID.fromString(workId))
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mInputData").apply {
            isAccessible = true
            set(workerParams, inputData)
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mRunAttemptCount").apply {
            isAccessible = true
            set(workerParams, 0)
        }

        AutomationActionWorker.clearLedger()
        val worker = AutomationActionWorker(dummyContext(), workerParams)
        worker.messageRepo = messageRepo
        worker.conversationRepo = convRepo
        worker.notificationManager = mockNotificationManager
        worker.automationRepo = autoRepo

        val workerResult = worker.doWork()

        // 1. Worker must report failure
        assertEquals(androidx.work.ListenableWorker.Result.failure(), workerResult)

        // 2. Crucial Requirement: Original message MUST be deleted even though forward failed!
        assertTrue("Message must be deleted per delete intention when chain has ACTION_DELETE",
            messageRepo.deletedMessageIds.contains(messageId))
        Mockito.verify(mockNotificationManager).update(threadId)

        // 3. Failed message must be captured in repository
        val failedList = autoRepo.getFailedMessagesList()
        assertEquals(1, failedList.size)
        val failed = failedList.first()
        assertEquals(messageId, failed.messageId)
        assertEquals(threadId, failed.threadId)
        assertEquals(rule.id, failed.ruleId)
        assertEquals(rule.name, failed.ruleName)
        assertEquals(0, failed.failedStepIndex)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, failed.failedActionType)
        assertTrue(failed.canRetry)
        assertEquals(sender, failed.sender)
        assertEquals(body, failed.body)
        assertTrue(failed.errorMessage.contains("Carrier/provider rejected SMS send"))

        // 4. Execution failure must be logged in AutomationLog
        val errorLogs = autoRepo.getLogsList(AutomationLog.TYPE_ERROR)
        assertEquals(1, errorLogs.size)
        val errLog = errorLogs.first()
        assertEquals(rule.id, errLog.ruleId)
        assertEquals(rule.name, errLog.ruleName)
        assertEquals(messageId, errLog.messageId)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, errLog.actionType)
        assertTrue(errLog.details.contains("Carrier/provider rejected SMS send"))
    }

    // ────────────────────────────────────────────────────────────────────
    // 23. Action Failure in Chain WITHOUT ACTION_DELETE: Keeps Message in Inbox
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testActionFailureInChainWithoutDelete_doesNotDeleteOriginalMessage() {
        val autoRepo = FakeAutomationRuleRepo()
        val messageRepo = FakeMessageRepo()
        val convRepo = FakeConvRepo()
        val mockNotificationManager = Mockito.mock(dev.octoshrimpy.quik.manager.NotificationManager::class.java)

        val rule = AutomationRule(
            id = 102L,
            name = "Forward Only",
            enabled = true
        ).apply {
            setActionSteps(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_FORWARD_SMS, forwardToPhone = "+15550002222")
            ))
        }
        autoRepo.rules.add(rule)

        // Simulate SMS sending failure
        messageRepo.failSend = true

        val messageId = 7002L
        val threadId = 445L

        val workId = UUID.randomUUID().toString()
        val actionsJson = AutomationActionStepSerializer.toJson(rule.getActionSteps())
        val inputData = androidx.work.Data.Builder()
            .putLong(ExecuteAutomations.KEY_RULE_ID, rule.id)
            .putString(ExecuteAutomations.KEY_RULE_NAME, rule.name)
            .putString(AutomationActionWorker.KEY_ACTIONS_JSON, actionsJson)
            .putLong(AutomationActionWorker.KEY_MESSAGE_ID, messageId)
            .putLong(AutomationActionWorker.KEY_THREAD_ID, threadId)
            .putString(AutomationActionWorker.KEY_SENDER_ADDRESS, "+1000111222")
            .putString(AutomationActionWorker.KEY_MESSAGE_BODY, "Notice")
            .build()

        val workerParams = org.objenesis.ObjenesisStd().newInstance(androidx.work.WorkerParameters::class.java)
        androidx.work.WorkerParameters::class.java.getDeclaredField("mId").apply {
            isAccessible = true
            set(workerParams, UUID.fromString(workId))
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mInputData").apply {
            isAccessible = true
            set(workerParams, inputData)
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mRunAttemptCount").apply {
            isAccessible = true
            set(workerParams, 0)
        }

        AutomationActionWorker.clearLedger()
        val worker = AutomationActionWorker(dummyContext(), workerParams)
        worker.messageRepo = messageRepo
        worker.conversationRepo = convRepo
        worker.notificationManager = mockNotificationManager
        worker.automationRepo = autoRepo

        val workerResult = worker.doWork()

        assertEquals(androidx.work.ListenableWorker.Result.failure(), workerResult)
        // Message must NOT be deleted because rule has no ACTION_DELETE
        assertFalse("Message must NOT be deleted when chain does not contain ACTION_DELETE",
            messageRepo.deletedMessageIds.contains(messageId))

        // But failure was still recorded
        assertEquals(1, autoRepo.getFailedMessagesList().size)
        assertEquals(1, autoRepo.getLogsList(AutomationLog.TYPE_ERROR).size)
    }

    // ────────────────────────────────────────────────────────────────────
    // 24. Activity Log Filtering by Type and Clearing
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testActivityLogFilteringAndClearing() {
        val repo = FakeAutomationRuleRepo()

        repo.log(1L, "Rule 1", 10L, AutomationLog.TYPE_INFO, "MATCH", "Matched message")
        repo.log(1L, "Rule 1", 10L, AutomationLog.TYPE_SUCCESS, "FORWARD_SMS", "Forwarded")
        repo.log(2L, "Rule 2", 20L, AutomationLog.TYPE_ERROR, "FORWARD_HTTP", "Webhook timed out")
        repo.log(3L, "Rule 3", 30L, AutomationLog.TYPE_SUCCESS, "DELETE", "Deleted message")
        repo.log(2L, "Rule 2", 20L, AutomationLog.TYPE_ERROR, "FORWARD_HTTP", "Retry failed")

        // Unfiltered / ALL
        assertEquals(5, repo.getLogsList("ALL").size)
        assertEquals(5, repo.getLogsList(null).size)
        assertEquals(5, repo.getLogsList("").size)

        // Filter by ERROR
        val errorLogs = repo.getLogsList(AutomationLog.TYPE_ERROR)
        assertEquals(2, errorLogs.size)
        assertTrue(errorLogs.all { it.type == AutomationLog.TYPE_ERROR })

        // Filter by SUCCESS
        val successLogs = repo.getLogsList(AutomationLog.TYPE_SUCCESS)
        assertEquals(2, successLogs.size)
        assertTrue(successLogs.all { it.type == AutomationLog.TYPE_SUCCESS })

        // Clear logs
        repo.clearLogs()
        assertEquals(0, repo.getLogsList().size)
        assertEquals(0, repo.getLogsList("ALL").size)
    }

    // ────────────────────────────────────────────────────────────────────
    // 25. Failed Messages Dismiss and Clear
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testFailedMessagesDismissAndClear() {
        val repo = FakeAutomationRuleRepo()

        val msg1 = FailedAutomationMessage(
            id = 10L,
            messageId = 100L,
            ruleId = 1L,
            ruleName = "Rule 1",
            sender = "111",
            body = "Body 1",
            errorMessage = "Error 1"
        )
        val msg2 = FailedAutomationMessage(
            id = 20L,
            messageId = 200L,
            ruleId = 2L,
            ruleName = "Rule 2",
            sender = "222",
            body = "Body 2",
            errorMessage = "Error 2"
        )

        repo.saveFailedMessage(msg1)
        repo.saveFailedMessage(msg2)
        assertEquals(2, repo.getFailedMessagesList().size)

        // Dismiss single message
        repo.deleteFailedMessage(10L)
        val remaining = repo.getFailedMessagesList()
        assertEquals(1, remaining.size)
        assertEquals(20L, remaining.first().id)

        // Clear all failed messages
        repo.clearFailedMessages()
        assertTrue(repo.getFailedMessagesList().isEmpty())
    }

    // ────────────────────────────────────────────────────────────────────
    // 26. AutomationsPresenter Tab Switching & Failed Message Retry/Dismiss
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testAutomationsPresenter_tabSwitchingAndFailedMessageRetry() {
        val repo = FakeAutomationRuleRepo()
        val presenter = AutomationsPresenter(repo)

        val tabSubject = PublishSubject.create<Int>()
        val retrySubject = PublishSubject.create<Long>()
        val dismissSubject = PublishSubject.create<Long>()
        val clearFailedSubject = PublishSubject.create<Unit>()
        val filterLogsSubject = PublishSubject.create<String>()
        val clearLogsSubject = PublishSubject.create<Unit>()

        var latestState: AutomationsState? = null

        val lifecycleOwner = object : LifecycleOwner {
            private val registry = LifecycleRegistry(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
            override val lifecycle: Lifecycle get() = registry
        }

        val view = object : AutomationsView {
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
            override fun addRuleClicks(): Observable<*> = Observable.empty<Unit>()
            override fun editRuleClicks(): Observable<Long> = Observable.empty<Long>()
            override fun deleteRuleClicks(): Observable<Long> = Observable.empty<Long>()
            override fun deleteRuleConfirmed(): Observable<Long> = Observable.empty<Long>()
            override fun toggleRuleClicks(): Observable<Long> = Observable.empty<Long>()

            override fun tabSelectedClicks(): Observable<Int> = tabSubject
            override fun retryFailedClicks(): Observable<Long> = retrySubject
            override fun dismissFailedClicks(): Observable<Long> = dismissSubject
            override fun clearFailedClicks(): Observable<*> = clearFailedSubject
            override fun filterLogsClicks(): Observable<String> = filterLogsSubject
            override fun clearLogsClicks(): Observable<*> = clearLogsSubject

            override fun confirmDelete(ruleId: Long) {}
            override fun showEditRule(ruleId: Long) {}
            override fun render(state: AutomationsState) {
                latestState = state
            }
        }

        val stateObserver = presenter.stateObservable.test()
        presenter.bindIntents(view)

        // 1. Initial tab is TAB_RULES (0)
        assertEquals(AutomationsState.TAB_RULES, stateObserver.values().last().selectedTab)

        // 2. Switch to Failed Messages tab (1)
        tabSubject.onNext(AutomationsState.TAB_FAILED)
        assertEquals(AutomationsState.TAB_FAILED, stateObserver.values().last().selectedTab)

        // 3. Switch to Activity Logs tab (2)
        tabSubject.onNext(AutomationsState.TAB_LOGS)
        assertEquals(AutomationsState.TAB_LOGS, stateObserver.values().last().selectedTab)

        // 4. Save a failed message and trigger retry with a deterministic workManagerEnqueuer
        var enqueuedRequest: androidx.work.OneTimeWorkRequest? = null
        var shouldThrowOnEnqueue = false

        val testPresenter = AutomationsPresenter(repo) { req ->
            if (shouldThrowOnEnqueue) {
                throw IllegalStateException("Simulated WorkManager queue failure")
            }
            enqueuedRequest = req
        }

        val testView = object : AutomationsView {
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
            override fun addRuleClicks(): Observable<*> = Observable.empty<Unit>()
            override fun editRuleClicks(): Observable<Long> = Observable.empty<Long>()
            override fun deleteRuleClicks(): Observable<Long> = Observable.empty<Long>()
            override fun deleteRuleConfirmed(): Observable<Long> = Observable.empty<Long>()
            override fun toggleRuleClicks(): Observable<Long> = Observable.empty<Long>()

            override fun tabSelectedClicks(): Observable<Int> = tabSubject
            override fun retryFailedClicks(): Observable<Long> = retrySubject
            override fun dismissFailedClicks(): Observable<Long> = dismissSubject
            override fun clearFailedClicks(): Observable<*> = clearFailedSubject
            override fun filterLogsClicks(): Observable<String> = filterLogsSubject
            override fun clearLogsClicks(): Observable<*> = clearLogsSubject

            override fun confirmDelete(ruleId: Long) {}
            override fun showEditRule(ruleId: Long) {}
            override fun render(state: AutomationsState) {}
        }
        testPresenter.bindIntents(testView)

        val failedMsg = FailedAutomationMessage(
            id = 555L,
            messageId = 12345L,
            threadId = 999L,
            ruleId = 42L,
            ruleName = "Bank Forwarder",
            sender = "+19998887777",
            body = "Debit alert",
            failedStepIndex = 0,
            failedActionType = AutomationRule.ACTION_FORWARD_SMS,
            errorMessage = "Carrier error",
            actionsJson = AutomationActionStepSerializer.toJson(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_FORWARD_SMS, forwardToPhone = "+15551234567")
            ))
        )
        repo.saveFailedMessage(failedMsg)
        assertEquals(1, repo.getFailedMessagesList().size)

        // 4a. Failure resilience: If WorkManager fails to enqueue, failed message MUST NOT be deleted
        shouldThrowOnEnqueue = true
        retrySubject.onNext(555L)

        // Wait brief moment for Schedulers.io
        Thread.sleep(100)
        assertEquals("Failed message must NOT be deleted when enqueue fails",
            1, repo.getFailedMessagesList().size)
        assertTrue(repo.logs.any { it.actionType == "RETRY" && it.type == AutomationLog.TYPE_ERROR })

        // 4b. Successful retry: wait for throttleFirst window (500ms) to elapse
        Thread.sleep(600)
        shouldThrowOnEnqueue = false
        retrySubject.onNext(555L)
        Thread.sleep(200)

        assertNotNull("Work request must be enqueued on successful retry", enqueuedRequest)
        assertTrue("Failed message must be removed after retry is dispatched",
            repo.getFailedMessagesList().isEmpty())
        assertTrue(repo.logs.any { it.actionType == "RETRY" && it.ruleId == 42L && it.type == AutomationLog.TYPE_INFO })
    }

    // ────────────────────────────────────────────────────────────────────
    // 27. Housekeeping Prunes Logs Older Than 7 Days
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testHousekeepingWorkerPrunesOldLogs() {
        val repo = FakeAutomationRuleRepo()
        val now = System.currentTimeMillis()
        val eightDaysAgo = now - (8L * 24 * 60 * 60 * 1000L)
        val twoDaysAgo = now - (2L * 24 * 60 * 60 * 1000L)

        repo.logs.add(AutomationLog(
            id = 1L, ruleId = 1L, ruleName = "Old Rule", messageId = 10L,
            type = AutomationLog.TYPE_SUCCESS, actionType = "DELETE",
            details = "Old action", timestamp = eightDaysAgo
        ))
        repo.logs.add(AutomationLog(
            id = 2L, ruleId = 1L, ruleName = "Recent Rule", messageId = 11L,
            type = AutomationLog.TYPE_SUCCESS, actionType = "DELETE",
            details = "Recent action", timestamp = twoDaysAgo
        ))
        repo.logs.add(AutomationLog(
            id = 3L, ruleId = 2L, ruleName = "Current Rule", messageId = 12L,
            type = AutomationLog.TYPE_INFO, actionType = "MATCH",
            details = "Current action", timestamp = now
        ))

        assertEquals(3, repo.logs.size)

        // Prune logs older than 7 days
        repo.pruneOldLogs(7)

        val remaining = repo.logs
        assertEquals(2, remaining.size)
        assertFalse(remaining.any { it.id == 1L })
        assertTrue(remaining.any { it.id == 2L })
        assertTrue(remaining.any { it.id == 3L })
    }

    // ────────────────────────────────────────────────────────────────────
    // 28. ExecuteAutomations Delete Preservation & Failure Persistence on Schedule Failure
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testExecuteAutomations_deletesMessageAndLogsFailureWhenSchedulingFails() {
        val autoRepo = FakeAutomationRuleRepo()
        val contactsRepo = FakeContactRepo()
        val messageRepo = FakeMessageRepo()
        val convRepo = FakeConvRepo()

        val interactor = ExecuteAutomations(
            dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo,
            workManagerEnqueuer = { throw IllegalStateException("WorkManager rejected enqueue") }
        )

        // Rule with async action FORWARD_SMS and ACTION_DELETE
        val rule = AutomationRule(
            id = 77L,
            name = "Forward & Delete Rule",
            enabled = true,
            matchBody = "Secret"
        ).apply {
            setActionSteps(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_FORWARD_SMS, forwardToPhone = "+15551234567"),
                AutomationActionStep(action = AutomationRule.ACTION_DELETE)
            ))
        }
        autoRepo.rules.add(rule)

        // Incoming message: dummyContext() does not have real WorkManager, so scheduleAction will catch Throwable and return false
        val message = createMessage(999L, 888L, "+19998887777", "Secret Code 1234")
        val result = interactor.execute(message)

        // Result MUST be Dropped because rule has ACTION_DELETE and scheduling failed
        assertTrue("Failed chain with ACTION_DELETE must drop notification & conversation",
            result is ExecuteAutomations.ExecutionResult.Dropped)

        // Message must be deleted from database
        assertTrue("Message must be deleted from messageRepo on scheduling failure",
            messageRepo.deletedMessageIds.contains(999L))

        // Failed message must be captured for manual review/retry
        assertEquals(1, autoRepo.getFailedMessagesList().size)
        val failed = autoRepo.getFailedMessagesList().first()
        assertEquals(999L, failed.messageId)
        assertEquals(77L, failed.ruleId)
        assertEquals("Forward & Delete Rule", failed.ruleName)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, failed.failedActionType)
        assertTrue(failed.errorMessage.contains("Failed to enqueue WorkManager task"))

        // Error log must be written
        val errorLogs = autoRepo.getLogsList(AutomationLog.TYPE_ERROR)
        assertEquals(1, errorLogs.size)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, errorLogs.first().actionType)
    }

    // ────────────────────────────────────────────────────────────────────
    // 29. AutomationRuleRepositoryImpl Query Realm Reuse (No premature closure)
    // ────────────────────────────────────────────────────────────────────
    @Suppress("UNCHECKED_CAST")
    @Test
    fun testAutomationRuleRepositoryImpl_reusesActiveQueryRealmWithoutPrematureClosure() {
        val mockRealm = Mockito.mock(io.realm.Realm::class.java)
        Mockito.`when`(mockRealm.isClosed).thenReturn(false)
        val mockQuery = Mockito.mock(io.realm.RealmQuery::class.java) as io.realm.RealmQuery<AutomationLog>
        val mockResults = Mockito.mock(io.realm.RealmResults::class.java) as io.realm.RealmResults<AutomationLog>

        Mockito.`when`(mockRealm.where(AutomationLog::class.java)).thenReturn(mockQuery)
        Mockito.`when`(mockQuery.equalTo(Mockito.anyString(), Mockito.anyString())).thenReturn(mockQuery)
        Mockito.`when`(mockQuery.sort(Mockito.anyString(), Mockito.any<io.realm.Sort>())).thenReturn(mockQuery)
        Mockito.`when`(mockQuery.findAllAsync()).thenReturn(mockResults)

        var providerCallCount = 0
        val repo = AutomationRuleRepositoryImpl {
            providerCallCount++
            mockRealm
        }

        // First call opens the realm
        val logs1 = repo.getLogs(null)
        assertNotNull(logs1)
        assertEquals(1, providerCallCount)

        // Second call with filter must REUSE the open realm, not close it and re-instantiate
        val logs2 = repo.getLogs("ERROR")
        assertNotNull(logs2)
        assertEquals(1, providerCallCount)
        Mockito.verify(mockRealm, Mockito.never()).close()

        // Calling close() closes the realm
        repo.close()
        Mockito.verify(mockRealm, Mockito.times(1)).close()
    }

    // ────────────────────────────────────────────────────────────────────
    // 30. AutomationActionWorker Step Exception Preserves Exact Step Index & Action
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testAutomationActionWorker_stepExceptionPreservesExactStepIndexAndActionType() {
        val autoRepo = FakeAutomationRuleRepo()
        val messageRepo = FakeMessageRepo().apply {
            throwOnSend = SecurityException("Carrier permission denied: SEND_SMS")
        }
        val convRepo = FakeConvRepo()
        val mockNotificationManager = Mockito.mock(dev.octoshrimpy.quik.manager.NotificationManager::class.java)

        val rule = AutomationRule(
            id = 501L,
            name = "Sensitive Rule",
            enabled = true
        ).apply {
            setActionSteps(listOf(
                AutomationActionStep(action = AutomationRule.ACTION_FORWARD_SMS, forwardToPhone = "+15559998888"),
                AutomationActionStep(action = AutomationRule.ACTION_DELETE)
            ))
        }

        val workId = UUID.randomUUID().toString()
        val actionsJson = AutomationActionStepSerializer.toJson(rule.getActionSteps())
        val inputData = androidx.work.Data.Builder()
            .putLong(ExecuteAutomations.KEY_RULE_ID, rule.id)
            .putString(ExecuteAutomations.KEY_RULE_NAME, rule.name)
            .putString(AutomationActionWorker.KEY_ACTIONS_JSON, actionsJson)
            .putLong(AutomationActionWorker.KEY_MESSAGE_ID, 8888L)
            .putLong(AutomationActionWorker.KEY_THREAD_ID, 4444L)
            .putString(AutomationActionWorker.KEY_SENDER_ADDRESS, "+11223344")
            .putString(AutomationActionWorker.KEY_MESSAGE_BODY, "Confidential PIN")
            .build()

        val workerParams = org.objenesis.ObjenesisStd().newInstance(androidx.work.WorkerParameters::class.java)
        androidx.work.WorkerParameters::class.java.getDeclaredField("mId").apply {
            isAccessible = true
            set(workerParams, UUID.fromString(workId))
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mInputData").apply {
            isAccessible = true
            set(workerParams, inputData)
        }
        androidx.work.WorkerParameters::class.java.getDeclaredField("mRunAttemptCount").apply {
            isAccessible = true
            set(workerParams, 0)
        }

        AutomationActionWorker.clearLedger()
        val worker = AutomationActionWorker(dummyContext(), workerParams)
        worker.messageRepo = messageRepo
        worker.conversationRepo = convRepo
        worker.notificationManager = mockNotificationManager
        worker.automationRepo = autoRepo

        val workerResult = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.failure(), workerResult)

        // 1. Original message MUST be deleted because chain contains ACTION_DELETE
        assertTrue("Message must be deleted on step exception when chain contains ACTION_DELETE",
            messageRepo.deletedMessageIds.contains(8888L))

        // 2. Failed message must record exact step index 0 and action type FORWARD_SMS (not -1 / CHAIN)
        val failedList = autoRepo.getFailedMessagesList()
        assertEquals(1, failedList.size)
        val failed = failedList.first()
        assertEquals(0, failed.failedStepIndex)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, failed.failedActionType)
        assertTrue(failed.errorMessage.contains("Carrier permission denied: SEND_SMS"))

        // 3. Error log must record step failure
        val errorLogs = autoRepo.getLogsList(AutomationLog.TYPE_ERROR)
        assertEquals(1, errorLogs.size)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, errorLogs.first().actionType)
    }

    // ────────────────────────────────────────────────────────────────────
    // 31. Realm Migration v18 -> v19 Table and Field Creation
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testQkRealmMigration_v18ToV19CreatesFailedMessageAndAutomationLogTables() {
        val mockDynamicRealm = Mockito.mock(io.realm.DynamicRealm::class.java)
        val mockSchema = Mockito.mock(io.realm.RealmSchema::class.java)
        val mockFailedSchema = Mockito.mock(io.realm.RealmObjectSchema::class.java)
        val mockLogSchema = Mockito.mock(io.realm.RealmObjectSchema::class.java)

        Mockito.`when`(mockDynamicRealm.schema).thenReturn(mockSchema)
        Mockito.`when`(mockSchema.create("FailedAutomationMessage")).thenReturn(mockFailedSchema)
        Mockito.`when`(mockSchema.create("AutomationLog")).thenReturn(mockLogSchema)

        Mockito.`when`(mockFailedSchema.addField(Mockito.anyString(), Mockito.any(), Mockito.anyVararg())).thenReturn(mockFailedSchema)
        Mockito.`when`(mockLogSchema.addField(Mockito.anyString(), Mockito.any(), Mockito.anyVararg())).thenReturn(mockLogSchema)

        val mockCursor: dev.octoshrimpy.quik.mapper.CursorToContact = Mockito.mock(dev.octoshrimpy.quik.mapper.CursorToContact::class.java)
        val mockPrefs: dev.octoshrimpy.quik.util.Preferences = Mockito.mock(dev.octoshrimpy.quik.util.Preferences::class.java)
        val migration = dev.octoshrimpy.quik.migration.QkRealmMigration(mockCursor, mockPrefs)
        migration.migrate(mockDynamicRealm, 18L, 19L)

        // Verify tables created
        Mockito.verify(mockSchema).create("FailedAutomationMessage")
        Mockito.verify(mockSchema).create("AutomationLog")

        // Verify primary key on FailedAutomationMessage
        Mockito.verify(mockFailedSchema).addField(
            Mockito.eq("id"),
            Mockito.eq(Long::class.java),
            Mockito.eq(io.realm.FieldAttribute.PRIMARY_KEY),
            Mockito.eq(io.realm.FieldAttribute.REQUIRED)
        )

        // Verify primary key on AutomationLog
        Mockito.verify(mockLogSchema).addField(
            Mockito.eq("id"),
            Mockito.eq(Long::class.java),
            Mockito.eq(io.realm.FieldAttribute.PRIMARY_KEY),
            Mockito.eq(io.realm.FieldAttribute.REQUIRED)
        )
    }
}


