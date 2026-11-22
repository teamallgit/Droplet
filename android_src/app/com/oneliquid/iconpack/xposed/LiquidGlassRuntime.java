package com.oneliquid.iconpack.xposed;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Authentic Apple iOS 26 "Introducing Liquid Glass" AGSL Optical Engine for One UI 8 / 8.5 / 9+.
 *
 * Calibrated to Apple's real Liquid Glass physics (restrained, crystal-clear, zero neon/milky haze):
 *  - Crystal-clear center (~6-10% subtle clarity wash, gentle ~4% convex lens magnification)
 *  - True Snell's-law refraction concentrated cleanly in the outer ~16% curved glass bevel
 *  - Whisper-subtle prismatic RGB chromatic dispersion right along the curved bevel lip
 *  - Razor-thin (~1.2px) mirror-sharp anisotropic Fresnel specular rim gliding live with gyroscope tilt
 *  - Reads wallpaper via Samsung semGetScreenshotFileDescriptor / semGetThumbnailFileDescriptor /
 *    semGetDrawable / getDrawable so even Live Wallpapers get authentic optical refraction when available,
 *    with a restrained crystal-clear alpha fallback when no snapshot exists.
 */
public final class LiquidGlassRuntime {

    private static final String TAG = "OneLiquid-Glass: ";

    public static final class PureGlyphDrawable extends BitmapDrawable {
        public PureGlyphDrawable(Resources res, Bitmap bitmap) {
            super(res, bitmap);
        }
    }

    // Engine A: Authentic Apple iOS 26 Liquid Glass Optical Refraction Shader (samples wallpaper!)
    private static final String APPLE_LIQUID_LENS_AGSL =
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
        "half3 sampleGlassBlur(float2 coord, float r) {\n" +
        "    half3 c0 = uWallpaper.eval(coord).rgb;\n" +
        "    half3 c1 = uWallpaper.eval(coord + float2( r,  0.0)).rgb;\n" +
        "    half3 c2 = uWallpaper.eval(coord + float2(-r,  0.0)).rgb;\n" +
        "    half3 c3 = uWallpaper.eval(coord + float2( 0.0,  r)).rgb;\n" +
        "    half3 c4 = uWallpaper.eval(coord + float2( 0.0, -r)).rgb;\n" +
        "    return c0 * 0.44 + (c1 + c2 + c3 + c4) * 0.14;\n" +
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
        "        float sh = exp(-pow(sdf / 2.6, 2.0)) * 0.10;\n" +
        "        return half4(0.0, 0.0, 0.0, sh);\n" +
        "    }\n" +
        "    float mask = clamp(0.5 - sdf, 0.0, 1.0);\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float2 norm = squircleNormal(p, b, 4.5);\n" +
        "\n" +
        "    // Authentic Apple Liquid Glass: flat crystal-clear center + tight optical bevel in outer ~17%\n" +
        "    float bevelW = minSide * 0.175;\n" +
        "    float edgeT = clamp(1.0 - dIn / bevelW, 0.0, 1.0);\n" +
        "    // Steep smooth S-curve refraction profile ONLY along the curved glass rim!\n" +
        "    float rimCurve = edgeT * edgeT * (3.0 - 2.0 * edgeT);\n" +
        "    float lensSlope = pow(edgeT, 2.3);\n" +
        "\n" +
        "    // Subtle convex center magnification (~4.5%) + crisp inward Snell's-law rim bend (~16% of icon)\n" +
        "    float2 magOffset = -p * (0.045 * uStrength);\n" +
        "    float2 bendOffset = -norm * (minSide * 0.165 * uStrength * lensSlope);\n" +
        "    float2 tiltParallax = uTilt * (minSide * 0.04 * uStrength);\n" +
        "\n" +
        "    float2 baseScreen = uScreenPos + fragCoord + magOffset + bendOffset + tiltParallax;\n" +
        "    // Light, crisp optical softening (not blurry soup!)\n" +
        "    float blurR = (1.1 + 0.9 * (1.0 - rimCurve)) * uWallScale;\n" +
        "\n" +
        "    // Whisper-subtle optical RGB chromatic dispersion ONLY on the curved rim!\n" +
        "    float dispPx = minSide * 0.015 * uStrength * lensSlope;\n" +
        "    half rCh = sampleGlassBlur((baseScreen + norm * dispPx) * uWallScale, blurR).r;\n" +
        "    half gCh = sampleGlassBlur(baseScreen * uWallScale, blurR).g;\n" +
        "    half bCh = sampleGlassBlur((baseScreen - norm * dispPx) * uWallScale, blurR).b;\n" +
        "    half3 col = half3(rCh, gCh, bCh);\n" +
        "\n" +
        "    // Authentic Apple glass clarity: natural crystal vibrancy + subtle depth lip inside bevel\n" +
        "    half luma = dot(col, half3(0.299, 0.587, 0.114));\n" +
        "    col = mix(half3(luma), col, half(1.06));\n" +
        "\n" +
        "    // Subtle optical grazing-angle depth right inside the rim (makes real glass look cut & crisp!)\n" +
        "    float depthLip = exp(-pow((dIn - bevelW * 0.30) / (bevelW * 0.25), 2.0));\n" +
        "    col *= half(1.0 - 0.055 * depthLip);\n" +
        "\n" +
        "    // Authentic Apple airy frosted crystal tint (lighter, luminous, never opaque!)\n" +
        "    float yn = p.y / max(halfSize.y, 1.0);\n" +
        "    float frost = 0.14 + 0.055 * clamp((1.0 - yn) * 0.5, 0.0, 1.0) + 0.05 * rimCurve;\n" +
        "    col = mix(col, half3(1.0), half(frost));\n" +
        "\n" +
        "    col = clamp(col, half3(0.0), half3(1.0));\n" +
        "    return half4(col * mask, mask);\n" +
        "}\n";

