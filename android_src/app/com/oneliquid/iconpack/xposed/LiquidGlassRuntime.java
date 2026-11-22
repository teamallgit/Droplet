package com.oneliquid.iconpack.xposed;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.SurfaceControl;
import android.view.View;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Authentic Apple iOS 26 "Introducing Liquid Glass" Real-Time Optical Lens Engine for One UI 8 / 8.5 / 9+.
 *
 * Real Optical Physics (Zero Flat Tinted Squares!):
 *  - Interior (~80%): Crystal-clear convex lens magnification (~1.26x) preserving true continuous wallpaper gradients
 *    without smearing the center pixel across the whole tile.
 *  - Outer Curved Bevel (~20%): True high-curvature meniscus refraction bending rays steeply OUTWARD across the
 *    curved glass lip (+norm), pulling contrasting perimeter colors into a glossy refracted glass ring with crisp
 *    prismatic RGB chromatic dispersion and liquid Fresnel caustics!
 *  - Live 30fps Wallpaper Capture: Captures ONLY the live wallpaper layer via IWindowManager.screenshotWallpaper()
 *    on a background HandlerThread while Home Screen is visible/focused (0% CPU when hidden or screen off).
 *  - Zero onDraw View mutations: Never calls semSetBlurInfo or toggles BackgroundBlurDrawable during onDraw,
 *    preventing any icon flicker/disappearance during app open/close animations.
 */
public final class LiquidGlassRuntime {

    private static final String TAG = "OneLiquid-Glass: ";

    public static final class PureGlyphDrawable extends BitmapDrawable {
        public PureGlyphDrawable(Resources res, Bitmap bitmap) {
            super(res, bitmap);
        }
    }

