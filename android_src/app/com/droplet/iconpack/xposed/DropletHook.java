package com.droplet.iconpack.xposed;

import android.app.Activity;
import android.app.Application;
import android.app.WallpaperManager;
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
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import com.droplet.iconpack.ModuleStatus;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
 * Droplet v2.1.0 LSPosed Module for Samsung One UI 8 / 8.5 / 9+.
 *
 * Renders TRUE real-time GPU AGSL "Liquid Syrup" Glass Refraction on every Home Screen icon:
 *  - Live wallpaper BitmapShader sampling (Snell's-law edge bend + convex lens magnification + RGB dispersion)
 *  - Live gyroscope tilt & swipe-reactive specular caustics (zero idle redraws when stationary!)
 *  - Clean pure-glyph separation so icons float INSIDE the live refracting glass droplet (zero double boxes!)
 *  - Liquid Glass Folders & SystemUI Glass Polish (Dock hook removed per user request)
 */
public final class DropletHook implements IXposedHookLoadPackage {

    private static final String TAG = "Droplet-LSPosed: ";
    private static volatile XSharedPreferences sPrefs;
    private static volatile Resources sModuleRes;
    private static volatile Map<String, String> sCompMap;
    private static volatile Map<String, String> sPkgMap;
    private static volatile Map<String, String> sLabelToDrawableMap;
    private static volatile Map<String, ComponentName> sLabelToComponentMap;
    private static final Map<String, Bitmap> sPureGlyphCache = new ConcurrentHashMap<>();
    private static final Map<String, Bitmap> sFullIconCache = new ConcurrentHashMap<>();
    private static final Set<View> sActiveIcons = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> sAllIconViews = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, Drawable> sOriginalIcons = new WeakHashMap<>();
    private static final Map<View, Bitmap> sCompleteGlassBitmaps = new WeakHashMap<>();
    private static final Map<View, Drawable> sLazyCheckedIcons = new WeakHashMap<>();
    private static final Map<View, long[]> sLastIconPos = new WeakHashMap<>();
    private static final Map<View, Boolean> sDrawerIconCache = new WeakHashMap<>();
    private static final Map<View, ViewParent> sDrawerParentCache = new WeakHashMap<>();
    private static final Set<View> sHookedRoots = Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile Handler sMainHandler;
    private static final Runnable sInvalidateRunnable = new Runnable() {
        @Override
        public void run() {
            invalidateVisibleIconsOnUiThread();
        }
    };

    private static Handler getMainHandler() {
        Handler h = sMainHandler;
        if (h == null) {
            Looper ml = Looper.getMainLooper();
            if (ml != null) {
                h = new Handler(ml);
                sMainHandler = h;
            }
        }
        return h;
    }
    private static final AtomicInteger sHomeHooks = new AtomicInteger(0);
    private static final AtomicInteger sSysUiHooks = new AtomicInteger(0);
    private static final ThreadLocal<Boolean> sInSetIcon = new ThreadLocal<>();
    private static final ThreadLocal<Integer> sInternalIconOpDepth = new ThreadLocal<>();
    private static volatile Class<?> sIconViewCls;
    private static volatile Method sGetInvisibleStateMethod;

    private static void enterInternalIconOp() {
        Integer d = sInternalIconOpDepth.get();
        sInternalIconOpDepth.set(d == null ? 1 : (d + 1));
    }

    private static void exitInternalIconOp() {
        Integer d = sInternalIconOpDepth.get();
        if (d == null || d <= 1) {
            sInternalIconOpDepth.set(0);
        } else {
            sInternalIconOpDepth.set(d - 1);
        }
    }

    private static boolean isInInternalIconOp() {
        Integer d = sInternalIconOpDepth.get();
        return d != null && d > 0;
    }

    private static int getInvisibleState(View v) {
        Method m = sGetInvisibleStateMethod;
        Class<?> cls = sIconViewCls;
        if (v == null || m == null || cls == null || !cls.isInstance(v)) return 0;
        try {
            Object res = m.invoke(v);
            if (res instanceof Integer) return (Integer) res;
        } catch (Throwable ignored) {}
        return 0;
    }

    private static boolean isIconHidden(View v, Drawable icon) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0.05f) return true;
        if (icon != null && icon.getAlpha() <= 15) return true;
        return getInvisibleState(v) != 0;
    }

    // Cached preferences (zero stat()/disk syscalls during onDraw or onPreDraw!)
    private static volatile boolean sPrefsInit = false;
    private static volatile boolean sCachedEnabled = true;
    private static volatile boolean sCachedLiquidIcons = true;
    private static volatile boolean sCachedLiveRefraction = true;
    private static volatile boolean sCachedGyroTilt = true;
    private static volatile boolean sCachedGlassFolders = true;
    private static volatile boolean sCachedSysUiGlass = true;
    private static volatile float sCachedFrost = 0.28f;
    private static volatile float sCachedMagStrength = 1.05f;
    private static volatile float sCachedCornerGlow = 0.90f;
    private static volatile int sCachedTintColor = 0;
    private static volatile float sCachedTintStrength = 0f;

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

    private static void syncPrefCache() {
        try {
            XSharedPreferences p = prefs();
            sCachedEnabled = p.getBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true);
            sCachedLiquidIcons = p.getBoolean(ModuleStatus.KEY_LIQUID_ICONS, true);
            sCachedLiveRefraction = p.getBoolean(ModuleStatus.KEY_LIVE_REFRACTION, true);
            sCachedGyroTilt = p.getBoolean(ModuleStatus.KEY_GYRO_TILT, true);
            sCachedGlassFolders = p.getBoolean(ModuleStatus.KEY_GLASS_FOLDERS, true);
            sCachedSysUiGlass = p.getBoolean(ModuleStatus.KEY_SYSTEMUI_GLASS, true);

            int frostPct = p.getInt(ModuleStatus.KEY_FROST_AMOUNT, 28);
            sCachedFrost = Math.max(0f, Math.min(1f, frostPct / 100f));

            int magPct = p.getInt(ModuleStatus.KEY_MAG_AMOUNT, -1);
            if (magPct >= 0) {
                sCachedMagStrength = 0.20f + (Math.max(0, Math.min(100, magPct)) / 100f) * 1.35f;
            } else {
                int lvl = p.getInt(ModuleStatus.KEY_REFRACTION_LEVEL, 1);
                sCachedMagStrength = (lvl == 0) ? 0.65f : ((lvl == 1) ? 1.05f : 1.45f);
            }

            int glowPct = p.getInt(ModuleStatus.KEY_GLOW_AMOUNT, 78);
            sCachedCornerGlow = (Math.max(0, Math.min(100, glowPct)) / 100f) * 1.15f;

            sCachedTintColor = p.getInt(ModuleStatus.KEY_TINT_COLOR, 0);
            int tintPct = p.getInt(ModuleStatus.KEY_TINT_STRENGTH, 0);
            sCachedTintStrength = Math.max(0f, Math.min(1f, tintPct / 100f));

            LiquidGlassRuntime.setFrostLevel(sCachedFrost);
            sPrefsInit = true;
        } catch (Throwable ignored) {}
    }

    private static void applyPrefsFromIntent(Intent intent) {
        if (intent == null) return;
        try {
            if (intent.hasExtra(ModuleStatus.KEY_LSPOSED_ENABLED)) {
                sCachedEnabled = intent.getBooleanExtra(ModuleStatus.KEY_LSPOSED_ENABLED, sCachedEnabled);
            }
            if (intent.hasExtra(ModuleStatus.KEY_LIQUID_ICONS)) {
                sCachedLiquidIcons = intent.getBooleanExtra(ModuleStatus.KEY_LIQUID_ICONS, sCachedLiquidIcons);
                sCachedLiveRefraction = sCachedLiquidIcons;
            }
            if (intent.hasExtra(ModuleStatus.KEY_GYRO_TILT)) {
                sCachedGyroTilt = intent.getBooleanExtra(ModuleStatus.KEY_GYRO_TILT, sCachedGyroTilt);
            }
            if (intent.hasExtra(ModuleStatus.KEY_GLASS_FOLDERS)) {
                sCachedGlassFolders = intent.getBooleanExtra(ModuleStatus.KEY_GLASS_FOLDERS, sCachedGlassFolders);
            }
            if (intent.hasExtra(ModuleStatus.KEY_SYSTEMUI_GLASS)) {
                sCachedSysUiGlass = intent.getBooleanExtra(ModuleStatus.KEY_SYSTEMUI_GLASS, sCachedSysUiGlass);
            }
            if (intent.hasExtra(ModuleStatus.KEY_FROST_AMOUNT)) {
                int frostPct = intent.getIntExtra(ModuleStatus.KEY_FROST_AMOUNT, 28);
                sCachedFrost = Math.max(0f, Math.min(1f, frostPct / 100f));
                LiquidGlassRuntime.setFrostLevel(sCachedFrost);
            }
            if (intent.hasExtra(ModuleStatus.KEY_MAG_AMOUNT)) {
                int magPct = intent.getIntExtra(ModuleStatus.KEY_MAG_AMOUNT, 62);
                sCachedMagStrength = 0.20f + (Math.max(0, Math.min(100, magPct)) / 100f) * 1.35f;
            }
            if (intent.hasExtra(ModuleStatus.KEY_GLOW_AMOUNT)) {
                int glowPct = intent.getIntExtra(ModuleStatus.KEY_GLOW_AMOUNT, 78);
                sCachedCornerGlow = (Math.max(0, Math.min(100, glowPct)) / 100f) * 1.15f;
            }
            if (intent.hasExtra(ModuleStatus.KEY_TINT_COLOR)) {
                sCachedTintColor = intent.getIntExtra(ModuleStatus.KEY_TINT_COLOR, 0);
            }
            if (intent.hasExtra(ModuleStatus.KEY_TINT_STRENGTH)) {
                int tintPct = intent.getIntExtra(ModuleStatus.KEY_TINT_STRENGTH, 0);
                sCachedTintStrength = Math.max(0f, Math.min(1f, tintPct / 100f));
            }
            sPrefsInit = true;
        } catch (Throwable ignored) {}
    }

    private static void reloadPrefs() {
        try {
            prefs().reload();
            syncPrefCache();
        } catch (Throwable ignored) {}
    }

    private static boolean isEnabled() {
        if (!sPrefsInit) syncPrefCache();
        return sCachedEnabled;
    }

    private static boolean opt(String key, boolean def) {
        if (!sPrefsInit) syncPrefCache();
        if (ModuleStatus.KEY_LIQUID_ICONS.equals(key)) return sCachedLiquidIcons;
        if (ModuleStatus.KEY_LIVE_REFRACTION.equals(key)) return sCachedLiveRefraction;
        if (ModuleStatus.KEY_GYRO_TILT.equals(key)) return sCachedGyroTilt;
        if (ModuleStatus.KEY_GLASS_FOLDERS.equals(key)) return sCachedGlassFolders;
        if (ModuleStatus.KEY_SYSTEMUI_GLASS.equals(key)) return sCachedSysUiGlass;
        return def;
    }

    private static float getRefractionStrength() {
        if (!sPrefsInit) syncPrefCache();
        return sCachedMagStrength;
    }

    private static float getFrostLevel() {
        if (!sPrefsInit) syncPrefCache();
        return sCachedFrost;
    }

    private static float getCornerGlowIntensity() {
        if (!sPrefsInit) syncPrefCache();
        return sCachedCornerGlow;
    }

    private static int getTintColor() {
        if (!sPrefsInit) syncPrefCache();
        return sCachedTintColor;
    }

    private static float getTintStrength() {
        if (!sPrefsInit) syncPrefCache();
        return sCachedTintStrength;
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
                "com.droplet.iconpack.ModuleStatus",
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
                    if (!LiquidGlassRuntime.isHomeRecentlyActive()) return;
                    // Normalize gravity tilt cleanly to [-1..1] with smooth 60Hz exponential damping
                    float gx = Math.max(-1f, Math.min(1f, -event.values[0] / 6.5f));
                    float gy = Math.max(-1f, Math.min(1f, (event.values[1] - 4.5f) / 6.5f));
                    sTiltX = sTiltX * 0.72f + gx * 0.28f;
                    sTiltY = sTiltY * 0.72f + gy * 0.28f;

                    // Only invalidate visible on-screen icons when tilt actually moves (zero idle battery drain!)
                    if (Math.abs(sTiltX - sLastNotifiedTiltX) > 0.012f || Math.abs(sTiltY - sLastNotifiedTiltY) > 0.012f) {
                        sLastNotifiedTiltX = sTiltX;
                        sLastNotifiedTiltY = sTiltY;
                        invalidateVisibleIcons();
                    }
                }
                @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            }, s, SensorManager.SENSOR_DELAY_GAME);
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
                        // 1. Signature-permission-gated command receiver (only Droplet itself can send config/ping!)
                        IntentFilter cmdFilter = new IntentFilter();
                        cmdFilter.addAction(pingAction);
                        cmdFilter.addAction(ModuleStatus.ACTION_RELOAD_PREFS);
                        BroadcastReceiver cmdReceiver = new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                String act = intent != null ? intent.getAction() : null;
                                if (ModuleStatus.ACTION_RELOAD_PREFS.equals(act)) {
                                    reloadPrefs();
                                    applyPrefsFromIntent(intent);
                                    reapplyAllIconDrawables();
                                    invalidateVisibleIcons();
                                } else if (pingAction.equals(act)) {
                                    String nonce = intent.getStringExtra(ModuleStatus.EXTRA_NONCE);
                                    if (nonce == null || nonce.isEmpty()) return;
                                    reloadPrefs();
                                    Intent pong = new Intent(ModuleStatus.ACTION_PONG);
                                    pong.setPackage(ModuleStatus.PKG);
                                    pong.putExtra(ModuleStatus.EXTRA_NONCE, nonce);
                                    pong.putExtra("target", targetName);
                                    pong.putExtra("hooks", hookCounter.get());
                                    pong.putExtra("enabled", isEnabled());
                                    pong.putExtra("oneui", ModuleStatus.getOneUiVersionName());
                                    context.sendBroadcast(pong);
                                }
                            }
                        };
                        if (Build.VERSION.SDK_INT >= 33) {
                            app.registerReceiver(cmdReceiver, cmdFilter,
                                    ModuleStatus.PERM_CONTROL_BROADCAST, null, Context.RECEIVER_EXPORTED);
                        } else {
                            app.registerReceiver(cmdReceiver, cmdFilter,
                                    ModuleStatus.PERM_CONTROL_BROADCAST, null);
                        }

                        // 2. Separate system-event receiver for wallpaper changes (system_server doesn't hold Droplet's signature perm)
                        if (listenWallpaper) {
                            IntentFilter wallFilter = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
                            BroadcastReceiver wallReceiver = new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context context, Intent intent) {
                                    if (intent != null && Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())) {
                                        LiquidGlassRuntime.invalidateWallpaperCache();
                                        invalidateVisibleIcons();
                                    }
                                }
                            };
                            if (Build.VERSION.SDK_INT >= 33) {
                                app.registerReceiver(wallReceiver, wallFilter, Context.RECEIVER_EXPORTED);
                            } else {
                                app.registerReceiver(wallReceiver, wallFilter);
                            }
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

    /**
     * Detects whether an IconViewImpl lives inside One UI's blurred App Drawer, Open Folder,
     * Overlay Apps, or Suggested Apps container (cached once per View -> O(1) at draw time!).
     */
    private static boolean isInBlurredContainer(View v) {
        if (v == null) return false;
        ViewParent curParent = v.getParent();
        Boolean cached = sDrawerIconCache.get(v);
        if (cached != null && sDrawerParentCache.get(v) == curParent) return cached;
        boolean blurred = false;
        ViewParent p = curParent;
        int depth = 0;
        while (p != null && depth < 7) {
            String cn = p.getClass().getName().toLowerCase(Locale.ROOT);
            if (cn.contains("applist") || cn.contains("appscreen") || cn.contains("overlayapps")
                    || cn.contains("openfolder") || cn.contains("suggestedapps") || cn.contains("appsedge")) {
                blurred = true;
                break;
            }
            if (cn.contains("workspace") || cn.contains("hotseat")) {
                blurred = false;
                break;
            }
            p = p.getParent();
            depth++;
        }
        sDrawerIconCache.put(v, blurred);
        sDrawerParentCache.put(v, curParent);
        return blurred;
    }

    private static void computeViewScale(View v, float[] outScale) {
        float sx = 1.0f;
        float sy = 1.0f;
        if (v != null) {
            sx *= v.getScaleX();
            sy *= v.getScaleY();
            ViewParent p = v.getParent();
            while (p instanceof View) {
                View pv = (View) p;
                sx *= pv.getScaleX();
                sy *= pv.getScaleY();
                p = pv.getParent();
            }
        }
        outScale[0] = (sx > 0.1f && sx < 4.0f) ? sx : 1.0f;
        outScale[1] = (sy > 0.1f && sy < 4.0f) ? sy : 1.0f;
    }

    private static boolean isViewOnScreen(View v, int[] tmp) {
        if (v == null || !v.isAttachedToWindow() || !v.isShown() || v.getAlpha() <= 0.01f) return false;
        v.getLocationOnScreen(tmp);
        int sw = v.getResources().getDisplayMetrics().widthPixels;
        int sh = v.getResources().getDisplayMetrics().heightPixels;
        return tmp[0] > -240 && tmp[0] < sw + 240 && tmp[1] > -240 && tmp[1] < sh + 240;
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
                    final float[] scTmp = new float[2];
                    root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            if (!isEnabled() || !opt(ModuleStatus.KEY_LIQUID_ICONS, true)) return true;
                            boolean anyMoved = false;
                            synchronized (sActiveIcons) {
                                for (View iv : sActiveIcons) {
                                    if (!isViewOnScreen(iv, tmp)) continue;
                                    computeViewScale(iv, scTmp);
                                    int scaleQ = Math.round(scTmp[0] * 500f);
                                    long packed = (((long) tmp[0]) << 36) ^ (((long) (tmp[1] & 0xFFFFF)) << 16) ^ (scaleQ & 0xFFFFL);
                                    long[] prev = sLastIconPos.get(iv);
                                    if (prev == null) {
                                        sLastIconPos.put(iv, new long[] { packed });
                                    } else if (prev[0] != packed) {
                                        prev[0] = packed;
                                        iv.invalidate();
                                        anyMoved = true;
                                    }
                                }
                            }
                            if (anyMoved) {
                                LiquidGlassRuntime.noteScrollMotion();
                            }
                            return true;
                        }
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void invalidateVisibleIcons() {
        Looper ml = Looper.getMainLooper();
        if (ml != null && Looper.myLooper() == ml) {
            invalidateVisibleIconsOnUiThread();
        } else {
            Handler h = getMainHandler();
            if (h != null) {
                h.removeCallbacks(sInvalidateRunnable);
                h.post(sInvalidateRunnable);
            }
        }
    }

    private static void invalidateVisibleIconsOnUiThread() {
        final int[] tmp = new int[2];
        synchronized (sActiveIcons) {
            for (View v : sActiveIcons) {
                if (isViewOnScreen(v, tmp)) {
                    v.postInvalidateOnAnimation();
                }
            }
        }
        synchronized (sAllIconViews) {
            for (View v : sAllIconViews) {
                if (isViewOnScreen(v, tmp)) {
                    v.postInvalidateOnAnimation();
                }
            }
        }
    }

    private static void applyIconSafe(View v, Drawable d) {
        if (v == null || d == null) return;
        try {
            sInSetIcon.set(Boolean.TRUE);
            enterInternalIconOp();
            Drawable cur = (v instanceof TextView) ? getIconFromView((TextView) v) : null;
            if (cur != null && !cur.getBounds().isEmpty()) {
                d.setBounds(cur.getBounds());
            }
            try {
                XposedHelpers.callMethod(v, "setIcon", d);
            } catch (Throwable t) {
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    Drawable[] cds = tv.getCompoundDrawables();
                    tv.setCompoundDrawables(cds != null ? cds[0] : null, d,
                            cds != null ? cds[2] : null, cds != null ? cds[3] : null);
                }
            }
            v.invalidate();
        } catch (Throwable ignored) {
        } finally {
            exitInternalIconOp();
            sInSetIcon.set(Boolean.FALSE);
        }
    }

    /**
     * Immediately restores every icon back to its exact original stock Drawable when Liquid Glass
     * is toggled OFF (zero invisible/broken icons without Theme Park!), and seamlessly re-applies
     * Liquid Glass PureGlyphDrawables when toggled back ON — without restarting One UI Home!
     */
    private static void reapplyAllIconDrawables() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                boolean wantLiquid = isEnabled() && opt(ModuleStatus.KEY_LIQUID_ICONS, true);
                synchronized (sLazyCheckedIcons) {
                    sLazyCheckedIcons.clear();
                }
                List<View> views;
                synchronized (sAllIconViews) {
                    views = new ArrayList<>(sAllIconViews);
                }
                for (View v : views) {
                    if (v == null || !(v instanceof TextView)) continue;
                    if (v.getClass().getSimpleName().contains("Folder")) continue;
                    Drawable cur = getIconFromView((TextView) v);
                    Drawable orig;
                    synchronized (sOriginalIcons) {
                        orig = sOriginalIcons.get(v);
                    }
                    if (!wantLiquid) {
                        synchronized (sCompleteGlassBitmaps) {
                            sCompleteGlassBitmaps.remove(v);
                        }
                        if (cur instanceof LiquidGlassRuntime.PureGlyphDrawable && orig != null) {
                            applyIconSafe(v, orig);
                        }
                    } else {
                        if (!(cur instanceof LiquidGlassRuntime.PureGlyphDrawable)) {
                            Drawable src = (orig != null) ? orig : cur;
                            if (src != null) {
                                ComponentName cn = extractComponent(v);
                                String lbl = extractLabel(v);
                                int sz = Math.max(src.getIntrinsicWidth(), 192);
                                Bitmap[] fullOut = new Bitmap[1];
                                Drawable themed = prepareGlyphDrawable(v.getContext(), cn, lbl, src, sz, true, fullOut);
                                if (themed != null) {
                                    if (themed instanceof LiquidGlassRuntime.PureGlyphDrawable && fullOut[0] != null) {
                                        synchronized (sCompleteGlassBitmaps) {
                                            sCompleteGlassBitmaps.put(v, fullOut[0]);
                                        }
                                    } else {
                                        synchronized (sCompleteGlassBitmaps) {
                                            sCompleteGlassBitmaps.remove(v);
                                        }
                                    }
                                    applyIconSafe(v, themed);
                                } else {
                                    synchronized (sCompleteGlassBitmaps) {
                                        sCompleteGlassBitmaps.remove(v);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        };
        Looper ml = Looper.getMainLooper();
        if (ml != null && Looper.myLooper() == ml) {
            r.run();
        } else {
            Handler h = getMainHandler();
            if (h != null) h.post(r);
        }
    }

    private static String normalizeLabelKey(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toLowerCase(raw.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    private static synchronized void ensureIconPackLoaded(Context hostCtx) {
        if (sModuleRes != null && sCompMap != null) return;
        try {
            Context modCtx = hostCtx.createPackageContext(ModuleStatus.PKG, Context.CONTEXT_IGNORE_SECURITY);
            Resources res = modCtx.getResources();
            Map<String, String> compMap = new ConcurrentHashMap<>();
            Map<String, String> pkgMap = new ConcurrentHashMap<>();
            Map<String, String> labelMap = new ConcurrentHashMap<>();
            Map<String, ComponentName> labelCompMap = new ConcurrentHashMap<>();

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
                    ComponentName cnObj = new ComponentName(p, c);
                    CharSequence lbl = ri.loadLabel(pm);
                    String key = null;
                    String normKey = null;
                    if (lbl != null && lbl.length() > 0) {
                        key = lbl.toString().trim().toLowerCase(Locale.ROOT);
                        if (!labelCompMap.containsKey(key)) labelCompMap.put(key, cnObj);
                        normKey = normalizeLabelKey(key);
                        if (normKey.length() >= 3 && !labelCompMap.containsKey(normKey)) labelCompMap.put(normKey, cnObj);
                    }
                    String draw = compMap.get(p + "/" + c);
                    if (draw == null) draw = pkgMap.get(p);
                    if (draw != null && key != null) {
                        if (!labelMap.containsKey(key)) labelMap.put(key, draw);
                        if (normKey != null && normKey.length() >= 3 && !labelMap.containsKey(normKey)) labelMap.put(normKey, draw);
                    }
                }
            } catch (Throwable ignored) {}

            sModuleRes = res;
            sCompMap = compMap;
            sPkgMap = pkgMap;
            sLabelToDrawableMap = labelMap;
            sLabelToComponentMap = labelCompMap;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed loading Droplet resources: " + t);
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
     * Also outputs the complete static Liquid Glass Bitmap via outFullBmp[0] so FloatingAnimator
     * renders a complete Liquid Glass icon during app-open/app-close fly animations!
     */
    private static Drawable prepareGlyphDrawable(Context ctx, ComponentName cn, String labelKey, Drawable orig,
                                                 int iconPx, boolean liveRefraction, Bitmap[] outFullBmp) {
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
                if (drawName == null) {
                    String normKey = normalizeLabelKey(labelKey);
                    if (normKey.length() >= 3) drawName = sLabelToDrawableMap.get(normKey);
                }
            }
            if (drawName != null) {
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
                if (outFullBmp != null && outFullBmp.length > 0) {
                    outFullBmp[0] = full;
                }
                if (liveRefraction) {
                    Bitmap pure = sPureGlyphCache.get(drawName);
                    if (pure == null && full != null) {
                        // Full-bleed icons (Notes, Maps, Clock) look best kept mostly intact; standard icons strip static plate!
                        if ("ios_notes".equals(drawName) || "ios_maps".equals(drawName) || "ios_clock".equals(drawName)) {
                            pure = full;
                        } else {
                            pure = LiquidGlassRuntime.extractPureGlyphFromDroplet(full);
                        }
                        if (pure != null) sPureGlyphCache.put(drawName, pure);
                    }
                    if (pure != null) return new LiquidGlassRuntime.PureGlyphDrawable(ctx.getResources(), pure);
                } else {
                    if (full != null) return new LiquidGlassRuntime.PureGlyphDrawable(ctx.getResources(), full);
                }
            }
        }

        // If DragController / FloatingAnimator passes our CompleteGlassDrawable back into setIcon,
        // immediately restore live PureGlyphDrawable refraction!
        if (liveRefraction && orig instanceof LiquidGlassRuntime.CompleteGlassDrawable) {
            Bitmap origBmp = ((BitmapDrawable) orig).getBitmap();
            if (origBmp != null && !origBmp.isRecycled()) {
                if (outFullBmp != null && outFullBmp.length > 0) {
                    outFullBmp[0] = origBmp;
                }
                Bitmap pure = LiquidGlassRuntime.extractPureGlyphFromDroplet(origBmp);
                if (pure != null) return new LiquidGlassRuntime.PureGlyphDrawable(ctx.getResources(), pure);
            }
        }

        // If Theme Park Droplet is active and live refraction is ON:
        // - If the icon has a genuine non-empty glyph (e.g. a themed icon whose label/component wasn't bound yet),
        //   extractPureGlyphFromDroplet returns a crisp PureGlyphDrawable (never blank!).
        // - If it's an unrecognised app mangled/erased by an older Theme Park fallback mask (extractPureGlyphFromDroplet == null),
        //   automatically load its genuine untouched stock icon straight from the app's own APK resources!
        if (liveRefraction && orig instanceof BitmapDrawable && ((BitmapDrawable) orig).getBitmap() != null
                && LiquidGlassRuntime.isAlreadyTranslucentGlass(orig)) {
            Bitmap origBmp = ((BitmapDrawable) orig).getBitmap();
            Bitmap pure = LiquidGlassRuntime.extractPureGlyphFromDroplet(origBmp);
            if (pure != null) {
                if (outFullBmp != null && outFullBmp.length > 0) {
                    outFullBmp[0] = origBmp;
                }
                return new LiquidGlassRuntime.PureGlyphDrawable(ctx.getResources(), pure);
            }
            // Mangled/blank Theme Park fallback for an unrecognised app -> restore real crisp stock icon!
            Drawable stock = loadGenuineStockIcon(ctx, cn, labelKey);
            if (stock != null) return stock;
        }

        // Unrecognised app not covered by Droplet -> return null so the regular stock icon stays 100% intact & visible!
        return null;
    }

    /**
     * Loads an app's genuine untouched stock icon directly from its own APK resources,
     * bypassing any broken Samsung Theme Park fallback overlay for unrecognised apps.
     */
    private static Drawable loadGenuineStockIcon(Context ctx, ComponentName cn, String labelKey) {
        if (ctx == null) return null;
        ComponentName target = cn;
        if (target == null && labelKey != null && sLabelToComponentMap != null) {
            target = sLabelToComponentMap.get(labelKey);
            if (target == null) {
                String normKey = normalizeLabelKey(labelKey);
                if (normKey.length() >= 3) target = sLabelToComponentMap.get(normKey);
            }
        }
        if (target == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            int iconRes = 0;
            try {
                iconRes = pm.getActivityInfo(target, 0).getIconResource();
            } catch (Throwable ignored) {}
            if (iconRes == 0) {
                iconRes = pm.getApplicationInfo(target.getPackageName(), 0).icon;
            }
            if (iconRes != 0) {
                Resources appRes = pm.getResourcesForApplication(target.getPackageName());
                int dpi = ctx.getResources().getDisplayMetrics().densityDpi;
                return appRes.getDrawableForDensity(iconRes, dpi, null);
            }
        } catch (Throwable ignored) {}
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

        // Track One UI Home's exact live wallpaper scroll offset (mWallpaperX/Y) for 1:1 parallax alignment!
        try {
            XposedBridge.hookAllMethods(WallpaperManager.class, "setWallpaperOffsets", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length >= 3
                            && param.args[1] instanceof Float && param.args[2] instanceof Float) {
                        LiquidGlassRuntime.setWallpaperOffsets((Float) param.args[1], (Float) param.args[2]);
                    }
                }
            });
        } catch (Throwable ignored) {}

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
            sIconViewCls = iconViewCls;
            try {
                sGetInvisibleStateMethod = iconViewCls.getMethod("getInvisibleState");
            } catch (Throwable ignored) {}

            // Guard internal IconViewImpl ops so getIcon() returns the real PureGlyphDrawable internally,
            // while external callers like FloatingAnimator receive the complete Liquid Glass icon!
            XC_MethodHook internalOpGuard = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    enterInternalIconOp();
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    exitInternalIconOp();
                }
            };
            for (String mName : new String[] {
                    "setIconIntoPosition", "clearIconColorFilter", "clear",
                    "getFolderTransformAnim", "getOutlineBitmap",
                    "onLayout", "onMeasure", "setItemStyle", "setIconState",
                    "onStartDragAnimation", "onEndDragAnimation"
            }) {
                try {
                    XposedBridge.hookAllMethods(iconViewCls, mName, internalOpGuard);
                } catch (Throwable ignored) {}
            }

            // Invalidate immediately when FloatingAnimator toggles stationary icon visibility on app launch/close!
            try {
                XposedBridge.hookAllMethods(iconViewCls, "setIconVisible", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        enterInternalIconOp();
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        exitInternalIconOp();
                        if (param.thisObject instanceof View) {
                            ((View) param.thisObject).invalidate();
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Return complete Liquid Glass icon to external callers (e.g. FloatingAnimator fly-home icon!)
            try {
                XposedBridge.hookAllMethods(iconViewCls, "getIcon", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || !opt(ModuleStatus.KEY_LIQUID_ICONS, true)) return;
                        if (isInInternalIconOp() || Boolean.TRUE.equals(sInSetIcon.get())) return;
                        if (!(param.thisObject instanceof View)) return;
                        Object res = param.getResult();
                        if (!(res instanceof LiquidGlassRuntime.PureGlyphDrawable)) return;
                        View v = (View) param.thisObject;
                        Bitmap fullBmp;
                        synchronized (sCompleteGlassBitmaps) {
                            fullBmp = sCompleteGlassBitmaps.get(v);
                        }
                        if (fullBmp == null || fullBmp.isRecycled()) return;
                        Drawable cur = (Drawable) res;
                        LiquidGlassRuntime.CompleteGlassDrawable fullDrawable =
                                new LiquidGlassRuntime.CompleteGlassDrawable(v.getResources(), fullBmp);
                        fullDrawable.setBounds(cur.getBounds());
                        fullDrawable.setAlpha(cur.getAlpha());
                        param.setResult(fullDrawable);
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "IconViewImpl.getIcon hook err: " + t);
            }

            // 1. Always save original stock Drawable (so toggling OFF instantly restores stock icons!),
            //    and substitute pure floating frosted glyph when Liquid Glass is ON!
            try {
                XposedBridge.hookAllMethods(iconViewCls, "setIcon", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(sInSetIcon.get())) return;
                        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Drawable)) return;
                        if (param.thisObject.getClass().getSimpleName().contains("Folder")) return;

                        View v = (View) param.thisObject;
                        Drawable orig = (Drawable) param.args[0];
                        synchronized (sAllIconViews) {
                            sAllIconViews.add(v);
                        }
                        synchronized (sLazyCheckedIcons) {
                            sLazyCheckedIcons.remove(v);
                        }
                        if (!(orig instanceof LiquidGlassRuntime.PureGlyphDrawable)
                                && !(orig instanceof LiquidGlassRuntime.CompleteGlassDrawable)) {
                            synchronized (sOriginalIcons) {
                                sOriginalIcons.put(v, orig);
                            }
                        }

                        if (!isEnabled() || !opt(ModuleStatus.KEY_LIQUID_ICONS, true)) {
                            synchronized (sCompleteGlassBitmaps) {
                                sCompleteGlassBitmaps.remove(v);
                            }
                            return;
                        }

                        try {
                            sInSetIcon.set(Boolean.TRUE);
                            enterInternalIconOp();
                            ComponentName cn = extractComponent(param.thisObject);
                            String lbl = extractLabel(param.thisObject);
                            int sz = Math.max(orig.getIntrinsicWidth(), 192);
                            Bitmap[] fullOut = new Bitmap[1];
                            Drawable themed = prepareGlyphDrawable(v.getContext(), cn, lbl, orig, sz, true, fullOut);
                            if (themed != null) {
                                if (themed instanceof LiquidGlassRuntime.PureGlyphDrawable && fullOut[0] != null) {
                                    synchronized (sCompleteGlassBitmaps) {
                                        sCompleteGlassBitmaps.put(v, fullOut[0]);
                                    }
                                } else {
                                    synchronized (sCompleteGlassBitmaps) {
                                        sCompleteGlassBitmaps.remove(v);
                                    }
                                    synchronized (sOriginalIcons) {
                                        sOriginalIcons.put(v, themed);
                                    }
                                }
                                themed.setBounds(orig.getBounds());
                                param.args[0] = themed;
                            } else {
                                synchronized (sCompleteGlassBitmaps) {
                                    sCompleteGlassBitmaps.remove(v);
                                }
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            exitInternalIconOp();
                            sInSetIcon.set(Boolean.FALSE);
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "IconViewImpl.setIcon hook err: " + t);
            }

            // 2. LIVE 120fps AGSL Refracting Glass Droplet (BEFORE glyph) + Retina-Smooth Corner Rim (AFTER glyph)!
            try {
                XposedBridge.hookAllMethods(iconViewCls, "onDraw", new XC_MethodHook() {
                    private final int[] loc = new int[2];
                    private final float[] viewScale = new float[2];

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        enterInternalIconOp();
                        if (!isEnabled() || !opt(ModuleStatus.KEY_LIQUID_ICONS, true)) return;
                        if (!(param.thisObject instanceof TextView) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        if (param.thisObject.getClass().getSimpleName().contains("Folder")) return;
                        try {
                            TextView tv = (TextView) param.thisObject;
                            Drawable icon = getIconFromView(tv);
                            // Lazy recovery: if HoneySpace bound setIcon before tag/label (e.g. after drag-and-drop),
                            // or passed a static CompleteGlassDrawable, re-apply live refraction once!
                            if (icon != null && !(icon instanceof LiquidGlassRuntime.PureGlyphDrawable)) {
                                Drawable prevChecked;
                                synchronized (sLazyCheckedIcons) {
                                    prevChecked = sLazyCheckedIcons.get(tv);
                                }
                                if (prevChecked != icon) {
                                    ComponentName cn = extractComponent(tv);
                                    String lbl = extractLabel(tv);
                                    if (cn != null || lbl != null || icon instanceof LiquidGlassRuntime.CompleteGlassDrawable) {
                                        synchronized (sLazyCheckedIcons) {
                                            sLazyCheckedIcons.put(tv, icon);
                                        }
                                        Bitmap[] fullOut = new Bitmap[1];
                                        Drawable themed = prepareGlyphDrawable(
                                                tv.getContext(), cn, lbl, icon,
                                                Math.max(icon.getIntrinsicWidth(), 192), true, fullOut);
                                        if (themed != null) {
                                            if (themed instanceof LiquidGlassRuntime.PureGlyphDrawable && fullOut[0] != null) {
                                                synchronized (sCompleteGlassBitmaps) {
                                                    sCompleteGlassBitmaps.put(tv, fullOut[0]);
                                                }
                                            } else {
                                                synchronized (sCompleteGlassBitmaps) {
                                                    sCompleteGlassBitmaps.remove(tv);
                                                }
                                                synchronized (sOriginalIcons) {
                                                    sOriginalIcons.put(tv, themed);
                                                }
                                                synchronized (sLazyCheckedIcons) {
                                                    sLazyCheckedIcons.put(tv, themed);
                                                }
                                            }
                                            applyIconSafe(tv, themed);
                                            icon = themed;
                                        }
                                    }
                                }
                            }
                            // Leave unrecognised stock icons untouched, and skip hidden slots during FloatingAnimator app-close!
                            if (!(icon instanceof LiquidGlassRuntime.PureGlyphDrawable)) return;
                            if (isIconHidden(tv, icon)) return;
                            registerActiveIcon(tv);
                            Canvas canvas = (Canvas) param.args[0];
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
                            computeViewScale(tv, viewScale);
                            if (Math.abs(viewScale[0] - 1.0f) > 0.005f || Math.abs(viewScale[1] - 1.0f) > 0.005f) {
                                tv.postInvalidateOnAnimation();
                            }
                            float screenX = loc[0] + left * viewScale[0];
                            float screenY = loc[1] + top * viewScale[1];

                            float tx = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltX : 0f;
                            float ty = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltY : 0f;
                            int sw = Math.max(tv.getResources().getDisplayMetrics().widthPixels, 1080);
                            float scrollShift = ((screenX + boxW * viewScale[0] * 0.5f) / (float) sw) * 2.0f - 1.0f;

                            LiquidGlassRuntime.drawLiveLiquidBody(
                                    tv.getContext(), tv, canvas, left, top, boxW, boxH,
                                    screenX, screenY,
                                    viewScale[0], viewScale[1],
                                    tx + scrollShift * 0.16f, ty,
                                    getRefractionStrength(),
                                    getFrostLevel(),
                                    isInBlurredContainer(tv),
                                    getTintColor(),
                                    getTintStrength(),
                                    icon.getAlpha()
                            );
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!isEnabled() || !opt(ModuleStatus.KEY_LIQUID_ICONS, true)) return;
                            if (!(param.thisObject instanceof TextView) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                            if (param.thisObject.getClass().getSimpleName().contains("Folder")) return;
                            TextView tv = (TextView) param.thisObject;
                            Drawable icon = getIconFromView(tv);
                            if (!(icon instanceof LiquidGlassRuntime.PureGlyphDrawable)) return;
                            if (isIconHidden(tv, icon)) return;
                            Canvas canvas = (Canvas) param.args[0];
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
                            float tx = (opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltX : 0f) + scrollShift * 0.22f;
                            float ty = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltY : 0f;

                            // Authentic iOS 26 retina-smooth corner-originating specular rim glow ON TOP!
                            float rimIntensity = getCornerGlowIntensity() * (isInBlurredContainer(tv) ? 0.90f : 1.0f);
                            LiquidGlassRuntime.drawLiveGlazeRim(canvas, left, top, boxW, boxH, tx, ty, false, rimIntensity, icon.getAlpha());
                        } catch (Throwable ignored) {
                        } finally {
                            exitInternalIconOp();
                        }
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
                    private final float[] viewScale = new float[2];

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || !opt(ModuleStatus.KEY_GLASS_FOLDERS, true)) return;
                        if (!(param.thisObject instanceof View) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        try {
                            View v = (View) param.thisObject;
                            Drawable icon = (v instanceof TextView) ? getIconFromView((TextView) v) : null;
                            if (isIconHidden(v, icon)) return;
                            registerActiveIcon(v);
                            Canvas c = (Canvas) param.args[0];
                            int iw = (icon != null && icon.getBounds().width() >= 32)
                                    ? icon.getBounds().width() : Math.round(Math.min(v.getWidth(), v.getHeight()) * 0.72f);
                            int ih = (icon != null && icon.getBounds().height() >= 32) ? icon.getBounds().height() : iw;
                            float insetX = iw * 0.0195f;
                            float insetY = ih * 0.0195f;
                            float left = (v.getWidth() - iw) * 0.5f + insetX;
                            float top = v.getPaddingTop() + insetY;
                            v.getLocationOnScreen(loc);
                            computeViewScale(v, viewScale);
                            if (Math.abs(viewScale[0] - 1.0f) > 0.005f || Math.abs(viewScale[1] - 1.0f) > 0.005f) {
                                v.postInvalidateOnAnimation();
                            }
                            float tx = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltX : 0f;
                            float ty = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltY : 0f;
                            int alpha255 = (icon != null) ? icon.getAlpha() : 255;
                            LiquidGlassRuntime.drawLiveLiquidBody(
                                    v.getContext(), v, c, left, top, iw - 2f * insetX, ih - 2f * insetY,
                                    loc[0] + left * viewScale[0], loc[1] + top * viewScale[1],
                                    viewScale[0], viewScale[1], tx, ty,
                                    getRefractionStrength(), getFrostLevel(), isInBlurredContainer(v),
                                    getTintColor(), getTintStrength(), alpha255);
                        } catch (Throwable ignored) {}
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || !opt(ModuleStatus.KEY_GLASS_FOLDERS, true)) return;
                        if (!(param.thisObject instanceof View) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        try {
                            View v = (View) param.thisObject;
                            Drawable icon = (v instanceof TextView) ? getIconFromView((TextView) v) : null;
                            if (isIconHidden(v, icon)) return;
                            Canvas c = (Canvas) param.args[0];
                            int iw = (icon != null && icon.getBounds().width() >= 32)
                                    ? icon.getBounds().width() : Math.round(Math.min(v.getWidth(), v.getHeight()) * 0.72f);
                            int ih = (icon != null && icon.getBounds().height() >= 32) ? icon.getBounds().height() : iw;
                            float insetX = iw * 0.0195f;
                            float insetY = ih * 0.0195f;
                            float left = (v.getWidth() - iw) * 0.5f + insetX;
                            float top = v.getPaddingTop() + insetY;
                            float tx = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltX : 0f;
                            float ty = opt(ModuleStatus.KEY_GYRO_TILT, true) ? sTiltY : 0f;
                            float rimIntensity = getCornerGlowIntensity() * (isInBlurredContainer(v) ? 0.90f : 1.0f);
                            int alpha255 = (icon != null) ? icon.getAlpha() : 255;
                            LiquidGlassRuntime.drawLiveGlazeRim(c, left, top, iw - 2f * insetX, ih - 2f * insetY, tx, ty, false, rimIntensity, alpha255);
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "FolderIconViewImpl hook err: " + t);
            }
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
                        LiquidGlassRuntime.drawLiveGlazeRim((Canvas) param.args[0], 0f, 0f, v.getWidth(), v.getHeight(), 0f, 0f, true, 0.45f * getCornerGlowIntensity());
                    }
                });
            } catch (Throwable ignored) {}
        }
    }
}
