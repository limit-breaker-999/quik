# PROGRESS — Forward-Reliability + Deep Automation Diagnostics

Branch: `feature/material3-redesign-v2`. Commit `110284e8` — all 5 plan phases complete.

## Done (committed)
- **Phase 1 Egress gating**: content-keyed forward slot (`forward:<dest>:<sha256-16>`), `releaseForwardSlot`/`releaseReplySlot` refund, `Result.retry()` + EXPONENTIAL 10s backoff (3 attempts), decline-skip semantics (budget → retryable FailedAutomationMessage), reply gate strips AUTO_REPLY steps only, editor cooldown fields restored (minutes, 0=off), retry passes `KEY_FORWARD_RATE_LIMIT_MS`, real exception messages preserved.
- **Phase 2 Deep logging**: schema v22 (`Message.suppressionExpiresAt`, `AutomationLog.jsonPayload`), `AutomationDiag` helper, INGEST/BLOCK_CHECK/FILTER_CHECK/AUTOMATION/MATCH/PLAN/DISPATCH/forward_guard/forward_send/reply_send/RADIO_ACK events; failures carry error+sender+body+actionsJson.
- **Phase 3 Export UI**: Logs tab "Export JSON" bar → Copy (500k cap) / Save-as-file (`ACTION_CREATE_DOCUMENT` via `startActivityForResult`, REQUEST_SAVE_EXPORT=2002); per-row drill-down dialog with pretty payload + copy; presenter `buildDiagnosticsJson()` envelope.
- **Phase 4 Suppression expiry**: `markPendingSuppression(id, expiresAt)` / `resetOrphanedSuppressedMessages()`; expiry = now + delayMs + 10min (60s immediate); QKApplication/BootReceiver/HousekeepingWorker call sites updated.
- **Phase 5 subId**: `SmsReceivedReceiver` reads `"android.telephony.extra.SUBSCRIPTION_ID"` with `"subscription"` fallback.
- Tests: 65/65 pass (`:presentation:testDebugUnitTest`), incl. new v21→v22 migration test, export-shape test, reworked skip-semantics/expiry tests; `org.json:json` test dep added.

## Verification limits
- No emulator on host: on-device scenarios (2 SMS <60s apart, airplane-mode retry, export copy/save on Samsung/Realme Android 16) not exercised — delegate to user via `adb logcat -s AutomationActionWorker:* MessageSentReceiver:*`.

## Next
- None in scope. Optional: user-driven device verification; `/opsx-archive`.
