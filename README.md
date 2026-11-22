<p align="center">
  <img src="assets/oneliquid_logo.png" width="160" alt="OneLiquid Logo" />
</p>

<h1 align="center">OneLiquid</h1>

<p align="center">
  <strong>iOS 26 Clear Liquid Glass icon pack for Samsung One UI</strong><br/>
  Translucent frosted-glass icons with Apple continuous-squircle geometry, Fresnel specular rims, and consistent optical scaling.
</p>

---

## Features

- **1:1 iOS 26 Clear Liquid Glass Aesthetic** — Calibrated against Apple's official iOS 26 Clear Home Screen: pure-white top-to-bottom translucent frosted glyphs, crystal-clear glass body so your wallpaper shines through, and razor-crisp anisotropic Fresnel specular rim catchlights (top-left `-135°` & bottom-right `+45°`).
- **Continuous Squircle Geometry** — Supersampled (`512×512` → `256×256`) smooth continuous squircle (`n = 4.50`) with exact Euclidean SDF normals.
- **1,020+ Liquid Glass Icons** — Full coverage of mainstream & niche Android apps (mapped across thousands of `ComponentInfo` activities in `appfilter.xml`), combining exact official brand SVGs (Chrome, YouTube, Spotify, Steam, Google Play, Google Drive, App Store, Snapchat, Roblox, Bose, Sonos, eBay, McDonald's), bespoke iOS 26 Clear system icons, and 900+ mainstream & niche vectors sourced from [Lawnicons](https://github.com/LawnchairLauncher/lawnicons) + [Simple Icons](https://simpleicons.org/).
- **Universal Launcher Fallback** — Includes `iconback`, `iconmask`, and `iconupon` Liquid Glass framing so unthemed apps still blend in.

## Icon Preview

| Part 1 | Part 2 |
| :---: | :---: |
| ![OneLiquid Preview Part 1](assets/preview_part1.png) | ![OneLiquid Preview Part 2](assets/preview_part2.png) |

## Installation (Samsung One UI)

1. Download **`OneLiquid.apk`** from the [Releases](../../releases/latest) page and install it.
2. Open **Good Lock → Theme Park → Icon** (or open the **OneLiquid** app and tap **Open Theme Park**).
3. Tap **Create New → Iconpack → Third Party → OneLiquid**.
4. Keep **Shape** unmodified (original) and **Effects** set to **None** so One UI preserves the exact squircle geometry and specular glass rim.
5. Tap **Save** (top-right), name your pack, and tap **Apply**.

## Building from Source

Requires Python 3.10+, JDK 17+, and Android SDK Build-Tools (`aapt2`, `d8`, `zipalign`, `apksigner`).

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python build_all_icons.py
.venv/bin/python build_apk.py
```

## Notes

- Brand logos in `official_svgs/` are sourced from [Simple Icons](https://simpleicons.org/) and belong to their respective trademark holders.