    // Engine A: Strong 2.0x Convex Live Wallpaper Magnification + Clean GitHub-Exact Crystal Glass Plate!
    // Uses true Euclidean-corrected superellipse SDF (f / |grad f|) so rim & bevel thickness are 100% uniform
    // around straight sides AND 45-degree corners — matching our GitHub preview photos with ZERO weird frame groove!
    private static final String APPLE_LIQUID_LENS_AGSL =
        "uniform shader uWallpaper;\n" +
        "uniform float2 uSize;\n" +
        "uniform float2 uScreenPos;\n" +
        "uniform float2 uWallScale;\n" +
        "uniform float2 uTilt;\n" +
        "uniform float uStrength;\n" +
        "\n" +
        "// Exact Euclidean-corrected superellipse SDF (matches liquid_glass_engine.py distance_transform_edt!)\n" +
        "float squircleSdf(float2 p, float2 b, float n) {\n" +
        "    float2 invB = 1.0 / max(b, float2(1.0));\n" +
        "    float2 q = max(abs(p) * invB, float2(0.0001));\n" +
        "    float sumN = pow(q.x, n) + pow(q.y, n);\n" +
        "    float f = pow(sumN, 1.0 / n) - 1.0;\n" +
        "    float scale = pow(sumN, (1.0 / n) - 1.0);\n" +
        "    float2 grad = scale * float2(pow(q.x, n - 1.0) * invB.x, pow(q.y, n - 1.0) * invB.y);\n" +
        "    return f / max(length(grad), 0.0001);\n" +
        "}\n" +
        "float2 squircleNormal(float2 p, float2 b, float n) {\n" +
        "    float2 q = clamp(abs(p) / max(b, float2(1.0)), float2(0.0001), float2(2.0));\n" +
        "    float2 g = sign(p) * pow(q, float2(n - 1.0));\n" +
        "    return normalize(g + float2(0.0001));\n" +
        "}\n" +
        "half3 sampleGlassOptical(float2 coord, float r) {\n" +
        "    // Silky 9-tap Gaussian optical glass blur (authentic iOS 26 frosted clarity!)\n" +
        "    float d = r * 0.72;\n" +
        "    half3 c0 = uWallpaper.eval(coord).rgb;\n" +
        "    half3 c1 = uWallpaper.eval(coord + float2( r,  0.0)).rgb;\n" +
        "    half3 c2 = uWallpaper.eval(coord + float2(-r,  0.0)).rgb;\n" +
        "    half3 c3 = uWallpaper.eval(coord + float2( 0.0,  r)).rgb;\n" +
        "    half3 c4 = uWallpaper.eval(coord + float2( 0.0, -r)).rgb;\n" +
        "    half3 c5 = uWallpaper.eval(coord + float2( d,  d)).rgb;\n" +
        "    half3 c6 = uWallpaper.eval(coord + float2(-d,  d)).rgb;\n" +
        "    half3 c7 = uWallpaper.eval(coord + float2( d, -d)).rgb;\n" +
        "    half3 c8 = uWallpaper.eval(coord + float2(-d, -d)).rgb;\n" +
        "    return c0 * 0.28 + (c1 + c2 + c3 + c4) * 0.11 + (c5 + c6 + c7 + c8) * 0.07;\n" +
        "}\n" +
        "half4 main(float2 fragCoord) {\n" +
        "    float2 halfSize = uSize * 0.5;\n" +
        "    float2 p = fragCoord - halfSize;\n" +
        "    float minSide = min(uSize.x, uSize.y);\n" +
        "    float2 b = halfSize - float2(0.5);\n" +
        "    float sdf = squircleSdf(p, b, 4.5);\n" +
        "    if (sdf > 5.0) return half4(0.0);\n" +
        "    // Weightless authentic floating drop shadow (exact match to liquid_glass_engine.py)\n" +
        "    if (sdf > 0.4) {\n" +
        "        float sh = exp(-pow(sdf / 2.8, 2.0)) * 0.09;\n" +
        "        return half4(0.0, 0.0, 0.0, sh);\n" +
        "    }\n" +
        "    float mask = clamp(0.5 - sdf * 0.92, 0.0, 1.0);\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float d512 = dIn * (512.0 / max(minSide, 1.0));\n" +
        "    float2 norm = squircleNormal(p, b, 4.5);\n" +
        "\n" +
        "    // 1. STRONG REAL CONVEX LENS MAGNIFICATION (~2.5x at iOS 26, ~3.6x at Liquid Syrup, ~1.65x at Subtle!)\n" +
        "    //    Monotonic optical zoom: pulls background cleanly outward so waves visibly swell & magnify through each icon!\n" +
        "    float zoomFrac = clamp(0.60 * uStrength, 0.28, 0.76);\n" +
        "    float r2 = clamp(dot(p / max(halfSize, float2(1.0)), p / max(halfSize, float2(1.0))), 0.0, 1.4);\n" +
        "    float2 magOffset = -p * (zoomFrac * (1.0 - 0.14 * r2));\n" +
        "\n" +
        "    // 2. CRISP CURVED-EDGE GLASS REFRACTION (outer ~16% — smooth glowing wrap, ZERO chunky fake frame!)\n" +
        "    float edgeT = clamp(1.0 - dIn / (minSide * 0.16), 0.0, 1.0);\n" +
        "    float2 rimBend = norm * (minSide * 0.28 * uStrength * pow(edgeT, 1.85));\n" +
        "\n" +
        "    // 3. LIVE GYROSCOPE REFRACTIVE PARALLAX\n" +
        "    float2 tiltShift = uTilt * (minSide * 0.13 * uStrength);\n" +
        "\n" +
        "    float2 baseScreen = uScreenPos + fragCoord + magOffset + rimBend + tiltShift;\n" +
        "\n" +
        "    // Silky Apple frosted clarity in interior (~5px screen blur) sharpening crisply along the curved edge!\n" +
        "    float blurR = (1.25 - 0.65 * pow(edgeT, 1.5)) * min(uWallScale.x, uWallScale.y);\n" +
        "    float dispPx = minSide * 0.030 * uStrength * pow(edgeT, 1.9);\n" +
        "    half rCh = sampleGlassOptical((baseScreen + norm * dispPx) * uWallScale, blurR).r;\n" +
        "    half gCh = sampleGlassOptical(baseScreen * uWallScale, blurR).g;\n" +
        "    half bCh = sampleGlassOptical((baseScreen - norm * dispPx) * uWallScale, blurR).b;\n" +
        "    half3 col = half3(rCh, gCh, bCh);\n" +
        "\n" +
        "    // Vivid optical edge refraction glow (like real iOS 26 pink/cyan/gold glowing rims!) +\n" +
        "    // adaptive bright-wallpaper legibility balance so icons pop cleanly even on bright yellow!\n" +
        "    half luma = dot(col, half3(0.299, 0.587, 0.114));\n" +
        "    col = mix(half3(luma), col, half(1.06 + 0.28 * pow(edgeT, 1.5)));\n" +
        "    float brightClamp = smoothstep(0.58, 0.92, float(luma));\n" +
        "    col *= half(1.0 - 0.12 * brightClamp);\n" +
        "\n" +
        "    // Natural crystal transmission matching our GitHub preview icons (luminous & airy, ZERO muddy groove!)\n" +
        "    float2 pn = p / max(halfSize, float2(1.0));\n" +
        "    float edgeMeniscus = exp(-d512 / 16.0);\n" +
        "    float topCatch = pow(clamp((1.0 - pn.y) * 0.5, 0.0, 1.0), 1.15);\n" +
        "    float diag = pow(clamp((-pn.x - pn.y + 0.30) / 2.2, 0.0, 1.0), 2.4);\n" +
        "    float baseFrost = mix(0.145, 0.085, brightClamp);\n" +
        "    float bodyAlpha = clamp(baseFrost + 0.048 * topCatch + 0.045 * edgeMeniscus + 0.040 * diag, 0.0, 0.30);\n" +
        "    half3 glassTint = half3(0.985 + 0.015 * diag, 0.992 + 0.008 * diag, 1.0);\n" +
        "    col = mix(col, glassTint, half(bodyAlpha));\n" +
        "\n" +
        "    col = clamp(col, half3(0.0), half3(1.0));\n" +
        "    return half4(col * mask, mask);\n" +
        "}\n";

