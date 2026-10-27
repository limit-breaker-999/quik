# QUIK Comprehensive Architectural Master Blueprint & Deep Pipeline Investigation Report (V2)

**Document ID**: `QUIK-ARCH-2026-M3-V2-CRITICAL-REVIEW`  
**Classification**: Technical Architectural Blueprint & Skeptical Codebase Investigation  
**Working Branch**: `feature/material3-redesign-v2` (Base commit: `9e6b7240`)  
**Preserved Documentation Branch**: `docs/message-pipeline-architecture` (Head: `17080667`)  
**Target Codebase**: `/home/ubuntu/quik` (`dev.octoshrimpy.quik` / legacy `com.moez.QKSMS`)  
**Date**: October 26, 2026  

---

## 1. Executive Summary & Critical Review of Prior Findings

An independent, highly skeptical investigation was conducted to scrutinize the prior findings, verify the actual codebase state on `feature/material3-redesign-v2` (synchronized with `origin/master` at commit `9e6b7240`), and resolve the fundamental defects in incoming message processing, automation interception, and outbound SMS delivery reliability.

### 1.1 Discrepancy & Correction Register

The table below documents every discrepancy identified between the prior investigator's claims and the actual codebase:

| # | Prior Investigator Claim | Evidence Cited | Actual Code Reality | Corrected Finding / Remediation |
|---|---|---|---|---|
| **1** | `Colors.kt:81` has `minimumContrastRatio = 2.0f` and violates WCAG AA; must be updated to `4.5f`. | `presentation/src/main/java/com/moez/QKSMS/common/util/Colors.kt:81` | Line 81 **already reads**: `private val minimumContrastRatio = 4.5`. | Updated in commit `ad1e9585` on Oct 16, 2026. The contrast ratio is already compliant. The real UI work is completing Monet container tokens and edge-to-edge window insets. |
| **2** | `tryAcquireActionSlot` in `ExecuteAutomations.kt` caused multi-request forward failures due to key collisions on `"$kind:$ruleId:$threadId"`. | `domain/src/main/java/com/moez/QKSMS/interactor/ExecuteAutomations.kt` | `tryAcquireActionSlot` **does not exist** on `feature/material3-redesign-v2`! The branch only has `tryAcquireReplySlot` (gating `ACTION_AUTO_REPLY`). | `tryAcquireActionSlot` was only present on the experimental `docs/message-pipeline-architecture` branch (commit `82f9c030`). On the target branch, forwards have zero domain rate limiting; failures when sending 3–4 times in 15 minutes stem from OS `SmsUsageMonitor`, carrier SMSC limits, modem RIL queue collision, and premature deletion. |
| **3** | Repeatedly cited packages as `com.moez.QKSMS.*`. | File paths in directory tree. | Directories retain `com/moez/QKSMS/`, but Kotlin package headers are **`dev.octoshrimpy.quik.*`**. | All class definitions, Dagger injections, and reflection strings (e.g., `WORKER_CLASS = "dev.octoshrimpy.quik.worker.AutomationActionWorker"`) must strictly match `dev.octoshrimpy.quik.*`. |
| **4** | "Suppress-First" design: add `pendingSuppression`, filter `getUnreadUnseenMessages` and `lastMessage`, but *"keep thread-list queries unchanged (message visible in thread body, never in notification)"*. | OpenSpec task 2.2 / `docs/architecture/01-message-lifecycle.md` / `Message.kt:83-86`. | Leaves suppressed messages visible in `getMessagesBase` (`MessagesAdapter`). When deleted, the message disappears before the user's eyes in the conversation view. | Directly violates user requirement: *"it won't ever land in the inbox... First it will do everything behind the surface"*. `pendingSuppression` must filter `getMessagesBase` AND `ConversationRepositoryImpl`, backed by a 60-second TTL watchdog to prevent permanent loss on crash. |
| **5** | Identified premature delete in `AutomationActionWorker.kt`, but treated it as a simple exception-handling defect in `QkTransaction.sendSmsMessage`. | `QkTransaction.kt:347-356` | Multi-part SMS PDUs trigger multiple `sentPI` callbacks in `MessageSentReceiver.kt:66-75`. There is no reassembly tracking. If part 1 succeeds and part 2 fails, `markSent` and `markFailed` race. | Forwarding must be a two-phase state machine: Phase 1 dispatches and awaits carrier delivery acknowledgement via `MessageSentReceiver`. Only when all parts report `RESULT_OK` does Phase 2 execute deletion. |
| **6** | Noted `SmsUsageMonitor` 30 SMS / 30 min limit, but omitted background execution mechanics. | AOSP `SmsUsageMonitor.java` | When the limit is reached, Android OS raises a system modal dialog (`sms_control_message`). Because `AutomationActionWorker` runs in a background thread, the dialog is never shown/confirmed; the OS rejects the send with `RESULT_ERROR_LIMIT_EXCEEDED` (code 5). | Implement an `OutboundSmsTracker` in Realm to track rolling 30-minute sends (capped at 25 SMS) and a serial pacing queue (2.0s gap) so the baseband radio is never overrun and the dialog is never triggered. |
| **7** | Expressed uncertainty in "Remaining Questions" regarding Android 14 expedited job quotas. | `setExpedited()` quotas | `ExecuteAutomations.kt:296-301` **does not even call `setExpedited()`**! It schedules a standard `OneTimeWorkRequest` with zero network constraints. | Under Doze mode, standard workers are deferred 15–30 minutes, keeping messages suppressed/delayed. Immediate automation chains (`delayMs == 0`) must use `.setExpedited()`, while HTTP forwards must require `NetworkType.CONNECTED`. |
| **8** | Omitted risk of orphaned `pendingSuppression` messages on app crash / kill. | `Message.kt` | If the process dies while `pendingSuppression == true`, the message remains hidden from unread counts, notifications, and inbox snippets permanently. | A fail-safe TTL recovery mechanism is required at app startup, in `HousekeepingWorker`, and in `BootReceiver`: messages with `pendingSuppression == true` older than 3 minutes must automatically reset to `false` and trigger notification update. |
| **9** | Claimed `BootReceiver` only reschedules `ScheduledMessage`. | `BootReceiver.kt:28-39` | Verified correct. In `MessageRepositoryImpl.kt:632`, delayed SMS (`delayMs > 0`) uses `AlarmManager.setExactAndAllowWhileIdle(...)` without rescheduling on boot. | Delayed SMS messages must be registered in Realm and rescheduled on `ACTION_BOOT_COMPLETED`. |

