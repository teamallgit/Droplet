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
import android.graphics.Path;
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
 * Dual-Engine 120fps Liquid Glass Shader & Samsung SemBlurInfo Engine (One UI 8 / 8.5 / 9+):
 *
 *  Engine A (Static Wallpaper):
 *    Full GPU AGSL Snell's-law pixel refraction + convex lens magnification + RGB dispersion
 *    sampling the live wallpaper BitmapShader at 120fps.
 *
 *  Engine B (Live Animated Wallpaper, e.g. ColorOS / Video / Interactive Live Wallpapers):
 *    Samsung native 120fps SurfaceFlinger hardware backdrop blur (SemBlurInfo BLUR_MODE_WINDOW
 *    clipped to exact Apple squircle rect/path!) + Crystal-Clear Alpha-Blended Viscous Liquid
 *    Glass AGSL shader (wet curved meniscus dome sheen, prismatic corner dispersion, gyro tilt!).
 */
public final class LiquidGlassRuntime {

    public static final class PureGlyphDrawable extends BitmapDrawable {
        public PureGlyphDrawable(Resources res, Bitmap bitmap) {
            super(res, bitmap);
        }
    }

    // Engine A: Static-wallpaper true pixel-warping Snell's-law refraction + convex lens magnification
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
        "float2 squircleNormal(float2 p, float2 b, float n) {\n" +
        "    float2 q = clamp(abs(p) / max(b, float2(1.0)), float2(0.0001), float2(2.0));\n" +
        "    float2 g = sign(p) * pow(q, float2(n - 1.0));\n" +
        "    return normalize(g + float2(0.0001));\n" +
        "}\n" +
        "half3 sampleSoft(float2 coord, float blurPx) {\n" +
        "    half3 c0 = uWallpaper.eval(coord).rgb;\n" +
        "    half3 c1 = uWallpaper.eval(coord + float2( blurPx,  blurPx)).rgb;\n" +
        "    half3 c2 = uWallpaper.eval(coord + float2(-blurPx,  blurPx)).rgb;\n" +
        "    half3 c3 = uWallpaper.eval(coord + float2( blurPx, -blurPx)).rgb;\n" +
        "    half3 c4 = uWallpaper.eval(coord + float2(-blurPx, -blurPx)).rgb;\n" +
        "    return c0 * 0.36 + (c1 + c2 + c3 + c4) * 0.16;\n" +
        "}\n" +
        "half4 main(float2 fragCoord) {\n" +
        "    float2 halfSize = uSize * 0.5;\n" +
        "    float2 p = fragCoord - halfSize;\n" +
        "    float minSide = min(uSize.x, uSize.y);\n" +
        "    float2 b = halfSize - float2(1.2);\n" +
        "    float sdf = squircleSdf(p, b, 4.5);\n" +
        "    if (sdf > 4.0) return half4(0.0);\n" +
        "    if (sdf > 0.5) {\n" +
        "        float sh = exp(-pow(sdf / 2.6, 2.0)) * 0.12;\n" +
        "        return half4(0.0, 0.0, 0.0, sh);\n" +
        "    }\n" +
        "    float mask = clamp(0.5 - sdf * 0.95, 0.0, 1.0);\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float2 norm = squircleNormal(p, b, 4.5);\n" +
        "    float bevelW = minSide * 0.28;\n" +
        "    float edgeT = clamp(1.0 - dIn / bevelW, 0.0, 1.0);\n" +
        "    float meniscusSlope = pow(edgeT, 1.9);\n" +
        "    float2 magOffset = -p * (0.11 * uStrength);\n" +
        "    float bendPx = minSide * 0.22 * uStrength * meniscusSlope;\n" +
        "    float2 bendOffset = -norm * bendPx;\n" +
        "    float2 tiltOffset = uTilt * (minSide * 0.075 * uStrength);\n" +
        "    float2 baseScreen = uScreenPos + fragCoord + magOffset + bendOffset + tiltOffset;\n" +
        "    float blurR = (1.6 + 2.2 * (1.0 - edgeT * 0.5)) * uWallScale;\n" +
        "    float dispPx = minSide * 0.028 * uStrength * pow(edgeT, 1.6);\n" +
        "    half rCh = sampleSoft((baseScreen + norm * dispPx) * uWallScale, blurR).r;\n" +
        "    half gCh = sampleSoft(baseScreen * uWallScale, blurR).g;\n" +
        "    half bCh = sampleSoft((baseScreen - norm * dispPx) * uWallScale, blurR).b;\n" +
        "    half3 glassCol = half3(rCh, gCh, bCh);\n" +
        "    half luma = dot(glassCol, half3(0.299, 0.587, 0.114));\n" +
        "    glassCol = mix(half3(luma), glassCol, half(1.28));\n" +
        "    float tirBand = exp(-pow((dIn - bevelW * 0.24) / (bevelW * 0.22), 2.0));\n" +
        "    glassCol *= half(1.0 - 0.11 * tirBand * uStrength);\n" +
        "    float yn = p.y / max(halfSize.y, 1.0);\n" +
        "    float frostWash = 0.12 + 0.08 * clamp((1.0 - yn) * 0.5, 0.0, 1.0) + 0.09 * pow(edgeT, 2.2);\n" +
        "    glassCol = mix(glassCol, half3(1.0, 0.996, 1.0), half(frostWash));\n" +
        "    float2 lDir = normalize(float2(-0.66, -0.75) + uTilt * 0.65);\n" +
        "    float2 pn = p / max(halfSize, float2(1.0));\n" +
        "    float domeSheen = pow(clamp(dot(pn, lDir) + 0.35, 0.0, 1.0), 2.8) * 0.18;\n" +
        "    glassCol += half3(domeSheen);\n" +
        "    glassCol = clamp(glassCol, half3(0.0), half3(1.0));\n" +
        "    return half4(glassCol * mask, mask);\n" +
        "}\n";

