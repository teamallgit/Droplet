import os
import shutil
import subprocess
import xml.sax.saxutils as saxutils

BASE_DIR = "."
ICONS_DIR = os.path.join(BASE_DIR, "icons")
APK_PROJ = os.path.join(BASE_DIR, "apk_build")
OUT_APK = os.path.join(BASE_DIR, "OneLiquid.apk")

JAVA_HOME = "/opt/homebrew/opt/openjdk@17"
BUILD_TOOLS = "/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0"
ANDROID_JAR = "/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar"
KEYSTORE = os.path.join(BASE_DIR, "debug.keystore")


def load_mapping():
    rows = []
    with open(os.path.join(BASE_DIR, "icon_mapping.tsv"), "r", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 5:
                rows.append(tuple(parts[:5]))
    return rows


def generate_appfilter_xml(rows):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<resources>',
        '',
        '    <!-- Fallback Liquid Glass framing for any unthemed future apps -->',
        '    <iconback img1="ic_glass_back" />',
        '    <iconmask img1="ic_glass_mask" />',
        '    <iconupon img1="ic_glass_upon" />',
        '    <scale factor="0.72" />',
        '',
        '    <!-- Generic launcher shortcuts -->',
        '    <item component=":BROWSER" drawable="ios_safari" />',
        '    <item component=":CALCULATOR" drawable="ios_calculator" />',
        '    <item component=":CALENDAR" drawable="ios_calendar" />',
        '    <item component=":CAMERA" drawable="ios_camera" />',
        '    <item component=":CLOCK" drawable="ios_clock" />',
        '    <item component=":CONTACTS" drawable="ios_contacts" />',
        '    <item component=":EMAIL" drawable="ios_mail" />',
        '    <item component=":GALLERY" drawable="ios_photos" />',
        '    <item component=":PHONE" drawable="ios_phone" />',
        '    <item component=":SMS" drawable="ios_messages" />',
        '',
        '    <!-- OneLiquid itself -->',
        '    <item component="ComponentInfo{com.oneliquid.iconpack/com.oneliquid.iconpack.MainActivity}" drawable="ic_oneliquid_launcher" />',
        '',
        '    <!-- Installed launchable apps -->',
    ]

    seen_components = {
        "ComponentInfo{com.oneliquid.iconpack/com.oneliquid.iconpack.MainActivity}"
    }
    for res_name, pkg, cls, label, tier in rows:
        comp = f"ComponentInfo{{{pkg}/{cls}}}"
        if comp not in seen_components:
            seen_components.add(comp)
            safe_lbl = label.replace("--", "-")
            lines.append(f'    <!-- {safe_lbl} -->')
            lines.append(f'    <item component={saxutils.quoteattr(comp)} drawable={saxutils.quoteattr(res_name)} />')

    bonus_items = [
        ("ComponentInfo{com.spotify.music/com.spotify.music.MainActivity}", "ios_spotify"),
        ("ComponentInfo{com.spotify.music/com.spotify.music.LauncherActivity}", "ios_spotify"),
        ("ComponentInfo{com.sec.android.app.shealth/com.samsung.android.app.shealth.home.HomeMainActivity}", "ios_health"),
        ("ComponentInfo{com.sec.android.app.shealth/com.samsung.android.app.shealth.home.MainActivity}", "ios_health"),
        ("ComponentInfo{com.google.android.apps.fitness/com.google.android.apps.fitness.welcome.WelcomeActivity}", "ios_health"),
        ("ComponentInfo{com.android.chrome/com.google.android.apps.chrome.IntentDispatcher}", "ios_chrome"),
        ("ComponentInfo{com.sec.android.app.sbrowser/com.sec.android.app.sbrowser.SBrowserLauncherActivity}", "ios_safari"),
        ("ComponentInfo{com.samsung.android.dialer/com.samsung.android.dialer.DialtactsActivity}", "ios_phone"),
        ("ComponentInfo{com.samsung.android.calendar/com.android.calendar.AllInOneActivity}", "ios_calendar"),
        ("ComponentInfo{com.sec.android.app.clockpackage/com.sec.android.app.clockpackage.ClockPackage}", "ios_clock"),
        ("ComponentInfo{com.google.android.gm/com.google.android.gm.ConversationListActivityGmail}", "ios_mail"),
        ("ComponentInfo{com.sec.android.app.myfiles/com.sec.android.app.myfiles.external.ui.MainActivity}", "ios_files"),
        ("ComponentInfo{com.android.vending/com.android.vending.AssetBrowserActivity}", "ios_playstore"),
        ("ComponentInfo{com.whatsapp/com.whatsapp.HomeActivity}", "ic_com_whatsapp"),
        ("ComponentInfo{org.telegram.messenger/org.telegram.ui.LaunchActivity}", "ic_org_telegram_messenger_web"),
        ("ComponentInfo{org.telegram.messenger.web/org.telegram.ui.LaunchActivity}", "ic_org_telegram_messenger_web"),
        ("ComponentInfo{com.instagram.android/com.instagram.mainactivity.InstagramMainActivity}", "ic_com_instagram_android"),
        ("ComponentInfo{com.discord/com.discord.app.AppActivity$Main}", "ic_com_discord"),
    ]
    lines.append('')
    lines.append('    <!-- Bonus popular apps & aliases -->')
    for comp, dname in bonus_items:
        if comp not in seen_components and os.path.exists(os.path.join(ICONS_DIR, f"{dname}.png")):
            seen_components.add(comp)
            lines.append(f'    <item component={saxutils.quoteattr(comp)} drawable={saxutils.quoteattr(dname)} />')

    lines.append('</resources>')
    return "\n".join(lines) + "\n"


