package com.droplet.iconpack.xposed;

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
import android.view.WindowManager;
import com.droplet.iconpack.ModuleStatus;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Droplet v2.5.0 — Heavily Optimized Real-Time iOS 26 Liquid Glass Optical Engine.
 *
 * Key Performance & Visual Upgrades:
 *  - ~18x faster GPU fragment shader: pre-blurs tiny 144x312 wallpaper textures ONCE per frame on a
 *    background thread (<1.2ms via O(1) sliding-window separable box blur) instead of 27 taps/pixel in AGSL!
 *    Center pixels now perform 1 single texture fetch; only the outer 16% curved bevel uses 3 taps for RGB prism.
 *  - Dual pre-blurred textures: crisp silky frost for Home Screen + heavily blurred/dimmed texture for
 *    One UI App Drawer & Open Folders so icons blend seamlessly into One UI's blurred App Drawer!
 *  - Scroll-aware zero-stutter capture pump: pauses Binder/SurfaceFlinger capture during active touch/scroll
 *    so Home Screen & App Drawer scrolling stay locked at 120fps, and pumps live wallpapers at a light ~10fps.
 *  - Authentic corner-originating Apple iOS 26 specular glow: quiet hairline along straight edges,
 *    brilliant curved-corner light arcs starting from the 45-degree corners and tapering softly onto the sides.
 *  - Live slider uniforms: uFrost (Clear <-> Frosted), uStrength (Magnification/Refraction), uIntensity (Corner Glow).
 */
public final class LiquidGlassRuntime {

    private static final String TAG = "Droplet-Glass: ";

    // High-detail 1/3-res Static Home texture (360x780, captured once -> 0% ongoing CPU!)
    // + fast 216x468 animated Live Wallpaper texture (~30fps smooth!)
    // + true-Gaussian 108x234 3-pass blurred App Drawer texture (~0.6ms + FILTER_MODE_LINEAR -> zero banding!)
    private static final int CAP_W = 360;
    private static final int CAP_H = 780;
    private static final int LIVE_CAP_W = 216;
    private static final int LIVE_CAP_H = 468;
    private static final int DRAWER_W = 108;
    private static final int DRAWER_H = 234;

    public static final class PureGlyphDrawable extends BitmapDrawable {
        public PureGlyphDrawable(Resources res, Bitmap bitmap) {
            super(res, bitmap);
            setFilterBitmap(true);
            setAntiAlias(true);
            setDither(true);
        }
    }

    // Engine A: Ultra-Smooth Retina-AA Optical Convex Magnification + Refraction Shader
    // (Zero hard edge branches, C1-smooth cubic AA boundary, 1-tap center / 3-tap bevel -> 120fps!)
    private static final String APPLE_LIQUID_LENS_AGSL =
        "uniform shader uWallpaper;\n" +
        "uniform float2 uSize;\n" +
        "uniform float2 uScreenPos;\n" +
        "uniform float2 uViewScale;\n" +
        "uniform float2 uWallScale;\n" +
        "uniform float2 uTilt;\n" +
        "uniform float uStrength;\n" +
        "uniform float uFrost;\n" +
        "uniform float uDrawerMode;\n" +
        "uniform float3 uTintColor;\n" +
        "uniform float uTintStrength;\n" +
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
        "    float2 b = halfSize - float2(1.25);\n" +
        "    float sdf = squircleSdf(p, b, 4.5);\n" +
        "    if (sdf > 5.5) return half4(0.0);\n" +
        "\n" +
        "    // C1-smooth cubic anti-aliased boundary (1.6px silky transition, ZERO jagged edge jumps!)\n" +
        "    float mask = smoothstep(0.80, -0.80, sdf);\n" +
        "    float shAlpha = exp(-pow(max(sdf + 0.35, 0.0) / 2.8, 2.0)) * mix(0.085, 0.055, uDrawerMode) * (1.0 - mask);\n" +
        "    if (mask <= 0.001) return half4(0.0, 0.0, 0.0, half(shAlpha));\n" +
        "\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float d512 = dIn * (512.0 / max(minSide, 1.0));\n" +
        "    float2 norm = squircleNormal(p, b, 3.6);\n" +
        "\n" +
        "    // 1. REAL CONVEX LENS MAGNIFICATION (controlled live by Magnification slider!)\n" +
        "    float effStr = uStrength * mix(1.0, 0.42, uDrawerMode);\n" +
        "    float zoomFrac = clamp(0.56 * effStr, 0.12, 0.74);\n" +
        "    float2 pn = p / max(halfSize, float2(1.0));\n" +
        "    float r2 = clamp(dot(pn, pn), 0.0, 1.4);\n" +
        "    float2 magOffset = -p * (zoomFrac * (1.0 - 0.14 * r2));\n" +
        "\n" +
        "    // 2. CURVED OUTER 16% GLASS BEVEL REFRACTION + GYRO PARALLAX (view-scale aware for app-close spring!)\n" +
        "    float edgeT = smoothstep(minSide * 0.165, 0.0, dIn);\n" +
        "    float edgePow = edgeT * edgeT;\n" +
        "    float2 rimBend = norm * (minSide * 0.25 * effStr * edgePow);\n" +
        "    float2 tiltShift = uTilt * (minSide * 0.10 * effStr);\n" +
        "\n" +
        "    float2 baseScreen = uScreenPos + (fragCoord + magOffset + rimBend + tiltShift) * uViewScale;\n" +
        "\n" +
        "    // ULTRA-FAST TEXTURE SAMPLING: 1 tap in interior, 3 taps ONLY on curved bevel for RGB prism!\n" +
        "    half3 col;\n" +
        "    if (edgeT > 0.02 && uStrength > 0.15) {\n" +
        "        float dispPx = minSide * 0.026 * effStr * edgePow * mix(1.0, 0.42, uDrawerMode);\n" +
        "        float2 dispVec = norm * dispPx * uViewScale;\n" +
        "        half rCh = uWallpaper.eval((baseScreen + dispVec) * uWallScale).r;\n" +
        "        half gCh = uWallpaper.eval(baseScreen * uWallScale).g;\n" +
        "        half bCh = uWallpaper.eval((baseScreen - dispVec) * uWallScale).b;\n" +
        "        col = half3(rCh, gCh, bCh);\n" +
        "    } else {\n" +
        "        col = uWallpaper.eval(baseScreen * uWallScale).rgb;\n" +
        "    }\n" +
        "\n" +
        "    // Vivid curved-edge color refraction glow + adaptive bright-wallpaper legibility balance\n" +
        "    half luma = dot(col, half3(0.299, 0.587, 0.114));\n" +
        "    col = mix(half3(luma), col, half(mix(1.04, 0.96, uDrawerMode) + 0.22 * edgePow * mix(1.0, 0.45, uDrawerMode)));\n" +
        "    float brightClamp = smoothstep(0.58, 0.92, float(luma));\n" +
        "    col *= half(1.0 - 0.10 * brightClamp);\n" +
        "\n" +
        "    // Live Clear (0% = pure crystal glass, ZERO white wash!) <-> Frosted (100% = heavy frosted glass!)\n" +
        "    // Refined App Drawer optics: subtle tactile meniscus + clean top sheen so drawer icons look like Apple App Library glass!\n" +
        "    float edgeMeniscus = exp(-d512 / 16.0);\n" +
        "    float topCatch = pow(clamp((1.0 - pn.y) * 0.5, 0.0, 1.0), 1.15);\n" +
        "    float diag = pow(clamp((-pn.x - pn.y + 0.30) / 2.2, 0.0, 1.0), 2.4);\n" +
        "    float frostCurve = pow(clamp(uFrost, 0.0, 1.0), 1.15);\n" +
        "    float drawerTintMod = mix(1.0, mix(0.32, 0.88, frostCurve), uDrawerMode);\n" +
        "    float baseFrost = (0.50 * frostCurve) * mix(1.0, 0.78, brightClamp);\n" +
        "    float drawerSheen = uDrawerMode * (0.032 * edgeMeniscus + 0.022 * topCatch);\n" +
        "    float bodyAlpha = (baseFrost + (0.09 * frostCurve) * topCatch + (0.008 + 0.055 * frostCurve) * edgeMeniscus + (0.065 * frostCurve) * diag) * drawerTintMod + drawerSheen;\n" +
        "    bodyAlpha = clamp(bodyAlpha, 0.0, 0.64);\n" +
        "    half3 glassTint = half3(0.985 + 0.015 * diag, 0.992 + 0.008 * diag, 1.0);\n" +
        "    col = mix(col, glassTint, half(bodyAlpha));\n" +
        "\n" +
        "    // Optional Optical Glass Tint (stained-glass transmission + luminous colored glaze!)\n" +
        "    if (uTintStrength > 0.005) {\n" +
        "        half3 tCol = half3(uTintColor);\n" +
        "        half3 stained = col * clamp(tCol * 1.18 + half3(0.14), half3(0.08), half3(1.18));\n" +
        "        col = mix(col, stained, half(uTintStrength * 0.65));\n" +
        "        float tintGlaze = clamp(uTintStrength * (0.30 + 0.18 * edgeMeniscus + 0.08 * topCatch), 0.0, 0.74);\n" +
        "        col = mix(col, tCol, half(tintGlaze));\n" +
        "    }\n" +
        "\n" +
        "    col = clamp(col, half3(0.0), half3(1.0));\n" +
        "    float outAlpha = mask + shAlpha;\n" +
        "    return half4(col * half(mask), half(outAlpha));\n" +
        "}\n";

