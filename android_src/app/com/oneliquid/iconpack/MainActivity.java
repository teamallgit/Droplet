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
import android.content.res.ColorStateList;
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
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Clean Samsung One UI 8 / 9 Dark Settings interface for OneLiquid.
 * - Native One UI black canvas (#000000), 26dp rounded #1C1C1E grouped cards, #3E8BFF One UI Blue accent
 * - Automatic LSPosed detection: guides setup & grays out root-only controls when inactive
 * - Master On/Off switch bar, live Clarity/Frost, Magnification, and Corner Glow sliders
 * - Live module status & one-tap Restart One UI Home / Restart SystemUI
 */
public class MainActivity extends Activity {

    // Samsung One UI Dark palette
    private static final int COL_BG = Color.parseColor("#000000");
    private static final int COL_CARD = Color.parseColor("#1C1C1E");
    private static final int COL_CARD_SUBTLE = Color.parseColor("#141416");
    private static final int COL_DIVIDER = Color.parseColor("#2A2A2E");
    private static final int COL_ACCENT = Color.parseColor("#3E8BFF");
    private static final int COL_MASTER_ON = Color.parseColor("#1C3454");
    private static final int COL_MASTER_OFF = Color.parseColor("#242428");
    private static final int COL_TEXT_PRI = Color.parseColor("#F5F5F7");
    private static final int COL_TEXT_SEC = Color.parseColor("#9898A0");
    private static final int COL_GREEN = Color.parseColor("#34D399");
    private static final int COL_AMBER = Color.parseColor("#FBBF24");
    private static final int COL_MUTED = Color.parseColor("#63636A");

    private String[] allIcons = new String[0];
    private final List<String> filtered = new ArrayList<>();
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private View tabLsposedView;
    private View tabIconsView;
    private Button tabBtnLsposed;
    private Button tabBtnIcons;

    // LSPosed state-bound controls (grayed out automatically when LSPosed is inactive or Master is Off)
    private LinearLayout masterBar;
    private TextView masterLabel;
    private Switch masterSwitch;
    private LinearLayout setupGuideCard;
    private LinearLayout slidersCard;
    private LinearLayout togglesCard;
    private View tintStrengthRow;
    private SeekBar tintStrengthSeek;
    private TextView tintStrengthValLbl;
    private TextView tintNameLbl;
    private final List<View> tintSwatchViews = new ArrayList<>();
    private final List<View> lsposedControls = new ArrayList<>();
    private LinearLayout statusListContainer;
    private Button restartHomeBtn;
    private Button restartSysUiBtn;

    private volatile boolean homeScopeResponded = false;
    private volatile boolean sysUiScopeResponded = false;
    private volatile boolean rootAvailable = false;
    private BroadcastReceiver pongReceiver;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundedBg(int fillColor, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable roundedStrokeBg(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setBackgroundColor(COL_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(20);
        lp.rightMargin = dp(20);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView makeSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(COL_TEXT_SEC);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(dp(20), dp(14), dp(20), dp(8));
        return tv;
    }

    private void styleSwitch(Switch sw) {
        if (Build.VERSION.SDK_INT >= 23) {
            int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[] {}
            };
            sw.setThumbTintList(new ColorStateList(states, new int[] { Color.WHITE, Color.parseColor("#E5E5EA") }));
            sw.setTrackTintList(new ColorStateList(states, new int[] { COL_ACCENT, Color.parseColor("#48484D") }));
        }
    }

    private void styleSeekBar(SeekBar sb) {
        if (Build.VERSION.SDK_INT >= 21) {
            sb.setProgressTintList(ColorStateList.valueOf(COL_ACCENT));
            sb.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            sb.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#38383D")));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = ModuleStatus.getPrefs(this);
        // Clean one-time migration from legacy v2.4.0 prefs (which had removed widget/live_refraction keys)
        if (prefs.contains("opt_glass_widgets")) {
            prefs.edit()
                    .remove("opt_glass_widgets")
                    .putBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true)
                    .putBoolean(ModuleStatus.KEY_LIQUID_ICONS, true)
                    .putBoolean(ModuleStatus.KEY_LIVE_REFRACTION, true)
                    .putBoolean(ModuleStatus.KEY_GYRO_TILT, true)
                    .putBoolean(ModuleStatus.KEY_GLASS_FOLDERS, true)
                    .putBoolean(ModuleStatus.KEY_SYSTEMUI_GLASS, true)
                    .putInt(ModuleStatus.KEY_FROST_AMOUNT, 28)
                    .putInt(ModuleStatus.KEY_MAG_AMOUNT, 62)
                    .putInt(ModuleStatus.KEY_GLOW_AMOUNT, 78)
                    .commit();
        }

        // Clean One UI edge-to-edge black system bars
        try {
            Window w = getWindow();
            w.setStatusBarColor(COL_BG);
            w.setNavigationBarColor(COL_BG);
        } catch (Throwable ignored) {}

        int arrId = getResources().getIdentifier("icon_pack", "array", getPackageName());
        if (arrId != 0) allIcons = getResources().getStringArray(arrId);

        pongReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String target = intent.getStringExtra("target");
                if ("launcher".equals(target)) homeScopeResponded = true;
                else if ("systemui".equals(target)) sysUiScopeResponded = true;
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
        root.setBackgroundColor(COL_BG);
        root.setPadding(dp(12), dp(44), dp(12), dp(12));

        // --- Spacious Samsung One UI Header ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(6), dp(10), dp(14));

        ImageView logoView = new ImageView(this);
        int logoId = getResources().getIdentifier("ic_oneliquid_launcher", "drawable", getPackageName());
        if (logoId == 0) logoId = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (logoId != 0) logoView.setImageResource(logoId);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        logoLp.rightMargin = dp(12);
        header.addView(logoView, logoLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("OneLiquid");
        title.setTextColor(COL_TEXT_PRI);
        title.setTextSize(26f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(ModuleStatus.getOneUiVersionName() + "  •  v" + ModuleStatus.getModuleVersion());
        subtitle.setTextColor(COL_TEXT_SEC);
        subtitle.setTextSize(13f);
        titleCol.addView(subtitle);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        // --- Clean Two-Tab One UI Pill Bar ---
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackground(roundedBg(COL_CARD, 22));
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));

        tabBtnLsposed = makePillTab("Liquid Glass");
        tabBtnIcons = makePillTab("Icons (" + allIcons.length + ")");
        tabBtnLsposed.setOnClickListener(v -> selectTab(0));
        tabBtnIcons.setOnClickListener(v -> selectTab(1));
        tabBar.addView(tabBtnLsposed, new LinearLayout.LayoutParams(0, dp(38), 1f));
        tabBar.addView(tabBtnIcons, new LinearLayout.LayoutParams(0, dp(38), 1f));

        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.bottomMargin = dp(12);
        root.addView(tabBar, tabLp);

        // --- Tab Content ---
        tabLsposedView = buildSettingsTab();
        tabIconsView = buildIconsTab();
        root.addView(tabLsposedView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(tabIconsView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        selectTab(0);
        applyLsposedInteractivityState();
        runStatusChecks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyLsposedInteractivityState();
        runStatusChecks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (pongReceiver != null) unregisterReceiver(pongReceiver); } catch (Throwable ignored) {}
        ioPool.shutdownNow();
    }

    private Button makePillTab(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private void selectTab(int idx) {
        boolean isSettings = (idx == 0);
        tabLsposedView.setVisibility(isSettings ? View.VISIBLE : View.GONE);
        tabIconsView.setVisibility(isSettings ? View.GONE : View.VISIBLE);

        tabBtnLsposed.setBackground(isSettings ? roundedBg(Color.parseColor("#2E2E33"), 18) : roundedBg(Color.TRANSPARENT, 18));
        tabBtnLsposed.setTextColor(isSettings ? COL_TEXT_PRI : COL_TEXT_SEC);

        tabBtnIcons.setBackground(!isSettings ? roundedBg(Color.parseColor("#2E2E33"), 18) : roundedBg(Color.TRANSPARENT, 18));
        tabBtnIcons.setTextColor(!isSettings ? COL_TEXT_PRI : COL_TEXT_SEC);
    }

    private void notifyHooksPrefsChanged() {
        try {
            boolean enabled = prefs.getBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true);
            boolean liquidIcons = prefs.getBoolean(ModuleStatus.KEY_LIQUID_ICONS, true);
            boolean gyro = prefs.getBoolean(ModuleStatus.KEY_GYRO_TILT, true);
            boolean folders = prefs.getBoolean(ModuleStatus.KEY_GLASS_FOLDERS, true);
            boolean sysui = prefs.getBoolean(ModuleStatus.KEY_SYSTEMUI_GLASS, true);
            int frost = prefs.getInt(ModuleStatus.KEY_FROST_AMOUNT, 28);
            int mag = prefs.getInt(ModuleStatus.KEY_MAG_AMOUNT, 62);
            int glow = prefs.getInt(ModuleStatus.KEY_GLOW_AMOUNT, 78);
            int tintColor = prefs.getInt(ModuleStatus.KEY_TINT_COLOR, 0);
            int tintStr = prefs.getInt(ModuleStatus.KEY_TINT_STRENGTH, 0);

            for (String pkg : new String[] { ModuleStatus.PKG_ONEUI_HOME, ModuleStatus.PKG_SYSTEMUI }) {
                Intent i = new Intent(ModuleStatus.ACTION_RELOAD_PREFS);
                i.setPackage(pkg);
                i.putExtra(ModuleStatus.KEY_LSPOSED_ENABLED, enabled);
                i.putExtra(ModuleStatus.KEY_LIQUID_ICONS, liquidIcons);
                i.putExtra(ModuleStatus.KEY_LIVE_REFRACTION, liquidIcons);
                i.putExtra(ModuleStatus.KEY_GYRO_TILT, gyro);
                i.putExtra(ModuleStatus.KEY_GLASS_FOLDERS, folders);
                i.putExtra(ModuleStatus.KEY_SYSTEMUI_GLASS, sysui);
                i.putExtra(ModuleStatus.KEY_FROST_AMOUNT, frost);
                i.putExtra(ModuleStatus.KEY_MAG_AMOUNT, mag);
                i.putExtra(ModuleStatus.KEY_GLOW_AMOUNT, glow);
                i.putExtra(ModuleStatus.KEY_TINT_COLOR, tintColor);
                i.putExtra(ModuleStatus.KEY_TINT_STRENGTH, tintStr);
                sendBroadcast(i);
            }
        } catch (Throwable ignored) {}
    }

    private void applyLsposedInteractivityState() {
        boolean moduleActive = ModuleStatus.isModuleActive();
        boolean masterOn = prefs.getBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true);
        boolean controlsEnabled = moduleActive && masterOn;

        // Guide user clearly when LSPosed module is not enabled; hide when active!
        if (setupGuideCard != null) {
            setupGuideCard.setVisibility(moduleActive ? View.GONE : View.VISIBLE);
        }
        if (masterBar != null) {
            masterBar.setBackground(roundedBg(controlsEnabled ? COL_MASTER_ON : COL_MASTER_OFF, 26));
            masterBar.setAlpha(moduleActive ? 1.0f : 0.45f);
        }
        if (masterSwitch != null) {
            masterSwitch.setEnabled(moduleActive);
        }
        if (masterLabel != null) {
            masterLabel.setText(moduleActive ? (masterOn ? "On" : "Off") : "Off (LSPosed required)");
            masterLabel.setTextColor(controlsEnabled ? COL_TEXT_PRI : COL_TEXT_SEC);
        }
        if (slidersCard != null) slidersCard.setAlpha(controlsEnabled ? 1.0f : 0.42f);
        if (togglesCard != null) togglesCard.setAlpha(controlsEnabled ? 1.0f : 0.42f);

        for (View v : lsposedControls) {
            v.setEnabled(controlsEnabled);
        }
        boolean hasTint = prefs.getInt(ModuleStatus.KEY_TINT_COLOR, 0) != 0;
        if (tintStrengthRow != null) {
            tintStrengthRow.setAlpha((controlsEnabled && hasTint) ? 1.0f : 0.40f);
        }
        if (tintStrengthSeek != null) {
            tintStrengthSeek.setEnabled(controlsEnabled && hasTint);
        }
    }

    private View buildSettingsTab() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // --- 1. Setup Guide Card (only visible when LSPosed module is NOT active) ---
        setupGuideCard = new LinearLayout(this);
        setupGuideCard.setOrientation(LinearLayout.VERTICAL);
        setupGuideCard.setBackground(roundedStrokeBg(COL_CARD, Color.parseColor("#3E8BFF"), 26));
        setupGuideCard.setPadding(dp(20), dp(18), dp(20), dp(18));

        TextView guideTitle = new TextView(this);
        guideTitle.setText("Enable in LSPosed to customize glass effects");
        guideTitle.setTextColor(COL_TEXT_PRI);
        guideTitle.setTextSize(15.5f);
        guideTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        setupGuideCard.addView(guideTitle);

        TextView guideSteps = new TextView(this);
        guideSteps.setText(
                "Live magnification, clarity sliders, and refracting glass require root + LSPosed. "
                        + "Without root, you can still apply all " + allIcons.length + " static icons from the Icons tab.\n\n"
                        + "1. Open LSPosed Manager → Modules → OneLiquid\n"
                        + "2. Turn it on and select One UI Home & SystemUI\n"
                        + "3. Restart One UI Home below");
        guideSteps.setTextColor(COL_TEXT_SEC);
        guideSteps.setTextSize(13f);
        guideSteps.setLineSpacing(dp(2), 1.1f);
        guideSteps.setPadding(0, dp(8), 0, dp(12));
        setupGuideCard.addView(guideSteps);

        Button recheckGuideBtn = makeOneUiPillButton("Check again", Color.parseColor("#2B3B52"), COL_TEXT_PRI);
        recheckGuideBtn.setOnClickListener(v -> {
            applyLsposedInteractivityState();
            runStatusChecks();
        });
        setupGuideCard.addView(recheckGuideBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));

        LinearLayout.LayoutParams guideLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        guideLp.bottomMargin = dp(12);
        content.addView(setupGuideCard, guideLp);

        // --- 2. One UI Master On/Off Switch Bar ---
        masterBar = new LinearLayout(this);
        masterBar.setOrientation(LinearLayout.HORIZONTAL);
        masterBar.setGravity(Gravity.CENTER_VERTICAL);
        masterBar.setPadding(dp(22), dp(16), dp(18), dp(16));

        masterLabel = new TextView(this);
        masterLabel.setTextSize(17.5f);
        masterLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        masterBar.addView(masterLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        masterSwitch = new Switch(this);
        masterSwitch.setChecked(prefs.getBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true));
        styleSwitch(masterSwitch);
        masterSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, isChecked).commit();
            notifyHooksPrefsChanged();
            applyLsposedInteractivityState();
        });
        masterBar.addView(masterSwitch);
        masterBar.setOnClickListener(v -> {
            if (masterSwitch.isEnabled()) masterSwitch.toggle();
        });

        LinearLayout.LayoutParams masterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        masterLp.bottomMargin = dp(8);
        content.addView(masterBar, masterLp);

        // --- 3. Glass Appearance Sliders Card ---
        content.addView(makeSectionHeader("Glass appearance"));
        slidersCard = new LinearLayout(this);
        slidersCard.setOrientation(LinearLayout.VERTICAL);
        slidersCard.setBackground(roundedBg(COL_CARD, 26));
        slidersCard.setPadding(0, dp(6), 0, dp(6));

        slidersCard.addView(makeSliderRow(
                "Clarity & frost",
                "Clear liquid",
                "Frosted",
                ModuleStatus.KEY_FROST_AMOUNT,
                28));
        slidersCard.addView(makeDivider());
        slidersCard.addView(makeSliderRow(
                "Magnification & refraction",
                "Subtle",
                "Strong",
                ModuleStatus.KEY_MAG_AMOUNT,
                62));
        slidersCard.addView(makeDivider());
        slidersCard.addView(makeSliderRow(
                "Corner light glow",
                "Off",
                "Bright",
                ModuleStatus.KEY_GLOW_AMOUNT,
                78));
        slidersCard.addView(makeDivider());
        slidersCard.addView(makeTintPickerRow());
        slidersCard.addView(makeDivider());
        tintStrengthRow = makeTintStrengthSliderRow();
        slidersCard.addView(tintStrengthRow);

        content.addView(slidersCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- 4. Simplified Effects Toggles Card ---
        content.addView(makeSectionHeader("Effects"));
        togglesCard = new LinearLayout(this);
        togglesCard.setOrientation(LinearLayout.VERTICAL);
        togglesCard.setBackground(roundedBg(COL_CARD, 26));
        togglesCard.setPadding(0, dp(4), 0, dp(4));

        togglesCard.addView(makeToggleRow(
                "Liquid Glass icons",
                "Floating frosted glyphs with live glass lens",
                ModuleStatus.KEY_LIQUID_ICONS, true));
        togglesCard.addView(makeDivider());
        togglesCard.addView(makeToggleRow(
                "Gyroscope tilt glow",
                "Corner specular highlights react as you tilt",
                ModuleStatus.KEY_GYRO_TILT, true));
        togglesCard.addView(makeDivider());
        togglesCard.addView(makeToggleRow(
                "Glass folders",
                "Apply Liquid Glass to Home Screen folders",
                ModuleStatus.KEY_GLASS_FOLDERS, true));
        togglesCard.addView(makeDivider());
        togglesCard.addView(makeToggleRow(
                "SystemUI glass rims",
                "Subtle specular rims on Quick Settings & notifications",
                ModuleStatus.KEY_SYSTEMUI_GLASS, true));

        content.addView(togglesCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- 5. Module Status & Restart Card ---
        LinearLayout statusHdrRow = new LinearLayout(this);
        statusHdrRow.setOrientation(LinearLayout.HORIZONTAL);
        statusHdrRow.setGravity(Gravity.CENTER_VERTICAL);
        statusHdrRow.addView(makeSectionHeader("LSPosed status"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView recheckLink = new TextView(this);
        recheckLink.setText("Refresh");
        recheckLink.setTextColor(COL_ACCENT);
        recheckLink.setTextSize(13f);
        recheckLink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        recheckLink.setPadding(dp(16), dp(14), dp(20), dp(8));
        recheckLink.setOnClickListener(v -> runStatusChecks());
        statusHdrRow.addView(recheckLink);
        content.addView(statusHdrRow);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackground(roundedBg(COL_CARD, 26));
        statusCard.setPadding(dp(20), dp(14), dp(20), dp(16));

        statusListContainer = new LinearLayout(this);
        statusListContainer.setOrientation(LinearLayout.VERTICAL);
        statusCard.addView(statusListContainer);

        // Restart One UI Home & Restart SystemUI pill buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(14), 0, 0);

        restartHomeBtn = makeOneUiPillButton("Restart Home", Color.parseColor("#2A2A30"), COL_TEXT_PRI);
        restartHomeBtn.setOnClickListener(v -> confirmAndRestart("One UI Home", "killall com.sec.android.app.launcher || am force-stop com.sec.android.app.launcher"));

        restartSysUiBtn = makeOneUiPillButton("Restart SystemUI", COL_ACCENT, Color.WHITE);
        restartSysUiBtn.setOnClickListener(v -> confirmAndRestart("SystemUI", "killall com.android.systemui || pkill -f com.android.systemui"));

        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        b1Lp.rightMargin = dp(8);
        btnRow.addView(restartHomeBtn, b1Lp);
        btnRow.addView(restartSysUiBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        statusCard.addView(btnRow);

        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.bottomMargin = dp(18);
        content.addView(statusCard, stLp);

        sv.addView(content);
        return sv;
    }

    private Button makeOneUiPillButton(String label, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(textColor);
        b.setBackground(roundedBg(bgColor, 22));
        b.setStateListAnimator(null);
        b.setPadding(dp(16), 0, dp(16), 0);
        return b;
    }

    private View makeSliderRow(String titleText, String leftHint, String rightHint, String prefKey, int defVal) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(14), dp(20), dp(14));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(COL_TEXT_PRI);
        title.setTextSize(15.5f);
        topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int curVal = prefs.getInt(prefKey, defVal);
        TextView valLbl = new TextView(this);
        valLbl.setText(curVal + "%");
        valLbl.setTextColor(COL_ACCENT);
        valLbl.setTextSize(14f);
        valLbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        topRow.addView(valLbl);
        wrap.addView(topRow);

        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(curVal);
        sb.setPadding(dp(6), dp(10), dp(6), dp(4));
        styleSeekBar(sb);
        lsposedControls.add(sb);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valLbl.setText(progress + "%");
                if (fromUser) {
                    prefs.edit().putInt(prefKey, progress).apply();
                    notifyHooksPrefsChanged();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(prefKey, seekBar.getProgress()).commit();
                notifyHooksPrefsChanged();
            }
        });
        wrap.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout hints = new LinearLayout(this);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        TextView lh = new TextView(this);
        lh.setText(leftHint);
        lh.setTextColor(COL_TEXT_SEC);
        lh.setTextSize(11.5f);
        hints.addView(lh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView rh = new TextView(this);
        rh.setText(rightHint);
        rh.setTextColor(COL_TEXT_SEC);
        rh.setTextSize(11.5f);
        hints.addView(rh);
        wrap.addView(hints);

        return wrap;
    }

    private static final String[] TINT_NAMES = {
            "None", "Blue", "Cyan", "Indigo", "Purple", "Pink", "Red", "Amber", "Mint", "Dark"
    };
    private static final int[] TINT_COLORS = {
            0,
            Color.parseColor("#3B82F6"),
            Color.parseColor("#06B6D4"),
            Color.parseColor("#6366F1"),
            Color.parseColor("#A855F7"),
            Color.parseColor("#EC4899"),
            Color.parseColor("#EF4444"),
            Color.parseColor("#F59E0B"),
            Color.parseColor("#10B981"),
            Color.parseColor("#181C24")
    };

    private View makeTintPickerRow() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(14), dp(20), dp(14));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Glass tint");
        title.setTextColor(COL_TEXT_PRI);
        title.setTextSize(15.5f);
        topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tintNameLbl = new TextView(this);
        tintNameLbl.setTextColor(COL_ACCENT);
        tintNameLbl.setTextSize(14f);
        tintNameLbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        topRow.addView(tintNameLbl);
        wrap.addView(topRow);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setPadding(0, dp(12), 0, dp(2));

        LinearLayout swatches = new LinearLayout(this);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        tintSwatchViews.clear();

        for (int i = 0; i < TINT_COLORS.length; i++) {
            final int col = TINT_COLORS[i];
            TextView dot = new TextView(this);
            dot.setGravity(Gravity.CENTER);
            dot.setTextSize(13f);
            dot.setTextColor(COL_TEXT_SEC);
            if (col == 0) dot.setText("✕");
            dot.setTag(col);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.rightMargin = dp(10);
            dot.setLayoutParams(lp);
            dot.setOnClickListener(v -> selectTintColor(col));
            lsposedControls.add(dot);
            tintSwatchViews.add(dot);
            swatches.addView(dot);
        }

        // Custom RGB tint button
        TextView customBtn = new TextView(this);
        customBtn.setText("+");
        customBtn.setGravity(Gravity.CENTER);
        customBtn.setTextSize(18f);
        customBtn.setTextColor(COL_TEXT_PRI);
        customBtn.setTag(-1);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(34), dp(34));
        customBtn.setLayoutParams(clp);
        customBtn.setOnClickListener(v -> showCustomTintDialog());
        lsposedControls.add(customBtn);
        tintSwatchViews.add(customBtn);
        swatches.addView(customBtn);

        hsv.addView(swatches);
        wrap.addView(hsv);
        refreshTintSwatches();
        return wrap;
    }

    private void selectTintColor(int color) {
        SharedPreferences.Editor ed = prefs.edit().putInt(ModuleStatus.KEY_TINT_COLOR, color);
        if (color != 0 && prefs.getInt(ModuleStatus.KEY_TINT_STRENGTH, 0) == 0) {
            ed.putInt(ModuleStatus.KEY_TINT_STRENGTH, 45);
            if (tintStrengthSeek != null) tintStrengthSeek.setProgress(45);
            if (tintStrengthValLbl != null) tintStrengthValLbl.setText("45%");
        }
        ed.commit();
        refreshTintSwatches();
        applyLsposedInteractivityState();
        notifyHooksPrefsChanged();
    }

    private void refreshTintSwatches() {
        int cur = prefs.getInt(ModuleStatus.KEY_TINT_COLOR, 0);
        String name = null;
        for (int i = 0; i < TINT_COLORS.length; i++) {
            if (TINT_COLORS[i] == cur) {
                name = TINT_NAMES[i];
                break;
            }
        }
        boolean isCustom = (cur != 0 && name == null);
        if (tintNameLbl != null) {
            tintNameLbl.setText(isCustom ? String.format("#%06X", (0xFFFFFF & cur)) : (name != null ? name : "None"));
        }
        for (View v : tintSwatchViews) {
            int tag = (v.getTag() instanceof Integer) ? (Integer) v.getTag() : 0;
            if (tag == -1) {
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(isCustom ? cur : Color.parseColor("#2E2E34"));
                bg.setStroke(dp(isCustom ? 3 : 1), isCustom ? Color.WHITE : Color.parseColor("#55555E"));
                v.setBackground(bg);
            } else {
                boolean sel = (tag == cur);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(tag == 0 ? Color.parseColor("#26262C") : tag);
                bg.setStroke(dp(sel ? 3 : 1), sel ? Color.WHITE : Color.parseColor("#44FFFFFF"));
                v.setBackground(bg);
            }
        }
    }

    private void showCustomTintDialog() {
        int cur = prefs.getInt(ModuleStatus.KEY_TINT_COLOR, 0);
        if (cur == 0) cur = Color.parseColor("#3B82F6");
        final int[] rgb = new int[] { Color.red(cur), Color.green(cur), Color.blue(cur) };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(16), dp(22), dp(8));

        LinearLayout prevRow = new LinearLayout(this);
        prevRow.setOrientation(LinearLayout.HORIZONTAL);
        prevRow.setGravity(Gravity.CENTER_VERTICAL);
        prevRow.setPadding(0, 0, 0, dp(14));

        final View prevDot = new View(this);
        LinearLayout.LayoutParams pdLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        pdLp.rightMargin = dp(12);
        prevRow.addView(prevDot, pdLp);

        final TextView hexLbl = new TextView(this);
        hexLbl.setTextColor(COL_TEXT_PRI);
        hexLbl.setTextSize(16f);
        hexLbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        prevRow.addView(hexLbl);
        box.addView(prevRow);

        final Runnable updatePrev = () -> {
            int c = Color.rgb(rgb[0], rgb[1], rgb[2]);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(c);
            gd.setStroke(dp(2), Color.WHITE);
            prevDot.setBackground(gd);
            hexLbl.setText(String.format("#%06X", (0xFFFFFF & c)));
        };
        updatePrev.run();

        String[] chLabels = { "Red", "Green", "Blue" };
        int[] chColors = { Color.parseColor("#EF4444"), Color.parseColor("#10B981"), Color.parseColor("#3B82F6") };
        for (int idx = 0; idx < 3; idx++) {
            final int ch = idx;
            TextView lbl = new TextView(this);
            lbl.setText(chLabels[idx]);
            lbl.setTextColor(COL_TEXT_SEC);
            lbl.setTextSize(12.5f);
            box.addView(lbl);

            SeekBar sb = new SeekBar(this);
            sb.setMax(255);
            sb.setProgress(rgb[idx]);
            if (Build.VERSION.SDK_INT >= 21) {
                sb.setProgressTintList(ColorStateList.valueOf(chColors[idx]));
                sb.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            }
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    rgb[ch] = progress;
                    updatePrev.run();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            box.addView(sb);
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Custom glass tint")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d, w) -> selectTintColor(Color.rgb(rgb[0], rgb[1], rgb[2])))
                .show();
    }

    private View makeTintStrengthSliderRow() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(14), dp(20), dp(14));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Tint strength");
        title.setTextColor(COL_TEXT_PRI);
        title.setTextSize(15.5f);
        topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int curVal = prefs.getInt(ModuleStatus.KEY_TINT_STRENGTH, 0);
        tintStrengthValLbl = new TextView(this);
        tintStrengthValLbl.setText(curVal + "%");
        tintStrengthValLbl.setTextColor(COL_ACCENT);
        tintStrengthValLbl.setTextSize(14f);
        tintStrengthValLbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        topRow.addView(tintStrengthValLbl);
        wrap.addView(topRow);

        tintStrengthSeek = new SeekBar(this);
        tintStrengthSeek.setMax(100);
        tintStrengthSeek.setProgress(curVal);
        tintStrengthSeek.setPadding(dp(6), dp(10), dp(6), dp(4));
        styleSeekBar(tintStrengthSeek);
        lsposedControls.add(tintStrengthSeek);

        tintStrengthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tintStrengthValLbl.setText(progress + "%");
                if (fromUser) {
                    prefs.edit().putInt(ModuleStatus.KEY_TINT_STRENGTH, progress).apply();
                    notifyHooksPrefsChanged();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(ModuleStatus.KEY_TINT_STRENGTH, seekBar.getProgress()).commit();
                notifyHooksPrefsChanged();
            }
        });
        wrap.addView(tintStrengthSeek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout hints = new LinearLayout(this);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        TextView lh = new TextView(this);
        lh.setText("Subtle");
        lh.setTextColor(COL_TEXT_SEC);
        lh.setTextSize(11.5f);
        hints.addView(lh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView rh = new TextView(this);
        rh.setText("Vivid");
        rh.setTextColor(COL_TEXT_SEC);
        rh.setTextSize(11.5f);
        hints.addView(rh);
        wrap.addView(hints);

        return wrap;
    }

    private View makeToggleRow(String titleText, String subText, String prefKey, boolean defVal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(16), dp(14));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(COL_TEXT_PRI);
        t.setTextSize(15.5f);
        col.addView(t);

        TextView s = new TextView(this);
        s.setText(subText);
        s.setTextColor(COL_TEXT_SEC);
        s.setTextSize(12.5f);
        s.setPadding(0, dp(2), dp(12), 0);
        col.addView(s);

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(prefKey, defVal));
        styleSwitch(sw);
        lsposedControls.add(sw);
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            SharedPreferences.Editor ed = prefs.edit().putBoolean(prefKey, isChecked);
            if (ModuleStatus.KEY_LIQUID_ICONS.equals(prefKey)) {
                ed.putBoolean(ModuleStatus.KEY_LIVE_REFRACTION, isChecked);
            }
            ed.commit();
            notifyHooksPrefsChanged();
        });
        row.addView(sw);
        row.setOnClickListener(v -> {
            if (sw.isEnabled()) sw.toggle();
        });
        return row;
    }

    private void addStatusRow(String label, String value, int dotColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        View dot = new View(this);
        dot.setBackground(roundedBg(dotColor, 5));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(10);
        row.addView(dot, dotLp);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(COL_TEXT_PRI);
        lbl.setTextSize(14f);
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(COL_TEXT_SEC);
        val.setTextSize(13f);
        row.addView(val);

        statusListContainer.addView(row);
    }

    private void runStatusChecks() {
        homeScopeResponded = false;
        sysUiScopeResponded = false;
        statusListContainer.removeAllViews();
        addStatusRow("Checking status...", "", COL_MUTED);

        try {
            Intent p1 = new Intent(ModuleStatus.ACTION_PING_HOME);
            p1.setPackage(ModuleStatus.PKG_ONEUI_HOME);
            sendBroadcast(p1);
            Intent p2 = new Intent(ModuleStatus.ACTION_PING_SYSUI);
            p2.setPackage(ModuleStatus.PKG_SYSTEMUI);
            sendBroadcast(p2);
        } catch (Throwable ignored) {}

        ioPool.execute(() -> {
            final boolean hasRoot = ModuleStatus.checkRootAvailable();
            rootAvailable = hasRoot;
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}

            ui.post(() -> {
                statusListContainer.removeAllViews();
                boolean modActive = ModuleStatus.isModuleActive();
                boolean homeOk = homeScopeResponded;
                boolean sysUiOk = sysUiScopeResponded;

                addStatusRow("LSPosed module",
                        modActive ? "Active" : "Not enabled",
                        modActive ? COL_GREEN : COL_AMBER);
                addStatusRow("One UI Home",
                        homeOk ? "Connected" : (modActive ? "Restart Home" : "Inactive"),
                        homeOk ? COL_GREEN : (modActive ? COL_AMBER : COL_MUTED));
                addStatusRow("SystemUI",
                        sysUiOk ? "Connected" : (modActive ? "Restart SystemUI" : "Inactive"),
                        sysUiOk ? COL_GREEN : (modActive ? COL_AMBER : COL_MUTED));
                addStatusRow("Root access",
                        hasRoot ? "Available" : "Unavailable",
                        hasRoot ? COL_GREEN : COL_MUTED);

                restartHomeBtn.setEnabled(hasRoot);
                restartHomeBtn.setAlpha(hasRoot ? 1.0f : 0.42f);
                restartSysUiBtn.setEnabled(hasRoot);
                restartSysUiBtn.setAlpha(hasRoot ? 1.0f : 0.42f);
                applyLsposedInteractivityState();
            });
        });
    }

    private void confirmAndRestart(String targetLabel, String rootCmd) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Restart " + targetLabel + "?")
                .setMessage("Your screen will briefly reload to apply changes.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restart", (d, w) -> {
                    Toast.makeText(this, "Restarting " + targetLabel + "...", Toast.LENGTH_SHORT).show();
                    ioPool.execute(() -> {
                        boolean ok = ModuleStatus.runRootCommand(rootCmd);
                        ui.post(() -> {
                            if (ok) {
                                ui.postDelayed(this::runStatusChecks, 1600);
                            } else {
                                Toast.makeText(this, "Root permission denied", Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .show();
    }

    // --- Icons Tab (No-Root Theme Park & Icon Browser) ---
    private View buildIconsTab() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        // Apply Card (No Root)
        LinearLayout applyCard = new LinearLayout(this);
        applyCard.setOrientation(LinearLayout.HORIZONTAL);
        applyCard.setGravity(Gravity.CENTER_VERTICAL);
        applyCard.setBackground(roundedBg(COL_CARD, 24));
        applyCard.setPadding(dp(18), dp(14), dp(14), dp(14));

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("Theme Park (No Root)");
        t.setTextColor(COL_TEXT_PRI);
        t.setTextSize(15f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        infoCol.addView(t);

        TextView s = new TextView(this);
        s.setText("Apply all " + allIcons.length + " static glass icons");
        s.setTextColor(COL_TEXT_SEC);
        s.setTextSize(12.5f);
        infoCol.addView(s);
        applyCard.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button applyBtn = makeOneUiPillButton("Apply", COL_ACCENT, Color.WHITE);
        applyBtn.setOnClickListener(v -> openThemeParkOrLauncher());
        applyCard.addView(applyBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));

        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.bottomMargin = dp(10);
        wrap.addView(applyCard, aLp);

        // Clean One UI Search Bar
        EditText search = new EditText(this);
        search.setHint("Search icons");
        search.setHintTextColor(COL_TEXT_SEC);
        search.setTextColor(COL_TEXT_PRI);
        search.setSingleLine(true);
        search.setTextSize(14.5f);
        search.setPadding(dp(18), dp(11), dp(18), dp(11));
        search.setBackground(roundedBg(COL_CARD, 22));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = dp(10);
        wrap.addView(search, searchLp);

        for (String name : allIcons) filtered.add(name);

        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(14));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setBackground(roundedBg(COL_CARD_SUBTLE, 24));
        grid.setPadding(dp(12), dp(14), dp(12), dp(14));
        grid.setClipToPadding(false);

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int pos) { return filtered.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                LinearLayout cell;
                ImageView iv;
                TextView tv;
                if (convertView instanceof LinearLayout) {
                    cell = (LinearLayout) convertView;
                    iv = (ImageView) cell.getChildAt(0);
                    tv = (TextView) cell.getChildAt(1);
                } else {
                    cell = new LinearLayout(MainActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    iv = new ImageView(MainActivity.this);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    cell.addView(iv, new LinearLayout.LayoutParams(dp(58), dp(58)));
                    tv = new TextView(MainActivity.this);
                    tv.setTextColor(COL_TEXT_SEC);
                    tv.setTextSize(11f);
                    tv.setGravity(Gravity.CENTER);
                    tv.setSingleLine(true);
                    tv.setPadding(0, dp(4), 0, 0);
                    cell.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                String name = filtered.get(pos);
                int id = getResources().getIdentifier(name, "drawable", getPackageName());
                if (id != 0) iv.setImageResource(id);
                else iv.setImageDrawable(null);

                String pretty = name.replaceFirst("^(ios_|ic_|si_)", "").replace("_", " ");
                tv.setText(pretty.length() > 12 ? pretty.substring(0, 12) + "…" : pretty);
                return cell;
            }
        };
        grid.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s.toString().trim().toLowerCase();
                filtered.clear();
                for (String name : allIcons) {
                    if (q.isEmpty() || name.toLowerCase().contains(q)) filtered.add(name);
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        wrap.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return wrap;
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
        } catch (Throwable ignored) {}
        return false;
    }

    private void openThemeParkOrLauncher() {
        String launcher = getDefaultLauncherPackage();
        if (tryDirectApply(launcher)) return;

        Intent tp = getPackageManager().getLaunchIntentForPackage(ModuleStatus.PKG_THEME_PARK);
        if (tp != null) {
            tp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Toast.makeText(this, "Theme Park → Icon → Create New → Iconpack → OneLiquid", Toast.LENGTH_LONG).show();
            startActivity(tp);
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Theme Park required")
                .setMessage("Install Good Lock & Theme Park from the Galaxy Store to apply icons without root.")
                .setPositiveButton("Open Galaxy Store", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("samsungapps://ProductDetail/com.samsung.android.themedesigner")));
                    } catch (Throwable t) {
                        Toast.makeText(this, "Search 'Theme Park' in Galaxy Store", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
