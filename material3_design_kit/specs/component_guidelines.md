# Material 3 Component Specifications for QUIK (Google Messages Theme)

This document provides exact Material 3 guidelines and token mappings for the QUIK Android redesign, matching the design of Google Messages and Google Pixel system apps.

---

## 1. Top App Bar & Messages Header

### Layout & Sizing
* **Height:** `64dp` (M3 standard small top app bar; can be `56dp` if compact).
* **Title Typography:** `title_large` (22sp, Regular/Medium, line-height 28sp).
* **Title Text Color:** `?attr/colorOnSurface` (Light: `#1D1B20`, Dark: `#E6E0E9`).
* **Background:** `?attr/colorSurface` (level 0 elevation when stationary; level 2 `?attr/colorSurfaceContainer` when content scrolls under it).
* **Navigation Icon:** Hamburger menu icon (`24dp × 24dp`), tint `?attr/colorOnSurface`.

### Implementation Rules
* Do **not** keep a custom TextView overlapping the search bar. The title and the search bar must be mutually exclusive or cleanly stacked (in Google Messages, the search bar is either embedded in the top bar or acts as the top bar itself).

---

## 2. Pill Search Bar

### Layout & Sizing
* **Height:** `48dp` (compact) to `56dp` (standard M3 search bar).
* **Corner Radius:** Full round / pill shape (`28dp` radius on 56dp height, or `24dp` on 48dp height).
* **Background Color:** `?attr/colorSurfaceContainerHigh` or `?attr/colorSurfaceContainerHighest` (Light: `#ECE6F0`, Dark: `#2B2930` to `#36343B`).
* **Elevation:** `0dp` when resting, `3dp` when elevated / scrolling.
* **Horizontal Margins:** `16dp` start and end.
* **Content Padding:** `16dp` start, `16dp` end.
* **Search Icon:** `24dp × 24dp` leading icon, padding `12dp`, tint `?attr/colorOnSurfaceVariant` (`#49454F` / `#CAC4D0`).
* **Text / Hint Typography:** `body_large` (16sp, regular).
* **Text Color:** `?attr/colorOnSurface`.
* **Hint Color:** `?attr/colorOnSurfaceVariant`.

---

## 3. Conversation List Item

### Layout & Sizing
* **Row Height:**
  * Single line snippet: `72dp` (M3 standard 2-line list item: contact name + snippet).
  * Double line snippet: `88dp` (M3 standard 3-line list item).
* **Horizontal Padding:** `16dp` start and end.
* **Vertical Padding:** `12dp` top and bottom.
* **Background:** `?attr/selectableItemBackground` (ripple on tap).

### Elements
1. **Avatar (Leading):**
   * Size: `48dp × 48dp` or `52dp × 52dp`.
   * Shape: Full circle (`shape.full`).
   * Background: Monogram initials or contact photo. Initial text `title_medium` (16sp, bold), centered.
2. **Contact Name (Title):**
   * Typography: `title_medium` (16sp, Medium 500, line-height 24sp).
   * Color: `?attr/colorOnSurface` (read) or `?attr/colorOnSurface` + bold (unread).
   * Single line, ellipsize end.
3. **Snippet (Body):**
   * Typography: `body_medium` (14sp, Regular 400, line-height 20sp).
   * Color: `?attr/colorOnSurfaceVariant` (read) or `?attr/colorOnSurface` (unread).
   * Max lines: `1` (clean, predictable item heights) or `2` (detailed).
4. **Date / Timestamp (Trailing top):**
   * Typography: `label_small` or `body_small` (12sp, Regular).
   * Color: `?attr/colorOnSurfaceVariant` (read) or `?attr/colorPrimary` (unread).
5. **Status Icons (Pinned / Scheduled / Unread):**
   * Trailing row or inline with snippet.
   * `unread`: `10dp` or `12dp` solid dot, tinted `?attr/colorPrimary`.
   * `pinned`: `18dp × 18dp`, tint `?attr/colorOnSurfaceVariant`.
   * `scheduled`: `18dp × 18dp`, tint `?attr/colorOnSurfaceVariant`.
   * All status icons must be grouped using a `Barrier` so that `snippet` text wraps cleanly without overlapping icons when visibility changes.

