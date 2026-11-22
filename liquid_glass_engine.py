import io
import math
import numpy as np
from PIL import Image
from scipy.ndimage import distance_transform_edt, gaussian_filter, sobel
import resvg_py

SIZE = 512       # Supersampled internal resolution
OUT_SIZE = 256   # Final crisp PNG resolution for One UI Theme Park (450dpi QHD)
MARGIN = 10      # Margin at 512px (~5px at 256px) -> ~96% canvas fill
# Tuned slightly rounder per user feedback (v1.0 was 5.25, v1.1 was 4.80 -> v1.2 is 4.50)
SQUIRCLE_N = 4.50

_PLATE_CACHE = None


def _compute_squircle_sdf(size=SIZE, margin=MARGIN, n=SQUIRCLE_N):
    """Return exact Euclidean SDF (negative inside, positive outside), unit normal (nx, ny), and soft AA mask."""
    half = (size - 2 * margin) / 2.0
    c = (size - 1) / 2.0
    y, x = np.mgrid[0:size, 0:size].astype(np.float64)
    dx = (x - c) / half
    dy = (y - c) / half

    # Apple iOS 26 continuous-corner squircle (superellipse n = 4.80)
    val = (np.abs(dx) ** n + np.abs(dy) ** n) ** (1.0 / n)
    inside = val <= 1.0

    dist_in = distance_transform_edt(inside)
    dist_out = distance_transform_edt(~inside)
    sdf = dist_out - dist_in

    # Crisp antialiased interior mask
    mask = np.clip(0.5 - sdf * 0.90, 0.0, 1.0)

    # Smooth outward normal so corner specular caustics curve organically like liquid glass
    sdf_smooth = gaussian_filter(sdf, sigma=2.5)
    gy, gx = np.gradient(sdf_smooth)
    mag = np.sqrt(gx * gx + gy * gy) + 1e-8
    nx = gx / mag
    ny = gy / mag

    return sdf, nx, ny, mask, dx, dy


def _alpha_over(dst, src):
    """Porter-Duff SRC_OVER compositing of float RGBA arrays in [0, 1]."""
    sa = src[..., 3:4]
    da = dst[..., 3:4]
    out_a = sa + da * (1.0 - sa)
    out_rgb = (src[..., :3] * sa + dst[..., :3] * da * (1.0 - sa)) / np.maximum(out_a, 1e-6)
    return np.concatenate([np.clip(out_rgb, 0.0, 1.0), np.clip(out_a, 0.0, 1.0)], axis=-1)


