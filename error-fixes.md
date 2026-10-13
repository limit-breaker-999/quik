# error.md Triage & Fix Report — Session 2

**Branch:** `feature/material3-redesign-v2` + working tree
**Verification:** `compileReleaseKotlin` (all modules) GREEN · `lintVitalRelease` GREEN · `assembleDebug` GREEN (`QUIK-v4.3.8-debug.apk`)

## Fixed (19)

| BUG | Fix | File(s) |
|-----|-----|---------|
| **080 CRITICAL** — `KEY_SENDER_ADDRESS` never put into worker Data → auto-reply ALWAYS failed, forwards sent empty sender | Restored `.putString(KEY_SENDER_ADDRESS, ...)` | `ExecuteAutomations.kt` |
| **029** — immediate DELETE returned early, skipping remaining rules (forward after delete never fired) | Delete sets flag, loop continues; `Snapshot` data class captures all fields before any rule runs | `ExecuteAutomations.kt` |
| **027** — managed Realm `Message` invalidated by DELETE mid-loop | Same `Snapshot` fix: fields copied once up front | `ExecuteAutomations.kt` |
| **031** — new executor thread spawned per regex match per SMS | Shared singleton daemon executor (`automation-regex`) | `ExecuteAutomations.kt` |
| **058** — 14 RxBinding initial emissions blanked loaded-rule state | `skipInitialValue()` on all `textChanges()`/`checkedChanges()` | `EditAutomationController.kt` |
| **057** — `ruleId` lost on process death / rotation | `args.putLong(KEY_RULE_ID, ruleId)` in init | `EditAutomationController.kt` |
| **082** — `activity!!` NPE in `showPresets` | `val act = activity ?: return` | `EditAutomationController.kt` |
| **019** — delay chips had no selected visual | Selected chip tinted with theme accent | `EditAutomationController.kt` |
| **032** — webhook URL / phone / template unvalidated on save | Validation in presenter + `showValidationError()` dialog | `EditAutomationPresenter/Controller/View`, strings |
| **061** — duplicate `markUnarchived()` in MMS receive | Deduplicated | `ReceiveMmsWorker.kt` |
| **040** — unquoted `senderAddress` in webhook JSON (malformed JSON on spoofed senders) | `JSONObject.quote(senderAddress)` | `AutomationActionWorker.kt` |
| **037** — `adapterPosition` −1 clicks swallowed/mid-animation crash | `NO_POSITION` guards on all three click paths | `AutomationRulesAdapter.kt` |
| **038/070** — saveRule ID race + caller-object mutation | ID generation moved inside `executeTransaction`; explicit copy instead of `apply` | `AutomationRuleRepositoryImpl.kt` |
| **071** — silent DI miss for unknown workers | `else ->` branch with Timber warning | `InjectionWorkerFactory.kt` |
| **083** — rule delete with no confirmation | Confirmation dialog; raw clicks → `confirmDelete`, confirmed stream deletes | `AutomationsController/Presenter/View`, strings |
| **085** — `sendNewMessages()` empty result ignored | Empty result → `Result.retry()` (max 3) for FORWARD_SMS + AUTO_REPLY | `AutomationActionWorker.kt` |
| **066** — snippet missing `constrainedWidth` | Added | `conversation_list_item.xml` |
| **018** — edit screen used unthemed `SwitchCompat` | All 6 replaced with `QkSwitch` | `edit_automation_controller.xml` |
| **052** — log literal `{messageId}` | `$messageId` | `ReceiveSmsWorker.kt` |

## Rejected as false positives (verified against code/build)

- **001/003/074** — `@color/text_primary` etc. EXIST as color state-list selectors in `res/color/` + `res/color-night/` with day/night variants. Build links green.
- **006/084** — title lives *inside* the Toolbar; search is constrained *below* it. Stacked, not overlapping.
- **023** — QkSwitch thumb=onColor/track=accent when checked IS the M3 convention; report misread it.
- **020** — programmatic `isChecked` DOES fire `checkedChanges()`; state machine receives the update.
- **028** — proguard `-keep class dev.octoshrimpy.quik.**` preserves the reflective worker class.
- **039** — factory explicitly assigns all `@Inject` fields (verified in `InjectionWorkerFactory`).
- **041** — notification ID 0 is the shared convention across all workers (transient foreground notifications).
- **046** — `deleteMessages` on missing IDs is a silent no-op (empty Realm query); no retry spin.
- **051** — `QkPresenter.state` is a `BehaviorSubject`; state replays to late subscribers.

## Known remaining (deferred, non-blocking)

- **030/054** — rate-limit config UI (currently fixed 24h default); cross-rule reply dedup
- **035/036/068** — `getRules()` live RealmResults pattern; app-wide adapter convention, safe as used
- **062/063** — SMS/MMS automation pipeline divergence; media-only MMS body matching
- **072** — default-SMS-handler check before send (needs product decision on notification UX)
- **048** — blocking-vs-automation precedence policy
- **002/076** — `messsageLinkHandling` typo + `diable` string typo (cosmetic, compile-safe today)
