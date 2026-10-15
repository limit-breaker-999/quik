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
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.AutomationStepHelper
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
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPipelineE2ETest {

    // ── Fake Repositories ───────────────────────────────────────────────

    class FakeAutomationRuleRepo : AutomationRuleRepository {
        val rules = mutableListOf<AutomationRule>()
        val hitCounts = mutableMapOf<Long, Long>()

        override fun getRules(): RealmResults<AutomationRule> = throw UnsupportedOperationException()
        override fun getRule(id: Long): AutomationRule? = rules.find { it.id == id }
        override fun saveRule(rule: AutomationRule) {
            rules.removeAll { it.id == rule.id && rule.id != 0L }
            rules.add(rule)
        }
        override fun deleteRule(id: Long) { rules.removeAll { it.id == id } }
        override fun getEnabledRules(): List<AutomationRule> = rules.filter { it.enabled }
        override fun tryAcquireReplySlot(ruleId: Long, threadId: Long, rateLimitMs: Long): Boolean = true
        override fun incrementHitCount(id: Long) {
            hitCounts[id] = (hitCounts[id] ?: 0L) + 1L
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
        override fun sendNewMessages(subId: Int, toAddresses: Collection<String>, body: String, attachments: Collection<dev.octoshrimpy.quik.model.Attachment>, sendAsGroup: Boolean, delayMs: Int) = emptyList<Message>()
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
        assertEquals(AutomationRule.ACTION_DELETE, fixed[2].action)
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
}
