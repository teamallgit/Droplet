package com.oneliquid.iconpack.xposed;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;
import com.oneliquid.iconpack.ModuleStatus;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.xmlpull.v1.XmlPullParser;

/**
 * OneLiquid v2.1.0 LSPosed Module for Samsung One UI 8 / 8.5 / 9+.
 *
 * Renders TRUE real-time GPU AGSL "Liquid Syrup" Glass Refraction on every Home Screen icon:
 *  - Live wallpaper BitmapShader sampling (Snell's-law edge bend + convex lens magnification + RGB dispersion)
 *  - Live gyroscope tilt & swipe-reactive specular caustics (zero idle redraws when stationary!)
 *  - Clean pure-glyph separation so icons float INSIDE the live refracting glass droplet (zero double boxes!)
 *  - Liquid Glass Folders & SystemUI Glass Polish (Dock hook removed per user request)
 */
public final class OneLiquidHook implements IXposedHookLoadPackage {

    private static final String TAG = "OneLiquid-LSPosed: ";
    private static volatile XSharedPreferences sPrefs;
    private static volatile Resources sModuleRes;
    private static volatile Map<String, String> sCompMap;
    private static volatile Map<String, String> sPkgMap;
    private static volatile Map<String, String> sLabelToDrawableMap;
    private static final Map<String, Bitmap> sPureGlyphCache = new ConcurrentHashMap<>();
    private static final Map<String, Bitmap> sFullIconCache = new ConcurrentHashMap<>();
    private static final Set<View> sActiveIcons = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, long[]> sLastIconPos = new WeakHashMap<>();
    private static final Set<View> sHookedRoots = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> sCleanedWidgets = Collections.newSetFromMap(new WeakHashMap<>());
    private static final AtomicInteger sHomeHooks = new AtomicInteger(0);
    private static final AtomicInteger sSysUiHooks = new AtomicInteger(0);
    private static final ThreadLocal<Boolean> sInSetIcon = new ThreadLocal<>();

    // Live smoothed gyroscope/gravity tilt vector in [-1..1]
    private static volatile float sTiltX = 0f;
    private static volatile float sTiltY = 0f;
    private static volatile float sLastNotifiedTiltX = 0f;
    private static volatile float sLastNotifiedTiltY = 0f;
    private static volatile boolean sSensorRegistered = false;

    private static XSharedPreferences prefs() {
        if (sPrefs == null) {
            sPrefs = new XSharedPreferences(ModuleStatus.PKG, ModuleStatus.PREFS_NAME);
            sPrefs.makeWorldReadable();
        }
        return sPrefs;
    }

    private static void reloadPrefs() {
        try {
            prefs().reload();
        } catch (Throwable ignored) {}
    }