    // Engine B: Crystal-Clear Alpha-Blended Viscous "Liquid Syrup" Glass Body (for Live Animated Wallpapers!)
    // Zero outer cell halo — strictly clipped to the exact Apple n=4.50 continuous squircle!
    private static final String LIQUID_CLEAR_BODY_AGSL =
        "uniform float2 uSize;\n" +
        "uniform float2 uTilt;\n" +
        "uniform float uStrength;\n" +
        "\n" +
        "float squircleSdf(float2 p, float2 b, float n) {\n" +
        "    float2 q = abs(p) / max(b, float2(1.0));\n" +
        "    float d = pow(pow(q.x, n) + pow(q.y, n), 1.0 / n) - 1.0;\n" +
        "    return d * min(b.x, b.y);\n" +
        "}\n" +
        "float2 squircleNormal(float2 p, float2 b, float n) {\n" +
        "    float2 q = clamp(abs(p) / max(b, float2(1.0)), float2(0.0001), float2(2.0));\n" +
        "    float2 g = sign(p) * pow(q, float2(n - 1.0));\n" +
        "    return normalize(g + float2(0.0001));\n" +
        "}\n" +
        "half4 main(float2 fragCoord) {\n" +
        "    float2 halfSize = uSize * 0.5;\n" +
        "    float2 p = fragCoord - halfSize;\n" +
        "    float minSide = min(uSize.x, uSize.y);\n" +
        "    float2 b = halfSize - float2(1.2);\n" +
        "    float sdf = squircleSdf(p, b, 4.5);\n" +
        "    if (sdf > 4.5) return half4(0.0);\n" +
        "    // Delicate floating contact shadow directly under the liquid droplet\n" +
        "    if (sdf > 0.4) {\n" +
        "        float sh = exp(-pow(sdf / 2.8, 2.0)) * 0.14;\n" +
        "        return half4(0.0, 0.0, 0.0, sh);\n" +
        "    }\n" +
        "    float mask = clamp(0.5 - sdf * 0.95, 0.0, 1.0);\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float2 norm = squircleNormal(p, b, 4.5);\n" +
        "    float2 pn = p / max(halfSize, float2(1.0));\n" +
        "\n" +
        "    // Thick viscous curved meniscus edge (outer 28% of droplet)\n" +
        "    float bevelW = minSide * 0.28;\n" +
        "    float edgeT = clamp(1.0 - dIn / bevelW, 0.0, 1.0);\n" +
        "    float meniscus = pow(edgeT, 1.9);\n" +
        "\n" +
        "    // Light direction driven live by gyroscope tilt & scroll\n" +
        "    float2 lDir = normalize(float2(-0.68, -0.73) + uTilt * 0.65);\n" +
        "    float cosTL = max(0.0, dot(norm, lDir));\n" +
        "    float cosBR = max(0.0, dot(norm, -lDir));\n" +
        "\n" +
        "    // 1. Subtle internal total-reflection depth ring (gives 3D thick-glass refraction contrast!)\n" +
        "    float tirBand = exp(-pow((dIn - bevelW * 0.26) / (bevelW * 0.22), 2.0));\n" +
        "    float tirDark = tirBand * (1.0 - 0.62 * cosTL - 0.35 * cosBR) * 0.14 * uStrength;\n" +
        "\n" +
        "    // 2. Crystal-clear glass wash (ultra-clear center ~0.10, rising along curved viscous edge)\n" +
        "    float yn = pn.y;\n" +
        "    float clearAlpha = 0.10 + 0.055 * clamp((1.0 - yn) * 0.5, 0.0, 1.0) + 0.14 * meniscus * uStrength;\n" +
        "\n" +
        "    // 3. Wet glossy curved-dome specular reflection + opposite bottom-right caustic bounce!\n" +
        "    float domeSpec = pow(clamp(dot(pn, lDir) + 0.26, 0.0, 1.0), 3.0) * 0.26 * uStrength;\n" +
        "    float bounceGlow = pow(clamp(dot(pn, -lDir) + 0.16, 0.0, 1.0), 3.2) * meniscus * 0.24 * uStrength;\n" +
        "\n" +
        "    // 4. Subtle prismatic chromatic sheen along curved meniscus corners\n" +
        "    float prism = meniscus * (pow(cosTL, 1.7) + pow(cosBR, 1.9)) * 0.20 * uStrength;\n" +
        "    half3 lightCol = half3(0.97, 0.99, 1.0);\n" +
        "    lightCol.r += half(prism * (0.35 - 0.55 * edgeT));\n" +
        "    lightCol.g += half(prism * 0.12);\n" +
        "    lightCol.b += half(prism * (0.55 * edgeT));\n" +
        "    lightCol = clamp(lightCol, half3(0.0), half3(1.0));\n" +
        "\n" +
        "    // Premultiplied blend of light glass wash + subtle dark TIR trough\n" +
        "    float lightA = clamp(clearAlpha + domeSpec + bounceGlow, 0.0, 0.56);\n" +
        "    float outA = clamp(lightA + tirDark * (1.0 - lightA), 0.0, 0.62) * mask;\n" +
        "    half3 outPremul = lightCol * half(lightA * mask) + half3(0.04, 0.06, 0.10) * half(tirDark * (1.0 - lightA) * mask);\n" +
        "    return half4(outPremul, half(outA));\n" +
        "}\n";

