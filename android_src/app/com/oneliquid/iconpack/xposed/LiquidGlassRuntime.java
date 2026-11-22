package com.oneliquid.iconpack.xposed;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * True 120fps GPU AGSL "Liquid Syrup" Glass Refraction Engine for One UI 8 / 8.5 / 9+.
 *
 * Samples the live Home Screen wallpaper via BitmapShader and runs a physically-based
 * Snell's-law thick-droplet lens shader on every icon:
 *  - Convex center magnification (bulges wallpaper slightly like viscous liquid glass)
 *  - Dramatic curved-meniscus edge refraction (wraps & stretches colors moving behind the rim)
 *  - Real RGB chromatic dispersion (subtle prismatic wavelength splitting on curved bevels)
 *  - 5-tap smooth optical liquid diffusion + rich jewel saturation lift
 *  - Live gyroscope tilt + scroll-reactive wet specular dome sheen & caustic catchlights
 */
public final class LiquidGlassRuntime {

    public static final class PureGlyphDrawable extends BitmapDrawable {
        public PureGlyphDrawable(Resources res, Bitmap bitmap) {
            super(res, bitmap);
        }
    }

    // Main live refracting liquid-glass droplet body shader (samples live wallpaper!)
    private static final String LIQUID_LENS_BODY_AGSL =
        "uniform shader uWallpaper;\n" +
        "uniform float2 uSize;\n" +
        "uniform float2 uScreenPos;\n" +
        "uniform float uWallScale;\n" +
        "uniform float2 uTilt;\n" +
        "uniform float uStrength;\n" +
        "\n" +
        "float squircleSdf(float2 p, float2 b, float n) {\n" +
        "    float2 q = abs(p) / max(b, float2(1.0));\n" +
        "    float d = pow(pow(q.x, n) + pow(q.y, n), 1.0 / n) - 1.0;\n" +
        "    return d * min(b.x, b.y);\n" +
        "}\n" +
        "\n" +
        "float2 squircleNormal(float2 p, float2 b, float n) {\n" +
        "    float2 q = clamp(abs(p) / max(b, float2(1.0)), float2(0.0001), float2(2.0));\n" +
        "    float2 g = sign(p) * pow(q, float2(n - 1.0));\n" +
        "    return normalize(g + float2(0.0001));\n" +
        "}\n" +
        "\n" +
        "half3 sampleSoft(float2 coord, float blurPx) {\n" +
        "    half3 c0 = uWallpaper.eval(coord).rgb;\n" +
        "    half3 c1 = uWallpaper.eval(coord + float2( blurPx,  blurPx)).rgb;\n" +
        "    half3 c2 = uWallpaper.eval(coord + float2(-blurPx,  blurPx)).rgb;\n" +
        "    half3 c3 = uWallpaper.eval(coord + float2( blurPx, -blurPx)).rgb;\n" +
        "    half3 c4 = uWallpaper.eval(coord + float2(-blurPx, -blurPx)).rgb;\n" +
        "    return c0 * 0.36 + (c1 + c2 + c3 + c4) * 0.16;\n" +
        "}\n" +
        "\n" +
        "half4 main(float2 fragCoord) {\n" +
        "    float2 halfSize = uSize * 0.5;\n" +
        "    float2 p = fragCoord - halfSize;\n" +
        "    float minSide = min(uSize.x, uSize.y);\n" +
        "    float2 b = halfSize - float2(1.2);\n" +
        "    float sdf = squircleSdf(p, b, 4.5);\n" +
        "    if (sdf > 4.0) return half4(0.0);\n" +
        "\n" +
        "    // Soft outer floating contact shadow under the viscous droplet\n" +
        "    if (sdf > 0.5) {\n" +
        "        float sh = exp(-pow(sdf / 2.6, 2.0)) * 0.16;\n" +
        "        return half4(0.0, 0.0, 0.0, sh);\n" +
        "    }\n" +
        "\n" +
        "    float mask = clamp(0.5 - sdf * 0.95, 0.0, 1.0);\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float2 norm = squircleNormal(p, b, 4.5);\n" +
        "\n" +
        "    // Thick corn-syrup / liquid-droplet meniscus bevel (outer 28% of icon curves sharply!)\n" +
        "    float bevelW = minSide * 0.28;\n" +
        "    float edgeT = clamp(1.0 - dIn / bevelW, 0.0, 1.0);\n" +
        "    float meniscusSlope = pow(edgeT, 1.95);\n" +
        "\n" +
        "    // 1. Center convex dome magnification (magnifies wallpaper behind droplet by ~10-16%)\n" +
        "    float2 magOffset = -p * (0.11 * uStrength);\n" +
        "\n" +
        "    // 2. Dramatic Snell's-law inward edge refraction (bends & wraps wallpaper colors into the rim!)\n" +
        "    float bendPx = minSide * 0.22 * uStrength * meniscusSlope;\n" +
        "    float2 bendOffset = -norm * bendPx;\n" +
        "\n" +
        "    // 3. Subtle live gyroscope parallax shift inside the thick glass volume\n" +
        "    float2 tiltOffset = uTilt * (minSide * 0.075 * uStrength);\n" +
        "\n" +
        "    float2 baseScreen = uScreenPos + fragCoord + magOffset + bendOffset + tiltOffset;\n" +
        "    float blurR = (1.6 + 2.2 * (1.0 - edgeT * 0.5)) * uWallScale;\n" +
        "\n" +
        "    // 4. Real prismatic RGB chromatic dispersion along the curved bevel!\n" +
        "    float dispPx = minSide * 0.026 * uStrength * pow(edgeT, 1.6);\n" +
        "    half rCh = sampleSoft((baseScreen + norm * dispPx) * uWallScale, blurR).r;\n" +
        "    half gCh = sampleSoft(baseScreen * uWallScale, blurR).g;\n" +
        "    half bCh = sampleSoft((baseScreen - norm * dispPx) * uWallScale, blurR).b;\n" +
        "    half3 glassCol = half3(rCh, gCh, bCh);\n" +
        "\n" +
        "    // 5. Rich liquid-crystal optical saturation boost + luminous clarity (never dull/muddy!)\n" +
        "    half luma = dot(glassCol, half3(0.299, 0.587, 0.114));\n" +
        "    glassCol = mix(half3(luma), glassCol, half(1.26));\n" +
        "\n" +
        "    // Slight internal total-reflection depth ring just inside the bright rim (sells thick viscous glass!)\n" +
        "    float tirBand = exp(-pow((dIn - bevelW * 0.26) / (bevelW * 0.24), 2.0));\n" +
        "    glassCol *= half(1.0 - 0.14 * tirBand * uStrength);\n" +
        "\n" +
        "    // Subtle liquid frosted luminance wash (clearer center, slightly brighter top dome)\n" +
        "    float yn = p.y / max(halfSize.y, 1.0);\n" +
        "    float frostWash = 0.09 + 0.07 * clamp((1.0 - yn) * 0.5, 0.0, 1.0) + 0.08 * pow(edgeT, 2.2);\n" +
        "    glassCol = mix(glassCol, half3(1.0, 0.996, 1.0), half(frostWash));\n" +
        "\n" +
        "    // 6. Wet viscous dome sheen sweeping across top-left face (reacts live to gyroscope tilt!)\n" +
        "    float2 lDir = normalize(float2(-0.66, -0.75) + uTilt * 0.65);\n" +
        "    float2 pn = p / max(halfSize, float2(1.0));\n" +
        "    float domeSheen = pow(clamp(dot(pn, lDir) + 0.35, 0.0, 1.0), 3.0) * 0.16;\n" +
        "    glassCol += half3(domeSheen);\n" +
        "\n" +
        "    glassCol = clamp(glassCol, half3(0.0), half3(1.0));\n" +
        "    return half4(glassCol * mask, mask);\n" +
        "}\n";

