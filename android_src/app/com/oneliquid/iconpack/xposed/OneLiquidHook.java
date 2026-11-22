package com.oneliquid.iconpack.xposed;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.widget.TextView;
import com.oneliquid.iconpack.ModuleStatus;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.xmlpull.v1.XmlPullParser;

/**
 * OneLiquid LSPosed Module for Samsung One UI 8 / 8.5 / 9+ (HoneySpace & SystemUI).
 *
 * Scopes:
 *   - com.sec.android.app.launcher (One UI Home)
 *   - com.android.systemui         (SystemUI)
 */
public final class OneLiquidHook implements IXposedHookLoadPackage {

    private static final String TAG = "OneLiquid-LSPosed: ";
    private static volatile XSharedPreferences sPrefs;
    private static volatile Resources sModuleRes;
    private static volatile Map<String, String> sCompMap;
    private static volatile Map<String, String> sPkgMap;
    private static final Map<String, Bitmap> sIconCache = new ConcurrentHashMap<>();
    private static final AtomicInteger sHomeHooks = new AtomicInteger(0);
    private static final AtomicInteger sSysUiHooks = new AtomicInteger(0);
    private static final ThreadLocal<Boolean> sInSetIcon = new ThreadLocal<>();

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

    private void registerStatusReceiver(ClassLoader cl, final String pingAction, final String targetName, final AtomicInteger hookCounter) {
        try {
            XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final Application app = (Application) param.thisObject;
                        IntentFilter f = new IntentFilter();
                        f.addAction(pingAction);
                        f.addAction(ModuleStatus.ACTION_RELOAD_PREFS);
                        BroadcastReceiver br = new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                String act = intent != null ? intent.getAction() : null;
                                if (ModuleStatus.ACTION_RELOAD_PREFS.equals(act)) {
                                    reloadPrefs();
                                    sIconCache.clear();
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

    private static synchronized void ensureIconPackLoaded(Context hostCtx) {
        if (sModuleRes != null && sCompMap != null) return;
        try {
            Context modCtx = hostCtx.createPackageContext(ModuleStatus.PKG, Context.CONTEXT_IGNORE_SECURITY);
            Resources res = modCtx.getResources();
            Map<String, String> compMap = new ConcurrentHashMap<>();
            Map<String, String> pkgMap = new ConcurrentHashMap<>();

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
            sModuleRes = res;
            sCompMap = compMap;
            sPkgMap = pkgMap;
            XposedBridge.log(TAG + "Loaded " + compMap.size() + " component mappings & " + pkgMap.size() + " packages");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed loading OneLiquid resources: " + t);
        }
    }

    /**
     * Safely resolves the ComponentName/package of a HoneySpace IconViewImpl across One UI 8 / 8.5 / 9.
     */
    private static ComponentName extractComponent(Object iconView) {
        if (iconView == null) return null;
        try {
            // Check View tag (HoneySpace commonly stores IconItem / AppItem in tag or fields)
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

    private static ComponentName extractComponentFromObject(Object obj, int depth) {
        if (obj == null || depth > 2) return null;
        if (obj instanceof ComponentName) return (ComponentName) obj;
        if (obj instanceof Intent) return ((Intent) obj).getComponent();
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                String n = f.getName().toLowerCase();
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

    private static Drawable loadOneLiquidDrawable(Context ctx, ComponentName cn, Drawable fallbackOriginal, int iconPx) {
        ensureIconPackLoaded(ctx);
        Resources res = sModuleRes;
        if (res != null && cn != null) {
            String fullKey = cn.getPackageName() + "/" + cn.getClassName();
            String drawName = sCompMap != null ? sCompMap.get(fullKey) : null;
            if (drawName == null && sPkgMap != null) {
                drawName = sPkgMap.get(cn.getPackageName());
            }
            if (drawName != null) {
                Bitmap cached = sIconCache.get(drawName);
                if (cached == null) {
                    int id = res.getIdentifier(drawName, "drawable", ModuleStatus.PKG);
                    if (id != 0) {
                        Drawable d = res.getDrawable(id, null);
                        if (d instanceof BitmapDrawable) {
                            cached = ((BitmapDrawable) d).getBitmap();
                            if (cached != null) sIconCache.put(drawName, cached);
                        }
                    }
                }
                if (cached != null) {
                    return new BitmapDrawable(ctx.getResources(), cached);
                }
            }
        }
        // Fallback: dynamically convert any unknown app into 1:1 iOS 26 Clear Liquid Glass!
        if (fallbackOriginal != null) {
            Bitmap glassBmp = LiquidGlassRuntime.convertDrawableToLiquidGlass(fallbackOriginal, iconPx > 0 ? iconPx : 192);
            if (glassBmp != null) {
                return new BitmapDrawable(ctx.getResources(), glassBmp);
            }
        }
        return null;
    }

    private void hookOneUiHome(ClassLoader cl) {
        XposedBridge.log(TAG + "Hooking One UI Home (" + ModuleStatus.getOneUiVersionName() + ")");
        registerStatusReceiver(cl, ModuleStatus.ACTION_PING_HOME, "launcher", sHomeHooks);

        // 1. Hook HoneySpace IconViewImpl (One UI 7 / 8 / 8.5 / 9)
        Class<?> iconViewCls = XposedHelpers.findClassIfExists("com.honeyspace.ui.common.iconview.IconViewImpl", cl);
        if (iconViewCls != null) {
            sHomeHooks.incrementAndGet();
            try {
                XposedBridge.hookAllMethods(iconViewCls, "setIcon", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(sInSetIcon.get())) return;
                        if (!isEnabled() || !opt(ModuleStatus.KEY_LIQUID_ICONS, true)) return;
                        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Drawable)) return;
                        // Skip folder icons here; FolderIconViewImpl handles folders separately
                        if (param.thisObject.getClass().getSimpleName().contains("Folder")) return;

                        try {
                            sInSetIcon.set(Boolean.TRUE);
                            View v = (View) param.thisObject;
                            Drawable orig = (Drawable) param.args[0];
                            ComponentName cn = extractComponent(param.thisObject);
                            int sz = Math.max(orig.getIntrinsicWidth(), 192);
                            Drawable themed = loadOneLiquidDrawable(v.getContext(), cn, orig, sz);
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

            // Live scroll-reactive AGSL Fresnel specular refraction shimmer on every icon!
            try {
                XposedBridge.hookAllMethods(iconViewCls, "onDraw", new XC_MethodHook() {
                    private final int[] loc = new int[2];
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || !opt(ModuleStatus.KEY_LIVE_REFRACTION, true)) return;
                        if (!(param.thisObject instanceof TextView) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        try {
                            TextView tv = (TextView) param.thisObject;
                            Canvas canvas = (Canvas) param.args[0];
                            Drawable icon = null;
                            try {
                                Object f = XposedHelpers.getObjectField(tv, "icon");
                                if (f instanceof Drawable) icon = (Drawable) f;
                            } catch (Throwable ignored) {}
                            if (icon == null) {
                                Drawable[] cds = tv.getCompoundDrawables();
                                if (cds != null && cds.length > 1) icon = cds[1];
                            }
                            if (icon == null) return;
                            int iw = icon.getBounds().width();
                            int ih = icon.getBounds().height();
                            if (iw < 32 || ih < 32) return;

                            tv.getLocationOnScreen(loc);
                            int sw = Math.max(tv.getResources().getDisplayMetrics().widthPixels, 1080);
                            int sh = Math.max(tv.getResources().getDisplayMetrics().heightPixels, 2340);
                            float shiftX = (loc[0] / (float) sw) * 2.0f - 1.0f;
                            float shiftY = (loc[1] / (float) sh) * 2.0f - 1.0f;

                            float left = (tv.getWidth() - iw) * 0.5f;
                            float top = tv.getPaddingTop();
                            LiquidGlassRuntime.drawLiveLiquidRim(canvas, left, top, iw, ih, shiftX, shiftY, false, 0.58f);
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "IconViewImpl.onDraw hook err: " + t);
            }
        }

        // 2. Hook HoneySpace FolderIconViewImpl for 1:1 iOS 26 Clear Liquid Glass Folders
        Class<?> folderCls = XposedHelpers.findClassIfExists("com.honeyspace.ui.common.iconview.FolderIconViewImpl", cl);
        if (folderCls != null) {
            sHomeHooks.incrementAndGet();
            try {
                XposedBridge.hookAllMethods(folderCls, "onDraw", new XC_MethodHook() {
                    private final int[] loc = new int[2];
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || !opt(ModuleStatus.KEY_GLASS_FOLDERS, true)) return;
                        if (!(param.thisObject instanceof View) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        try {
                            View v = (View) param.thisObject;
                            Canvas c = (Canvas) param.args[0];
                            int s = Math.min(v.getWidth(), v.getHeight());
                            if (s < 48) return;
                            // Estimate icon square bounds inside folder view
                            int iconBox = Math.round(s * 0.72f);
                            float left = (v.getWidth() - iconBox) * 0.5f;
                            float top = v.getPaddingTop();
                            v.getLocationOnScreen(loc);
                            int sw = Math.max(v.getResources().getDisplayMetrics().widthPixels, 1080);
                            float shiftX = (loc[0] / (float) sw) * 2.0f - 1.0f;
                            LiquidGlassRuntime.drawLiveLiquidRim(c, left, top, iconBox, iconBox, shiftX, 0f, false, 0.85f);
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "FolderIconViewImpl hook err: " + t);
            }
        }

        // 3. Hook HoneySpace HotseatBackgroundView for 1:1 iOS 26 Floating Liquid Glass Dock Pill!
        Class<?> hotseatBgCls = XposedHelpers.findClassIfExists("com.honeyspace.ui.honeypots.hotseat.presentation.HotseatBackgroundView", cl);
        if (hotseatBgCls != null) {
            sHomeHooks.incrementAndGet();
            XC_MethodHook dockHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View v = (View) param.thisObject;
                    if (!isEnabled() || !opt(ModuleStatus.KEY_GLASS_DOCK, true)) {
                        LiquidGlassRuntime.clearSemBlur(v);
                        return;
                    }
                    try {
                        float d = v.getResources().getDisplayMetrics().density;
                        LiquidGlassRuntime.styleGlassDock(v, d);
                    } catch (Throwable ignored) {}
                }
            };
            try {
                XposedBridge.hookAllMethods(hotseatBgCls, "onAttachedToWindow", dockHook);
                XposedBridge.hookAllMethods(hotseatBgCls, "onSizeChanged", dockHook);
                XposedBridge.hookAllMethods(hotseatBgCls, "onDraw", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || !opt(ModuleStatus.KEY_GLASS_DOCK, true)) return;
                        if (!(param.thisObject instanceof View) || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;
                        View v = (View) param.thisObject;
                        LiquidGlassRuntime.drawLiveLiquidRim((Canvas) param.args[0], 0f, 0f, v.getWidth(), v.getHeight(), 0f, 0f, true, 0.72f);
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "HotseatBackgroundView hook err: " + t);
            }
        }
    }

    private void hookSystemUi(ClassLoader cl) {
        XposedBridge.log(TAG + "Hooking SystemUI (" + ModuleStatus.getOneUiVersionName() + ")");
        registerStatusReceiver(cl, ModuleStatus.ACTION_PING_SYSUI, "systemui", sSysUiHooks);

        // Subtle Liquid Glass specular rim polish on SystemUI QS tiles & notification backgrounds (safe & version-resilient)
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
                        LiquidGlassRuntime.drawLiveLiquidRim((Canvas) param.args[0], 0f, 0f, v.getWidth(), v.getHeight(), 0f, 0f, true, 0.42f);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }
}