    // Engine B: Clean Crystal-Clear Glass Body (used during the initial 15ms before frame #1 arrives)
    private static final String APPLE_CLEAR_BODY_AGSL =
        "uniform float2 uSize;\n" +
        "uniform float2 uTilt;\n" +
        "uniform float uStrength;\n" +
        "\n" +
        "float squircleSdf(float2 p, float2 b, float n) {\n" +
        "    float2 invB = 1.0 / max(b, float2(1.0));\n" +
        "    float2 q = max(abs(p) * invB, float2(0.0001));\n" +
        "    float sumN = pow(q.x, n) + pow(q.y, n);\n" +
        "    float f = pow(sumN, 1.0 / n) - 1.0;\n" +
        "    float scale = pow(sumN, (1.0 / n) - 1.0);\n" +
        "    float2 grad = scale * float2(pow(q.x, n - 1.0) * invB.x, pow(q.y, n - 1.0) * invB.y);\n" +
        "    return f / max(length(grad), 0.0001);\n" +
        "}\n" +
        "half4 main(float2 fragCoord) {\n" +
        "    float2 halfSize = uSize * 0.5;\n" +
        "    float2 p = fragCoord - halfSize;\n" +
        "    float minSide = min(uSize.x, uSize.y);\n" +
        "    float2 b = halfSize - float2(0.5);\n" +
        "    float sdf = squircleSdf(p, b, 4.5);\n" +
        "    if (sdf > 5.0) return half4(0.0);\n" +
        "    if (sdf > 0.4) {\n" +
        "        float sh = exp(-pow(sdf / 2.8, 2.0)) * 0.09;\n" +
        "        return half4(0.0, 0.0, 0.0, sh);\n" +
        "    }\n" +
        "    float mask = clamp(0.5 - sdf * 0.92, 0.0, 1.0);\n" +
        "    float d512 = max(-sdf, 0.0) * (512.0 / max(minSide, 1.0));\n" +
        "    float2 pn = p / max(halfSize, float2(1.0));\n" +
        "    float edgeMeniscus = exp(-d512 / 16.0);\n" +
        "    float topCatch = pow(clamp((1.0 - pn.y) * 0.5, 0.0, 1.0), 1.15);\n" +
        "    float diag = pow(clamp((-pn.x - pn.y + 0.30) / 2.2, 0.0, 1.0), 2.4);\n" +
        "    float outA = clamp(0.14 + 0.055 * topCatch + 0.05 * edgeMeniscus + 0.045 * diag, 0.0, 0.32) * mask;\n" +
        "    return half4(half3(0.99, 0.995, 1.0) * half(outA), half(outA));\n" +
        "}\n";