    // Engine B: Authentic Apple iOS 26 Crystal-Clear Liquid Glass Body (composited live over SurfaceFlinger BackgroundBlurDrawable!)
    // Ultra-clean luminous transparency with subtle 3D curved optical bevel & zero opaque color mismatch!
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
        "\n" +
        "    float bevelW = minSide * 0.145;\n" +
        "    float edgeT = clamp(1.0 - dIn / bevelW, 0.0, 1.0);\n" +
        "\n" +
        "    float2 lDir = normalize(float2(-0.707, -0.707) + uTilt * 0.55);\n" +
        "    float cosTL = max(0.0, dot(norm, lDir));\n" +
        "    float cosBR = max(0.0, dot(norm, -lDir));\n" +
        "\n" +
        "    // Airy, luminous Apple iOS 26 Clear Liquid Glass tint (brightens live blurred wallpaper cleanly!)\n" +
        "    float topCatch = clamp((1.0 - pn.y) * 0.5, 0.0, 1.0);\n" +
        "    float baseA = 0.145 + 0.055 * topCatch + 0.068 * pow(edgeT, 2.1);\n" +
        "    // Delicate optical refraction lip right inside the bevel (cut-crystal depth without darkening center!)\n" +
        "    float depthLip = exp(-pow((dIn - bevelW * 0.34) / (bevelW * 0.24), 2.0));\n" +
        "    float darkA = depthLip * (1.0 - 0.65 * cosTL - 0.48 * cosBR) * 0.055;\n" +
        "\n" +
        "    // Whisper-subtle prismatic glass sheen along the tilted bevel\n" +
        "    half3 tintCol = half3(0.985 + 0.015 * cosBR, 0.992, 0.992 + 0.008 * cosTL);\n" +
        "    float outA = clamp(baseA + darkA * (1.0 - baseA), 0.0, 0.32) * mask;\n" +
        "    half3 outPremul = tintCol * half(baseA * mask) + half3(0.04, 0.06, 0.10) * half(darkA * (1.0 - baseA) * mask);\n" +
        "    return half4(outPremul, half(outA));\n" +
        "}\n";