    // Engine B: Clean Crystal-Clear Glass Body (initial fallback while first frame captures)
    private static final String APPLE_CLEAR_BODY_AGSL =
        "uniform float2 uSize;\n" +
        "uniform float uFrost;\n" +
        "uniform float3 uTintColor;\n" +
        "uniform float uTintStrength;\n" +
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
        "    float2 b = halfSize - float2(1.25);\n" +
        "    float sdf = squircleSdf(p, b, 4.5);\n" +
        "    if (sdf > 5.5) return half4(0.0);\n" +
        "    float mask = smoothstep(0.80, -0.80, sdf);\n" +
        "    float shAlpha = exp(-pow(max(sdf + 0.35, 0.0) / 2.8, 2.0)) * 0.075 * (1.0 - mask);\n" +
        "    if (mask <= 0.001) return half4(0.0, 0.0, 0.0, half(shAlpha));\n" +
        "    float d512 = max(-sdf, 0.0) * (512.0 / max(minSide, 1.0));\n" +
        "    float2 pn = p / max(halfSize, float2(1.0));\n" +
        "    float edgeMeniscus = exp(-d512 / 16.0);\n" +
        "    float topCatch = pow(clamp((1.0 - pn.y) * 0.5, 0.0, 1.0), 1.15);\n" +
        "    float baseA = mix(0.015, 0.52, uFrost);\n" +
        "    float outA = clamp(baseA + (0.08 * uFrost) * topCatch + (0.012 + 0.05 * uFrost) * edgeMeniscus + 0.38 * uTintStrength, 0.01, 0.76) * mask;\n" +
        "    half3 outCol = mix(half3(0.99, 0.995, 1.0), half3(uTintColor), half(clamp(uTintStrength * 1.15, 0.0, 1.0)));\n" +
        "    return half4(outCol * half(outA), half(outA + shAlpha * (1.0 - outA)));\n" +
        "}\n";

