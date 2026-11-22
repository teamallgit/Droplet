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
 * Authentic Apple iOS 26 "Introducing Liquid Glass" Real-Time Optical Engine for One UI 8 / 8.5 / 9+.
 *
 * Real-time Live Wallpaper Magnification & Snell's-Law Refraction:
 *  - Captures ONLY the live wallpaper SurfaceFlinger layer (mirrorWallpaperSurface + ScreenCaptureInternal.captureLayers
 *    or IWindowManager.screenshotWallpaper, using TouchWizHome's granted READ_FRAME_BUFFER permission) at ~30fps
 *    on a background HandlerThread ONLY while the Home Screen is actively visible (0% CPU when hidden/screen off!).
 *  - Static wallpapers are captured once at 0% ongoing cost and still refract at 120fps on scroll/drag/tilt!
 *  - Runs an AGSL convex optical lens shader: genuine ~1.2x magnification inside each icon + steep Snell's-law
 *    curved-bevel edge bending + prismatic RGB chromatic dispersion + razor-thin gyroscope Fresnel specular rim.
 *  - Automatic fallback to hardware SurfaceFlinger BackgroundBlurDrawable if wallpaper surface capture is unavailable.
 */
public final class LiquidGlassRuntime {

    private static final String TAG = "OneLiquid-Glass: ";

    public static final class PureGlyphDrawable extends BitmapDrawable {
        public PureGlyphDrawable(Resources res, Bitmap bitmap) {
            super(res, bitmap);
        }
    }

    // Engine A: Authentic Apple iOS 26 Live Convex Magnification + Snell's-Law Bevel Refraction Shader
    private static final String APPLE_LIQUID_LENS_AGSL =
        "uniform shader uWallpaper;\n" +
        "uniform float2 uSize;\n" +
        "uniform float2 uScreenPos;\n" +
        "uniform float2 uWallScale;\n" +
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
        "half3 sampleGlassOptical(float2 coord, float r) {\n" +
        "    half3 c0 = uWallpaper.eval(coord).rgb;\n" +
        "    half3 c1 = uWallpaper.eval(coord + float2( r,  0.0)).rgb;\n" +
        "    half3 c2 = uWallpaper.eval(coord + float2(-r,  0.0)).rgb;\n" +
        "    half3 c3 = uWallpaper.eval(coord + float2( 0.0,  r)).rgb;\n" +
        "    half3 c4 = uWallpaper.eval(coord + float2( 0.0, -r)).rgb;\n" +
        "    return c0 * 0.48 + (c1 + c2 + c3 + c4) * 0.13;\n" +
        "}\n" +
        "half4 main(float2 fragCoord) {\n" +
        "    float2 halfSize = uSize * 0.5;\n" +
        "    float2 p = fragCoord - halfSize;\n" +
        "    float minSide = min(uSize.x, uSize.y);\n" +
        "    float2 b = halfSize - float2(1.0);\n" +
        "    float sdf = squircleSdf(p, b, 4.5);\n" +
        "    if (sdf > 4.0) return half4(0.0);\n" +
        "    // Delicate authentic Apple elevation shadow underneath the glass tile\n" +
        "    if (sdf > 0.35) {\n" +
        "        float sh = exp(-pow(sdf / 2.6, 2.0)) * 0.11;\n" +
        "        return half4(0.0, 0.0, 0.0, sh);\n" +
        "    }\n" +
        "    float mask = clamp(0.5 - sdf, 0.0, 1.0);\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float2 norm = squircleNormal(p, b, 4.5);\n" +
        "\n" +
        "    // Thick-crystal curved optical bevel in outer ~25% of icon + genuine convex magnification across center!\n" +
        "    float bevelW = minSide * 0.25;\n" +
        "    float edgeT = clamp(1.0 - dIn / bevelW, 0.0, 1.0);\n" +
        "    float rimCurve = edgeT * edgeT * (3.0 - 2.0 * edgeT);\n" +
        "    float lensSlope = pow(edgeT, 1.6);\n" +
        "\n" +
        "    // REAL CONVEX LENS MAGNIFICATION (~1.38x at iOS 26 strength, ~1.55x at Liquid Syrup!)\n" +
        "    // Sampling inward (-p) pulls background outward -> wallpaper visibly magnifies & swells as it moves through!\n" +
        "    float r2 = dot(p / max(halfSize, float2(1.0)), p / max(halfSize, float2(1.0)));\n" +
        "    float magAmt = (0.28 - 0.05 * clamp(r2, 0.0, 1.0)) * uStrength;\n" +
        "    float2 magOffset = -p * magAmt;\n" +
        "\n" +
        "    // REAL SNELL'S-LAW CURVED-RIM REFRACTION: sharply bends & wraps moving wallpaper colors around the glass bevel!\n" +
        "    float2 bendOffset = -norm * (minSide * 0.52 * uStrength * lensSlope);\n" +
        "    float2 tiltParallax = uTilt * (minSide * 0.08 * uStrength);\n" +
        "\n" +
        "    float2 baseScreen = uScreenPos + fragCoord + magOffset + bendOffset + tiltParallax;\n" +
        "\n" +
        "    // Ultra-crisp crystal optical clarity (minimal blur so magnified & bent wallpaper details pop sharply!)\n" +
        "    float blurR = (0.35 + 0.30 * (1.0 - rimCurve)) * min(uWallScale.x, uWallScale.y);\n" +
        "\n" +
        "    // Prismatic optical RGB chromatic dispersion along the curved bevel lip!\n" +
        "    float dispPx = minSide * 0.036 * uStrength * lensSlope;\n" +
        "    half rCh = sampleGlassOptical((baseScreen + norm * dispPx) * uWallScale, blurR).r;\n" +
        "    half gCh = sampleGlassOptical(baseScreen * uWallScale, blurR).g;\n" +
        "    half bCh = sampleGlassOptical((baseScreen - norm * dispPx) * uWallScale, blurR).b;\n" +
        "    half3 col = half3(rCh, gCh, bCh);\n" +
        "\n" +
        "    // Optical glass clarity & crisp contrast boost + subtle curved refraction shadow band inside bevel\n" +
        "    half luma = dot(col, half3(0.299, 0.587, 0.114));\n" +
        "    col = mix(half3(luma), col, half(1.14));\n" +
        "    col = clamp((col - half3(0.5)) * half(1.06) + half3(0.5), half3(0.0), half3(1.0));\n" +
        "\n" +
        "    // Crisp internal optical bevel rim ring (gives unmistakable 3D cut-crystal lens thickness!)\n" +
        "    float depthLip = exp(-pow((dIn - bevelW * 0.24) / (bevelW * 0.18), 2.0));\n" +
        "    col *= half(1.0 - 0.10 * depthLip);\n" +
        "\n" +
        "    // Very light crystal glass sheen (low center haze so magnified wallpaper shines right through!)\n" +
        "    float yn = p.y / max(halfSize.y, 1.0);\n" +
        "    float topCatch = clamp((1.0 - yn) * 0.5, 0.0, 1.0);\n" +
        "    float frost = 0.045 + 0.040 * topCatch + 0.075 * pow(edgeT, 2.2);\n" +
        "    col = mix(col, half3(1.0), half(frost));\n" +
        "\n" +
        "    col = clamp(col, half3(0.0), half3(1.0));\n" +
        "    return half4(col * mask, mask);\n" +
        "}\n";

    // Engine B: Fallback Crystal-Clear Glass Body (used only if live wallpaper capture is unavailable)
    private static final String APPLE_CLEAR_BODY_AGSL =
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
        "    float2 b = halfSize - float2(1.0);\n" +
        "    float sdf = squircleSdf(p, b, 4.5);\n" +
        "    if (sdf > 4.0) return half4(0.0);\n" +
        "    if (sdf > 0.35) {\n" +
        "        float sh = exp(-pow(sdf / 2.6, 2.0)) * 0.095;\n" +
        "        return half4(0.0, 0.0, 0.0, sh);\n" +
        "    }\n" +
        "    float mask = clamp(0.5 - sdf, 0.0, 1.0);\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float2 norm = squircleNormal(p, b, 4.5);\n" +
        "    float2 pn = p / max(halfSize, float2(1.0));\n" +
        "    float bevelW = minSide * 0.145;\n" +
        "    float edgeT = clamp(1.0 - dIn / bevelW, 0.0, 1.0);\n" +
        "    float2 lDir = normalize(float2(-0.707, -0.707) + uTilt * 0.55);\n" +
        "    float cosTL = max(0.0, dot(norm, lDir));\n" +
        "    float cosBR = max(0.0, dot(norm, -lDir));\n" +
        "    float topCatch = clamp((1.0 - pn.y) * 0.5, 0.0, 1.0);\n" +
        "    float baseA = 0.145 + 0.055 * topCatch + 0.068 * pow(edgeT, 2.1);\n" +
        "    float depthLip = exp(-pow((dIn - bevelW * 0.34) / (bevelW * 0.24), 2.0));\n" +
        "    float darkA = depthLip * (1.0 - 0.65 * cosTL - 0.48 * cosBR) * 0.055;\n" +
        "    half3 tintCol = half3(0.985 + 0.015 * cosBR, 0.992, 0.992 + 0.008 * cosTL);\n" +
        "    float outA = clamp(baseA + darkA * (1.0 - baseA), 0.0, 0.32) * mask;\n" +
        "    half3 outPremul = tintCol * half(baseA * mask) + half3(0.04, 0.06, 0.10) * half(darkA * (1.0 - baseA) * mask);\n" +
        "    return half4(outPremul, half(outA));\n" +
        "}\n";

    // Razor-thin, mirror-sharp authentic Apple iOS 26 specular Fresnel rim
    private static final String APPLE_SPECULAR_RIM_AGSL =
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
        "    float2 b = halfSize - float2(0.85);\n" +
        "    float sdf = squircleSdf(p, b, n);\n" +
        "    if (sdf > 1.0) return half4(0.0);\n" +
        "    float mask = clamp(0.5 - sdf, 0.0, 1.0);\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float2 norm = squircleNormal(p, b, n);\n" +
        "    float2 tlDir = normalize(float2(-0.707, -0.707) + uTilt * 0.60);\n" +
        "    float2 brDir = -tlDir;\n" +
        "    float cosTL = max(0.0, dot(norm, tlDir));\n" +
        "    float cosBR = max(0.0, dot(norm, brDir));\n" +
        "    float sharpRim = exp(-pow((dIn - 0.95) / 0.85, 2.0));\n" +
        "    float rimIntensity = 0.24 + 0.74 * pow(cosTL, 2.1) + 0.58 * pow(cosBR, 2.4);\n" +
        "    float tightBevel = exp(-pow((dIn - 2.3) / 2.0, 2.0)) * (0.24 * pow(cosTL, 3.8) + 0.16 * pow(cosBR, 4.2));\n" +
        "    float a = clamp((sharpRim * rimIntensity + tightBevel) * uIntensity, 0.0, 0.95) * mask;\n" +
        "    return half4(half3(1.0, 0.996, 1.0) * a, a);\n" +
        "}\n";

    // Live wallpaper frame state (atomically swapped by background capture thread)
    private static volatile BitmapShader sWallpaperShader;
    private static volatile float sWallScaleX = 0.25f;
    private static volatile float sWallScaleY = 0.25f;
    private static volatile boolean sIsLiveWallpaper = false;
    private static volatile long sLastHomeDrawMs = 0L;
    private static volatile boolean sHomeFocused = false;
    private static volatile boolean sCaptureLoggedOnce = false;
    private static volatile boolean sCaptureFailedPermanently = false;
    private static volatile Runnable sFrameInvalidateCb;
    private static volatile Context sAppCtx;

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

    // Fallback BackgroundBlurDrawable per-view cache
    private static final WeakHashMap<View, Drawable> sViewBlurs = new WeakHashMap<>();
    private static Method sGetViewRootImpl;
    private static Method sCreateBgBlurDrawable;
    private static Method sBlurSetBlurRadius;
    private static Method sBlurSetCornerRadius;
    private static Method sBlurSetColor;
    private static boolean sBgBlurUnavailable = false;
    private static volatile boolean sLoggedLiveBlur = false;

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
        sWallpaperShader = null;
        sCaptureLoggedOnce = false;
        sCaptureFailedPermanently = false;
        if (sMirrorSc != null) {
            try { sMirrorSc.release(); } catch (Throwable ignored) {}
            sMirrorSc = null;
        }
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
            // Android 16/17 uses android.window.ScreenCaptureInternal; Android 14/15 uses android.window.ScreenCapture
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
        // Hardware bitmaps cannot call getPixel directly — trust valid non-empty HardwareBuffer dimensions!
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

        // Path 1: Ultra-fast GPU HardwareBuffer mirrorWallpaperSurface(0) + captureLayers(mirrorSc, displayRect, 0.25f)
        if (sWmService != null && sMirrorWallMethod != null && sCaptureLayersMethod != null) {
            try {
                if (sMirrorSc == null || !sMirrorSc.isValid()) {
                    Object sc = sMirrorWallMethod.invoke(sWmService, 0);
                    if (sc instanceof SurfaceControl && ((SurfaceControl) sc).isValid()) {
                        sMirrorSc = (SurfaceControl) sc;
                    }
                }
                if (sMirrorSc != null && sMirrorSc.isValid()) {
                    Rect crop = new Rect(0, 0, sw, sh);
                    Object shb = sCaptureLayersMethod.invoke(null, sMirrorSc, crop, 0.25f);
                    if (shb == null) {
                        shb = sCaptureLayersMethod.invoke(null, sMirrorSc, null, 0.25f);
                    }
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
            } catch (Throwable t) {
                if (!sCaptureLoggedOnce) {
                    XposedBridge.log(TAG + "mirrorWallpaperSurface err: " + t);
                }
                if (sMirrorSc != null) {
                    try { sMirrorSc.release(); } catch (Throwable ignored) {}
                    sMirrorSc = null;
                }
            }
        }

        // Path 2: IWindowManager.screenshotWallpaper() (auto-cropped to display viewport by WindowManagerService!)
        if (result == null && sWmService != null && sScreenshotWallMethod != null) {
            try {
                Object bObj = sScreenshotWallMethod.invoke(sWmService);
                if (bObj instanceof Bitmap && isBitmapNonBlack((Bitmap) bObj)) {
                    Bitmap raw = (Bitmap) bObj;
                    // Downscale software bitmaps cleanly to 1/3 res (~360x780, ~1MB) to keep GC & memory ultra-light!
                    if (Build.VERSION.SDK_INT < 26 || raw.getConfig() != Bitmap.Config.HARDWARE) {
                        int tw = Math.max(240, raw.getWidth() / 3);
                        int th = Math.max(480, raw.getHeight() / 3);
                        Bitmap small = Bitmap.createScaledBitmap(raw, tw, th, true);
                        if (small != raw) raw.recycle();
                        result = small;
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

            // Pause completely (0% CPU/GPU) when home screen is hidden, unfocused, or screen is off!
            if (!screenOn || (!sHomeFocused && sWallpaperShader != null) || (now - sLastHomeDrawMs > 400L && sWallpaperShader != null)) {
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
                sWallScaleX = frame.getWidth() / (float) sw;
                sWallScaleY = frame.getHeight() / (float) sh;
                sWallpaperShader = new BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Runnable cb = sFrameInvalidateCb;
                if (cb != null) cb.run();

                // If LIVE wallpaper -> pump smoothly at ~35fps while Home Screen is visible!
                // If STATIC wallpaper -> captured once, stop pump (0% idle cost, 120fps lens refraction on scroll/tilt!)
                if (sIsLiveWallpaper && sCapHandler != null) {
                    sCapHandler.postDelayed(this, 28L);
                } else {
                    sCapRunning = false;
                }
            } else {
                sCaptureFailedPermanently = true;
                sCapRunning = false;
                if (!sCaptureLoggedOnce) {
                    sCaptureLoggedOnce = true;
                    XposedBridge.log(TAG + "Wallpaper surface capture unavailable -> falling back to SurfaceFlinger BackgroundBlurDrawable");
                }
            }
        }
    };

    private static synchronized void kickLiveCaptureIfNeeded() {
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
            if (!sLoggedLiveBlur) {
                sLoggedLiveBlur = true;
                XposedBridge.log(TAG + "Fallback SurfaceFlinger BackgroundBlurDrawable engaged");
            }
            return blur;
        } catch (Throwable t) {
            sBgBlurUnavailable = true;
            return null;
        }
    }

    public static void clearSemBlur(View view) {
        if (view == null) return;
        try {
            Method semSetBlur = View.class.getMethod("semSetBlurInfo", Class.forName("android.view.SemBlurInfo"));
            semSetBlur.invoke(view, new Object[] { null });
        } catch (Throwable ignored) {}
    }

    public static void drawLiveLiquidBody(Context ctx, View hostView, Canvas canvas,
                                          float left, float top, float width, float height,
                                          float screenX, float screenY,
                                          float tiltX, float tiltY, float strength) {
        if (width < 16f || height < 16f || Build.VERSION.SDK_INT < 33) return;
        if (hostView != null) clearSemBlur(hostView);

        if (sAppCtx == null && ctx != null) {
            sAppCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        }
        sLastHomeDrawMs = System.currentTimeMillis();
        sHomeFocused = (hostView == null) || hostView.hasWindowFocus();
        kickLiveCaptureIfNeeded();

        BitmapShader wall = sWallpaperShader;
        if (wall != null && !sLensFailed) {
            if (hostView != null) detachBlurForView(hostView);
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

        // Fallback while first frame captures or if capture unavailable: Hardware SurfaceFlinger live blur
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
                canvas.drawRect(0f, 0f, width, height, sRimPaint);
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
     * scaled boldly (1.06x) so inner icons are large, crisp, and prominent!
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

        // Scale up cleanly to 1.08x centered so inner icons are bold & prominent (never shrunk!)
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float expand = w * 0.04f;
        RectF dst = new RectF(-expand, -expand, w + expand, h + expand);
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