    // Razor-thin, mirror-sharp authentic Apple iOS 26 specular Fresnel rim (NO thick milky neon glow!)
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
        "\n" +
        "    // Gyroscope-reactive specular light axis\n" +
        "    float2 tlDir = normalize(float2(-0.707, -0.707) + uTilt * 0.60);\n" +
        "    float2 brDir = -tlDir;\n" +
        "    float cosTL = max(0.0, dot(norm, tlDir));\n" +
        "    float cosBR = max(0.0, dot(norm, brDir));\n" +
        "\n" +
        "    // RAZOR-THIN (~1.15px) mirror-sharp Apple rim — crisp around full contour, gleaming on TL & BR corners!\n" +
        "    float sharpRim = exp(-pow((dIn - 0.95) / 0.85, 2.0));\n" +
        "    float rimIntensity = 0.26 + 0.72 * pow(cosTL, 2.1) + 0.56 * pow(cosBR, 2.4);\n" +
        "\n" +
        "    // Tight secondary 2.3px inner bevel reflection ONLY right on the two bright corners\n" +
        "    float tightBevel = exp(-pow((dIn - 2.3) / 2.0, 2.0)) * (0.24 * pow(cosTL, 3.8) + 0.16 * pow(cosBR, 4.2));\n" +
        "\n" +
        "    float a = clamp((sharpRim * rimIntensity + tightBevel) * uIntensity, 0.0, 0.95) * mask;\n" +
        "    return half4(half3(1.0, 0.996, 1.0) * a, a);\n" +
        "}\n";

    private static volatile BitmapShader sWallpaperShader;
    private static volatile float sWallScale = 0.5f;
    private static volatile long sLastWallAttemptMs = 0L;
    private static volatile boolean sLoggedLiveBlur = false;

    // Per-icon hardware SurfaceFlinger BackgroundBlurDrawable (120fps live backdrop blur clipped to exact icon rect!)
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
        sWallpaperShader = null;
        sLastWallAttemptMs = 0L;
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
                XposedBridge.log(TAG + "Hardware 120fps SurfaceFlinger BackgroundBlurDrawable engaged for live glass icons!");
            }
            return blur;
        } catch (Throwable t) {
            sBgBlurUnavailable = true;
            XposedBridge.log(TAG + "BackgroundBlurDrawable unavailable (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            return null;
        }
    }

    private static boolean isBitmapNonBlack(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return false;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 16 || h < 16) return false;
        int nonDark = 0;
        for (int sy = 1; sy <= 5; sy++) {
            for (int sx = 1; sx <= 5; sx++) {
                int px = bmp.getPixel(w * sx / 6, h * sy / 6);
                int a = Color.alpha(px);
                int lum = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3;
                if (a > 200 && lum > 12) nonDark++;
            }
        }
        return nonDark >= 3;
    }

    /**
     * Loads the static Home Screen wallpaper ONLY when a static wallpaper is set (wm.getWallpaperInfo() == null).
     * For Live Animated Wallpapers (e.g. Organic4WallpaperService), returns null so icons use REAL-TIME
     * 120fps SurfaceFlinger BackgroundBlurDrawable instead of a stale mismatched static snapshot!
     */
    public static BitmapShader ensureWallpaperShader(Context ctx) {
        if (sWallpaperShader != null) return sWallpaperShader;
        long now = System.currentTimeMillis();
        if (now - sLastWallAttemptMs < 5000L) return null;
        sLastWallAttemptMs = now;

        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            if (wm.getWallpaperInfo() != null) {
                XposedBridge.log(TAG + "Live wallpaper active (" + wm.getWallpaperInfo().getServiceName()
                        + ") -> using real-time 120fps SurfaceFlinger BackgroundBlurDrawable + Apple Crystal AGSL");
                return null;
            }
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int sw = Math.max(dm.widthPixels, 720);
            int sh = Math.max(dm.heightPixels, 1280);
            float scale = 0.5f;
            int tw = Math.max(180, Math.round(sw * scale));
            int th = Math.max(320, Math.round(sh * scale));

            Drawable wd = wm.getDrawable();
            if (wd != null) {
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
                    sWallScale = scale;
                    sWallpaperShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    XposedBridge.log(TAG + "Static wallpaper loaded for live Snell's-law refraction (" + tw + "x" + th + ")");
                    return sWallpaperShader;
                } else {
                    b.recycle();
                }
            }
        } catch (Throwable ignored) {}
        return null;
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

        BitmapShader wall = ensureWallpaperShader(ctx);
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

        // Live Wallpaper / Real-time SurfaceFlinger 120fps Backdrop Blur clipped strictly to icon squircle!
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
            // Subtle Apple frosted-glass vertical translucency: luminous crisp white at top -> 78% frosted at bottom
            float yFrac = y / (float) Math.max(h - 1, 1);
            float frostMul = 0.96f - 0.18f * yFrac;
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

        // Scale cleanly to 0.88x centered so every glyph has sleek Apple iOS 26 clear-glass breathing room!
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float pad = w * 0.06f;
        RectF dst = new RectF(pad, pad, w - pad, h - pad);
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