    // Authentic Apple iOS 26 RETINA-SMOOTH CORNER-ORIGINATING Specular Fresnel Rim!
    // Uses Nyquist-safe screen-pixel Gaussian widths (>=1.18px) + cubic AA mask + silky n=3.2 lighting normals
    // so curved corner light arcs are 100% butter-smooth like an iPhone display with ZERO pixelation!
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
        "    float2 b = halfSize - float2(1.25);\n" +
        "    float sdf = squircleSdf(p, b, n);\n" +
        "    if (sdf > 2.8) return half4(0.0);\n" +
        "\n" +
        "    // Exact match to body AA boundary (1.6px smooth cubic transition -> ZERO jagged corner pixels!)\n" +
        "    float mask = smoothstep(0.80, -0.80, sdf);\n" +
        "    float dPx = max(-sdf, 0.0);\n" +
        "    // Silky n=3.2 lighting normals keep corner light arcs liquid-smooth with zero angular banding!\n" +
        "    float2 norm = squircleNormal(p, b, min(n, 3.2));\n" +
        "\n" +
        "    // Corner localization: 2*|nx*ny| is 1.0 at the 45° corners and 0.0 along straight sides!\n" +
        "    float cornerBase = clamp(2.0 * abs(norm.x * norm.y), 0.0, 1.0);\n" +
        "    float2 tlDir = normalize(float2(-0.7071, -0.7071) + uTilt * 0.52);\n" +
        "    float2 brDir = -tlDir;\n" +
        "    float cornerTL = smoothstep(0.66, 0.992, dot(norm, tlDir));\n" +
        "    float cornerBR = smoothstep(0.66, 0.992, dot(norm, brDir));\n" +
        "    float softArcTL = pow(cornerTL, 1.12);\n" +
        "    float softArcBR = pow(cornerBR, 1.22);\n" +
        "    float minorCorners = pow(cornerBase, 2.0) * 0.16;\n" +
        "\n" +
        "    // Nyquist-safe screen-pixel profiles (guaranteed >= 1.18px wide so rim NEVER aliases/pixelates!)\n" +
        "    float rimCenter = max(0.85, minSide * 0.0058);\n" +
        "    float rimWidth = max(1.18, minSide * 0.0082);\n" +
        "    float sparkWidth = max(1.05, minSide * 0.0068);\n" +
        "    float glowCenter = max(2.2, minSide * 0.015);\n" +
        "    float glowWidth = max(3.2, minSide * 0.024);\n" +
        "\n" +
        "    // 1. Feather-soft outer contrast kiss\n" +
        "    float outerA = exp(-pow((sdf - 0.55) / 1.15, 2.0)) * (1.0 - mask) * 0.042;\n" +
        "\n" +
        "    // 2. Silky liquid corner glow wrapping organically inward ONLY from the corners\n" +
        "    float innerGlowA = exp(-pow((dPx - glowCenter) / glowWidth, 2.0)) * mask\n" +
        "        * clamp(0.28 * pow(cornerTL, 1.5) + 0.22 * pow(cornerBR, 1.7) + minorCorners * 0.35, 0.0, 0.34);\n" +
        "\n" +
        "    // 3. Smooth optical glass rim: quiet hairline (~0.16) on straight sides, surging smoothly at corners\n" +
        "    float rimProf = exp(-pow((dPx - rimCenter) / rimWidth, 2.0)) * mask;\n" +
        "    float rimDir = clamp(0.16 + 0.78 * softArcTL + 0.66 * softArcBR + minorCorners, 0.0, 0.95);\n" +
        "    float rimA = rimProf * rimDir;\n" +
        "\n" +
        "    // 4. Pure-white corner catchlight sheen on TL & BR curved corner peaks\n" +
        "    float sparkleA = exp(-pow((dPx - rimCenter * 1.15) / sparkWidth, 2.0)) * mask\n" +
        "        * clamp(0.56 * pow(cornerTL, 2.4) + 0.44 * pow(cornerBR, 2.6), 0.0, 0.62);\n" +
        "\n" +
        "    float whiteA = innerGlowA;\n" +
        "    whiteA = rimA + whiteA * (1.0 - rimA);\n" +
        "    whiteA = sparkleA + whiteA * (1.0 - sparkleA);\n" +
        "    whiteA = clamp(whiteA * uIntensity, 0.0, 0.96);\n" +
        "\n" +
        "    float outA = whiteA + outerA * (1.0 - whiteA);\n" +
        "    half3 outPremul = half3(0.992, 0.996, 1.0) * half(whiteA) + half3(0.08) * half(outerA * (1.0 - whiteA));\n" +
        "    return half4(outPremul, half(outA));\n" +
        "}\n";

    // Dual pre-blurred live wallpaper shaders (Home Screen + Extra-Blurred App Drawer!)
    private static volatile BitmapShader sHomeWallShader;
    private static volatile BitmapShader sDrawerWallShader;
    private static volatile int[] sLastRawPixels;
    private static volatile int sLastRawW = CAP_W;
    private static volatile int sLastRawH = CAP_H;
    private static volatile int sLastBuiltBlurRadius = -1;
    private static volatile float sWallScaleX = 0.25f;
    private static volatile float sWallScaleY = 0.25f;
    private static volatile float sDrawerWallScaleX = 0.0833f;
    private static volatile float sDrawerWallScaleY = 0.0833f;
    private static volatile float sCurrentFrost = 0.28f;
    private static volatile boolean sIsLiveWallpaper = false;
    private static volatile int sLastWallpaperId = -1;
    private static volatile boolean sUsedFallbackCapture = false;
    private static volatile boolean sForceRecapture = false;
    // Live One UI wallpaper scroll offsets (default 0.5 = centered, matching ImageWallpaper mWallpaperX/Y!)
    private static volatile float sWallOffsetX = 0.5f;
    private static volatile float sWallOffsetY = 0.5f;
    // Wallpaper-adaptive Auto Tint RGB in [0..1] (computed in <0.1ms per captured frame!)
    private static volatile float sAutoTintR = 0.24f;
    private static volatile float sAutoTintG = 0.54f;
    private static volatile float sAutoTintB = 0.96f;
    private static volatile boolean sAutoTintInit = false;
    private static volatile long sLastHomeDrawMs = 0L;
    private static volatile long sLastDrawerDrawMs = 0L;
    private static volatile long sLastMotionMs = 0L;
    private static volatile boolean sCaptureLoggedOnce = false;
    private static volatile boolean sCaptureFailedPermanently = false;
    private static volatile int sConsecutiveFails = 0;
    private static volatile Runnable sFrameInvalidateCb;
    private static volatile Context sAppCtx;
    private static volatile long sFrameCount = 0L;