    // Top specular liquid glaze & anisotropic Fresnel caustic rim drawn OVER the glyph!
    private static final String LIQUID_GLAZE_TOP_AGSL =
        "uniform float2 uSize;\n" +
        "uniform float2 uTilt;\n" +
        "uniform float uCornerRatio;\n" +
        "uniform float uIntensity;\n" +
        "\n" +
        "float squircleSdf(float2 p, float2 b, float n) {\n" +
        "    float2 q = abs(p) / max(b, float2(1.0));\n" +
        "    float d = pow(pow(q.x, n) + pow(q.y, n), 1.0 / n) - 1.0;\n" +
        "    return d * min(b.x, b.y);\n" +
        "}\n" +
        "\n" +
        "float2 squircleNormal(float2 p, float2 b, float n) {\n" +
        "    float2 q = clamp(abs(p) / max(b, float2(1.0)), float2(0.0001), float2(2.0));\n" +
        "    float2 g = sign(p) * pow(q, float2(n - 1.0));\n" +
        "    return normalize(g + float2(0.0001));\n" +
        "}\n" +
        "\n" +
        "half4 main(float2 fragCoord) {\n" +
        "    float2 halfSize = uSize * 0.5;\n" +
        "    float2 p = fragCoord - halfSize;\n" +
        "    float n = uCornerRatio > 0.35 ? 2.8 : 4.5;\n" +
        "    float2 b = halfSize - float2(1.0);\n" +
        "    float sdf = squircleSdf(p, b, n);\n" +
        "    if (sdf > 1.2) return half4(0.0);\n" +
        "    float mask = clamp(0.5 - sdf, 0.0, 1.0);\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float2 norm = squircleNormal(p, b, n);\n" +
        "\n" +
        "    // Gyroscope-reactive specular light direction (glides smoothly around the curved rim!)\n" +
        "    float2 tlDir = normalize(float2(-0.707, -0.707) + uTilt * 0.60);\n" +
        "    float2 brDir = -tlDir;\n" +
        "    float cosTL = max(0.0, dot(norm, tlDir));\n" +
        "    float cosBR = max(0.0, dot(norm, brDir));\n" +
        "\n" +
        "    // Razor-crisp liquid rim + soft curved meniscus caustic bloom + high-energy corner sparkle\n" +
        "    float rimBand = exp(-pow((dIn - 1.15) / 1.35, 2.0));\n" +
        "    float rimPower = 0.24 + 0.78 * pow(cosTL, 1.8) + 0.60 * pow(cosBR, 2.0);\n" +
        "    float innerCaustic = exp(-pow((dIn - 4.8) / 8.5, 2.0)) * (0.04 + 0.26 * pow(cosTL, 2.4) + 0.18 * pow(cosBR, 2.6));\n" +
        "    float sparkle = exp(-pow((dIn - 1.7) / 1.05, 2.0)) * (0.62 * pow(cosTL, 4.8) + 0.44 * pow(cosBR, 5.2));\n" +
        "\n" +
        "    // Subtle top liquid specular reflection arc (makes the glyph look submerged inside wet glass!)\n" +
        "    float2 pn = p / max(halfSize, float2(1.0));\n" +
        "    float topArc = exp(-pow((pn.y + 0.72) / 0.28, 2.0)) * (1.0 - abs(pn.x) * 0.55) * 0.12;\n" +
        "\n" +
        "    float a = clamp((rimBand * rimPower + innerCaustic + sparkle + topArc) * uIntensity, 0.0, 0.94) * mask;\n" +
        "    return half4(half3(1.0, 0.996, 1.0) * a, a);\n" +
        "}\n";

