# Google Material 3 Design Kit & Specifications

This directory contains the official Google Material 3 design tokens, component specifications, and Android resource definitions extracted directly from Google's official design system packages:
* **Token Source:** Google Material 3 Web Tokens (`@material/web` v2.5.0)
* **Android Source:** Google Material Components for Android (`com.google.android.material:material:1.12.0`)

---

## Directory Structure

```
material3_design_kit/
├── README.md                           # This guide
├── specs/                              # Structured, developer-ready specifications
│   ├── color_roles.json                # Light & Dark M3 color roles with hex & Android attrs
│   ├── typography.json                 # M3 Typescales (size, line-height, letter-spacing, weights)
│   ├── elevation_and_shape.json        # Elevation levels 0-5 and corner radius tokens
│   └── component_guidelines.md         # Component specs for QUIK (TopAppBar, Search, List, Switch, FAB)
├── tokens/                             # Raw Google Material 3 SCSS token source
│   ├── system/                         # 16 system token definitions (sys-color, sys-shape, etc.)
│   └── components/                     # 168 component token definitions (switch, top-app-bar, fab, etc.)
└── android_m3_reference/               # Official Android M3 XML resource files from material:1.12.0
    ├── values/
    │   └── values.xml                  # Full Android M3 themes, styles, attrs, dimensions
    └── color/                          # 120 M3 ColorStateList XML definitions
```

---

## Key Design Token Mappings for QUIK

### 1. Colors (Light vs. Dark)

| Role | Light Hex | Dark Hex | Android Theme Attr | Primary Usage |
| :--- | :--- | :--- | :--- | :--- |
| **Primary** | `#6750A4` | `#D0BCFF` | `?attr/colorPrimary` | Key buttons, active toggles, FAB |
| **On Primary** | `#FFFFFF` | `#381E72` | `?attr/colorOnPrimary` | Text/icons on primary fill |
| **Primary Container** | `#EADDFF` | `#4F378B` | `?attr/colorPrimaryContainer` | FAB container, pill accents |
| **On Primary Container**| `#21005D` | `#EADDFF` | `?attr/colorOnPrimaryContainer` | Text/icons on primary container |
| **Surface** | `#FEF7FF` | `#141218` | `?attr/colorSurface` | Default background |
| **On Surface** | `#1D1B20` | `#E6E0E9` | `?attr/colorOnSurface` | Primary body text |
| **Surface Variant** | `#E7E0EC` | `#49454F` | `?attr/colorSurfaceVariant` | Card strokes, subtle backgrounds |
| **On Surface Variant** | `#49454F` | `#CAC4D0` | `?attr/colorOnSurfaceVariant` | Secondary text, hints, unselected icons |
| **Surface Container Highest** | `#E6E0E9` | `#36343B` | `?attr/colorSurfaceContainerHighest` | Pill search bar fill, switch track |
| **Outline** | `#79747E` | `#938F99` | `?attr/colorOutline` | Unselected switch thumb, card border |
| **Outline Variant** | `#CAC4D0` | `#49454F` | `?attr/colorOutlineVariant` | Subtle separators, list dividers |

---

## 2. Corner Radii (Shapes)

* **Full / Pill (`shape.full`):** Search bar pill, Avatar circles, switch track/thumb.
* **Large (`shape.large` - `16dp`):** Compose FAB, settings cards (`MaterialCardView`), dialogs.
* **Medium (`shape.medium` - `12dp`):** Small cards, menus.
* **Small (`shape.small` - `8dp`):** Chips, snackbars.

---

## 3. Elevation Levels

* **Level 0 (`0dp`):** Default list item background, flat cards.
* **Level 1 (`1dp`):** Outlined settings cards, top bar on scroll.
* **Level 2 (`3dp`):** Pill search bar, modal navigation drawer.
* **Level 3 (`6dp`):** Compose FAB (resting).
* **Level 4 (`8dp`):** Compose FAB (pressed).

---

## Quick Reference Links

* [Color Roles JSON](file:///home/ubuntu/quik/material3_design_kit/specs/color_roles.json)
* [Typography Tokens JSON](file:///home/ubuntu/quik/material3_design_kit/specs/typography.json)
* [Elevation & Shape JSON](file:///home/ubuntu/quik/material3_design_kit/specs/elevation_and_shape.json)
* [Component Guidelines Markdown](file:///home/ubuntu/quik/material3_design_kit/specs/component_guidelines.md)
* [Android M3 Values Reference](file:///home/ubuntu/quik/material3_design_kit/android_m3_reference/values/values.xml)