---

## Section 1: UI/UX & Material 3 Architecture & Guidelines

### 1.1 UI Layer Architecture Overview
QUIK utilizes a hybrid UI model:
- **Root Windows**: Standard `QkThemedActivity` (`AppCompatActivity`) for `MainActivity.kt` (conversation list) and `ComposeActivity.kt` (conversation thread).
- **Navigation & Subscreens**: Conductor-based `QkController` instances inside `ContainerActivity.kt` for Settings, Blocking, and Automations.
- **Material 3 Foundation**: Upgraded to `Theme.Material3.DayNight.NoActionBar` with dynamic theming support (Monet) via `DynamicColors.applyToActivitiesIfAvailable()`.

### 1.2 Material 3 Expressive Tokens & Semantic Roles
To provide a modern, tactile interface consistent with Google Messages:

```
                                MATERIAL 3 DESIGN TOKENS
 ┌─────────────────────────┬──────────────────────────┬──────────────────────────┬──────────────────────────┐
 │ Semantic Role           │ Light Mode (Day)         │ Dark Mode (Night)        │ AMOLED Black Mode        │
 ├─────────────────────────┼──────────────────────────┼──────────────────────────┼──────────────────────────┤
 │ ?android:windowBackground│ #FEF7FF (Surface)        │ #141218 (Surface)        │ #000000 (Pure Black)     │
 │ ?attr/colorSurface      │ #FEF7FF                  │ #141218                  │ #000000                  │
 │ Surface Container Low   │ #F7F2FA                  │ #1D1B20                  │ #121214 (Subtle Card)    │
 │ Surface Container High  │ #ECE6F0 (Search/Bubbles) │ #2B2930                  │ #1C1B1F                  │
 │ Primary Container       │ #EADDFF (Outbound Msg)   │ #4F378B                  │ #381E72                  │
 │ On Primary Container    │ #21005D (Outbound Text)  │ #EADDFF                  │ #EADDFF                  │
 │ Inbound Msg Bubble      │ #ECE6F0 (Neutral)        │ #2B2930                  │ #1E1E22                  │
 │ Inbound Msg Text        │ #1D1B20                  │ #E6E0E9                  │ #FFFFFF                  │
 │ Card Stroke / Divider   │ #CAC4D0 (Outline Variant)│ #49454F                  │ #2B2930 (1dp outline)    │
 └─────────────────────────┴──────────────────────────┴──────────────────────────┴──────────────────────────┘
```

- **Pill Search Bar**: 56dp height unified container housing navigation drawer toggle, search query input, and quick-access profile icon.
- **AMOLED Black Support**: High-contrast pure black background (`#000000`) paired with subtle `1dp` container outlines (`?attr/colorOutlineVariant`).
- **Edge-to-Edge System Insets**: Attach `ViewCompat.setOnApplyWindowInsetsListener` across root layouts in `MainActivity`, `ComposeActivity`, and `ContainerActivity` to handle status bar, cutout, and gesture navigation bar insets cleanly.
- **Conductor Lifecycle Cleanliness**: Prevent multiple subscription accumulation across view detach/re-attach in `AutomationsController.kt` and `EditAutomationController.kt` by binding disposables to `view.scope()` or clearing on `onDestroyView()`.

---

## Section 2: Data Management, Persistence (Realm/Telephony) & Atomic State Machine

### 2.1 Dual Persistence Architecture: Realm vs. Telephony Provider
QUIK operates as a default SMS/MMS application on Android, requiring dual persistence:
1. **System Telephony Provider (`content://sms`, `content://mms`)**:
   - The OS-managed SQLite database accessed via `ContentResolver`.
   - Mandatory for Android system interoperability, smartwatches (WearOS), Android Auto, and system backup.
2. **Local Realm Database (`QKSMS.realm`)**:
   - The reactive database driving UI adapters, search indices, automation rules, and fast conversation caching.
   - Primary entities: `Message`, `Conversation`, `Recipient`, `AutomationRule`, `AutomationReplyState`, `MessageContentFilter`, `QuarantinedMessage`, `AutomationStepLedger`.