def get_plate_components():
    """
    Precompute official iOS 26 Clear Liquid Glass plate layers calibrated directly
    against Apple's WWDC25 iOS 26 2x retina Home Screen reference:
      - Crystal-clear luminous white glass body (never murky/dirty gray!)
      - Razor-crisp anisotropic Fresnel specular rim peaking at TL (-135°) & BR (+45°)
      - Delicate curved-lens inner meniscus sheen & micro-chromatic dispersion
      - Weightless floating drop shadow
    """
    global _PLATE_CACHE
    if _PLATE_CACHE is not None:
        return _PLATE_CACHE

    sdf, nx, ny, mask, dx, dy = _compute_squircle_sdf()
    H, W = sdf.shape
    theta = np.arctan2(ny, nx)
    d_in = np.maximum(-sdf, 0.0)

    # --- 1. Weightless floating shadow (subtle so glass stays airy & crystal clear) ---
    shadow_src = np.roll(mask, shift=5, axis=0)
    shadow_src[:5, :] = 0.0
    shadow_blur = gaussian_filter(shadow_src, sigma=7.0)
    shadow_layer = np.zeros((H, W, 4), dtype=np.float64)
    shadow_layer[..., :3] = 0.0
    shadow_layer[..., 3] = shadow_blur * 0.09 * (1.0 - mask * 0.88)

    # --- 2. Crystal-Clear Luminous Glass Body ---
    # Pure luminous white glass tint (no muddy gray!), letting wallpaper color & luminance shine through!
    yn = dy
    xn = dx
    edge_meniscus = np.exp(-d_in / 16.0)

    # Center ~0.160 clear frosted wash, slightly brighter at top (~0.215) & curved meniscus edge (+0.055)
    body_alpha = (
        0.160
        + 0.055 * np.clip((1.0 - yn) * 0.5, 0.0, 1.0) ** 1.15
        + 0.055 * edge_meniscus
    )
    # Soft diagonal light sweep across upper-left glass face
    diag = np.clip((-xn - yn + 0.30) / 2.2, 0.0, 1.0) ** 2.4
    body_alpha += 0.052 * diag

    body_layer = np.zeros((H, W, 4), dtype=np.float64)
    body_layer[..., 0] = 0.985 + 0.015 * diag
    body_layer[..., 1] = 0.992 + 0.008 * diag
    body_layer[..., 2] = 1.000
    body_layer[..., 3] = np.clip(body_alpha, 0.0, 0.36) * mask

    # --- 3. Whisper-Subtle Internal Refraction Depth Lip (very delicate, never murky!) ---
    tl_angle = -3.0 * math.pi / 4.0  # -135 deg (top-left)
    br_angle = math.pi / 4.0         # +45 deg (bottom-right)
    cos_tl = np.maximum(0.0, np.cos(theta - tl_angle))
    cos_br = np.maximum(0.0, np.cos(theta - br_angle))

    trough_band = np.exp(-((d_in - 7.5) / 5.5) ** 2) * mask
    trough_dir = 1.0 - 0.65 * (cos_tl ** 1.4) - 0.45 * (cos_br ** 1.5)
    trough_layer = np.zeros((H, W, 4), dtype=np.float64)
    trough_layer[..., 0] = 0.05
    trough_layer[..., 1] = 0.07
    trough_layer[..., 2] = 0.10
    trough_layer[..., 3] = np.clip(trough_band * trough_dir * 0.055, 0.0, 0.09)

    base_plate = _alpha_over(shadow_layer, body_layer)
    base_plate = _alpha_over(base_plate, trough_layer)

    # --- 4. Razor-Crisp Anisotropic Fresnel Specular Rim & Liquid Glass Meniscus ---
    # 4a. Micro outer contrast kiss (barely perceptible on dark bg, defines edge cleanly on white bg)
    outer_kiss = np.exp(-((sdf - 0.6) / 1.0) ** 2) * (1.0 - mask * 0.80)
    outer_layer = np.zeros((H, W, 4), dtype=np.float64)
    outer_layer[..., :3] = 0.08
    outer_layer[..., 3] = outer_kiss * 0.05

    # 4b. Curved-lens inner caustic glow hugging top-left & bottom-right rounded corners
    inner_glow_band = np.exp(-((d_in - 4.8) / 9.5) ** 2) * mask
    inner_glow_dir = 0.04 + 0.24 * (cos_tl ** 2.2) + 0.17 * (cos_br ** 2.4)
    inner_glow = np.zeros((H, W, 4), dtype=np.float64)
    inner_glow[..., 0] = 0.99
    inner_glow[..., 1] = 0.995
    inner_glow[..., 2] = 1.00
    inner_glow[..., 3] = np.clip(inner_glow_band * inner_glow_dir, 0.0, 0.30)

    # 4c. Razor-crisp optical glass rim (thinner & cleaner than v2 -> ~1.4px at 256px!)
    rim_profile = np.exp(-((d_in - 1.45) / 1.42) ** 2) * mask
    rim_r_prof = np.exp(-((d_in - 1.15) / 1.42) ** 2) * mask
    rim_b_prof = np.exp(-((d_in - 1.75) / 1.42) ** 2) * mask

    # Continuous delicate crystal hairline around all 4 sides (~0.32),
    # surging smoothly into brilliant liquid-glass specular arcs at TL (~0.95) & BR (~0.80)
    rim_dir = 0.32 + 0.65 * (cos_tl ** 1.75) + 0.48 * (cos_br ** 2.0)
    rim_dir = np.clip(rim_dir, 0.0, 0.97)

    rim_layer = np.zeros((H, W, 4), dtype=np.float64)
    caustic_boost = np.clip(cos_tl ** 2.6 + cos_br ** 2.6, 0.0, 1.0)
    rim_layer[..., 0] = np.clip(0.985 + 0.015 * np.clip(rim_r_prof / (rim_profile + 1e-4), 0.85, 1.15) * caustic_boost, 0.0, 1.0)
    rim_layer[..., 1] = 0.995
    rim_layer[..., 2] = np.clip(0.990 + 0.010 * np.clip(rim_b_prof / (rim_profile + 1e-4), 0.85, 1.15) * caustic_boost, 0.0, 1.0)
    rim_layer[..., 3] = np.clip(rim_profile * rim_dir, 0.0, 0.97)

    # 4d. High-energy corner specular catchlight sparkle (pure white liquid glint on TL & BR arcs)
    sparkle_profile = np.exp(-((d_in - 2.0) / 1.15) ** 2) * mask
    sparkle_dir = 0.55 * (cos_tl ** 5.2) + 0.40 * (cos_br ** 5.6)
    sparkle_layer = np.zeros((H, W, 4), dtype=np.float64)
    sparkle_layer[..., :3] = 1.0
    sparkle_layer[..., 3] = np.clip(sparkle_profile * sparkle_dir, 0.0, 0.65)

    rim_overlay = _alpha_over(outer_layer, inner_glow)
    rim_overlay = _alpha_over(rim_overlay, rim_layer)
    rim_overlay = _alpha_over(rim_overlay, sparkle_layer)

    _PLATE_CACHE = {
        "base": base_plate,
        "rim": rim_overlay,
        "mask": mask,
        "sdf": sdf,
        "dx": dx,
        "dy": dy,
    }
    return _PLATE_CACHE


