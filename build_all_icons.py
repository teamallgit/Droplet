import os
import re
from collections import deque
import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import gaussian_filter

import liquid_glass_engine as lge
import ios_svgs

BASE_DIR = "."
EXTRACTED_DIR = os.path.join(BASE_DIR, "extracted_icons")
OUT_ICONS_DIR = os.path.join(BASE_DIR, "icons")
USER_ONEUI_LOGO = os.path.join(BASE_DIR, "assets", "oneui_source.png")

TONAL_RELIEF_PACKAGES = {
    "com.epicgames.fortnite",
    "com.supercell.brawlstars",
    "com.robtopx.geometryjump",
    "com.paradyme.solarsmash",
    "com.miHoYo.Yuanshen",
    "cn.nanoturtle.rootmys9280",
    "au.com.opal.travel",
    "com.giggles.mb",
    "com.lsfg.android",
}

KNOCKOUT_BG_PACKAGES = {
    "com.alibaba.aliexpresshd",
    "com.blackshark.flutter_toolbox",
    "com.lsfg.android",
}


def sanitize_res_name(pkg, idx):
    s = re.sub(r"[^a-z0-9_]", "_", pkg.lower())
    s = re.sub(r"_+", "_", s).strip("_")
    if not s or not s[0].isalpha():
        s = f"app_{s}"
    return f"ic_{s}"


def center_and_scale_rgba(arr, explicit_target=None, canvas_size=lge.SIZE):
    """
    Tightened Unified Optical Visual-Weight Normalizer:
    Balances dense squares, circles, organic logos, sparse line-art, and wide wordmarks
    so every icon across the home screen & app drawer shares the same visual weight.
    """
    alpha = arr[..., 3]
    ys, xs = np.where(alpha > 0.04)
    if len(ys) == 0:
        return np.zeros((canvas_size, canvas_size, 4), dtype=np.float64)

    y0, y1 = int(ys.min()), int(ys.max()) + 1
    x0, x1 = int(xs.min()), int(xs.max()) + 1
    crop = arr[y0:y1, x0:x1]
    ch, cw = crop.shape[:2]

    crop_a = crop[..., 3]
    ink_fill = float((crop_a > 0.25).mean())
    aspect = max(cw, ch) / max(min(cw, ch), 1.0)
    is_wide = cw > ch * 1.45
    is_tall = ch > cw * 1.45

    if explicit_target is not None:
        effective_target = explicit_target
    elif is_wide:
        # Wide wordmarks & horizontal shapes (Wearable, Samsung Shop, Game Booster+, WiFiman, My Battery, Dynamic Island)
        effective_target = min(368, int(round(326 + 24 * min(aspect - 1.4, 2.0))))
    elif is_tall:
        # Tall narrow glyphs (Google One, DevCheck, Surfshark, LiqUI)
        effective_target = 308
    elif ink_fill >= 0.58 and aspect < 1.25:
        # Heavy dense blocks & solid squares (Bixby, Creative Studio, Secure Folder, KernelSU, Good Lock, OneOS, OneGlass, Geometry Dash, zFile)
        effective_target = 274
    elif ink_fill >= 0.40 and aspect < 1.35:
        # Medium-dense badges & circles (Instagram, Discord, Reddit, GitHub, WhatsApp, ChatGPT, Brawl Stars, Opal, UniFi, Meta Horizon, Fortnite)
        effective_target = 300
    elif ink_fill >= 0.24:
        # Medium organic symbols (Google, Gemini, Telegram, NAB, AliExpress, Shizuku, Canta, Theme Park, Apple Music, Google Photos)
        effective_target = 312
    else:
        # Sparse thin line-art (X, Termux, 3DMark, Geode, Authenticator, CheckFirm, App Manager, Duck Detector, LibrePods, DirtyFrag)
        effective_target = 324

    scale = effective_target / float(max(cw, ch))
    nw = max(1, int(round(cw * scale)))
    nh = max(1, int(round(ch * scale)))

    crop_u8 = np.clip(crop * 255.0 + 0.5, 0, 255).astype(np.uint8)
    im_resized = Image.fromarray(crop_u8, "RGBA").resize((nw, nh), Image.Resampling.LANCZOS)

    # Optical center: blend 70% geometric center + 30% alpha centroid (clamped to +/-10px)
    ra = np.asarray(im_resized, dtype=np.float64)[..., 3] / 255.0
    total_w = float(ra.sum())
    dx_opt, dy_opt = 0, 0
    if total_w > 1.0:
        yy, xx = np.mgrid[0:nh, 0:nw]
        cx_mass = float((xx * ra).sum() / total_w)
        cy_mass = float((yy * ra).sum() / total_w)
        dx_opt = int(round(np.clip((nw / 2.0 - cx_mass) * 0.28, -9.0, 9.0)))
        dy_opt = int(round(np.clip((nh / 2.0 - cy_mass) * 0.28, -9.0, 9.0)))

    out = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    ox = (canvas_size - nw) // 2 + dx_opt
    oy = (canvas_size - nh) // 2 + dy_opt
    out.paste(im_resized, (ox, oy))
    return np.asarray(out, dtype=np.float64) / 255.0


