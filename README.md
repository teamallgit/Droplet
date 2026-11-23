<p align="center">
  <img src="assets/droplet_banner.png" width="800" alt="Droplet — iOS 26 Liquid Glass for Samsung One UI" />
</p>

<h1 align="center">Droplet</h1>

<p align="center">
  <strong>iOS 26 Clear Liquid Glass Icon Pack &amp; Real-Time LSPosed Module for Samsung One UI</strong>
</p>

<p align="center">
  <a href="../../releases/latest"><img src="https://img.shields.io/github/v/release/teamallgit/Droplet?style=flat-square&color=3E8BFF&label=Download" alt="Latest Release" /></a>
  <img src="https://img.shields.io/badge/One%20UI-8%20%7C%208.5%20%7C%209%2B-1C1C1E?style=flat-square" alt="One UI 8 / 8.5 / 9+" />
  <img src="https://img.shields.io/badge/Icons-1%2C600%2B-1C1C1E?style=flat-square" alt="1,600+ Icons" />
  <img src="https://img.shields.io/badge/Root-Optional-34D399?style=flat-square" alt="Root Optional" />
</p>

---

## Live Liquid Glass in Action

<p align="center">
  <img src="assets/demo_refraction.gif" width="720" alt="Droplet Live Liquid Glass Refraction & Corner Glow" /><br/>
  <sub><em>Real-time convex lens magnification, curved bevel RGB dispersion, and tilt-reactive corner specular glow over live &amp; static wallpapers.</em></sub>
</p>

<p align="center">
  <img src="assets/demo_customization.gif" width="720" alt="Droplet Live Clarity to Frost and Optical Glass Tint" /><br/>
  <sub><em>Live <strong>Clarity &amp; frost</strong> (0% pure crystal glass &harr; 100% heavy frost) and <strong>Optical Glass Tint</strong> (9 presets + Custom RGB picker) with instant 0ms updates.</em></sub>
</p>

---

## Two Ways to Use Droplet

Everything ships inside a single **`Droplet.apk`**. No-root users get the full 1,600+ icon pack immediately; rooted users with LSPosed unlock live GPU optical glass effects and real-time sliders.

| Capability | No Root (Theme Park) | Root + LSPosed Module |
| :--- | :---: | :---: |
| **1,600+ solid-filled iOS 26 Clear Liquid Glass icons** (`3,500+` app mappings) | &#x2705; | &#x2705; |
| **Apple continuous-squircle geometry** (`n = 4.50`) &amp; translucent frosted glyphs | &#x2705; | &#x2705; |
| **Live 120fps GPU wallpaper magnification &amp; curved bevel refraction** | &mdash; | &#x2705; |
| **Gyroscope tilt-reactive corner specular glow** | &mdash; | &#x2705; |
| **Live Clarity &harr; Frost, Magnification, Glow &amp; Optical Glass Tint sliders** | &mdash; | &#x2705; |
| **Automatic App Drawer extra-blur matching** | &mdash; | &#x2705; |
| **Glass Folders &amp; SystemUI specular rims** | &mdash; | &#x2705; |

---

## Capabilities

### Optical Liquid Glass Engine (Optimized for 120fps)
- **Live Convex Magnification &amp; Bevel Refraction**: Samples your live or static wallpaper in real time with a 1-tap center / 3-tap outer-bevel AGSL fragment shader (`~18&times;` lighter than multi-tap shader blur) and hardware GPU bilinear filtering (`FILTER_MODE_LINEAR`) on `270&times;585` pre-blurred textures.
- **Zero-Stutter Scrolling &amp; Viewport Culling**: Pauses background wallpaper capture during active touch/scroll so Home Screen and App Drawer scrolling stays locked at 120fps, and skips off-screen icons during redraws and tilt updates.
- **Corner-Originating Specular Glow**: Quiet optical hairline along straight edges with bright liquid-glass specular arcs originating strictly from the 45&deg; curved corners (`-135&deg;` top-left &amp; `+45&deg;` bottom-right) that shift naturally as you tilt your phone.
- **App Drawer Extra-Blur Matching**: Automatically detects One UI's blurred containers (App Drawer, Open Folders, Overlay Apps, Suggested Apps) and switches those icons to a dedicated silky-blurred backdrop texture so they blend seamlessly instead of looking sharper than the blurred drawer behind them.
- **Icons-Only &amp; Clean Stock Fallback**: Carefully leaves Home Screen widgets untouched so your other One UI &amp; LSPosed modules keep working cleanly, and preserves the regular stock app icon intact for any unrecognised app not yet covered by Droplet.