    private static volatile BitmapShader sWallpaperShader;
    private static volatile float sWallScale = 0.5f;
    private static volatile long sLastWallAttemptMs = 0L;

    private static RuntimeShader sLensShader;
    private static Paint sLensPaint;
    private static boolean sLensFailed = false;

    private static RuntimeShader sGlazeShader;
    private static Paint sGlazePaint;
    private static boolean sGlazeFailed = false;

    public static void invalidateWallpaperCache() {
        sWallpaperShader = null;
        sLastWallAttemptMs = 0L;
    }

    /**
     * Loads and caches the current live Home Screen wallpaper scaled to 50% display resolution
     * into a fast GPU BitmapShader for real-time 120fps Snell's-law refraction.
     */
    public static BitmapShader ensureWallpaperShader(Context ctx) {
        if (sWallpaperShader != null) return sWallpaperShader;
        long now = System.currentTimeMillis();
        if (now - sLastWallAttemptMs < 4000L) return null;
        sLastWallAttemptMs = now;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            Drawable wd = null;
            try { wd = wm.getDrawable(); } catch (Throwable ignored) {}
            if (wd == null) {
                try { wd = wm.peekDrawable(); } catch (Throwable ignored) {}
            }
            if (wd == null) return null;

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int sw = Math.max(dm.widthPixels, 720);
            int sh = Math.max(dm.heightPixels, 1280);
            // Half-resolution GPU texture (e.g. 540x1170): ~2.5 MB RAM, blazing 120fps sampling!
            float scale = 0.5f;
            int tw = Math.max(180, Math.round(sw * scale));
            int th = Math.max(320, Math.round(sh * scale));

            Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);