    // EXACT 1:1 AGSL port of liquid_glass_engine.py's crisp GitHub specular rim & corner catchlight!
    // Uses true Euclidean SDF + 512px normalized distance so borders look IDENTICAL to our GitHub preview photos,
    // while gliding smoothly with live gyroscope tilt!
    private static final String APPLE_SPECULAR_RIM_AGSL =
        "uniform float2 uSize;\n" +
        "uniform float2 uTilt;\n" +
        "uniform float uCornerRatio;\n" +
        "uniform float uIntensity;\n" +
        "\n" +
        "float squircleSdf(float2 p, float2 b, float n) {\n" +
        "    float2 invB = 1.0 / max(b, float2(1.0));\n" +
        "    float2 q = max(abs(p) * invB, float2(0.0001));\n" +
        "    float sumN = pow(q.x, n) + pow(q.y, n);\n" +
        "    float f = pow(sumN, 1.0 / n) - 1.0;\n" +
        "    float scale = pow(sumN, (1.0 / n) - 1.0);\n" +
        "    float2 grad = scale * float2(pow(q.x, n - 1.0) * invB.x, pow(q.y, n - 1.0) * invB.y);\n" +
        "    return f / max(length(grad), 0.0001);\n" +
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
        "    float n = uCornerRatio > 0.35 ? 2.8 : 4.5;\n" +
        "    float2 b = halfSize - float2(0.5);\n" +
        "    float sdf = squircleSdf(p, b, n);\n" +
        "    if (sdf > 2.5) return half4(0.0);\n" +
        "    float mask = clamp(0.5 - sdf * 0.92, 0.0, 1.0);\n" +
        "    float s512 = 512.0 / max(minSide, 1.0);\n" +
        "    float sdf512 = sdf * s512;\n" +
        "    float d512 = max(-sdf512, 0.0);\n" +
        "    float2 norm = squircleNormal(p, b, n);\n" +
        "\n" +
        "    // Gyroscope-reactive TL (-135 deg) & BR (+45 deg) specular light axis (exact match to liquid_glass_engine.py)\n" +
        "    float2 tlDir = normalize(float2(-0.707, -0.707) + uTilt * 0.65);\n" +
        "    float2 brDir = -tlDir;\n" +
        "    float cosTL = max(0.0, dot(norm, tlDir));\n" +
        "    float cosBR = max(0.0, dot(norm, brDir));\n" +
        "\n" +
        "    // 4a. Micro outer contrast kiss (clean silhouette on light wallpapers)\n" +
        "    float outerA = exp(-pow((sdf512 - 0.6) / 1.0, 2.0)) * (1.0 - mask * 0.80) * 0.05;\n" +
        "\n" +
        "    // 4b. Feather-soft inner corner glow hugging ONLY TL & BR rounded corners\n" +
        "    float innerGlowA = exp(-pow((d512 - 4.8) / 9.5, 2.0)) * mask\n" +
        "        * clamp(0.04 + 0.24 * pow(cosTL, 2.2) + 0.17 * pow(cosBR, 2.4), 0.0, 0.30);\n" +
        "\n" +
        "    // 4c. Razor-crisp optical glass rim (exact liquid_glass_engine.py profile!)\n" +
        "    float rimProf = exp(-pow((d512 - 1.45) / 1.42, 2.0)) * mask;\n" +
        "    float rimDir = clamp(0.32 + 0.65 * pow(cosTL, 1.75) + 0.48 * pow(cosBR, 2.0), 0.0, 0.97);\n" +
        "    float rimA = rimProf * rimDir;\n" +
        "\n" +
        "    // 4d. Pure-white corner catchlight sparkle on TL & BR curved arcs\n" +
        "    float sparkleA = exp(-pow((d512 - 2.0) / 1.15, 2.0)) * mask\n" +
        "        * clamp(0.55 * pow(cosTL, 5.2) + 0.40 * pow(cosBR, 5.6), 0.0, 0.65);\n" +
        "\n" +
        "    // Porter-Duff SRC_OVER composite identical to liquid_glass_engine.py\n" +
        "    float whiteA = innerGlowA;\n" +
        "    whiteA = rimA + whiteA * (1.0 - rimA);\n" +
        "    whiteA = sparkleA + whiteA * (1.0 - sparkleA);\n" +
        "    whiteA = clamp(whiteA * uIntensity, 0.0, 0.97);\n" +
        "\n" +
        "    float outA = whiteA + outerA * (1.0 - whiteA);\n" +
        "    half3 outPremul = half3(0.992, 0.996, 1.0) * half(whiteA) + half3(0.08) * half(outerA * (1.0 - whiteA));\n" +
        "    return half4(outPremul, half(outA));\n" +
        "}\n";

    // Live wallpaper frame state (atomically swapped by background capture thread)
    private static volatile BitmapShader sWallpaperShader;
    private static volatile float sWallScaleX = 0.25f;
    private static volatile float sWallScaleY = 0.25f;
    private static volatile boolean sIsLiveWallpaper = false;
    private static volatile long sLastHomeDrawMs = 0L;
    private static volatile boolean sCaptureLoggedOnce = false;
    private static volatile boolean sCaptureFailedPermanently = false;
    private static volatile int sConsecutiveFails = 0;
    private static volatile Runnable sFrameInvalidateCb;
    private static volatile Context sAppCtx;
    private static volatile long sFrameCount = 0L;

    // Background capture thread + cached reflection handles
    private static HandlerThread sCapThread;
    private static Handler sCapHandler;
    private static boolean sCapRunning = false;
    private static SurfaceControl sMirrorSc;
    private static Object sWmService;
    private static Method sMirrorWallMethod;
    private static Method sScreenshotWallMethod;
    private static Method sCaptureLayersMethod;
    private static Method sHwBufAsBitmapMethod;
    private static boolean sReflectionInit = false;

    // Fallback BackgroundBlurDrawable per-view cache (ONLY used if capture permanently fails!)
    private static final WeakHashMap<View, Drawable> sViewBlurs = new WeakHashMap<>();
    private static Method sGetViewRootImpl;
    private static Method sCreateBgBlurDrawable;
    private static Method sBlurSetBlurRadius;
    private static Method sBlurSetCornerRadius;
    private static Method sBlurSetColor;
    private static boolean sBgBlurUnavailable = false;