    // Top wet specular glaze & razor-crisp anisotropic Fresnel caustic rim drawn OVER the glyph!
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
        "float2 squircleNormal(float2 p, float2 b, float n) {\n" +
        "    float2 q = clamp(abs(p) / max(b, float2(1.0)), float2(0.0001), float2(2.0));\n" +
        "    float2 g = sign(p) * pow(q, float2(n - 1.0));\n" +
        "    return normalize(g + float2(0.0001));\n" +
        "}\n" +
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
        "    float2 tlDir = normalize(float2(-0.707, -0.707) + uTilt * 0.65);\n" +
        "    float2 brDir = -tlDir;\n" +
        "    float cosTL = max(0.0, dot(norm, tlDir));\n" +
        "    float cosBR = max(0.0, dot(norm, brDir));\n" +
        "\n" +
        "    // Liquid Fresnel outer edge + thick glossy inner meniscus bloom + brilliant corner catchlights\n" +
        "    float rimBand = exp(-pow((dIn - 1.15) / 1.35, 2.0));\n" +
        "    float rimPower = 0.28 + 0.80 * pow(cosTL, 1.7) + 0.64 * pow(cosBR, 1.9);\n" +
        "    float innerCaustic = exp(-pow((dIn - 5.2) / 9.0, 2.0)) * (0.04 + 0.28 * pow(cosTL, 2.2) + 0.20 * pow(cosBR, 2.4));\n" +
        "    float sparkle = exp(-pow((dIn - 1.7) / 1.05, 2.0)) * (0.68 * pow(cosTL, 4.5) + 0.48 * pow(cosBR, 5.0));\n" +
        "\n" +
        "    // Wet glass upper meniscus crescent reflection (sells viscous liquid dome over the glyph!)\n" +
        "    float2 pn = p / max(halfSize, float2(1.0));\n" +
        "    float topCrescent = exp(-pow((pn.y + 0.74) / 0.22, 2.0)) * clamp(1.0 - pn.x * pn.x * 1.1, 0.0, 1.0) * 0.20;\n" +
        "\n" +
        "    float a = clamp((rimBand * rimPower + innerCaustic + sparkle + topCrescent) * uIntensity, 0.0, 0.96) * mask;\n" +
        "    return half4(half3(1.0, 0.996, 1.0) * a, a);\n" +
        "}\n";

