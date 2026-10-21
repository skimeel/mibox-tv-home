package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.StaticLayout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home dashboard over rotating nature photos: clock, date and weather top-left, the
 * Wi-Fi network name, a verse of the day lower-left (ESV from the ESV API, cached, with the bundled
 * WEB text as the fallback), a dock of pinned apps, and every other app
 * plus Settings in a grid that scrolls up on Down. Wi-Fi and Settings pills top-right, the dock and
 * the grid take D-pad focus; the weather and verse are display only. Long-press OK on an app to
 * rearrange the dock. Plain framework views only (no AndroidX), no background work; the only network
 * use is the weather and the day's ESV verse, fetched while the launcher is in front.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV, grid width, weather and verse settings come from res/values/config.xml.
    // The dock order is saved once edited; config.xml's dock_apps is the first-run default.
    private List<String> dockOrder;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;
    private String weatherCity, weatherLat, weatherLon, weatherTz, verseTranslation;

    static final class App {
        String pkg, label;
        Intent intent;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();
    static final int DOCK_MAX = 8;

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private View header, verseCard;
    private LinearLayout grid;
    private View glow;
    private View gridShade;
    private ImageView wallBack, wallFront;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    static final long WEATHER_INTERVAL_MS = 30 * 60 * 1000;
    // A reading older than this shows as unavailable rather than passing for current weather.
    static final long WEATHER_STALE_MS = 90 * 60 * 1000;
    static final long DAY_MS = 24 * 60 * 60 * 1000;
    static final int VERSE_CARD_DP = 700, VERSE_MAX_LINES = 4;
    private TextView greeting, clock, ampm, date, weather, title, wifiPill, verseText, verseRef, appsLabel;
    private final List<String[]> verses = new ArrayList<>();
    private long verseDay = -1;
    // ESV: cached passages expire after this many days (Crossway asks for periodic cache clearing).
    static final long ESV_CACHE_DAYS = 30;
    private TextView esvNotice;
    private boolean esvShown, esvBusy;
    private boolean weatherBusy;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow popup;
    // Move mode: the dock app being slid with Left/Right, its highlight, and the hint under the dock.
    private String movingPkg;
    private View moveRing;
    private TextView moveHint;

    // Registered only while resumed, so the weather check it runs happens only in the foreground.
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); updateWeather(); refreshEsv(); }
    };
    // The default network's capabilities, kept current by netCallback while the launcher is started.
    private NetworkCapabilities netCaps;
    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { netCaps = c; updateWifi(); }
        @Override public void onLost(Network n) { netCaps = null; updateWifi(); }
    };
    // Wi-Fi radio and association changes, which tell "connecting" apart from "not connected".
    private final BroadcastReceiver wifiState = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String saved = getPreferences(MODE_PRIVATE).getString("dock_order", null);
        dockOrder = new ArrayList<>(Arrays.asList(saved != null
                ? TextUtils.split(saved, ",") : getResources().getStringArray(R.array.dock_apps)));
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        cols = getResources().getInteger(R.integer.columns);
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        weatherCity = getString(R.string.weather_city);
        weatherLat = getString(R.string.weather_latitude);
        weatherLon = getString(R.string.weather_longitude);
        weatherTz = getString(R.string.weather_timezone);
        verseTranslation = getString(R.string.verse_translation);
        loadVerses(getString(R.string.verse_file));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        keepVersesThatFit();
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Network watching runs only while the launcher is started, never in the background.
        IntentFilter wf = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
        wf.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        wf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(wifiState, wf);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) {
            // Start from the current state so the pill doesn't flash "offline" until the first callback.
            Network active = cm.getActiveNetwork();
            netCaps = active == null ? null : cm.getNetworkCapabilities(active);
            // Callbacks arrive on the main thread.
            cm.registerDefaultNetworkCallback(netCallback, handler);
        }
        updateWifi();
    }

    @Override
    protected void onStop() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) cm.unregisterNetworkCallback(netCallback);
        unregisterReceiver(wifiState);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        tf.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        updateWeather();
        refreshEsv();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null) last = homeFocus();
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
        if (popup != null) popup.dismiss();
        if (movingPkg != null) finishMove(false);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (popup != null) popup.dismiss();
        if (movingPkg != null) finishMove(false);
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, weather) and bottom (verse, dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (IOException ignored) { }

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        // ScrollView makes itself focusable; never let focus park on the container instead of a tile.
        scroll.setFocusable(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, verse, title and dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        header = buildHeader();
        hero.addView(header, lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        verseCard = buildVerseCard();
        bottom.addView(verseCard, llp(dp(VERSE_CARD_DP), -2, 0, 0, 0, dp(12)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        // Sits in the gap under the dock, so showing it doesn't shift the layout.
        moveHint = text(12, "sans-serif-medium", 0xE6FFFFFF);
        moveHint.setText("Use Left/Right to move, OK to save, Back to cancel");
        moveHint.setShadowLayer(dp(8), 0, dp(1), 0xCC000000);
        moveHint.setVisibility(View.GONE);
        hero.addView(moveHint, lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, dp(7)));

        // Crossway's copyright notice and esv.org, in the same gap, shown whenever the card shows ESV text.
        esvNotice = text(8, "sans-serif", 0x99FFFFFF);
        esvNotice.setText(getString(R.string.esv_copyright) + "  ·  " + getString(R.string.esv_link));
        esvNotice.setGravity(Gravity.CENTER);
        esvNotice.setVisibility(View.GONE);
        hero.addView(esvNotice, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(3)));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ALL APPS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);

        weather = text(12, "sans-serif", 0xCCFFFFFF);
        weather.setSingleLine(true);
        left.addView(weather, llp(-2, -2, 0, dp(10), 0, 0));
        for (TextView t : new TextView[]{greeting, clock, ampm, date, weather}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        wifiPill = pill("Wi-Fi", null); // updateWifi sets the network name and state icon
        wifiPill.setCompoundDrawablePadding(dp(7));
        // Android 9 builds without a Wi-Fi screen get the main Settings screen instead.
        wifiPill.setOnClickListener(v -> {
            if (!launch(new Intent(Settings.ACTION_WIFI_SETTINGS))) launch(new Intent(Settings.ACTION_SETTINGS));
        });
        TextView settings = pill("Settings", "⚙");
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        for (TextView p : new TextView[]{wifiPill, settings}) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph == null ? label : glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(14), 0, dp(14), 0);
        p.setFocusable(true);
        p.setDefaultFocusHighlightEnabled(false); // the pill draws its own focus state
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            TextView t = (TextView) v;
            t.setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            t.setCompoundDrawableTintList(ColorStateList.valueOf(t.getCurrentTextColor())); // icon follows the text
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    private View buildVerseCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(14), dp(40), dp(14));
        // Soft dark wash, heaviest behind the text, so the verse reads on bright photos too.
        GradientDrawable wash = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xB3000000, 0x8C000000, 0x00000000});
        wash.setCornerRadius(dp(20));
        card.setBackground(wash);

        verseText = text(17, "serif", 0xF2FFFFFF);
        verseText.setLineSpacing(dp(4), 1f);
        verseText.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        card.addView(verseText);

        verseRef = text(10, "sans-serif-medium", 0xB3FFFFFF);
        verseRef.setSingleLine(true);
        verseRef.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        verseRef.setLetterSpacing(0.2f);
        card.addView(verseRef, llp(-2, -2, 0, dp(10), 0, 0));
        return card;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        // A rebuild (say, an app installed mid-move) resets the tiles, so it ends move mode unsaved.
        if (movingPkg != null) endMoveMode();
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Map<String, App> byPkg = new HashMap<>();
        // TV apps first, then any app that only has a regular (phone-style) launcher entry.
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
                byPkg.put(ai.packageName, app(pm, ri, new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setComponent(new ComponentName(ai.packageName, ai.name))));
            }
        }
        // A Settings tile, standing in for the settings app if it is listed already.
        ResolveInfo settings = pm.resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
        if (settings != null && settings.activityInfo != null) {
            App s = app(pm, settings, new Intent(Settings.ACTION_SETTINGS));
            s.label = "Settings";
            byPkg.put(s.pkg, s);
        }

        List<App> dock = new ArrayList<>();
        // Saved apps that are no longer installed are skipped.
        for (String p : dockOrder) if (dock.size() < DOCK_MAX && byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        // Note what has focus before the tiles go; removing them would otherwise hand focus to a pill.
        View had = getCurrentFocus();
        String keepPkg = had != null && had.getTag() instanceof App ? ((App) had.getTag()).pkg : null;
        boolean keepPill = had != null && pills.contains(had);

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        // Tiles share the band, but a short dock keeps five-app-sized tiles centred rather than
        // growing tall enough to push the dock up over the clock.
        int band = getResources().getDisplayMetrics().widthPixels - dp(96) - dockRow.getPaddingLeft() - dockRow.getPaddingRight();
        int slots = Math.max(dock.size(), 5);
        int tileW = (band - (slots - 1) * dp(20)) / slots;
        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(tileW, -2);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == dock.size() - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        // Keep focus on the same app (or pill) across a rebuild, else the first dock app.
        View target = keepPill ? had : findCard(keepPkg);
        if (target == null) target = homeFocus();
        if (target != null) target.requestFocus();
    }

    /** The tile for a package, dock first; null if it isn't shown. */
    private View findCard(String pkg) {
        if (pkg == null) return null;
        for (View c : dockCards) if (((App) c.getTag()).pkg.equals(pkg)) return c;
        for (View c : gridCards) if (((App) c.getTag()).pkg.equals(pkg)) return c;
        return null;
    }

    /** Where focus lands by default: the first dock app, or the first grid app if the dock is empty. */
    private View homeFocus() {
        return !dockCards.isEmpty() ? dockCards.get(0) : !gridCards.isEmpty() ? gridCards.get(0) : null;
    }

    private App app(PackageManager pm, ResolveInfo ri, Intent launch) {
        ActivityInfo ai = ri.activityInfo;
        App a = new App();
        a.pkg = ai.packageName;
        a.label = String.valueOf(ri.loadLabel(pm));
        a.intent = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Drawable banner = ai.loadBanner(pm);
        if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
        a.isBanner = banner != null;
        a.art = banner != null ? banner : ri.loadIcon(pm);
        a.color = sampleColor(a.pkg, a.art);
        return a;
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setDefaultFocusHighlightEnabled(false); // the ring and zoom show focus
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(app.intent)));
        // Long-press OK: move or remove a dock app, or add a grid app to the dock.
        card.setOnLongClickListener(v -> { showAppMenu(v, app, inDock); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                // Down from the pills returns to the dock app focus came up from.
                for (View p : pills) p.setNextFocusDownId(v.getId());
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /**
     * Explicit D-pad targets: pills, dock and grid, one row per press, ends of rows don't wrap.
     * Up from the dock goes to the pills, Down from the dock opens the grid.
     */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        int topId = pills.get(0).getId();
        View firstBelow = d > 0 ? dockCards.get(0) : g > 0 ? gridCards.get(0) : null;
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(firstBelow != null ? firstBelow.getId() : p.getId());
        }
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor) {
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        showMenu(anchor, rows, Gravity.END, false);
    }

    /** A small menu of rows under the anchor, or above it; Back or a choice closes it. */
    private void showMenu(View anchor, List<TextView> rows, int gravity, boolean above) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> {
            popup = null;
            View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
            if (last != null) last.requestFocus();
        });
        popup = pw;
        int y = dp(8);
        if (above) {
            // Clear the focused tile's zoom too, so the menu never covers the app it is for.
            list.measure(View.MeasureSpec.makeMeasureSpec(dp(200), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            y = -(anchor.getHeight() + list.getMeasuredHeight() + dp(16));
        }
        pw.showAsDropDown(anchor, 0, y, gravity);
        rows.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- dock editing

    private void showAppMenu(View card, App app, boolean inDock) {
        List<String> shown = shownDock();
        int i = shown.indexOf(app.pkg);
        List<TextView> rows = new ArrayList<>();
        if (inDock) {
            if (shown.size() > 1) rows.add(inputRow("Move", () -> startMove(card, app.pkg)));
            rows.add(inputRow("Remove from dock", () -> {
                shown.remove(i);
                saveDock(shown, shown.isEmpty() ? null : shown.get(Math.min(i, shown.size() - 1)));
            }));
        } else if (shown.size() < DOCK_MAX) {
            rows.add(inputRow("Add to dock", () -> { shown.add(app.pkg); saveDock(shown, app.pkg); }));
        } else {
            rows.add(inputRow("Dock is full (" + DOCK_MAX + " apps)", () -> { }));
        }
        // The dock sits near the bottom of the screen, so its menu opens above the tile.
        showMenu(card, rows, Gravity.START, inDock);
    }

    /** Move mode: highlight the tile, show the hint, and let Left/Right slide it (see dispatchKeyEvent). */
    private void startMove(View card, String pkg) {
        movingPkg = pkg;
        card.requestFocus();
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(4), 0xFF5AC8FA);
        moveRing = new View(this);
        moveRing.setBackground(ring);
        ((ViewGroup) card).addView(moveRing, new FrameLayout.LayoutParams(-1, -1));
        card.animate().translationY(-dp(10)).setDuration(180).start();
        moveHint.setVisibility(View.VISIBLE);
        esvNotice.setVisibility(View.GONE);
    }

    /** Slide the moving tile one place left (-1) or right (+1), animating both tiles into place. */
    private void moveBy(int delta) {
        View card = findCard(movingPkg);
        int i = dockCards.indexOf(card), j = i + delta;
        if (i < 0 || j < 0 || j >= dockCards.size()) return;
        View box = (View) card.getParent(), other = (View) dockCards.get(j).getParent();
        int boxLeft = box.getLeft(), otherLeft = other.getLeft();
        // Re-insert the neighbour on the far side, so the focused tile itself is never detached.
        dockRow.removeView(other);
        dockRow.addView(other, i);
        Collections.swap(dockCards, i, j);
        for (int k = 0; k < dockRow.getChildCount(); k++) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) dockRow.getChildAt(k).getLayoutParams();
            p.setMargins(k == 0 ? 0 : dp(10), 0, k == dockRow.getChildCount() - 1 ? 0 : dp(10), 0);
        }
        dockRow.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                dockRow.getViewTreeObserver().removeOnPreDrawListener(this);
                for (View v : new View[]{box, other}) {
                    v.setTranslationX((v == box ? boxLeft : otherLeft) - v.getLeft());
                    v.animate().translationX(0).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
                }
                return true;
            }
        });
    }

    /** OK saves the new position; Back (or leaving home) puts the app back where it started. */
    private void finishMove(boolean save) {
        String pkg = movingPkg;
        endMoveMode();
        if (save) {
            dockOrder = shownDock();
            getPreferences(MODE_PRIVATE).edit().putString("dock_order", TextUtils.join(",", dockOrder)).apply();
            wireFocus();
        } else {
            buildApps();
        }
        View c = findCard(pkg);
        if (c != null) {
            c.requestFocus();
            for (View p : pills) p.setNextFocusDownId(c.getId());
        }
    }

    /** Clear move mode's highlight and hint without touching the order. */
    private void endMoveMode() {
        View card = findCard(movingPkg);
        movingPkg = null;
        if (moveRing != null && moveRing.getParent() != null) ((ViewGroup) moveRing.getParent()).removeView(moveRing);
        moveRing = null;
        if (card != null) card.animate().translationY(0).setDuration(180).start();
        moveHint.setVisibility(View.GONE);
        esvNotice.setVisibility(esvShown ? View.VISIBLE : View.GONE);
    }

    /** Packages in the dock as shown now, left to right. */
    private List<String> shownDock() {
        List<String> shown = new ArrayList<>();
        for (View c : dockCards) shown.add(((App) c.getTag()).pkg);
        return shown;
    }

    /** Save the new dock order, rebuild the dock and grid, and focus the app that was acted on. */
    private void saveDock(List<String> order, String focusPkg) {
        dockOrder = order;
        getPreferences(MODE_PRIVATE).edit().putString("dock_order", TextUtils.join(",", order)).apply();
        buildApps();
        View c = findCard(focusPkg);
        if (c == null) c = homeFocus();
        if (c != null) c.requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        r.setDefaultFocusHighlightEnabled(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
        updateVerse(now);
    }

    /**
     * Network name and state icon on the Wi-Fi pill: working, no internet (not validated),
     * connecting, or not connected / Wi-Fi off. Ethernet gets its own icon when it is in use.
     */
    private void updateWifi() {
        NetworkCapabilities c = netCaps;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo info = null;
        try {
            if (wm != null) info = wm.getConnectionInfo();
        } catch (SecurityException ignored) { }
        boolean working = c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        int icon;
        String name;
        boolean dim = false;
        if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            icon = working ? R.drawable.ic_ethernet : R.drawable.ic_ethernet_alert;
            name = "Ethernet";
        } else if (c != null) {
            icon = working ? R.drawable.ic_wifi : R.drawable.ic_wifi_alert;
            name = "Wi-Fi";
            String s = info == null ? null : info.getSSID();
            // The name needs the location permission granted at install; otherwise it reads <unknown ssid>.
            if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
        } else if (wm != null && wm.isWifiEnabled() && info != null && isConnecting(info.getSupplicantState())) {
            icon = R.drawable.ic_wifi;
            dim = true;
            name = "Connecting…";
        } else {
            icon = R.drawable.ic_wifi_off;
            name = wm != null && !wm.isWifiEnabled() ? "Wi-Fi off" : "Offline";
        }
        Drawable d = getDrawable(icon).mutate();
        d.setAlpha(dim ? 100 : 255);
        d.setBounds(0, 0, dp(16), dp(16));
        wifiPill.setCompoundDrawablesRelative(d, null, null, null);
        wifiPill.setCompoundDrawableTintList(ColorStateList.valueOf(wifiPill.getCurrentTextColor()));
        wifiPill.setText(name);
    }

    /** Joining a Wi-Fi network but not yet the default network (associating up to getting an address). */
    private static boolean isConnecting(SupplicantState s) {
        if (s == null) return false;
        switch (s) {
            case AUTHENTICATING: case ASSOCIATING: case ASSOCIATED:
            case FOUR_WAY_HANDSHAKE: case GROUP_HANDSHAKE: case COMPLETED:
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- weather

    private void updateWeather() {
        showWeather();
        refreshWeather();
    }

    /** The cached reading while online and recent; otherwise a quiet "unavailable". */
    private void showWeather() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        String text = p.getString("weather_text", null);
        long age = System.currentTimeMillis() - p.getLong("weather_at", 0);
        if (text != null && age >= 0 && age < WEATHER_STALE_MS && isOnline()) {
            weather.setText(text);
            weather.setTextColor(0xCCFFFFFF);
        } else {
            weather.setText(weatherCity + "  ·  Weather unavailable");
            weather.setTextColor(0x66FFFFFF);
        }
    }

    /** Ask Open-Meteo at most once per 30 minutes; called only while the launcher is resumed. */
    private void refreshWeather() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long since = now - p.getLong("weather_tried", 0);
        if (weatherBusy || !isOnline() || (since >= 0 && since < WEATHER_INTERVAL_MS)) return;
        weatherBusy = true;
        p.edit().putLong("weather_tried", now).apply();
        new Thread(() -> {
            String text = fetchWeather();
            runOnUiThread(() -> {
                weatherBusy = false;
                if (text != null) {
                    p.edit().putString("weather_text", text).putLong("weather_at", System.currentTimeMillis()).apply();
                }
                if (!isDestroyed()) showWeather();
            });
        }).start();
    }

    /** One forecast request on a worker thread; null on any failure. */
    private String fetchWeather() {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + weatherLat
                    + "&longitude=" + weatherLon
                    + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
                    + "&forecast_days=1&timezone=" + URLEncoder.encode(weatherTz, "UTF-8"));
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) body.append(line);
            }
            JSONObject j = new JSONObject(body.toString());
            JSONObject now = j.getJSONObject("current");
            JSONObject day = j.getJSONObject("daily");
            return String.format(Locale.getDefault(), "%s  %d°  %s  ·  H %d°  L %d°", weatherCity,
                    Math.round(now.getDouble("temperature_2m")), describeWeather(now.getInt("weather_code")),
                    Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)),
                    Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)));
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** WMO weather interpretation codes, as used by Open-Meteo. */
    private static String describeWeather(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorms";
            case 96: case 99: return "Thunderstorms, hail";
            default: return "";
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    // ---------------------------------------------------------------- verse

    /** Bundled verses, one "reference<TAB>text" per line; lines starting with # are comments. */
    private void loadVerses(String asset) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(asset), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] v = line.split("\t", 2);
                if (v.length == 2) verses.add(v);
            }
        } catch (IOException ignored) { }
    }

    /**
     * Drop any verse that would need more than four lines on the card, measured with the card's own
     * font, size and width. Verse text is never shortened.
     */
    private void keepVersesThatFit() {
        for (Iterator<String[]> it = verses.iterator(); it.hasNext(); ) if (!fitsCard(it.next()[1])) it.remove();
    }

    /** Whether the text fits the verse card in four lines, laid out exactly as the card would. */
    private boolean fitsCard(String t) {
        int width = dp(VERSE_CARD_DP) - verseCard.getPaddingLeft() - verseCard.getPaddingRight();
        StaticLayout l = StaticLayout.Builder.obtain(t, 0, t.length(), verseText.getPaint(), width)
                .setLineSpacing(verseText.getLineSpacingExtra(), verseText.getLineSpacingMultiplier())
                .setIncludePad(verseText.getIncludeFontPadding())
                .setBreakStrategy(verseText.getBreakStrategy())
                .setHyphenationFrequency(verseText.getHyphenationFrequency())
                .build();
        return l.getLineCount() <= VERSE_MAX_LINES;
    }

    /** One verse per calendar day from the ones that fit, the same all day, chosen by the local date. */
    private void updateVerse(Calendar now) {
        if (verses.isEmpty()) { verseCard.setVisibility(View.GONE); return; }
        long day = (now.getTimeInMillis() + now.get(Calendar.ZONE_OFFSET) + now.get(Calendar.DST_OFFSET)) / DAY_MS;
        if (day == verseDay) return;
        verseDay = day;
        showVerse();
    }

    /** Today's {reference, WEB text} from the bundled list. */
    private String[] todaysVerse() {
        return verses.get((int) (verseDay % verses.size()));
    }

    /**
     * The ESV text for today's reference when it is cached and fits the card; otherwise the bundled
     * WEB text, labelled WEB, plus why the ESV isn't shown when that is the key.
     */
    private void showVerse() {
        String[] v = todaysVerse();
        String esv = cachedEsv(v[0]);
        String label;
        esvShown = esv != null && fitsCard(esv);
        if (esvShown) {
            verseText.setText(esv);
            label = "ESV";
        } else {
            verseText.setText(v[1]);
            label = verseTranslation;
            SharedPreferences p = esvPrefs();
            if (EsvKey.KEY.isEmpty()) label += "  ·  ESV key missing";
            else if (p.getLong("rejected_day", -1) == verseDay && p.getInt("rejected_key", 0) == EsvKey.KEY.hashCode()) {
                label += "  ·  ESV key rejected";
            }
        }
        verseRef.setText((v[0] + "  ·  " + label).toUpperCase(Locale.getDefault()));
        esvNotice.setVisibility(esvShown && movingPkg == null ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- ESV

    private SharedPreferences esvPrefs() {
        return getSharedPreferences("esv", MODE_PRIVATE);
    }

    /** A cached ESV passage for the reference, or null if there is none or it has expired. */
    private String cachedEsv(String ref) {
        SharedPreferences p = esvPrefs();
        long at = p.getLong("at:" + ref, Long.MIN_VALUE);
        if (at == Long.MIN_VALUE || verseDay - at >= ESV_CACHE_DAYS || verseDay < at) return null;
        return p.getString("text:" + ref, null);
    }

    /**
     * Fetch today's passage from the ESV API: only with a key, only while the launcher is resumed
     * (onResume and the minute tick call this), only if it isn't cached, and at most one request a
     * day per key. Any failure leaves the WEB text up.
     */
    private void refreshEsv() {
        if (EsvKey.KEY.isEmpty() || verseDay < 0 || verses.isEmpty() || esvBusy || !isOnline()) return;
        String ref = todaysVerse()[0];
        SharedPreferences p = esvPrefs();
        int keyId = EsvKey.KEY.hashCode(); // tells a rebuilt key apart without storing the key
        if (cachedEsv(ref) != null || (p.getLong("tried_day", -1) == verseDay && p.getInt("tried_key", 0) == keyId)) return;
        esvBusy = true;
        long day = verseDay;
        p.edit().putLong("tried_day", day).putInt("tried_key", keyId).apply();
        new Thread(() -> {
            int[] status = new int[1];
            String text = fetchEsv(ref, status);
            runOnUiThread(() -> {
                esvBusy = false;
                SharedPreferences.Editor e = p.edit();
                if (status[0] == HttpURLConnection.HTTP_UNAUTHORIZED || status[0] == HttpURLConnection.HTTP_FORBIDDEN) {
                    e.putLong("rejected_day", day).putInt("rejected_key", keyId);
                }
                if (text != null) {
                    pruneEsvCache(e, day);
                    e.putString("text:" + ref, text).putLong("at:" + ref, day);
                }
                e.apply();
                if (!isDestroyed() && day == verseDay) showVerse();
            });
        }).start();
    }

    /**
     * Drop expired passages and any reference no longer in the bundled list. The cache can then
     * hold at most the list's 30 references (32 verses), far below the API's 500-verse limit.
     */
    private void pruneEsvCache(SharedPreferences.Editor e, long today) {
        Set<String> refs = new HashSet<>();
        for (String[] v : verses) refs.add(v[0]);
        for (Map.Entry<String, ?> en : esvPrefs().getAll().entrySet()) {
            if (!en.getKey().startsWith("at:") || !(en.getValue() instanceof Long)) continue;
            String ref = en.getKey().substring(3);
            long at = (Long) en.getValue();
            if (!refs.contains(ref) || today - at >= ESV_CACHE_DAYS || today < at) {
                e.remove("at:" + ref).remove("text:" + ref);
            }
        }
    }

    /**
     * One passage request on a worker thread: plain text with no references, verse numbers,
     * headings, footnotes or copyright line. status[0] gets the HTTP code; null on any failure.
     */
    private String fetchEsv(String ref, int[] status) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://api.esv.org/v3/passage/text/?q=" + URLEncoder.encode(ref.replace('–', '-'), "UTF-8")
                    + "&include-passage-references=false&include-verse-numbers=false&include-first-verse-numbers=false"
                    + "&include-footnotes=false&include-footnote-body=false&include-headings=false"
                    + "&include-short-copyright=false&include-copyright=false"
                    + "&include-passage-horizontal-lines=false&include-heading-horizontal-lines=false"
                    + "&indent-paragraphs=0&indent-poetry=false&indent-declares=0&indent-psalm-doxology=0");
            c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("Authorization", "Token " + EsvKey.KEY);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            status[0] = c.getResponseCode();
            if (status[0] != HttpURLConnection.HTTP_OK) return null;
            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) body.append(line).append('\n');
            }
            JSONArray passages = new JSONObject(body.toString()).getJSONArray("passages");
            if (passages.length() != 1) return null;
            // Line breaks and indents are layout only; the words stay exactly as sent.
            String t = passages.getString(0).trim().replaceAll("\\s+", " ");
            return t.isEmpty() || t.length() > 600 ? null : t;
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + wallpapers[wallIndex];
        // Two thirds of the screen width: soft enough to sit under the scrim, about 1.8 MB at 1080p.
        int targetW = Math.max(1, getResources().getDisplayMetrics().widthPixels * 2 / 3);
        new Thread(() -> {
            Bitmap bm = null;
            try {
                bm = decodeScaled(name, targetW);
            } catch (IOException ignored) { }
            Bitmap result = bm;
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    /** Decode at about targetW wide: subsample by powers of two, then let the decoder scale the rest. */
    private Bitmap decodeScaled(String asset, int targetW) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getAssets().open(asset)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        if (o.outWidth / sample > targetW) {
            o.inScaled = true;
            o.inDensity = o.outWidth / sample;
            o.inTargetDensity = targetW;
        }
        Bitmap bm;
        try (InputStream in = getAssets().open(asset)) {
            bm = BitmapFactory.decodeStream(in, null, o);
        }
        // The scaling above stamps a made-up density on the bitmap; drop it so it draws by pixels.
        if (bm != null) bm.setDensity(Bitmap.DENSITY_NONE);
        return bm;
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Finish any fade still running, so at most two photos (old and new) are ever held.
        wallFront.animate().cancel();
        wallFront.setAlpha(1f);
        wallBack.setImageDrawable(null);
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(wallBack.getDrawable() == null ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
    }

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        verseCard.setAlpha(0f);
        verseCard.animate().alpha(1f).setDuration(600).setStartDelay(260).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Move mode takes the remote: Left/Right slide, OK saves, Back cancels, Up/Down do nothing.
        if (movingPkg != null) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN, up = e.getAction() == KeyEvent.ACTION_UP;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (down) moveBy(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) moveBy(1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (up) finishMove(true);
                    return true;
                case KeyEvent.KEYCODE_BACK: if (up) finishMove(false); return true;
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_MENU: return true;
                default: break;
            }
        }
        // Menu key on the remote opens the Inputs dropdown (it falls back to Settings).
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && popup == null) {
            showInputs(wifiPill);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
