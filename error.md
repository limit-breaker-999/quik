# QUIK Fork — Comprehensive Bug & Error Report

> **Branch under review:** `feature/material3-redesign-v2` (commit `8141a85c`) + uncommitted working-tree changes  
> **Comparison baseline:** `master` (commit `2367b172`)  
> **Scope:** All 19 files changed between master→HEAD, all automation engine files, uncommitted changes  
> **Purpose:** Identification only — no fixes written. For use by the implementing agent.

---

## Table of Contents

1. [CRITICAL — Resource/Build Errors](#1-critical--resourcebuild-errors)
2. [CRASH — Navigation & Drawer](#2-crash--navigation--drawer)
3. [Layout & UI Bugs — Main Screen](#3-layout--ui-bugs--main-screen)
4. [Layout & UI Bugs — Conversation List Items](#4-layout--ui-bugs--conversation-list-items)
5. [Layout & UI Bugs — Settings Screen](#5-layout--ui-bugs--settings-screen)
6. [Layout & UI Bugs — Edit Automation Screen](#6-layout--ui-bugs--edit-automation-screen)
7. [Widget / Component Bugs](#7-widget--component-bugs)
8. [Automation Engine — Logic Bugs](#8-automation-engine--logic-bugs)
9. [Automation Engine — Data & Repository Bugs](#9-automation-engine--data--repository-bugs)
10. [Automation Engine — Worker Bugs](#10-automation-engine--worker-bugs)
11. [Theme & Color System Bugs](#11-theme--color-system-bugs)
12. [Process Interaction & End-to-End Flow Bugs](#12-process-interaction--end-to-end-flow-bugs)
13. [Minor / Low-Severity Issues](#13-minor--low-severity-issues)
14. [Second-Pass Architectural, Lifecycle & Cross-Process Bugs](#14-second-pass-architectural-lifecycle--cross-process-bugs)
15. [Re-evaluation & Refined Root Causes of Prior Findings](#15-re-evaluation--refined-root-causes-of-prior-findings)
16. [Comprehensive Summary Matrix (All 85 Bugs)](#16-comprehensive-summary-matrix-all-85-bugs)

---

## 1. CRITICAL — Resource/Build Errors

### BUG-001 · `@color/text_primary` referenced but never defined
**Severity:** CRITICAL — causes runtime crash or aapt2 build failure

- `values/themes.xml` and `values-night/themes.xml` both reference `@color/text_primary`, `@color/text_secondary`, `@color/text_tertiary` in multiple style items (`android:textColorPrimary`, `colorOnSurface`, `colorSecondary`, etc.)
- The actual color resources in `colors.xml` are named **`textPrimary`**, **`textSecondary`**, **`textTertiary`** (camelCase — no underscore)
- `@color/text_primary` (snake_case) does not exist anywhere in the resource tree
- At minimum this causes a runtime `InflateException` / `ResourceNotFoundException` the moment any themed view tries to resolve the color
- Affects **all themes**: `AppBaseTheme`, `AppThemeDialog`, `AppTheme.Black`, `AppThemeDialog.Black` in both day and night variants
- **Possible fix:** Rename the color definitions in `colors.xml` from `textPrimary` → `text_primary` (and similarly for secondary/tertiary), OR change every `@color/text_primary` reference in the theme files to `@color/textPrimary`. Must be consistent across both `values/` and `values-night/`.

---

### BUG-002 · `@id/messsageLinkHandling` — typo in layout ID (triple 's')
**Severity:** HIGH — binding code references correct spelling, layout has typo

- In `settings_controller.xml`, the new `PreferenceView` for message link handling has ID `@+id/messsageLinkHandling` (note: `messsage` with three `s`)
- In `SettingsController.kt`, the binding is accessed as `binding.messsageLinkHandling.summary = ...` — this matches the typo so it actually compiles
- However, if anyone ever corrects the typo in either location, the binding breaks silently at compile time (wrong generated binding field)
- The string resource `@string/settings_mms_weblink_title` and the preference itself may not work correctly if binding-generated field name collides with anything
- **Possible fix:** Rename ID in both layout and Kotlin code to the correct spelling `messageLinkHandling`.

---

## 2. CRASH — Navigation & Drawer

### BUG-003 · Drawer navigation crashes on every menu item tap
**Severity:** CRITICAL — confirmed crash reported by user

- In the original QKSMS, the navigation drawer items (`inbox`, `archived`, `scheduled`, `blockedList`) are handled inside `MainActivity` by listening to click/check events on each drawer list item
- In the current branch, `main_activity.xml` wraps the drawer content in a `DrawerLayout` with a custom `drawer_view.xml`
- The drawer view now has `android:clickable="true"` and `android:focusable="true"` (added in uncommitted working-tree change) — this **intercepts all touch events** at the root of the drawer, preventing individual child items from receiving click events
- When the user taps on `Settings`, `Blocked List`, or any menu item, the touch event is consumed by the root `DrawerLayout` and never delivered to the item — the app likely calls a navigation method with a null or unresolved target, causing NPE crash
- **Possible fix:** Remove `clickable`/`focusable` from the drawer root view. These attributes should only be set on individual interactive items, not the container.

---

### BUG-004 · Navigation drawer does not close on swipe (only back button works)
**Severity:** HIGH — user-reported UX regression

- The swipe-to-close gesture depends on `DrawerLayout`'s built-in drag handling. If the drawer root is `clickable=true`, the `DrawerLayout`'s internal `ViewDragHelper` does not get the motion events it needs to detect the close swipe, because the child intercepts all touches.
- Additionally, the `DrawerLayout.DrawerListener` (or `ActionBarDrawerToggle`) that should close the drawer on swipe may not be wired up in `MainActivity.kt` — the uncommitted diff removes `binding.toolbarSearch.requestFocus()` but does not show that a proper drawer close listener is re-attached.
- **Possible fix:** Verify `DrawerLayout` listener is registered. Remove `clickable/focusable` from drawer root. The close swipe should work natively once touch events aren't intercepted.

---

### BUG-005 · `MainActivity` drawer toggle listener registration may be incomplete
**Severity:** HIGH

- The uncommitted `MainActivity.kt` diff removes the branch `} else binding.toolbarSearch.requestFocus()` from what appears to be the `onDrawerOpened`/`onDrawerClosed` callback — this means the drawer open/close callback now has no `else` branch, potentially leaving a dangling state where neither the inbox list nor the search bar gets focus after drawer close
- If the `ActionBarDrawerToggle` is not also calling `drawerLayout.closeDrawer(...)` explicitly on item tap, the drawer may not close after navigation either
- **Possible fix:** Verify the full `DrawerListener` implementation in `MainActivity.kt`. Ensure `closeDrawer()` is explicitly called after each navigation action.

---

## 3. Layout & UI Bugs — Main Screen

### BUG-006 · `toolbarTitle` always visible — overlaps search bar
**Severity:** HIGH — visual glitch / broken search UX

- In `MainActivity.kt`, `binding.toolbarTitle.setVisible(true)` is now called unconditionally
- Previously, `toolbarTitle` was hidden when the search UI was active (they were mutually exclusive)
- Now, when the user opens search, `toolbarTitle` ("Messages") remains visible simultaneously with the search `EditText`, causing visual overlap
- The state machine in `MainActivity`'s `render()` method needs to know about both states but the title visibility is no longer driven by `MainState`
- **Possible fix:** Re-add mutual exclusion: hide `toolbarTitle` when `state.query.isNotEmpty()` or when search is active; show it otherwise.

---

### BUG-007 · Search bar (`toolbarSearch`) outside Toolbar — constraint chain collapse
**Severity:** MEDIUM-HIGH — layout jump when search bar is hidden

- In `main_activity.xml`, `toolbarSearch` was moved out of the `Toolbar` and placed as a sibling `View` in the `ConstraintLayout`
- `recyclerView` has `app:layout_constraintTop_toBottomOf="@id/toolbarSearch"`
- When `toolbarSearch` is `GONE` (not `INVISIBLE`), `ConstraintLayout` treats it as 0×0 and the recycler snaps to the bottom of the toolbar — causing a visible layout jump every time search is toggled
- **Possible fix:** Use `INVISIBLE` instead of `GONE` for the search bar, OR use a `Barrier` or `Space` view as a stable constraint anchor so the recycler top position doesn't jump.

---

### BUG-008 · FAB is `ImageView` not `FloatingActionButton` — no ripple, no a11y
**Severity:** MEDIUM

- The compose FAB was changed from a `FloatingActionButton` to a plain `ImageView` in `main_activity.xml`
- `ImageView` has no built-in ripple feedback on click, no elevation shadow behavior, and no accessibility `role` — screen readers won't announce it as a button
- The `fab_m3_background.xml` drawable provides the shape but not touch feedback
- **Possible fix:** Either use `MaterialButton` with icon-only style, keep `FloatingActionButton`, or add `android:background="?attr/selectableItemBackgroundBorderless"` + `android:clickable="true"` + `android:focusable="true"` + `android:contentDescription` to the `ImageView`. Also needs `android:role` for accessibility.

---

### BUG-009 · Shadow/elevation view removed with no replacement
**Severity:** LOW-MEDIUM — visual regression

- The original `main_activity.xml` had a shadow view below the toolbar that provided visual depth separation
- This has been removed in the redesign with no elevation or shadow replacement on the toolbar itself
- The `Toolbar` has no `app:elevation` or `android:outlineProvider` set, so content scrolls under it with no visual separation
- **Possible fix:** Add `app:elevation="4dp"` to the toolbar, or re-add the shadow view.

---

## 4. Layout & UI Bugs — Conversation List Items

### BUG-010 · `snippet` constraint chain is broken — ends at `@id/pinned` not at rightmost view
**Severity:** HIGH — layout overflow / text clipped at wrong position

- In `conversation_list_item.xml`, `app:layout_constraintEnd_toStartOf` for the `snippet` view was changed to reference `@id/pinned`
- However, `pinned`, `scheduled`, and `unread` icons are all present to the right of `snippet`
- If `pinned` is GONE (conversation is not pinned), the snippet's end constraint target is gone, so ConstraintLayout may extend the snippet all the way past where the other icons are — causing text to overlap `scheduled` and `unread` indicators
- **Possible fix:** The chain should end at the leftmost always-visible boundary on the right side, or use a `Barrier` referencing all right-side icons.

---

### BUG-011 · `maxLines="2"` on snippet — variable row heights in RecyclerView
**Severity:** MEDIUM — performance and visual inconsistency

- `snippet` was changed from `maxLines="1"` to `maxLines="2"`
- RecyclerView's item animator and `LinearLayoutManager` assume consistent item heights by default
- Items with 1-line snippets and items with 2-line snippets have different heights, causing the recycler to re-measure on every bind — stuttery scrolling and layout instability
- Items may jump in height during the initial load when data changes propagate through `AsyncListDiffer`
- **Possible fix:** Either set a fixed `minHeight` on list items so all rows are the same height, or revert to `maxLines="1"` for the snippet.

---

### BUG-012 · `pinned` and `scheduled` icons anchored to bottom of 2-line snippet
**Severity:** MEDIUM — visual misalignment

- Both `pinned` and `scheduled` icons have `app:layout_constraintBottom_toBottomOf="@id/snippet"` added
- With `snippet` now potentially 2 lines tall, these icons align to the bottom of the snippet's text baseline rather than the vertical center of the row or the center of the `title` row
- For a 1-line snippet message, the icon is centered; for a 2-line snippet message, the icon is shifted down — inconsistent appearance
- **Possible fix:** Anchor the icons to `parent` with vertical center bias, or anchor to `@id/title` center.

---

### BUG-013 · `date` view missing explicit end constraint — potential overflow
**Severity:** MEDIUM

- The `date` view's `app:layout_constraintHorizontal_bias="100"` was removed
- No explicit `app:layout_constraintEnd_toEndOf="parent"` replacement was verified in the diff
- If the end constraint is missing or wrong, `date` may expand leftward into the `title` or be clipped
- **Possible fix:** Verify the full constraint set for `date` and ensure both `constraintStart` and `constraintEnd` are set, plus proper horizontal bias.

---

### BUG-014 · `unread` dot is 10×10dp — too small for accessibility touch target
**Severity:** LOW-MEDIUM

- The unread indicator was shrunk to `10dp × 10dp`
- Android accessibility guidelines require touch targets to be at least 48×48dp
- While this is not interactive itself, it may be nearly invisible on high-DPI screens
- **Possible fix:** Keep visual size at 10dp but the surrounding container should be padded to 48dp if it's tappable; if purely visual, at least bump to 12–14dp for visibility.

---

## 5. Layout & UI Bugs — Settings Screen

### BUG-015 · Settings preference views inside `MaterialCardView` — `findPreferences()` recursion required but may miss nested non-standard views
**Severity:** MEDIUM

- The settings screen moved all `PreferenceView` items inside `MaterialCardView → LinearLayout` wrappers
- The original `preferenceClicks()` only iterated `binding.preferences.childCount` — this would miss all views now wrapped inside `MaterialCardView` → `LinearLayout`
- A new recursive `findPreferences()` function was added to `SettingsController.kt` — this correctly traverses the view hierarchy
- **However:** The `findPreferences()` calls `view.childCount` which only works for `ViewGroup` subclasses; `MaterialCardView` IS a `ViewGroup`, so traversal should work — but needs end-to-end testing
- Edge case: If any `PreferenceView` is added by another developer at the top level (not inside a card), it will still be found. But if a non-`ViewGroup` wrapper is used in future, it would silently break click registration.

---

### BUG-016 · `syncingProgress` ProgressBar removed from constraint (no `layout_constraintBottom_toBottomOf="parent"`)
**Severity:** LOW-MEDIUM

- In the original layout, `syncingProgress` had `app:layout_constraintBottom_toBottomOf="parent"` — it was anchored to the screen bottom
- In the new layout, it's placed inside a `LinearLayout` inside a `MaterialCardView` — it now appears directly beneath the `sync` preference item, at whatever vertical position the card occupies
- During active sync, the progress bar will appear mid-page inside the card rather than at the bottom of the screen, which may be confusing
- **Possible fix:** Decide on the intended UX for sync progress visibility within the card-based layout.

---

### BUG-017 · `PreferenceCategory` label `marginStart` reduced from 72dp to 24dp
**Severity:** LOW — visual alignment regression

- In `styles.xml`, `PreferenceCategory` `marginStart` was changed from 72dp to 24dp
- The original 72dp aligned the category label with the text of preference items (which have a 72dp icon+padding area on the left)
- At 24dp, the category label may appear misaligned relative to the preference item text, since preference items' text still starts at ~72dp
- **Possible fix:** Verify alignment visually; consider 72dp for consistency with preference item text start position.

---

## 6. Layout & UI Bugs — Edit Automation Screen

### BUG-018 · Switch widgets in `edit_automation_controller.xml` use `SwitchCompat` not `QkSwitch`
**Severity:** MEDIUM — theme inconsistency

- All toggle switches in `edit_automation_controller.xml` (`senderRegexSwitch`, `senderCaseSwitch`, `bodyRegexSwitch`, `bodyCaseSwitch`, `skipContactsSwitch`, `appendSenderSwitch`) use `androidx.appcompat.widget.SwitchCompat`
- The rest of the app (settings preferences) use `QkSwitch` which applies the app's custom `Colors.theme()` tinting
- These switches will appear in the system default style (purple Material You color on Android 12+, or generic gray) rather than the app's theme color
- **Possible fix:** Replace all `SwitchCompat` in `edit_automation_controller.xml` with `dev.octoshrimpy.quik.common.widget.QkSwitch`.

---

### BUG-019 · Delay chips styled as `Button.Borderless` — no visual "selected" indication despite `isActivated` being set
**Severity:** MEDIUM — broken UX feedback for delay selection

- Delay buttons (`delayNone`, `delay30s`, `delay1m`, `delay5m`, `delay1h`) use `@style/Widget.AppCompat.Button.Borderless`
- The controller sets `chip.isActivated = true` for the selected delay — but `Widget.AppCompat.Button.Borderless` has no `activated` state in its state list drawable
- The user has no visual feedback for which delay is currently selected
- **Possible fix:** Either define a custom background drawable with `state_activated`, use Material `Chip` widgets (which support selection state natively), or use `MaterialButton` with a toggle group.

---

### BUG-020 · `presetsButton` applies selected preset to `matchBodyField` only — does not set `bodyRegexSwitch` to true via the state machine
**Severity:** MEDIUM — state mismatch

- In `EditAutomationController.showPresets()`, when a preset is selected:
  ```kotlin
  binding.matchBodyField.setText(presets[index].second)
  binding.bodyRegexSwitch.isChecked = true
  ```
- Setting `isChecked` directly on the switch **bypasses** the `checkedChanges()` observable → `EditAutomationPresenter` subscriber → `newState { copy(matchBodyIsRegex = true) }`
- The presenter state will remain `matchBodyIsRegex = false` until the user manually toggles the switch
- On the next `render()` call (triggered by any other state change), `render()` will overwrite `bodyRegexSwitch.isChecked` back to `false` because `state.matchBodyIsRegex` was never updated
- **Possible fix:** Do not set `binding.bodyRegexSwitch.isChecked` directly; instead emit through the `checkedChanges()` observable or emit through a dedicated preset subject. Alternatively, directly update the state via `presenter.newState`.

---

### BUG-021 · `edit_automation_controller.xml` uses plain `EditText` not `TextInputLayout` — no error display capability
**Severity:** LOW-MEDIUM — validation gaps

- All input fields (`ruleName`, `matchSenderField`, `matchBodyField`, `forwardPhoneField`, `forwardUrlField`, `replyTemplateField`) use bare `EditText` with no `TextInputLayout` wrapper
- There is no way to display inline validation errors (e.g., "Invalid regex", "URL required") without implementing custom error logic
- Currently `EditAutomationPresenter` does no input validation before saving — a rule with an empty URL for `FORWARD_HTTP` or empty phone for `FORWARD_SMS` saves successfully and then fails silently when triggered
- **Possible fix:** Add validation in `EditAutomationPresenter.saveClicks()` handler; use `TextInputLayout` for user-visible error display.

---

### BUG-022 · `GridLayout` for delay buttons does not wrap on small screens
**Severity:** LOW — layout overflow on narrow screens

- The delay `GridLayout` has `columnCount="3"` — with 5 buttons, this creates 2 rows (3+2)
- Each button uses `layout_columnWeight="1"` — this requires the `GridLayout` to be measured properly
- On very narrow screens or with large font sizes, the button text may be clipped
- The 5th delay button (`delay1h`) fills only 1 of 3 columns in row 2, potentially appearing left-aligned with 2/3 of the row empty — inconsistent visual
- **Possible fix:** Use a `FlexboxLayout` or `ChipGroup` for better wrapping behavior.

---

## 7. Widget / Component Bugs

### BUG-023 · `QkSwitch` thumb color uses `textPrimary` (on-theme color) — semantically wrong
**Severity:** MEDIUM — visual inconsistency in dark themes

- In `QkSwitch.kt`, when checked, `thumbTintList` is set to `colors.theme().textPrimary` (which is the text color on the theme, usually white/black)
- When unchecked, `trackTintList` uses `colors.theme().theme` (the accent color itself)
- This inverts the expected Material Design 3 convention: the **track** should be the accent color when checked, and the **thumb** should be white/on-color
- In practice this means a checked switch has a colored track and a white/black thumb — visually reversed vs. standard M3
- **Possible fix:** For checked state, set `thumbTintList` to the on-theme color (white/black) and `trackTintList` to the theme accent color. Verify against M3 switch color roles: `md.sys.color.primary` for track, `md.sys.color.on-primary` for thumb.

---

### BUG-024 · `AvatarView` — photo and initials may both be visible simultaneously
**Severity:** MEDIUM — visual glitch

- In `AvatarView.kt`, the updated code now sets `layout.initial.visibility = VISIBLE` when initials are shown, and `layout.photo.visibility = VISIBLE` when a photo URI exists
- But the code that sets initials runs **before** the photo-loading block
- If `initials.isNotEmpty()` AND `!photoUri.isNullOrEmpty()` — the initials section makes `initial` VISIBLE and `icon` GONE, then the photo section makes `photo` VISIBLE
- Depending on the Z-order of `initial` vs `photo` in the avatar layout, one may render on top of the other rather than being mutually exclusive
- The old code used `photoUri?.let { ... }` which would load photo only if non-null, but didn't explicitly show/hide views in a mutually exclusive sequence
- **Possible fix:** When loading a photo, explicitly hide `initial`. When showing initials, explicitly hide `photo`. Use a clear state machine: `PHOTO → show photo, hide initial/icon`; `INITIALS → show initial, hide photo, hide icon`; else `show icon, hide both`.

---

### BUG-025 · `AvatarView` — Glide `circleCrop()` applied but photo view shape may already be circular via XML
**Severity:** LOW

- `.circleCrop()` was added to the Glide load chain — if the `photo` `ImageView` already has a circular clip (via `ShapeableImageView` or a circular background), `circleCrop()` will double-crop and may cause anti-aliasing artifacts or quality loss
- **Possible fix:** Use either `circleCrop()` OR a circular clip on the view, not both.

---

### BUG-026 · `ComposeActivity` — `send` and `scheduledSend` tint set on theme change but not on initial render
**Severity:** LOW-MEDIUM

- In `ComposeActivity.kt`, `binding.send.setTint(it.theme)` and `binding.scheduledSend.setTint(it.theme)` are now set in the theme `doOnNext` observable
- This only fires when the theme **changes** — on initial app start, if the `doOnNext` observable emits before the view is fully laid out, the tint may not apply
- Additionally, if `send` and `scheduledSend` are conditionally visible (shown only when text is entered), they may appear with the wrong tint briefly on first show
- **Possible fix:** Ensure the theme is also applied in `onViewCreated()` or the initial `render()` call.

---

## 8. Automation Engine — Logic Bugs

### BUG-027 · `executeAutomations.execute(message)` called with live Realm object — cross-thread access
**Severity:** HIGH — potential crash/data corruption

- In `ReceiveSmsWorker.doWork()`, `messageRepo.getMessage(messageId)` returns a `Message` object
- `Message` extends `RealmObject` — it is a **managed Realm object** tied to the Realm instance opened on the `ReceiveSmsWorker`'s thread
- `executeAutomations.execute(message)` then accesses `message.address`, `message.threadId`, `message.getText()`, `message.id`, `message.subId` from within `ExecuteAutomations.execute()`
- Since `ExecuteAutomations` is a domain-layer class and doesn't own the Realm instance, if the `Worker` thread's Realm is closed or if `execute()` is called on a different thread in future, the `RealmObject` access will throw `IllegalStateException: Realm accessed from incorrect thread`
- Currently both are on the same WorkManager thread so it *might* not crash — but it's inherently fragile
- **Possible fix:** Convert the `Message` to an unmanaged copy (`realm.copyFromRealm(message)`) in `ReceiveSmsWorker` before passing to `executeAutomations.execute()`.

---

### BUG-028 · `ExecuteAutomations` — `scheduleAction()` uses `Class.forName(WORKER_CLASS)` with hardcoded package name
**Severity:** HIGH — silent crash if package/class is renamed or ProGuard strips it

- The constant `WORKER_CLASS = "dev.octoshrimpy.quik.worker.AutomationActionWorker"` is a fully qualified class name as a string
- If ProGuard/R8 minification is enabled with class renaming (not just obfuscation), this will throw `ClassNotFoundException` at runtime with no compile-time safety
- If the package name changes (e.g., forking to another package), this string must be manually updated — easy to miss
- The current `proguard-rules.pro` likely has a keep rule for workers, but this should be verified
- **Possible fix:** Reference the class directly: `AutomationActionWorker::class.java` — then `Class.forName()` is unnecessary.

---

### BUG-029 · `ExecuteAutomations.execute()` — `ACTION_DELETE` returns immediately, other rules are NOT evaluated
**Severity:** HIGH — rule ordering dependency / silent behavior change

- In `execute()`, when `ACTION_DELETE` matches with `delayMs == 0`:
  ```kotlin
  AutomationRule.ACTION_DELETE -> {
      messageRepo.deleteMessages(listOf(message.id))
      isDropped = true
      return ExecutionResult.Dropped  // ← returns immediately
  }
  ```
- If the user has multiple rules and a delete rule is not the last one, **all subsequent rules are skipped**
- A message matching both a delete rule AND an HTTP forward rule will only be deleted — the webhook never fires
- The `isDropped = true` line is also unreachable since `return` precedes it
- **Possible fix:** Do not `return` immediately on delete; instead set a flag and continue evaluating remaining rules, applying delete at the end. OR document that delete rules always terminate the chain and reorder logic accordingly.

---

### BUG-030 · Rate limiting (`replyRateLimitMs`) per-thread but `AutomationReplyState.id` is `"$ruleId:$threadId"` — no cross-rule deduplication
**Severity:** MEDIUM — unexpected behavior in edge cases

- The rate limit key is `"$ruleId:$threadId"` — rate limiting is scoped to a specific rule AND thread
- If a user has two auto-reply rules that both match the same message, both rules' rate limits are tracked independently
- A message could trigger two auto-replies if two rules match — the rate limit won't prevent the second one because it has a different key
- **Possible fix:** Consider whether the user intends for one or multiple replies per message. Add a global "one reply per thread per time window" check, or document this behavior to users.

---

### BUG-031 · `safeRegexMatch()` spawns a new `Executors.newSingleThreadExecutor()` for **every regex match** on **every incoming SMS**
**Severity:** MEDIUM — performance/resource issue under high SMS load

- Each call to `safeRegexMatch()` creates a new `ThreadPoolExecutor`, submits work, waits up to 500ms, then calls `shutdownNow()`
- For a device receiving many SMS quickly, or a user with many automation rules, this creates many short-lived threads in rapid succession
- `shutdownNow()` doesn't guarantee thread termination — the thread may linger briefly, accumulating
- **Possible fix:** Use a shared static/singleton `ScheduledExecutorService`, or use `Thread.interrupt()` + `Regex.find()` with the `MULTILINE` flag + bounded input, avoiding the executor overhead entirely. A `runWithDeadline()` utility or coroutine with timeout would be cleaner.

---

### BUG-032 · No validation of `forwardToUrl` format before scheduling HTTP forward
**Severity:** MEDIUM — worker will silently fail with network error on invalid URLs

- `EditAutomationPresenter` saves the rule without validating that `forwardToUrl` starts with `http://` or `https://`
- `AutomationActionWorker` passes the raw URL to `OkHttpClient` which will throw `IllegalArgumentException` on malformed URLs — caught by the outer `try/catch` and returned as `Result.failure()`
- The user has no feedback that their webhook URL is wrong — the rule fires, appears in `hitCount`, but never actually delivers
- **Possible fix:** Validate URL format in `EditAutomationPresenter.saveClicks()` before saving, or in the worker before the HTTP call; surface an error to the user.

---

### BUG-033 · `AutomationRule.ACTION_FORWARD_SMS` uses `sendNewMessages()` without checking if a SIM is selected
**Severity:** MEDIUM — SMS forwarding may send from wrong SIM or fail silently

- `messageRepo.sendNewMessages(subId, listOf(phone), body, emptyList(), false)` — the `subId` is passed from the original message's subscription ID
- On dual-SIM devices, the user may want to forward from the same SIM that received the message (which this does), but the SIM may have been removed or changed since the rule was created
- If `subId` is -1 (default), the system picks a SIM — this may not be what the user intends
- **Possible fix:** Add a "forward from" SIM preference per rule, or at minimum validate subId > 0 before sending.

---

### BUG-034 · `ACTION_AUTO_REPLY` — `{sender}` is the only supported template variable; no `{body}` or `{timestamp}` substitution
**Severity:** LOW — feature gap, may confuse users expecting richer templates

- The reply template only replaces `{sender}` → `senderAddress`
- Common admin use cases (quoting the message body in the reply, including timestamp) are not supported
- **Possible fix:** Document supported template variables clearly in the UI hint text, and consider adding `{body}` and `{date}` substitutions.

---

## 9. Automation Engine — Data & Repository Bugs

### BUG-035 · `AutomationRuleRepositoryImpl.getRules()` returns a `RealmResults` live query — but caller (`AutomationsPresenter`) subscribes only once
**Severity:** MEDIUM — UI may not update when rules change

- `getRules()` calls `findAllAsync()` which returns a **live, auto-updating** `RealmResults`
- `AutomationsPresenter` passes this directly to `AutomationsState(rules = automationRepo.getRules())` at init time
- `AutomationRulesAdapter` extends `QkRealmAdapter` which likely observes `RealmResults` changes — but only if `QkRealmAdapter` sets up a `RealmChangeListener`
- If `QkRealmAdapter.updateData()` is called once with the live results, and the adapter does NOT add a change listener, deletions/additions may not refresh the list view
- **Possible fix:** Verify `QkRealmAdapter` registers a `RealmChangeListener` on the passed `RealmResults`. If not, convert to `RealmObservable` and push updates through `render(state)`.

---

### BUG-036 · `AutomationRuleRepositoryImpl.getRules()` opens a Realm instance but never closes it
**Severity:** HIGH — Realm instance leak

- `Realm.getDefaultInstance()` in `getRules()` is called without `.use { }` and without storing the reference for later `.close()`
- The returned `RealmResults` requires the owning Realm instance to remain open
- The Realm instance is never explicitly closed, relying on GC — this causes a "Realm instance leaked" warning and accumulates open file handles
- Other methods (`getRule()`, `getEnabledRules()`, `saveRule()`, `deleteRule()`, `incrementHitCount()`) correctly use `.use { }` — only `getRules()` is missing this
- **Note:** Cannot use `.use {}` here because `RealmResults` becomes invalid after the Realm is closed. The correct fix is to hold a long-lived Realm instance for async results.
- **Possible fix:** Store a class-level Realm instance in the repository, open it once, and close it in a lifecycle-aware cleanup (e.g., when the repository is no longer needed). Alternatively switch to snapshot-based approach.

---

### BUG-037 · `AutomationRulesAdapter` uses `adapterPosition` (deprecated) — may return -1 during animation
**Severity:** MEDIUM — click events may be dropped silently

- In `onCreateViewHolder()`:
  ```kotlin
  val rule = getItem(adapterPosition) ?: return@setOnClickListener
  ```
- `adapterPosition` is deprecated in favor of `bindingAdapterPosition` (or `absoluteAdapterPosition`) in RecyclerView 1.2+
- More critically: `adapterPosition` returns -1 if the item is in the process of being animated (deleted/added) — if the user taps during an animation, `getItem(-1)` likely returns null and the click is silently swallowed
- **Possible fix:** Use `holder.bindingAdapterPosition` inside the click listener, and guard with `if (position == RecyclerView.NO_ID) return`.

---

### BUG-038 · `AutomationRuleRepositoryImpl.saveRule()` — race condition on ID generation
**Severity:** MEDIUM — two simultaneous saves could get same ID

- ID is generated as:
  ```kotlin
  (realm.where(AutomationRule::class.java).max("id")?.toLong() ?: -1L) + 1L
  ```
- This reads the max ID and then increments it — two simultaneous saves (unlikely but possible with delayed WorkManager actions) could both read the same max ID and produce duplicate primary keys, causing a Realm exception
- **Possible fix:** Use Realm's `@PrimaryKey` auto-increment behavior via `createObject` or move the ID generation inside the `executeTransaction` block so the read-and-increment is atomic.

---

## 10. Automation Engine — Worker Bugs

### BUG-039 · `AutomationActionWorker` — injected fields (`messageRepo`, `conversationRepo`, `notificationManager`) may not be injected at `doWork()` call time
**Severity:** HIGH — NullPointerException on any action

- `AutomationActionWorker` is a `Worker` subclass with `@Inject lateinit var` fields
- `Worker` instances are created by `WorkManager` using the `WorkerFactory` — in this project via `InjectionWorkerFactory`
- `InjectionWorkerFactory` must be registered with WorkManager at app startup in `Application.onCreate()` via `WorkManager.initialize(config)`
- If `WorkManager` is initialized before `InjectionWorkerFactory` is registered (e.g., via auto-initialize in the manifest), the default factory is used — `@Inject` fields are never populated — `doWork()` immediately throws `UninitializedPropertyAccessException`
- **Possible fix:** Verify `WorkManager` auto-initialize is disabled in the manifest (`androidx.startup` initialization disabled), and that `InjectionWorkerFactory` is explicitly registered before any WorkManager usage.

---

### BUG-040 · `AutomationActionWorker.ACTION_FORWARD_HTTP` — JSON body uses string interpolation instead of proper JSON serialization
**Severity:** MEDIUM — malformed JSON if `messageBody` or `senderAddress` contains special characters

- The JSON payload is built as:
  ```kotlin
  val json = """{"from":"$senderAddress","body":${org.json.JSONObject.quote(messageBody)},...}"""
  ```
- `senderAddress` is NOT passed through `JSONObject.quote()` — if the sender address contains backslashes, control characters, or quote marks (malformed/spoofed), the resulting JSON is malformed
- Only `messageBody` is properly quoted
- **Possible fix:** Use `JSONObject` builder for the entire payload, or pass both `senderAddress` and `messageBody` through `JSONObject.quote()`.

---

### BUG-041 · `AutomationActionWorker` — `getForegroundInfo()` always returns notification ID `0`
**Severity:** LOW-MEDIUM — notification collision

- `ForegroundInfo(0, ...)` uses a hardcoded notification ID of `0`
- If multiple `AutomationActionWorker` instances run concurrently (e.g., two SMS received simultaneously trigger two rules with delays), they both post a foreground notification with ID `0` — Android will show only one notification, and canceling one cancels the other
- **Possible fix:** Use a unique notification ID per worker instance (e.g., derived from the rule ID or a static counter).

---

## 11. Theme & Color System Bugs

### BUG-042 · `AppBaseTheme` and `AppThemeDialog` base parents changed to `Theme.MaterialComponents` — potential attribute conflicts with existing AppCompat views
**Severity:** HIGH — potential visual regressions or crashes on mixed view types

- `values/themes.xml`: `AppBaseTheme` parent changed from `Theme.AppCompat.Light.NoActionBar` to `Theme.MaterialComponents.Light.NoActionBar`
- `values-night/themes.xml`: `AppBaseTheme` parent changed from `Theme.AppCompat.NoActionBar` to `Theme.MaterialComponents.NoActionBar`
- `MaterialComponents` themes require views to use `MaterialComponents` equivalents — e.g., `AlertDialog` must be `MaterialAlertDialog`, `Button` must use M3 style
- Existing `AlertDialog.Builder(activity!!)` calls in `EditAutomationController` and other screens will now render as Material dialogs which may have different button placement/styling than expected
- `Widget.AppCompat.Button` styles used throughout may conflict with the MaterialComponents parent theme which expects `Widget.MaterialComponents.Button`
- **Possible fix:** Either update all dialog builders to `MaterialAlertDialog.Builder`, or keep `AppCompat` as parent and manually add only the M3 color attributes needed.

---

### BUG-043 · `values/themes.xml` — `AppTheme.Black`, `AppThemeDialog.Black`, `PopupTheme.Black` now have explicit parents but `values/` (light) variant is NOT the night/dark version
**Severity:** MEDIUM — wrong inheritance in light mode

- In `values/themes.xml` (day/light theme), these styles now have `parent="AppTheme"`, `parent="AppThemeDialog"`, `parent="PopupTheme"` respectively — they inherit from the light theme variants
- In `values-night/themes.xml` (dark theme), they correctly inherit from `AppTheme` (the dark one defined in that file)
- The `Black` variants in day mode now inherit from the **light** base theme — so "Black" mode in light system theme would show light backgrounds rather than black ones
- **Possible fix:** The `Black` variants in `values/themes.xml` should remain empty (inheriting nothing extra) or explicitly override only the black-specific colors, since they're meant to be used on top of the night theme.

---

### BUG-044 · Custom theme attributes (`cardBackground`, `cardBorder`, `glassBackground`, etc.) defined in `values/themes.xml` inside `<resources>` but declared in `values/attrs.xml`
**Severity:** LOW

- `values/themes.xml` contains `<attr name="switchTrackEnabled" format="color" />` and similar attribute declarations at the top of the file, mixed with style definitions
- These attribute declarations are duplicated from `values/attrs.xml` — having them in both places can cause "duplicate resource" aapt2 warnings/errors
- **Possible fix:** Remove attribute declarations from `themes.xml` and keep them only in `attrs.xml`.

---

### BUG-045 · `glassLight` color uses `#E6F8FAFC` — alpha prefix is 0xE6 (90%) but the hex is 8 characters, not 6
**Severity:** LOW-MEDIUM — potential color parsing confusion

- `<color name="glassLight">#E6F8FAFC</color>` — this is `ARGB` format: alpha=0xE6, R=0xF8, G=0xFA, B=0xFC
- This is valid Android color format, but it may be confused with `#FAFC` (4-char) or a different intent
- If the developer intended `backgroundLight` (`#F8FAFC`) with 90% opacity, the result is correct — but it should be documented to avoid future confusion
- The alpha channel in 8-character hex colors is often overlooked by developers reading the file

---

## 12. Process Interaction & End-to-End Flow Bugs

### BUG-046 · End-to-end automation flow: SMS received → rule triggers → delayed action → message already deleted by then
**Severity:** HIGH — race condition in delayed delete/archive

- Sequence: SMS arrives → `ReceiveSmsWorker` runs → matches DELETE rule with `delayMs > 0` → `scheduleAction()` enqueues `AutomationActionWorker` with a 5-minute delay
- In those 5 minutes: the user reads and manually deletes the message
- After 5 minutes: `AutomationActionWorker` runs `messageRepo.deleteMessages(listOf(messageId))` — `messageId` is now stale
- Realm's `deleteMessages()` likely does a `findFirst()` by ID — if not found, it silently succeeds (no-op) — this is OK
- BUT: if `deleteMessages()` throws when the message ID is not found, the worker returns `Result.failure()` — which WorkManager may retry, spinning multiple failed attempts
- **Possible fix:** Verify `messageRepo.deleteMessages()` handles non-existent IDs gracefully. Add an early-exit check in `AutomationActionWorker.doWork()` if the message no longer exists.

---

### BUG-047 · `ExecuteAutomations.execute()` — `incrementHitCount()` is called even when the action worker will later fail
**Severity:** MEDIUM — hit count is inaccurate

- `automationRepo.incrementHitCount(rule.id)` is called immediately when a rule matches, before the `AutomationActionWorker` runs
- If the worker fails (e.g., network down for HTTP forward, invalid phone number for SMS forward), the hit count still increments
- Users see a high hit count but no successful actions — misleading
- **Possible fix:** Increment hit count inside `AutomationActionWorker.doWork()` after successful execution, not before scheduling.

---

### BUG-048 · `ReceiveSmsWorker` — blocked conversation check happens BEFORE automation execution, but automations can archive/delete messages
**Severity:** MEDIUM — conflicting logic

- Sequence in `ReceiveSmsWorker.doWork()`:
  1. Fetch message
  2. Check blocking → mark read/blocked if blocked
  3. Check content filters → delete if filtered
  4. Run automations
  5. Update conversation, show notification
- If a message from a blocked sender also matches an automation rule, the message is first marked read and the conversation blocked (step 2), THEN automations run (step 4)
- The automation's `skipContacts` check uses `contactsRepo.isContact()` — NOT whether the conversation is blocked
- An automation DELETE rule could delete a message that was already handled by blocking
- The automation ARCHIVE rule could unblock an archived conversation's state by archiving it a different way
- **Possible fix:** Define clear precedence: blocking takes priority over automations. Add an early return after blocking action so automations don't run on blocked senders.

---

### BUG-049 · `AutomationsPresenter.toggleRuleClicks()` — `observeOn` and `subscribeOn` both set to `Schedulers.io()` but in wrong order
**Severity:** MEDIUM — potential threading issue

- Current code:
  ```kotlin
  view.toggleRuleClicks()
      .observeOn(Schedulers.io())
      .subscribeOn(Schedulers.io())
      .autoDisposable(view.scope())
      .subscribe { ... }
  ```
- `subscribeOn` controls the thread for the source observable — but `toggleRuleClicks()` emits from the main thread (UI clicks)
- `subscribeOn` on a subject/hot observable has no effect — the emission thread is already determined
- `observeOn(Schedulers.io())` does move downstream work to IO — so the `saveRule()` call is on IO, which is correct
- BUT: `automationRepo.getRule(ruleId)` inside the subscriber opens a new Realm instance on the IO thread, which is OK — however it then calls `saveRule(rule.apply { enabled = !enabled })` with a **mutable Realm object** — if `getRule()` returns a managed object (not a copy), mutating it outside a transaction is illegal
- `AutomationRuleRepositoryImpl.getRule()` does use `copyFromRealm()` — so this specific path is safe, but the code reads fragile

---

### BUG-050 · Settings navigation to Automations: `SettingsController.preferenceClicks()` → `SettingsPresenter` → `view.showAutomations()` chain has no error path
**Severity:** MEDIUM — crash if Conductor router state is unexpected

- `SettingsController.showAutomations()` calls `router.pushController(...)` with `AutomationsController()`
- `AutomationsController.init` calls `appComponent.inject(this)` — if Dagger component is not properly set up or `AutomationRuleRepository` binding is missing, this throws at inject time, crashing before the controller is shown
- `AutomationsController.onViewCreated()` calls `binding.fab.setBackgroundTint(colors.theme().theme)` — if `colors` is not injected, NPE
- No try-catch around the inject or the controller push
- **Possible fix:** Ensure `AutomationsController` and `EditAutomationController` are included in the Dagger component's `inject()` declarations.

---

### BUG-051 · `EditAutomationPresenter.setRuleId()` called from `onAttach()` BEFORE `bindIntents()` — state set before observables are subscribed
**Severity:** MEDIUM — first `render()` call may use stale state

- In `EditAutomationController.onAttach()`:
  ```kotlin
  presenter.setRuleId(ruleId)       // sets state immediately
  presenter.bindIntents(this)       // subscribes observables
  ```
- `setRuleId()` calls `newState { copy(...) }` which may emit through the state stream
- If the state subscriber (set up in `bindIntents()`) hasn't been registered yet, the first state emission is missed — the UI renders with default empty state instead of the loaded rule's values
- This is particularly problematic for edit (existing rule) mode — the form fields won't be pre-populated
- **Possible fix:** Call `bindIntents()` first, then `setRuleId()`. Or ensure `QkPresenter` buffers the last state and replays it to new subscribers (check if it uses `BehaviorSubject`).

---

## 13. Minor / Low-Severity Issues

### BUG-052 · `ReceiveSmsWorker` log message has Kotlin string template not evaluated
**Severity:** LOW

- Line 60: `Timber.v("failed. message id was {messageId}")` — uses curly braces instead of `$messageId`
- The actual message ID value is never logged, making debugging harder
- **Possible fix:** Change to `"failed. message id was $messageId"`.

---

### BUG-053 · `QkRealmMigration.SCHEMA_VERSION = 17` but only migrations 0–16 are defined
**Severity:** LOW — potential future migration gap

- Migrations go up to `version 16L` (creating `AutomationReplyState`) → increments to 17
- The `check(version >= SCHEMA_VERSION)` at the end ensures the migration chain reaches version 17 — which it does
- However, the check only validates `version >= SCHEMA_VERSION`, not `version == SCHEMA_VERSION` — if someone adds a migration step that increments to 18 but forgets to update `SCHEMA_VERSION`, the migration appears to succeed silently
- **Possible fix:** Use `check(version == SCHEMA_VERSION)` for strictness.

---

### BUG-054 · `AutomationRule.replyRateLimitMs` defaults to `86_400_000L` (24 hours) — but UI has no way to configure this value
**Severity:** MEDIUM — hidden behavior, no user control

- The rate limit defaults to 24 hours but the edit automation form has no field for configuring it
- Users cannot change the rate limit per rule — it's always 24 hours
- The delay chips (None / 30s / 1m / 5m / 1h) control `delayMs` (action delay), not `replyRateLimitMs` (rate limit)
- **Possible fix:** Add a rate limit field to the edit screen, or document the default behavior prominently in the UI.

---

### BUG-055 · `ic_search_black_24dp.xml` — drawable name contains "black" but is used in both light and dark themes
**Severity:** COSMETIC — naming inconsistency

- The search icon drawable `ic_search_black_24dp.xml` is tinted at runtime via `drawableTint` in the layout
- The "black" in the name is misleading — the drawable may be any color after tinting
- No functional bug, but can cause confusion for future developers

---

### BUG-056 · `changelog.json` updated but `build.gradle` version code/name not verified to match
**Severity:** LOW

- `data/src/main/assets/changelog.json` was updated in the diff to add a new changelog entry
- No corresponding version code bump was verified in `build.gradle` or `presentation/build.gradle`
- If the changelog references a version number that doesn't match the APK's `versionName`, the About screen may show inconsistent version information
- **Possible fix:** Verify version codes in both `build.gradle` files match the latest changelog entry.

---

## 14. Second-Pass Architectural, Lifecycle & Cross-Process Bugs

### BUG-057 · `EditAutomationController` — Conductor restoration crash: `ruleId` not saved in bundle
**Severity:** HIGH — crash on process death / back stack restoration
- The controller stores `ruleId` as a `private val` constructor parameter: `class EditAutomationController(private val ruleId: Long = -1L) : QkController<...>`.
- It defines a secondary constructor `constructor(bundle: Bundle) : this(bundle.getLong(KEY_RULE_ID, -1L))`, but never sets `args = Bundle()` and never overrides `onSaveInstanceState()`.
- In Conductor, when an activity/controller is restored from the back stack after process death, Conductor uses the zero-argument constructor (or passes the bundle stored in `args`). Because `args` was never set at creation time (`router.pushController(RouterTransaction.with(EditAutomationController(ruleId)))`), Conductor falls back to the default constructor where `ruleId = -1L`.
- Consequently, when the user rotates the device or the OS kills the process in the background, reopening the edit screen converts an existing rule edit into a new rule form (`ruleId = -1L`), discarding all current rule data and state.
- **Possible fix:** Pass `Bundle().apply { putLong(KEY_RULE_ID, ruleId) }` into `args` upon instantiation or initialize `args` in the primary constructor.

---

### BUG-058 · RxBinding `textChanges()` and `checkedChanges()` emit immediately on subscription — N×render() cascade
**Severity:** HIGH — UI stutter, race conditions on controller attachment
- `EditAutomationController` exposes 14 RxBinding streams (7 text changes, 6 checked changes, 1 radio selection).
- In `EditAutomationPresenter.bindIntents()`, each view intent is subscribed to update state: `.subscribe { v -> newState { copy(...) } }`.
- RxBinding 2.x `InitialValueObservable` types (`textChanges()`, `checkedChanges()`) emit the view's current value immediately upon subscription.
- On `bindIntents()`, all 14 observables fire synchronously, queuing 14 separate reducer invocations through `stateReducer`.
- This causes 14 consecutive state emissions and 14 `render()` passes in rapid succession.
- Worse: if an existing rule is being loaded, the initial view widgets are empty/default before the first render; the immediate emissions from empty UI widgets can race and overwrite the loaded rule state back to default blank values before the user can interact.
- **Possible fix:** Apply `.skipInitialValue()` or `.skip(1)` to all `textChanges()` and `checkedChanges()` observables in `EditAutomationController` before returning them to the presenter.

---

### BUG-059 · `EditAutomationController.render()` ↔ `checkedChanges()` re-entrancy on switch widgets
**Severity:** MEDIUM — potential state ping-pong and unnecessary render passes
- In `render()`: `binding.senderRegexSwitch.isChecked = state.matchSenderIsRegex` (and similarly for the other 5 switches).
- Setting `isChecked` programmatically triggers `checkedChanges()` on RxBinding, sending an event back to `presenter`, triggering `newState`, which triggers another `render()`.
- While `distinctUntilChanged()` helps terminate the loop once the state matches, intermediate animations or delayed MainThread dispatch can trigger extra render cycles.
- **Possible fix:** Temporarily detach listeners or suppress events during programmatic updates, or use `setOnCheckedChangeListener(null)` prior to mutating `isChecked` followed by reattaching the listener.

---

### BUG-060 · `EditAutomationController.render()` — `ruleName.setText()` races with user input
**Severity:** MEDIUM — input cursor jump or dropped keystrokes
- In `render()`: `if (binding.ruleName.text.toString() != state.name) binding.ruleName.setText(state.name)`.
- If the user types faster than the main thread event loop processes `textChanges()` → `newState` → `render()`, an older state emission can trigger `setText()` with an earlier substring, moving the cursor to position 0 or deleting recently typed characters.
- **Possible fix:** Treat the UI input field as the primary source of truth for text during active editing, only updating the EditText from `render()` if the controller is in initial load mode or if the change originated externally.

---

### BUG-061 · `ReceiveMmsWorker` — duplicate `markUnarchived()` call
**Severity:** LOW-MEDIUM — redundant database write
- In `ReceiveMmsWorker.kt` (lines 219–226):
  ```kotlin
  if (conversation.archived)
      conversationRepo.markUnarchived(listOf(conversation.id))

  if (conversation.archived) {
      Timber.v("conversation unarchived")
      conversationRepo.markUnarchived(listOf(conversation.id))
  }
  ```
- Because `conversation` is a cached/managed Realm snapshot that is not refreshed between these two statements, both `if` checks evaluate to true, executing the unarchive transaction twice sequentially.
- **Possible fix:** Remove the duplicate first `if` statement.

---

### BUG-062 · Inconsistent automation execution boundary between `ReceiveSmsWorker` and `ReceiveMmsWorker`
**Severity:** MEDIUM — behavioral divergence between SMS and MMS
- In `ReceiveSmsWorker`: automations execute for all messages unless dropped by content filters, running even if the conversation was marked blocked (as long as `prefs.drop.get()` is false).
- In `ReceiveMmsWorker`: automations are placed inside an `else` branch of blocking checks, meaning if blocking logic handles the message, automations may be skipped or executed under different conditions compared to SMS.
- An auto-reply or forward rule will behave differently depending on whether the incoming message was SMS or MMS from the same sender.
- **Possible fix:** Standardize the pipeline sequence across both workers: `Download/Persist -> ContentFilter -> Blocking -> Automations -> Conversation/Notification Sync`.

---

### BUG-063 · `ReceiveMmsWorker` — image/media-only MMS has empty body in automation matching
**Severity:** MEDIUM — rule match failure for media MMS
- In MMS processing, `message.getText()` extracts text from parts where `type == "text/plain"`.
- If an incoming MMS contains only an image, audio, or video attachment with no text body, `message.getText()` returns `""`.
- Any automation rule configured with body matching keywords will fail to trigger, even if the MMS has a subject line or attachment name.
- **Possible fix:** Include `message.subject` or an attachment descriptor in `message.getText()` when evaluating automation rules.

---

### BUG-064 · `main_activity.xml` — `toolbarSearch` focus navigation anomaly
**Severity:** LOW-MEDIUM — keyboard/D-pad navigation trap
- `toolbarSearch` has `android:focusable="true"` and `android:nextFocusRight="@+id/compose"`.
- On hardware keyboard or TV/D-pad devices, pressing right from the search bar shifts focus directly to the compose FAB at the bottom right of the screen rather than into the conversation list or toolbar actions.
- **Possible fix:** Remove hardcoded `nextFocusRight` and allow the focus engine to use natural spatial hierarchy.

---

### BUG-065 · `main_activity.xml` — `android:drawableTint` on `toolbarSearch`
**Severity:** LOW — OEM compatibility risk
- `toolbarSearch` specifies `android:drawableTint="?android:attr/textColorTertiary"`.
- On certain Android 8.0/8.1 OEM skins (API 26/27), `android:drawableTint` on `EditText` inside layout XML fails to resolve theme attributes correctly, resulting in an invisible or black search icon.
- **Possible fix:** Use `app:drawableTint` or apply tint programmatically via `TextViewCompat.setCompoundDrawableTintList()`.

---

### BUG-066 · `conversation_list_item.xml` — `title` has `app:layout_constrainedWidth="true"` but `snippet` does not
**Severity:** LOW — layout measurement asymmetry
- In `conversation_list_item.xml`, `title` sets `app:layout_constrainedWidth="true"`, ensuring it respects start and end anchors when text is long.
- `snippet` lacks `app:layout_constrainedWidth="true"`, relying solely on `layout_width="0dp"`. While `0dp` creates a match-constraint view, missing explicit constraint enforcement across nested chains can cause measurement glitches when right-side icons toggle visibility.
- **Possible fix:** Add `app:layout_constrainedWidth="true"` to `snippet`.

---

### BUG-067 · `conversation_list_item.xml` — GONE chain collapse between `pinned`, `scheduled`, `unread`
**Severity:** MEDIUM — icon overlap and alignment drift
- The right-side icons (`pinned`, `scheduled`, `unread`) are chained horizontally with each other, and `snippet` ends at `@id/pinned`.
- When `pinned` is `GONE`, it collapses to 0-width at its constraint position. If `scheduled` is visible but `pinned` is gone, `snippet` end constraint resolves to the collapsed position of `pinned`, which can overlap with `scheduled`.
- **Possible fix:** Anchor `snippet`'s end constraint to a `Barrier` placed to the start of all three status icons (`pinned`, `scheduled`, `unread`).

---

### BUG-068 · `AutomationsState` data class equality with `RealmResults`
**Severity:** MEDIUM — RxJava `distinctUntilChanged()` failure
- `AutomationsState` is declared as `data class AutomationsState(val rules: RealmResults<AutomationRule>? = null)`.
- `RealmResults` does not implement structural `equals()`; it relies on identity/reference comparison.
- When `RealmResults` is updated internally by Realm's background thread, the object reference remains identical. Any presenter trying to emit a new state holding the same `RealmResults` instance will evaluate as equal to the previous state, causing `distinctUntilChanged()` to drop state updates.
- **Possible fix:** Pass an immutable list snapshot `rules = realm.copyFromRealm(results)` or expose a revision counter/timestamp in `AutomationsState`.

---

### BUG-069 · `AutomationRulesAdapter` empty listener cast vulnerability
**Severity:** LOW — potential ClassCastException during background sync
- `QkRealmAdapter` sets an `emptyListener` that casts the collection to `OrderedRealmCollection<T>`.
- If a custom collection or snapshot is passed that does not implement `OrderedRealmCollection`, a runtime `ClassCastException` is thrown.
- **Possible fix:** Guard with `if (data is OrderedRealmCollection<T>)` before attaching the change listener.

---

### BUG-070 · `AutomationRuleRepositoryImpl.saveRule()` mutates caller object and risks primary key collision
**Severity:** MEDIUM — data inconsistency and unexpected object mutation
- In `saveRule()`:
  ```kotlin
  val id = if (rule.id == 0L) {
      (realm.where(AutomationRule::class.java).max("id")?.toLong() ?: -1L) + 1L
  } else {
      rule.id
  }
  realm.executeTransaction {
      val managed = realm.copyToRealmOrUpdate(rule.apply { this.id = id })
      ...
  }
  ```
- Mutating `rule.apply { this.id = id }` modifies the caller's in-memory instance directly. If the transaction rolls back or fails, the caller's object has an invalid ID assigned.
- Furthermore, calculating `max("id") + 1` outside the Realm transaction is prone to race conditions if multiple workers or threads save rules concurrently.
- **Possible fix:** Perform the ID calculation strictly inside the `executeTransaction` block, or use a dedicated atomic sequence generator.

---

### BUG-071 · `InjectionWorkerFactory.createWorker()` missing fallback branch
**Severity:** HIGH — uninitialized dependencies on new or unknown workers
- `createWorker()` uses a `when (instance)` statement matching `HousekeepingWorker`, `ReceiveSmsWorker`, `AutomationActionWorker`, and `ReceiveMmsWorker`.
- There is no `else` or default handler. If any new worker is scheduled, its dependencies will never be injected, causing an immediate crash with `UninitializedPropertyAccessException` when the worker starts.
- **Possible fix:** Add an explicit `else -> Unit` or log a warning if an unrecognized worker class is instantiated.

---

### BUG-072 · `AutomationActionWorker` SMS sending fails if app is not default SMS handler
**Severity:** HIGH — silent operational failure
- Both `ACTION_FORWARD_SMS` and `ACTION_AUTO_REPLY` call `messageRepo.sendNewMessages()`.
- On Android 4.4+, only the default SMS application is permitted to write to the SMS Telephony provider and dispatch SMS messages without user confirmation.
- If QUIK is not currently the default SMS app (e.g. running in secondary mode or after user changed defaults), `sendNewMessages()` will throw a `SecurityException` or fail silently.
- The worker catches generic `Exception` and returns `Result.failure()`, giving no diagnostic indication to the user.
- **Possible fix:** Check `Telephony.Sms.getDefaultSmsPackage(context)` before attempting SMS transmission; post a system notification if the required default role is missing.

---

### BUG-073 · `AutomationActionWorker.getForegroundInfo()` early invocation risk
**Severity:** MEDIUM — potential NullPointerException on Android 12+
- On Android 12+, WorkManager may invoke `getForegroundInfo()` before `doWork()`.
- `getForegroundInfo()` calls `notificationManager.getForegroundNotificationForWorkersOnOlderAndroids()`.
- If WorkManager instantiates the worker outside of `InjectionWorkerFactory` (e.g. via default reflection fallback in test or error recovery scenarios), `notificationManager` is an uninitialized `lateinit` variable, crashing the process.
- **Possible fix:** Provide a fallback default notification if `notificationManager` has not been initialized.

---

### BUG-074 · Drawer navigation crash root cause: Theme color reference failure (BUG-001)
**Severity:** CRITICAL — root cause of reported drawer menu crashes
- Re-examination of `drawer_view.xml` reveals that `android:clickable="true"` on the root `ScrollView` does not intercept clicks on child `LinearLayout` elements that have their own clickable backgrounds (`?attr/selectableItemBackground`).
- The actual crash when tapping "Settings", "Blocked List", or other drawer entries occurs because clicking an item causes `MainActivity` to navigate to the target controller (e.g., `SettingsController`), which inflates a layout referencing `AppBaseTheme`.
- Because `AppBaseTheme` and `AppThemeDialog` reference `@color/text_primary` (which does not exist; only `textPrimary` exists), Android's resource system throws an `InflateException` / `Resources$NotFoundException` upon creating the new screen's view hierarchy.
- **Possible fix:** Resolve BUG-001 by adding alias or renaming `@color/text_primary` -> `@color/textPrimary`.

---

### BUG-075 · `DrawerRow` style lacks explicit `android:orientation`
**Severity:** COSMETIC — style ambiguity
- In `styles.xml`, `DrawerRow` sets height, width, padding, background, and gravity, but omits `android:orientation`.
- While `LinearLayout` defaults to `horizontal`, defining orientation explicitly prevents layout corruption if the style is applied to a custom container or layout subclass.
- **Possible fix:** Add `<item name="android:orientation">horizontal</item>` to `DrawerRow`.

---

### BUG-076 · `settings_controller.xml` — `ic_screenshot` drawable and typo in string name
**Severity:** MEDIUM — resource lookup failure
- `settings_controller.xml` references `app:icon="@drawable/ic_screenshot"`. If this vector asset is missing from `presentation/src/main/res/drawable/`, inflating `settings_controller.xml` will fail.
- It also references `@string/settings_diable_screenshots_summary` (note spelling "diable" instead of "disable"). While the string resource currently exists with the typo, any cleanup or refactor will break layout compilation.
- **Possible fix:** Verify `ic_screenshot.xml` exists; fix the typo in both `strings.xml` and `settings_controller.xml`.

---

### BUG-077 · `settings_controller.xml` — `@android:drawable/stat_sys_warning` platform drawable usage
**Severity:** LOW-MEDIUM — OEM UI fragmentation
- `settings_controller.xml` sets `app:icon="@android:drawable/stat_sys_warning"` on `messsageLinkHandling`.
- Platform drawables are not guaranteed to have consistent visual styles, dimensions, or dark-mode color tints across different OEM firmware (Samsung OneUI, Xiaomi MIUI, Google Pixel).
- **Possible fix:** Bundle a local vector icon (`ic_warning_24dp.xml`) instead of referencing private/platform Android drawables.

---

### BUG-078 · `values/themes.xml` (light) vs `values-night/themes.xml` (dark) inheritance mismatch for `AppTheme.Black`
**Severity:** MEDIUM — black theme broken in light system mode
- In `values/themes.xml`: `<style name="AppTheme.Black" parent="AppTheme" />` inherits from the light `AppTheme`.
- In `values-night/themes.xml`: `<style name="AppTheme.Black" parent="AppTheme">` inherits from the dark `AppTheme`.
- When the user enables "AMOLED Black" theme while Android system day/night mode is set to Day (Light), the app resolves `AppTheme.Black` from `values/themes.xml`, resulting in a pure white background instead of true black.
- **Possible fix:** Ensure `AppTheme.Black` in `values/themes.xml` explicitly inherits from a dark base theme or defines the black background attributes.

---

### BUG-079 · `ExecuteAutomations.matches()` — empty address matching edge case
**Severity:** MEDIUM — false positives on unknown senders
- When an MMS or service broadcast arrives without a valid sender address, `message.address` evaluates to `""`.
- If an automation rule has `matchSender = ""` (meaning match any sender), it evaluates to true.
- However, if `matchSenderIsRegex = true` with a pattern like `.*` or `^$`, it will also match an empty address. If the user created a rule meant for real phone numbers, it will fire unexpectedly on malformed carrier notifications.
- **Possible fix:** Ensure empty addresses are explicitly flagged or rejected when rules specify contact-filtering or phone-based triggers.

---

### BUG-080 · `ExecuteAutomations.scheduleAction()` NEVER passes `KEY_SENDER_ADDRESS` — Auto-reply ALWAYS fails!
**Severity:** CRITICAL — complete functional failure of auto-reply & sender attribution
- In `ExecuteAutomations.kt` (`scheduleAction` method, lines 157–170):
  ```kotlin
  val data = Data.Builder()
      .putLong(KEY_RULE_ID, rule.id)
      .putLong(KEY_MESSAGE_ID, message.id)
      .putLong(KEY_THREAD_ID, message.threadId)
      .putString(KEY_ACTION, rule.action)
      .putString(KEY_FORWARD_PHONE, rule.forwardToPhone)
      .putString(KEY_FORWARD_URL, rule.forwardToUrl)
      .putBoolean(KEY_APPEND_SENDER, rule.appendSenderToForward)
      .putString(KEY_REPLY_TEMPLATE, rule.replyTemplate)
      .putInt(KEY_SUB_ID, message.subId)
      .putString(KEY_MESSAGE_BODY, message.getText().take(MAX_BODY_LENGTH))
      .build()
  ```
- **`KEY_SENDER_ADDRESS` is completely missing from `Data.Builder()`!**
- In `AutomationActionWorker.kt` (lines 52 and 102):
  ```kotlin
  val senderAddress = inputData.getString(KEY_SENDER_ADDRESS) ?: ""
  ...
  AutomationRule.ACTION_AUTO_REPLY -> {
      val template = inputData.getString(KEY_REPLY_TEMPLATE) ?: return Result.failure()
      if (template.isBlank() || senderAddress.isBlank()) return Result.failure()
  ```
- Because `KEY_SENDER_ADDRESS` was never inserted, `senderAddress` is ALWAYS empty (`""`).
- For `ACTION_AUTO_REPLY`: `senderAddress.isBlank()` is ALWAYS true -> Worker **ALWAYS returns `Result.failure()`**. Auto-reply CANNOT EVER WORK in the current codebase!
- For `ACTION_FORWARD_SMS`: `"From: $senderAddress | $messageBody"` outputs `"From:  | $messageBody"`.
- For `ACTION_FORWARD_HTTP`: webhook JSON payload sends `{"from":"","body":"...","timestamp":...}`.
- **Possible fix:** Add `.putString(KEY_SENDER_ADDRESS, message.address)` to `Data.Builder()` in `ExecuteAutomations.scheduleAction()`.

---

### BUG-081 · Delayed actions on deleted messages in MMS
**Severity:** MEDIUM — orphaned attachment files and broken forward payloads
- If an MMS is scheduled for delayed forwarding (`delayMs > 0`), the user or another automation rule might delete the message or its thread before the delay expires.
- When `AutomationActionWorker` runs, the message record in Realm is gone. While `messageBody` was serialized into `inputData`, any MMS media parts stored on disk may be deleted or inaccessible, preventing proper forwarding.
- **Possible fix:** Check if the message or thread still exists before attempting delayed actions; handle missing records gracefully with `Result.success()`.

---

### BUG-082 · `EditAutomationController.showPresets()` uses `activity!!`
**Severity:** MEDIUM — crash on orientation change while dialog is opening
- `AlertDialog.Builder(activity!!)` forces non-null on `activity`.
- If the device rotates or the user navigates away at the instant `showPresets()` is invoked, `activity` is null, resulting in an immediate `KotlinNullPointerException`.
- **Possible fix:** Safely unwrap `val act = activity ?: return` before constructing the dialog.

---

### BUG-083 · `AutomationsPresenter.deleteRuleClicks()` lacks confirmation prompt
**Severity:** MEDIUM — destructive action without guard
- When the delete trash icon is tapped on an automation rule card, `deleteRuleClicks()` directly triggers `automationRepo.deleteRule(ruleId)`.
- There is no confirmation dialog, snackbar undo, or safeguard. A single accidental touch permanently destroys the automation rule.
- **Possible fix:** Route deletion through a confirmation dialog in `AutomationsView`, matching the rest of the application.

---

### BUG-084 · Dual title overlap on child controllers
**Severity:** LOW-MEDIUM — visual glitch in top app bar
- `MainActivity` enforces `binding.toolbarTitle.setVisible(true)` on every render pass with text `"Messages"`.
- When child controllers (such as `SettingsController` or `AutomationsController`) are pushed onto the Conductor router, they call `setTitle(...)`, which sets `activity?.title`.
- In `main_activity.xml`, `toolbarTitle` is an independent `QkTextView` child of `Toolbar`. Setting `activity.title` affects the Toolbar's internal title TextView, while `binding.toolbarTitle` remains visible simultaneously, causing overlapping double titles.
- **Possible fix:** Set `toolbarTitle.setVisible(false)` when navigating to child screens, or use the standard Toolbar title consistently.

---

### BUG-085 · `AutomationActionWorker.sendNewMessages()` return value ignored
**Severity:** LOW-MEDIUM — lack of error propagation
- `messageRepo.sendNewMessages()` returns the list of newly created `Message` objects.
- `AutomationActionWorker` ignores the return value and unconditionally returns `Result.success()`. If message creation failed or the recipient address was invalid, the worker reports success regardless.
- **Possible fix:** Validate that the returned message collection is not empty before returning `Result.success()`.

---

## 15. Re-evaluation & Refined Root Causes of Prior Findings

| Original ID | Previous Assessment | Deeper Architectural Re-evaluation |
|-------------|---------------------|-----------------------------------|
| **BUG-003** | Assumed `clickable="true"` on drawer root consumed menu item clicks | **Re-attributed to BUG-001**: `ScrollView` with `clickable="true"` does NOT block clicks on child `LinearLayout` rows with `?attr/selectableItemBackground`. The crash on tapping drawer items (Settings, Blocked list) is actually an `InflateException` caused by `@color/text_primary` missing from `colors.xml` during theme resolution in the newly launched screens. |
| **BUG-008** | Assumed compose FAB had no ripple feedback | **Downgraded**: `fab_m3_background.xml` is actually an `<ripple>` drawable with a 16dp rounded shape. Visual ripple feedback functions properly; however, using an `ImageView` instead of `FloatingActionButton` still lacks proper accessibility button role and elevation animators. |
| **BUG-028** | Assumed `Class.forName(WORKER_CLASS)` would break under ProGuard | **Downgraded from HIGH to LOW**: `presentation/proguard-rules.pro` contains `-keep class dev.octoshrimpy.quik.** { *; }` which preserves all classes in the app package. Reflection will succeed, though it remains fragile to future package refactors. |
| **BUG-039** | Assumed Worker `@Inject` fields would be null at runtime | **Re-evaluated**: `InjectionWorkerFactory.kt` lines 84–88 explicitly assigns `messageRepo`, `conversationRepo`, and `notificationManager` directly onto `AutomationActionWorker` during `createWorker()`. The fields are safely populated, provided WorkManager utilizes the custom factory. |

---

## 16. Comprehensive Summary Matrix (All 85 Bugs)

| ID | Area | Severity | Type | Description Summary |
|----|------|----------|------|---------------------|
| BUG-001 | Colors/Resources | **CRITICAL** | Resource Crash | `@color/text_primary` undefined; `colors.xml` uses `textPrimary` |
| BUG-002 | Settings Layout | HIGH | Binding / Layout | Typo `messsageLinkHandling` (triple 's') in layout ID |
| BUG-003 | Navigation | **CRITICAL** | Crash on Navigation | Drawer items crash when clicked (theme inflation crash via BUG-001) |
| BUG-004 | Navigation | HIGH | Gesture Glitch | Drawer swipe-to-close blocked or missing listener |
| BUG-005 | Navigation | HIGH | Navigation State | `MainActivity` drawer listener registration incomplete |
| BUG-006 | Main Layout | HIGH | Layout Overlap | `toolbarTitle` always visible; overlaps search bar |
| BUG-007 | Main Layout | MEDIUM-HIGH | Layout Jump | `toolbarSearch` outside toolbar collapses recyclerView space |
| BUG-008 | Main Layout | LOW-MEDIUM | Accessibility | Compose FAB is `ImageView` without accessibility button role |
| BUG-009 | Main Layout | LOW-MEDIUM | Visual Depth | Toolbar shadow/elevation view removed without replacement |
| BUG-010 | List Item | HIGH | Layout Overflow | `snippet` constraint chain ends at `@id/pinned` instead of right margin |
| BUG-011 | List Item | MEDIUM | Recycler Jitter | `snippet` `maxLines="2"` causes variable-height scrolling jitter |
| BUG-012 | List Item | MEDIUM | Visual Alignment | `pinned` and `scheduled` icons anchored to bottom of 2-line snippet |
| BUG-013 | List Item | MEDIUM | Constraint Overflow | `date` view missing explicit horizontal constraint anchors |
| BUG-014 | List Item | LOW-MEDIUM | Accessibility | `unread` indicator shrunk to 10×10dp (sub-standard touch/visual target) |
| BUG-015 | Settings | MEDIUM | Event Routing | `findPreferences()` recursive search needed for card-wrapped prefs |
| BUG-016 | Settings | LOW-MEDIUM | UX Positioning | `syncingProgress` moved inside cards instead of bottom anchor |
| BUG-017 | Settings | LOW | Alignment | `PreferenceCategory` indent reduced from 72dp to 24dp |
| BUG-018 | Edit Automation | MEDIUM | Theming | Form switches use `SwitchCompat` instead of themed `QkSwitch` |
| BUG-019 | Edit Automation | MEDIUM | UI State | Delay buttons use `Button.Borderless`; no visual selected feedback |
| BUG-020 | Edit Automation | MEDIUM | State Machine | Preset selection bypasses presenter state machine |
| BUG-021 | Edit Automation | LOW-MEDIUM | Validation | Bare `EditText` used without input validation or error indicators |
| BUG-022 | Edit Automation | LOW | Responsive UI | `GridLayout` delay selector overflows or aligns awkwardly on small screens |
| BUG-023 | QkSwitch | MEDIUM | Visual Design | Switch thumb/track colors inverted relative to Material 3 standard |
| BUG-024 | AvatarView | MEDIUM | View Overlap | Initial text and contact photo can become visible simultaneously |
| BUG-025 | AvatarView | LOW | Performance | Double circular cropping via Glide and custom shape |
| BUG-026 | ComposeActivity | LOW-MEDIUM | Visual Theming | Send button tint missing on initial render pass |
| BUG-027 | Automation Engine | HIGH | Realm Threading | Managed Realm `Message` accessed cross-thread in worker |
| BUG-028 | Automation Engine | LOW | Reflection Fragility | `Class.forName()` string worker class resolution (mitigated by proguard) |
| BUG-029 | Automation Engine | HIGH | Logic Premature Exit | `ACTION_DELETE` returns immediately, skipping remaining rules |
| BUG-030 | Automation Engine | MEDIUM | Rate Limiting | Rate limiting keyed by rule+thread, allowing cross-rule duplicate replies |
| BUG-031 | Automation Engine | MEDIUM | Resource Overhead | Spawns single-thread executor per regex match per incoming message |
| BUG-032 | Automation Engine | MEDIUM | Validation Gap | Malformed webhook URLs crash worker without user feedback |
| BUG-033 | Automation Engine | MEDIUM | Dual-SIM Logic | SMS forwarding does not validate or allow selecting active subId |
| BUG-034 | Automation Engine | LOW | Feature Gap | Auto-reply only replaces `{sender}`, no `{body}` or `{date}` variables |
| BUG-035 | Repository | MEDIUM | Data Observation | `getRules()` live `RealmResults` needs active adapter change listener |
| BUG-036 | Repository | HIGH | Resource Leak | `getRules()` opens Realm instance without lifecycle closure |
| BUG-037 | Adapter | MEDIUM | Deprecated API | `adapterPosition` deprecated and returns `NO_POSITION` (-1) during animation |
| BUG-038 | Repository | MEDIUM | Race Condition | `max("id") + 1` primary key calculation vulnerable to concurrent race |
| BUG-039 | Worker | LOW | Injection Timing | Field injection verified manual in factory, but requires factory use |
| BUG-040 | Worker | MEDIUM | Serialization Bug | Webhook JSON builds sender string without `JSONObject.quote()` |
| BUG-041 | Worker | LOW-MEDIUM | Notification Conflict | Foreground notification ID hardcoded to `0` across all worker tasks |
| BUG-042 | Theme | HIGH | Theme Compatibility | `Theme.MaterialComponents` base conflicts with legacy dialog builders |
| BUG-043 | Theme | MEDIUM | Theme Inheritance | `AppTheme.Black` inherits from light theme when in day mode |
| BUG-044 | Theme | LOW | Resource Duplication | Duplicate `<attr>` declarations between `themes.xml` and `attrs.xml` |
| BUG-045 | Colors | LOW-MEDIUM | Color Parsing | 8-character ARGB hex formats (`#E6F8FAFC`) easily misread |
| BUG-046 | E2E Flow | HIGH | Race Condition | Delayed delete/archive actions fail if message already deleted |
| BUG-047 | E2E Flow | MEDIUM | Metric Error | Hit count increments on match before worker actually succeeds |
| BUG-048 | E2E Flow | MEDIUM | Logic Conflict | Blocking and automation rule execution order conflicting |
| BUG-049 | Threading | MEDIUM | Rx Threading | `subscribeOn` has no effect on hot UI click subjects |
| BUG-050 | Navigation | MEDIUM | Architecture | Missing error boundary if Dagger injection fails in new controllers |
| BUG-051 | State | MEDIUM | Lifecycle Order | `setRuleId()` called before `bindIntents()` in controller attach |
| BUG-052 | Logging | LOW | Syntax Bug | Log string template uses literal `{messageId}` instead of `$messageId` |
| BUG-053 | Schema | LOW | Validation Rigor | Realm migration checks `>=` rather than strict `==` target version |
| BUG-054 | UX Gap | MEDIUM | Configuration Gap | `replyRateLimitMs` hardcoded to 24 hours with no user configuration |
| BUG-055 | Naming | COSMETIC | Resource Naming | Drawable `ic_search_black_24dp.xml` named black but dynamically tinted |
| BUG-056 | Build | LOW | Version Parity | Changelog entries out of sync with gradle version code/name |
| BUG-057 | Conductor | HIGH | State Loss | `EditAutomationController` loses `ruleId` on process restoration |
| BUG-058 | RxBinding | HIGH | UI Cascade | 14 RxBinding inputs fire on subscribe, flooding presenter with renders |
| BUG-059 | State Machine | MEDIUM | Feedback Loop | Programmatic switch toggles trigger `checkedChanges()` feedback loop |
| BUG-060 | State Machine | MEDIUM | Input Race | Asynchronous `setText()` overwrites user's in-progress typing |
| BUG-061 | MMS Worker | LOW-MEDIUM | Database Waste | Duplicate `markUnarchived` call in MMS receive worker |
| BUG-062 | MMS Worker | MEDIUM | Pipeline Divergence | SMS vs MMS automation execution placement differs around blocking |
| BUG-063 | Automation Engine | MEDIUM | Data Handling | Image-only MMS produces empty body text for keyword matching |
| BUG-064 | Layout | LOW-MEDIUM | Focus Trap | Redundant `focusable` on search EditText + inappropriate `nextFocusRight` |
| BUG-065 | Layout | LOW | OEM Glitch | `android:drawableTint` on EditText fails on certain API 26 ROMs |
| BUG-066 | Layout | LOW | Constraint Asymmetry | `snippet` missing `app:layout_constrainedWidth="true"` |
| BUG-067 | Layout | MEDIUM | Constraint Chain | Right-side status icons collapse unpredictably when `pinned` is GONE |
| BUG-068 | State | MEDIUM | Deduplication | `AutomationsState` data class equality fails on `RealmResults` |
| BUG-069 | Adapter | LOW | Crash Risk | Unchecked cast to `OrderedRealmCollection` in empty view listener |
| BUG-070 | Repository | MEDIUM | Data Mutation | `saveRule()` mutates caller's unmanaged object before save |
| BUG-071 | Worker Factory | HIGH | Architecture | `InjectionWorkerFactory` lacks fallback branch for unknown workers |
| BUG-072 | Worker | HIGH | Permission / Role | SMS forward/reply fails silently if app is not default SMS handler |
| BUG-073 | Worker | MEDIUM | Android 12 Lifecycle | `getForegroundInfo()` invoked before injection in expedited work |
| BUG-074 | Navigation | **CRITICAL** | Root Cause Clarified | Drawer crashes caused by missing color resource (BUG-001) |
| BUG-075 | Style | COSMETIC | Style Completeness | `DrawerRow` style omits explicit horizontal orientation |
| BUG-076 | Resources | MEDIUM | Resource Missing | `ic_screenshot` drawable existence & typo in summary string ID |
| BUG-077 | Resources | LOW-MEDIUM | UI Consistency | Platform drawable `@android:drawable/stat_sys_warning` inconsistent across OEMs |
| BUG-078 | Theme | MEDIUM | Theme Glitch | AMOLED Black theme resolves to light parent when system is in day mode |
| BUG-079 | Automation Engine | MEDIUM | Edge Case | Empty sender addresses match wildcard regexes unintentionally |
| BUG-080 | **Automation Engine** | **CRITICAL** | **Data Loss / Fatal Bug** | **`KEY_SENDER_ADDRESS` never added to worker data — Auto-reply ALWAYS FAILS** |
| BUG-081 | Automation Engine | MEDIUM | Orphaned State | Delayed actions on MMS whose records or parts are deleted |
| BUG-082 | Conductor | MEDIUM | Null Pointer | `showPresets()` force-unwraps `activity!!` which can be null |
| BUG-083 | UX | MEDIUM | Destructive Action | Rule deletion lacks confirmation prompt or undo snackbar |
| BUG-084 | Navigation | LOW-MEDIUM | App Bar Glitch | Custom `toolbarTitle` and ActionBar title display simultaneously |
| BUG-085 | Worker | LOW-MEDIUM | Silent Error | `sendNewMessages()` return value ignored in action worker |

---

*Report updated with second-pass architectural, Conductor lifecycle, RxBinding stream, and WorkManager data-flow analysis. Total findings: 85 bugs across all layers.*

