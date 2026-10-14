# QUIK: Comprehensive Handover, Engineering Specifications & Roadmap

> **Repository:** [https://github.com/limit-breaker-999/quik](https://github.com/limit-breaker-999/quik)  
> **Visibility:** Public  
> **Active Development Branch:** `feature/material3-redesign-v2`  
> **Target Release:** v4.3.8 (Build 2240)  

---

## 1. Project Mission & Core Objectives

**QUIK** is an open-source Android SMS & MMS client (originally based on QKSMS) focused on privacy, user sovereignty, modern Google Pixel / Google Messages design aesthetics, and advanced communication automation.

### What This Project Solves & Aims to Fix:
1. **Modern Material 3 Design & Google Messages Parity**:
   - The legacy app relied on obsolete Material 2 (2014) palettes, hardcoded Slate color hexes, and bloated multi-row toolbars.
   - QUIK transitions fully to Google's official **Material 3 (M3)** design system with **Android 12+ Monet Dynamic Wallpaper Theming**, full AMOLED Black support, and Google Messages interaction patterns.
2. **End-to-End SMS/MMS Automation Engine**:
   - Native on-device automation: keyword triggers, auto-replies, forwarding, regex matching, scheduled transmissions, and rate-limited execution via Android `WorkManager`.
3. **Rock-Solid Stability & Zero-Glitch Performance**:
   - Eradicating RecyclerView recycling glitches, typeface leakages, fragment/controller lifecycle crashes, and broken constraint chaining.
4. **Toolchain Modernization**:
   - Upgrading the build pipeline to modern Gradle 9 / AGP standards and Kotlin 1.9+/2.0.

---

## 2. Repository Layout & Key Reference Assets

```
quik/
├── material3_design_kit/         # Official Google Material 3 Design Database
│   ├── specs/                    # Component guidelines, color roles, elevation, typography
│   │   ├── component_guidelines.md
│   │   ├── color_roles.json
│   │   ├── elevation_and_shape.json
│   │   └── typography.json
│   ├── tokens/                   # System and component SCSS/JSON tokens
│   │   ├── system/               # Reference palettes, shapes, typescales, motion
│   │   └── components/           # Top app bar, search bar, list items, switches, FAB, etc.
│   └── android_m3_reference/     # Extracted M3 ColorStateList XMLs and attributes (v1.12.0)
├── presentation/                 # UI, Activities, Controllers, ViewModels, XML Layouts
├── domain/                       # Use Cases, Preferences, Interactors, Models
├── data/                         # Repositories, Realm DB, Telephony Providers
├── common/                       # Base classes, Navigator, Logging, Utilities
├── android-smsmms/               # Low-level MMS/PDU parsing and APN transport library
├── error.md                      # Comprehensive historical audit (85 categorized bugs)
├── error-fixes.md                # Verification log of resolved issues
└── INSTRUCTIONS.md               # This handover document
```

---

## 3. High-Priority Bug Investigation: Settings Drawer Crash

### The Issue:
When clicking the top-left hamburger menu icon on the main screen to open the navigation drawer and tapping **"Settings"**, the application crashes or fails to render.

### Architectural Trace & Root Cause Candidates:
1. **Navigation Entry Point**:
   - In [`MainActivity.kt`](presentation/src/main/java/com/moez/QKSMS/feature/main/MainActivity.kt), tapping the drawer item emits `NavItem.SETTINGS` via `navigationIntent`.
   - In [`MainViewModel.kt`](presentation/src/main/java/com/moez/QKSMS/feature/main/MainViewModel.kt), `NavItem.SETTINGS` invokes `navigator.showSettings()`.
   - In [`Navigator.kt`](presentation/src/main/java/com/moez/QKSMS/common/Navigator.kt), `showSettings()` launches `SettingsActivity::class.java`.
2. **Container & Controller Initialization**:
   - [`SettingsActivity.kt`](presentation/src/main/java/com/moez/QKSMS/feature/settings/SettingsActivity.kt) inflates `ContainerActivityBinding` (`container_activity.xml`) and attaches a Conductor `Router`, setting `SettingsController()` as the root.
3. **Key Crash Vectors to Inspect in `SettingsController.kt`**:
   - **Vector A — Unsafe `activity!!` in Lazy Initializers**:
     In `SettingsController.kt`:
     ```kotlin
     private val signatureDialog: TextInputDialog by lazy {
         TextInputDialog(activity!!, context.getString(R.string.settings_signature_title), signatureSubject::onNext)
     }
     ```
     If accessed before or during controller attachment before `activity` is bound, `activity!!` throws a `NullPointerException`.
   - **Vector B — Conductor Lifecycle vs `scope()` in `init {}`**:
     In `SettingsController.kt`:
     ```kotlin
     init {
         appComponent.inject(this)
         retainViewMode = RetainViewMode.RETAIN_DETACH
         colors.themeObservable()
                 .autoDisposable(scope()) // <-- LifecycleOwner scope called in constructor
                 .subscribe { activity?.recreate() }
     }
     ```
     Calling `scope()` in `init` before Conductor attaches the controller to a parent router / lifecycle host causes an `IllegalStateException` or `LifecycleNotYetInitializedException`. **Fix:** Move subscription to `onAttach(view: View)`.
   - **Vector C — Layout Inflation & Attribute Resolution in `settings_controller.xml`**:
     `settings_controller.xml` contains custom `dev.octoshrimpy.quik.common.widget.PreferenceView` and `MaterialCardView` elements. If any attribute (such as `app:strokeColor="?attr/cardBorder"` or `app:cardBackgroundColor="?attr/cardBackground"`) resolves to a color state list that fails to resolve on the activity's theme overlay, inflation crashes with `InflateException`.

---

## 4. Gradle 9 Modernization Roadmap

The project currently uses **Gradle 8.2.2** with **AGP 8.2.2** and **Kotlin 1.7.21**. Gradle logs warn that deprecated configuration features will fail under **Gradle 9.0**.

### Step-by-Step Modernization Instructions:
1. **Upgrade Java & JVM Compatibility**:
   - Modern AGP (8.5+) and Gradle 9 require Java 17+.
   - Update `compileOptions` and `kotlinOptions` across all subprojects:
     ```groovy
     compileOptions {
         sourceCompatibility JavaVersion.VERSION_17
         targetCompatibility JavaVersion.VERSION_17
     }
     kotlinOptions {
         jvmTarget = "17"
     }
     ```
2. **Upgrade Kotlin & Dependencies**:
   - In root `build.gradle`:
     - Upgrade Kotlin: `ext.kotlin_version = '1.9.24'` (or `2.0.20`).
     - Upgrade AGP: `com.android.tools.build:gradle:8.5.2` (or `8.7.0`).
     - Upgrade Coroutines: `1.8.1`.
     - Upgrade KAPT / Dagger compiler.
3. **Clean Up Gradle 9 Incompatibilities**:
   - Remove deprecated `setProperty("archivesBaseName", ...)`; replace with `android.defaultConfig.archivesBaseName`.
   - Replace legacy `testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"` configurations.
   - Replace deprecated `allprojects { repositories { ... } }` in root `build.gradle` with modern `settings.gradle` `dependencyResolutionManagement`:
     ```groovy
     dependencyResolutionManagement {
         repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
         repositories {
             google()
             mavenCentral()
             maven { url "https://jitpack.io" }
         }
     }
     ```
4. **Upgrade Gradle Wrapper**:
   - Run:
     ```bash
     ./gradlew wrapper --gradle-version 8.10.2 --distribution-type all
     ```
   - Verify with `./gradlew compileReleaseKotlin` and fix any compiler warning flags.

---

## 5. UI/UX, Interaction & Visual Guidelines (Material 3 & Google Messages)

All styling must adhere to the tokens defined in `material3_design_kit/`:

### 5.1 Main Screen & Navigation
- **Unified M3 Search Bar Pill**:
  - Height: `56dp`, corner radius: `28dp` (full round), margins: `16dp` horizontal, `8dp` top.
  - Background: `?attr/colorSurfaceContainerHigh` (`#ECE6F0` light / `#2B2930` dark).
  - Contains navigation hamburger icon on the left, search hint in the center, and options menu on the right.
  - When in contextual selection mode (CAB), morphs or overlays with `?attr/colorSurfaceContainer` displaying item count and batch action icons (Archive, Delete, Pin, Read).
- **Floating Action Button (FAB)**:
  - Must use `com.google.android.material.floatingactionbutton.FloatingActionButton` (`@style/Widget.Material3.FloatingActionButton.Primary`).
  - Shape: `16dp` squircle (`shape.large`).
  - Background: `?attr/colorPrimaryContainer`, icon tint: `?attr/colorOnPrimaryContainer`.
  - Dynamic state elevation: `6dp` resting, `8dp` pressed with M3 state layer ripple.
- **Modal Navigation Drawer**:
  - Sheet width: `320dp`, trailing top & bottom corners: `28dp` radius (`drawer_sheet_background.xml`).
  - Container background: `?attr/colorSurfaceContainerLow` (`#F7F2FA` light / `#1D1B20` dark).
  - Destination items: `56dp` height, `12dp` horizontal margins.
  - Active item: Full pill indicator background (`28dp` radius) tinted with `?attr/colorSecondaryContainer`. Text and icon turn `?attr/colorOnSecondaryContainer`.
  - Inactive item: Transparent background, `?attr/colorOnSurfaceVariant` text and icon.

### 5.2 Conversation List Item
- **Sizing**: `72dp` minimum height for single-line snippets, `88dp` for two-line snippets.
- **Avatar Circle**:
  - Size: `52dp × 52dp`, full circular shape (`shape.full`).
  - Background: Harmonized pastel container color derived from recipient hash.
  - Monogram Initials: `title_medium` (16sp bold), centered.
  - Contact silhouette fallback: `ic_person_black_24dp`, tinted `?attr/colorOnSurfaceVariant`.
- **Horizontal Barrier Constraint**:
  - All status icons (`pinned`, `scheduled`, `unread`) must be grouped using `androidx.constraintlayout.widget.Barrier` (`barrierDirection="start"`).
  - Snippet text must constrain its end to the barrier (`app:layout_constraintEnd_toStartOf="@id/statusBarrier"`). This prevents text overlapping or ragged right margins regardless of which icons are visible.
- **Unread vs. Read State Distinction**:
  - **Unread**: Bold title (`fontFamily="sans-serif-medium"`, `colorOnSurface`), bold snippet (`colorOnSurface`), bold date tinted with `?attr/colorPrimary`, solid `10dp` dot badge tinted `?attr/colorPrimary`.
  - **Read**: Regular title (`colorOnSurface`), regular snippet (`colorOnSurfaceVariant`), regular date (`colorOnSurfaceVariant`), dot badge `GONE`.
  - **Recycling Safety**: All typefaces, colors, and visibilities must be rebound explicitly in `onBindViewHolder` to prevent style leakage on recycled views.

### 5.3 Conversation Thread & Message Bubbles
- **Bubble Inversion (Modern Google Messages Standard)**:
  - **Outbound (Sent) Messages**: Tinted with `?attr/colorPrimary` or `?attr/colorPrimaryContainer` with `?attr/colorOnPrimary` / `?attr/colorOnPrimaryContainer` text.
  - **Inbound (Received) Messages**: Neutral container `?attr/colorSurfaceContainerHigh` with `?attr/colorOnSurface` text.
- **Grouped Bubble Geometry**:
  - Corner radius is `18dp` standard.
  - For sequential messages from the same sender within 10 minutes: adjacent inner corners shrink to `4dp`, forming a cohesive visual bubble stack.
  - Emoji-only messages must reset `backgroundTintList = null` to ensure transparent rendering without inheriting recycled solid tints.
- **Floating Pill Composer Bar**:
  - Full rounded pill container (`24dp` radius) with `?attr/colorSurfaceContainerHigh` background.
  - Embedded '+' attachment button, auto-expanding text input (min `44dp`, max `140dp`), SIM indicator badge, and character counter.
  - Dynamic right action button: Shows microphone icon when text field is empty; smoothly morphs into circular Send button (`?attr/colorPrimary` fill, `?attr/colorOnPrimary` icon) when text is entered.
- **Attachment Bottom Sheet**:
  - Replaces floating speed-dial with an M3 Modal Bottom Sheet with `28dp` top corners, housing 6 grid actions: Gallery, Camera, Audio Note, Files, Contact VCard, Scheduled Message.

---

## 6. Color Theming & Monet Architecture

### 6.1 Theme Schemes
1. **Light Scheme (`AppBaseTheme` in `values/themes.xml`)**:
   - `colorPrimary`: `#6750A4`, `colorOnPrimary`: `#FFFFFF`
   - `colorPrimaryContainer`: `#EADDFF`, `colorOnPrimaryContainer`: `#21005D`
   - `colorSurface`: `#FEF7FF`, `colorOnSurface`: `#1D1B20`
   - `colorSurfaceContainerLow`: `#F7F2FA`, `colorSurfaceContainerHigh`: `#ECE6F0`
   - `colorOutline`: `#79747E`, `colorOutlineVariant`: `#CAC4D0`
2. **Dark Scheme (`AppBaseTheme` in `values-night/themes.xml`)**:
   - `colorPrimary`: `#D0BCFF`, `colorOnPrimary`: `#381E72`
   - `colorPrimaryContainer`: `#4F378B`, `colorOnPrimaryContainer`: `#EADDFF`
   - `colorSurface`: `#141218`, `colorOnSurface`: `#E6E0E9`
   - `colorSurfaceContainerLow`: `#1D1B20`, `colorSurfaceContainerHigh`: `#2B2930`
   - `colorOutline`: `#938F99`, `colorOutlineVariant`: `#49454F`
3. **AMOLED Black Scheme (`AppTheme.Black`)**:
   - Root Window & Surface: `#000000` (true black for OLED power savings).
   - Card/Drawer Container: `#121214`.
   - Search Pill / Bubble: `#1C1B1F`.
   - Outlines: `1dp` stroke of `#2B2930` for crisp boundary contrast without glow.

### 6.2 Monet Wallpaper Theming Integration
- Initialized in [`QKApplication.kt`](presentation/src/main/java/com/moez/QKSMS/common/QKApplication.kt) via:
  ```kotlin
  if (DynamicColors.isDynamicColorAvailable()) {
      DynamicColors.applyToActivitiesIfAvailable(
          this,
          DynamicColorsOptions.Builder()
              .setPrecondition { _, _ -> prefs.dynamicColor.get() && !prefs.black.get() }
              .build()
      )
  }
  ```
- Guarded by `!prefs.black.get()` to prevent wallpaper tints from overriding AMOLED pure black mode.
- Monitored in [`QkThemedActivity.kt`](presentation/src/main/java/com/moez/QKSMS/common/base/QkThemedActivity.kt) so toggling dynamic color immediately recreates activities.

---

## 7. Verification & Build Commands

Before submitting any code changes, always run the full validation pipeline:

```bash
# 1. Compile release Kotlin bytecode across all 5 modules
./gradlew compileReleaseKotlin

# 2. Run release unit test suites
./gradlew testReleaseUnitTest

# 3. Assemble signed release APK
./gradlew assembleRelease

# 4. Check git status to ensure no orphaned files or untracked changes
git status
```

Generated signed release APK is located at:
`presentation/build/outputs/apk/release/QUIK-v4.3.8-release.apk`
