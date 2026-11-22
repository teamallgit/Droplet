package com.oneliquid.iconpack.xposed;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
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
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Runtime Liquid Glass shader & Samsung One UI SemBlurInfo hardware backdrop blur helper.
 * Supports Android 13-17 (One UI 8 / 8.5 / 9+).
 */
public final class LiquidGlassRuntime {

    // Live AGSL fragment shader for 1:1 iOS 26 Clear Liquid Glass Fresnel rim + scroll-reactive caustics
    private static final String LIQUID_RIM_AGSL =
        "uniform float2 uSize;\n" +
        "uniform float2 uLightShift;\n" +
        "uniform float uCornerRatio;\n" +
        "uniform float uIntensity;\n" +
        "float squircleSdf(float2 p, float2 b, float n) {\n" +
        "    float2 q = abs(p) / max(b, float2(1.0));\n" +
        "    float d = pow(pow(q.x, n) + pow(q.y, n), 1.0 / n) - 1.0;\n" +
        "    return d * min(b.x, b.y);\n" +
        "}\n" +
        "half4 main(float2 fragCoord) {\n" +
        "    float2 halfSize = uSize * 0.5;\n" +
        "    float2 p = fragCoord - halfSize;\n" +
        "    float n = uCornerRatio > 0.35 ? 2.8 : 4.5;\n" +
        "    float sdf = squircleSdf(p, halfSize - float2(1.5), n);\n" +
        "    if (sdf > 1.5) return half4(0.0);\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float2 norm = normalize(p / max(halfSize, float2(1.0)) + float2(0.0001));\n" +
        "    float2 tlDir = normalize(float2(-0.707, -0.707) + uLightShift * 0.25);\n" +
        "    float2 brDir = -tlDir;\n" +
        "    float cosTL = max(0.0, dot(norm, tlDir));\n" +
        "    float cosBR = max(0.0, dot(norm, brDir));\n" +
        "    float rimBand = exp(-pow((dIn - 1.3) / 1.45, 2.0));\n" +
        "    float rimPower = 0.28 + 0.68 * pow(cosTL, 1.7) + 0.48 * pow(cosBR, 2.0);\n" +
        "    float innerGlow = exp(-pow((dIn - 4.0) / 8.5, 2.0)) * (0.04 + 0.22 * pow(cosTL, 2.4) + 0.16 * pow(cosBR, 2.6));\n" +
        "    float sparkle = exp(-pow((dIn - 1.8) / 1.1, 2.0)) * (0.48 * pow(cosTL, 5.0) + 0.34 * pow(cosBR, 5.2));\n" +
        "    float a = clamp((rimBand * rimPower + innerGlow + sparkle) * uIntensity, 0.0, 0.92);\n" +
        "    float mask = clamp(0.5 - sdf, 0.0, 1.0);\n" +
        "    return half4(half3(1.0, 0.995, 1.0) * a * mask, a * mask);\n" +
        "}\n";

    private static RuntimeShader sIconShader;
    private static Paint sShaderPaint;
    private static boolean sShaderFailed = false;