def render_svg_to_rgba(svg_str, size=SIZE):
    """Render SVG string to float64 RGBA array in [0, 1] at `size x size`."""
    png_bytes = resvg_py.svg_to_bytes(svg_string=svg_str, width=size, height=size)
    im = Image.open(io.BytesIO(png_bytes)).convert("RGBA")
    if im.size != (size, size):
        im = im.resize((size, size), Image.Resampling.LANCZOS)
    return np.asarray(im, dtype=np.float64) / 255.0


def shade_glass_glyph(glyph_rgba, mask, bevel_strength=0.65, shadow_strength=0.35,
                      min_bottom_alpha=0.58, max_top_alpha=0.96, preserve_tonal=False):
    """
    Apple iOS 26 Clear Luminous Frosted Glass Glyph Shader:
      - PURE LUMINOUS WHITE (#FFFFFF) — no muddy gray tint!
      - Glass depth achieved via smooth top-to-bottom ALPHA TRANSLUCENCY gradient
        (dense frosted white at top ~0.96 -> airy translucent white at bottom ~0.58,
        so the wallpaper glows gently upward through every glyph just like real iOS 26!)
      - Crisp subtle top-left specular edge catchlight on the glyph silhouette
      - Feather-light depth shadow (keeps glyphs airy, never dirty gray)
    """
    H, W, _ = glyph_rgba.shape
    a = glyph_rgba[..., 3]
    lum = 0.299 * glyph_rgba[..., 0] + 0.587 * glyph_rgba[..., 1] + 0.114 * glyph_rgba[..., 2]

    if preserve_tonal:
        density = a * np.clip(0.24 + 0.78 * (lum ** 0.80), 0.0, 1.0)
    else:
        if np.any(a > 0.1) and np.max(lum[a > 0.1]) < 0.08:
            density = a
        else:
            density = a * np.clip(lum, 0.0, 1.0)

    density = density * mask

    # 1. Whisper-soft elevation shadow (subtle separation on bright backgrounds, never muddy)
    sh = np.roll(density, shift=4, axis=0)
    sh = np.roll(sh, shift=1, axis=1)
    sh[:4, :] = 0.0
    sh[:, :1] = 0.0
    sh = gaussian_filter(sh, sigma=7.5) * mask
    shadow_layer = np.zeros((H, W, 4), dtype=np.float64)
    shadow_layer[..., 0] = 0.02
    shadow_layer[..., 1] = 0.04
    shadow_layer[..., 2] = 0.07
    shadow_layer[..., 3] = np.clip(sh * 0.10 * shadow_strength, 0.0, 0.16)

    # 2. Local vertical translucent glass gradient (pure luminous white, fading alpha bottomward!)
    nz_y = np.where(density.max(axis=1) > 0.05)[0]
    if len(nz_y) >= 2:
        y0, y1 = nz_y[0], nz_y[-1]
        y_local = np.clip((np.arange(H)[:, None] - y0) / max(y1 - y0, 1.0), 0.0, 1.0)
    else:
        y_local = np.linspace(0.0, 1.0, H)[:, None]

    # Top of glyph: crisp dense frosted white; bottom: airy translucent frosted glass!
    grad_alpha = max_top_alpha - (max_top_alpha - min_bottom_alpha) * (y_local ** 1.15)

    # 3. Crisp top-left glass edge catchlight on the glyph (like Apple's frosted acrylic edges)
    smooth_d = gaussian_filter(density, sigma=1.4)
    gx = sobel(smooth_d, axis=1) / 8.0
    gy = sobel(smooth_d, axis=0) / 8.0
    light_dot = gx * 0.707 + gy * 0.707
    top_spec = np.clip(light_dot, 0.0, 1.0) * bevel_strength
    bot_rim = np.clip(-light_dot, 0.0, 1.0) * bevel_strength

    # Pure luminous white throughout (very gentle 3% cool tint at bottom, NO dark gray!)
    base_rgb = np.ones((H, W, 3), dtype=np.float64)
    base_rgb[..., 0] = 1.0 - 0.03 * y_local
    base_rgb[..., 1] = 1.0 - 0.015 * y_local
    base_rgb[..., 2] = 1.0

    # Subtle bright top-left rim + delicate bottom-right edge light (internal refraction in glyph!)
    final_alpha = np.clip(
        density * grad_alpha
        + top_spec * 0.24 * (density > 0.04)
        + bot_rim * 0.08 * (density > 0.04),
        0.0, 0.98
    ) * mask

    glyph_layer = np.zeros((H, W, 4), dtype=np.float64)
    glyph_layer[..., :3] = base_rgb
    glyph_layer[..., 3] = final_alpha

    return shadow_layer, glyph_layer