def generate_drawable_xml(rows):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<resources>',
        '    <version>1</version>',
        '    <category title="OneLiquid Core" />',
        '    <item drawable="ic_oneliquid_launcher" />',
    ]
    seen = {"ic_oneliquid_launcher"}
    for res_name, pkg, cls, label, tier in rows:
        if tier in ("bespoke_ios", "official_brand") and res_name not in seen:
            seen.add(res_name)
            lines.append(f'    <item drawable={saxutils.quoteattr(res_name)} />')
    for bonus in ("ios_spotify", "ios_health", "ios_appstore"):
        if bonus not in seen:
            seen.add(bonus)
            lines.append(f'    <item drawable={saxutils.quoteattr(bonus)} />')

    lines.append('    <category title="All Apps" />')
    for res_name, pkg, cls, label, tier in rows:
        if res_name not in seen:
            seen.add(res_name)
            lines.append(f'    <item drawable={saxutils.quoteattr(res_name)} />')

    lines.append('</resources>')
    return "\n".join(lines) + "\n"


def generate_icon_pack_xml(all_drawables):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<resources>',
        '    <string-array name="icon_pack" translatable="false">',
    ]
    for d in all_drawables:
        lines.append(f'        <item>{d}</item>')
    lines.append('    </string-array>')
    lines.append('</resources>')
    return "\n".join(lines) + "\n"


