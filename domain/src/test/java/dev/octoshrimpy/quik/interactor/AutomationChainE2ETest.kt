package dev.octoshrimpy.quik.interactor

import android.content.Context
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.repository.AutomationRuleRepository
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import io.realm.RealmResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end unit and integration test for automation rule chaining,
 * rate limiting, crash recovery, and multi-action processing pipelines.
 */
class AutomationChainE2ETest {

    class FakeAutomationRuleRepository : AutomationRuleRepository {
        val rules = mutableListOf<AutomationRule>()
        val hitCounts = mutableMapOf<Long, Long>()
        val acquiredSlots = mutableMapOf<Pair<Long, Long>, Long>()
        var rateLimitAllowed: Boolean = true

        override fun getRules(): RealmResults<AutomationRule> = throw UnsupportedOperationException()
        override fun getRule(id: Long): AutomationRule? = rules.find { it.id == id }
        override fun saveRule(rule: AutomationRule) { rules.add(rule) }
        override fun deleteRule(id: Long) { rules.removeAll { it.id == id } }
        override fun getEnabledRules(): List<AutomationRule> = rules.filter { it.enabled }

        override fun tryAcquireReplySlot(ruleId: Long, threadId: Long, rateLimitMs: Long): Boolean {
            if (!rateLimitAllowed) return false
            acquiredSlots[Pair(ruleId, threadId)] = System.currentTimeMillis()
            return true
        }

        override fun incrementHitCount(id: Long) {
            hitCounts[id] = (hitCounts[id] ?: 0L) + 1L
        }

        override fun close() {}
    }

    class FakeContactRepository : ContactRepository {
        val contacts = mutableSetOf<String>()
        override fun isContact(address: String): Boolean = contacts.contains(address)
        override fun findContactUri(address: String) = throw UnsupportedOperationException()
        override fun getContacts(): RealmResults<dev.octoshrimpy.quik.model.Contact> = throw UnsupportedOperationException()
        override fun getUnmanagedContact(lookupKey: String) = null
        override fun getUnmanagedAllContacts() = emptyList<dev.octoshrimpy.quik.model.Contact>()
        override fun getUnmanagedContacts(starred: Boolean) = io.reactivex.Observable.just(emptyList<dev.octoshrimpy.quik.model.Contact>())
        override fun getUnmanagedContactGroups() = io.reactivex.Observable.just(emptyList<dev.octoshrimpy.quik.model.ContactGroup>())
        override fun setDefaultPhoneNumber(lookupKey: String, phoneNumberId: Long) {}
    }

    open class FakeMessageRepository : MessageRepository {
        val deletedMessageIds = mutableListOf<Long>()
        val markedReadThreadIds = mutableListOf<Long>()

        override val deduplicationProgress: io.reactivex.Observable<MessageRepository.DeduplicationProgress>
            get() = io.reactivex.Observable.empty()

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

    class FakeConversationRepository : ConversationRepository {
        val archivedThreadIds = mutableListOf<Long>()

        override fun markArchived(vararg threadIds: Long) {
            archivedThreadIds.addAll(threadIds.toList())
        }

        override fun getConversations(unreadAtTop: Boolean, archived: Boolean) = throw UnsupportedOperationException()
        override fun getConversationsSnapshot(unreadAtTop: Boolean) = emptyList<dev.octoshrimpy.quik.model.Conversation>()
        override fun getTopConversations() = emptyList<dev.octoshrimpy.quik.model.Conversation>()
        override fun setConversationName(id: Long, name: String) = io.reactivex.Completable.complete()
        override fun searchConversations(query: CharSequence) = emptyList<dev.octoshrimpy.quik.model.SearchResult>()
        override fun getBlockedConversations() = throw UnsupportedOperationException()
        override fun getBlockedConversationsAsync() = throw UnsupportedOperationException()
        override fun getConversationAsync(threadId: Long) = throw UnsupportedOperationException()
        override fun getConversation(threadId: Long) = null
        override fun updateSendAsGroup(threadId: Long, sendAsGroup: Boolean): Unit? = Unit
        override fun getUnseenIds(archived: Boolean) = emptyList<Long>()
        override fun getUnreadIds(archived: Boolean) = emptyList<Long>()
        override fun getConversationAndLastSenderContactName(threadId: Long) = null
        override fun getConversations(vararg threadIds: Long) = throw UnsupportedOperationException()
        override fun getUnmanagedConversations() = io.reactivex.Observable.just(emptyList<dev.octoshrimpy.quik.model.Conversation>())
        override fun getRecipients() = throw UnsupportedOperationException()
        override fun getUnmanagedRecipients() = io.reactivex.Observable.just(emptyList<dev.octoshrimpy.quik.model.Recipient>())
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