            int dw = wd.getIntrinsicWidth();
            int dh = wd.getIntrinsicHeight();
            if (dw > 0 && dh > 0) {
                // Center-crop wallpaper to match screen aspect ratio cleanly
                float sx = tw / (float) dw;
                float sy = th / (float) dh;
                float s = Math.max(sx, sy);
                int rw = Math.round(dw * s);
                int rh = Math.round(dh * s);
                int ox = (tw - rw) / 2;
                int oy = (th - rh) / 2;
                wd.setBounds(ox, oy, ox + rw, oy + rh);
            } else {
                wd.setBounds(0, 0, tw, th);
            }
            wd.draw(c);

            sWallScale = scale;
            sWallpaperShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            return sWallpaperShader;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Draws the LIVE refracting, magnifying, chromatic-dispersing "Corn Syrup" Liquid Glass droplet!
     * Returns true if the live wallpaper-refracting GPU shader rendered.
     */
    public static boolean drawLiveLiquidLensBody(Context ctx, Canvas canvas,
                                                 float left, float top, float width, float height,
                                                 float screenX, float screenY,
                                                 float tiltX, float tiltY, float strength) {
        if (width < 16f || height < 16f || Build.VERSION.SDK_INT < 33 || sLensFailed) return false;
        BitmapShader wall = ensureWallpaperShader(ctx);
        if (wall == null) return false;
        try {
            if (sLensShader == null) {
                sLensShader = new RuntimeShader(LIQUID_LENS_BODY_AGSL);
                sLensPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            }
            sLensShader.setInputShader("uWallpaper", wall);
            sLensShader.setFloatUniform("uSize", width, height);
            sLensShader.setFloatUniform("uScreenPos", screenX, screenY);
            sLensShader.setFloatUniform("uWallScale", sWallScale);
            sLensShader.setFloatUniform("uTilt", tiltX, tiltY);
            sLensShader.setFloatUniform("uStrength", strength);
            sLensPaint.setShader(sLensShader);

            canvas.save();
            canvas.translate(left, top);
            canvas.drawRect(-4f, -4f, width + 4f, height + 4f, sLensPaint);
            canvas.restore();
            return true;
        } catch (Throwable ignored) {
            sLensFailed = true;
            return false;
        }
    }

