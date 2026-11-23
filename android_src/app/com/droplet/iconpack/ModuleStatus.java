package com.droplet.iconpack;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

public final class ModuleStatus {
    public static final String PKG = "com.droplet.iconpack";
    public static final String PREFS_NAME = "droplet_prefs";

    public static final String KEY_LSPOSED_ENABLED = "lsposed_enabled";
    public static final String KEY_LIQUID_ICONS = "opt_liquid_icons";
    public static final String KEY_LIVE_REFRACTION = "opt_live_refraction";
    public static final String KEY_GYRO_TILT = "opt_gyro_tilt";
    public static final String KEY_GLASS_FOLDERS = "opt_glass_folders";
    public static final String KEY_SYSTEMUI_GLASS = "opt_systemui_glass";
    // Sliders (0..100): Clarity/Frost, Magnification/Refraction, Corner Light Glow, Glass Tint
    public static final String KEY_FROST_AMOUNT = "opt_frost_amount";
    public static final String KEY_MAG_AMOUNT = "opt_mag_amount";
    public static final String KEY_GLOW_AMOUNT = "opt_glow_amount";
    public static final String KEY_TINT_COLOR = "opt_tint_color";
    public static final String KEY_TINT_STRENGTH = "opt_tint_strength";
    // Sentinel color value for Wallpaper-Adaptive Auto Tint (distinct from 0=None and 0xFFxxxxxx=manual RGB)
    public static final int TINT_COLOR_AUTO = 0x01000000;
    // Legacy fallback key
    public static final String KEY_REFRACTION_LEVEL = "opt_refraction_level";

    public static final String ACTION_PING_HOME = "com.droplet.iconpack.ACTION_PING_HOME";
    public static final String ACTION_PING_SYSUI = "com.droplet.iconpack.ACTION_PING_SYSUI";
    public static final String ACTION_PONG = "com.droplet.iconpack.ACTION_PONG";
    public static final String ACTION_RELOAD_PREFS = "com.droplet.iconpack.ACTION_RELOAD_PREFS";

    public static final String PKG_ONEUI_HOME = "com.sec.android.app.launcher";
    public static final String PKG_SYSTEMUI = "com.android.systemui";
    public static final String PKG_THEME_PARK = "com.samsung.android.themedesigner";

    public static boolean isModuleActive() {
        return false;
    }

    public static String getModuleVersion() {
        return "2.7.1";
    }

    public static int getOneUiVersionInt() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method getInt = sp.getMethod("getInt", String.class, int.class);
            Object v = getInt.invoke(null, "ro.build.version.oneui", 0);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {}
        return 0;
    }

    public static String getOneUiVersionName() {
        int v = getOneUiVersionInt();
        if (v <= 0) {
            return "Android " + Build.VERSION.RELEASE + " (Non-One UI)";
        }
        int major = v / 10000;
        int minor = (v % 10000) / 100;
        return "One UI " + major + "." + minor;
    }

    public static boolean isOneUiSupported() {
        return getOneUiVersionInt() >= 70000;
    }

    @SuppressWarnings("deprecation")
    public static SharedPreferences getPrefs(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    @SuppressWarnings("deprecation")
    public static boolean isPrefsWorldReadable(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    public static boolean isPackageInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean checkRootAvailable() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            if (p.exitValue() == 0) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line = br.readLine();
                    return line != null && line.contains("uid=0");
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean runRootCommand(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            boolean finished = p.waitFor(6, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
