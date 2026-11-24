import os
import shutil
import subprocess
import xml.sax.saxutils as saxutils

BASE_DIR = "."
ICONS_DIR = os.path.join(BASE_DIR, "icons")
ANDROID_SRC_DIR = os.path.join(BASE_DIR, "android_src")
APK_PROJ = os.path.join(BASE_DIR, "apk_build")
OUT_APK = os.path.join(BASE_DIR, "Droplet.apk")

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


def load_extra_mapping():
    extra_path = os.path.join(BASE_DIR, "extra_mapping.tsv")
    rows = []
    if os.path.exists(extra_path):
        with open(extra_path, "r", encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 3:
                    rows.append((parts[0], parts[1], parts[2]))
    return rows


def generate_appfilter_xml(rows, extra_rows=None):
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
        '    <!-- Droplet itself -->',
        '    <item component="ComponentInfo{com.droplet.iconpack/com.droplet.iconpack.MainActivity}" drawable="ic_droplet_launcher" />',
        '',
        '    <!-- Core installed & One UI apps -->',
    ]

    seen_components = {
        "ComponentInfo{com.droplet.iconpack/com.droplet.iconpack.MainActivity}"
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

    if extra_rows:
        lines.append('')
        lines.append('    <!-- Mainstream & Niche Apps (1,000+ Solid-Filled Liquid Glass Collection) -->')
        for res_name, comp, lbl in extra_rows:
            if comp and comp.startswith("ComponentInfo{") and comp not in seen_components and os.path.exists(os.path.join(ICONS_DIR, f"{res_name}.png")):
                seen_components.add(comp)
                lines.append(f'    <item component={saxutils.quoteattr(comp)} drawable={saxutils.quoteattr(res_name)} />')

    lines.append('</resources>')
    return "\n".join(lines) + "\n"


def generate_drawable_xml(rows, extra_rows=None):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<resources>',
        '    <version>1</version>',
        '    <category title="Droplet Core" />',
        '    <item drawable="ic_droplet_launcher" />',
    ]
    seen = {"ic_droplet_launcher"}
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

    if extra_rows:
        lines.append('    <category title="Mainstream &amp; Niche (1,000+)" />')
        for res_name, comp, lbl in extra_rows:
            if res_name not in seen and os.path.exists(os.path.join(ICONS_DIR, f"{res_name}.png")):
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


def generate_xposed_scope_xml():
    return """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="xposed_scope" translatable="false">
        <item>com.sec.android.app.launcher</item>
        <item>com.android.systemui</item>
    </string-array>
</resources>
"""


