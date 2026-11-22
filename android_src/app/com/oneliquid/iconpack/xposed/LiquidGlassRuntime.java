package com.oneliquid.iconpack.xposed;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
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

    /**
     * Marker BitmapDrawable subclass so OneLiquidHook NEVER double-wraps an icon
     * that OneLiquid already replaced or transformed.
     */
    public static final class LiquidGlassDrawable extends BitmapDrawable {
        public LiquidGlassDrawable(Resources res, Bitmap bitmap) {
            super(res, bitmap);
        }
    }

    // Live AGSL fragment shader for 1:1 iOS 26 Clear Liquid Glass scroll-reactive Fresnel caustics
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
        "    float n = uCornerRatio > 0.35 ? 2.6 : 4.5;\n" +
        "    float sdf = squircleSdf(p, halfSize - float2(1.0), n);\n" +
        "    if (sdf > 1.2) return half4(0.0);\n" +
        "    float dIn = max(-sdf, 0.0);\n" +
        "    float2 norm = normalize(p / max(halfSize, float2(1.0)) + float2(0.0001));\n" +
        "    float2 tlDir = normalize(float2(-0.707, -0.707) + uLightShift * 0.32);\n" +
        "    float2 brDir = -tlDir;\n" +
        "    float cosTL = max(0.0, dot(norm, tlDir));\n" +
        "    float cosBR = max(0.0, dot(norm, brDir));\n" +
        "    float rimBand = exp(-pow((dIn - 1.1) / 1.25, 2.0));\n" +
        "    float rimPower = 0.16 + 0.72 * pow(cosTL, 2.2) + 0.52 * pow(cosBR, 2.5);\n" +
        "    float innerGlow = exp(-pow((dIn - 3.8) / 7.5, 2.0)) * (0.18 * pow(cosTL, 3.0) + 0.12 * pow(cosBR, 3.2));\n" +
        "    float sparkle = exp(-pow((dIn - 1.5) / 1.0, 2.0)) * (0.55 * pow(cosTL, 5.5) + 0.38 * pow(cosBR, 5.8));\n" +
        "    float a = clamp((rimBand * rimPower + innerGlow + sparkle) * uIntensity, 0.0, 0.88);\n" +
        "    float mask = clamp(0.5 - sdf, 0.0, 1.0);\n" +
        "    return half4(half3(1.0, 0.996, 1.0) * a * mask, a * mask);\n" +
        "}\n";

    private static RuntimeShader sIconShader;
    private static Paint sShaderPaint;
    private static boolean sShaderFailed = false;

    /**
     * Detects whether an incoming Drawable is ALREADY a translucent Liquid Glass icon
     * (e.g. already applied via Theme Park with OneLiquid or another glass icon pack).
     * Prevents double-wrapping / nested squircles!
     */
    public static boolean isAlreadyTranslucentGlass(Drawable d) {
        if (d == null) return false;
        if (d instanceof LiquidGlassDrawable) return true;
        try {
            Bitmap bmp = null;
            boolean shouldRecycle = false;
            if (d instanceof BitmapDrawable) {
                bmp = ((BitmapDrawable) d).getBitmap();
            }
            if (bmp == null || bmp.isRecycled()) {
                int s = 32;
                bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, s, s);
                d.draw(c);
                shouldRecycle = true;
            }
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (w < 8 || h < 8) {
                if (shouldRecycle) bmp.recycle();
                return false;
            }
            // Sample 8 perimeter-interior glass-plate points (avoiding center glyph & outer transparent corner)
            int[][] pts = {
                { w / 6, h / 2 }, { w - w / 6, h / 2 },
                { w / 2, h / 6 }, { w / 2, h - h / 6 },
                { w / 5, h / 5 }, { w - w / 5, h / 5 },
                { w / 5, h - h / 5 }, { w - w / 5, h - h / 5 }
            };
            int translucentCount = 0;
            for (int[] pt : pts) {
                int px = Math.max(0, Math.min(w - 1, pt[0]));
                int py = Math.max(0, Math.min(h - 1, pt[1]));
                int alpha = Color.alpha(bmp.getPixel(px, py));
                // Translucent glass plate pixels have alpha roughly in [18..195]
                if (alpha >= 18 && alpha <= 195) {
                    translucentCount++;
                }
            }
            if (shouldRecycle) bmp.recycle();
            // If >= 4 of the 8 plate samples are semi-transparent, this icon is ALREADY translucent glass!
            return translucentCount >= 4;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Draws a live scroll-reactive AGSL Fresnel specular glass rim over an icon, folder, or dock pill.
     */
    public static void drawLiveLiquidRim(Canvas canvas, float left, float top, float width, float height,
                                         float lightShiftX, float lightShiftY, boolean isPill, float intensity) {
        if (width < 12f || height < 12f || intensity <= 0.01f) return;
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
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.2f, width * 0.012f));
        p.setShader(new LinearGradient(
                left, top, left + width, top + height,
                new int[] { Color.argb((int) (180 * intensity), 255, 255, 255),
                            Color.argb((int) (45 * intensity), 255, 255, 255),
                            Color.argb((int) (150 * intensity), 255, 255, 255) },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP
        ));
        float r = isPill ? Math.min(width, height) * 0.46f : Math.min(width, height) * 0.235f;
        canvas.drawRoundRect(new RectF(left + 1f, top + 1f, left + width - 1f, top + height - 1f), r, r, p);
    }

    /**
     * Enables Samsung One UI's native 120fps hardware backdrop blur (SemBlurInfo) on any View.
     */
    public static boolean applySemBlur(View view, int blurRadius, float cornerRadiusPx, int bgTintColor) {
        if (view == null) return false;
        try {
            Class<?> builderCls = Class.forName("android.view.SemBlurInfo$Builder");
            Constructor<?> ctor = builderCls.getConstructor(int.class);
            Object builder = ctor.newInstance(0); // BLUR_MODE_WINDOW
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
     * Styles One UI's Hotseat dock container into a floating iOS 26 Clear Liquid Glass capsule pill.
     */
    public static void styleGlassDock(View dockView, float density) {
        if (dockView == null || dockView.getWidth() < 40 || dockView.getHeight() < 30) return;
        final float r = Math.min(32f * density, dockView.getHeight() * 0.44f);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(r);
        bg.setColor(Color.argb(34, 255, 255, 255));
        bg.setStroke(Math.max(1, Math.round(1.0f * density)), Color.argb(105, 255, 255, 255));
        dockView.setBackground(bg);
        dockView.setClipToOutline(true);
        dockView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                int padX = Math.round(10f * density);
                int padY = Math.round(2f * density);
                int w = v.getWidth();
                int h = v.getHeight();
                if (w > padX * 2 && h > padY * 2) {
                    outline.setRoundRect(padX, padY, w - padX, h - padY, Math.min(r, (h - padY * 2) * 0.48f));
                } else {
                    outline.setRoundRect(0, 0, w, h, Math.min(r, h * 0.48f));
                }
            }
        });
        applySemBlur(dockView, 80, r, Color.argb(30, 245, 250, 255));
    }

    /**
     * On-the-fly fallback converter ONLY for opaque stock icons not found in our 1,020 icon pack.
     */
    public static Bitmap convertDrawableToLiquidGlass(Drawable src, int sizePx) {
        if (src == null || sizePx <= 0) return null;
        int sz = Math.max(96, Math.min(sizePx, 256));
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);

        // Match exact OneLiquid MARGIN/SIZE ratio (10/512 = 1.95%)
        float pad = sz * 0.0195f;
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

        int gSz = Math.round(sz * 0.66f);
        Bitmap rawGlyph = Bitmap.createBitmap(gSz, gSz, Bitmap.Config.ARGB_8888);
        Canvas gc = new Canvas(rawGlyph);
        glyphSrc.setBounds(0, 0, gSz, gSz);
        glyphSrc.draw(gc);

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

        drawLiveLiquidRim(c, pad, pad, sz - 2f * pad, sz - 2f * pad, 0f, 0f, false, 0.95f);
        return out;
    }
}