def border_floodfill_knockout(arr, tol=0.22):
    H, W, _ = arr.shape
    a = arr[..., 3]
    rgb = arr[..., :3]

    ys, xs = np.where(a > 0.2)
    if len(ys) == 0:
        return arr
    y0, y1 = int(ys.min()), int(ys.max())
    x0, x1 = int(xs.min()), int(xs.max())

    pts = [
        (y0 + 6, x0 + 6), (y0 + 6, x1 - 6),
        (y1 - 6, x0 + 6), (y1 - 6, x1 - 6),
        (y0 + 6, (x0 + x1) // 2), (y1 - 6, (x0 + x1) // 2),
        ((y0 + y1) // 2, x0 + 6), ((y0 + y1) // 2, x1 - 6),
    ]
    samples = []
    for py, px in pts:
        py = max(0, min(H - 1, py))
        px = max(0, min(W - 1, px))
        if a[py, px] > 0.5:
            samples.append(rgb[py, px])
    if len(samples) < 3:
        return arr

    bg_color = np.median(np.array(samples), axis=0)
    dist = np.sqrt(np.sum((rgb - bg_color[None, None, :]) ** 2, axis=-1))

    bg_mask = np.zeros((H, W), dtype=bool)
    visited = np.zeros((H, W), dtype=bool)
    q = deque()

    for x in range(W):
        for y in (0, H - 1, y0, y0 + 2, y1, y1 - 2):
            if 0 <= y < H and not visited[y, x]:
                if a[y, x] < 0.25 or dist[y, x] < tol:
                    visited[y, x] = True
                    q.append((y, x))
    for y in range(H):
        for x in (0, W - 1, x0, x0 + 2, x1, x1 - 2):
            if 0 <= x < W and not visited[y, x]:
                if a[y, x] < 0.25 or dist[y, x] < tol:
                    visited[y, x] = True
                    q.append((y, x))

    while q:
        cy, cx = q.popleft()
        bg_mask[cy, cx] = True
        for ny, nx in ((cy - 1, cx), (cy + 1, cx), (cy, cx - 1), (cy, cx + 1)):
            if 0 <= ny < H and 0 <= nx < W and not visited[ny, nx]:
                if a[ny, nx] < 0.25 or dist[ny, nx] < tol:
                    visited[ny, nx] = True
                    q.append((ny, nx))

    fg_keep = (~bg_mask).astype(np.float64)
    fg_soft = gaussian_filter(fg_keep, sigma=0.9)
    out = arr.copy()
    out[..., 3] = a * np.clip(fg_soft, 0.0, 1.0)
    return out


def prepare_mono_glyph(mono_path):
    im = Image.open(mono_path).convert("RGBA")
    arr = np.asarray(im, dtype=np.float64) / 255.0
    alpha = arr[..., 3]

    clean = np.zeros_like(arr)
    clean[..., :3] = 1.0
    clean[..., 3] = alpha

    mx = float(clean[..., 3].max())
    if 0.05 < mx < 0.90:
        clean[..., 3] = np.clip(clean[..., 3] / mx * 0.96, 0.0, 1.0)

    return center_and_scale_rgba(clean)


def prepare_fg_glyph(pkg, fg_path, full_path):
    src_path = fg_path if os.path.exists(fg_path) else full_path
    im = Image.open(src_path).convert("RGBA")
    arr = np.asarray(im, dtype=np.float64) / 255.0

    if pkg in KNOCKOUT_BG_PACKAGES:
        arr = border_floodfill_knockout(arr, tol=0.22)

    if pkg in TONAL_RELIEF_PACKAGES:
        # Keep square/dense game artwork neatly sized (Geometry Dash slightly tighter so it looks balanced)
        explicit = 276 if pkg == "com.robtopx.geometryjump" else (302 if pkg == "com.epicgames.fortnite" else None)
        arr = center_and_scale_rgba(arr, explicit_target=explicit)
        a = arr[..., 3]
        rgb = arr[..., :3]
        lum = 0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]
        nz = a > 0.15
        if np.any(nz):
            p5, p95 = np.percentile(lum[nz], [5, 95])
            if p95 - p5 > 0.05:
                lum_norm = np.clip((lum - p5) / (p95 - p5), 0.0, 1.0)
            else:
                lum_norm = np.ones_like(lum) * 0.92
        else:
            lum_norm = lum
        out = np.zeros_like(arr)
        out[..., :3] = 0.25 + 0.75 * (lum_norm[..., None] ** 0.78)
        out[..., 3] = a * (0.28 + 0.72 * (lum_norm ** 0.65))
        return out, True

    arr = center_and_scale_rgba(arr)
    a = arr[..., 3]
    rgb = arr[..., :3]
    lum = 0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]
    nz = a > 0.2
    spread = float(np.percentile(lum[nz], 92) - np.percentile(lum[nz], 8)) if np.any(nz) else 0.0

    out = np.zeros_like(arr)
    if spread > 0.28:
        p5, p95 = np.percentile(lum[nz], [5, 95])
        lum_n = np.clip((lum - p5) / max(p95 - p5, 0.05), 0.0, 1.0)
        out[..., :3] = 1.0
        out[..., 3] = a * (0.42 + 0.56 * (lum_n ** 0.75))
    else:
        out[..., :3] = 1.0
        out[..., 3] = a
    return out, False