def generate_manifest():
    return """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.oneliquid.iconpack"
    android:versionCode="1"
    android:versionName="1.0.0">

    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />

    <application
        android:label="OneLiquid"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="false">

        <activity
            android:name=".MainActivity"
            android:label="OneLiquid"
            android:exported="true"
            android:theme="@android:style/Theme.DeviceDefault.NoActionBar">

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- Standard Icon Pack Intent Filters (One UI Theme Park, Nova, Lawnchair, ADW, Apex, Smart) -->
            <intent-filter>
                <action android:name="org.adw.launcher.THEMES" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="org.adw.launcher.icons.ACTION_PICK_ICON" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.novalauncher.THEME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="com.anddoes.launcher.THEME" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="com.teslacoilsw.launcher.THEME" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.novalauncher.category.CUSTOM_ICON_PICKER" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="ch.deletescape.lawnchair.ICONPACK" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="app.lawnchair.icons.THEMED_ICON" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="ginlemon.smartlauncher.THEMES" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.sonymobile.home.ICON_PACK" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.gau.go.launcherex.theme" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="net.oneplus.launcher.icons.ACTION_PICK_ICON" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""


def generate_main_activity_java(all_drawables):
    arr_items = ", ".join(f'"{d}"' for d in all_drawables)
    return f"""package com.oneliquid.iconpack;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {{
    private static final String[] ICONS = new String[] {{ {arr_items} }};

    private int dp(int v) {{
        return Math.round(v * getResources().getDisplayMetrics().density);
    }}

    @Override
    protected void onCreate(Bundle savedInstanceState) {{
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#182230"));
        root.setPadding(dp(18), dp(48), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("OneLiquid");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(ICONS.length + " Liquid Glass icons for One UI\\nApply via Good Lock -> Theme Park -> Icon -> Create New -> Iconpack -> Third Party -> OneLiquid (keep Shape original & Effects None).");
        sub.setTextColor(Color.parseColor("#C8D6E8"));
        sub.setTextSize(13);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        Button btn = new Button(this);
        btn.setText("Open Theme Park");
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#33FFFFFF"));
        btnBg.setCornerRadius(dp(16));
        btnBg.setStroke(dp(1), Color.parseColor("#88FFFFFF"));
        btn.setBackground(btnBg);
        btn.setOnClickListener(v -> {{
            Intent i = getPackageManager().getLaunchIntentForPackage("com.samsung.android.themedesigner");
            if (i != null) {{
                startActivity(i);
            }} else {{
                Toast.makeText(this, "Open Good Lock -> Theme Park -> Icon", Toast.LENGTH_LONG).show();
            }}
        }});
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        btnLp.bottomMargin = dp(16);
        root.addView(btn, btnLp);

        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(14));
        grid.setAdapter(new BaseAdapter() {{
            @Override public int getCount() {{ return ICONS.length; }}
            @Override public Object getItem(int pos) {{ return ICONS[pos]; }}
            @Override public long getItemId(int pos) {{ return pos; }}
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {{
                ImageView iv = (convertView instanceof ImageView)
                        ? (ImageView) convertView : new ImageView(MainActivity.this);
                int sz = dp(72);
                iv.setLayoutParams(new GridView.LayoutParams(sz, sz));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int resId = getResources().getIdentifier(ICONS[pos], "drawable", getPackageName());
                if (resId != 0) iv.setImageResource(resId);
                return iv;
            }}
        }});
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        setContentView(root);
    }}
}}
"""


def run(cmd, env=None):
    res = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if res.returncode != 0:
        print("CMD FAILED:", " ".join(cmd))
        print("STDOUT:", res.stdout)
        print("STDERR:", res.stderr)
        raise RuntimeError(f"Command failed with exit code {res.returncode}")
    return res.stdout


def main():
    rows = load_mapping()
    if os.path.exists(APK_PROJ):
        shutil.rmtree(APK_PROJ)

    res_dir = os.path.join(APK_PROJ, "res")
    drawable_dir = os.path.join(res_dir, "drawable-nodpi")
    mipmap_dir = os.path.join(res_dir, "mipmap-xxxhdpi")
    xml_dir = os.path.join(res_dir, "xml")
    values_dir = os.path.join(res_dir, "values")
    assets_dir = os.path.join(APK_PROJ, "assets")
    src_dir = os.path.join(APK_PROJ, "src", "com", "oneliquid", "iconpack")
    build_dir = os.path.join(APK_PROJ, "build")

    for d in (drawable_dir, mipmap_dir, xml_dir, values_dir, assets_dir, src_dir, build_dir):
        os.makedirs(d, exist_ok=True)

    all_drawables = []
    for fname in sorted(os.listdir(ICONS_DIR)):
        if not fname.endswith(".png"):
            continue
        shutil.copy2(os.path.join(ICONS_DIR, fname), os.path.join(drawable_dir, fname))
        stem = fname[:-4]
        if stem not in ("ic_glass_back", "ic_glass_mask", "ic_glass_upon"):
            all_drawables.append(stem)

    # Use our custom OneLiquid ('OneUI' text in Liquid Glass) icon as the app's launcher icon!
    launcher_src = os.path.join(ICONS_DIR, "ic_oneliquid_launcher.png")
    shutil.copy2(launcher_src, os.path.join(mipmap_dir, "ic_launcher.png"))

    appfilter_xml = generate_appfilter_xml(rows)
    drawable_xml = generate_drawable_xml(rows)
    icon_pack_xml = generate_icon_pack_xml(all_drawables)

    for target_dir in (xml_dir, assets_dir):
        with open(os.path.join(target_dir, "appfilter.xml"), "w", encoding="utf-8") as f:
            f.write(appfilter_xml)
        with open(os.path.join(target_dir, "drawable.xml"), "w", encoding="utf-8") as f:
            f.write(drawable_xml)

    with open(os.path.join(values_dir, "icon_pack.xml"), "w", encoding="utf-8") as f:
        f.write(icon_pack_xml)

    manifest_path = os.path.join(APK_PROJ, "AndroidManifest.xml")
    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write(generate_manifest())

    java_path = os.path.join(src_dir, "MainActivity.java")
    with open(java_path, "w", encoding="utf-8") as f:
        f.write(generate_main_activity_java(all_drawables))

    compiled_zip = os.path.join(build_dir, "res_compiled.zip")
    run([os.path.join(BUILD_TOOLS, "aapt2"), "compile", "--dir", res_dir, "-o", compiled_zip])

    unaligned_apk = os.path.join(build_dir, "unaligned.apk")
    gen_dir = os.path.join(build_dir, "gen")
    os.makedirs(gen_dir, exist_ok=True)
    run([
        os.path.join(BUILD_TOOLS, "aapt2"), "link",
        "-o", unaligned_apk,
        "-I", ANDROID_JAR,
        "--manifest", manifest_path,
        "-A", assets_dir,
        "--java", gen_dir,
        "--auto-add-overlay",
        compiled_zip,
    ])

    classes_dir = os.path.join(build_dir, "classes")
    os.makedirs(classes_dir, exist_ok=True)
    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME
    run([
        os.path.join(JAVA_HOME, "bin", "javac"),
        "-cp", ANDROID_JAR,
        "-d", classes_dir,
        java_path,
    ], env=env)

    class_files = []
    for root_d, _, files in os.walk(classes_dir):
        for fn in files:
            if fn.endswith(".class"):
                class_files.append(os.path.join(root_d, fn))

    run([
        os.path.join(BUILD_TOOLS, "d8"),
        "--output", build_dir,
        "--lib", ANDROID_JAR,
        *class_files,
    ], env=env)

    subprocess.run(["zip", "-q", "-u", "unaligned.apk", "classes.dex"], cwd=build_dir, check=True)

    aligned_apk = os.path.join(build_dir, "aligned.apk")
    run([os.path.join(BUILD_TOOLS, "zipalign"), "-f", "4", unaligned_apk, aligned_apk])

    run([
        os.path.join(BUILD_TOOLS, "apksigner"), "sign",
        "--ks", KEYSTORE,
        "--ks-pass", "pass:android",
        "--key-pass", "pass:android",
        "--out", OUT_APK,
        aligned_apk,
    ], env=env)

    run([os.path.join(BUILD_TOOLS, "apksigner"), "verify", OUT_APK], env=env)
    size_mb = os.path.getsize(OUT_APK) / (1024 * 1024)
    print(f"SUCCESS! Built & signed {OUT_APK} ({size_mb:.2f} MB, {len(all_drawables)} icons)")


if __name__ == "__main__":
    main()