### 2.2 Lack of Distributed Atomicity & Ingestion Failure Window
- **Location**: [`data/src/main/java/com/moez/QKSMS/repository/MessageRepositoryImpl.kt:707-768`](file:///home/ubuntu/quik/data/src/main/java/com/moez/QKSMS/repository/MessageRepositoryImpl.kt#L707-L768)
- **Current Defect**:
  1. Provider write occurs first: `context.contentResolver.insert(Sms.Inbox.CONTENT_URI, values)`.
  2. Realm write occurs second: `realm.executeTransaction { realm.copyToRealmOrUpdate(message) }`.
  - If a process crash, low-memory kill, or Realm exception occurs between steps 1 and 2, the message exists in the OS telephony provider but is completely missing from Realm.
  - In `SmsReceivedReceiver.kt:69-72`, if `insertReceivedSms` throws an uncaught exception, `ReceiveSmsWorker` is never enqueued.
- **Remediation**:
  - Wrap Realm transaction in a try-catch fallback. If Realm insertion fails, `insertReceivedSms` still returns an unmanaged `Message` containing the `providerContentId` and `contentUri`.
  - In `ReceiveSmsWorker` / `ReceiveMmsWorker`, if `getUnmanagedMessage(messageId)` returns null, the worker falls back to hydrating from the provider URI via `syncRepo.syncMessage(uri, messageId)` before returning failure.

---

## Section 3: End-to-End Inbound Message Pipeline & Automation Interceptor

### 3.1 Trace of Incoming Message Lifecycle

```
[OS Telephony Stack (RIL / Modem)]
       │
       ▼ (android.provider.Telephony.SMS_DELIVER broadcast, BROADCAST_SMS guarded)
[SmsReceivedReceiver.kt:38-73]
       │  ├─ Extract messages: Sms.Intents.getMessagesFromIntent(intent)
       │  ├─ Multi-SIM Extraction: SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX with "subscription" fallback
       │  ├─ goAsync() + Schedulers.io()
       │  └─ messageRepo.insertReceivedSms(subId, address, body, timestamp)
       │         ├─ Provider Insert: Sms.Inbox.CONTENT_URI -> returns providerContentId
       │         └─ Realm Insert: realm.copyToRealmOrUpdate(message)
       ▼ Enqueue Expedited OneTimeWorkRequest
[ReceiveSmsWorker.kt:58-144] (WorkManager background executor)
       │
       ├─ Stage 1: Blocking Check (blockingClient.shouldBlock(address))
       │     └─ If Blocked & drop: Quarantine -> messageRepo.deleteMessages() -> Result.failure()
       │     └─ If Blocked: markRead + conversationRepo.markBlocked -> continue
       │
       ├─ Stage 2: Content Filter Check (filterRepo.isBlocked(body, address, contactsRepo))
       │     └─ If Filtered: Move to QuarantinedMessage store -> deleteMessages() -> Result.failure()
       │
       ├─ Stage 3: Automation Interceptor (executeAutomations.execute(message))
       │     ├─ Snapshot: Snapshot(id, threadId, subId, address, body)
       │     ├─ Rule Matching: regex/exact/contact filters
       │     ├─ If Rule contains ACTION_DELETE:
       │     │     ├─ Mark message.pendingSuppression = true in Realm (with 60s TTL)
       │     │     └─ Return ExecutionResult.Dropped (Suppresses Notification & Inbox)
       │     └─ Schedule AutomationActionWorker (Expedited if delayMs == 0)
       │
       ├─ Stage 4: Conversation Update (conversationRepo.updateConversations(threadId))
       │     └─ Excludes pendingSuppression == true from lastMessage
       │
       ├─ Stage 5: Unarchive Thread (unless rule isArchived = true)
       │
       ├─ Stage 6: Notification Dispatch (notificationManager.update(threadId))
       │     └─ Excludes pendingSuppression == true from getUnreadUnseenMessages()
       │
       └─ Stage 7: OS Surfaces Update (updateShortcuts, updateBadge, home widget)
```

### 3.2 True "Suppress-First" Architecture Across ALL Surfaces

To satisfy the explicit user requirement:
> *"So let's say a message appears and it is to be in automation section. Though so it won't, it won't ever land in the inbox. It won't ever show a notification at all. First it will do everything behind the surface and then it has to show up in the user interfaces and publicly."*

The message must be structurally invisible across **all four** presentation surfaces while under automation processing:
1. **Notification Shade**: `MessageRepositoryImpl.getUnreadUnseenMessages(threadId)` filters out `pendingSuppression == true`.
2. **Conversation List Snippet**: `ConversationRepositoryImpl.getConversationsBase()` and `lastMessage` queries filter out `pendingSuppression == true`. The thread does not jump to the top of the inbox.
3. **Conversation Thread View**: `MessageRepositoryImpl.getMessagesBase(threadId)` filters out `pendingSuppression == true`. The message does not appear in `MessagesAdapter` while the automation chain is running.
4. **Fail-Safe Watchdog (TTL Recovery)**: To prevent messages from becoming trapped in permanent invisibility if the app is killed or crashes:
   - At app launch, inside `BootReceiver`, and in `HousekeepingWorker`: query Realm for messages where `pendingSuppression == true` and `System.currentTimeMillis() - date > 60,000L` (1 minute).
   - Reset `pendingSuppression = false` and call `notificationManager.update(threadId)`. The message safely re-surfaces in the inbox.

---

## Section 4: Telephony Radio Transmission, Carrier Constraints & OS SMS Rate-Limiting Engine

### 4.1 Root Cause Analysis: Undelivered & Prematurely Deleted Forwards
When a user requests messages 3 to 4 times within 15 minutes, forwards fail and original messages are deleted due to five compounding defects:

#### 1. The "Delete-Before-Ack" Asynchronous Defect
- In `AutomationActionWorker.kt:177` and `QkTransaction.kt:355`:
  `QkTransaction.sendSmsMessage()` catches exceptions, returns `true` immediately, and hands PDUs to the Android telephony modem asynchronously.
  `AutomationActionWorker` immediately advances to `ACTION_DELETE`, deleting the original message from Realm and the telephony provider.
  When the cellular radio modem later encounters a carrier error or rate limit, `MessageSentReceiver` receives a failure code (`RESULT_ERROR_GENERIC_FAILURE`), but the original message has already been deleted.

#### 2. Android OS System-Level SMS Rate Limiting (`SmsUsageMonitor`)
- Located in AOSP core telephony (`com.android.internal.telephony.SmsUsageMonitor`).
- Default limit: **30 outgoing SMS messages per 30 minutes** (configured even lower on some OEM ROMs, e.g. 10 SMS per 15 minutes).
- When exceeded, Android OS displays a modal security dialog: *"QUIK is sending a large number of SMS messages. Do you want to allow this app to continue sending messages?"*
- Because automations execute in the background via WorkManager, the dialog is never presented to the user. Android OS rejects subsequent sends with `RESULT_ERROR_LIMIT_EXCEEDED` (code 5).

#### 3. Multi-Part SMS PDU Splitting & Carrier SMSC Burst Limits
- Standard SMS is limited to 160 characters (GSM-7) or 70 characters (UCS-2).
- Adding the prefix `"From: +1234567890 | ..."` causes most forwards to exceed single-PDU length, splitting into 2 or 3 concatenated PDUs (153 or 67 characters each).
- Sending 4 alerts in 15 minutes generates **8 to 12 separate PDU transmissions**.
- Consumer carrier SMSCs enforce strict burst limits (typically 1 PDU per 1–2 seconds, max 10–15 per 15 minutes). Rapid back-to-back submissions trigger `SM_CAUSE_CONGESTION` or silent carrier drops.

#### 4. Dual-SIM `subId = -1` Default Routing
- In `SmsReceivedReceiver.kt:55`, querying `"subscription"` instead of `SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX` returns `-1` on modern Android devices.
- `SmsManagerFactory.createSmsManager(-1)` falls back to `SmsManager.getDefault()`, routing the forward through SIM 1. On dual-SIM phones where SIM 2 received the message and SIM 1 has no SMS service, the forward immediately fails.

#### 5. Multipart PDU Callback Race in `MessageSentReceiver`
- When a 3-part SMS is dispatched, `QkTransaction.kt:342` attaches `sentPI` for each part.
- `MessageSentReceiver.kt:66-75` receives a separate callback for each part. If part 1 succeeds, it calls `markSent`. If part 2 fails, it calls `markFailed`. If part 3 succeeds, it calls `markSent` again. The status is corrupted, and partial transmission causes the receiving carrier SMSC to drop reassembly.

### 4.2 Engineering Solution: Two-Phase Commit & Pacing Engine

1. **Two-Phase Commit (Forward-Then-Delete)**:
   - For an automation chain containing `[FORWARD_SMS, DELETE]`:
     - Step 0 (`FORWARD_SMS`): Dispatch forward with a unique `correlationId`. Hold original message in `pendingSuppression = true`.
     - `MessageSentReceiver` tracks all parts of the multipart message. Only when ALL parts report `Activity.RESULT_OK` does it emit a success signal to WorkManager.
     - WorkManager executes Step 1 (`ACTION_DELETE`).
     - If `MessageSentReceiver` reports any failure code: abort deletion, reset `pendingSuppression = false`, post notification on `automation_failures` channel, and restore original message in inbox.
2. **Serial Transmission Pacing Queue**:
   - Outbound SMS dispatches must be funneled through a serial FIFO queue with a **2,000ms inter-message pacing interval**. Baseband modem buffers are never overwhelmed by concurrent threads.
3. **Outbound SMS Budget Tracker**:
   - Track rolling 30-minute outbound SMS count in Realm (`OutboundSmsTracker`). Cap automated sends at 25 SMS per 30 minutes to stay safely below the Android OS `SmsUsageMonitor` threshold.

---

## Section 5: Crash Resilience, Worker Idempotency, Reboot Recovery & Resource Optimization

### 5.1 Durable Realm Ledger with TTL
- Replace SharedPreferences ledger in `AutomationActionWorker` with a Realm entity `AutomationStepLedger`:
  ```kotlin
  open class AutomationStepLedger(
      @PrimaryKey var workId: String = "",
      var ruleId: Long = 0L,
      var messageId: Long = 0L,
      var completedStepsJson: String = "[]",
      var createdAt: Long = System.currentTimeMillis()
  ) : RealmObject()
  ```
- Purge ledger rows older than 7 days at worker startup.
- Send `Idempotency-Key: $ruleId-$messageId-$index` header on all `FORWARD_HTTP` requests to prevent duplicate webhooks across WorkManager retries.

### 5.2 Device Reboot Recovery (`BOOT_COMPLETED`)
- In `BootReceiver.kt:36`, query Realm for unsent delayed messages (`where(Message::class.java).equalTo("type", TYPE_QUEUED).findAll()`) and re-schedule `AlarmManager` alarms.
- Scan for orphaned `pendingSuppression == true` messages older than 60 seconds and reset suppression.

### 5.3 Memory Leak & Resource Audit Findings

| Issue | File & Line | Impact | Engineering Remediation |
|---|---|---|---|
| **Realm Instance Leak** | [`MessageContentFilterRepositoryImpl.kt:40-51`](file:///home/ubuntu/quik/data/src/main/java/com/moez/QKSMS/repository/MessageContentFilterRepositoryImpl.kt#L40-L51) | `Realm.getDefaultInstance()` called without `.close()`. Native Realm C++ memory leaks on every filter query. | Wrap operations in `.use { }`. |
| **All-Contacts Heap Spike** | [`NotificationManagerImpl.kt:194-205`](file:///home/ubuntu/quik/presentation/src/main/java/com/moez/QKSMS/common/util/NotificationManagerImpl.kt#L194-L205) | Loads entire phonebook and flattens all numbers into heap on every notification if `silentNotContact` is true. Causes 20MB–50MB heap allocation and GC pause. | Replace with single index lookup: `contactsRepo.isContact(address)`. |
| **Unbounded Glide Decode** | [`NotificationManagerImpl.kt:248-254`](file:///home/ubuntu/quik/presentation/src/main/java/com/moez/QKSMS/common/util/NotificationManagerImpl.kt#L248-L254) | `futureGet.get()` called with no timeout; blocks notification worker indefinitely on bitmap decode. | Add `500ms` timeout with monogram fallback. |
| **Regex ReDoS Vulnerability** | [`MessageContentFilterRepositoryImpl.kt:63`](file:///home/ubuntu/quik/data/src/main/java/com/moez/QKSMS/repository/MessageContentFilterRepositoryImpl.kt#L63) | Evaluates unbounded user regex on receive thread without length cap or timeout. | Migrate to `safeRegexMatch` (256-char pattern cap, 4KB text, 500ms timeout on daemon thread). |
| **Conductor Re-attach Leak** | [`AutomationsController.kt:38-43`](file:///home/ubuntu/quik/presentation/src/main/java/com/moez/QKSMS/feature/settings/automations/AutomationsController.kt#L38-L43) | `presenter.bindIntents()` in `onAttach()` registers duplicate Rx subscriptions across view re-attachments. | Clear disposables in `onDestroyView()` or scope strictly to view lifecycle. |

---

## Section 6: Multi-Agent Division of Labor & Implementation Action Plan

```
 ┌──────────────────────────────────────────────────────────────────────────────────┐
 │                               COORDINATOR AGENT                                  │
 └──────┬──────────────────────┬──────────────────────┬──────────────────────┬──────┘
        │                      │                      │                      │
        ▼                      ▼                      ▼                      ▼
 ┌──────────────┐      ┌──────────────┐      ┌──────────────┐      ┌──────────────┐
 │   Agent 1    │      │   Agent 2    │      │   Agent 3    │      │   Agent 4    │
 │  Telephony,  │      │   Pipeline   │      │    UI/UX     │      │ Performance, │
 │   Radio &    │      │& Automations │      │ & Material 3 │      │   Memory &   │
 │ Persistence  │      │ Interceptor  │      │  Expressive  │      │ Optimization │
 └──────────────┘      └──────────────┘      └──────────────┘      └──────────────┘
        │                      │                      │                      │
        └──────────────────────┼──────────────────────┼──────────────────────┘
                               ▼
 ┌──────────────────────────────────────────────────────────────────────────────────┐
 │                         QA & INTEGRATION VERIFIER                                │
 │ - End-to-end regression unit tests, multi-action retries, and migration smoke    │
 └──────────────────────────────────────────────────────────────────────────────────┘
```

### Agent Task Packages

#### Agent 1: Telephony, Radio & Persistence Engine (`TelephonyCoder`)
- **Files**: `SmsReceivedReceiver.kt`, `MessageRepositoryImpl.kt`, `QkTransaction.kt`, `QuarantineRepositoryImpl.kt`, `QkRealmMigration.kt`
- **Tasks**:
  1. Fix multi-SIM extra extraction in `SmsReceivedReceiver.kt` checking both `SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX` and `"subscription"`.
  2. Implement dual-write fallback in `insertReceivedSms`: wrap Realm insert in try-catch; return unmanaged message with provider content URI so worker falls back to `syncRepo.syncMessage(uri)`.
  3. Implement `QuarantinedMessage` Realm schema and `QuarantineRepositoryImpl` (quarantine/restore/delete/purge).
  4. Fix radio send confirmation in `QkTransaction.kt` and `MessageSentReceiver.kt`: implement multipart PDU reassembly tracking and ensure failures prevent local deletion.
  5. Implement 2-second serial transmission pacing queue for outbound SMS dispatches.

#### Agent 2: Message Pipeline & Automations Interceptor (`PipelineCoder`)
- **Files**: `ExecuteAutomations.kt`, `ReceiveSmsWorker.kt`, `AutomationActionWorker.kt`, `NotificationManagerImpl.kt`
- **Tasks**:
  1. Implement complete "Suppress-First" state machine: add `Message.pendingSuppression`, mark in `ExecuteAutomations`, exclude from `getUnreadUnseenMessages()`, `lastMessage`, and `getMessagesBase()`.
  2. Implement 60-second fail-safe TTL recovery watchdog for `pendingSuppression` messages.
  3. Implement `AutomationStepLedger` in Realm with 7-day TTL cleanup at worker start.
  4. Add `Idempotency-Key` header on `FORWARD_HTTP` requests.
  5. Implement `showAutomationFailure()` on `notifications_automation_failures` channel; un-suppress message and notify on terminal forward failure.

#### Agent 3: UI/UX & Material 3 Expressive (`Material3Coder`)
- **Files**: `Colors.kt`, `AutomationsController.kt`, `EditAutomationController.kt`, `QuarantinedMessagesController.kt`, layout XMLs
- **Tasks**:
  1. Add `WindowInsetsCompat` edge-to-edge padding across `MainActivity`, `ComposeActivity`, and `ContainerActivity`.
  2. Implement Quarantined Messages review controller, adapter, and layout under Settings → Blocking → Quarantined Messages.
  3. Fix Conductor Rx subscription leaks in `AutomationsController.kt` and `EditAutomationController.kt` across detach/re-attach.
  4. Align container tokens and AMOLED Black pure `#000000` styling with `1dp` outline strokes.

#### Agent 4: Performance, Memory & Resource Optimizer (`PerfCoder`)
- **Files**: `MessageContentFilterRepositoryImpl.kt`, `NotificationManagerImpl.kt`, `BootReceiver.kt`, `HousekeepingWorker.kt`
- **Tasks**:
  1. Plug Realm instance leaks in `MessageContentFilterRepositoryImpl.kt` using `.use { }`.
  2. Eliminate all-contacts loading in `NotificationManagerImpl.kt` using targeted queries (`contactsRepo.isContact(address)`).
  3. Add 500ms timeout to Glide notification avatar loading.
  4. Enforce `safeRegexMatch` on content filter evaluation.
  5. Reschedule delayed SMS messages in `BootReceiver.kt` and add 30-day quarantine purge in `HousekeepingWorker.kt`.

---

## Section 8: Automation Failure Recovery, Activity Logging & Material 3 UI (Implemented v4.4.0)

### 1. Delete-Preserving Failure Recovery (`ACTION_DELETE` Chains)
- **Problem**: Previously, if an automation action step failed (e.g. `ACTION_FORWARD_SMS` rejected by carrier, `ACTION_FORWARD_HTTP` webhook connection refused, or WorkManager queue failure during `ExecuteAutomations`), execution aborted before reaching subsequent steps. If the rule included `ACTION_DELETE`, the SMS unexpectedly remained in the user's inbox, conversation view, and notification shade.
- **Solution**:
  - In `ExecuteAutomations.kt`:
    - When `scheduleAction` encounters a WorkManager enqueue failure or unhandled exception on a rule containing `ACTION_DELETE`, the original message is immediately deleted via `messageRepo.deleteMessages(listOf(snap.id))`, `isDropped` is set to `true` to suppress notification/inbox surfacing, a `FailedAutomationMessage` is captured, and an `AutomationLog.TYPE_ERROR` is recorded.
  - In `AutomationActionWorker.kt`:
    - Whenever a step fails or throws an exception, `handleStepFailure` accurately captures `currentStepIndex` and `currentActionType`.
    - If the rule's steps contain `ACTION_DELETE` (`hasDelete == true`), the original message is immediately deleted via `messageRepo.deleteMessages(listOf(messageId))`, `conversationRepo.updateConversations(listOf(threadId))` refreshes conversation snippets and counts, and `notificationManager.update(threadId)` dismisses or updates the notification. The user's intent to keep the incoming message out of their inbox is strictly honored.
    - A `FailedAutomationMessage` entity is persisted to Realm containing full step metadata and payload for manual review and retry.
    - An `AutomationLog` entry with `type = AutomationLog.TYPE_ERROR` is recorded in Realm.

### 2. Dedicated Realm Entities & Migration (Schema v19)
- **`FailedAutomationMessage`** (`domain/model/FailedAutomationMessage.kt`):
  - Primary Key: `id: Long` (auto-incremented)
  - Fields: `messageId: Long`, `threadId: Long`, `subId: Int`, `sender: String`, `body: String`, `ruleId: Long`, `ruleName: String`, `failedStepIndex: Int`, `failedActionType: String`, `errorMessage: String`, `timestamp: Long`, `canRetry: Boolean`, `actionsJson: String`.
- **`AutomationLog`** (`domain/model/AutomationLog.kt`):
  - Primary Key: `id: Long` (auto-incremented)
  - Fields: `ruleId: Long`, `ruleName: String`, `messageId: Long`, `type: String` (`SUCCESS`, `ERROR`, `INFO`), `actionType: String`, `details: String`, `timestamp: Long`.
- **`QkRealmMigration.kt`**:
  - `SCHEMA_VERSION` incremented to `19`.
  - Migration block from version 18 creates tables and fields for `FailedAutomationMessage` and `AutomationLog` with primary key constraints.

### 3. Repository & Presenter Lifecycle Robustness
- **Realm Query Instance Reuse** (`AutomationRuleRepositoryImpl.kt`):
  - Query methods (`getRules`, `getFailedMessages`, `getLogs`) reuse open Realm instances rather than eagerly closing them mid-session. This prevents `IllegalStateException` crashes in `QkRealmAdapter.updateData()` when removing change listeners from previous query results upon filter tab toggling. Instances are safely closed in `close()`.
- **Retry Failure Resilience** (`AutomationsPresenter.kt`):
  - `retryFailedClicks` is throttled (500ms) to prevent duplicate worker enqueue.
  - `deleteFailedMessage(failedId)` is scoped strictly within the successful worker enqueue block. If enqueuing the retry job fails, the failed message is preserved in Realm and an error log is recorded.
- **Housekeeping Worker Pruning** (`HousekeepingWorker.kt`):
  - Invokes `automationRepo.pruneOldLogs(maxAgeDays = 7)` on its recurring schedule to prune activity logs older than 7 days, preventing unbounded database growth.

### 4. Settings → Automations Material 3 UI
- **Navigation Tabs**: M3 `TabLayout` in `automations_controller.xml` switching between:
  1. **Rules**: Existing rule list with add/edit/delete/toggle.
  2. **Failed Messages**: List of failed messages displaying sender, message preview, rule name, failed step and error message, with "Retry" and "Dismiss" action buttons using standard M3 `?attr/cardBorder` card styling.
  3. **Activity Logs**: Filterable timeline of execution events with M3 filter chips (`All`, `Errors`, `Success`) and top-bar "Clear Logs" menu option.
- **Presenter & Architecture**: `AutomationsPresenter` handles `tabSelectedClicks`, `retryFailedClicks`, `dismissFailedClicks`, `clearFailedClicks`, `filterLogsClicks`, and `clearLogsClicks`. Retrying a failed message re-enqueues `AutomationActionWorker` with remaining steps and removes the failed message from Realm upon success.

---

## Remaining Questions & Gaps

1. **Android 14/15/16 WorkManager Expedited Job Quotas & Doze Behavior**:
   - Expedited work requests (`setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`) are subject to OS standby bucket quotas. On devices under deep battery saver, expedited jobs may experience brief scheduling latency (10–30 seconds). Verification should confirm whether foreground service fallback (`getForegroundInfo`) is reliably accepted on API 34+.
2. **Third-Party Carrier RCS Interop**:
   - Messages arriving via proprietary carrier RCS (e.g. Google Jibe / Samsung Messages) bypass `Telephony.SMS_DELIVER` entirely if RCS is configured as default provider. QUIK only captures SMS/MMS PDU broadcasts delivered by the Android Telephony Framework.
3. **Next Investigator / Coder Priority**:
   - Coding agents should immediately implement Realm migration schema bumps (`forwardRateLimitMs`, `pendingSuppression`, `QuarantinedMessage`, `AutomationStepLedger`), followed by the "Suppress-First" pipeline interceptor in `ExecuteAutomations` and `AutomationActionWorker`.


---

## Section 7: Multi-Rule Concurrency, Cascading Carrier Feedback Loops & Precedence Arbitration

### 7.1 Multi-Rule Concurrency Analysis
When a single incoming message matches multiple enabled rules (e.g. Rule 1: Forward + Delete, Rule 2: Archive or Auto-Reply):
1. **Unordered Evaluation Defect**: `automationRepo.getEnabledRules()` returns rules in arbitrary B-tree storage order without explicit priority sorting.
2. **Parallel WorkManager Contention**: Independent `OneTimeWorkRequest`s are scheduled concurrently for the same `messageId`. If Rule 1 deletes the message before Rule 2 runs, Rule 2 produces orphaned auto-replies or records false-positive failure entries in `FailedAutomationMessage`.
3. **Ghost Notification Defect**: If any rule contains asynchronous actions, `isDropped` remains `false` in `ExecuteAutomations.kt`, causing `ReceiveSmsWorker` to fire a sound/heads-up notification that disappears abruptly when the message is deleted seconds later.

### 7.2 Unified Action Plan Compiler
To guarantee deterministic execution and eliminate race conditions:
1. **Rule Prioritization (`priority: Int`)**: Rules are evaluated in descending priority order (`sort("priority", Sort.DESCENDING)`).
2. **Exclusive Execution (`stopOnMatch: Boolean`)**: If a matching rule has `stopOnMatch = true`, evaluation halts immediately.
3. **Canonical Action Plan Merging**: When multiple rules match without `stopOnMatch`, actions are compiled into a SINGLE atomic sequence:
   $$\text{External Egress (HTTP, Forward SMS, Reply)} \longrightarrow \text{Thread Mutations (Read, Archive)} \longrightarrow \text{Terminal Action (Delete)}$$
4. **Single Unique WorkRequest**: Schedule exactly one worker per message:
   `WorkManager.enqueueUniqueWork("automation_msg_${snap.id}", ExistingWorkPolicy.KEEP, request)`
5. **Immediate Notification Suppression ("Suppress-First")**: If the compiled plan contains `ACTION_DELETE`, set `isDropped = true` upfront in `ExecuteAutomations` so no system notification ever sounds or surfaces.

### 7.3 Cascading Feedback SMS & Infinite Loop Protection
When an outbound forward triggers an immediate inbound balance/delivery SMS from the carrier SIM (e.g. "Balance remaining: $14.50"), and that feedback SMS matches another forward rule:
1. **Carrier Shortcode / Alphanumeric Filter**: Block automated `ACTION_FORWARD_SMS` if the sender is an alphanumeric shortcode (length $\le 6$ and non-E.164) or USSD gateway unless explicitly whitelisted.
2. **Self-Forward / Loop Header Detection**: Abort automated forwarding if `message.body` contains `"From: <own_number>"` or the target forwarding destination.
3. **Forward Rate Limiting (`forwardRateLimitMs`)**: Add per-destination forward debouncing (default 60s cooldown per destination).
4. **Rolling Outbound Budget**: Cap automated SMS at 20 messages per 30 minutes in Realm (`OutboundSmsTracker`) to prevent triggering Android's 30-message `SmsUsageMonitor` background security dialog.
5. **Serial Radio Pacing Queue**: Funnel outbound automated SMS through a 2,000ms inter-message pacing queue to eliminate baseband modem buffer collisions and carrier SMSC congestion (`SM_CAUSE_CONGESTION`).

### 7.4 Thread-Safe Primary Key Generation (`KeyManagerImpl.kt`)
- `KeyManager.newId()` is invoked concurrently by inbound SMS (`insertReceivedSms`) and outbound forward synchronization (`syncProviderMessage`).
- Protect `newId()` with `synchronized(lock)` to prevent identical primary key collisions that destroy messages in Realm.
- Fix `reset()` to re-query the database maximum rather than resetting `maxValue = 0L`, preventing historical message overwrites during background sync.

### 7.5 Version Bump Specification
- Update `presentation/build.gradle` to `versionCode 2250`, `versionName '4.5.0'`.
- Update `changelog.json` with release notes for `4.5.0`.
- Bump Realm schema version to `20L` in `QkRealmMigration.kt` to support `priority`, `stopOnMatch`, `forwardRateLimitMs`, and `OutboundSmsTracker`.

---

## Section 8: UI/UX Rendering, Memory Profiling & Real-World "Massacre" Audit

### 8.1 UI/UX Rendering & Interaction Glitches
1. **Send & Resend Button Unthrottled Double-Tap Hazard**:
   - In `ComposeViewModel.kt`, `view.sendIntent` and `view.resendIntent` lacked click throttling. Rapid double-tapping dispatches duplicate SMS messages and bills the user twice.
   - **Remediation**: Add `.throttleFirst(500, TimeUnit.MILLISECONDS)` on all send and resend click streams.
2. **`ComposeActivity` Activity Stack Duplication**:
   - Neither manifest nor `Navigator.kt` specifies `singleTop` for `ComposeActivity`.
   - **Remediation**: Add `android:launchMode="singleTop"` in `AndroidManifest.xml` and `Intent.FLAG_ACTIVITY_SINGLE_TOP` in `Navigator.kt`.
3. **Conductor Controller View Leak**:
   - `adapter.emptyView` in `AutomationsController.kt` retains strong references to detached views when pushing child controllers under `RETAIN_DETACH`.
   - **Remediation**: Override `onDestroyView()` in `AutomationsController` and null out adapter `emptyView` references.
4. **IME Keyboard Occlusion on Android 11+**:
   - `applySystemBarInsets()` in `QkThemedActivity.kt` queries `systemBars()` but ignores `WindowInsetsCompat.Type.ime()`.
   - **Remediation**: Update insets listener to combine `systemBars()` and `ime()` insets, applying padding to view containers instead of `android.R.id.content`.

### 8.2 RAM Management & Swiped-from-Recents Lifecycle
1. **Swiped-from-Recents Behavior**:
   - Swiping the app from the recent apps drawer kills the UI process (`SIGKILL`).
   - When an incoming SMS arrives, Android spawns a background process for `SmsReceivedReceiver` with `goAsync()` (extending wake lock up to 30s), saves to provider + Realm, enqueues `ReceiveSmsWorker` (expedited WorkManager), and transfers execution to `JobScheduler`.
   - Mid-flight worker interruption: WorkManager detects `isStopped` and retries. The persistent step ledger in `SharedPreferences` skips already completed steps.
2. **Estimated RAM Footprint**:
   - **Idle Background**: ~30 MB – 42 MB (16–22 MB JVM heap + 14–20 MB native RSS/Realm).
   - **Active Conversation List**: ~52 MB – 75 MB.
   - **Active Composing**: ~68 MB – 115 MB.
   - **Background Worker Execution**: ~38 MB – 60 MB.
   - **Full Telephony Sync**: ~160 MB – 290 MB.
3. **Memory Pressure (`onTrimMemory`)**:
   - Implement `ComponentCallbacks2.onTrimMemory()` in `QKApplication.kt` to flush Glide bitmap caches and Realm instance caches under OS memory pressure (`TRIM_MEMORY_RUNNING_CRITICAL`).
4. **Native Realm Leak Plugs**:
   - Plug unclosed Realm instances in `SyncRepositoryImpl.kt` (`handlerThread.quitSafely()`), `ConversationRepositoryImpl.kt`, and `MessageContentFilterRepositoryImpl.kt`.

### 8.3 Multi-Rule & Multi-Regex Semantics
1. **Single Rule with Compound Regex**: Evaluated once per message; does NOT execute multiple times for multiple regex matches within the same rule.
2. **Multiple Messages Simultaneously**: Each receives a monotonic ID via thread-safe `KeyManager.newId()`. Auto-replies are gated per thread; forwards are gated per destination with 2,000ms radio pacing.
3. **Multiple Rules Matching One Message**: Priority ordering (`priority DESC`), `stopOnMatch` exclusive execution, and Unified Action Plan Compiler merging into a single canonical worker.
4. **"Suppress-First" Inbox Flashing Fix**: Add `@Index var pendingSuppression: Boolean = false` to `Message.kt` (schema 21) to filter suppressed messages from conversation list and thread views during background automation execution.
