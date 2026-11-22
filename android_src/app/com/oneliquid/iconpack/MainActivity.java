package com.oneliquid.iconpack;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private String[] allIcons = new String[0];
    private final List<String> filtered = new ArrayList<>();
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private TextView statusPill;
    private LinearLayout checklistContainer;
    private View tabLsposedView;
    private View tabIconsView;
    private Button tabBtnLsposed;
    private Button tabBtnIcons;

    private volatile boolean homeScopeResponded = false;
    private volatile int homeHookCount = 0;
    private volatile boolean sysUiScopeResponded = false;
    private volatile int sysUiHookCount = 0;
    private BroadcastReceiver pongReceiver;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable cardBg(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private String getDefaultLauncherPackage() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return (ri != null && ri.activityInfo != null) ? ri.activityInfo.packageName : "";
    }

    private boolean tryDirectApply(String launcherPkg) {
        try {
            String pkg = getPackageName();
            if ("com.teslacoilsw.launcher".equals(launcherPkg)) {
                Intent i = new Intent("com.teslacoilsw.launcher.APPLY_ICON_THEME");
                i.setPackage("com.teslacoilsw.launcher");
                i.putExtra("com.teslacoilsw.launcher.extra.ICON_THEME_TYPE", "GO");
                i.putExtra("com.teslacoilsw.launcher.extra.ICON_THEME_PACKAGE", pkg);
                startActivity(i);
                return true;
            }
            if (launcherPkg.startsWith("ginlemon.flower")) {
                Intent i = new Intent("ginlemon.smartlauncher.setGSLTHEME");
                i.putExtra("package", pkg);
                startActivity(i);
                return true;
            }
            if ("com.anddoes.launcher".equals(launcherPkg)) {
                Intent i = new Intent("com.anddoes.launcher.SET_THEME");
                i.putExtra("com.anddoes.launcher.THEME_PACKAGE_NAME", pkg);
                startActivity(i);
                return true;
            }
            if ("com.actionlauncher.playstore".equals(launcherPkg)) {
                Intent i = getPackageManager().getLaunchIntentForPackage(launcherPkg);
                if (i != null) {
                    i.putExtra("apply_icon_pack", pkg);
                    startActivity(i);
                    return true;
                }
            }
            if ("org.adw.launcher".equals(launcherPkg)) {
                Intent i = new Intent("org.adw.launcher.SET_THEME");
                i.putExtra("org.adw.launcher.theme.NAME", pkg);
                startActivity(i);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = ModuleStatus.getPrefs(this);

        // Load icon_pack string-array dynamically from resources
        int arrId = getResources().getIdentifier("icon_pack", "array", getPackageName());
        if (arrId != 0) {
            allIcons = getResources().getStringArray(arrId);
        }

        // Register receiver for live LSPosed scope PONG responses from One UI Home & SystemUI
        pongReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String target = intent.getStringExtra("target");
                int hooks = intent.getIntExtra("hooks", 0);
                if ("launcher".equals(target)) {
                    homeScopeResponded = true;
                    homeHookCount = hooks;
                } else if ("systemui".equals(target)) {
                    sysUiScopeResponded = true;
                    sysUiHookCount = hooks;
                }
            }
        };
        IntentFilter pongFilter = new IntentFilter(ModuleStatus.ACTION_PONG);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pongReceiver, pongFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(pongReceiver, pongFilter);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0D1829"));
        root.setPadding(dp(16), dp(46), dp(16), dp(14));

        // --- Header row: OneLiquid Liquid Glass Logo right next to Title & Live Status Pill ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        int logoRes = getResources().getIdentifier("ic_oneliquid_launcher", "drawable", getPackageName());
        if (logoRes != 0) logo.setImageResource(logoRes);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        logoLp.rightMargin = dp(12);
        header.addView(logo, logoLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("OneLiquid");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title);

        statusPill = new TextView(this);
        statusPill.setTextSize(10.5f);
        statusPill.setTypeface(Typeface.DEFAULT_BOLD);
        statusPill.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.leftMargin = dp(10);
        titleRow.addView(statusPill, pillLp);
        titleCol.addView(titleRow);

        TextView badge = new TextView(this);
        badge.setText(ModuleStatus.getOneUiVersionName() + " • " + allIcons.length + " Icons • v" + ModuleStatus.getModuleVersion());
        badge.setTextColor(Color.parseColor("#96B2D4"));
        badge.setTextSize(12f);
        titleCol.addView(badge);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = dp(12);
        root.addView(header, headerLp);

        // --- 2-Tab Bar: [ LSPosed Module ] | [ Icons & Theme Park ] ---
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackground(cardBg(Color.parseColor("#1AFFFFFF"), Color.parseColor("#33FFFFFF"), 14));
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));

        tabBtnLsposed = makeTabButton("LSPosed Module");
        tabBtnIcons = makeTabButton("Icons (" + allIcons.length + ")");
        tabBar.addView(tabBtnLsposed, new LinearLayout.LayoutParams(0, dp(38), 1f));
        tabBar.addView(tabBtnIcons, new LinearLayout.LayoutParams(0, dp(38), 1f));
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.bottomMargin = dp(12);
        root.addView(tabBar, tabLp);

        // --- Tab Content Views ---
        tabLsposedView = buildLsposedTab();
        tabIconsView = buildIconsTab();

        root.addView(tabLsposedView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(tabIconsView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabBtnLsposed.setOnClickListener(v -> selectTab(0));
        tabBtnIcons.setOnClickListener(v -> selectTab(1));

        selectTab(0);
        setContentView(root);
        updateHeaderStatusPill();
        runAllStatusChecks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (pongReceiver != null) unregisterReceiver(pongReceiver);
        } catch (Throwable ignored) {}
        ioPool.shutdownNow();
    }

    private Button makeTabButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private void selectTab(int idx) {
        boolean isLsp = (idx == 0);
        tabLsposedView.setVisibility(isLsp ? View.VISIBLE : View.GONE);
        tabIconsView.setVisibility(isLsp ? View.GONE : View.VISIBLE);

        tabBtnLsposed.setBackground(isLsp
                ? cardBg(Color.parseColor("#4474C8FF"), Color.parseColor("#88A8DEFF"), 11)
                : cardBg(Color.TRANSPARENT, Color.TRANSPARENT, 11));
        tabBtnLsposed.setTextColor(isLsp ? Color.WHITE : Color.parseColor("#9EB5D2"));

        tabBtnIcons.setBackground(!isLsp
                ? cardBg(Color.parseColor("#4474C8FF"), Color.parseColor("#88A8DEFF"), 11)
                : cardBg(Color.TRANSPARENT, Color.TRANSPARENT, 11));
        tabBtnIcons.setTextColor(!isLsp ? Color.WHITE : Color.parseColor("#9EB5D2"));
    }

    private void updateHeaderStatusPill() {
        boolean active = ModuleStatus.isModuleActive();
        boolean enabled = prefs.getBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true);
        if (active && enabled) {
            statusPill.setText("LSPosed Active");
            statusPill.setTextColor(Color.parseColor("#B8FFD6"));
            statusPill.setBackground(cardBg(Color.parseColor("#3322C55E"), Color.parseColor("#8822C55E"), 10));
        } else if (active) {
            statusPill.setText("LSPosed Paused");
            statusPill.setTextColor(Color.parseColor("#FFE4A3"));
            statusPill.setBackground(cardBg(Color.parseColor("#33F59E0B"), Color.parseColor("#88F59E0B"), 10));
        } else {
            statusPill.setText("Theme Park Mode");
            statusPill.setTextColor(Color.parseColor("#B8DCFF"));
            statusPill.setBackground(cardBg(Color.parseColor("#3338BDF8"), Color.parseColor("#8838BDF8"), 10));
        }
    }

    private void notifyHooksPrefsChanged() {
        try {
            Intent i1 = new Intent(ModuleStatus.ACTION_RELOAD_PREFS);
            i1.setPackage(ModuleStatus.PKG_ONEUI_HOME);
            sendBroadcast(i1);
            Intent i2 = new Intent(ModuleStatus.ACTION_RELOAD_PREFS);
            i2.setPackage(ModuleStatus.PKG_SYSTEMUI);
            sendBroadcast(i2);
        } catch (Throwable ignored) {}
    }

    private View buildLsposedTab() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // --- 1. Master LSPosed Toggle Card ---
        LinearLayout masterCard = new LinearLayout(this);
        masterCard.setOrientation(LinearLayout.VERTICAL);
        masterCard.setBackground(cardBg(Color.parseColor("#243A5E99"), Color.parseColor("#5578B8FF"), 18));
        masterCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        masterCard.addView(makeSwitchRow(
                "Enable OneLiquid LSPosed Module",
                "Activates 1:1 iOS 26 Clear Liquid Glass hooks on One UI 8 / 8.5 / 9+",
                ModuleStatus.KEY_LSPOSED_ENABLED,
                true,
                true
        ));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        content.addView(masterCard, cardLp);

        // --- 2. Feature Toggles Card ---
        LinearLayout featCard = new LinearLayout(this);
        featCard.setOrientation(LinearLayout.VERTICAL);
        featCard.setBackground(cardBg(Color.parseColor("#18FFFFFF"), Color.parseColor("#30FFFFFF"), 18));
        featCard.setPadding(dp(16), dp(14), dp(16), dp(10));

        TextView featHdr = new TextView(this);
        featHdr.setText("LIQUID GLASS MODULES (ONE UI 8 / 8.5 / 9)");
        featHdr.setTextColor(Color.parseColor("#8AC4FF"));
        featHdr.setTextSize(11f);
        featHdr.setTypeface(Typeface.DEFAULT_BOLD);
        featHdr.setPadding(0, 0, 0, dp(8));
        featCard.addView(featHdr);

        featCard.addView(makeSwitchRow(
                "1:1 iOS 26 Clear Icons",
                "Applies all 1,020 icons + live auto-glass conversion on One UI Home",
                ModuleStatus.KEY_LIQUID_ICONS, true, false));
        featCard.addView(makeSwitchRow(
                "Live Backdrop Blur & AGSL Refraction",
                "Scroll-reactive Fresnel specular shimmer & hardware SemBlurInfo",
                ModuleStatus.KEY_LIVE_REFRACTION, true, false));
        featCard.addView(makeSwitchRow(
                "iOS 26 Liquid Glass Dock",
                "Floating translucent glass pill with live backdrop blur on Hotseat",
                ModuleStatus.KEY_GLASS_DOCK, true, false));
        featCard.addView(makeSwitchRow(
                "iOS 26 Liquid Glass Folders",
                "Translucent continuous-squircle glass tiles with Fresnel rim",
                ModuleStatus.KEY_GLASS_FOLDERS, true, false));
        featCard.addView(makeSwitchRow(
                "SystemUI Glass Polish",
                "Subtle Liquid Glass specular rims on QS tiles & notification cards",
                ModuleStatus.KEY_SYSTEMUI_GLASS, true, false));

        LinearLayout.LayoutParams featLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        featLp.bottomMargin = dp(12);
        content.addView(featCard, featLp);

        // --- 3. Setup Checklist & Live Status Checks Card ---
        LinearLayout checkCard = new LinearLayout(this);
        checkCard.setOrientation(LinearLayout.VERTICAL);
        checkCard.setBackground(cardBg(Color.parseColor("#18FFFFFF"), Color.parseColor("#30FFFFFF"), 18));
        checkCard.setPadding(dp(16), dp(14), dp(16), dp(12));

        LinearLayout checkHdrRow = new LinearLayout(this);
        checkHdrRow.setOrientation(LinearLayout.HORIZONTAL);
        checkHdrRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView checkHdr = new TextView(this);
        checkHdr.setText("LSPOSED CHECKLIST & LIVE STATUS");
        checkHdr.setTextColor(Color.parseColor("#8AC4FF"));
        checkHdr.setTextSize(11f);
        checkHdr.setTypeface(Typeface.DEFAULT_BOLD);
        checkHdrRow.addView(checkHdr, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button recheckBtn = new Button(this);
        recheckBtn.setText("Recheck");
        recheckBtn.setAllCaps(false);
        recheckBtn.setTextSize(11.5f);
        recheckBtn.setTextColor(Color.WHITE);
        recheckBtn.setBackground(cardBg(Color.parseColor("#2EFFFFFF"), Color.parseColor("#55FFFFFF"), 10));
        recheckBtn.setPadding(dp(10), 0, dp(10), 0);
        recheckBtn.setOnClickListener(v -> runAllStatusChecks());
        checkHdrRow.addView(recheckBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        checkCard.addView(checkHdrRow);

        checklistContainer = new LinearLayout(this);
        checklistContainer.setOrientation(LinearLayout.VERTICAL);
        checklistContainer.setPadding(0, dp(8), 0, 0);
        checkCard.addView(checklistContainer);

        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        checkLp.bottomMargin = dp(12);
        content.addView(checkCard, checkLp);

        // --- 4. Restart SystemUI & One UI Home Controls ---
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);

        Button restartSysUiBtn = new Button(this);
        restartSysUiBtn.setText("Restart SystemUI");
        restartSysUiBtn.setAllCaps(false);
        restartSysUiBtn.setTextSize(13.5f);
        restartSysUiBtn.setTypeface(Typeface.DEFAULT_BOLD);
        restartSysUiBtn.setTextColor(Color.WHITE);
        restartSysUiBtn.setBackground(cardBg(Color.parseColor("#44EF4444"), Color.parseColor("#99F87171"), 14));
        restartSysUiBtn.setOnClickListener(v -> confirmRestartSystemUi());

        Button restartHomeBtn = new Button(this);
        restartHomeBtn.setText("Restart One UI Home");
        restartHomeBtn.setAllCaps(false);
        restartHomeBtn.setTextSize(13.5f);
        restartHomeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        restartHomeBtn.setTextColor(Color.WHITE);
        restartHomeBtn.setBackground(cardBg(Color.parseColor("#3838BDF8"), Color.parseColor("#887DD3FC"), 14));
        restartHomeBtn.setOnClickListener(v -> restartOneUiHome());

        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        b1Lp.rightMargin = dp(8);
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        actionsRow.addView(restartSysUiBtn, b1Lp);
        actionsRow.addView(restartHomeBtn, b2Lp);

        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.bottomMargin = dp(14);
        content.addView(actionsRow, actLp);

        // Quick setup guide note
        TextView tip = new TextView(this);
        tip.setText("Setup Checklist: In LSPosed Manager -> Modules -> enable OneLiquid -> tick One UI Home (com.sec.android.app.launcher) & SystemUI (com.android.systemui) -> tap Restart One UI Home & Restart SystemUI above.");
        tip.setTextColor(Color.parseColor("#8AA4C4"));
        tip.setTextSize(11.5f);
        tip.setPadding(dp(4), 0, dp(4), dp(12));
        content.addView(tip);

        sv.addView(content);
        return sv;
    }

    private View makeSwitchRow(String titleText, String descText, final String prefKey, boolean defVal, final boolean isMaster) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(Color.WHITE);
        t.setTextSize(isMaster ? 15.5f : 14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(t);

        TextView d = new TextView(this);
        d.setText(descText);
        d.setTextColor(Color.parseColor("#A4BCD8"));
        d.setTextSize(11.5f);
        textCol.addView(d);

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(prefKey, defVal));
        sw.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            prefs.edit().putBoolean(prefKey, isChecked).apply();
            notifyHooksPrefsChanged();
            if (isMaster) updateHeaderStatusPill();
            Toast.makeText(this,
                    titleText + (isChecked ? " enabled" : " disabled") + " — restart One UI Home / SystemUI to apply",
                    Toast.LENGTH_SHORT).show();
        });
        row.addView(sw);
        return row;
    }

    private void addChecklistRow(String title, String detail, int state) {
        // state: 2 = PASS (green), 1 = WARN/ACTION (amber), 0 = FAIL (red)
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView icon = new TextView(this);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setTextSize(11f);
        if (state == 2) {
            icon.setText("✓");
            icon.setTextColor(Color.parseColor("#86EFAC"));
            icon.setBackground(cardBg(Color.parseColor("#3322C55E"), Color.parseColor("#8822C55E"), 12));
        } else if (state == 1) {
            icon.setText("!");
            icon.setTextColor(Color.parseColor("#FDE68A"));
            icon.setBackground(cardBg(Color.parseColor("#33F59E0B"), Color.parseColor("#88F59E0B"), 12));
        } else {
            icon.setText("✕");
            icon.setTextColor(Color.parseColor("#FCA5A5"));
            icon.setBackground(cardBg(Color.parseColor("#33EF4444"), Color.parseColor("#88EF4444"), 12));
        }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.rightMargin = dp(10);
        row.addView(icon, iconLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);

        TextView d = new TextView(this);
        d.setText(detail);
        d.setTextColor(Color.parseColor("#9FB8D6"));
        d.setTextSize(11.5f);
        col.addView(d);

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText(state == 2 ? "OK" : (state == 1 ? "ACTION" : "OFF"));
        badge.setTextSize(10f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(state == 2 ? Color.parseColor("#86EFAC")
                : (state == 1 ? Color.parseColor("#FDE68A") : Color.parseColor("#FCA5A5")));
        row.addView(badge);

        checklistContainer.addView(row);
    }

    private void runAllStatusChecks() {
        homeScopeResponded = false;
        homeHookCount = 0;
        sysUiScopeResponded = false;
        sysUiHookCount = 0;

        checklistContainer.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("Running live LSPosed & One UI checks...");
        loading.setTextColor(Color.parseColor("#9FB8D6"));
        loading.setTextSize(12.5f);
        loading.setPadding(0, dp(8), 0, dp(8));
        checklistContainer.addView(loading);

        // Send live IPC pings to hooked One UI Home & SystemUI processes
        try {
            Intent p1 = new Intent(ModuleStatus.ACTION_PING_HOME);
            p1.setPackage(ModuleStatus.PKG_ONEUI_HOME);
            sendBroadcast(p1);

            Intent p2 = new Intent(ModuleStatus.ACTION_PING_SYSUI);
            p2.setPackage(ModuleStatus.PKG_SYSTEMUI);
            sendBroadcast(p2);
        } catch (Throwable ignored) {}

        ioPool.execute(() -> {
            final boolean oneUiOk = ModuleStatus.isOneUiSupported();
            final String oneUiVer = ModuleStatus.getOneUiVersionName() + " (code " + ModuleStatus.getOneUiVersionInt() + ")";
            final boolean hasRoot = ModuleStatus.checkRootAvailable();
            final boolean modActive = ModuleStatus.isModuleActive();
            final boolean prefsWorld = ModuleStatus.isPrefsWorldReadable(this);
            final boolean homeInstalled = ModuleStatus.isPackageInstalled(this, ModuleStatus.PKG_ONEUI_HOME);
            final boolean sysUiInstalled = ModuleStatus.isPackageInstalled(this, ModuleStatus.PKG_SYSTEMUI);

            // Allow 380ms for PONG broadcasts from One UI Home & SystemUI
            try { Thread.sleep(380); } catch (InterruptedException ignored) {}

            ui.post(() -> {
                updateHeaderStatusPill();
                checklistContainer.removeAllViews();

                addChecklistRow(
                        "1. One UI Compatibility (8 / 8.5 / 9+)",
                        oneUiOk ? oneUiVer + " — fully supported" : oneUiVer + " — One UI 8+ recommended",
                        oneUiOk ? 2 : 1
                );
                addChecklistRow(
                        "2. Root Access (KernelSU / Magisk)",
                        hasRoot ? "Root granted (required for Restart SystemUI / Home)" : "Not granted — grant root to enable SystemUI restart",
                        hasRoot ? 2 : 1
                );
                addChecklistRow(
                        "3. LSPosed Module Hook Active",
                        modActive ? "OneLiquidHook loaded & active in LSPosed" : "Not active — enable OneLiquid in LSPosed Manager",
                        modActive ? 2 : 0
                );
                addChecklistRow(
                        "4. Scope: One UI Home",
                        homeScopeResponded
                                ? "Hooked live (" + homeHookCount + " HoneySpace hooks active)"
                                : (homeInstalled ? "Enable com.sec.android.app.launcher in LSPosed & restart Home" : "One UI Home not detected"),
                        homeScopeResponded ? 2 : (modActive ? 1 : 0)
                );
                addChecklistRow(
                        "5. Scope: SystemUI",
                        sysUiScopeResponded
                                ? "Hooked live (" + sysUiHookCount + " SystemUI hooks active)"
                                : (sysUiInstalled ? "Enable com.android.systemui in LSPosed & restart SystemUI" : "SystemUI not detected"),
                        sysUiScopeResponded ? 2 : (modActive ? 1 : 0)
                );
                addChecklistRow(
                        "6. XSharedPreferences Sync",
                        prefsWorld ? "MODE_WORLD_READABLE active (toggles sync live)" : "Waiting for LSPosed xposedsharedprefs activation",
                        prefsWorld ? 2 : (modActive ? 1 : 0)
                );
            });
        });
    }

    private void confirmRestartSystemUi() {
        new AlertDialog.Builder(this)
                .setTitle("Restart SystemUI?")
                .setMessage("This briefly reloads the status bar, lock screen, and notification shade to apply OneLiquid LSPosed changes.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restart SystemUI", (dlg, which) -> {
                    Toast.makeText(this, "Restarting SystemUI...", Toast.LENGTH_SHORT).show();
                    ioPool.execute(() -> {
                        boolean ok = ModuleStatus.runRootCommand(
                                "pkill -f com.android.systemui || killall com.android.systemui");
                        ui.post(() -> {
                            if (!ok) {
                                Toast.makeText(this, "Root access required to restart SystemUI", Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .show();
    }

    private void restartOneUiHome() {
        Toast.makeText(this, "Restarting One UI Home...", Toast.LENGTH_SHORT).show();
        ioPool.execute(() -> {
            boolean ok = ModuleStatus.runRootCommand("am force-stop com.sec.android.app.launcher");
            ui.post(() -> {
                if (ok) {
                    Toast.makeText(this, "One UI Home restarted — hooks reloaded!", Toast.LENGTH_SHORT).show();
                    ui.postDelayed(this::runAllStatusChecks, 900);
                } else {
                    Toast.makeText(this, "Root access required to restart One UI Home", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private View buildIconsTab() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        String launcherPkg = getDefaultLauncherPackage();
        boolean supportsDirect = "com.teslacoilsw.launcher".equals(launcherPkg)
                || launcherPkg.startsWith("ginlemon.flower")
                || "com.anddoes.launcher".equals(launcherPkg)
                || "com.actionlauncher.playstore".equals(launcherPkg)
                || "org.adw.launcher".equals(launcherPkg);

        TextView sub = new TextView(this);
        sub.setText(supportsDirect
                ? "Tap below to apply OneLiquid directly to your launcher (no root required)."
                : "No-root Theme Park mode: Theme Park -> Icon -> Create New -> Iconpack -> Third Party -> OneLiquid (keep Shape original & Effects None).");
        sub.setTextColor(Color.parseColor("#B8CCE4"));
        sub.setTextSize(12f);
        sub.setPadding(0, 0, 0, dp(10));
        box.addView(sub);

        Button btn = new Button(this);
        btn.setText(supportsDirect ? "Apply OneLiquid Now" : "Apply via Theme Park (No Root)");
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setTextSize(14.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setBackground(cardBg(Color.parseColor("#33FFFFFF"), Color.parseColor("#88FFFFFF"), 14));
        btn.setOnClickListener(v -> {
            if (supportsDirect && tryDirectApply(launcherPkg)) return;
            Intent tp = getPackageManager().getLaunchIntentForPackage(ModuleStatus.PKG_THEME_PARK);
            if (tp != null) {
                startActivity(tp);
            } else {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("samsungapps://ProductDetail/" + ModuleStatus.PKG_THEME_PARK)));
                } catch (Throwable t) {
                    Toast.makeText(this, "Install Good Lock -> Theme Park from Galaxy Store", Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        btnLp.bottomMargin = dp(10);
        box.addView(btn, btnLp);

        filtered.clear();
        for (String ic : allIcons) filtered.add(ic);

        EditText search = new EditText(this);
        search.setHint("Search " + allIcons.length + " icons (e.g. netflix, steam, paypal)...");
        search.setHintTextColor(Color.parseColor("#8FA4BE"));
        search.setTextColor(Color.WHITE);
        search.setTextSize(13.5f);
        search.setSingleLine(true);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setBackground(cardBg(Color.parseColor("#1FFFFFFF"), Color.parseColor("#44FFFFFF"), 14));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = dp(12);
        box.addView(search, searchLp);

        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(14));
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int pos) { return filtered.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                ImageView iv = (convertView instanceof ImageView)
                        ? (ImageView) convertView : new ImageView(MainActivity.this);
                int sz = dp(72);
                iv.setLayoutParams(new GridView.LayoutParams(sz, sz));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int resId = getResources().getIdentifier(filtered.get(pos), "drawable", getPackageName());
                if (resId != 0) iv.setImageResource(resId);
                return iv;
            }
        };
        grid.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s.toString().trim().toLowerCase().replace(" ", "_");
                filtered.clear();
                for (String ic : allIcons) {
                    if (q.isEmpty() || ic.toLowerCase().contains(q)) filtered.add(ic);
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        box.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return box;
    }
}