### Native Samsung One UI Companion App
- **Automatic LSPosed Detection**: Automatically detects whether the LSPosed module is active — hides the setup guide when active, and grays out root-only sliders/toggles with a clear 3-step setup guide when inactive.
- **Master Switch &amp; Live Sliders**:
  - **Clarity &amp; frost**: `0%` pure crystal-clear glass (zero white wash) &harr; `100%` heavy frosted glass.
  - **Magnification &amp; refraction**: `Subtle` &harr; `Strong` convex lens &amp; edge bend.
  - **Corner light glow**: `Off` &harr; `Bright` specular corner highlights.
  - **Glass tint &amp; Tint strength**: `None`, **`Auto` (wallpaper-adaptive dynamic tint)**, 9 curated glass presets (`Blue`, `Cyan`, `Indigo`, `Purple`, `Pink`, `Red`, `Amber`, `Mint`, `Dark`), plus a **Custom RGB color picker** with live hex preview.
- **Live Status &amp; One-Tap Restart**: Live connection status for LSPosed, One UI Home, SystemUI, and Root, plus one-tap **Restart Home** and **Restart SystemUI** buttons.

---

## Icon Pack Preview

*120 of 1,600+ solid-filled iOS 26 Clear Liquid Glass icons:*

| Core &amp; Popular | Apps &amp; Services |
| :---: | :---: |
| ![Droplet Preview Part 1](assets/preview_part1.png) | ![Droplet Preview Part 2](assets/preview_part2.png) |

| More Mainstream | Niche &amp; Utilities |
| :---: | :---: |
| ![Droplet Preview Part 3](assets/preview_part3.png) | ![Droplet Preview Part 4](assets/preview_part4.png) |

---

## Installation

### Option A — No Root (Samsung Good Lock / Theme Park)
1. Download **`Droplet.apk`** from [Releases](../../releases/latest) and install it.
2. Open **Droplet &rarr; Icons tab &rarr; Apply** (or open **Good Lock &rarr; Theme Park &rarr; Icon**).
3. Tap **Create New &rarr; Iconpack &rarr; Third Party &rarr; Droplet**.
4. Leave **Shape** unmodified and **Effects** set to **None** so One UI preserves the exact squircle geometry and specular rim.
5. Tap **Save** and **Apply**.

### Option B — Root + LSPosed (One UI 8 / 8.5 / 9+)
1. Download and install **`Droplet.apk`** from [Releases](../../releases/latest).
2. Open **LSPosed Manager &rarr; Modules &rarr; Droplet** and enable it.
3. Select the recommended scope:
   - **One UI Home** (`com.sec.android.app.launcher`)
   - **SystemUI** (`com.android.systemui`)
4. Open **Droplet**, customize your glass sliders/tint, and tap **Restart Home** (and **Restart SystemUI** if using SystemUI rims).

---

## Building from Source

Requires Python 3.10+, JDK 17+, and Android SDK Build-Tools (`aapt2`, `d8`, `zipalign`, `apksigner`).

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python build_all_icons.py
.venv/bin/python expand_1000_icons.py
.venv/bin/python build_apk.py
```

---

## Credits

- Brand vectors are sourced from [Simple Icons](https://simpleicons.org/) and belong to their respective trademark holders.