    /**
     * Draws a live scroll-reactive AGSL Fresnel specular glass rim over an icon, folder, or dock pill.
     */
    public static void drawLiveLiquidRim(Canvas canvas, float left, float top, float width, float height,
                                         float lightShiftX, float lightShiftY, boolean isPill, float intensity) {
        if (width < 12f || height < 12f) return;
        if (Build.VERSION.SDK_INT >= 33 && !sShaderFailed) {
            try {
                if (sIconShader == null) {
                    sIconShader = new RuntimeShader(LIQUID_RIM_AGSL);
                    sShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                }
                sIconShader.setFloatUniform("uSize", width, height);
                sIconShader.setFloatUniform("uLightShift", lightShiftX, lightShiftY);
                sIconShader.setFloatUniform("uCornerRatio", isPill ? 0.48f : 0.23f);
                sIconShader.setFloatUniform("uIntensity", intensity);
                sShaderPaint.setShader(sIconShader);

                canvas.save();
                canvas.translate(left, top);
                canvas.drawRect(0f, 0f, width, height, sShaderPaint);
                canvas.restore();
                return;
            } catch (Throwable ignored) {
                sShaderFailed = true;
            }
        }
        // Fallback crisp canvas specular rim if AGSL unavailable
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, width * 0.014f));
        p.setShader(new LinearGradient(
                left, top, left + width, top + height,
                new int[] { Color.argb((int) (210 * intensity), 255, 255, 255),
                            Color.argb((int) (75 * intensity), 255, 255, 255),
                            Color.argb((int) (175 * intensity), 255, 255, 255) },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP
        ));
        float r = isPill ? Math.min(width, height) * 0.45f : Math.min(width, height) * 0.235f;
        canvas.drawRoundRect(new RectF(left + 1f, top + 1f, left + width - 1f, top + height - 1f), r, r, p);
    }

    /**
     * Enables Samsung One UI's native 120fps hardware backdrop blur (SemBlurInfo) on any View.
     * Everything moving behind the View blurs & shimmers live!
     */
    public static boolean applySemBlur(View view, int blurRadius, float cornerRadiusPx, int bgTintColor) {
        if (view == null) return false;
        try {
            Class<?> builderCls = Class.forName("android.view.SemBlurInfo$Builder");
            // BLUR_MODE_WINDOW = 0 (real-time window/wallpaper backdrop blur!)
            Constructor<?> ctor = builderCls.getConstructor(int.class);
            Object builder = ctor.newInstance(0);
            try {
                builderCls.getMethod("setRadius", int.class).invoke(builder, blurRadius);
            } catch (Throwable ignored) {}
            try {
                builderCls.getMethod("setBackgroundCornerRadius", float.class).invoke(builder, cornerRadiusPx);
            } catch (Throwable ignored) {}
            try {
                builderCls.getMethod("setBackgroundColor", int.class).invoke(builder, bgTintColor);
            } catch (Throwable ignored) {}

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
     * Applies a smooth iOS 26 rounded glass capsule pill background + outline clip to One UI's Hotseat dock.
     */
    public static void styleGlassDock(View dockView, float density) {
        if (dockView == null) return;
        float r = 28f * density;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(r);
        bg.setColor(Color.argb(38, 255, 255, 255));
        bg.setStroke(Math.max(1, Math.round(1.1f * density)), Color.argb(120, 255, 255, 255));
        dockView.setBackground(bg);
        dockView.setClipToOutline(true);
        dockView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.min(r, v.getHeight() * 0.48f));
            }
        });
        applySemBlur(dockView, 75, r, Color.argb(34, 245, 248, 255));
    }

    /**
     * On-the-fly fallback converter for any unthemed app not in our 1,020 icon pack:
     * transforms any Android Drawable into a cohesive 1:1 iOS 26 Clear Liquid Glass bitmap.
     */
    public static Bitmap convertDrawableToLiquidGlass(Drawable src, int sizePx) {
        if (src == null || sizePx <= 0) return null;
        int sz = Math.max(96, Math.min(sizePx, 256));
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);

        // 1. Translucent iOS 26 squircle glass body
        float pad = sz * 0.025f;
        RectF rect = new RectF(pad, pad, sz - pad, sz - pad);
        float cr = (sz - 2f * pad) * 0.24f;

        Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setShader(new LinearGradient(
                0f, pad, 0f, sz - pad,
                Color.argb(56, 255, 255, 255),
                Color.argb(34, 245, 248, 255),
                Shader.TileMode.CLAMP
        ));
        c.drawRoundRect(rect, cr, cr, bodyPaint);

        // 2. Extract foreground/monochrome glyph and render as pure-white top-to-bottom translucent glass
        Drawable glyphSrc = src;
        if (Build.VERSION.SDK_INT >= 33 && src instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable aid = (AdaptiveIconDrawable) src;
            if (aid.getMonochrome() != null) {
                glyphSrc = aid.getMonochrome();
            } else if (aid.getForeground() != null) {
                glyphSrc = aid.getForeground();
            }
        } else if (src instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable aid = (AdaptiveIconDrawable) src;
            if (aid.getForeground() != null) glyphSrc = aid.getForeground();
        }

        int gSz = Math.round(sz * 0.64f);
        Bitmap rawGlyph = Bitmap.createBitmap(gSz, gSz, Bitmap.Config.ARGB_8888);
        Canvas gc = new Canvas(rawGlyph);
        glyphSrc.setBounds(0, 0, gSz, gSz);
        glyphSrc.draw(gc);

        // Convert glyph alpha/luminance into luminous pure-white frosted glass gradient
        Bitmap frosted = Bitmap.createBitmap(gSz, gSz, Bitmap.Config.ARGB_8888);
        Canvas fc = new Canvas(frosted);
        fc.drawBitmap(rawGlyph, 0f, 0f, null);
        Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        tint.setShader(new LinearGradient(
                0f, 0f, 0f, gSz,
                Color.argb(246, 255, 255, 255),
                Color.argb(156, 245, 250, 255),
                Shader.TileMode.CLAMP
        ));
        fc.drawRect(0f, 0f, gSz, gSz, tint);
        rawGlyph.recycle();

        float off = (sz - gSz) * 0.5f;
        c.drawBitmap(frosted, off, off, null);
        frosted.recycle();

        // 3. Crisp anisotropic Fresnel specular rim
        drawLiveLiquidRim(c, pad, pad, sz - 2f * pad, sz - 2f * pad, 0f, 0f, false, 1.0f);
        return out;
    }
}
