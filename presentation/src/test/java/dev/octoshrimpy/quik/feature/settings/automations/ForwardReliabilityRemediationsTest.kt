package dev.octoshrimpy.quik.feature.settings.automations

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SubscriptionManager
import dev.octoshrimpy.quik.interactor.ExecuteAutomations
import dev.octoshrimpy.quik.model.AutomationDiag
import dev.octoshrimpy.quik.model.AutomationRule
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.manager.ActiveConversationManager
import dev.octoshrimpy.quik.manager.KeyManager
import dev.octoshrimpy.quik.mapper.CursorToMessage
import dev.octoshrimpy.quik.mapper.CursorToPart
import dev.octoshrimpy.quik.receiver.SmsReceivedReceiver
import dev.octoshrimpy.quik.repository.AutomationRuleRepositoryImpl
import dev.octoshrimpy.quik.repository.EmojiReactionRepository
import dev.octoshrimpy.quik.repository.MessageRepositoryImpl
import dev.octoshrimpy.quik.repository.SyncRepository
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.Preferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class ForwardReliabilityRemediationsTest {

    // ────────────────────────────────────────────────────────────────────
    // 1. Cooldown Zero-Disabling Bug Remediation
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testForwardRateLimitZeroDisablesCooldownInRepo() {
        val mockRealm = Mockito.mock(io.realm.Realm::class.java)
        var savedRule: AutomationRule? = null

        Mockito.doAnswer { invocation ->
            val transaction = invocation.arguments[0] as io.realm.Realm.Transaction
            transaction.execute(mockRealm)
            null
        }.`when`(mockRealm).executeTransaction(Mockito.any())

        @Suppress("UNCHECKED_CAST")
        val query = Mockito.mock(io.realm.RealmQuery::class.java) as io.realm.RealmQuery<AutomationRule>
        Mockito.`when`(mockRealm.where(AutomationRule::class.java)).thenReturn(query)
        Mockito.`when`(query.equalTo(Mockito.anyString(), Mockito.anyLong())).thenReturn(query)
        Mockito.`when`(query.findFirst()).thenReturn(null)

        Mockito.`when`(mockRealm.copyToRealmOrUpdate(Mockito.any(AutomationRule::class.java))).thenAnswer { invocation ->
            val r = invocation.arguments[0] as AutomationRule
            savedRule = r
            r
        }

        val repo = AutomationRuleRepositoryImpl { mockRealm }

        // Save rule with forwardRateLimitMs = 0L (cooldown disabled)
        val rule = AutomationRule(
            id = 501L,
            name = "Zero Cooldown Rule",
            enabled = true,
            forwardRateLimitMs = 0L
        )
        repo.saveRule(rule)

        assertNotNull(savedRule)
        assertEquals("forwardRateLimitMs = 0L must be preserved to allow disabling cooldown",
            0L, savedRule!!.forwardRateLimitMs)
    }

    // ────────────────────────────────────────────────────────────────────
    // 2. False-Positive Self-Forward Loop Remediation
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testSelfForwardLoopDoesNotFlagOtpMentioningOwnNumber() {
        val ownNumber = "+15559876543"
        val targetPhone = "+15551112222"

        // Incoming bank OTP mentioning own phone number in body MUST NOT be flagged as loop
        val bankAlert = "Dear customer, OTP for your transaction on card ending 1234 linked to 5559876543 is 482910."
        val isLoop = ExecuteAutomations.isSelfForwardLoop(bankAlert, targetPhone, ownNumber, "HDFCBK")
        assertFalse("Bank OTP mentioning own phone number must NOT be flagged as self-forward loop", isLoop)

        // Incoming SMS from own number or target MUST be flagged as loop
        assertTrue("Sender matching own number must be flagged as loop",
            ExecuteAutomations.isSelfForwardLoop("Test message", targetPhone, ownNumber, ownNumber))
        assertTrue("Sender matching target phone must be flagged as loop",
            ExecuteAutomations.isSelfForwardLoop("Test message", targetPhone, ownNumber, targetPhone))

        // Target phone matching own number (forwarding to self) MUST be flagged as loop
        assertTrue("Target matching own number must be flagged as loop",
            ExecuteAutomations.isSelfForwardLoop("Test message", ownNumber, ownNumber, "+15553334444"))

        // Body containing forwarded header "From: $ownNumber" MUST be flagged as loop
        assertTrue("Body with From: ownNumber must be flagged as loop",
            ExecuteAutomations.isSelfForwardLoop("From: +15559876543 | Hello", targetPhone, ownNumber, "+15553334444"))
    }

    // ────────────────────────────────────────────────────────────────────
    // 3. Shortcode & OTP Forwarding Gating Remediation
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testShortcodeAndOtpAllowedUnderWildcardRule() {
        val wildcardRule = AutomationRule(
            id = 601L,
            name = "All SMS Forwarder",
            enabled = true,
            matchSender = "" // Wildcard (all senders)
        )

        // Standard 5-6 digit shortcode OTP must be whitelisted under wildcard rule
        assertTrue("Shortcode OTP sender must be whitelisted under wildcard rule",
            ExecuteAutomations.isSenderWhitelisted("555123", listOf(wildcardRule), "Your OTP is 123456"))

        // Alphanumeric banking sender must be whitelisted under wildcard rule
        assertTrue("Alphanumeric sender must be whitelisted under wildcard rule",
            ExecuteAutomations.isSenderWhitelisted("VM-HDFCBK", listOf(wildcardRule), "Rs 500 debited from acct"))

        // Bank transaction alert mentioning 'Your balance is' from bank name MUST be whitelisted and NOT falsely flagged as carrier feedback
        assertTrue("Bank alert with 'balance is' from CHASE must be whitelisted under wildcard rule",
            ExecuteAutomations.isSenderWhitelisted("CHASE", listOf(wildcardRule), "Your account was debited $50. Your balance is $1,250.00"))

        // Carrier balance feedback message from carrier customer care (121) MUST be rejected to prevent feedback loops
        assertFalse("Carrier balance feedback SMS from 121 must be rejected under wildcard rule",
            ExecuteAutomations.isSenderWhitelisted("121", listOf(wildcardRule), "Your carrier balance is low"))

        // But when explicitly whitelisted, carrier message passes
        ExecuteAutomations.whitelistedSenders.add("121")
        assertTrue("Explicitly whitelisted carrier number must pass",
            ExecuteAutomations.isSenderWhitelisted("121", listOf(wildcardRule), "Your carrier balance is low"))
        ExecuteAutomations.whitelistedSenders.remove("121")
    }

    // ────────────────────────────────────────────────────────────────────
    // 4. Multi-SIM subId Extraction Remediation
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testMultiSimSubIdExtractionFromVariousBundleTypes() {
        // Intent with EXTRA_SUBSCRIPTION_INDEX (standard Android)
        val intentIndex = Mockito.mock(Intent::class.java)
        val extrasIndex = Mockito.mock(Bundle::class.java)
        Mockito.`when`(intentIndex.extras).thenReturn(extrasIndex)
        Mockito.`when`(extrasIndex.get(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)).thenReturn(1)
        assertEquals(1, SmsReceivedReceiver.extractSubId(intentIndex))

        // Intent with Long extra for subscription (some Qualcomm/MediaTek chipsets)
        val intentLong = Mockito.mock(Intent::class.java)
        val extrasLong = Mockito.mock(Bundle::class.java)
        Mockito.`when`(intentLong.extras).thenReturn(extrasLong)
        Mockito.`when`(extrasLong.get("subscription")).thenReturn(2L)
        assertEquals(2, SmsReceivedReceiver.extractSubId(intentLong))

        // Intent with String representation of subscription ID
        val intentString = Mockito.mock(Intent::class.java)
        val extrasString = Mockito.mock(Bundle::class.java)
        Mockito.`when`(intentString.extras).thenReturn(extrasString)
        Mockito.`when`(extrasString.get("sub_id")).thenReturn("0")
        assertEquals(0, SmsReceivedReceiver.extractSubId(intentString))

        // Intent with null extras should safely return -1
        val intentNull = Mockito.mock(Intent::class.java)
        Mockito.`when`(intentNull.extras).thenReturn(null)
        assertEquals(-1, SmsReceivedReceiver.extractSubId(intentNull))
    }

    // ────────────────────────────────────────────────────────────────────
    // 5. Suppression Expiry Watchdog Remediation
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testSuppressionExpiryWatchdogResetsZeroExpiryMessagesOlderThan60s() {
        val mockRealm = Mockito.mock(io.realm.Realm::class.java)
        val now = System.currentTimeMillis()

        // Message with suppressionExpiresAt == 0L and date older than 60s
        val legacySuppressedMsg = Message().apply {
            id = 7001L
            threadId = 301L
            pendingSuppression = true
            suppressionExpiresAt = 0L
            date = now - 90_000L // 90 seconds old
        }

        @Suppress("UNCHECKED_CAST")
        val messageQuery = Mockito.mock(io.realm.RealmQuery::class.java) as io.realm.RealmQuery<Message>
        val realmResults = Mockito.mock(io.realm.RealmResults::class.java) as io.realm.RealmResults<Message>

        Mockito.`when`(mockRealm.where(Message::class.java)).thenReturn(messageQuery)
        Mockito.`when`(messageQuery.equalTo(Mockito.anyString(), Mockito.anyBoolean())).thenReturn(messageQuery)
        Mockito.`when`(messageQuery.equalTo(Mockito.anyString(), Mockito.anyLong())).thenReturn(messageQuery)
        Mockito.`when`(messageQuery.beginGroup()).thenReturn(messageQuery)
        Mockito.`when`(messageQuery.greaterThan(Mockito.anyString(), Mockito.anyLong())).thenReturn(messageQuery)
        Mockito.`when`(messageQuery.lessThanOrEqualTo(Mockito.anyString(), Mockito.anyLong())).thenReturn(messageQuery)
        Mockito.`when`(messageQuery.or()).thenReturn(messageQuery)
        Mockito.`when`(messageQuery.endGroup()).thenReturn(messageQuery)
        Mockito.`when`(messageQuery.findAll()).thenReturn(realmResults)

        Mockito.`when`(realmResults.isEmpty()).thenReturn(false)
        Mockito.`when`(realmResults.iterator()).thenAnswer { mutableListOf(legacySuppressedMsg).iterator() }

        Mockito.doAnswer { invocation ->
            val transaction = invocation.arguments[0] as io.realm.Realm.Transaction
            transaction.execute(mockRealm)
            null
        }.`when`(mockRealm).executeTransaction(Mockito.any())

        val repo = MessageRepositoryImpl(
            Mockito.mock(ActiveConversationManager::class.java),
            Mockito.mock(Context::class.java),
            Mockito.mock(KeyManager::class.java),
            Mockito.mock(PhoneNumberUtils::class.java),
            Mockito.mock(Preferences::class.java),
            Mockito.mock(SyncRepository::class.java),
            Mockito.mock(EmojiReactionRepository::class.java),
            Mockito.mock(CursorToMessage::class.java),
            Mockito.mock(CursorToPart::class.java)
        ).apply {
            this.realmProvider = { mockRealm }
        }

        // Execute recovery
        val recovered = repo.resetOrphanedSuppressedMessages()

        assertFalse("Orphaned message with suppressionExpiresAt = 0L older than 60s must have pendingSuppression reset to false",
            legacySuppressedMsg.pendingSuppression)
        assertEquals(0L, legacySuppressedMsg.suppressionExpiresAt)
        assertEquals(listOf(301L), recovered)
    }

    // ────────────────────────────────────────────────────────────────────
    // 6. AutomationDiag JSON Formatting
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testAutomationDiagSerialization() {
        val json = AutomationDiag.json(
            "stage" to "forward_send",
            "destination" to "+15551234567",
            "attempt" to 1,
            "nullField" to null
        )

        assertTrue(json.contains("\"stage\":\"forward_send\""))
        assertTrue(json.contains("\"destination\":\"+15551234567\""))
        assertTrue(json.contains("\"attempt\":1"))
        assertFalse("Null values should be omitted from AutomationDiag.json", json.contains("nullField"))
    }

    // ────────────────────────────────────────────────────────────────────
    // 7. Prevent Silent Message Loss on Forward Declines/Skips (Delayed Rules)
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testAntiCascadeFilterStripsActionDeleteForDelayedRuleWhenForwardRejected() {
        val mockContext = Mockito.mock(Context::class.java)
        val mockAutomationRepo = Mockito.mock(dev.octoshrimpy.quik.repository.AutomationRuleRepository::class.java)
        val mockContactsRepo = Mockito.mock(dev.octoshrimpy.quik.repository.ContactRepository::class.java)
        val mockMessageRepo = Mockito.mock(dev.octoshrimpy.quik.repository.MessageRepository::class.java)
        val mockConversationRepo = Mockito.mock(dev.octoshrimpy.quik.repository.ConversationRepository::class.java)

        val interactor = ExecuteAutomations(
            mockContext,
            mockAutomationRepo,
            mockContactsRepo,
            mockMessageRepo,
            mockConversationRepo,
            null
        )

        // Delayed Rule (>0s) with FORWARD_SMS and ACTION_DELETE
        val rule = AutomationRule(
            id = 801L,
            name = "Delayed Forward & Delete Rule",
            enabled = true,
            matchSender = "",
            delayMs = 30_000L
        )
        val steps = listOf(
            dev.octoshrimpy.quik.model.AutomationActionStep(
                action = AutomationRule.ACTION_FORWARD_SMS,
                forwardToPhone = "+15559998888"
            ),
            dev.octoshrimpy.quik.model.AutomationActionStep(
                action = AutomationRule.ACTION_DELETE
            )
        )
        rule.setActionSteps(steps)

        Mockito.`when`(mockAutomationRepo.getEnabledRules()).thenReturn(listOf(rule))
        Mockito.`when`(mockContactsRepo.isContact(Mockito.anyString())).thenReturn(false)

        // Incoming carrier balance loop message from "121": forward is rejected.
        // For delayed rules (>0s), ACTION_DELETE must be stripped so the original message is NOT deleted!
        val incomingCarrierMsg = Message().apply {
            id = 9001L
            threadId = 401L
            address = "121"
            body = "Your carrier balance is low"
        }

        val result = interactor.execute(incomingCarrierMsg)
        // Since forward is rejected and delete is stripped on delayed rules, normal result, not dropped
        assertTrue("Result must be Normal when forward was rejected on delayed rule", result is ExecuteAutomations.ExecutionResult.Normal)
        assertFalse("Message must not be dropped/deleted when forward was rejected on delayed rule", result is ExecuteAutomations.ExecutionResult.Dropped)
        Mockito.verify(mockMessageRepo, Mockito.never()).deleteMessages(listOf(9001L))
    }

    // ────────────────────────────────────────────────────────────────────
    // 8. Zero-Second Rules with ACTION_DELETE MUST ALWAYS Delete on Forward Failure/Skip
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testZeroSecondRuleWithDeleteAlwaysDeletesOnForwardRejected() {
        val mockContext = Mockito.mock(Context::class.java)
        val mockAutomationRepo = Mockito.mock(dev.octoshrimpy.quik.repository.AutomationRuleRepository::class.java)
        val mockContactsRepo = Mockito.mock(dev.octoshrimpy.quik.repository.ContactRepository::class.java)
        val mockMessageRepo = Mockito.mock(dev.octoshrimpy.quik.repository.MessageRepository::class.java)
        val mockConversationRepo = Mockito.mock(dev.octoshrimpy.quik.repository.ConversationRepository::class.java)

        val interactor = ExecuteAutomations(
            mockContext,
            mockAutomationRepo,
            mockContactsRepo,
            mockMessageRepo,
            mockConversationRepo,
            null
        )

        // Immediate 0-second rule with FORWARD_SMS and ACTION_DELETE
        val rule = AutomationRule(
            id = 802L,
            name = "Immediate Forward & Delete Rule",
            enabled = true,
            matchSender = "",
            delayMs = 0L
        )
        val steps = listOf(
            dev.octoshrimpy.quik.model.AutomationActionStep(
                action = AutomationRule.ACTION_FORWARD_SMS,
                forwardToPhone = "+15559998888"
            ),
            dev.octoshrimpy.quik.model.AutomationActionStep(
                action = AutomationRule.ACTION_DELETE
            )
        )
        rule.setActionSteps(steps)

        Mockito.`when`(mockAutomationRepo.getEnabledRules()).thenReturn(listOf(rule))
        Mockito.`when`(mockContactsRepo.isContact(Mockito.anyString())).thenReturn(false)

        val incomingCarrierMsg = Message().apply {
            id = 9002L
            threadId = 402L
            address = "121"
            body = "Your carrier balance is low"
        }

        val result = interactor.execute(incomingCarrierMsg)
        // 0-second rule with ACTION_DELETE must NEVER surface to inbox -> Dropped result!
        assertTrue("0-second rule with delete MUST drop notification & inbox surfacing", result is ExecuteAutomations.ExecutionResult.Dropped)
        // Message MUST still be deleted from message repository!
        Mockito.verify(mockMessageRepo).deleteMessages(listOf(9002L))
        // Failed forward MUST be recorded in FailedAutomationMessage for inspectability and retry!
        val captor = org.mockito.ArgumentCaptor.forClass(dev.octoshrimpy.quik.model.FailedAutomationMessage::class.java)
        Mockito.verify(mockAutomationRepo).saveFailedMessage(capture(captor, dev.octoshrimpy.quik.model.FailedAutomationMessage()))
        assertEquals(9002L, captor.value.messageId)
        assertEquals(AutomationRule.ACTION_FORWARD_SMS, captor.value.failedActionType)
    }

    // ────────────────────────────────────────────────────────────────────
    // 9. Multi-Rule Scoping: Immediate rule without delete does not trigger delete on delayed rule forward skip
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun testMultiRule_immediateNonDeleteDoesNotTriggerDeleteOnDelayedForwardSkip() {
        val mockContext = Mockito.mock(Context::class.java)
        val mockAutomationRepo = Mockito.mock(dev.octoshrimpy.quik.repository.AutomationRuleRepository::class.java)
        val mockContactsRepo = Mockito.mock(dev.octoshrimpy.quik.repository.ContactRepository::class.java)
        val mockMessageRepo = Mockito.mock(dev.octoshrimpy.quik.repository.MessageRepository::class.java)
        val mockConversationRepo = Mockito.mock(dev.octoshrimpy.quik.repository.ConversationRepository::class.java)

        val interactor = ExecuteAutomations(
            mockContext,
            mockAutomationRepo,
            mockContactsRepo,
            mockMessageRepo,
            mockConversationRepo,
            null
        )

        // Rule 1: Immediate rule (delayMs = 0L) with MARK_READ only (NO delete)
        val rule1 = AutomationRule(
            id = 803L,
            name = "Immediate Mark Read Rule",
            enabled = true,
            matchSender = "",
            delayMs = 0L
        ).apply {
            setActionSteps(listOf(
                dev.octoshrimpy.quik.model.AutomationActionStep(
                    action = AutomationRule.ACTION_MARK_READ
                )
            ))
        }

        // Rule 2: Delayed rule (delayMs = 30_000L) with FORWARD_SMS and ACTION_DELETE
        val rule2 = AutomationRule(
            id = 804L,
            name = "Delayed Forward & Delete Rule",
            enabled = true,
            matchSender = "",
            delayMs = 30_000L
        ).apply {
            setActionSteps(listOf(
                dev.octoshrimpy.quik.model.AutomationActionStep(
                    action = AutomationRule.ACTION_FORWARD_SMS,
                    forwardToPhone = "+15559998888"
                ),
                dev.octoshrimpy.quik.model.AutomationActionStep(
                    action = AutomationRule.ACTION_DELETE
                )
            ))
        }

        Mockito.`when`(mockAutomationRepo.getEnabledRules()).thenReturn(listOf(rule1, rule2))
        Mockito.`when`(mockContactsRepo.isContact(Mockito.anyString())).thenReturn(false)

        val incomingCarrierMsg = Message().apply {
            id = 9003L
            threadId = 403L
            address = "121"
            body = "Your carrier balance is low"
        }

        val result = interactor.execute(incomingCarrierMsg)

        // Because Rule 2 (the rule with delete) has delay 30s, delete MUST be stripped and message preserved!
        assertTrue("Result must be Normal when delete belongs to a delayed rule", result is ExecuteAutomations.ExecutionResult.Normal)
        assertFalse("Message must NOT be dropped when delete belongs to a delayed rule", result is ExecuteAutomations.ExecutionResult.Dropped)
        Mockito.verify(mockMessageRepo, Mockito.never()).deleteMessages(listOf(9003L))
    }

    private fun <T : Any> capture(captor: org.mockito.ArgumentCaptor<T>, dummy: T): T {
        captor.capture()
        return dummy
    }
}