def build_oneliquid_app_logo():
    """Extract the exact 'OneUI' typography from the user's logo and render in our Liquid Glass style."""
    im = Image.open(USER_ONEUI_LOGO).convert("RGBA")
    arr = np.asarray(im, dtype=np.float64) / 255.0
    lum = 0.299 * arr[:, :, 0] + 0.587 * arr[:, :, 1] + 0.114 * arr[:, :, 2]
    H, W = lum.shape
    roi = lum[int(H * 0.30):int(H * 0.70), int(W * 0.18):int(W * 0.82)]
    text_alpha = np.clip(1.0 - roi, 0.0, 1.0)
    text_alpha = np.where(text_alpha > 0.03, text_alpha, 0.0)
    ys, xs = np.where(text_alpha > 0.15)
    crop_a = text_alpha[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    ch, cw = crop_a.shape

    target_w = 358
    scale = target_w / float(cw)
    nw, nh = target_w, int(round(ch * scale))
    a_u8 = np.clip(crop_a * 255.0 + 0.5, 0, 255).astype(np.uint8)
    a_im = Image.fromarray(a_u8, "L").resize((nw, nh), Image.Resampling.LANCZOS)

    glyph = np.zeros((lge.SIZE, lge.SIZE, 4), dtype=np.float64)
    ox = (lge.SIZE - nw) // 2
    oy = (lge.SIZE - nh) // 2
    glyph[oy:oy + nh, ox:ox + nw, :3] = 1.0
    glyph[oy:oy + nh, ox:ox + nw, 3] = np.asarray(a_im, dtype=np.float64) / 255.0

    logo_512 = lge.compose_liquid_glass_icon(glyph, out_size=512, min_bottom_alpha=0.70, max_top_alpha=0.98)
    logo_256 = lge.compose_liquid_glass_icon(glyph, out_size=256, min_bottom_alpha=0.70, max_top_alpha=0.98)
    os.makedirs(os.path.join(BASE_DIR, "assets"), exist_ok=True)
    logo_512.save(os.path.join(BASE_DIR, "assets", "oneliquid_logo.png"), optimize=True)
    logo_256.save(os.path.join(OUT_ICONS_DIR, "ic_oneliquid_launcher.png"), optimize=True)
    return logo_512


def main():
    os.makedirs(OUT_ICONS_DIR, exist_ok=True)
    manifest_path = os.path.join(EXTRACTED_DIR, "manifest.tsv")

    entries = []
    with open(manifest_path, "r", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 6:
                entries.append(parts)

    generated = []
    used_res_names = set()

    for idx, (safe, pkg, cls, label, is_adapt, has_mono) in enumerate(entries):
        if pkg in ios_svgs.OFFICIAL_BRAND_SVGS:
            res_name, si_name, target_px = ios_svgs.OFFICIAL_BRAND_SVGS[pkg]
            if res_name in used_res_names:
                res_name = sanitize_res_name(pkg, idx)
            used_res_names.add(res_name)

            raw = lge.render_svg_to_rgba(ios_svgs._official_simple_icon(si_name), size=lge.SIZE)
            glyph = center_and_scale_rgba(raw, explicit_target=target_px)
            icon = lge.compose_liquid_glass_icon(glyph)
            tier = "official_brand"
        elif pkg in ios_svgs.BESPOKE_PACKAGE_SVGS:
            res_name, svg_fn = ios_svgs.BESPOKE_PACKAGE_SVGS[pkg]
            if res_name in used_res_names:
                res_name = sanitize_res_name(pkg, idx)
            used_res_names.add(res_name)

            glyph = lge.render_svg_to_rgba(svg_fn())
            if pkg in ("com.samsung.android.calendar", "com.sec.android.app.clockpackage"):
                icon = lge.compose_liquid_glass_icon(
                    glyph, bevel_strength=0.35, shadow_strength=0.30,
                    min_bottom_alpha=0.76, max_top_alpha=0.98,
                )
            elif pkg in ("com.samsung.android.app.notes", "com.google.android.apps.maps"):
                icon = lge.compose_liquid_glass_icon(
                    glyph, bevel_strength=0.50, shadow_strength=0.25,
                    min_bottom_alpha=0.68, max_top_alpha=0.97,
                )
            else:
                icon = lge.compose_liquid_glass_icon(glyph)
            tier = "bespoke_ios"
        elif has_mono == "true" and os.path.exists(os.path.join(EXTRACTED_DIR, f"{safe}_mono.png")):
            res_name = sanitize_res_name(pkg, idx)
            while res_name in used_res_names:
                res_name += "_2"
            used_res_names.add(res_name)

            glyph = prepare_mono_glyph(os.path.join(EXTRACTED_DIR, f"{safe}_mono.png"))
            icon = lge.compose_liquid_glass_icon(glyph)
            tier = "mono_glass"
        else:
            res_name = sanitize_res_name(pkg, idx)
            while res_name in used_res_names:
                res_name += "_2"
            used_res_names.add(res_name)

            glyph, is_tonal = prepare_fg_glyph(
                pkg,
                os.path.join(EXTRACTED_DIR, f"{safe}_fg.png"),
                os.path.join(EXTRACTED_DIR, f"{safe}_full.png"),
            )
            icon = lge.compose_liquid_glass_icon(
                glyph,
                preserve_tonal=is_tonal,
                bevel_strength=0.55 if is_tonal else 0.65,
                min_bottom_alpha=0.66 if is_tonal else 0.58,
            )
            tier = "fg_tonal" if is_tonal else "fg_glass"

        icon.save(os.path.join(OUT_ICONS_DIR, f"{res_name}.png"), optimize=True)
        generated.append((res_name, pkg, cls, label, tier))

    # Bonus official brand icons (Spotify, App Store)
    for bonus_name, (si_name, target_px) in ios_svgs.OFFICIAL_BONUS_SVGS.items():
        raw = lge.render_svg_to_rgba(ios_svgs._official_simple_icon(si_name), size=lge.SIZE)
        glyph = center_and_scale_rgba(raw, explicit_target=target_px)
        icon = lge.compose_liquid_glass_icon(glyph)
        icon.save(os.path.join(OUT_ICONS_DIR, f"{bonus_name}.png"), optimize=True)

    # Bonus bespoke iOS icons (Health)
    for bonus_name, svg_fn in ios_svgs.BONUS_SVGS.items():
        glyph = lge.render_svg_to_rgba(svg_fn())
        icon = lge.compose_liquid_glass_icon(glyph)
        icon.save(os.path.join(OUT_ICONS_DIR, f"{bonus_name}.png"), optimize=True)

    # OneLiquid App Icon (user's 'OneUI' logo transformed into matching Liquid Glass!)
    build_oneliquid_app_logo()

    # Fallback launcher masking assets
    lge.render_glass_plate_only().save(os.path.join(OUT_ICONS_DIR, "ic_glass_back.png"), optimize=True)
    lge.render_glass_mask().save(os.path.join(OUT_ICONS_DIR, "ic_glass_mask.png"), optimize=True)
    lge.render_glass_rim_only().save(os.path.join(OUT_ICONS_DIR, "ic_glass_upon.png"), optimize=True)

    with open(os.path.join(BASE_DIR, "icon_mapping.tsv"), "w", encoding="utf-8") as f:
        for row in generated:
            f.write("\t".join(row) + "\n")

    counts = {}
    for _, _, _, _, t in generated:
        counts[t] = counts.get(t, 0) + 1
    print(f"Generated {len(generated)} app icons + 3 bonus + OneLiquid logo + 3 fallback masks! Tiers: {counts}")

    # Generate two clean visual verification sheets (also used in GitHub README!)
    all_preview = [(r, lbl) for r, _, _, lbl, _ in generated] + [
        ("ios_spotify", "Spotify*"), ("ios_health", "Health*"), ("ios_appstore", "App Store*"),
        ("ic_oneliquid_launcher", "OneLiquid"),
    ]
    half = (len(all_preview) + 1) // 2
    for part_idx, chunk in enumerate([all_preview[:half], all_preview[half:]], start=1):
        cols = 8
        rows_n = (len(chunk) + cols - 1) // cols
        cell_w, cell_h = 116, 128
        W, H = cols * cell_w + 32, rows_n * cell_h + 32
        y, x = np.mgrid[0:H, 0:W].astype(np.float64)
        bg = np.zeros((H, W, 4), dtype=np.float64)
        bg[..., 0] = 38 + 38 * np.sin(x / 210.0) + 22 * np.cos(y / 230.0)
        bg[..., 1] = 108 + 46 * np.cos(x / 240.0) + 30 * np.sin(y / 210.0)
        bg[..., 2] = 166 + 34 * np.sin(x / 220.0 + 0.8) + 28 * np.cos(y / 250.0)
        bg[..., 3] = 255
        canvas = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8), "RGBA")
        draw = ImageDraw.Draw(canvas)

        for i, (rname, lbl) in enumerate(chunk):
            r, c = divmod(i, cols)
            im = Image.open(os.path.join(OUT_ICONS_DIR, f"{rname}.png")).convert("RGBA").resize((92, 92), Image.Resampling.LANCZOS)
            px = 16 + c * cell_w + 12
            py = 16 + r * cell_h + 6
            canvas.alpha_composite(im, (px, py))
            draw.text((px + 2, py + 98), lbl[:13], fill=(255, 255, 255, 240))

        out_sheet = os.path.join(BASE_DIR, "assets", f"preview_part{part_idx}.png")
        canvas.save(out_sheet)
        print("Saved preview sheet:", out_sheet)


if __name__ == "__main__":
    main()