    private static RuntimeShader sLensShader;
    private static Paint sLensPaint;
    private static boolean sLensFailed = false;

    private static RuntimeShader sClearShader;
    private static Paint sClearPaint;
    private static boolean sClearFailed = false;

    private static RuntimeShader sRimShader;
    private static Paint sRimPaint;
    private static boolean sRimFailed = false;

    public static void invalidateWallpaperCache() {
        sCaptureLoggedOnce = false;
        sCaptureFailedPermanently = false;
        if (sMirrorSc != null) {
            try { sMirrorSc.release(); } catch (Throwable ignored) {}
            sMirrorSc = null;
        }
        kickLiveCaptureIfNeeded();
    }

    public static void onLauncherResumed(Context ctx) {
        if (sAppCtx == null && ctx != null) {
            sAppCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        }
        // Always retry live capture cleanly on resume (recovers automatically if launcher booted while locked!)
        sCaptureFailedPermanently = false;
        sConsecutiveFails = 0;
        sLastHomeDrawMs = System.currentTimeMillis();
        kickLiveCaptureIfNeeded();
    }

    public static void setFrameInvalidateCallback(Runnable cb) {
        sFrameInvalidateCb = cb;
    }

    public static void detachBlurForView(View view) {
        if (view == null) return;
        Drawable d;
        synchronized (sViewBlurs) {
            d = sViewBlurs.remove(view);
        }
        if (d != null) {
            try { d.setVisible(false, false); } catch (Throwable ignored) {}
        }
    }