def compose_liquid_glass_icon(glyph_rgba=None, bg_accent_rgba=None, preserve_tonal=False,
                              bevel_strength=0.65, shadow_strength=0.35,
                              min_bottom_alpha=0.58, max_top_alpha=0.96,
                              out_size=OUT_SIZE):
    """Compose a complete 1:1 Apple iOS 26 Clear Liquid Glass icon."""
    plate = get_plate_components()
    out = plate["base"].copy()
    mask = plate["mask"]

    if bg_accent_rgba is not None:
        acc = bg_accent_rgba.copy()
        acc[..., 3] *= mask
        out = _alpha_over(out, acc)

    if glyph_rgba is not None:
        sh, gl = shade_glass_glyph(
            glyph_rgba, mask,
            bevel_strength=bevel_strength,
            shadow_strength=shadow_strength,
            min_bottom_alpha=min_bottom_alpha,
            max_top_alpha=max_top_alpha,
            preserve_tonal=preserve_tonal,
        )
        out = _alpha_over(out, sh)
        out = _alpha_over(out, gl)

    out = _alpha_over(out, plate["rim"])

    img = Image.fromarray(np.clip(out * 255.0 + 0.5, 0, 255).astype(np.uint8), mode="RGBA")
    if out_size != SIZE:
        img = img.resize((out_size, out_size), Image.Resampling.LANCZOS)
    return img


def render_glass_plate_only(out_size=OUT_SIZE):
    return compose_liquid_glass_icon(glyph_rgba=None, out_size=out_size)


def render_glass_mask(out_size=OUT_SIZE):
    plate = get_plate_components()
    mask = plate["mask"]
    arr = np.zeros((SIZE, SIZE, 4), dtype=np.uint8)
    arr[..., :3] = 255
    arr[..., 3] = np.clip(mask * 255.0 + 0.5, 0, 255).astype(np.uint8)
    img = Image.fromarray(arr, mode="RGBA")
    if out_size != SIZE:
        img = img.resize((out_size, out_size), Image.Resampling.LANCZOS)
    return img


def render_glass_rim_only(out_size=OUT_SIZE):
    plate = get_plate_components()
    rim = plate["rim"]
    img = Image.fromarray(np.clip(rim * 255.0 + 0.5, 0, 255).astype(np.uint8), mode="RGBA")
    if out_size != SIZE:
        img = img.resize((out_size, out_size), Image.Resampling.LANCZOS)
    return img