---

## 4. Navigation Drawer (Modal Drawer)

### Layout & Sizing
* **Width:** `320dp` (or `360dp` on wider screens).
* **Corner Radius:** Trailing top & bottom corners: `16dp` (`shape.large`) or `28dp` (`shape.extra_large`).
* **Container Background:** `?attr/colorSurfaceContainerLow` (Light: `#F7F2FA`, Dark: `#1D1B20`).
* **Elevation:** `1dp` to `2dp` (`level_1` to `level_2`).

### Navigation Rows
* **Height:** `56dp` (standard M3 drawer destination item).
* **Horizontal Margins:** `12dp` start and end (floating active indicator).
* **Corner Radius of Item Indicator:** `28dp` (full pill shape).
* **Selected Item Background:** `?attr/colorSecondaryContainer` (Light: `#E8DEF8`, Dark: `#4A4458`).
* **Selected Icon & Text:** `?attr/colorOnSecondaryContainer` (Light: `#1D192B`, Dark: `#E8DEF8`).
* **Unselected Icon:** `24dp × 24dp`, tint `?attr/colorOnSurfaceVariant`.
* **Unselected Text:** `label_large` (14sp, Medium), color `?attr/colorOnSurfaceVariant`.

---

## 5. MaterialSwitch

### M3 Token Roles
* **Selected (Checked):**
  * **Track Fill:** `?attr/colorPrimary` (Light: `#6750A4`, Dark: `#D0BCFF`).
  * **Thumb (Handle) Fill:** `?attr/colorOnPrimary` (Light: `#FFFFFF`, Dark: `#381E72`).
  * **Thumb Size:** `24dp × 24dp` (with icon or expanded) / `16dp × 16dp` (resting).
* **Unselected (Unchecked):**
  * **Track Fill:** `?attr/colorSurfaceContainerHighest` (Light: `#E6E0E9`, Dark: `#36343B`).
  * **Track Outline:** `?attr/colorOutline` (`1dp` stroke).
  * **Thumb (Handle) Fill:** `?attr/colorOutline` (Light: `#79747E`, Dark: `#938F99`).
* **Disabled:**
  * **Track:** `?attr/colorOnSurface` at `12%` opacity.
  * **Thumb:** `?attr/colorOnSurface` at `38%` opacity.

---

## 6. Floating Action Button (FAB)

### Layout & Sizing
* **Standard FAB:** `56dp × 56dp`.
* **Corner Radius:** `16dp` (`shape.large` squircle / rounded rectangle).
* **Elevation:** `6dp` (`level_3`) resting, `8dp` (`level_4`) pressed.
* **Background Fill:** `?attr/colorPrimaryContainer` (Light: `#EADDFF`, Dark: `#4F378B`) or `?attr/colorPrimary`.
* **Icon Size:** `24dp × 24dp`, tint `?attr/colorOnPrimaryContainer` or `?attr/colorOnPrimary`.
* **Ripple:** `?attr/colorOnPrimaryContainer` or `?attr/colorOnPrimary` with state list alpha.
* **Accessibility:** Must implement `role="button"` or use `com.google.android.material.floatingactionbutton.FloatingActionButton`.

---

## 7. Cards & Settings Containers

### Outlined Card (M3 Standard)
* **Corner Radius:** `16dp` (`shape.large`).
* **Background Fill:** `?attr/colorSurfaceContainerLowest` or `?attr/colorSurface`.
* **Border:** `1dp` stroke, color `?attr/colorOutlineVariant` (Light: `#CAC4D0`, Dark: `#49454F`).
* **Elevation:** `0dp` (elevation is conveyed by outline and container contrast).
* **Margins:** `16dp` horizontal, `8dp` vertical spacing between sections.
