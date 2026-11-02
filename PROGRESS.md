# PROGRESS — Forward-Reliability + Deep Automation Diagnostics

Branch: `feature/material3-redesign-v2`. Plan: `local://quik-forward-reliability-plan.md` (approved).

## Done
- Phase 1 (egress gating rework):
  - `AutomationRuleRepository`: `tryAcquireForwardSlot(dest, contentKey, rateLimitMs)` content-keyed (`forward:<dest>:<sha256-16>`); `releaseForwardSlot`/`releaseReplySlot`/`getRulesList` defaults; `log(..., payloadJson="{}")`.
  - `AutomationActionWorker` FORWARD_SMS: declines (cooldown/self-loop/carrier-shortcode) → INFO skip (chain continues, DELETE still wipes); budget-exceeded → skip + retryable FailedAutomationMessage; send failure → slot refund + `Result.retry()` (max 3 attempts via runAttemptCount) then terminal failure. AUTO_REPLY: same refund+retry.
  - `ExecuteAutomations`: reply gate strips only AUTO_REPLY steps (rule's forwards still run), logs INFO "Auto-reply suppressed"; `scheduleAction` sets exponential backoff (10s).
  - AutomationsPresenter retry: passes `KEY_FORWARD_RATE_LIMIT_MS` + backoff.
  - Editor: `EditAutomationState.forwardRateLimitMin/replyRateLimitMin`, view intents, controller render/debounce, presenter load/save (min↔ms via 60_000), layout fields, strings.
  - `AutomationDiag` helper created (domain/model/AutomationDiag.kt); `AutomationLog.jsonPayload` added.
  - Test fakes updated (fake repo log/slot signatures; test 40 now content-keyed + distinct-content-passes assertion).

## In progress
- Phase 1 compile verification (gradle job running).

## Next
- Phase 2: migration v22 (`Message.suppressionExpiresAt`, done jsonPayload already), instrument ReceiveSmsWorker/ExecuteAutomations/MessageSentReceiver, failure payloads.
- Phase 3: export builder in AutomationsPresenter, Logs tab export button + copy/save dialog, per-entry drill-down, strings, TestAutomationsView.
- Phase 4: `markPendingSuppression(messageId, expiresAt)` / `resetOrphanedSuppressedMessages()` rework; expiry = now + delay + 10min buffer (60s if no delay); call sites QKApplication/BootReceiver/HousekeepingWorker; test updates (FakeMessageRepo:170 pins old signature).
- Phase 5: SmsReceivedReceiver subId via `"android.telephony.extra.SUBSCRIPTION_ID"`.
- Cleanup: full unit suite, MASTER_ARCHITECTURAL_BLUEPRINT.md §4.2/§7.3/§10, PROGRESS.md, commit per phase.

## Open decisions
- None — plan approved as-is. Manual device verification delegated to user (no emulator on host).