    /**
     * Draws the live gyroscope-reactive specular glaze & anisotropic Fresnel caustic rim OVER the glyph.
     */
    public static void drawLiveGlazeRim(Canvas canvas, float left, float top, float width, float height,
                                        float tiltX, float tiltY, boolean isPill, float intensity) {
        if (width < 12f || height < 12f || intensity <= 0.01f) return;
        if (Build.VERSION.SDK_INT >= 33 && !sGlazeFailed) {
            try {
                if (sGlazeShader == null) {
                    sGlazeShader = new RuntimeShader(LIQUID_GLAZE_TOP_AGSL);
                    sGlazePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                }
                sGlazeShader.setFloatUniform("uSize", width, height);
                sGlazeShader.setFloatUniform("uTilt", tiltX, tiltY);
                sGlazeShader.setFloatUniform("uCornerRatio", isPill ? 0.48f : 0.23f);
                sGlazeShader.setFloatUniform("uIntensity", intensity);
                sGlazePaint.setShader(sGlazeShader);

                canvas.save();
                canvas.translate(left, top);
                canvas.drawRect(0f, 0f, width, height, sGlazePaint);
                canvas.restore();
                return;
            } catch (Throwable ignored) {
                sGlazeFailed = true;
            }
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.4f, width * 0.013f));
        p.setShader(new LinearGradient(
                left, top, left + width, top + height,
                new int[] { Color.argb((int) (220 * intensity), 255, 255, 255),
                            Color.argb((int) (60 * intensity), 255, 255, 255),
                            Color.argb((int) (185 * intensity), 255, 255, 255) },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP
        ));
        float r = isPill ? Math.min(width, height) * 0.45f : Math.min(width, height) * 0.235f;
        canvas.drawRoundRect(new RectF(left + 1f, top + 1f, left + width - 1f, top + height - 1f), r, r, p);
    }

    /**
     * Strips the static baked translucent glass plate & static outer rim from a OneLiquid PNG
     * (keeping ONLY the crisp luminous pure-white frosted glyph!), so it floats cleanly inside
     * our LIVE GPU AGSL refracting & magnifying liquid-glass droplet with ZERO double borders!
     */
    public static Bitmap extractPureGlyphFromOneLiquid(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        float cx = (w - 1) * 0.5f;
        float cy = (h - 1) * 0.5f;
        float rx = w * 0.435f; // outer ~6.5% perimeter holds the static baked rim -> strip it cleanly!
        float ry = h * 0.435f;

        for (int y = 0; y < h; y++) {
            float dy = Math.abs(y - cy) / ry;
            float dy4 = dy * dy * dy * dy;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int argb = px[row + x];
                int a = (argb >>> 24) & 0xFF;
                if (a <= 105) {
                    // Static baked translucent glass body (~40..95 alpha) -> clear completely!
                    px[row + x] = 0;
                    continue;
                }
                float dx = Math.abs(x - cx) / rx;
                float sDist = (float) Math.pow(dx * dx * dx * dx + dy4, 0.25);
                if (sDist >= 1.0f) {
                    // Static baked outer rim -> clear so live AGSL rim replaces it cleanly!
                    px[row + x] = 0;
                    continue;
                }
                // Softly ramp glyph alpha above plate floor (105..155) for smooth AA edges
                float glyphA;
                if (a < 155) {
                    glyphA = (a - 105f) / 50f * 155f;
                } else {
                    glyphA = a;
                }
                // Feather right near the inner 0.94..1.0 boundary
                if (sDist > 0.94f) {
                    glyphA *= (1.0f - sDist) / 0.06f;
                }
                int outA = Math.max(0, Math.min(255, Math.round(glyphA)));
                // Keep luminous pure frosted white
                px[row + x] = (outA << 24) | 0x00FFFFFF;
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /**
     * Detects whether a Drawable is already a translucent glass icon (e.g. from Theme Park).
     */
    public static boolean isAlreadyTranslucentGlass(Drawable d) {
        if (d == null) return false;
        if (d instanceof PureGlyphDrawable) return true;
        try {
            Bitmap bmp = null;
            boolean recycle = false;
            if (d instanceof BitmapDrawable) bmp = ((BitmapDrawable) d).getBitmap();
            if (bmp == null || bmp.isRecycled()) {
                bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, 32, 32);
                d.draw(c);
                recycle = true;
            }
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[][] pts = {
                { w / 6, h / 2 }, { w - w / 6, h / 2 },
                { w / 2, h / 6 }, { w / 2, h - h / 6 },
                { w / 5, h / 5 }, { w - w / 5, h / 5 },
                { w / 5, h - h / 5 }, { w - w / 5, h - h / 5 }
            };
            int count = 0;
            for (int[] pt : pts) {
                int a = Color.alpha(bmp.getPixel(Math.max(0, Math.min(w - 1, pt[0])), Math.max(0, Math.min(h - 1, pt[1]))));
                if (a >= 18 && a <= 195) count++;
            }
            if (recycle) bmp.recycle();
            return count >= 4;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Converts any unknown opaque stock Drawable into a pure luminous frosted-white glyph
     * (no static background box!) ready to float inside our live AGSL refracting glass droplet!
     */
    public static Bitmap extractPureGlyphFromOpaqueDrawable(Drawable src, int sizePx) {
        if (src == null || sizePx <= 0) return null;
        int sz = Math.max(96, Math.min(sizePx, 256));
        Drawable glyphSrc = src;
        if (Build.VERSION.SDK_INT >= 33 && src instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable aid = (AdaptiveIconDrawable) src;
            if (aid.getMonochrome() != null) glyphSrc = aid.getMonochrome();
            else if (aid.getForeground() != null) glyphSrc = aid.getForeground();
        } else if (src instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable aid = (AdaptiveIconDrawable) src;
            if (aid.getForeground() != null) glyphSrc = aid.getForeground();
        }

        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int gSz = Math.round(sz * 0.66f);
        Bitmap raw = Bitmap.createBitmap(gSz, gSz, Bitmap.Config.ARGB_8888);
        Canvas gc = new Canvas(raw);
        glyphSrc.setBounds(0, 0, gSz, gSz);
        glyphSrc.draw(gc);

        Bitmap frosted = Bitmap.createBitmap(gSz, gSz, Bitmap.Config.ARGB_8888);
        Canvas fc = new Canvas(frosted);
        fc.drawBitmap(raw, 0f, 0f, null);
        Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        tint.setShader(new LinearGradient(
                0f, 0f, 0f, gSz,
                Color.argb(248, 255, 255, 255),
                Color.argb(165, 245, 250, 255),
                Shader.TileMode.CLAMP
        ));
        fc.drawRect(0f, 0f, gSz, gSz, tint);
        raw.recycle();

        float off = (sz - gSz) * 0.5f;
        c.drawBitmap(frosted, off, off, null);
        frosted.recycle();
        return out;
    }

    public static boolean applySemBlur(View view, int blurRadius, float cornerRadiusPx, int bgTintColor) {
        if (view == null) return false;
        try {
            Class<?> builderCls = Class.forName("android.view.SemBlurInfo$Builder");
            Constructor<?> ctor = builderCls.getConstructor(int.class);
            Object builder = ctor.newInstance(0);
            try { builderCls.getMethod("setRadius", int.class).invoke(builder, blurRadius); } catch (Throwable ignored) {}
            try { builderCls.getMethod("setBackgroundCornerRadius", float.class).invoke(builder, cornerRadiusPx); } catch (Throwable ignored) {}
            try { builderCls.getMethod("setBackgroundColor", int.class).invoke(builder, bgTintColor); } catch (Throwable ignored) {}
            Object blurInfo = builderCls.getMethod("build").invoke(builder);
            Method semSetBlur = View.class.getMethod("semSetBlurInfo", Class.forName("android.view.SemBlurInfo"));
            semSetBlur.invoke(view, blurInfo);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