    @Test
    fun testEmptyRulesReturnsNormal() {
        val autoRepo = FakeAutomationRuleRepository()
        val contactsRepo = FakeContactRepository()
        val messageRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()

        val interactor = ExecuteAutomations(dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo)
        val message = createMessage(1L, 100L, "+1234567890", "Hello world")

        val result = interactor.execute(message)
        assertTrue(result is ExecuteAutomations.ExecutionResult.Normal)
        assertFalse(result.isArchived)
    }

    @Test
    fun testMultiRuleChain_markReadAndArchive() {
        val autoRepo = FakeAutomationRuleRepository()
        val contactsRepo = FakeContactRepository()
        val messageRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()

        // Rule 1: Match sender "BANK" -> Mark Read
        val rule1 = AutomationRule(
            id = 1L,
            name = "Bank Alert Read",
            enabled = true,
            matchSender = "BANK",
            action = AutomationRule.ACTION_MARK_READ
        )

        // Rule 2: Match body containing "OTP" -> Archive
        val rule2 = AutomationRule(
            id = 2L,
            name = "OTP Archive",
            enabled = true,
            matchBody = "OTP",
            action = AutomationRule.ACTION_ARCHIVE
        )

        autoRepo.rules.addAll(listOf(rule1, rule2))

        val interactor = ExecuteAutomations(dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo)
        val message = createMessage(10L, 200L, "MYBANK-SMS", "Your secret OTP is 591243")

        val result = interactor.execute(message)

        // Verify rule 1 fired
        assertEquals(listOf(200L), messageRepo.markedReadThreadIds)
        assertEquals(1L, autoRepo.hitCounts[1L])

        // Verify rule 2 fired
        assertEquals(listOf(200L), convRepo.archivedThreadIds)
        assertEquals(1L, autoRepo.hitCounts[2L])

        // Verify result state
        assertTrue(result.isArchived)
        assertTrue(result is ExecuteAutomations.ExecutionResult.Normal)
    }

    @Test
    fun testRuleChain_deleteThenArchive_snapshotPreservesExecution() {
        val autoRepo = FakeAutomationRuleRepository()
        val contactsRepo = FakeContactRepository()
        val messageRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()

        // Rule 1: Delete spam messages
        val rule1 = AutomationRule(
            id = 101L,
            name = "Spam Delete",
            enabled = true,
            matchBody = "Claim your free prize",
            action = AutomationRule.ACTION_DELETE
        )

        // Rule 2: Archive thread even after deletion
        val rule2 = AutomationRule(
            id = 102L,
            name = "Spam Archive",
            enabled = true,
            matchSender = "SPAMMER",
            action = AutomationRule.ACTION_ARCHIVE
        )

        autoRepo.rules.addAll(listOf(rule1, rule2))

        val interactor = ExecuteAutomations(dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo)
        val message = createMessage(42L, 500L, "SPAMMER_BOT", "Claim your free prize today!")

        val result = interactor.execute(message)

        // Message should be deleted
        assertEquals(listOf(42L), messageRepo.deletedMessageIds)
        // Thread should still be archived
        assertEquals(listOf(500L), convRepo.archivedThreadIds)
        // Result should be Dropped and Archived
        assertTrue(result is ExecuteAutomations.ExecutionResult.Dropped)
        assertTrue(result.isArchived)
    }

