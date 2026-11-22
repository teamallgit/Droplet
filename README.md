<p align="center">
  <img src="assets/oneliquid_banner.png" width="760" alt="OneLiquid Banner" />
</p>

<h1 align="center">OneLiquid</h1>

<p align="center">
  <strong>iOS 26 Clear Liquid Glass Icon Pack &amp; LSPosed Module for Samsung One UI (8 / 8.5 / 9+)</strong><br/>
  1,020+ solid-filled translucent frosted-glass icons with Apple continuous-squircle geometry, Fresnel specular rims, plus an optional built-in LSPosed module for live backdrop blur, AGSL refraction, floating Glass Dock &amp; Glass Folders — all in one APK.
</p>

---

## Features

- **1:1 iOS 26 Clear Liquid Glass Aesthetic** — Calibrated against Apple's official iOS 26 Clear Home Screen: pure-white top-to-bottom translucent frosted glyphs, crystal-clear glass body so your wallpaper shines through, and razor-crisp anisotropic Fresnel specular rim catchlights (top-left `-135°` & bottom-right `+45°`).
- **1,020+ Solid-Filled Liquid Glass Icons** — Full coverage of mainstream & niche Android apps (mapped across 2,700+ `ComponentInfo` activities in `appfilter.xml`), combining bespoke iOS 26 Clear system icons, optically normalized stock glyphs, and 900+ **solid-filled** official brand vectors from [Simple Icons](https://simpleicons.org/).
- **Continuous Squircle Geometry** — Supersampled (`512×512` → `192×192`) smooth continuous squircle (`n = 4.50`) with exact Euclidean SDF normals and unified optical visual-weight scaling.
- **Built-In LSPosed Module (One UI 8 / 8.5 / 9+)** — Distributed inside the exact same `OneLiquid.apk` with an in-app **ON / OFF master toggle**, individual feature switches, **live 6-point status checklist** (with real IPC scope pings), and **Restart SystemUI / Restart One UI Home** controls:
  - **1:1 Live Clear Icons**: Applies all 1,020 OneLiquid icons + auto-converts any unthemed app on the fly in HoneySpace (`com.sec.android.app.launcher`).
  - **Live Backdrop Blur & AGSL Refraction**: Uses Samsung's native `SemBlurInfo` hardware backdrop blur + live GPU AGSL `RuntimeShader` scroll-reactive Fresnel caustics.
  - **iOS 26 Liquid Glass Dock & Folders**: Floating clear glass Hotseat pill and translucent squircle folders with live backdrop blur.
  - **SystemUI Glass Polish**: Subtle translucent Liquid Glass specular rims in `com.android.systemui`.

## Icon Preview (288 of 1,020+ Icons)

| Part 1 | Part 2 |
| :---: | :---: |
| ![OneLiquid Preview Part 1](assets/preview_part1.png) | ![OneLiquid Preview Part 2](assets/preview_part2.png) |

| Part 3 | Part 4 |
| :---: | :---: |
| ![OneLiquid Preview Part 3](assets/preview_part3.png) | ![OneLiquid Preview Part 4](assets/preview_part4.png) |

## Installation

### Option A — No Root (Samsung Good Lock / Theme Park)
1. Download **`OneLiquid.apk`** from the [Releases](../../releases/latest) page and install it.
2. Open **Good Lock → Theme Park → Icon** (or open **OneLiquid → Icons tab → Apply via Theme Park**).
3. Tap **Create New → Iconpack → Third Party → OneLiquid**.
4. Keep **Shape** unmodified (original) and **Effects** set to **None** so One UI preserves the exact squircle geometry and specular glass rim.
5. Tap **Save** (top-right), name your pack, and tap **Apply**.

### Option B — LSPosed Module (One UI 8 / 8.5 / 9+ with Root)
1. Install **`OneLiquid.apk`** from [Releases](../../releases/latest).
2. Open **LSPosed Manager → Modules → OneLiquid** and enable it.
3. Tick the recommended scope:
   - **One UI Home** (`com.sec.android.app.launcher`)
   - **SystemUI** (`com.android.systemui`)
4. Open the **OneLiquid** app → **LSPosed Module** tab:
   - Toggle **Enable OneLiquid LSPosed Module** ON and choose your effects.
   - Check the **LSPosed Checklist & Live Status** panel.
   - Tap **Restart One UI Home** and **Restart SystemUI** to apply immediately.

## Building from Source

Requires Python 3.10+, JDK 17+, and Android SDK Build-Tools (`aapt2`, `d8`, `zipalign`, `apksigner`).

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python build_all_icons.py
.venv/bin/python expand_1000_icons.py
.venv/bin/python build_apk.py
```

## Notes

- Brand logos are sourced from [Simple Icons](https://simpleicons.org/) and belong to their respective trademark holders.