def generate_manifest():
    return """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.droplet.iconpack"
    android:versionCode="26"
    android:versionName="2.9.0">

    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />

    <!-- Signature-protected permission restricting live config & status broadcasts to Droplet only -->
    <permission
        android:name="com.droplet.iconpack.permission.CONTROL_BROADCAST"
        android:protectionLevel="signature" />
    <uses-permission android:name="com.droplet.iconpack.permission.CONTROL_BROADCAST" />

    <queries>
        <package android:name="com.sec.android.app.launcher" />
        <package android:name="com.android.systemui" />
        <package android:name="com.samsung.android.themedesigner" />
        <package android:name="com.samsung.android.goodlock" />
        <package android:name="com.teslacoilsw.launcher" />
        <package android:name="app.lawnchair" />
        <package android:name="ch.deletescape.lawnchair.plah" />
        <package android:name="ginlemon.flowerfree" />
        <package android:name="ginlemon.flowerpro" />
        <package android:name="com.actionlauncher.playstore" />
        <package android:name="com.anddoes.launcher" />
        <package android:name="org.adw.launcher" />
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.HOME" />
        </intent>
    </queries>

    <application
        android:label="Droplet"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="false">

        <!-- LSPosed / Xposed Module Metadata (One UI 8 / 8.5 / 9+) -->
        <meta-data android:name="xposedmodule" android:value="true" />
        <meta-data android:name="xposeddescription" android:value="1:1 iOS 26/27 Liquid Glass icons with live 120fps AGSL wallpaper refraction, lens magnification, RGB dispersion &amp; gyroscope tilt caustics for One UI 8 / 8.5 / 9+" />
        <meta-data android:name="xposedminversion" android:value="93" />
        <meta-data android:name="xposedscope" android:resource="@array/xposed_scope" />
        <meta-data android:name="xposedsharedprefs" android:value="true" />

        <activity
            android:name=".MainActivity"
            android:label="Droplet"
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


def run(cmd, env=None):
    res = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if res.returncode != 0:
        print("CMD FAILED:", " ".join(cmd))
        print("STDOUT:", res.stdout)
        print("STDERR:", res.stderr)
        raise RuntimeError(f"Command failed with exit code {res.returncode}")
    return res.stdout


def collect_java_files(directory):
    out = []
    for r, _, files in os.walk(directory):
        for fn in sorted(files):
            if fn.endswith(".java"):
                out.append(os.path.join(r, fn))
    return out


def main():
    rows = load_mapping()
    extra_rows = load_extra_mapping()
    if os.path.exists(APK_PROJ):
        shutil.rmtree(APK_PROJ)

    res_dir = os.path.join(APK_PROJ, "res")
    drawable_dir = os.path.join(res_dir, "drawable-nodpi")
    mipmap_dir = os.path.join(res_dir, "mipmap-xxxhdpi")
    xml_dir = os.path.join(res_dir, "xml")
    values_dir = os.path.join(res_dir, "values")
    assets_dir = os.path.join(APK_PROJ, "assets")
    build_dir = os.path.join(APK_PROJ, "build")

    for d in (drawable_dir, mipmap_dir, xml_dir, values_dir, assets_dir, build_dir):
        os.makedirs(d, exist_ok=True)

    all_drawables = []
    for fname in sorted(os.listdir(ICONS_DIR)):
        if not fname.endswith(".png"):
            continue
        shutil.copy2(os.path.join(ICONS_DIR, fname), os.path.join(drawable_dir, fname))
        stem = fname[:-4]
        if stem not in ("ic_glass_back", "ic_glass_mask", "ic_glass_upon"):
            all_drawables.append(stem)

    shutil.copy2(os.path.join(ICONS_DIR, "ic_droplet_launcher.png"), os.path.join(mipmap_dir, "ic_launcher.png"))

    appfilter_xml = generate_appfilter_xml(rows, extra_rows)
    drawable_xml = generate_drawable_xml(rows, extra_rows)
    icon_pack_xml = generate_icon_pack_xml(all_drawables)

    for target_dir in (xml_dir, assets_dir):
        with open(os.path.join(target_dir, "appfilter.xml"), "w", encoding="utf-8") as f:
            f.write(appfilter_xml)
        with open(os.path.join(target_dir, "drawable.xml"), "w", encoding="utf-8") as f:
            f.write(drawable_xml)

    with open(os.path.join(values_dir, "icon_pack.xml"), "w", encoding="utf-8") as f:
        f.write(icon_pack_xml)
    with open(os.path.join(values_dir, "xposed_scope.xml"), "w", encoding="utf-8") as f:
        f.write(generate_xposed_scope_xml())

    # Xposed / LSPosed module entry point
    with open(os.path.join(assets_dir, "xposed_init"), "w", encoding="utf-8") as f:
        f.write("com.droplet.iconpack.xposed.DropletHook\n")

    manifest_path = os.path.join(APK_PROJ, "AndroidManifest.xml")
    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write(generate_manifest())

    # Compile Android resources
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

    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME

    # 1. Compile compile-only Xposed API stubs (NEVER packaged into classes.dex!)
    stub_classes = os.path.join(build_dir, "xposed_stub_classes")
    os.makedirs(stub_classes, exist_ok=True)
    stub_javas = collect_java_files(os.path.join(ANDROID_SRC_DIR, "xposed_stub"))
    run([
        os.path.join(JAVA_HOME, "bin", "javac"),
        "-cp", ANDROID_JAR,
        "-d", stub_classes,
        *stub_javas,
    ], env=env)

    # 2. Compile Droplet app + LSPosed module classes against ANDROID_JAR + stub_classes
    app_classes = os.path.join(build_dir, "app_classes")
    os.makedirs(app_classes, exist_ok=True)
    app_javas = collect_java_files(os.path.join(ANDROID_SRC_DIR, "app"))
    run([
        os.path.join(JAVA_HOME, "bin", "javac"),
        "-cp", f"{ANDROID_JAR}:{stub_classes}",
        "-d", app_classes,
        *app_javas,
    ], env=env)

    # 3. DEX ONLY Droplet's own classes (LSPosed supplies de.robv.android.xposed.* at runtime!)
    class_files = []
    for root_d, _, files in os.walk(app_classes):
        for fn in files:
            if fn.endswith(".class"):
                class_files.append(os.path.join(root_d, fn))

    run([
        os.path.join(BUILD_TOOLS, "d8"),
        "--min-api", "26",
        "--output", build_dir,
        "--lib", ANDROID_JAR,
        "--classpath", stub_classes,
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
    print(f"SUCCESS! Built & signed {OUT_APK} ({size_mb:.2f} MB, {len(all_drawables)} icons, LSPosed Module + Icon Pack)")


if __name__ == "__main__":
    main()