    @Test
    fun testSkipContactsPreventsExecution() {
        val autoRepo = FakeAutomationRuleRepository()
        val contactsRepo = FakeContactRepository().apply {
            contacts.add("+1987654321")
        }
        val messageRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()

        val rule = AutomationRule(
            id = 1L,
            name = "Ignore Contacts",
            enabled = true,
            matchBody = "test",
            skipContacts = true,
            action = AutomationRule.ACTION_MARK_READ
        )
        autoRepo.rules.add(rule)

        val interactor = ExecuteAutomations(dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo)
        val message = createMessage(1L, 100L, "+1987654321", "test message")

        interactor.execute(message)

        assertEquals(0, messageRepo.markedReadThreadIds.size)
        assertEquals(0L, autoRepo.hitCounts[1L] ?: 0L)
    }

    @Test
    fun testAutoReplyRateLimitingGating() {
        val autoRepo = FakeAutomationRuleRepository().apply {
            rateLimitAllowed = false // Simulate rate-limited state
        }
        val contactsRepo = FakeContactRepository()
        val messageRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()

        val rule = AutomationRule(
            id = 5L,
            name = "Rate Limited Auto Reply",
            enabled = true,
            matchBody = "Help",
            action = AutomationRule.ACTION_AUTO_REPLY,
            replyRateLimitMs = 60000L
        )
        autoRepo.rules.add(rule)

        val interactor = ExecuteAutomations(dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo)
        val message = createMessage(1L, 300L, "+15551234", "Help needed")

        interactor.execute(message)

        // Rate limited: hit count should NOT be incremented
        assertEquals(0L, autoRepo.hitCounts[5L] ?: 0L)
    }

    @Test
    fun testUnicodeAndEmojiRegexMatching() {
        val input = "Security code: \uD83D\uDD12 849201 for verification"
        assertTrue(ExecuteAutomations.safeRegexMatch("[0-9]{6}", false, input))
        assertTrue(ExecuteAutomations.safeRegexMatch("\uD83D\uDD12", false, input))
    }

    @Test
    fun testSafeRegexMatchWithCatastrophicBacktrackingPattern() {
        // Evil regex that would hang a naive regex engine
        val evilPattern = "(a+)+b"
        val evilInput = "a".repeat(50) + "c"
        // Should time out safely and return false without throwing or hanging
        assertFalse(ExecuteAutomations.safeRegexMatch(evilPattern, false, evilInput))
    }

    @Test
    fun testRuleChain_exceptionInFirstRuleDoesNotAbortSubsequentRules() {
        val autoRepo = FakeAutomationRuleRepository()
        val contactsRepo = FakeContactRepository()
        val messageRepo = object : FakeMessageRepository() {
            override fun markRead(threadIds: Collection<Long>): Int {
                throw RuntimeException("Simulated unexpected database corruption/failure")
            }
        }
        val convRepo = FakeConversationRepository()

        // Rule 1: Attempt to mark read (will trigger simulated database failure)
        val rule1 = AutomationRule(
            id = 201L,
            name = "Failing Mark Read",
            enabled = true,
            matchSender = "ALERT",
            action = AutomationRule.ACTION_MARK_READ
        )

        // Rule 2: Archive thread (must still execute despite Rule 1 crashing)
        val rule2 = AutomationRule(
            id = 202L,
            name = "Resilient Archive",
            enabled = true,
            matchBody = "Emergency",
            action = AutomationRule.ACTION_ARCHIVE
        )

        autoRepo.rules.addAll(listOf(rule1, rule2))

        val interactor = ExecuteAutomations(dummyContext(), autoRepo, contactsRepo, messageRepo, convRepo)
        val message = createMessage(99L, 777L, "ALERT_SERVICE", "Emergency notification content")

        // Interactor must not throw and must complete remaining pipeline
        val result = interactor.execute(message)

        // Rule 1 hit was registered before action execution failed
        assertEquals(1L, autoRepo.hitCounts[201L])
        // Rule 2 must have succeeded despite Rule 1 exception
        assertEquals(listOf(777L), convRepo.archivedThreadIds)
        assertEquals(1L, autoRepo.hitCounts[202L])
        assertTrue(result.isArchived)
    }
}