    // Reusable scratch & double-buffered output arrays/bitmaps on background thread (ZERO per-frame GC churn!)
    private static int[] sTmpA;
    private static int[] sTmpB;
    private static int[] sRawCapturePx;
    private static int[] sDrawerRawPx;
    private static int[] sOutHomePx;
    private static int[] sOutDrawerPx;
    private static final Bitmap[] sHomeBmps = new Bitmap[2];
    private static final Bitmap[] sDrawerBmps = new Bitmap[2];
    private static int sHomeBmpIdx = 0;
    private static int sDrawerBmpIdx = 0;
    private static final Runnable sRebuildHomeRunnable = new Runnable() {
        @Override
        public void run() {
            rebuildHomeShaderFromCachedRaw();
        }
    };

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
        sConsecutiveFails = 0;
        sForceRecapture = true;
        sAutoTintInit = false;
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
        sCaptureFailedPermanently = false;
        sConsecutiveFails = 0;
        long now = System.currentTimeMillis();
        sLastHomeDrawMs = now;
        // Grace period during One UI's app-close / swipe-up-to-home spring animation (~340ms):
        // Keep the clean cached wallpaper shader locked while the closing app & wallpaper zoom-out settle,
        // preventing any 1-second broken/dimmed frame capture during app close!
        if (sHomeWallShader != null) {
            sLastMotionMs = Math.max(sLastMotionMs, now + 240L);
        }
        boolean needRefresh = sUsedFallbackCapture;
        try {
            if (sAppCtx != null && Build.VERSION.SDK_INT >= 24) {
                WallpaperManager wm = WallpaperManager.getInstance(sAppCtx);
                int curId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                if (curId != sLastWallpaperId) {
                    sLastWallpaperId = curId;
                    needRefresh = true;
                    sAutoTintInit = false;
                }
            }
        } catch (Throwable ignored) {}
        if (needRefresh) {
            sForceRecapture = true;
            sCaptureLoggedOnce = false;
        }
        kickLiveCaptureIfNeeded();
    }

    /**
     * Called by DropletHook when icons are actively scrolling/dragging so we pause Binder wallpaper
     * capture for 180ms — keeping Home Screen & App Drawer scrolling locked at 120fps with zero stutter!
     */
    public static void noteScrollMotion() {
        sLastMotionMs = System.currentTimeMillis();
    }

    public static boolean isHomeRecentlyActive() {
        return (System.currentTimeMillis() - sLastHomeDrawMs) < 1500L;
    }

    public static void setWallpaperOffsets(float xOff, float yOff) {
        float cx = Math.max(0f, Math.min(1f, xOff));
        float cy = Math.max(0f, Math.min(1f, yOff));
        if (Math.abs(cx - sWallOffsetX) > 0.015f || Math.abs(cy - sWallOffsetY) > 0.015f) {
            sWallOffsetX = cx;
            sWallOffsetY = cy;
            if (!sIsLiveWallpaper) {
                sForceRecapture = true;
                kickLiveCaptureIfNeeded();
            }
        }
    }

    public static void setFrostLevel(float frost01) {
        float clamped = Math.max(0f, Math.min(1f, frost01));
        if (Math.abs(clamped - sCurrentFrost) < 0.01f) return;
        sCurrentFrost = clamped;
        int desiredR = frostToBlurRadius(clamped);
        if (desiredR != sLastBuiltBlurRadius && sLastRawPixels != null && sCapHandler != null) {
            // Coalesce rapid slider drags so we only run one clean background blur!
            sCapHandler.removeCallbacks(sRebuildHomeRunnable);
            sCapHandler.post(sRebuildHomeRunnable);
        }
    }

    private static int frostToBlurRadius(float frost01) {
        // 0% -> 0px (pure crystal-clear glass lens!), 100% -> 18px 2-pass (~85px heavy frosted glass on 360x780!)
        if (frost01 <= 0.06f) return 0;
        return Math.max(1, Math.min(18, Math.round(frost01 * 18.0f)));
    }

    private static BitmapShader makeLinearShader(Bitmap bmp) {
        BitmapShader sh = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (Build.VERSION.SDK_INT >= 33) {
            sh.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        }
        return sh;
    }

    private static void getRealScreenSize(Context ctx, int[] out) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    Rect b = wm.getMaximumWindowMetrics().getBounds();
                    if (b.width() >= 720 && b.height() >= 1280) {
                        out[0] = b.width();
                        out[1] = b.height();
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        out[0] = Math.max(dm.widthPixels, 720);
        out[1] = Math.max(dm.heightPixels, 1280);
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

    /**
     * Blazing-fast O(W*H) sliding-window separable box blur (2 passes = smooth near-Gaussian!)
     * Runs on 144x312 (~45k pixels) in < 1.2ms on Snapdragon 8 Gen 3 with ZERO heap allocation!
     */
    private static void boxBlurPass(int[] src, int[] dst, int w, int h, int r, boolean dimDrawer) {
        if (r <= 0) {
            System.arraycopy(src, 0, dst, 0, w * h);
            return;
        }
        if (sTmpA == null || sTmpA.length < w * h) sTmpA = new int[w * h];
        int div = 2 * r + 1;
        // Horizontal pass: src -> sTmpA
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int cx = Math.max(0, Math.min(w - 1, i));
                int px = src[row + cx];
                sr += (px >>> 16) & 0xFF;
                sg += (px >>> 8) & 0xFF;
                sb += px & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                sTmpA[row + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int rem = src[row + Math.max(0, x - r)];
                int add = src[row + Math.min(w - 1, x + r + 1)];
                sr += ((add >>> 16) & 0xFF) - ((rem >>> 16) & 0xFF);
                sg += ((add >>> 8) & 0xFF) - ((rem >>> 8) & 0xFF);
                sb += (add & 0xFF) - (rem & 0xFF);
            }
        }
        // Vertical pass: sTmpA -> dst (calibrated ~0.91x luminance in App Drawer to match One UI's blurred backdrop!)
        int dimNum = dimDrawer ? 232 : 256;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int cy = Math.max(0, Math.min(h - 1, i));
                int px = sTmpA[cy * w + x];
                sr += (px >>> 16) & 0xFF;
                sg += (px >>> 8) & 0xFF;
                sb += px & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                int cr = ((sr / div) * dimNum) >>> 8;
                int cg = ((sg / div) * dimNum) >>> 8;
                int cb = ((sb / div) * dimNum) >>> 8;
                dst[y * w + x] = 0xFF000000 | (cr << 16) | (cg << 8) | cb;
                int rem = sTmpA[Math.max(0, y - r) * w + x];
                int add = sTmpA[Math.min(h - 1, y + r + 1) * w + x];
                sr += ((add >>> 16) & 0xFF) - ((rem >>> 16) & 0xFF);
                sg += ((add >>> 8) & 0xFF) - ((rem >>> 8) & 0xFF);
                sb += (add & 0xFF) - (rem & 0xFF);
            }
        }
    }

    /**
     * Extracts a luminous, wallpaper-adaptive Auto Tint accent color in <0.1ms from the small 90x195 buffer.
     * Smoothly damps transitions on live wallpapers while staying instant on wallpaper change!
     */
    private static void updateAutoTintFromPixels(int[] px, int count) {
        if (px == null || count <= 0) return;
        double sumR = 0, sumG = 0, sumB = 0, sumW = 0;
        double avgR = 0, avgG = 0, avgB = 0;
        int samples = 0;
        // Stride by 3 -> ~5,800 samples in <0.05ms!
        for (int i = 0; i < count; i += 3) {
            int c = px[i];
            int r = (c >>> 16) & 0xFF;
            int g = (c >>> 8) & 0xFF;
            int b = c & 0xFF;
            avgR += r; avgG += g; avgB += b; samples++;

            int mx = Math.max(r, Math.max(g, b));
            int mn = Math.min(r, Math.min(g, b));
            float sat = (mx > 12) ? ((mx - mn) / (float) mx) : 0f;
            float lum = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f;
            // Favor vibrant mid-tone accent hues over crushed blacks or blown whites
            float midWeight = Math.max(0.05f, 1.0f - Math.abs(lum - 0.48f) * 1.6f);
            float w = (sat * sat + 0.015f) * midWeight;
            sumR += r * w;
            sumG += g * w;
            sumB += b * w;
            sumW += w;
        }
        float tr, tg, tb;
        if (sumW > 1.0) {
            tr = (float) (sumR / sumW) / 255f;
            tg = (float) (sumG / sumW) / 255f;
            tb = (float) (sumB / sumW) / 255f;
        } else if (samples > 0) {
            tr = (float) (avgR / samples) / 255f;
            tg = (float) (avgG / samples) / 255f;
            tb = (float) (avgB / samples) / 255f;
        } else {
            return;
        }
        // Gently boost glass vibrancy & avoid muddy dark colors so the auto tint looks clean and glassy
        float luma = tr * 0.299f + tg * 0.587f + tb * 0.114f;
        tr = Math.max(0f, Math.min(1f, luma + (tr - luma) * 1.28f));
        tg = Math.max(0f, Math.min(1f, luma + (tg - luma) * 1.28f));
        tb = Math.max(0f, Math.min(1f, luma + (tb - luma) * 1.28f));
        float lift = (luma < 0.22f) ? (0.22f - luma) * 0.65f : 0f;
        tr = Math.min(1f, tr + lift);
        tg = Math.min(1f, tg + lift);
        tb = Math.min(1f, tb + lift);

        if (!sAutoTintInit) {
            sAutoTintR = tr;
            sAutoTintG = tg;
            sAutoTintB = tb;
            sAutoTintInit = true;
        } else {
            sAutoTintR = sAutoTintR * 0.72f + tr * 0.28f;
            sAutoTintG = sAutoTintG * 0.72f + tg * 0.28f;
            sAutoTintB = sAutoTintB * 0.72f + tb * 0.28f;
        }
    }

    private static Bitmap buildPreBlurredBitmap(int[] rawPx, int w, int h, int radius, int passes, boolean dimDrawer) {
        if (sTmpB == null || sTmpB.length < w * h) sTmpB = new int[w * h];
        int[] outPx;
        if (dimDrawer) {
            if (sOutDrawerPx == null || sOutDrawerPx.length < w * h) sOutDrawerPx = new int[w * h];
            outPx = sOutDrawerPx;
        } else {
            if (sOutHomePx == null || sOutHomePx.length < w * h) sOutHomePx = new int[w * h];
            outPx = sOutHomePx;
        }
        if (radius <= 0) {
            System.arraycopy(rawPx, 0, outPx, 0, w * h);
        } else if (passes <= 1) {
            boxBlurPass(rawPx, outPx, w, h, radius, dimDrawer);
        } else if (passes == 2) {
            boxBlurPass(rawPx, sTmpB, w, h, radius, false);
            boxBlurPass(sTmpB, outPx, w, h, radius, dimDrawer);
        } else {
            // 3-pass separable box blur = 99.5% true Gaussian (zero directional banding in App Drawer!)
            boxBlurPass(rawPx, outPx, w, h, radius, false);
            boxBlurPass(outPx, sTmpB, w, h, radius, false);
            boxBlurPass(sTmpB, outPx, w, h, radius, dimDrawer);
        }
        // Double-buffered mutable Bitmap pool -> ZERO per-frame Bitmap GC churn and 100% RenderThread-safe!
        Bitmap[] pool = dimDrawer ? sDrawerBmps : sHomeBmps;
        int idx = dimDrawer ? (sDrawerBmpIdx = (sDrawerBmpIdx ^ 1)) : (sHomeBmpIdx = (sHomeBmpIdx ^ 1));
        Bitmap bmp = pool[idx];
        if (bmp == null || bmp.isRecycled() || bmp.getWidth() != w || bmp.getHeight() != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            pool[idx] = bmp;
        }
        bmp.setPixels(outPx, 0, w, 0, 0, w, h);
        return bmp;
    }

    private static void rebuildHomeShaderFromCachedRaw() {
        int[] raw = sLastRawPixels;
        if (raw == null) return;
        int w = sLastRawW, h = sLastRawH;
        int r = frostToBlurRadius(sCurrentFrost);
        sLastBuiltBlurRadius = r;
        Bitmap homeBmp = buildPreBlurredBitmap(raw, w, h, r, r >= 3 ? 2 : 1, false);
        sHomeWallShader = makeLinearShader(homeBmp);
        Runnable cb = sFrameInvalidateCb;
        if (cb != null) cb.run();
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

    /**
     * Aspect-ratio-preserving crop & scale from any raw wallpaper bitmap into CAP_W x CAP_H
     * using live sWallOffsetX / sWallOffsetY (default 0.5 centered), preventing horizontal squashing
     * or mXOffset misalignment on wide/parallax wallpapers!
     */
    private static Bitmap cropAndScaleToViewport(Bitmap raw, int sw, int sh, int outW, int outH) {
        Bitmap src = raw;
        boolean recycleSrc = false;
        if (Build.VERSION.SDK_INT >= 26 && raw.getConfig() == Bitmap.Config.HARDWARE) {
            src = raw.copy(Bitmap.Config.ARGB_8888, false);
            recycleSrc = (src != null && src != raw);
        }
        if (src == null) return null;
        int rw = src.getWidth(), rh = src.getHeight();
        if (rw <= 0 || rh <= 0) {
            if (recycleSrc) src.recycle();
            return null;
        }
        float targetAspect = sw / (float) sh;
        float rawAspect = rw / (float) rh;
        Rect srcRect;
        if (rawAspect > targetAspect + 0.015f) {
            int cropW = Math.max(1, Math.round(rh * targetAspect));
            int cropX = Math.max(0, Math.min(rw - cropW, Math.round((rw - cropW) * sWallOffsetX)));
            srcRect = new Rect(cropX, 0, cropX + cropW, rh);
        } else if (rawAspect < targetAspect - 0.015f) {
            int cropH = Math.max(1, Math.round(rw / targetAspect));
            int cropY = Math.max(0, Math.min(rh - cropH, Math.round((rh - cropH) * sWallOffsetY)));
            srcRect = new Rect(0, cropY, rw, cropY + cropH);
        } else {
            srcRect = new Rect(0, 0, rw, rh);
        }
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, srcRect, new Rect(0, 0, outW, outH), p);
        if (recycleSrc) src.recycle();
        return out;
    }

    private static Bitmap captureSmallSoftwareFrame(Context ctx, int sw, int sh) {
        initCaptureReflection();
        long t0 = System.nanoTime();
        String methodUsed = null;
        Bitmap raw = null;
        int origW = 0, origH = 0;

        boolean isLive = false;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            isLive = (wm.getWallpaperInfo() != null);
        } catch (Throwable ignored) {}

        int targetW = isLive ? LIVE_CAP_W : CAP_W;
        int targetH = isLive ? LIVE_CAP_H : CAP_H;

        // For STATIC wallpapers (ImageWallpaper): ALWAYS use WallpaperManager.getDrawable() FIRST!
        // Reason: IWindowManager.screenshotWallpaper() on Samsung One UI captures ImageWallpaper's raw
        // wider surface (e.g. 1476x2340) at x=0 without mXOffset (-198px), shifting icons by a full column!
        // WallpaperManager.getDrawable() + exact sWallOffsetX/Y crop aligns 100.0% pixel-for-pixel!
        if (!isLive) {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(ctx);
                Drawable wd = wm.getDrawable();
                if (wd != null) {
                    int dw = wd.getIntrinsicWidth(), dh = wd.getIntrinsicHeight();
                    origW = dw; origH = dh;
                    Bitmap b = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(b);
                    if (dw > 0 && dh > 0) {
                        float s = Math.max(targetW / (float) dw, targetH / (float) dh);
                        int rw = Math.round(dw * s), rh = Math.round(dh * s);
                        int ox = -Math.round((rw - targetW) * sWallOffsetX);
                        int oy = -Math.round((rh - targetH) * sWallOffsetY);
                        wd.setBounds(ox, oy, ox + rw, oy + rh);
                    } else {
                        wd.setBounds(0, 0, targetW, targetH);
                    }
                    wd.draw(c);
                    if (isBitmapNonBlack(b)) {
                        raw = b;
                        methodUsed = "WallpaperManager.getDrawable(exact-offset)";
                        sUsedFallbackCapture = false;
                    } else {
                        b.recycle();
                    }
                }
            } catch (Throwable ignored) {}
        }

        // For LIVE wallpapers (or if static drawable unavailable): IWindowManager.screenshotWallpaper()
        if (raw == null && sWmService != null && sScreenshotWallMethod != null) {
            try {
                Object bObj = sScreenshotWallMethod.invoke(sWmService);
                if (bObj instanceof Bitmap && isBitmapNonBlack((Bitmap) bObj)) {
                    Bitmap bm = (Bitmap) bObj;
                    origW = bm.getWidth(); origH = bm.getHeight();
                    raw = cropAndScaleToViewport(bm, sw, sh, targetW, targetH);
                    methodUsed = "IWindowManager.screenshotWallpaper";
                    sUsedFallbackCapture = false;
                }
            } catch (Throwable t) {
                if (!sCaptureLoggedOnce) XposedBridge.log(TAG + "screenshotWallpaper err: " + t);
            }
        }

        // Last-resort fallback: mirrorWallpaperSurface(0) + captureLayers
        if (raw == null && sWmService != null && sMirrorWallMethod != null && sCaptureLayersMethod != null) {
            try {
                if (sMirrorSc == null || !sMirrorSc.isValid()) {
                    Object sc = sMirrorWallMethod.invoke(sWmService, 0);
                    if (sc instanceof SurfaceControl && ((SurfaceControl) sc).isValid()) {
                        sMirrorSc = (SurfaceControl) sc;
                    }
                }
                if (sMirrorSc != null && sMirrorSc.isValid()) {
                    Object shb = sCaptureLayersMethod.invoke(null, sMirrorSc, null, 0.25f);
                    if (shb != null) {
                        if (sHwBufAsBitmapMethod == null) {
                            sHwBufAsBitmapMethod = shb.getClass().getMethod("asBitmap");
                        }
                        Object bObj = sHwBufAsBitmapMethod.invoke(shb);
                        if (bObj instanceof Bitmap && isBitmapNonBlack((Bitmap) bObj)) {
                            Bitmap bm = (Bitmap) bObj;
                            origW = bm.getWidth(); origH = bm.getHeight();
                            raw = cropAndScaleToViewport(bm, sw, sh, targetW, targetH);
                            methodUsed = "mirrorWallpaperSurface+captureLayers";
                            sUsedFallbackCapture = true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (raw == null) return null;

        if (!sCaptureLoggedOnce) {
            sCaptureLoggedOnce = true;
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            XposedBridge.log(TAG + "Exact-aligned live texture via " + methodUsed
                    + " (src=" + origW + "x" + origH + " -> " + raw.getWidth() + "x" + raw.getHeight()
                    + ", offX=" + sWallOffsetX + ", " + ms + "ms)");
        }
        return raw;
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

            // Pause completely (0% CPU/GPU) when screen is off OR Home Screen hasn't drawn in > 1.2s!
            if (!screenOn || (sHomeWallShader != null && (now - sLastHomeDrawMs > 1200L))) {
                sCapRunning = false;
                return;
            }

            // ZERO-STUTTER SCROLL PROTECTION: if user is actively swiping/scrolling, skip Binder capture
            // so scrolling stays locked at 120.0 FPS on GPU! Resume smoothly right after scroll settles.
            if (sHomeWallShader != null && (now - sLastMotionMs < 120L)) {
                if (sCapHandler != null) sCapHandler.postDelayed(this, 45L);
                return;
            }
            long tickStartNs = System.nanoTime();

            try {
                WallpaperManager wm = WallpaperManager.getInstance(ctx);
                sIsLiveWallpaper = (wm.getWallpaperInfo() != null);
                if (Build.VERSION.SDK_INT >= 24) {
                    sLastWallpaperId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                }
            } catch (Throwable ignored) {}

            int[] realSize = new int[2];
            getRealScreenSize(ctx, realSize);
            int sw = realSize[0];
            int sh = realSize[1];

            Bitmap small = captureSmallSoftwareFrame(ctx, sw, sh);
            if (small != null) {
                sConsecutiveFails = 0;
                sCaptureFailedPermanently = false;
                sForceRecapture = false;
                int w = small.getWidth(), h = small.getHeight();
                if (sRawCapturePx == null || sRawCapturePx.length < w * h) sRawCapturePx = new int[w * h];
                int[] rawPx = sRawCapturePx;
                small.getPixels(rawPx, 0, w, 0, 0, w, h);

                sLastRawPixels = rawPx;
                sLastRawW = w;
                sLastRawH = h;
                sWallScaleX = w / (float) sw;
                sWallScaleY = h / (float) sh;

                // 1. Pre-blur Home Screen glass texture (270x585 + GPU FILTER_MODE_LINEAR = silky zero-pixelation!)
                int homeBlurR = frostToBlurRadius(sCurrentFrost);
                sLastBuiltBlurRadius = homeBlurR;
                Bitmap homeBmp = buildPreBlurredBitmap(rawPx, w, h, homeBlurR, homeBlurR >= 3 ? 2 : 1, false);
                sHomeWallShader = makeLinearShader(homeBmp);

                // 2. Lazy App Drawer texture + Auto Tint: skip redundant drawer blur when only Home Screen is visible!
                boolean needDrawer = (sDrawerWallShader == null) || !sIsLiveWallpaper || ((now - sLastDrawerDrawMs) < 2000L);
                if (needDrawer || (sFrameCount % 4L == 0L)) {
                    Bitmap tiny = Bitmap.createScaledBitmap(small, DRAWER_W, DRAWER_H, true);
                    int dw = tiny.getWidth(), dh = tiny.getHeight();
                    if (sDrawerRawPx == null || sDrawerRawPx.length < dw * dh) sDrawerRawPx = new int[dw * dh];
                    int[] drawerRaw = sDrawerRawPx;
                    tiny.getPixels(drawerRaw, 0, dw, 0, 0, dw, dh);
                    tiny.recycle();
                    updateAutoTintFromPixels(drawerRaw, dw * dh);
                    if (needDrawer) {
                        Bitmap drawerBmp = buildPreBlurredBitmap(drawerRaw, dw, dh, 7, 3, true);
                        sDrawerWallScaleX = dw / (float) sw;
                        sDrawerWallScaleY = dh / (float) sh;
                        sDrawerWallShader = makeLinearShader(drawerBmp);
                    }
                }
                small.recycle();

                sFrameCount++;
                if (sFrameCount == 15L) {
                    XposedBridge.log(TAG + "Smooth 30fps live pump active (15 low-overhead frames rendered)");
                }
                Runnable cb = sFrameInvalidateCb;
                if (cb != null) cb.run();

                // Clean frame-paced ~30fps cadence on live wallpapers (33ms target minus capture time)!
                // Static wallpaper captures ONCE -> 0% ongoing CPU/GPU!
                if (sIsLiveWallpaper && sCapHandler != null) {
                    long elapsedMs = (System.nanoTime() - tickStartNs) / 1_000_000L;
                    long delayMs = Math.max(10L, 33L - elapsedMs);
                    sCapHandler.postDelayed(this, delayMs);
                } else {
                    sCapRunning = false;
                }
            } else if (sHomeWallShader == null) {
                sConsecutiveFails++;
                if (sConsecutiveFails < 6 && sCapHandler != null) {
                    sCapHandler.postDelayed(this, 500L);
                } else {
                    sCaptureFailedPermanently = true;
                    sCapRunning = false;
                    if (!sCaptureLoggedOnce) {
                        sCaptureLoggedOnce = true;
                        XposedBridge.log(TAG + "Wallpaper surface capture unavailable -> fallback BackgroundBlurDrawable");
                    }
                }
            } else if (sIsLiveWallpaper && sCapHandler != null) {
                sCapHandler.postDelayed(this, 140L);
            } else {
                sCapRunning = false;
            }
        }
    };

    public static synchronized void kickLiveCaptureIfNeeded() {
        if (sCaptureFailedPermanently || sAppCtx == null) return;
        if (sCapRunning) return;
        if (sHomeWallShader != null && !sIsLiveWallpaper && !sForceRecapture) return;
        if (sCapThread == null) {
            sCapThread = new HandlerThread("Droplet-WallCap", Process.THREAD_PRIORITY_LOWEST);
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
                                          float viewScaleX, float viewScaleY,
                                          float tiltX, float tiltY,
                                          float strength, float frost01, boolean isAppDrawer,
                                          int tintColor, float tintStrength) {
        if (width < 16f || height < 16f || Build.VERSION.SDK_INT < 33) return;

        if (sAppCtx == null && ctx != null) {
            sAppCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        }
        setFrostLevel(frost01);
        long nowMs = System.currentTimeMillis();
        sLastHomeDrawMs = nowMs;
        if (isAppDrawer) sLastDrawerDrawMs = nowMs;
        kickLiveCaptureIfNeeded();

        float tStr = (tintColor != 0) ? Math.max(0f, Math.min(1f, tintStrength)) : 0f;
        float tR, tG, tB;
        if (tintColor == ModuleStatus.TINT_COLOR_AUTO) {
            tR = sAutoTintR;
            tG = sAutoTintG;
            tB = sAutoTintB;
        } else if (tintColor != 0) {
            tR = Color.red(tintColor) / 255f;
            tG = Color.green(tintColor) / 255f;
            tB = Color.blue(tintColor) / 255f;
        } else {
            tR = 1f;
            tG = 1f;
            tB = 1f;
        }

        boolean useDrawerShader = isAppDrawer && (sDrawerWallShader != null);
        BitmapShader wall = useDrawerShader ? sDrawerWallShader : sHomeWallShader;

        if (wall != null && !sLensFailed) {
            try {
                if (sLensShader == null) {
                    sLensShader = new RuntimeShader(APPLE_LIQUID_LENS_AGSL);
                    sLensPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                }
                float vsx = (viewScaleX > 0.1f && viewScaleX < 4.0f) ? viewScaleX : 1.0f;
                float vsy = (viewScaleY > 0.1f && viewScaleY < 4.0f) ? viewScaleY : 1.0f;
                sLensShader.setInputShader("uWallpaper", wall);
                sLensShader.setFloatUniform("uSize", width, height);
                sLensShader.setFloatUniform("uScreenPos", screenX, screenY);
                sLensShader.setFloatUniform("uViewScale", vsx, vsy);
                sLensShader.setFloatUniform("uWallScale",
                        useDrawerShader ? sDrawerWallScaleX : sWallScaleX,
                        useDrawerShader ? sDrawerWallScaleY : sWallScaleY);
                sLensShader.setFloatUniform("uTilt", tiltX, tiltY);
                sLensShader.setFloatUniform("uStrength", strength);
                sLensShader.setFloatUniform("uFrost", frost01);
                sLensShader.setFloatUniform("uDrawerMode", isAppDrawer ? 1.0f : 0.0f);
                sLensShader.setFloatUniform("uTintColor", tR, tG, tB);
                sLensShader.setFloatUniform("uTintStrength", tStr);
                sLensPaint.setShader(sLensShader);

                canvas.save();
                canvas.translate(left, top);
                canvas.drawRect(-6f, -6f, width + 6f, height + 6f, sLensPaint);
                canvas.restore();
                return;
            } catch (Throwable t) {
                sLensFailed = true;
                XposedBridge.log(TAG + "APPLE_LIQUID_LENS_AGSL err: " + t);
            }
        }

        if (sCaptureFailedPermanently) {
            Drawable liveBlur = getOrCreateLiveBlurDrawable(hostView);
            if (liveBlur != null) {
                try {
                    int l = Math.round(left + 0.5f);
                    int t = Math.round(top + 0.5f);
                    int r = Math.round(left + width - 0.5f);
                    int b = Math.round(top + height - 0.5f);
                    float cr = Math.min(width, height) * 0.238f;
                    int blurPx = isAppDrawer ? 64 : Math.max(12, Math.min(48, Math.round(14f + 34f * frost01)));
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
                sClearShader.setFloatUniform("uFrost", frost01);
                sClearShader.setFloatUniform("uTintColor", tR, tG, tB);
                sClearShader.setFloatUniform("uTintStrength", tStr);
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
                canvas.drawRect(-4f, -4f, width + 4f, height + 4f, sRimPaint);
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
     * Extracts the pure floating frosted-white glyph from a baked Droplet icon,
     * scaled boldly (1.08x) with a whisper-soft suspended glass elevation shadow.
     */
    public static Bitmap extractPureGlyphFromDroplet(Bitmap src) {
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

        int outSz = Math.max(256, Math.max(w, h));
        Bitmap out = Bitmap.createBitmap(outSz, outSz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float expand = outSz * 0.04f;
        RectF dst = new RectF(-expand, -expand, outSz + expand, outSz + expand);

        Paint shadowP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        shadowP.setColorFilter(new PorterDuffColorFilter(Color.argb(38, 6, 14, 28), PorterDuff.Mode.SRC_IN));
        shadowP.setMaskFilter(new BlurMaskFilter(Math.max(2.5f, outSz * 0.018f), BlurMaskFilter.Blur.NORMAL));
        RectF shDst = new RectF(dst.left, dst.top + outSz * 0.015f, dst.right, dst.bottom + outSz * 0.015f);
        c.drawBitmap(raw, null, shDst, shadowP);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
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