    private static volatile BitmapShader sWallpaperShader;
    private static volatile boolean sIsLiveWallpaper = false;
    private static volatile float sWallScale = 0.5f;
    private static volatile long sLastWallAttemptMs = 0L;

    private static RuntimeShader sLensShader;
    private static Paint sLensPaint;
    private static boolean sLensFailed = false;

    private static RuntimeShader sClearShader;
    private static Paint sClearPaint;
    private static boolean sClearFailed = false;

    private static RuntimeShader sGlazeShader;
    private static Paint sGlazePaint;
    private static boolean sGlazeFailed = false;

    public static void invalidateWallpaperCache() {
        sWallpaperShader = null;
        sIsLiveWallpaper = false;
        sLastWallAttemptMs = 0L;
    }

    /**
     * Loads the current static wallpaper into a GPU BitmapShader if (and ONLY if) the user
     * is using a static wallpaper whose bitmap is genuinely non-black.
     * Automatically detects live animated wallpapers (WallpaperInfo != null) and switches cleanly
     * to Engine B (SemBlurInfo hardware blur + crystal-clear viscous liquid glass AGSL)!
     */
    public static BitmapShader ensureStaticWallpaperShader(Context ctx) {
        if (sWallpaperShader != null) return sWallpaperShader;
        long now = System.currentTimeMillis();
        if (now - sLastWallAttemptMs < 4000L) return null;
        sLastWallAttemptMs = now;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            // If a live animated wallpaper service is active, do NOT sample a black/stale static drawable!
            if (wm.getWallpaperInfo() != null) {
                sIsLiveWallpaper = true;
                return null;
            }
            sIsLiveWallpaper = false;

            Drawable wd = null;
            try { wd = wm.getDrawable(); } catch (Throwable ignored) {}
            if (wd == null) {
                try { wd = wm.peekDrawable(); } catch (Throwable ignored) {}
            }
            if (wd == null) return null;

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int sw = Math.max(dm.widthPixels, 720);
            int sh = Math.max(dm.heightPixels, 1280);
            float scale = 0.5f;
            int tw = Math.max(180, Math.round(sw * scale));
            int th = Math.max(320, Math.round(sh * scale));

            Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            int dw = wd.getIntrinsicWidth();
            int dh = wd.getIntrinsicHeight();
            if (dw > 0 && dh > 0) {
                float s = Math.max(tw / (float) dw, th / (float) dh);
                int rw = Math.round(dw * s);
                int rh = Math.round(dh * s);
                wd.setBounds((tw - rw) / 2, (th - rh) / 2, (tw + rw) / 2, (th + rh) / 2);
            } else {
                wd.setBounds(0, 0, tw, th);
            }
            wd.draw(c);

            // Sanity check: verify the bitmap isn't empty/solid-black!
            int nonDark = 0;
            for (int sy = 1; sy <= 5; sy++) {
                for (int sx = 1; sx <= 5; sx++) {
                    int px = bmp.getPixel(tw * sx / 6, th * sy / 6);
                    int a = Color.alpha(px);
                    int lum = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3;
                    if (a > 200 && lum > 10) nonDark++;
                }
            }
            if (nonDark < 3) {
                bmp.recycle();
                sIsLiveWallpaper = true;
                return null;
            }

            sWallScale = scale;
            sWallpaperShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            return sWallpaperShader;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Attaches Samsung One UI's native 120fps SurfaceFlinger hardware backdrop blur clipped
     * tightly to the icon's squircle rect/corners — works LIVE on animated wallpapers!
     */
    public static boolean applyIconSemBlur(View view, int left, int top, int right, int bottom, float cornerRadiusPx) {
        if (view == null || right - left < 32 || bottom - top < 32) return false;
        try {
            Class<?> builderCls = Class.forName("android.view.SemBlurInfo$Builder");
            Constructor<?> ctor = builderCls.getConstructor(int.class);
            Object builder = ctor.newInstance(0); // BLUR_MODE_WINDOW = real-time wallpaper backdrop blur!
            try { builderCls.getMethod("setRadius", int.class).invoke(builder, 42); } catch (Throwable ignored) {}
            try { builderCls.getMethod("setBackgroundCornerRadius", float.class).invoke(builder, cornerRadiusPx); } catch (Throwable ignored) {}
            try { builderCls.getMethod("setBackgroundClipRect", int.class, int.class, int.class, int.class).invoke(builder, left, top, right, bottom); } catch (Throwable ignored) {}
            // Crystal-clear bright wash (NO dark tint!)
            try { builderCls.getMethod("setBackgroundColor", int.class).invoke(builder, Color.argb(26, 255, 255, 255)); } catch (Throwable ignored) {}

            Object blurInfo = builderCls.getMethod("build").invoke(builder);
            Method semSetBlur = View.class.getMethod("semSetBlurInfo", Class.forName("android.view.SemBlurInfo"));
            semSetBlur.invoke(view, blurInfo);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void clearSemBlur(View view) {
        if (view == null) return;
        try {
            Method semSetBlur = View.class.getMethod("semSetBlurInfo", Class.forName("android.view.SemBlurInfo"));
            semSetBlur.invoke(view, new Object[] { null });
        } catch (Throwable ignored) {}
    }

    /**
     * Draws the LIVE Liquid Glass droplet body behind the pure frosted white glyph:
     *  - On static wallpaper: Engine A (true pixel-refraction + convex magnification + RGB dispersion)
     *  - On live animated wallpaper: Engine B (crystal-clear alpha-blended viscous liquid dome + SemBlurInfo)
     */
    public static void drawLiveLiquidBody(Context ctx, View hostView, Canvas canvas,
                                          float left, float top, float width, float height,
                                          float screenX, float screenY,
                                          float tiltX, float tiltY, float strength) {
        if (width < 16f || height < 16f || Build.VERSION.SDK_INT < 33) return;

        BitmapShader wall = ensureStaticWallpaperShader(ctx);
        if (wall != null && !sLensFailed) {
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
                return;
            } catch (Throwable ignored) {
                sLensFailed = true;
            }
        }

        // Engine B (Live Animated Wallpaper): draw crystal-clear viscous AGSL body strictly clipped to exact squircle!
        // (No SemBlurInfo on outer IconViewImpl cell, which caused a faint outer square cell blur halo.)
        if (hostView != null) {
            clearSemBlur(hostView);
        }

        if (!sClearFailed) {
            try {
                if (sClearShader == null) {
                    sClearShader = new RuntimeShader(LIQUID_CLEAR_BODY_AGSL);
                    sClearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                }
                sClearShader.setFloatUniform("uSize", width, height);
                sClearShader.setFloatUniform("uTilt", tiltX, tiltY);
                sClearShader.setFloatUniform("uStrength", strength);
                sClearPaint.setShader(sClearShader);

                canvas.save();
                canvas.translate(left, top);
                canvas.drawRect(-4f, -4f, width + 4f, height + 4f, sClearPaint);
                canvas.restore();
            } catch (Throwable ignored) {
                sClearFailed = true;
            }
        }
    }

    /**
     * Draws the live gyroscope-reactive wet specular glaze & Fresnel caustic rim OVER the glyph.
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

    public static Bitmap extractPureGlyphFromOneLiquid(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        float cx = (w - 1) * 0.5f;
        float cy = (h - 1) * 0.5f;
        float rx = w * 0.435f;
        float ry = h * 0.435f;

        for (int y = 0; y < h; y++) {
            float dy = Math.abs(y - cy) / ry;
            float dy4 = dy * dy * dy * dy;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int argb = px[row + x];
                int a = (argb >>> 24) & 0xFF;
                if (a <= 105) {
                    px[row + x] = 0;
                    continue;
                }
                float dx = Math.abs(x - cx) / rx;
                float sDist = (float) Math.pow(dx * dx * dx * dx + dy4, 0.25);
                if (sDist >= 1.0f) {
                    px[row + x] = 0;
                    continue;
                }
                float glyphA = (a < 155) ? ((a - 105f) / 50f * 155f) : a;
                if (sDist > 0.94f) glyphA *= (1.0f - sDist) / 0.06f;
                int outA = Math.max(0, Math.min(255, Math.round(glyphA)));
                px[row + x] = (outA << 24) | 0x00FFFFFF;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

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
}