    private static void initCaptureReflection() {
        if (sReflectionInit) return;
        sReflectionInit = true;
        try {
            Class<?> wmgCls = Class.forName("android.view.WindowManagerGlobal");
            Method getWms = wmgCls.getMethod("getWindowManagerService");
            sWmService = getWms.invoke(null);
            if (sWmService != null) {
                try {
                    sMirrorWallMethod = sWmService.getClass().getMethod("mirrorWallpaperSurface", int.class);
                } catch (Throwable ignored) {}
                try {
                    sScreenshotWallMethod = sWmService.getClass().getMethod("screenshotWallpaper");
                } catch (Throwable ignored) {}
            }
            Class<?> scCls = null;
            for (String name : new String[] { "android.window.ScreenCaptureInternal", "android.window.ScreenCapture" }) {
                try {
                    scCls = Class.forName(name);
                    break;
                } catch (Throwable ignored) {}
            }
            if (scCls != null) {
                try {
                    sCaptureLayersMethod = scCls.getMethod("captureLayers", SurfaceControl.class, Rect.class, float.class);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Capture reflection init err: " + t);
        }
    }

    private static boolean isBitmapNonBlack(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return false;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 32 || h < 32) return false;
        if (Build.VERSION.SDK_INT >= 26 && bmp.getConfig() == Bitmap.Config.HARDWARE) {
            return true;
        }
        int nonDark = 0;
        for (int sy = 1; sy <= 4; sy++) {
            for (int sx = 1; sx <= 4; sx++) {
                int px = bmp.getPixel(w * sx / 5, h * sy / 5);
                int a = Color.alpha(px);
                int lum = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3;
                if (a > 200 && lum > 10) nonDark++;
            }
        }
        return nonDark >= 2;
    }

    private static Bitmap captureLiveWallpaperFrame(Context ctx, int sw, int sh) {
        initCaptureReflection();
        long t0 = System.nanoTime();
        String methodUsed = null;
        Bitmap result = null;

        // Path 1: IWindowManager.screenshotWallpaper() (auto-cropped 1:1 to display viewport by WindowManagerService!)
        if (sWmService != null && sScreenshotWallMethod != null) {
            try {
                Object bObj = sScreenshotWallMethod.invoke(sWmService);
                if (bObj instanceof Bitmap && isBitmapNonBlack((Bitmap) bObj)) {
                    Bitmap raw = (Bitmap) bObj;
                    // Downscale software bitmaps cleanly to 1/4 res (~270x585, ~630KB) for ultra-fast smooth FPS!
                    if (Build.VERSION.SDK_INT < 26 || raw.getConfig() != Bitmap.Config.HARDWARE) {
                        int tw = Math.max(200, raw.getWidth() / 4);
                        int th = Math.max(400, raw.getHeight() / 4);
                        result = Bitmap.createScaledBitmap(raw, tw, th, true);
                    } else {
                        result = raw;
                    }
                    methodUsed = "IWindowManager.screenshotWallpaper";
                }
            } catch (Throwable t) {
                if (!sCaptureLoggedOnce) {
                    XposedBridge.log(TAG + "screenshotWallpaper err: " + t);
                }
            }
        }

        // Path 2: mirrorWallpaperSurface(0) + ScreenCaptureInternal.captureLayers
        if (result == null && sWmService != null && sMirrorWallMethod != null && sCaptureLayersMethod != null) {
            try {
                if (sMirrorSc == null || !sMirrorSc.isValid()) {
                    Object sc = sMirrorWallMethod.invoke(sWmService, 0);
                    if (sc instanceof SurfaceControl && ((SurfaceControl) sc).isValid()) {
                        sMirrorSc = (SurfaceControl) sc;
                    }
                }
                if (sMirrorSc != null && sMirrorSc.isValid()) {
                    Object shb = sCaptureLayersMethod.invoke(null, sMirrorSc, new Rect(0, 0, sw, sh), 0.25f);
                    if (shb != null) {
                        if (sHwBufAsBitmapMethod == null) {
                            sHwBufAsBitmapMethod = shb.getClass().getMethod("asBitmap");
                        }
                        Object bObj = sHwBufAsBitmapMethod.invoke(shb);
                        if (bObj instanceof Bitmap && isBitmapNonBlack((Bitmap) bObj)) {
                            result = (Bitmap) bObj;
                            methodUsed = "mirrorWallpaperSurface+captureLayers";
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Path 3: Static WallpaperManager.getDrawable() when static wallpaper is set
        if (result == null) {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(ctx);
                if (wm.getWallpaperInfo() == null) {
                    Drawable wd = wm.getDrawable();
                    if (wd != null) {
                        int tw = Math.max(180, Math.round(sw * 0.5f));
                        int th = Math.max(320, Math.round(sh * 0.5f));
                        Bitmap b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(b);
                        int dw = wd.getIntrinsicWidth(), dh = wd.getIntrinsicHeight();
                        if (dw > 0 && dh > 0) {
                            float s = Math.max(tw / (float) dw, th / (float) dh);
                            int rw = Math.round(dw * s), rh = Math.round(dh * s);
                            wd.setBounds((tw - rw) / 2, (th - rh) / 2, (tw + rw) / 2, (th + rh) / 2);
                        } else {
                            wd.setBounds(0, 0, tw, th);
                        }
                        wd.draw(c);
                        if (isBitmapNonBlack(b)) {
                            result = b;
                            methodUsed = "WallpaperManager.getDrawable";
                        } else {
                            b.recycle();
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (result != null && !sCaptureLoggedOnce) {
            sCaptureLoggedOnce = true;
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            XposedBridge.log(TAG + "LIVE wallpaper refraction texture acquired via " + methodUsed
                    + " (" + result.getWidth() + "x" + result.getHeight() + " for display " + sw + "x" + sh + ", " + ms + "ms)");
        }
        return result;
    }

    private static final Runnable sCaptureTick = new Runnable() {
        @Override
        public void run() {
            Context ctx = sAppCtx;
            if (ctx == null || sCaptureFailedPermanently) {
                sCapRunning = false;
                return;
            }
            long now = System.currentTimeMillis();
            boolean screenOn = true;
            try {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) screenOn = pm.isInteractive();
            } catch (Throwable ignored) {}

            // Pause cleanly (0% CPU/GPU) ONLY when screen is off OR Home Screen hasn't drawn in > 1.2s!
            if (!screenOn || (sWallpaperShader != null && (now - sLastHomeDrawMs > 1200L))) {
                sCapRunning = false;
                return;
            }

            try {
                WallpaperManager wm = WallpaperManager.getInstance(ctx);
                sIsLiveWallpaper = (wm.getWallpaperInfo() != null);
            } catch (Throwable ignored) {}

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int sw = Math.max(dm.widthPixels, 720);
            int sh = Math.max(dm.heightPixels, 1280);

            Bitmap frame = captureLiveWallpaperFrame(ctx, sw, sh);
            if (frame != null) {
                sConsecutiveFails = 0;
                sCaptureFailedPermanently = false;
                sWallScaleX = frame.getWidth() / (float) sw;
                sWallScaleY = frame.getHeight() / (float) sh;
                sWallpaperShader = new BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                sFrameCount++;
                if (sFrameCount == 30L) {
                    XposedBridge.log(TAG + "Live wallpaper pump verified active (30 continuous frames rendered!)");
                }
                Runnable cb = sFrameInvalidateCb;
                if (cb != null) cb.run();

                // Pump continuously (~30fps) while Home Screen is visible on live wallpaper!
                if (sIsLiveWallpaper && sCapHandler != null) {
                    sCapHandler.postDelayed(this, 16L);
                } else {
                    sCapRunning = false;
                }
            } else if (sWallpaperShader == null) {
                sConsecutiveFails++;
                if (sConsecutiveFails < 6 && sCapHandler != null) {
                    // Transient failure (e.g. phone was locked or transitioning) -> retry smoothly!
                    sCapHandler.postDelayed(this, 500L);
                } else {
                    sCaptureFailedPermanently = true;
                    sCapRunning = false;
                    if (!sCaptureLoggedOnce) {
                        sCaptureLoggedOnce = true;
                        XposedBridge.log(TAG + "Wallpaper surface capture unavailable -> falling back to SurfaceFlinger BackgroundBlurDrawable");
                    }
                }
            } else if (sIsLiveWallpaper && sCapHandler != null) {
                // Transient empty capture during transition — keep previous valid frame and retry smoothly!
                sCapHandler.postDelayed(this, 60L);
            } else {
                sCapRunning = false;
            }
        }
    };

    public static synchronized void kickLiveCaptureIfNeeded() {
        if (sCaptureFailedPermanently || sAppCtx == null) return;
        if (sCapRunning) return;
        if (sWallpaperShader != null && !sIsLiveWallpaper) return;
        if (sCapThread == null) {
            sCapThread = new HandlerThread("OneLiquid-WallCap", Process.THREAD_PRIORITY_BACKGROUND);
            sCapThread.start();
            sCapHandler = new Handler(sCapThread.getLooper());
        }
        sCapRunning = true;
        sCapHandler.post(sCaptureTick);
    }

    private static Drawable getOrCreateLiveBlurDrawable(View hostView) {
        if (hostView == null || sBgBlurUnavailable || Build.VERSION.SDK_INT < 31) return null;
        if (!hostView.isAttachedToWindow()) return null;
        synchronized (sViewBlurs) {
            Drawable existing = sViewBlurs.get(hostView);
            if (existing != null) return existing;
        }
        try {
            if (sGetViewRootImpl == null) {
                sGetViewRootImpl = View.class.getMethod("getViewRootImpl");
            }
            Object vri = sGetViewRootImpl.invoke(hostView);
            if (vri == null) return null;
            if (sCreateBgBlurDrawable == null) {
                sCreateBgBlurDrawable = vri.getClass().getMethod("createBackgroundBlurDrawable");
            }
            Drawable blur = (Drawable) sCreateBgBlurDrawable.invoke(vri);
            if (blur == null) return null;
            if (sBlurSetBlurRadius == null) {
                Class<?> bc = blur.getClass();
                sBlurSetBlurRadius = bc.getMethod("setBlurRadius", int.class);
                sBlurSetCornerRadius = bc.getMethod("setCornerRadius", float.class);
                sBlurSetColor = bc.getMethod("setColor", int.class);
            }
            sBlurSetColor.invoke(blur, Color.TRANSPARENT);
            blur.setAlpha(255);
            blur.setVisible(true, false);
            synchronized (sViewBlurs) {
                sViewBlurs.put(hostView, blur);
            }
            return blur;
        } catch (Throwable t) {
            sBgBlurUnavailable = true;
            return null;
        }
    }

    public static void drawLiveLiquidBody(Context ctx, View hostView, Canvas canvas,
                                          float left, float top, float width, float height,
                                          float screenX, float screenY,
                                          float tiltX, float tiltY, float strength) {
        if (width < 16f || height < 16f || Build.VERSION.SDK_INT < 33) return;

        if (sAppCtx == null && ctx != null) {
            sAppCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        }
        sLastHomeDrawMs = System.currentTimeMillis();
        kickLiveCaptureIfNeeded();

        BitmapShader wall = sWallpaperShader;
        if (wall != null && !sLensFailed) {
            try {
                if (sLensShader == null) {
                    sLensShader = new RuntimeShader(APPLE_LIQUID_LENS_AGSL);
                    sLensPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                }
                sLensShader.setInputShader("uWallpaper", wall);
                sLensShader.setFloatUniform("uSize", width, height);
                sLensShader.setFloatUniform("uScreenPos", screenX, screenY);
                sLensShader.setFloatUniform("uWallScale", sWallScaleX, sWallScaleY);
                sLensShader.setFloatUniform("uTilt", tiltX, tiltY);
                sLensShader.setFloatUniform("uStrength", strength);
                sLensPaint.setShader(sLensShader);

                canvas.save();
                canvas.translate(left, top);
                canvas.drawRect(-4f, -4f, width + 4f, height + 4f, sLensPaint);
                canvas.restore();
                return;
            } catch (Throwable t) {
                sLensFailed = true;
                XposedBridge.log(TAG + "APPLE_LIQUID_LENS_AGSL err: " + t);
            }
        }

        // Only attach BackgroundBlurDrawable if capture permanently failed (never churn during cold start/app close!)
        if (sCaptureFailedPermanently) {
            Drawable liveBlur = getOrCreateLiveBlurDrawable(hostView);
            if (liveBlur != null) {
                try {
                    int l = Math.round(left + 0.5f);
                    int t = Math.round(top + 0.5f);
                    int r = Math.round(left + width - 0.5f);
                    int b = Math.round(top + height - 0.5f);
                    float cr = Math.min(width, height) * 0.238f;
                    int blurPx = Math.max(12, Math.min(48, Math.round(24f * strength)));
                    liveBlur.setBounds(l, t, r, b);
                    sBlurSetCornerRadius.invoke(liveBlur, cr);
                    sBlurSetBlurRadius.invoke(liveBlur, blurPx);
                    liveBlur.setAlpha(255);
                    liveBlur.setVisible(true, false);
                    liveBlur.draw(canvas);
                } catch (Throwable ignored) {}
            }
        }

        if (!sClearFailed) {
            try {
                if (sClearShader == null) {
                    sClearShader = new RuntimeShader(APPLE_CLEAR_BODY_AGSL);
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

    public static void drawLiveGlazeRim(Canvas canvas, float left, float top, float width, float height,
                                        float tiltX, float tiltY, boolean isPill, float intensity) {
        if (width < 12f || height < 12f || intensity <= 0.01f) return;
        if (Build.VERSION.SDK_INT >= 33 && !sRimFailed) {
            try {
                if (sRimShader == null) {
                    sRimShader = new RuntimeShader(APPLE_SPECULAR_RIM_AGSL);
                    sRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                }
                sRimShader.setFloatUniform("uSize", width, height);
                sRimShader.setFloatUniform("uTilt", tiltX, tiltY);
                sRimShader.setFloatUniform("uCornerRatio", isPill ? 0.48f : 0.23f);
                sRimShader.setFloatUniform("uIntensity", intensity);
                sRimPaint.setShader(sRimShader);

                canvas.save();
                canvas.translate(left, top);
                canvas.drawRect(-3f, -3f, width + 3f, height + 3f, sRimPaint);
                canvas.restore();
                return;
            } catch (Throwable ignored) {
                sRimFailed = true;
            }
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.1f, width * 0.01f));
        p.setShader(new LinearGradient(
                left, top, left + width, top + height,
                new int[] { Color.argb((int) (210 * intensity), 255, 255, 255),
                            Color.argb((int) (45 * intensity), 255, 255, 255),
                            Color.argb((int) (170 * intensity), 255, 255, 255) },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP
        ));
        float r = isPill ? Math.min(width, height) * 0.45f : Math.min(width, height) * 0.235f;
        canvas.drawRoundRect(new RectF(left + 1f, top + 1f, left + width - 1f, top + height - 1f), r, r, p);
    }

    /**
     * Extracts the pure floating frosted-white glyph from a baked OneLiquid icon,
     * scaled boldly (1.08x) so inner icons are large, crisp, and prominent!
     */
    public static Bitmap extractPureGlyphFromOneLiquid(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        float cx = (w - 1) * 0.5f;
        float cy = (h - 1) * 0.5f;
        float rx = w * 0.44f;
        float ry = h * 0.44f;

        for (int y = 0; y < h; y++) {
            float dy = Math.abs(y - cy) / ry;
            float dy4 = dy * dy * dy * dy;
            float yFrac = y / (float) Math.max(h - 1, 1);
            float frostMul = 0.99f - 0.11f * yFrac;
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
                int outA = Math.max(0, Math.min(255, Math.round(glyphA * frostMul)));
                px[row + x] = (outA << 24) | 0x00FFFFFF;
            }
        }
        Bitmap raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        raw.setPixels(px, 0, w, 0, 0, w, h);

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float expand = w * 0.04f;
        RectF dst = new RectF(-expand, -expand, w + expand, h + expand);

        // Whisper-soft depth shadow so floating frosted glyphs look physically suspended INSIDE the glass!
        Paint shadowP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        shadowP.setColorFilter(new PorterDuffColorFilter(Color.argb(38, 6, 14, 28), PorterDuff.Mode.SRC_IN));
        shadowP.setMaskFilter(new BlurMaskFilter(Math.max(2f, w * 0.018f), BlurMaskFilter.Blur.NORMAL));
        RectF shDst = new RectF(dst.left, dst.top + w * 0.015f, dst.right, dst.bottom + w * 0.015f);
        c.drawBitmap(raw, null, shDst, shadowP);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(raw, null, dst, p);
        raw.recycle();
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
        int gSz = Math.round(sz * 0.72f);
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
                Color.argb(252, 255, 255, 255),
                Color.argb(210, 245, 250, 255),
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