    private static boolean isEnabled() {
        XSharedPreferences p = prefs();
        if (p.hasFileChanged()) p.reload();
        return p.getBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true);
    }

    private static boolean opt(String key, boolean def) {
        return prefs().getBoolean(key, def);
    }

    private static float getRefractionStrength() {
        int lvl = prefs().getInt(ModuleStatus.KEY_REFRACTION_LEVEL, 1); // Default: iOS 26 Balanced (authentic Apple!)
        if (lvl == 0) return 0.65f;      // Subtle
        if (lvl == 1) return 1.00f;      // iOS 26 Authentic
        return 1.45f;                    // Liquid Syrup (Strong Refraction)
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        final String pkg = lpparam.packageName;
        if (ModuleStatus.PKG.equals(pkg)) {
            hookSelf(lpparam.classLoader);
            return;
        }
        if (ModuleStatus.PKG_ONEUI_HOME.equals(pkg)) {
            hookOneUiHome(lpparam.classLoader);
            return;
        }
        if (ModuleStatus.PKG_SYSTEMUI.equals(pkg)) {
            hookSystemUi(lpparam.classLoader);
        }
    }

    private void hookSelf(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.oneliquid.iconpack.ModuleStatus",
                cl,
                "isModuleActive",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "self-hook error: " + t);
        }
    }

    private void setupGyroSensor(Context ctx) {
        if (sSensorRegistered) return;
        sSensorRegistered = true;
        try {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (s == null) return;
            sm.registerListener(new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (!isEnabled() || !opt(ModuleStatus.KEY_GYRO_TILT, true)) return;
                    // Normalize gravity tilt cleanly to [-1..1] with smooth exponential damping
                    float gx = Math.max(-1f, Math.min(1f, -event.values[0] / 6.5f));
                    float gy = Math.max(-1f, Math.min(1f, (event.values[1] - 4.5f) / 6.5f));
                    sTiltX = sTiltX * 0.78f + gx * 0.22f;
                    sTiltY = sTiltY * 0.78f + gy * 0.22f;

                    // Only invalidate visible home icons when tilt actually moves (zero idle battery drain!)
                    if (Math.abs(sTiltX - sLastNotifiedTiltX) > 0.018f || Math.abs(sTiltY - sLastNotifiedTiltY) > 0.018f) {
                        sLastNotifiedTiltX = sTiltX;
                        sLastNotifiedTiltY = sTiltY;
                        synchronized (sActiveIcons) {
                            for (View v : sActiveIcons) {
                                if (v != null && v.isShown() && v.isAttachedToWindow()) {
                                    v.postInvalidateOnAnimation();
                                }
                            }
                        }
                    }
                }
                @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            }, s, SensorManager.SENSOR_DELAY_UI);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "gyro setup err: " + t);
        }
    }

    private void registerStatusReceiver(ClassLoader cl, final String pingAction, final String targetName,
                                        final AtomicInteger hookCounter, final boolean listenWallpaper) {
        try {
            XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final Application app = (Application) param.thisObject;
                        if (listenWallpaper) {
                            setupGyroSensor(app);
                        }
                        IntentFilter f = new IntentFilter();
                        f.addAction(pingAction);
                        f.addAction(ModuleStatus.ACTION_RELOAD_PREFS);
                        if (listenWallpaper) {
                            f.addAction(Intent.ACTION_WALLPAPER_CHANGED);
                        }
                        BroadcastReceiver br = new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                String act = intent != null ? intent.getAction() : null;
                                if (Intent.ACTION_WALLPAPER_CHANGED.equals(act)) {
                                    LiquidGlassRuntime.invalidateWallpaperCache();
                                    invalidateVisibleIcons();
                                } else if (ModuleStatus.ACTION_RELOAD_PREFS.equals(act)) {
                                    reloadPrefs();
                                    sPureGlyphCache.clear();
                                    sFullIconCache.clear();
                                    invalidateVisibleIcons();
                                } else if (pingAction.equals(act)) {
                                    reloadPrefs();
                                    Intent pong = new Intent(ModuleStatus.ACTION_PONG);
                                    pong.setPackage(ModuleStatus.PKG);
                                    pong.putExtra("target", targetName);
                                    pong.putExtra("hooks", hookCounter.get());
                                    pong.putExtra("enabled", isEnabled());
                                    pong.putExtra("oneui", ModuleStatus.getOneUiVersionName());
                                    context.sendBroadcast(pong);
                                }
                            }
                        };
                        if (Build.VERSION.SDK_INT >= 33) {
                            app.registerReceiver(br, f, Context.RECEIVER_EXPORTED);
                        } else {
                            app.registerReceiver(br, f);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "receiver err in " + targetName + ": " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Application.onCreate hook err: " + t);
        }
    }

    private static void registerActiveIcon(View v) {
        if (v == null) return;
        synchronized (sActiveIcons) {
            sActiveIcons.add(v);
        }
        try {
            final View root = v.getRootView();
            if (root != null) {
                boolean shouldHook;
                synchronized (sHookedRoots) {
                    shouldHook = sHookedRoots.add(root);
                }
                if (shouldHook) {
                    final int[] tmp = new int[2];
                    root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            if (!isEnabled() || !opt(ModuleStatus.KEY_LIVE_REFRACTION, true)) return true;
                            synchronized (sActiveIcons) {
                                for (View iv : sActiveIcons) {
                                    if (iv == null || !iv.isAttachedToWindow() || !iv.isShown()) continue;
                                    iv.getLocationOnScreen(tmp);
                                    long packed = (((long) tmp[0]) << 32) | (tmp[1] & 0xFFFFFFFFL);
                                    long[] prev = sLastIconPos.get(iv);
                                    if (prev == null) {
                                        sLastIconPos.put(iv, new long[] { packed });
                                    } else if (prev[0] != packed) {
                                        prev[0] = packed;
                                        iv.invalidate();
                                    }
                                }
                            }
                            return true;
                        }
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void invalidateVisibleIcons() {
        synchronized (sActiveIcons) {
            for (View v : sActiveIcons) {
                if (v != null && v.isAttachedToWindow() && v.isShown()) {
                    v.postInvalidateOnAnimation();
                }
            }
        }
    }

    private static synchronized void ensureIconPackLoaded(Context hostCtx) {
        if (sModuleRes != null && sCompMap != null) return;
        try {
            Context modCtx = hostCtx.createPackageContext(ModuleStatus.PKG, Context.CONTEXT_IGNORE_SECURITY);
            Resources res = modCtx.getResources();
            Map<String, String> compMap = new ConcurrentHashMap<>();
            Map<String, String> pkgMap = new ConcurrentHashMap<>();
            Map<String, String> labelMap = new ConcurrentHashMap<>();

            int xmlId = res.getIdentifier("appfilter", "xml", ModuleStatus.PKG);
            if (xmlId != 0) {
                XmlResourceParser xpp = res.getXml(xmlId);
                int evt = xpp.getEventType();
                while (evt != XmlPullParser.END_DOCUMENT) {
                    if (evt == XmlPullParser.START_TAG && "item".equals(xpp.getName())) {
                        String comp = xpp.getAttributeValue(null, "component");
                        String draw = xpp.getAttributeValue(null, "drawable");
                        if (comp != null && draw != null && comp.startsWith("ComponentInfo{") && comp.endsWith("}")) {
                            String inner = comp.substring("ComponentInfo{".length(), comp.length() - 1);
                            compMap.put(inner, draw);
                            int slash = inner.indexOf('/');
                            if (slash > 0) {
                                String p = inner.substring(0, slash);
                                if (!pkgMap.containsKey(p)) pkgMap.put(p, draw);
                            }
                        }
                    }
                    evt = xpp.next();
                }
                xpp.close();
            }

            try {
                PackageManager pm = hostCtx.getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
                for (ResolveInfo ri : apps) {
                    String p = ri.activityInfo.packageName;
                    String c = ri.activityInfo.name;
                    String draw = compMap.get(p + "/" + c);
                    if (draw == null) draw = pkgMap.get(p);
                    if (draw != null) {
                        CharSequence lbl = ri.loadLabel(pm);
                        if (lbl != null && lbl.length() > 0) {
                            String key = lbl.toString().trim().toLowerCase(Locale.ROOT);
                            if (!labelMap.containsKey(key)) labelMap.put(key, draw);
                        }
                    }
                }
            } catch (Throwable ignored) {}

            sModuleRes = res;
            sCompMap = compMap;
            sPkgMap = pkgMap;
            sLabelToDrawableMap = labelMap;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed loading OneLiquid resources: " + t);
        }
    }

    private static ComponentName extractComponent(Object iconView) {
        if (iconView == null) return null;
        try {
            if (iconView instanceof View) {
                ComponentName cn = extractComponentFromObject(((View) iconView).getTag(), 0);
                if (cn != null) return cn;
            }
            for (String fieldName : new String[] { "iconItem", "appItem", "item", "itemData", "mItem" }) {
                try {
                    Object f = XposedHelpers.getObjectField(iconView, fieldName);
                    ComponentName cn = extractComponentFromObject(f, 0);
                    if (cn != null) return cn;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractLabel(Object iconView) {
        if (iconView == null) return null;
        try {
            Object f = XposedHelpers.getObjectField(iconView, "label");
            if (f instanceof CharSequence && ((CharSequence) f).length() > 0) {
                return f.toString().trim().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {}
        if (iconView instanceof TextView) {
            CharSequence t = ((TextView) iconView).getText();
            if (t != null && t.length() > 0) {
                return t.toString().trim().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static ComponentName extractComponentFromObject(Object obj, int depth) {
        if (obj == null || depth > 2) return null;
        if (obj instanceof ComponentName) return (ComponentName) obj;
        if (obj instanceof Intent) return ((Intent) obj).getComponent();
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.contains("component") || n.contains("intent") || n.contains("key") || n.contains("item")) {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val instanceof ComponentName) return (ComponentName) val;
                    if (val instanceof Intent) {
                        ComponentName c = ((Intent) val).getComponent();
                        if (c != null) return c;
                    }
                    if (val != null && depth < 2 && !val.getClass().getName().startsWith("java.")) {
                        ComponentName nested = extractComponentFromObject(val, depth + 1);
                        if (nested != null) return nested;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Loads a pure floating frosted-white glyph (stripping any static baked plate/rim when
     * live AGSL refraction is enabled, so the live GPU refracting droplet acts as the icon plate!).
     */
    private static Drawable prepareGlyphDrawable(Context ctx, ComponentName cn, String labelKey, Drawable orig, int iconPx, boolean liveRefraction) {
        if (orig instanceof LiquidGlassRuntime.PureGlyphDrawable) return null;

        ensureIconPackLoaded(ctx);
        Resources res = sModuleRes;
        String drawName = null;
        if (res != null) {
            if (cn != null) {
                String fullKey = cn.getPackageName() + "/" + cn.getClassName();
                drawName = sCompMap != null ? sCompMap.get(fullKey) : null;
                if (drawName == null && sPkgMap != null) drawName = sPkgMap.get(cn.getPackageName());
            }
            if (drawName == null && labelKey != null && sLabelToDrawableMap != null) {
                drawName = sLabelToDrawableMap.get(labelKey);
            }
            if (drawName != null) {
                if (liveRefraction) {
                    Bitmap pure = sPureGlyphCache.get(drawName);
                    if (pure == null) {
                        int id = res.getIdentifier(drawName, "drawable", ModuleStatus.PKG);
                        if (id != 0) {
                            Drawable d = res.getDrawable(id, null);
                            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                                // Full-bleed icons (Notes, Maps, Clock) look best kept mostly intact; standard icons strip static plate!
                                if ("ios_notes".equals(drawName) || "ios_maps".equals(drawName) || "ios_clock".equals(drawName)) {
                                    pure = ((BitmapDrawable) d).getBitmap();
                                } else {
                                    pure = LiquidGlassRuntime.extractPureGlyphFromOneLiquid(((BitmapDrawable) d).getBitmap());
                                }
                                if (pure != null) sPureGlyphCache.put(drawName, pure);
                            }
                        }
                    }
                    if (pure != null) return new LiquidGlassRuntime.PureGlyphDrawable(ctx.getResources(), pure);
                } else {
                    Bitmap full = sFullIconCache.get(drawName);
                    if (full == null) {
                        int id = res.getIdentifier(drawName, "drawable", ModuleStatus.PKG);
                        if (id != 0) {
                            Drawable d = res.getDrawable(id, null);
                            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                                full = ((BitmapDrawable) d).getBitmap();
                                sFullIconCache.put(drawName, full);
                            }
                        }
                    }
                    if (full != null) return new LiquidGlassRuntime.PureGlyphDrawable(ctx.getResources(), full);
                }
            }
        }

        // If Theme Park OneLiquid is active and live refraction is ON, strip the static plate from orig too!
        if (liveRefraction && orig instanceof BitmapDrawable && ((BitmapDrawable) orig).getBitmap() != null
                && LiquidGlassRuntime.isAlreadyTranslucentGlass(orig)) {
            Bitmap pure = LiquidGlassRuntime.extractPureGlyphFromOneLiquid(((BitmapDrawable) orig).getBitmap());
            if (pure != null) return new LiquidGlassRuntime.PureGlyphDrawable(ctx.getResources(), pure);
        }

        // Unknown opaque stock app -> extract pure frosted white glyph to float inside live droplet!
        if (liveRefraction && orig != null && !LiquidGlassRuntime.isAlreadyTranslucentGlass(orig)) {
            Bitmap pure = LiquidGlassRuntime.extractPureGlyphFromOpaqueDrawable(orig, iconPx > 0 ? iconPx : 192);
            if (pure != null) return new LiquidGlassRuntime.PureGlyphDrawable(ctx.getResources(), pure);
        }
        return null;
    }

    private static Drawable getIconFromView(TextView tv) {
        try {
            Object f = XposedHelpers.getObjectField(tv, "icon");
            if (f instanceof Drawable) return (Drawable) f;
        } catch (Throwable ignored) {}
        Drawable[] cds = tv.getCompoundDrawables();
        if (cds != null && cds.length > 1) return cds[1];
        return null;
    }

    private void hookOneUiHome(ClassLoader cl) {
        XposedBridge.log(TAG + "Hooking One UI Home (" + ModuleStatus.getOneUiVersionName() + ")");
        registerStatusReceiver(cl, ModuleStatus.ACTION_PING_HOME, "launcher", sHomeHooks, true);
        LiquidGlassRuntime.setFrameInvalidateCallback(new Runnable() {
            @Override
            public void run() {
                invalidateVisibleIcons();
            }
        });

        // Wake live wallpaper refraction immediately whenever LauncherActivity resumes (e.g., swiping up from an app!)
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity) {
                        LiquidGlassRuntime.onLauncherResumed((Activity) param.thisObject);
                        invalidateVisibleIcons();
                    }
                }
            });
        } catch (Throwable ignored) {}

        Class<?> iconViewCls = XposedHelpers.findClassIfExists("com.honeyspace.ui.common.iconview.IconViewImpl", cl);
        if (iconViewCls != null) {
            sHomeHooks.incrementAndGet();

            // 1. Replace icon with pure frosted glyph (when live refraction is ON) so ZERO double borders ever appear!
            try {
                XposedBridge.hookAllMethods(iconViewCls, "setIcon", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(sInSetIcon.get())) return;
                        if (!isEnabled() || !opt(ModuleStatus.KEY_LIQUID_ICONS, true)) return;
                        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Drawable)) return;
                        if (param.thisObject.getClass().getSimpleName().contains("Folder")) return;

                        try {
                            sInSetIcon.set(Boolean.TRUE);
                            View v = (View) param.thisObject;
                            Drawable orig = (Drawable) param.args[0];
                            ComponentName cn = extractComponent(param.thisObject);
                            String lbl = extractLabel(param.thisObject);
                            boolean liveRef = opt(ModuleStatus.KEY_LIVE_REFRACTION, true);
                            int sz = Math.max(orig.getIntrinsicWidth(), 192);
                            Drawable themed = prepareGlyphDrawable(v.getContext(), cn, lbl, orig, sz, liveRef);
                            if (themed != null) {
                                themed.setBounds(orig.getBounds());
                                param.args[0] = themed;
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            sInSetIcon.set(Boolean.FALSE);
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "IconViewImpl.setIcon hook err: " + t);
            }

            // 2. LIVE 120fps AGSL "Corn Syrup" Refracting Glass Droplet (BEFORE glyph) + Wet Specular Glaze (AFTER glyph)!
            try {
                XposedBridge.hookAllMethods(iconViewCls, "onDraw", new XC_MethodHook() {
                    private final int[] loc = new int[2];

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || !opt(ModuleStatus.KEY_LIVE_REFRACTION, true)) return;
                        if (!(param.thisObject instanceof TextView) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        if (param.thisObject.getClass().getSimpleName().contains("Folder")) return;
                        try {
                            TextView tv = (TextView) param.thisObject;
                            registerActiveIcon(tv);
                            Canvas canvas = (Canvas) param.args[0];
                            Drawable icon = getIconFromView(tv);
                            if (icon == null) return;
                            int iw = icon.getBounds().width();
                            int ih = icon.getBounds().height();
                            if (iw < 32 || ih < 32) return;

                            float insetX = iw * 0.0195f;
                            float insetY = ih * 0.0195f;
                            float left = (tv.getWidth() - iw) * 0.5f + insetX;
                            float top = tv.getPaddingTop() + insetY;
                            float boxW = iw - 2f * insetX;
                            float boxH = ih - 2f * insetY;

                            tv.getLocationOnScreen(loc);
                            float screenX = loc[0] + left;
                            float screenY = loc[1] + top;

                            float tx = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltX : 0f;
                            float ty = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltY : 0f;
                            // Also add subtle dynamic scroll angle shift as icons sweep across the screen!
                            int sw = Math.max(tv.getResources().getDisplayMetrics().widthPixels, 1080);
                            float scrollShift = ((screenX + boxW * 0.5f) / (float) sw) * 2.0f - 1.0f;

                            LiquidGlassRuntime.drawLiveLiquidBody(
                                    tv.getContext(), tv, canvas, left, top, boxW, boxH,
                                    screenX, screenY,
                                    tx + scrollShift * 0.18f, ty,
                                    getRefractionStrength()
                            );
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || !opt(ModuleStatus.KEY_LIVE_REFRACTION, true)) return;
                        if (!(param.thisObject instanceof TextView) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        if (param.thisObject.getClass().getSimpleName().contains("Folder")) return;
                        try {
                            TextView tv = (TextView) param.thisObject;
                            registerActiveIcon(tv);
                            Canvas canvas = (Canvas) param.args[0];
                            Drawable icon = getIconFromView(tv);
                            if (icon == null) return;
                            int iw = icon.getBounds().width();
                            int ih = icon.getBounds().height();
                            if (iw < 32 || ih < 32) return;

                            float insetX = iw * 0.0195f;
                            float insetY = ih * 0.0195f;
                            float left = (tv.getWidth() - iw) * 0.5f + insetX;
                            float top = tv.getPaddingTop() + insetY;
                            float boxW = iw - 2f * insetX;
                            float boxH = ih - 2f * insetY;

                            tv.getLocationOnScreen(loc);
                            int sw = Math.max(tv.getResources().getDisplayMetrics().widthPixels, 1080);
                            float scrollShift = ((loc[0] + left + boxW * 0.5f) / (float) sw) * 2.0f - 1.0f;
                            float tx = (opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltX : 0f) + scrollShift * 0.25f;
                            float ty = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltY : 0f;

                            // Wet liquid specular glaze & razor-crisp Fresnel caustic rim ON TOP!
                            LiquidGlassRuntime.drawLiveGlazeRim(canvas, left, top, boxW, boxH, tx, ty, false, 0.92f);
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "IconViewImpl.onDraw hook err: " + t);
            }
        }

        // 3. Hook HoneySpace FolderIconViewImpl for LIVE refracting iOS 26 Liquid Glass Folders
        Class<?> folderCls = XposedHelpers.findClassIfExists("com.honeyspace.ui.common.iconview.FolderIconViewImpl", cl);
        if (folderCls != null) {
            sHomeHooks.incrementAndGet();
            try {
                XposedBridge.hookAllMethods(folderCls, "onDraw", new XC_MethodHook() {
                    private final int[] loc = new int[2];

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || !opt(ModuleStatus.KEY_GLASS_FOLDERS, true)) return;
                        if (!(param.thisObject instanceof View) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        try {
                            View v = (View) param.thisObject;
                            registerActiveIcon(v);
                            Canvas c = (Canvas) param.args[0];
                            Drawable icon = (v instanceof TextView) ? getIconFromView((TextView) v) : null;
                            int iw = (icon != null && icon.getBounds().width() >= 32)
                                    ? icon.getBounds().width() : Math.round(Math.min(v.getWidth(), v.getHeight()) * 0.72f);
                            int ih = (icon != null && icon.getBounds().height() >= 32) ? icon.getBounds().height() : iw;
                            float insetX = iw * 0.0195f;
                            float insetY = ih * 0.0195f;
                            float left = (v.getWidth() - iw) * 0.5f + insetX;
                            float top = v.getPaddingTop() + insetY;
                            v.getLocationOnScreen(loc);
                            float tx = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltX : 0f;
                            float ty = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltY : 0f;
                            LiquidGlassRuntime.drawLiveLiquidBody(
                                    v.getContext(), v, c, left, top, iw - 2f * insetX, ih - 2f * insetY,
                                    loc[0] + left, loc[1] + top, tx, ty, getRefractionStrength());
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || !opt(ModuleStatus.KEY_GLASS_FOLDERS, true)) return;
                        if (!(param.thisObject instanceof View) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        try {
                            View v = (View) param.thisObject;
                            registerActiveIcon(v);
                            Canvas c = (Canvas) param.args[0];
                            Drawable icon = (v instanceof TextView) ? getIconFromView((TextView) v) : null;
                            int iw = (icon != null && icon.getBounds().width() >= 32)
                                    ? icon.getBounds().width() : Math.round(Math.min(v.getWidth(), v.getHeight()) * 0.72f);
                            int ih = (icon != null && icon.getBounds().height() >= 32) ? icon.getBounds().height() : iw;
                            float insetX = iw * 0.0195f;
                            float insetY = ih * 0.0195f;
                            float left = (v.getWidth() - iw) * 0.5f + insetX;
                            float top = v.getPaddingTop() + insetY;
                            float tx = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltX : 0f;
                            float ty = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltY : 0f;
                            LiquidGlassRuntime.drawLiveGlazeRim(c, left, top, iw - 2f * insetX, ih - 2f * insetY, tx, ty, false, 0.90f);
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "FolderIconViewImpl hook err: " + t);
            }
        }

        // 4. Hook HoneySpace HoneyAppWidgetHostView via ViewGroup.dispatchDraw for 1:1 iOS 26 Clear Liquid Glass Widgets!
        //    (Hardware-accelerated ViewGroups call dispatchDraw directly on every frame; filtering by isInstance is O(1)!)
        final Class<?> widgetHostCls = XposedHelpers.findClassIfExists("com.honeyspace.ui.common.widget.HoneyAppWidgetHostView", cl);
        if (widgetHostCls != null) {
            sHomeHooks.incrementAndGet();
            try {
                XposedBridge.hookAllMethods(widgetHostCls, "updateAppWidget", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof View) {
                            synchronized (sCleanedWidgets) {
                                sCleanedWidgets.remove((View) param.thisObject);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}

            try {
                XposedBridge.hookAllMethods(ViewGroup.class, "dispatchDraw", new XC_MethodHook() {
                    private final int[] loc = new int[2];

                    private float getWidgetCornerRadius(View v) {
                        try {
                            Object r = XposedHelpers.callMethod(v, "getCornerRadius");
                            if (r instanceof Float && ((Float) r) > 4f) return (Float) r;
                        } catch (Throwable ignored) {}
                        try {
                            Object r = XposedHelpers.callMethod(v, "getEnforcedCornerRadius");
                            if (r instanceof Float && ((Float) r) > 4f) return (Float) r;
                        } catch (Throwable ignored) {}
                        return Math.min(v.getWidth(), v.getHeight()) * 0.165f;
                    }

                    private boolean isUniformTintBitmap(Bitmap bmp) {
                        if (bmp == null || bmp.isRecycled()) return false;
                        int w = bmp.getWidth(), h = bmp.getHeight();
                        if (w < 8 || h < 8) return true;
                        if (Build.VERSION.SDK_INT >= 26 && bmp.getConfig() == Bitmap.Config.HARDWARE) return false;
                        try {
                            int p1 = bmp.getPixel(w / 3, h / 3);
                            int p2 = bmp.getPixel(2 * w / 3, h / 3);
                            int p3 = bmp.getPixel(w / 3, 2 * h / 3);
                            int p4 = bmp.getPixel(2 * w / 3, 2 * h / 3);
                            int[] pts = { p1, p2, p3, p4 };
                            int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0, maxA = 0;
                            for (int px : pts) {
                                int a = (px >>> 24) & 0xFF;
                                int r = (px >>> 16) & 0xFF;
                                int g = (px >>> 8) & 0xFF;
                                int b = px & 0xFF;
                                if (a > maxA) maxA = a;
                                if (r < minR) minR = r; if (r > maxR) maxR = r;
                                if (g < minG) minG = g; if (g > maxG) maxG = g;
                                if (b < minB) minB = b; if (b > maxB) maxB = b;
                            }
                            int spread = Math.max(maxR - minR, Math.max(maxG - minG, maxB - minB));
                            return spread < 28 && (maxA < 240 || (maxR < 95 && maxG < 95 && maxB < 95));
                        } catch (Throwable ignored) {
                            return false;
                        }
                    }

                    private void stripDarkWidgetChrome(View host) {
                        if (host.getWidth() < 64 || host.getHeight() < 64) return;
                        boolean firstTime;
                        synchronized (sCleanedWidgets) {
                            firstTime = sCleanedWidgets.add(host);
                        }
                        try {
                            if (firstTime) {
                                LiquidGlassRuntime.clearSemBlurSafe(host);
                                if (host.getParent() instanceof View) {
                                    View p1 = (View) host.getParent();
                                    LiquidGlassRuntime.clearSemBlurSafe(p1);
                                    if (p1.getParent() instanceof View) {
                                        LiquidGlassRuntime.clearSemBlurSafe((View) p1.getParent());
                                    }
                                }
                            }
                            if (host.getBackground() != null) host.setBackground(null);
                            if (host.getForeground() != null) host.setForeground(null);
                            if (host.getParent() instanceof View) {
                                View p1 = (View) host.getParent();
                                if (p1.getBackground() != null) p1.setBackground(null);
                                if (p1.getParent() instanceof View) {
                                    View p2 = (View) p1.getParent();
                                    if (p2.getBackground() != null) p2.setBackground(null);
                                }
                            }
                            if (host instanceof ViewGroup) {
                                stripChildrenRecursive((ViewGroup) host, host.getWidth(), host.getHeight(), 0, firstTime);
                            }
                        } catch (Throwable ignored) {}
                    }

                    private void stripChildrenRecursive(ViewGroup vg, int hw, int hh, int depth, boolean firstTime) {
                        if (depth > 6 || hw < 40 || hh < 40) return;
                        int n = vg.getChildCount();
                        for (int i = 0; i < n; i++) {
                            View ch = vg.getChildAt(i);
                            if (ch == null) continue;
                            boolean fullBleed = (ch.getWidth() >= hw * 0.88f && ch.getHeight() >= hh * 0.88f);
                            if (fullBleed) {
                                if (firstTime) LiquidGlassRuntime.clearSemBlurSafe(ch);
                                if (ch.getBackground() != null) {
                                    if (firstTime) {
                                        XposedBridge.log(TAG + "Widget stripped bg on " + ch.getClass().getSimpleName()
                                                + " (" + ch.getBackground().getClass().getSimpleName() + ") depth=" + depth);
                                    }
                                    ch.setBackground(null);
                                }
                                if (ch.getForeground() != null) {
                                    ch.setForeground(null);
                                }
                                if (ch instanceof ImageView) {
                                    Drawable d = ((ImageView) ch).getDrawable();
                                    if (d != null) {
                                        boolean shouldStrip = !(d instanceof BitmapDrawable)
                                                || isUniformTintBitmap(((BitmapDrawable) d).getBitmap());
                                        if (firstTime) {
                                            XposedBridge.log(TAG + "Widget fullBleed ImageView drawable="
                                                    + d.getClass().getSimpleName() + " strip=" + shouldStrip);
                                        }
                                        if (shouldStrip) {
                                            ((ImageView) ch).setImageDrawable(null);
                                        }
                                    }
                                }
                            }
                            if (ch instanceof ViewGroup) {
                                stripChildrenRecursive((ViewGroup) ch, hw, hh, depth + 1, firstTime);
                            }
                        }
                    }

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!widgetHostCls.isInstance(param.thisObject)) return;
                        if (!isEnabled() || !opt(ModuleStatus.KEY_GLASS_WIDGETS, true)) return;
                        if (param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        try {
                            View v = (View) param.thisObject;
                            int w = v.getWidth();
                            int h = v.getHeight();
                            if (w < 64 || h < 64) return;
                            registerActiveIcon(v);
                            stripDarkWidgetChrome(v);

                            Canvas c = (Canvas) param.args[0];
                            v.getLocationOnScreen(loc);
                            float cr = getWidgetCornerRadius(v);
                            float tx = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltX : 0f;
                            float ty = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltY : 0f;

                            LiquidGlassRuntime.drawLiveLiquidWidgetBody(
                                    v.getContext(), c, 0f, 0f, w, h,
                                    loc[0], loc[1], cr, tx, ty, getRefractionStrength()
                            );
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!widgetHostCls.isInstance(param.thisObject)) return;
                        if (!isEnabled() || !opt(ModuleStatus.KEY_GLASS_WIDGETS, true)) return;
                        if (param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        try {
                            View v = (View) param.thisObject;
                            int w = v.getWidth();
                            int h = v.getHeight();
                            if (w < 64 || h < 64) return;
                            Canvas c = (Canvas) param.args[0];
                            float cr = getWidgetCornerRadius(v);
                            float tx = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltX : 0f;
                            float ty = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltY : 0f;
                            LiquidGlassRuntime.drawLiveWidgetRim(c, 0f, 0f, w, h, cr, tx, ty, 0.94f);
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "HoneyAppWidgetHostView dispatchDraw hook err: " + t);
            }
        }

        // Prevent WidgetHostViewContainer from re-applying Samsung's dark gray SemBlur/bg over our Clear Liquid Glass widgets!
        Class<?> widgetContCls = XposedHelpers.findClassIfExists("com.honeyspace.ui.common.widget.WidgetHostViewContainer", cl);
        if (widgetContCls != null) {
            try {
                XposedBridge.hookAllMethods(widgetContCls, "updateBlurBackgroundWithDelay", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isEnabled() && opt(ModuleStatus.KEY_GLASS_WIDGETS, true)) {
                            param.setResult(null);
                        }
                    }
                });
                XposedBridge.hookAllMethods(widgetContCls, "onUpdateTransparentBackground", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isEnabled() && opt(ModuleStatus.KEY_GLASS_WIDGETS, true)) {
                            synchronized (sCleanedWidgets) { sCleanedWidgets.clear(); }
                        }
                    }
                });
                XposedBridge.hookAllMethods(widgetContCls, "setBackgroundAlpha", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isEnabled() && opt(ModuleStatus.KEY_GLASS_WIDGETS, true)) {
                            synchronized (sCleanedWidgets) { sCleanedWidgets.clear(); }
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private void hookSystemUi(ClassLoader cl) {
        XposedBridge.log(TAG + "Hooking SystemUI (" + ModuleStatus.getOneUiVersionName() + ")");
        registerStatusReceiver(cl, ModuleStatus.ACTION_PING_SYSUI, "systemui", sSysUiHooks, false);

        String[] targets = new String[] {
            "com.android.systemui.qs.tileimpl.QSTileViewImpl",
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView",
        };
        for (String clsName : targets) {
            Class<?> cls = XposedHelpers.findClassIfExists(clsName, cl);
            if (cls == null) continue;
            sSysUiHooks.incrementAndGet();
            try {
                XposedBridge.hookAllMethods(cls, "onDraw", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || !opt(ModuleStatus.KEY_SYSTEMUI_GLASS, true)) return;
                        if (!(param.thisObject instanceof View) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        View v = (View) param.thisObject;
                        if (v.getWidth() < 40 || v.getHeight() < 40) return;
                        LiquidGlassRuntime.drawLiveGlazeRim((Canvas) param.args[0], 0f, 0f, v.getWidth(), v.getHeight(), 0f, 0f, true, 0.45f);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }
}
