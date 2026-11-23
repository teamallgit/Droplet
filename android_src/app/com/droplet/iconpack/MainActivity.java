package com.droplet.iconpack;

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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simplified Apple iOS 26 Dark Settings companion app for Droplet.
 * - Pure OLED black (#000000), inset grouped #1C1C1E lists (14dp radius), hairline #2C2C2E dividers
 * - iOS System Blue (#0A84FF) accents & iOS System Green (#30D158) toggle switches
 * - Automatic LSPosed detection with concise setup guide when inactive
 * - Live Clarity/Frost, Magnification, Corner Glow, and Glass Tint (None / Auto / 9 Presets / Custom RGB)
 */
public class MainActivity extends Activity {

    // Apple iOS Dark Mode palette
    private static final int COL_BG = Color.parseColor("#000000");
    private static final int COL_GROUP = Color.parseColor("#1C1C1E");
    private static final int COL_SEG_ACTIVE = Color.parseColor("#3A3A3C");
    private static final int COL_SEP = Color.parseColor("#2C2C2E");
    private static final int COL_BLUE = Color.parseColor("#0A84FF");
    private static final int COL_GREEN = Color.parseColor("#30D158");
    private static final int COL_AMBER = Color.parseColor("#FF9F0A");
    private static final int COL_TEXT_PRI = Color.parseColor("#FFFFFF");
    private static final int COL_TEXT_SEC = Color.parseColor("#8E8E93");
    private static final int COL_MUTED = Color.parseColor("#48484A");

    private static final String[] TINT_NAMES = {
            "None", "Auto", "Blue", "Cyan", "Indigo", "Purple", "Pink", "Red", "Amber", "Mint", "Dark"
    };
    private static final int[] TINT_COLORS = {
            0,
            ModuleStatus.TINT_COLOR_AUTO,
            Color.parseColor("#0A84FF"),
            Color.parseColor("#32ADE6"),
            Color.parseColor("#5E5CE6"),
            Color.parseColor("#BF5AF2"),
            Color.parseColor("#FF375F"),
            Color.parseColor("#FF453A"),
            Color.parseColor("#FF9F0A"),
            Color.parseColor("#30D158"),
            Color.parseColor("#181C24")
    };

    private String[] allIcons = new String[0];
    private final List<String> filtered = new ArrayList<>();
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private View tabSettingsView;
    private View tabIconsView;
    private Button segBtnSettings;
    private Button segBtnIcons;

    private LinearLayout setupGuideCard;
    private LinearLayout masterCard;
    private TextView masterLabel;
    private Switch masterSwitch;
    private LinearLayout appearanceCard;
    private LinearLayout effectsCard;
    private View tintStrengthDivider;
    private View tintStrengthRow;
    private SeekBar tintStrengthSeek;
    private TextView tintStrengthValLbl;
    private TextView tintNameLbl;
    private final List<View> tintSwatchViews = new ArrayList<>();
    private final List<View> lsposedControls = new ArrayList<>();
    private final List<Runnable> uiSyncActions = new ArrayList<>();
    private LinearLayout statusListContainer;
    private TextView restartHomeRow;
    private TextView restartSysUiRow;

    private volatile boolean homeScopeResponded = false;
    private volatile boolean sysUiScopeResponded = false;
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
        d.setStroke(Math.max(1, dp(1)), strokeColor);
        return d;
    }

    private View makeInsetSeparator() {
        View v = new View(this);
        v.setBackgroundColor(COL_SEP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView makeIosSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title.toUpperCase(Locale.ROOT));
        tv.setTextColor(COL_TEXT_SEC);
        tv.setTextSize(12f);
        tv.setLetterSpacing(0.04f);
        tv.setPadding(dp(16), dp(20), dp(16), dp(7));
        return tv;
    }

    private void styleIosSwitch(Switch sw) {
        if (Build.VERSION.SDK_INT >= 23) {
            int[][] states = new int[][] {
                    new int[] { android.R.attr.state_checked },
                    new int[] {}
            };
            sw.setThumbTintList(new ColorStateList(states, new int[] { Color.WHITE, Color.parseColor("#F2F2F7") }));
            sw.setTrackTintList(new ColorStateList(states, new int[] { COL_GREEN, Color.parseColor("#39393D") }));
        }
    }

    private void styleIosSlider(SeekBar sb) {
        if (Build.VERSION.SDK_INT >= 21) {
            sb.setProgressTintList(ColorStateList.valueOf(COL_BLUE));
            sb.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            sb.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#3A3A3C")));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = ModuleStatus.getPrefs(this);
        if (prefs.contains("opt_glass_widgets")) {
            prefs.edit()
                    .remove("opt_glass_widgets")
                    .putBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true)
                    .putBoolean(ModuleStatus.KEY_LIQUID_ICONS, true)
                    .putBoolean(ModuleStatus.KEY_LIVE_REFRACTION, true)
                    .putBoolean(ModuleStatus.KEY_GYRO_TILT, true)
                    .putBoolean(ModuleStatus.KEY_GLASS_FOLDERS, true)
                    .putBoolean(ModuleStatus.KEY_SYSTEMUI_GLASS, true)
                    .putInt(ModuleStatus.KEY_FROST_AMOUNT, 22)
                    .putInt(ModuleStatus.KEY_MAG_AMOUNT, 62)
                    .putInt(ModuleStatus.KEY_GLOW_AMOUNT, 78)
                    .commit();
        }

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
        root.setPadding(dp(16), dp(42), dp(16), dp(10));

        // --- Minimalist iOS Large Navigation Header ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(4), dp(2), dp(14));

        ImageView logo = new ImageView(this);
        int logoId = getResources().getIdentifier("ic_droplet_launcher", "drawable", getPackageName());
        if (logoId == 0) logoId = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (logoId != 0) logo.setImageResource(logoId);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        logoLp.rightMargin = dp(10);
        header.addView(logo, logoLp);

        TextView title = new TextView(this);
        title.setText("Droplet");
        title.setTextColor(COL_TEXT_PRI);
        title.setTextSize(28f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView verPill = new TextView(this);
        verPill.setText("v" + ModuleStatus.getModuleVersion());
        verPill.setTextColor(COL_TEXT_SEC);
        verPill.setTextSize(12f);
        verPill.setBackground(roundedBg(COL_GROUP, 12));
        verPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        header.addView(verPill);
        root.addView(header);

        // --- iOS Segmented Control ---
        LinearLayout segBar = new LinearLayout(this);
        segBar.setOrientation(LinearLayout.HORIZONTAL);
        segBar.setBackground(roundedBg(COL_GROUP, 10));
        segBar.setPadding(dp(3), dp(3), dp(3), dp(3));

        segBtnSettings = makeSegmentBtn("Glass");
        segBtnIcons = makeSegmentBtn("Icons (" + allIcons.length + ")");
        segBtnSettings.setOnClickListener(v -> selectTab(0));
        segBtnIcons.setOnClickListener(v -> selectTab(1));
        segBar.addView(segBtnSettings, new LinearLayout.LayoutParams(0, dp(32), 1f));
        segBar.addView(segBtnIcons, new LinearLayout.LayoutParams(0, dp(32), 1f));

        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segLp.bottomMargin = dp(14);
        root.addView(segBar, segLp);

        // --- Tab Content ---
        tabSettingsView = buildSettingsTab();
        tabIconsView = buildIconsTab();
        root.addView(tabSettingsView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(tabIconsView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        selectTab(0);
        applyLsposedState();
        runStatusChecks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyLsposedState();
        runStatusChecks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (pongReceiver != null) unregisterReceiver(pongReceiver); } catch (Throwable ignored) {}
        ioPool.shutdownNow();
    }

    private Button makeSegmentBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13.5f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null);
        b.setPadding(dp(6), 0, dp(6), 0);
        return b;
    }

    private void selectTab(int idx) {
        boolean isGlass = (idx == 0);
        tabSettingsView.setVisibility(isGlass ? View.VISIBLE : View.GONE);
        tabIconsView.setVisibility(isGlass ? View.GONE : View.VISIBLE);

        segBtnSettings.setBackground(isGlass ? roundedBg(COL_SEG_ACTIVE, 8) : roundedBg(Color.TRANSPARENT, 8));
        segBtnSettings.setTextColor(isGlass ? COL_TEXT_PRI : COL_TEXT_SEC);

        segBtnIcons.setBackground(!isGlass ? roundedBg(COL_SEG_ACTIVE, 8) : roundedBg(Color.TRANSPARENT, 8));
        segBtnIcons.setTextColor(!isGlass ? COL_TEXT_PRI : COL_TEXT_SEC);
    }

    private void notifyHooksPrefsChanged() {
        try {
            boolean enabled = prefs.getBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true);
            boolean liquidIcons = prefs.getBoolean(ModuleStatus.KEY_LIQUID_ICONS, true);
            boolean gyro = prefs.getBoolean(ModuleStatus.KEY_GYRO_TILT, true);
            boolean folders = prefs.getBoolean(ModuleStatus.KEY_GLASS_FOLDERS, true);
            boolean sysui = prefs.getBoolean(ModuleStatus.KEY_SYSTEMUI_GLASS, true);
            int frost = prefs.getInt(ModuleStatus.KEY_FROST_AMOUNT, 22);
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

    private void applyLsposedState() {
        boolean moduleActive = ModuleStatus.isModuleActive();
        boolean masterOn = prefs.getBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true);
        boolean controlsEnabled = moduleActive && masterOn;

        if (setupGuideCard != null) {
            setupGuideCard.setVisibility(moduleActive ? View.GONE : View.VISIBLE);
        }
        if (masterCard != null) {
            masterCard.setAlpha(moduleActive ? 1.0f : 0.45f);
        }
        if (masterSwitch != null) {
            masterSwitch.setEnabled(moduleActive);
        }
        if (masterLabel != null) {
            masterLabel.setText(moduleActive ? "Liquid Glass" : "Liquid Glass (LSPosed required)");
        }
        if (appearanceCard != null) appearanceCard.setAlpha(controlsEnabled ? 1.0f : 0.40f);
        if (effectsCard != null) effectsCard.setAlpha(controlsEnabled ? 1.0f : 0.40f);

        for (View v : lsposedControls) {
            v.setEnabled(controlsEnabled);
        }
        boolean hasTint = prefs.getInt(ModuleStatus.KEY_TINT_COLOR, 0) != 0;
        if (tintStrengthDivider != null) tintStrengthDivider.setVisibility(hasTint ? View.VISIBLE : View.GONE);
        if (tintStrengthRow != null) tintStrengthRow.setVisibility(hasTint ? View.VISIBLE : View.GONE);
        if (tintStrengthSeek != null) tintStrengthSeek.setEnabled(controlsEnabled && hasTint);
    }

    private View buildSettingsTab() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // --- 1. Concise iOS Callout Guide (hidden automatically when LSPosed is active!) ---
        setupGuideCard = new LinearLayout(this);
        setupGuideCard.setOrientation(LinearLayout.VERTICAL);
        setupGuideCard.setBackground(roundedStrokeBg(Color.parseColor("#111D2E"), Color.parseColor("#1E3A5F"), 14));
        setupGuideCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView guideTitle = new TextView(this);
        guideTitle.setText("Enable Droplet in LSPosed");
        guideTitle.setTextColor(COL_TEXT_PRI);
        guideTitle.setTextSize(15f);
        guideTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        setupGuideCard.addView(guideTitle);

        TextView guideBody = new TextView(this);
        guideBody.setText(
                "Live Liquid Glass effects require LSPosed. Without root, apply all " + allIcons.length + " static icons from the Icons tab.\n\n"
                        + "1. Open LSPosed Manager → Modules → Droplet\n"
                        + "2. Enable & select One UI Home + SystemUI\n"
                        + "3. Tap Restart One UI Home below");
        guideBody.setTextColor(Color.parseColor("#B0B8C8"));
        guideBody.setTextSize(13f);
        guideBody.setLineSpacing(dp(2), 1.08f);
        guideBody.setPadding(0, dp(6), 0, dp(8));
        setupGuideCard.addView(guideBody);

        TextView recheckLink = new TextView(this);
        recheckLink.setText("Check Again");
        recheckLink.setTextColor(COL_BLUE);
        recheckLink.setTextSize(14f);
        recheckLink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        recheckLink.setPadding(0, dp(4), dp(12), dp(2));
        recheckLink.setOnClickListener(v -> {
            applyLsposedState();
            runStatusChecks();
        });
        setupGuideCard.addView(recheckLink);

        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gLp.bottomMargin = dp(12);
        content.addView(setupGuideCard, gLp);

        // --- 2. Primary iOS Grouped Toggle Row ---
        masterCard = new LinearLayout(this);
        masterCard.setOrientation(LinearLayout.HORIZONTAL);
        masterCard.setGravity(Gravity.CENTER_VERTICAL);
        masterCard.setBackground(roundedBg(COL_GROUP, 14));
        masterCard.setPadding(dp(16), dp(13), dp(14), dp(13));

        masterLabel = new TextView(this);
        masterLabel.setTextColor(COL_TEXT_PRI);
        masterLabel.setTextSize(16f);
        masterLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        masterCard.addView(masterLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        masterSwitch = new Switch(this);
        masterSwitch.setChecked(prefs.getBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true));
        styleIosSwitch(masterSwitch);
        masterSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, isChecked).commit();
            notifyHooksPrefsChanged();
            applyLsposedState();
        });
        masterCard.addView(masterSwitch);
        masterCard.setOnClickListener(v -> {
            if (masterSwitch.isEnabled()) masterSwitch.toggle();
        });
        content.addView(masterCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- 3. APPEARANCE Grouped Card ---
        content.addView(makeIosSectionHeader("Appearance"));
        appearanceCard = new LinearLayout(this);
        appearanceCard.setOrientation(LinearLayout.VERTICAL);
        appearanceCard.setBackground(roundedBg(COL_GROUP, 14));

        appearanceCard.addView(makeSliderRow("Clarity & Frost", "Clear", "Frosted", ModuleStatus.KEY_FROST_AMOUNT, 22));
        appearanceCard.addView(makeInsetSeparator());
        appearanceCard.addView(makeSliderRow("Magnification", "Subtle", "Strong", ModuleStatus.KEY_MAG_AMOUNT, 62));
        appearanceCard.addView(makeInsetSeparator());
        appearanceCard.addView(makeSliderRow("Corner Glow", "Off", "Bright", ModuleStatus.KEY_GLOW_AMOUNT, 78));
        appearanceCard.addView(makeInsetSeparator());
        appearanceCard.addView(makeTintPickerRow());
        tintStrengthDivider = makeInsetSeparator();
        appearanceCard.addView(tintStrengthDivider);
        tintStrengthRow = makeTintStrengthSliderRow();
        appearanceCard.addView(tintStrengthRow);

        content.addView(appearanceCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- 4. EFFECTS Grouped Card (Clean single-line iOS rows) ---
        content.addView(makeIosSectionHeader("Effects"));
        effectsCard = new LinearLayout(this);
        effectsCard.setOrientation(LinearLayout.VERTICAL);
        effectsCard.setBackground(roundedBg(COL_GROUP, 14));

        effectsCard.addView(makeSimpleToggleRow("Liquid Glass Icons", ModuleStatus.KEY_LIQUID_ICONS, true));
        effectsCard.addView(makeInsetSeparator());
        effectsCard.addView(makeSimpleToggleRow("Tilt Light Glow", ModuleStatus.KEY_GYRO_TILT, true));
        effectsCard.addView(makeInsetSeparator());
        effectsCard.addView(makeSimpleToggleRow("Glass Folders", ModuleStatus.KEY_GLASS_FOLDERS, true));
        effectsCard.addView(makeInsetSeparator());
        effectsCard.addView(makeSimpleToggleRow("SystemUI Rims", ModuleStatus.KEY_SYSTEMUI_GLASS, true));

        content.addView(effectsCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- 5. SYSTEM Grouped Card (Status + Clean iOS Blue Action Rows) ---
        LinearLayout sysHdr = new LinearLayout(this);
        sysHdr.setOrientation(LinearLayout.HORIZONTAL);
        sysHdr.setGravity(Gravity.BOTTOM);
        sysHdr.addView(makeIosSectionHeader("System"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refreshBtn = new TextView(this);
        refreshBtn.setText("Refresh");
        refreshBtn.setTextColor(COL_BLUE);
        refreshBtn.setTextSize(13f);
        refreshBtn.setPadding(dp(12), dp(14), dp(16), dp(7));
        refreshBtn.setOnClickListener(v -> runStatusChecks());
        sysHdr.addView(refreshBtn);
        content.addView(sysHdr);

        LinearLayout sysCard = new LinearLayout(this);
        sysCard.setOrientation(LinearLayout.VERTICAL);
        sysCard.setBackground(roundedBg(COL_GROUP, 14));

        statusListContainer = new LinearLayout(this);
        statusListContainer.setOrientation(LinearLayout.VERTICAL);
        statusListContainer.setPadding(dp(16), dp(6), dp(16), dp(6));
        sysCard.addView(statusListContainer);

        sysCard.addView(makeInsetSeparator());
        restartHomeRow = makeIosActionRow("Restart One UI Home");
        restartHomeRow.setOnClickListener(v -> confirmAndRestart("One UI Home", "killall com.sec.android.app.launcher || am force-stop com.sec.android.app.launcher"));
        sysCard.addView(restartHomeRow);

        sysCard.addView(makeInsetSeparator());
        restartSysUiRow = makeIosActionRow("Restart SystemUI");
        restartSysUiRow.setOnClickListener(v -> confirmAndRestart("SystemUI", "killall com.android.systemui || pkill -f com.android.systemui"));
        sysCard.addView(restartSysUiRow);

        sysCard.addView(makeInsetSeparator());
        TextView resetRow = makeIosActionRow("Reset to Defaults");
        resetRow.setTextColor(Color.parseColor("#FF453A"));
        resetRow.setOnClickListener(v -> confirmResetToDefaults());
        sysCard.addView(resetRow);

        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.bottomMargin = dp(24);
        content.addView(sysCard, sLp);

        sv.addView(content);
        return sv;
    }

    private TextView makeIosActionRow(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(COL_BLUE);
        tv.setTextSize(15.5f);
        tv.setPadding(dp(16), dp(13), dp(16), dp(13));
        return tv;
    }

    private View makeSliderRow(String titleText, String leftHint, String rightHint, String prefKey, int defVal) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(COL_TEXT_PRI);
        title.setTextSize(15.5f);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int curVal = prefs.getInt(prefKey, defVal);
        TextView valLbl = new TextView(this);
        valLbl.setText(curVal + "%");
        valLbl.setTextColor(COL_TEXT_SEC);
        valLbl.setTextSize(14f);
        top.addView(valLbl);
        wrap.addView(top);

        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(curVal);
        sb.setPadding(dp(4), dp(8), dp(4), dp(4));
        styleIosSlider(sb);
        lsposedControls.add(sb);
        uiSyncActions.add(() -> {
            int v = prefs.getInt(prefKey, defVal);
            sb.setProgress(v);
            valLbl.setText(v + "%");
        });

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
        lh.setTextSize(11f);
        hints.addView(lh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView rh = new TextView(this);
        rh.setText(rightHint);
        rh.setTextColor(COL_TEXT_SEC);
        rh.setTextSize(11f);
        hints.addView(rh);
        wrap.addView(hints);

        return wrap;
    }

    private View makeTintPickerRow() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Glass Tint");
        title.setTextColor(COL_TEXT_PRI);
        title.setTextSize(15.5f);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tintNameLbl = new TextView(this);
        tintNameLbl.setTextColor(COL_TEXT_SEC);
        tintNameLbl.setTextSize(14f);
        top.addView(tintNameLbl);
        wrap.addView(top);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setPadding(0, dp(11), 0, dp(2));

        LinearLayout swatches = new LinearLayout(this);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        tintSwatchViews.clear();

        for (int col : TINT_COLORS) {
            TextView dot = new TextView(this);
            dot.setGravity(Gravity.CENTER);
            dot.setTextSize(12.5f);
            dot.setTextColor(col == ModuleStatus.TINT_COLOR_AUTO ? Color.WHITE : COL_TEXT_SEC);
            if (col == 0) dot.setText("✕");
            else if (col == ModuleStatus.TINT_COLOR_AUTO) dot.setText("✦");
            dot.setTag(col);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.rightMargin = dp(10);
            dot.setLayoutParams(lp);
            dot.setOnClickListener(v -> selectTintColor(col));
            lsposedControls.add(dot);
            tintSwatchViews.add(dot);
            swatches.addView(dot);
        }

        TextView customBtn = new TextView(this);
        customBtn.setText("+");
        customBtn.setGravity(Gravity.CENTER);
        customBtn.setTextSize(16f);
        customBtn.setTextColor(COL_TEXT_PRI);
        customBtn.setTag(-1);
        customBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
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
        applyLsposedState();
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
                bg.setColor(isCustom ? cur : Color.parseColor("#2C2C2E"));
                bg.setStroke(dp(isCustom ? 2 : 1), isCustom ? Color.WHITE : Color.parseColor("#545458"));
                v.setBackground(bg);
            } else if (tag == ModuleStatus.TINT_COLOR_AUTO) {
                boolean sel = (tag == cur);
                GradientDrawable bg = new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[] { Color.parseColor("#0A84FF"), Color.parseColor("#BF5AF2"), Color.parseColor("#32ADE6") });
                bg.setShape(GradientDrawable.OVAL);
                bg.setStroke(dp(sel ? 2 : 1), sel ? Color.WHITE : Color.parseColor("#55FFFFFF"));
                v.setBackground(bg);
            } else {
                boolean sel = (tag == cur);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(tag == 0 ? Color.parseColor("#2C2C2E") : tag);
                bg.setStroke(dp(sel ? 2 : 1), sel ? Color.WHITE : Color.parseColor("#38FFFFFF"));
                v.setBackground(bg);
            }
        }
    }

    private void showCustomTintDialog() {
        int cur = prefs.getInt(ModuleStatus.KEY_TINT_COLOR, 0);
        if (cur == 0 || cur == ModuleStatus.TINT_COLOR_AUTO) cur = COL_BLUE;
        final int[] rgb = new int[] { Color.red(cur), Color.green(cur), Color.blue(cur) };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(14), dp(20), dp(6));

        LinearLayout prevRow = new LinearLayout(this);
        prevRow.setOrientation(LinearLayout.HORIZONTAL);
        prevRow.setGravity(Gravity.CENTER_VERTICAL);
        prevRow.setPadding(0, 0, 0, dp(12));

        final View prevDot = new View(this);
        LinearLayout.LayoutParams pdLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        pdLp.rightMargin = dp(12);
        prevRow.addView(prevDot, pdLp);

        final TextView hexLbl = new TextView(this);
        hexLbl.setTextColor(COL_TEXT_PRI);
        hexLbl.setTextSize(15.5f);
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
        int[] chColors = { Color.parseColor("#FF453A"), COL_GREEN, COL_BLUE };
        for (int idx = 0; idx < 3; idx++) {
            final int ch = idx;
            TextView lbl = new TextView(this);
            lbl.setText(chLabels[idx]);
            lbl.setTextColor(COL_TEXT_SEC);
            lbl.setTextSize(12f);
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
                .setTitle("Custom Tint")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d, w) -> selectTintColor(Color.rgb(rgb[0], rgb[1], rgb[2])))
                .show();
    }

    private View makeTintStrengthSliderRow() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Tint Strength");
        title.setTextColor(COL_TEXT_PRI);
        title.setTextSize(15.5f);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int curVal = prefs.getInt(ModuleStatus.KEY_TINT_STRENGTH, 0);
        tintStrengthValLbl = new TextView(this);
        tintStrengthValLbl.setText(curVal + "%");
        tintStrengthValLbl.setTextColor(COL_TEXT_SEC);
        tintStrengthValLbl.setTextSize(14f);
        top.addView(tintStrengthValLbl);
        wrap.addView(top);

        tintStrengthSeek = new SeekBar(this);
        tintStrengthSeek.setMax(100);
        tintStrengthSeek.setProgress(curVal);
        tintStrengthSeek.setPadding(dp(4), dp(8), dp(4), dp(4));
        styleIosSlider(tintStrengthSeek);
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
        lh.setTextSize(11f);
        hints.addView(lh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView rh = new TextView(this);
        rh.setText("Vivid");
        rh.setTextColor(COL_TEXT_SEC);
        rh.setTextSize(11f);
        hints.addView(rh);
        wrap.addView(hints);

        return wrap;
    }

    private View makeSimpleToggleRow(String titleText, String prefKey, boolean defVal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(14), dp(11));

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(COL_TEXT_PRI);
        t.setTextSize(15.5f);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(prefKey, defVal));
        styleIosSwitch(sw);
        lsposedControls.add(sw);
        uiSyncActions.add(() -> sw.setChecked(prefs.getBoolean(prefKey, defVal)));
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
        row.setPadding(0, dp(7), 0, dp(7));

        View dot = new View(this);
        dot.setBackground(roundedBg(dotColor, 4));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(10);
        row.addView(dot, dotLp);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(COL_TEXT_PRI);
        lbl.setTextSize(14.5f);
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(COL_TEXT_SEC);
        val.setTextSize(14f);
        row.addView(val);

        statusListContainer.addView(row);
    }

    private void runStatusChecks() {
        homeScopeResponded = false;
        sysUiScopeResponded = false;
        statusListContainer.removeAllViews();
        addStatusRow("Checking...", "", COL_MUTED);

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
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}

            ui.post(() -> {
                statusListContainer.removeAllViews();
                boolean modActive = ModuleStatus.isModuleActive();
                boolean homeOk = homeScopeResponded;
                boolean sysUiOk = sysUiScopeResponded;

                addStatusRow("LSPosed", modActive ? "Active" : "Inactive", modActive ? COL_GREEN : COL_AMBER);
                addStatusRow("One UI Home", homeOk ? "Connected" : (modActive ? "Restart Home" : "Inactive"),
                        homeOk ? COL_GREEN : (modActive ? COL_AMBER : COL_MUTED));
                addStatusRow("SystemUI", sysUiOk ? "Connected" : (modActive ? "Restart SystemUI" : "Inactive"),
                        sysUiOk ? COL_GREEN : (modActive ? COL_AMBER : COL_MUTED));

                restartHomeRow.setEnabled(hasRoot);
                restartHomeRow.setTextColor(hasRoot ? COL_BLUE : COL_MUTED);
                restartSysUiRow.setEnabled(hasRoot);
                restartSysUiRow.setTextColor(hasRoot ? COL_BLUE : COL_MUTED);
                applyLsposedState();
            });
        });
    }

    private void confirmResetToDefaults() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Reset to Defaults?")
                .setMessage("This will restore all glass sliders, tint, and effects to their default values.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (d, w) -> {
                    prefs.edit()
                            .putBoolean(ModuleStatus.KEY_LSPOSED_ENABLED, true)
                            .putBoolean(ModuleStatus.KEY_LIQUID_ICONS, true)
                            .putBoolean(ModuleStatus.KEY_LIVE_REFRACTION, true)
                            .putBoolean(ModuleStatus.KEY_GYRO_TILT, true)
                            .putBoolean(ModuleStatus.KEY_GLASS_FOLDERS, true)
                            .putBoolean(ModuleStatus.KEY_SYSTEMUI_GLASS, true)
                            .putInt(ModuleStatus.KEY_FROST_AMOUNT, 22)
                            .putInt(ModuleStatus.KEY_MAG_AMOUNT, 62)
                            .putInt(ModuleStatus.KEY_GLOW_AMOUNT, 78)
                            .putInt(ModuleStatus.KEY_TINT_COLOR, 0)
                            .putInt(ModuleStatus.KEY_TINT_STRENGTH, 0)
                            .commit();
                    if (masterSwitch != null) masterSwitch.setChecked(true);
                    if (tintStrengthSeek != null) tintStrengthSeek.setProgress(0);
                    if (tintStrengthValLbl != null) tintStrengthValLbl.setText("0%");
                    for (Runnable r : uiSyncActions) r.run();
                    refreshTintSwatches();
                    applyLsposedState();
                    notifyHooksPrefsChanged();
                    Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show();
                })
                .show();
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
                            if (ok) ui.postDelayed(this::runStatusChecks, 1600);
                            else Toast.makeText(this, "Root permission denied", Toast.LENGTH_LONG).show();
                        });
                    });
                })
                .show();
    }

    // --- Icons Tab (Clean iOS Search + Theme Park Apply + Icon Grid) ---
    private View buildIconsTab() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout applyCard = new LinearLayout(this);
        applyCard.setOrientation(LinearLayout.HORIZONTAL);
        applyCard.setGravity(Gravity.CENTER_VERTICAL);
        applyCard.setBackground(roundedBg(COL_GROUP, 14));
        applyCard.setPadding(dp(16), dp(12), dp(14), dp(12));

        TextView t = new TextView(this);
        t.setText("Apply via Theme Park (No Root)");
        t.setTextColor(COL_TEXT_PRI);
        t.setTextSize(15f);
        applyCard.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView applyLink = new TextView(this);
        applyLink.setText("Apply");
        applyLink.setTextColor(COL_BLUE);
        applyLink.setTextSize(15f);
        applyLink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        applyLink.setPadding(dp(10), dp(4), dp(4), dp(4));
        applyCard.addView(applyLink);
        applyCard.setOnClickListener(v -> openThemeParkOrLauncher());

        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.bottomMargin = dp(10);
        wrap.addView(applyCard, aLp);

        EditText search = new EditText(this);
        search.setHint("Search");
        search.setHintTextColor(COL_TEXT_SEC);
        search.setTextColor(COL_TEXT_PRI);
        search.setSingleLine(true);
        search.setTextSize(15f);
        search.setPadding(dp(14), dp(9), dp(14), dp(9));
        search.setBackground(roundedBg(COL_GROUP, 10));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.bottomMargin = dp(10);
        wrap.addView(search, sLp);

        for (String name : allIcons) filtered.add(name);

        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(14));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setBackground(roundedBg(COL_GROUP, 14));
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
                    cell.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
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
            Toast.makeText(this, "Theme Park → Icon → Create New → Iconpack → Droplet", Toast.LENGTH_LONG).show();
            startActivity(tp);
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Theme Park Required")
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
