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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
 * Wi-Fi network name with today's schedule under it, a verse of the day lower-left (ESV from the ESV API, cached, with the bundled
 * BSB text as the fallback), a dock of pinned apps, and every other app
 * plus Settings in a grid that scrolls up on Down. Round Wi-Fi and Settings buttons top-right, the dock and
 * the grid take D-pad focus; the weather and verse are display only. Long-press OK on an app to
 * rearrange the dock. Plain framework views only (no AndroidX), no background work; the only network
 * use is the weather and the day's ESV verse, fetched while the launcher is in front.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV, grid width, weather and verse settings come from res/values/config.xml.
    // The dock order is saved once edited; config.xml's dock_apps is the first-run default. Apps a
    // save left beyond the cap (or not installed) are kept in order in dockOverflow, so raising
    // DOCK_MAX brings them back.
    private List<String> dockOrder, dockOverflow;
    // The grid's order once rearranged (null until then: alphabetical). Dock apps and the Settings
    // tile are not in it; apps it doesn't list go at the end, alphabetically.
    private List<String> gridOrder;
    private List<App> gridApps = new ArrayList<>();
    // Grid apps hidden from the remote (saved), on top of hidden_apps in config.xml. Reveal mode shows
    // them dimmed in their places until it's turned off or the launcher restarts.
    private final Set<String> userHidden = new java.util.LinkedHashSet<>();
    private boolean revealHidden;
    // Every grid app including hidden ones, in grid order (Settings excluded), so saving a move
    // keeps the hidden apps' places.
    private List<String> gridAllPkgs = new ArrayList<>();
    // icon_aliases from config.xml: app package -> {package whose banner/icon to show, label or null}.
    private final Map<String, String[]> iconAliases = new HashMap<>();
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;
    private String weatherCity, weatherLat, weatherLon, weatherTz, verseTranslation;
    private int windyGustKmh;

    static final class App {
        String pkg, label;
        Intent intent;
        Drawable art;
        boolean isBanner, isSettings, hiddenByUser;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();
    static final int DOCK_MAX = 6;

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private View header, verseCard;
    private LinearLayout grid;
    private View glow;
    private View gridShade;
    private boolean gridDimmed;     // gridShade is fully up while the grid is in view
    // Night mode: between these hours the photo is dimmed by raising gridShade a fixed amount.
    // NIGHT_SHADE is gridShade's alpha at rest at night (its 70% black, so the photo gets 28% darker).
    static final float NIGHT_SHADE = 0.4f;
    static final long NIGHT_FADE_MS = 1500;
    private boolean nightEnabled;
    private int nightStart, nightEnd;
    private Boolean nightOn;        // null until the first clock update, which sets it without a fade
    private ImageView wallBack, wallFront;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    static final long WEATHER_INTERVAL_MS = 30 * 60 * 1000;
    // After a failed request (often the network still coming up after boot), retry sooner a few times.
    static final long WEATHER_RETRY_MS = 5 * 60 * 1000;
    static final int WEATHER_QUICK_RETRIES = 3;
    // A reading older than this shows as unavailable rather than passing for current weather.
    static final long WEATHER_STALE_MS = 90 * 60 * 1000;
    static final long DAY_MS = 24 * 60 * 60 * 1000;
    // 560dp keeps the verse card clear of the schedule card on the right at any height.
    static final int VERSE_CARD_DP = 560, VERSE_MAX_LINES = 4;
    private TextView greeting, clock, ampm, date, weather, title, verseText, verseRef, appsLabel;
    private TextView weatherPlace;  // the city, ahead of the weather icon
    private boolean showRain;       // "Rain from 3 pm" in place of the high and low (show_rain_line)
    private int rainThresholdPct;
    private ImageView weatherIcon;
    private int weatherIconRes;     // the drawable it shows, so a minute tick does not reload it
    private View wifiButton;      // the focusable round button
    private ImageView[] wifiIconViews; // its 48px and 52px icon layers, swapped as the network state changes
    private int wifiIcon;
    private final List<String[]> allVerses = new ArrayList<>(); // as bundled
    private final List<String[]> verses = new ArrayList<>();    // the ones that fit the card
    // Height the verse text may take without the card reaching up over the weather line; set after
    // the first layout (it depends on font scale and screen), 0 until then.
    private int verseTextMaxPx;
    private long verseDay = -1;
    // ESV: cached passages expire after this many days (Crossway asks for periodic cache clearing).
    static final long ESV_CACHE_DAYS = 30;
    // A failed ESV request may be retried an hour later, up to three requests a day in all.
    static final int ESV_TRIES_PER_DAY = 3;
    static final long ESV_RETRY_MS = 60 * 60 * 1000;
    // The notice line runs edge to edge with this margin; it shrinks to fit, but not below the floor.
    static final int NOTICE_SIDE_DP = 8;
    static final float NOTICE_MAX_SP = 8f, NOTICE_MIN_SP = 5.5f;
    private TextView esvNotice; // null when the notice is off (config.xml or SHOW_ESV_NOTICE=false in esv.env)
    private boolean esvShown, esvBusy;
    // YouVersion verse of the day: the year list is used for this many days after it was fetched, and
    // only in the year it was fetched; the day is the day of the year in Melbourne. Failed requests
    // retry on the ESV schedule (an hour apart, three a day).
    static final long VOTD_LIST_DAYS = 30;
    static final ZoneId VOTD_ZONE = ZoneId.of("Australia/Melbourne");
    private boolean useVotd, votdBusy;
    private String[] votdByDay;  // passage ids by day of the year from the cached list, or null
    private String votdLoggedId; // an id that couldn't be read, logged once
    private boolean weatherBusy;
    // Schedule card: refreshed like the weather (30 minutes after a success, 5 after a failure,
    // three quick retries), shown from the cache, and marked stale after 3 hours.
    static final long CALENDAR_INTERVAL_MS = 30 * 60 * 1000, CALENDAR_RETRY_MS = 5 * 60 * 1000;
    static final int CALENDAR_QUICK_RETRIES = 3;
    static final long CALENDAR_STALE_MS = 3 * 60 * 60 * 1000;
    private LinearLayout calCard;
    private ZoneId calZone;
    private boolean showEventTitles, calendarBusy;
    private boolean showCountdown;
    private int countdownMinutes;
    private DateTimeFormatter calTime;
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
    // Grid moves rebind apps into the existing tiles, so nothing is detached and focus just moves.
    private boolean movingInGrid;
    private List<App> gridBefore;
    private int moveIndex;

    // Registered only while resumed, so the weather check it runs happens only in the foreground.
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateClock(); updateWifi(); updateWeather(); refreshVotd(); refreshEsv(); refreshCalendar(); showCalendar();
        }
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
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String saved = prefs.getString("dock_order", null), over = prefs.getString("dock_overflow", "");
        dockOrder = new ArrayList<>(Arrays.asList(saved != null
                ? TextUtils.split(saved, ",") : getResources().getStringArray(R.array.dock_apps)));
        dockOverflow = new ArrayList<>(Arrays.asList(TextUtils.split(over, ",")));
        // A saved order longer than the cap is left as it is: buildApps shows the first DOCK_MAX
        // installed apps and the rest fall into the grid. Overflow rejoins only if the cap has grown.
        if (DOCK_MAX > prefs.getInt("dock_cap", DOCK_MAX)) dockOrder.addAll(dockOverflow);
        String grid = prefs.getString("grid_order", null);
        if (grid != null) gridOrder = new ArrayList<>(Arrays.asList(TextUtils.split(grid, ",")));
        userHidden.addAll(Arrays.asList(TextUtils.split(prefs.getString("user_hidden", ""), ",")));
        for (String entry : getResources().getStringArray(R.array.icon_aliases)) {
            // "app=iconApp" or "app=iconApp|Label"
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String target = entry.substring(eq + 1).trim(), label = null;
            int bar = target.indexOf('|');
            if (bar >= 0) { label = target.substring(bar + 1).trim(); target = target.substring(0, bar).trim(); }
            if (!target.isEmpty()) iconAliases.put(entry.substring(0, eq).trim(), new String[]{target, label == null || label.isEmpty() ? null : label});
        }
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        cols = getResources().getInteger(R.integer.columns);
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        weatherCity = getString(R.string.weather_city);
        weatherLat = getString(R.string.weather_latitude);
        weatherLon = getString(R.string.weather_longitude);
        weatherTz = getString(R.string.weather_timezone);
        windyGustKmh = getResources().getInteger(R.integer.windy_gust_kmh);
        showRain = getResources().getBoolean(R.bool.show_rain_line);
        rainThresholdPct = getResources().getInteger(R.integer.rain_threshold_pct);
        nightEnabled = getResources().getBoolean(R.bool.night_mode_enabled);
        nightStart = getResources().getInteger(R.integer.night_start_hour);
        nightEnd = getResources().getInteger(R.integer.night_end_hour);
        // A reading cached by the older text-only weather line has no icon: drop it and fetch again now.
        SharedPreferences wp = getPreferences(MODE_PRIVATE);
        if (wp.contains("weather_text")) wp.edit().remove("weather_text").remove("weather_tried").apply();
        calZone = IcsParser.zoneOr(getString(R.string.calendar_timezone), ZoneId.systemDefault());
        showEventTitles = getResources().getBoolean(R.bool.show_event_titles);
        showCountdown = getResources().getBoolean(R.bool.show_event_countdown);
        countdownMinutes = getResources().getInteger(R.integer.countdown_minutes);
        calTime = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(calZone);
        verseTranslation = getString(R.string.verse_translation);
        loadVerses(getString(R.string.verse_file));
        useVotd = getResources().getBoolean(R.bool.use_youversion_votd) && !YouVersionKey.KEY.isEmpty()
                && !EsvKey.KEY.isEmpty(); // without the ESV the references alone would never be shown
        if (useVotd) votdByDay = Votd.parseYear(votdPrefs().getString("list", ""));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        keepVersesThatFit();
        // Once laid out, measure the real room above the card and drop verses that would not fit it.
        hero.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (hero.getHeight() == 0 || verseRef.getHeight() == 0) return;
                hero.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                View stack = (View) verseCard.getParent();
                int cardBottom = stack.getTop() + verseCard.getBottom();
                int room = cardBottom - header.getBottom() - dp(16);
                verseTextMaxPx = room - verseCard.getPaddingTop() - verseCard.getPaddingBottom()
                        - verseRef.getHeight() - ((LinearLayout.LayoutParams) verseRef.getLayoutParams()).topMargin;
                keepVersesThatFit();
                verseDay = -1; // pick today's verse again from the ones that fit
                updateVerse(Calendar.getInstance());
            }
        });
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
        refreshVotd();
        refreshEsv();
        refreshCalendar();
        showCalendar();
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

        // A soft dark pool behind the clock, date and weather (drawn on the photo, unlike the cards),
        // so they keep 4.5:1 on bright wallpapers. Radial and fading to nothing: no card edge, no
        // layout change. It covers about a third of the screen, not all of it.
        View headerShade = new View(this);
        GradientDrawable shade = new GradientDrawable();
        shade.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        shade.setGradientCenter(0.32f, 0.24f);
        shade.setGradientRadius(dp(330));
        shade.setColors(new int[]{0xB3000000, 0x80000000, 0x00000000});
        headerShade.setBackground(shade);
        hero.addView(headerShade, lp(dp(600), dp(480), Gravity.TOP | Gravity.START, 0, 0, 0, 0));

        header = buildHeader();
        hero.addView(header, lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        // Schedule card, top right under the pills. Display only, never focusable.
        calCard = new LinearLayout(this);
        calCard.setOrientation(LinearLayout.VERTICAL);
        calCard.setPadding(dp(18), dp(14), dp(18), dp(12));
        GradientDrawable calBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8C000000, 0x66000000});
        calBg.setCornerRadius(dp(20));
        calCard.setBackground(calBg);
        // 16dp below the round buttons (36dp top + 48dp buttons).
        hero.addView(calCard, lp(dp(300), -2, Gravity.TOP | Gravity.END, 0, dp(100), dp(48), 0));

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
        title.setEllipsize(TextUtils.TruncateAt.END); // long app names end in "…", not mid-letter
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(10));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        // Slimmer than before around the larger six-app tiles, so a four-line verse still clears the weather line.
        dockRow.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        // Sits in the gap under the dock, so showing it doesn't shift the layout.
        moveHint = text(12, "sans-serif-medium", 0xE6FFFFFF);
        moveHint.setShadowLayer(dp(8), 0, dp(1), 0xCC000000);
        moveHint.setGravity(Gravity.CENTER_VERTICAL);
        moveHint.setCompoundDrawablePadding(dp(8));
        moveHint.setVisibility(View.GONE);
        root.addView(moveHint, lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, dp(7)));

        // Crossway's copyright notice and esv.org: one dim line on the bottom edge, shown whenever the
        // card shows ESV text. Only the spacing is tightened to fit; the wording is never cut.
        // On by default (show_esv_notice), unless the build hid it with SHOW_ESV_NOTICE=false in
        // esv.env. When off the view isn't created, so it never shows or takes space.
        if (getResources().getBoolean(R.bool.show_esv_notice) && !EsvKey.HIDE_NOTICE) {
            esvNotice = text(8, "sans-serif-condensed", 0x73B3B3B3);
            esvNotice.setText(getString(R.string.esv_copyright) + " · " + getString(R.string.esv_link));
            esvNotice.setLetterSpacing(-0.01f);
            esvNotice.setGravity(Gravity.CENTER);
            esvNotice.setIncludeFontPadding(false);
            esvNotice.setVisibility(View.GONE);
            fitNoticeToOneLine();
            hero.addView(esvNotice, lp(-1, -2, Gravity.BOTTOM, dp(NOTICE_SIDE_DP), 0, dp(NOTICE_SIDE_DP), dp(2)));
        }

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

        // City, the day's weather icon, then the temperature and the day's words. The row is always
        // as tall as the icon, so the header (and the verse room measured from it) keeps its height
        // while the icon is hidden. The icon is drawn at its own 24dp size, never scaled.
        LinearLayout weatherRow = new LinearLayout(this);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        weatherRow.setMinimumHeight(dp(24));
        weatherPlace = text(12, "sans-serif", 0xCCFFFFFF);
        weatherPlace.setSingleLine(true);
        weatherRow.addView(weatherPlace);
        weatherIcon = new ImageView(this);
        weatherIcon.setVisibility(View.GONE);
        weatherRow.addView(weatherIcon, llp(dp(24), dp(24), dp(8), 0, dp(8), 0));
        weather = text(12, "sans-serif", 0xCCFFFFFF);
        weather.setSingleLine(true);
        weather.setEllipsize(TextUtils.TruncateAt.END);
        weatherRow.addView(weather);
        left.addView(weatherRow, llp(-2, -2, 0, dp(6), 0, 0));
        for (TextView t : new TextView[]{greeting, clock, ampm, date, weatherPlace, weather}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        wifiButton = roundButton(R.drawable.ic_wifi_off, "Wi-Fi"); // updateWifi sets the state icon
        wifiIconViews = new ImageView[]{(ImageView) ((ViewGroup) wifiButton).getChildAt(1),
                (ImageView) ((ViewGroup) wifiButton).getChildAt(2)};
        // Android 9 builds without a Wi-Fi screen get the main Settings screen instead.
        wifiButton.setOnClickListener(v -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        View settings = roundButton(R.drawable.ic_settings, "Settings");
        settings.setOnClickListener(v -> openSettings(Settings.ACTION_SETTINGS));
        for (View b : new View[]{wifiButton, settings}) {
            right.addView(b, llp(dp(48), dp(48), b == settings ? dp(12) : 0, 0, 0, 0));
            pills.add(b);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    /**
     * A 48dp round "soft glass" icon button: smoked glass (#0B0E14 at 25%) with a 22% white hairline when unfocused,
     * solid #F4F6FA when focused. The icon's colours follow the focus state through its colour
     * state lists (res/color), so nothing is recoloured in code.
     *
     * Only the circle is scaled on focus. The icon is never scaled as a view (that would stretch its
     * rendered bitmap and soften it); instead its padding shrinks so the vector is drawn afresh at
     * the larger size, rounded to whole, even pixels so it stays centred and crisp.
     */
    private View roundButton(int icon, String description) {
        FrameLayout b = new FrameLayout(this);
        b.setId(View.generateViewId());
        b.setFocusable(true);
        b.setClickable(true);
        b.setDefaultFocusHighlightEnabled(false); // the button draws its own focus state
        b.setContentDescription(description);
        b.setClipChildren(false);

        GradientDrawable glass = new GradientDrawable();
        glass.setShape(GradientDrawable.OVAL);
        // A dark tint, not a white one: it keeps the white icon at 6.5:1 or better on every wallpaper
        // (a white tint fell to 3.4:1 on bright ones) while three quarters of the picture shows through.
        glass.setColor(0x400B0E14);
        glass.setStroke(dp(1), 0x38FFFFFF); // GradientDrawable strokes sit inside the bounds
        GradientDrawable solid = new GradientDrawable();
        solid.setShape(GradientDrawable.OVAL);
        solid.setColor(0xFFF4F6FA);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, solid);
        bg.addState(new int[0], glass);
        View circle = new View(this);
        circle.setDuplicateParentStateEnabled(true);
        circle.setBackground(bg); // its oval outline also shapes the elevation shadow
        b.addView(circle, new FrameLayout.LayoutParams(-1, -1));

        // Two copies of the icon, one drawn at 48px and one at 52px, never resized: resizing one
        // drawable 48 -> 52 -> 48px left the unfocused icon soft afterwards. Focus crossfades them.
        int buttonPx = dp(48), iconPx = dp(24);
        int focusedIconPx = Math.round(iconPx * 1.1f / 2) * 2; // 1.1x, whole and even: 48px -> 52px at xhdpi
        ImageView small = iconLayer(icon, (buttonPx - iconPx) / 2);
        ImageView large = iconLayer(icon, (buttonPx - focusedIconPx) / 2);
        large.setAlpha(0f);
        large.setVisibility(View.INVISIBLE);
        b.addView(small, new FrameLayout.LayoutParams(-1, -1));
        b.addView(large, new FrameLayout.LayoutParams(-1, -1));

        b.setOnFocusChangeListener((v, has) -> {
            // One soft elevation shadow, only on the focused button; the icons ride above it.
            circle.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? dp(6) : 0)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(150).start();
            ImageView in = has ? large : small, out = has ? small : large;
            in.setVisibility(View.VISIBLE);
            in.animate().alpha(1f).translationZ(has ? dp(6) : 0)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(150).withEndAction(null).start();
            out.animate().alpha(0f).translationZ(has ? dp(6) : 0)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(150)
                    .withEndAction(() -> { if (out.getAlpha() == 0f) out.setVisibility(View.INVISIBLE); }).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return b;
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
            s.isSettings = true;
            byPkg.put(s.pkg, s);
        }

        // A hidden app that's gone (uninstalled, or now in hidden_apps) is dropped, so a reinstall shows.
        if (userHidden.retainAll(byPkg.keySet())) saveHidden();
        if (userHidden.isEmpty()) revealHidden = false;

        List<App> dock = new ArrayList<>();
        // Saved apps that are no longer installed are skipped.
        for (String p : dockOrder) if (dock.size() < DOCK_MAX && byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        // Dock apps are never hidden.
        boolean unhid = false;
        for (App d : dock) unhid |= userHidden.remove(d.pkg);
        if (unhid) saveHidden();
        if (userHidden.isEmpty()) revealHidden = false;
        List<App> rest = new ArrayList<>(byPkg.values());
        App settingsTile = null;
        for (App a : rest) if (a.isSettings) settingsTile = a;
        rest.remove(settingsTile);
        // Saved grid order first; anything it doesn't list (newly installed) after, alphabetically.
        Collections.sort(rest, (x, y) -> {
            int rx = gridRank(x.pkg), ry = gridRank(y.pkg);
            return rx != ry ? Integer.compare(rx, ry) : x.label.compareToIgnoreCase(y.label);
        });
        gridAllPkgs = new ArrayList<>();
        for (App a : rest) gridAllPkgs.add(a.pkg);
        for (Iterator<App> it = rest.iterator(); it.hasNext(); ) {
            App a = it.next();
            a.hiddenByUser = userHidden.contains(a.pkg);
            if (a.hiddenByUser && !revealHidden) it.remove();
        }
        if (settingsTile != null) rest.add(settingsTile); // always last
        gridApps = rest;

        // Note what has focus before the tiles go; removing them would otherwise hand focus to a pill.
        View had = getCurrentFocus();
        String keepPkg = had != null && had.getTag() instanceof App ? ((App) had.getTag()).pkg : null;
        boolean keepPill = had != null && pills.contains(had);

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        // Tiles are sized for a full dock of DOCK_MAX apps; a shorter dock keeps that size, centred.
        int band = getResources().getDisplayMetrics().widthPixels - dp(96) - dockRow.getPaddingLeft() - dockRow.getPaddingRight();
        int tileW = (band - (DOCK_MAX - 1) * dp(20)) / DOCK_MAX;
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
        // Android's own pick when the window first takes focus with nothing focused. requestFocus()
        // from onCreate/onResume can be refused because the window isn't shown yet (it still reads
        // as touch mode), and Android then chose the first focusable view, the Wi-Fi button.
        View home = homeFocus();
        if (home != null) home.setFocusedByDefault(true); // the old tiles were replaced above
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
        String[] alias = iconAliases.get(a.pkg);
        if (alias != null) {
            // Show another installed app's own banner (or icon); keep this app's art if it isn't installed.
            boolean[] isBanner = new boolean[1];
            Drawable other = installedArt(pm, alias[0], isBanner);
            if (other != null) { a.art = other; a.isBanner = isBanner[0]; }
            if (alias[1] != null) a.label = alias[1];
        }
        a.color = sampleColor(a.pkg, a.art);
        return a;
    }

    /** An installed app's own TV banner, else its icon; null if the app isn't installed. */
    private static Drawable installedArt(PackageManager pm, String pkg, boolean[] isBanner) {
        try {
            Intent tv = pm.getLeanbackLaunchIntentForPackage(pkg);
            Drawable d = tv != null ? pm.getActivityBanner(tv) : null;
            if (d == null) d = pm.getApplicationBanner(pkg);
            isBanner[0] = d != null;
            return d != null ? d : pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        AspectFrame card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setDefaultFocusHighlightEnabled(false); // the ring and zoom show focus
        card.setClickable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        card.bg = new GradientDrawable();
        card.setBackground(card.bg);
        card.img = new ImageView(this);
        card.addView(card.img, new FrameLayout.LayoutParams(-1, -1));
        if (!inDock) {
            // Shown on hidden apps in reveal mode.
            card.badge = text(9, "sans-serif-medium", 0xE6FFFFFF);
            card.badge.setText("Hidden");
            card.badge.setLetterSpacing(0.08f);
            card.badge.setPadding(dp(6), dp(2), dp(7), dp(2));
            card.badge.setGravity(Gravity.CENTER_VERTICAL);
            card.badge.setCompoundDrawablesRelative(icon(R.drawable.ic_hidden, 12), null, null, null);
            card.badge.setCompoundDrawablePadding(dp(4));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(8));
            badgeBg.setColor(0xCC000000);
            card.badge.setBackground(badgeBg);
            card.addView(card.badge, lp(-2, -2, Gravity.TOP | Gravity.START, dp(6), dp(6), 0, 0));
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
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) { box.addView(label, llp(-1, -2, 0, dp(10), 0, 0)); card.label = label; }
        bind(card, app);

        // The listeners read the app from the tile, since a grid move can put another app in it.
        card.setOnClickListener(v -> launch(new Intent(((App) v.getTag()).intent)));
        // Long-press OK: move or remove a dock app; move a grid app or add it to the dock.
        card.setOnLongClickListener(v -> { showAppMenu(v, (App) v.getTag(), inDock); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            App onTile = (App) v.getTag();
            if (inDock) {
                // Down from the pills returns to the dock app focus came up from.
                for (View p : pills) p.setNextFocusDownId(v.getId());
                title.setText(onTile.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(onTile.color);
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

    /** Put an app on a tile: art, background, label and tag. */
    private void bind(AspectFrame card, App app) {
        card.setTag(app);
        card.bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.img.setImageDrawable(app.art);
        card.img.setScaleType(app.isBanner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (app.isBanner) card.img.setPadding(0, 0, 0, 0);
        else card.img.setPadding(dp(22), dp(14), dp(22), dp(14));
        if (card.label != null) card.label.setText(app.label);
        card.img.setAlpha(app.hiddenByUser ? 0.4f : 1f); // only seen in reveal mode
        if (card.badge != null) card.badge.setVisibility(app.hiddenByUser ? View.VISIBLE : View.GONE);
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
                // Back in the grid, a rearranged grid gets it at the end.
                if (gridOrder != null && !gridOrder.contains(app.pkg)) { gridOrder.add(app.pkg); saveGridOrder(); }
                saveDock(shown, shown.isEmpty() ? null : shown.get(Math.min(i, shown.size() - 1)), app.pkg);
            }));
        } else {
            if (!app.isSettings && movableGridCount() > 1) rows.add(inputRow("Move", () -> startGridMove(card, app)));
            if (shown.size() < DOCK_MAX) {
                rows.add(inputRow("Add to dock", () -> {
                    shown.add(app.pkg);
                    if (gridOrder != null && gridOrder.remove(app.pkg)) saveGridOrder(); // dock apps aren't in the grid order
                    if (userHidden.remove(app.pkg)) saveHidden(); // dock apps can't be hidden
                    saveDock(shown, app.pkg, null);
                }));
            } else {
                rows.add(inputRow("Dock is full (" + DOCK_MAX + " apps)", () -> { }));
            }
            if (app.isSettings) {
                // The Settings tile can't be hidden; it switches reveal mode instead.
                if (revealHidden) rows.add(inputRow("Hide hidden apps", () -> setReveal(false)));
                else if (!userHidden.isEmpty()) rows.add(inputRow("Show hidden apps (" + userHidden.size() + ")", () -> setReveal(true)));
            } else if (app.hiddenByUser) {
                rows.add(inputRow("Unhide", () -> setHidden(app.pkg, false)));
            } else {
                rows.add(inputRow("Hide", () -> setHidden(app.pkg, true)));
            }
        }
        // The dock sits near the bottom of the screen, so its menu opens above the tile.
        showMenu(card, rows, Gravity.START, inDock);
    }

    /** Move mode: highlight the tile, show the hint, and let Left/Right slide it (see dispatchKeyEvent). */
    private void startMove(View card, String pkg) {
        movingPkg = pkg;
        movingInGrid = false;
        moveHint.setText("Use Left/Right to move, OK to save, Back to cancel");
        moveHint.setCompoundDrawablesRelative(icon(R.drawable.ic_move_dock, 18), null, null, null);
        card.requestFocus();
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(4), 0xFF5AC8FA);
        moveRing = new View(this);
        moveRing.setBackground(ring);
        ((ViewGroup) card).addView(moveRing, new FrameLayout.LayoutParams(-1, -1));
        card.animate().translationY(-dp(10)).setDuration(180).start();
        moveHint.setVisibility(View.VISIBLE);
        updateNotice();
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
        if (movingInGrid) { finishGridMove(save); return; }
        String pkg = movingPkg;
        endMoveMode();
        if (save) {
            persistDock(shownDock(), null);
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
        movingInGrid = false;
        if (moveRing != null && moveRing.getParent() != null) ((ViewGroup) moveRing.getParent()).removeView(moveRing);
        moveRing = null;
        if (card != null) card.animate().translationY(0).setDuration(180).start();
        moveHint.setVisibility(View.GONE);
        updateNotice();
    }

    /** Grid apps that can be moved: all but the Settings tile, which stays last. */
    private int movableGridCount() {
        int n = gridApps.size();
        return n > 0 && gridApps.get(n - 1).isSettings ? n - 1 : n;
    }

    private int gridRank(String pkg) {
        int i = gridOrder == null ? -1 : gridOrder.indexOf(pkg);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Grid move mode: ring the tile and let the arrows slide the app through the grid. */
    private void startGridMove(View card, App app) {
        movingPkg = app.pkg;
        movingInGrid = true;
        gridBefore = new ArrayList<>(gridApps);
        moveIndex = gridApps.indexOf(app);
        card.requestFocus();
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(4), 0xFF5AC8FA);
        moveRing = new View(this);
        moveRing.setBackground(ring);
        ((ViewGroup) card).addView(moveRing, new FrameLayout.LayoutParams(-1, -1));
        moveHint.setText("Use the arrows to move, OK to save, Back to cancel");
        moveHint.setCompoundDrawablesRelative(icon(R.drawable.ic_move_grid, 18), null, null, null);
        moveHint.setVisibility(View.VISIBLE);
        updateNotice();
    }

    /**
     * Move the app delta places in grid order (±1 for Left/Right, ±cols for Up/Down): the apps
     * between shift along, and the ring and focus follow it. Down from the row above a short last
     * row goes to the last place; Up from the first row and moves past the ends do nothing.
     */
    private void moveGridBy(int delta) {
        int last = movableGridCount() - 1, j = moveIndex + delta;
        if (j > last && delta == cols && moveIndex / cols < last / cols) j = last;
        if (j < 0 || j > last || j == moveIndex) return;
        gridApps.add(j, gridApps.remove(moveIndex));
        for (int k = Math.min(moveIndex, j); k <= Math.max(moveIndex, j); k++) bind((AspectFrame) gridCards.get(k), gridApps.get(k));
        View card = gridCards.get(j);
        ((ViewGroup) moveRing.getParent()).removeView(moveRing);
        ((ViewGroup) card).addView(moveRing, new FrameLayout.LayoutParams(-1, -1));
        moveIndex = j;
        card.requestFocus();
    }

    /** OK keeps and saves the new grid order; Back puts every app back where it was. */
    private void finishGridMove(boolean save) {
        String pkg = movingPkg;
        endMoveMode();
        if (save) {
            // The new order of the apps on screen, with any hidden ones left in their places.
            List<String> onScreen = new ArrayList<>();
            for (App a : gridApps) if (!a.isSettings) onScreen.add(a.pkg);
            gridOrder = new ArrayList<>();
            int next = 0;
            for (String p : gridAllPkgs) gridOrder.add(onScreen.contains(p) ? onScreen.get(next++) : p);
            while (next < onScreen.size()) gridOrder.add(onScreen.get(next++));
            gridAllPkgs = new ArrayList<>(gridOrder);
            saveGridOrder();
        } else {
            gridApps = gridBefore;
            for (int k = 0; k < gridApps.size() && k < gridCards.size(); k++) bind((AspectFrame) gridCards.get(k), gridApps.get(k));
        }
        View c = findCard(pkg);
        if (c != null) c.requestFocus();
    }

    /** Hide or unhide a grid app, keep focus near where it was, and end reveal mode once none are hidden. */
    private void setHidden(String pkg, boolean hide) {
        int index = Math.max(0, gridCards.indexOf(findCard(pkg)));
        if (hide) userHidden.add(pkg); else userHidden.remove(pkg);
        if (userHidden.isEmpty()) revealHidden = false;
        saveHidden();
        buildApps();
        View c = findCard(pkg);
        if (c == null && !gridCards.isEmpty()) c = gridCards.get(Math.min(index, gridCards.size() - 1));
        if (c != null) c.requestFocus();
    }

    /** Reveal mode on or off; focus stays on the Settings tile that switched it. */
    private void setReveal(boolean on) {
        revealHidden = on;
        buildApps();
        for (App a : gridApps) if (a.isSettings) { View c = findCard(a.pkg); if (c != null) c.requestFocus(); }
    }

    private void saveHidden() {
        getPreferences(MODE_PRIVATE).edit().putString("user_hidden", TextUtils.join(",", userHidden)).apply();
    }

    private void saveGridOrder() {
        getPreferences(MODE_PRIVATE).edit().putString("grid_order", TextUtils.join(",", gridOrder)).apply();
    }

    /**
     * Save the dock as shown. Apps from the old order (and the old overflow) that aren't in it now,
     * because they were past the cap or not installed, stay in order in the overflow list; an app
     * the user removed from the dock is dropped. The cap is saved too, so raising DOCK_MAX later
     * brings the overflow back.
     */
    private void persistDock(List<String> shown, String removed) {
        List<String> overflow = new ArrayList<>();
        for (List<String> src : Arrays.asList(dockOrder, dockOverflow)) {
            for (String p : src) if (!shown.contains(p) && !p.equals(removed) && !overflow.contains(p)) overflow.add(p);
        }
        dockOrder = new ArrayList<>(shown);
        dockOverflow = overflow;
        getPreferences(MODE_PRIVATE).edit()
                .putString("dock_order", TextUtils.join(",", shown))
                .putString("dock_overflow", TextUtils.join(",", overflow))
                .putInt("dock_cap", DOCK_MAX)
                .apply();
    }

    /** One icon layer of a round button: the vector centred with fixed padding, colours following focus. */
    private ImageView iconLayer(int icon, int pad) {
        ImageView img = new ImageView(this);
        img.setDuplicateParentStateEnabled(true); // the icon's colour lists follow the button's focus
        img.setImageResource(icon);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setPadding(pad, pad, pad, pad);
        return img;
    }

    /** Packages in the dock as shown now, left to right. */
    private List<String> shownDock() {
        List<String> shown = new ArrayList<>();
        for (View c : dockCards) shown.add(((App) c.getTag()).pkg);
        return shown;
    }

    /** Save the new dock order, rebuild the dock and grid, and focus the app that was acted on. */
    private void saveDock(List<String> order, String focusPkg, String removed) {
        persistDock(order, removed);
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
        updateNight(h);
    }

    /** At the minute tick, fade the night dimming in or out; the first call after a start sets it at once. */
    private void updateNight(int hour) {
        boolean night = nightEnabled && isNight(hour, nightStart, nightEnd);
        if (nightOn != null && nightOn == night) return;
        boolean first = nightOn == null;
        nightOn = night;
        if (gridDimmed) return; // the grid keeps its full dimming; fadeGlow returns to restShade()
        if (first) gridShade.setAlpha(restShade());
        else gridShade.animate().alpha(restShade()).setDuration(NIGHT_FADE_MS).start();
    }

    /** Whether the hour is in [start, end), which wraps past midnight when start > end. Equal hours: never. */
    static boolean isNight(int hour, int start, int end) {
        if (start == end) return false;
        return start < end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    /** gridShade's alpha while the grid is not in view. */
    private float restShade() {
        return Boolean.TRUE.equals(nightOn) ? NIGHT_SHADE : 0f;
    }

    /**
     * State icon and spoken description of the Wi-Fi button: working, no internet (not validated),
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
        String state;
        if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            icon = working ? R.drawable.ic_ethernet : R.drawable.ic_ethernet_alert;
            state = working ? "Ethernet" : "Ethernet, no internet";
        } else if (c != null) {
            icon = working ? R.drawable.ic_wifi : R.drawable.ic_wifi_alert;
            state = working ? "connected" : "connected, no internet";
        } else if (wm != null && wm.isWifiEnabled() && info != null && isConnecting(info.getSupplicantState())) {
            icon = R.drawable.ic_wifi_connecting;
            state = "connecting";
        } else {
            icon = R.drawable.ic_wifi_off;
            state = wm != null && !wm.isWifiEnabled() ? "off" : "not connected";
        }
        if (icon != wifiIcon) { wifiIcon = icon; for (ImageView v : wifiIconViews) v.setImageResource(icon); }
        wifiButton.setContentDescription("Wi-Fi, " + state);
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

    /** The cached reading while online and recent; otherwise a quiet "unavailable" with no icon. */
    private void showWeather() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        String line = p.getString("weather_line", null);
        long age = System.currentTimeMillis() - p.getLong("weather_at", 0);
        if (line != null && age >= 0 && age < WEATHER_STALE_MS && isOnline()) {
            weatherPlace.setText(weatherCity);
            weatherPlace.setTextColor(0xCCFFFFFF);
            int res = weatherIconRes(p.getString("weather_icon", ""));
            if (res != weatherIconRes) {
                if (res != 0) weatherIcon.setImageResource(res);
                weatherIconRes = res;
            }
            weatherIcon.setContentDescription(p.getString("weather_words", ""));
            weatherIcon.setVisibility(res != 0 ? View.VISIBLE : View.GONE);
            // Rain on its way: its hour takes the place of the high and low, so the row keeps one line.
            String rain = rainText(p);
            if (rain != null) line = p.getString("weather_lead", "") + rain;
            weather.setText(res != 0 ? line : "  " + line);
            weather.setVisibility(View.VISIBLE);
        } else {
            weatherPlace.setText(weatherCity + "  ·  Weather unavailable");
            weatherPlace.setTextColor(0x99FFFFFF); // dimmer than live weather (80%), but readable on the shade
            weatherIcon.setVisibility(View.GONE);
            weather.setVisibility(View.GONE);
        }
    }

    /**
     * "Rain from 3 pm" while the cached reading's first rainy hour is still ahead, else null. A cache
     * written before the rain hour was added (or with an unexpected value) gives null.
     */
    private String rainText(SharedPreferences p) {
        if (!showRain) return null;
        long from;
        try {
            from = p.getLong("weather_rain_from", 0);
            if (p.getString("weather_lead", null) == null) return null;
        } catch (ClassCastException e) {
            return null;
        }
        if (from <= System.currentTimeMillis()) return null;
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        DateTimeFormatter f = DateTimeFormatter.ofPattern(is24 ? "H:mm" : "h a", Locale.ENGLISH)
                .withZone(IcsParser.zoneOr(weatherTz, ZoneId.systemDefault()));
        return "Rain from " + f.format(Instant.ofEpochMilli(from)).toLowerCase(Locale.ENGLISH);
    }

    /**
     * Ask Open-Meteo at most once per 30 minutes after a success. After a failure, retry in 5 minutes,
     * up to three times in a row, then back to every 30 minutes. Called only while the launcher is
     * resumed.
     */
    private void refreshWeather() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long since = now - p.getLong("weather_tried", 0);
        int fails = p.getInt("weather_fails", 0);
        long wait = fails > 0 && fails <= WEATHER_QUICK_RETRIES ? WEATHER_RETRY_MS : WEATHER_INTERVAL_MS;
        if (weatherBusy || !isOnline() || (since >= 0 && since < wait)) return;
        weatherBusy = true;
        p.edit().putLong("weather_tried", now).apply();
        new Thread(() -> {
            String[] w = fetchWeather();
            runOnUiThread(() -> {
                weatherBusy = false;
                if (w != null) {
                    p.edit().putString("weather_line", w[0]).putString("weather_icon", w[1])
                            .putString("weather_words", w[2]).putString("weather_lead", w[3])
                            .putLong("weather_rain_from", Long.parseLong(w[4]))
                            .putLong("weather_at", System.currentTimeMillis())
                            .putInt("weather_fails", 0).apply();
                } else {
                    p.edit().putInt("weather_fails", p.getInt("weather_fails", 0) + 1).apply();
                }
                if (!isDestroyed()) showWeather();
            });
        }).start();
    }

    /**
     * One forecast request on a worker thread: {line after the icon, icon name, words, the line up to
     * the high and low, first rainy hour}, or null on any failure. The temperature is the current one; the icon and words are the
     * day's forecast. The rainy hour (epoch ms, "0" for none) is the first of the next 12 with at least
     * rain_threshold_pct chance of precipitation, looked for only while it is dry now.
     */
    private String[] fetchWeather() {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + weatherLat
                    + "&longitude=" + weatherLon
                    + "&current=temperature_2m,is_day,precipitation,weather_code"
                    + "&daily=temperature_2m_max,temperature_2m_min,weather_code,wind_gusts_10m_max,precipitation_sum"
                    + "&hourly=precipitation_probability&forecast_hours=13" // this hour and the next 12
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
            String[] sky = dayWeather(day.getJSONArray("weather_code").getInt(0), now.optInt("is_day", 1) == 1,
                    day.getJSONArray("precipitation_sum").optDouble(0, 0),
                    day.getJSONArray("wind_gusts_10m_max").optDouble(0, 0), windyGustKmh);
            String lead = String.format(Locale.getDefault(), "%d°  %s·  ",
                    Math.round(now.getDouble("temperature_2m")), sky[1].isEmpty() ? "" : sky[1] + "  ");
            String line = lead + String.format(Locale.getDefault(), "H %d°  L %d°",
                    Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)),
                    Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)));
            return new String[]{line, sky[0], sky[1], lead, String.valueOf(firstRainyHour(j, now))};
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Start of the first hour after now with a high enough chance of precipitation, or 0. */
    private long firstRainyHour(JSONObject j, JSONObject now) throws JSONException {
        // Wet already (any precipitation, or a drizzle, rain, snow or storm code): the words cover it.
        if (now.optDouble("precipitation", 0) > 0 || now.optInt("weather_code", 0) >= 51) return 0;
        JSONObject hourly = j.optJSONObject("hourly");
        if (hourly == null) return 0;
        JSONArray times = hourly.getJSONArray("time"), chance = hourly.getJSONArray("precipitation_probability");
        ZoneId zone = IcsParser.zoneOr(weatherTz, ZoneId.systemDefault());
        long nowMs = System.currentTimeMillis();
        for (int i = 0; i < times.length() && i < chance.length(); i++) {
            long t = LocalDateTime.parse(times.getString(i)).atZone(zone).toInstant().toEpochMilli();
            if (t > nowMs && t <= nowMs + 12 * 60 * 60 * 1000L && chance.optInt(i, 0) >= rainThresholdPct) return t;
        }
        return 0;
    }

    /**
     * The day's sky as {icon name, words}, from Open-Meteo's daily WMO code (the day's most severe
     * weather). Drizzle under 1 mm for the day counts as cloudy, since the model codes many nearly
     * dry days as drizzle. A gusty dry day shows the wind; a gusty wet day keeps its rain, storm or
     * snow icon and adds ", windy". Unknown codes give no icon and no words.
     */
    static String[] dayWeather(int code, boolean isDay, double rainMm, double gustKmh, int windyKmh) {
        String icon, words, wet = null;
        switch (code) {
            case 0: case 1: icon = isDay ? "clear_day" : "clear_night"; words = "Clear today"; break;
            case 2: icon = isDay ? "partly_cloudy_day" : "partly_cloudy_night"; words = "Partly cloudy"; break;
            case 3: icon = "cloud"; words = "Cloudy"; break;
            case 45: case 48: icon = "foggy"; words = "Fog today"; break;
            case 51: case 53: case 55:
                if (rainMm < 1) { icon = "cloud"; words = "Cloudy"; } else { icon = "rainy"; wet = "Rain"; words = null; }
                break;
            case 56: case 57: case 61: case 63: case 65: case 66: case 67: case 80: case 81: case 82:
                icon = "rainy"; wet = "Rain"; words = null; break;
            case 71: case 73: case 75: case 77: case 85: case 86: icon = "weather_snowy"; wet = "Snow"; words = null; break;
            case 95: case 96: case 97: case 99: icon = "thunderstorm"; wet = "Storms"; words = null; break;
            default: return new String[]{"", ""};
        }
        boolean windy = gustKmh >= windyKmh;
        if (wet != null) return new String[]{icon, wet + (windy ? ", windy" : " today")};
        return windy ? new String[]{"air", "Windy today"} : new String[]{icon, words};
    }

    private static int weatherIconRes(String name) {
        switch (name) {
            case "clear_day": return R.drawable.ic_wx_clear_day;
            case "clear_night": return R.drawable.ic_wx_clear_night;
            case "partly_cloudy_day": return R.drawable.ic_wx_partly_cloudy_day;
            case "partly_cloudy_night": return R.drawable.ic_wx_partly_cloudy_night;
            case "cloud": return R.drawable.ic_wx_cloud;
            case "foggy": return R.drawable.ic_wx_foggy;
            case "rainy": return R.drawable.ic_wx_rainy;
            case "thunderstorm": return R.drawable.ic_wx_thunderstorm;
            case "weather_snowy": return R.drawable.ic_wx_weather_snowy;
            case "air": return R.drawable.ic_wx_air;
            default: return 0;
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    // ---------------------------------------------------------------- schedule

    private SharedPreferences calPrefs() {
        return getSharedPreferences("calendar", MODE_PRIVATE);
    }

    /** Draw the schedule card from the cache, or its not-set-up / unavailable state. */
    private void showCalendar() {
        calCard.removeAllViews();
        calCard.addView(calLabel("TODAY", 0));
        SharedPreferences p = calPrefs();
        String json = p.getString("events", null);
        if (CalendarLinks.URLS.length == 0) { calCard.addView(calNote("Calendar not set up")); return; }
        if ("error".equals(p.getString("status", ""))) { calCard.addView(calNote("Calendar unavailable")); return; }
        if (json == null) { calCard.addView(calNote(calendarBusy ? "Loading…" : "Calendar unavailable")); return; }

        Agenda a = Agenda.pick(decodeEvents(json), ZonedDateTime.now(calZone));
        if (!a.allDayToday.isEmpty()) {
            StringBuilder titles = new StringBuilder();
            for (IcsParser.Event e : a.allDayToday) titles.append(titles.length() == 0 ? "" : " · ").append(eventTitle(e));
            TextView allDay = calText(13, "sans-serif", Color.WHITE);
            allDay.setText("All day: " + titles);
            calCard.addView(allDay, llp(-1, -2, 0, dp(4), 0, dp(2)));
        }
        if (a.tomorrow && !a.events.isEmpty()) calCard.addView(calLabel("TOMORROW", dp(8)));
        // The minute tick redraws the card, so the countdown stays current.
        String countdown = showCountdown ? Agenda.countdown(a, System.currentTimeMillis(), countdownMinutes) : null;
        for (IcsParser.Event e : a.events) {
            calCard.addView(calRow(e.allDay ? "All day" : calTime.format(Instant.ofEpochMilli(e.start)), eventTitle(e),
                    e == a.events.get(0) ? countdown : null));
        }
        if (a.isEmpty()) calCard.addView(calNote("Nothing scheduled"));

        // Only when the data is stale (over 3 hours since the last successful fetch) does the card say
        // when it was updated; fresh data needs no note. Opaque orange at 12sp keeps 5:1 or better on
        // every wallpaper, while staying below the 13sp event rows.
        long at = p.getLong("at", 0);
        if (System.currentTimeMillis() - at <= CALENDAR_STALE_MS) return;
        TextView updated = calText(12, "sans-serif", 0xFFFFC266);
        updated.setText("Updated " + calTime.format(Instant.ofEpochMilli(at)) + "  ·  stale");
        calCard.addView(updated, llp(-2, -2, 0, dp(8), 0, 0));
    }

    private String eventTitle(IcsParser.Event e) {
        return !showEventTitles || e.title.isEmpty() ? "Busy" : e.title;
    }

    private TextView calText(float sp, String family, int color) {
        TextView t = text(sp, family, color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END); // long titles end in "…" on one line
        t.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        return t;
    }

    // TODAY / TOMORROW at 70% white, like the greeting: 60% fell to 4.1:1 for TOMORROW on one wallpaper.
    private TextView calLabel(String label, int topMargin) {
        TextView t = calText(10, "sans-serif-medium", 0xB3FFFFFF);
        t.setText(label);
        t.setLetterSpacing(0.22f);
        t.setLayoutParams(llp(-2, -2, 0, topMargin, 0, dp(4)));
        return t;
    }

    private TextView calNote(String note) {
        TextView t = calText(13, "sans-serif", 0x99FFFFFF);
        t.setText(note);
        t.setLayoutParams(llp(-2, -2, 0, dp(4), 0, dp(2)));
        return t;
    }

    private View calRow(String time, String title, String countdown) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView t = calText(13, "sans-serif-medium", 0xCCFFFFFF);
        t.setText(time);
        row.addView(t, new LinearLayout.LayoutParams(dp(76), -2));
        TextView n = calText(13, "sans-serif", Color.WHITE);
        n.setText(title);
        row.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        if (countdown != null) {
            // Never squeezed: the title takes what is left and ends in "…".
            TextView c = calText(12, "sans-serif-medium", 0xE6FFFFFF);
            c.setText(countdown);
            row.addView(c, llp(-2, -2, dp(8), 0, 0, 0));
        }
        return row;
    }

    /**
     * Fetch the calendars only while the launcher is resumed (onResume and the minute tick call
     * this): at most every 30 minutes after a success, 5 minutes after a failure for three tries.
     */
    private void refreshCalendar() {
        if (CalendarLinks.URLS.length == 0 || calendarBusy || !isOnline()) return;
        SharedPreferences p = calPrefs();
        long now = System.currentTimeMillis();
        long since = now - p.getLong("tried", 0);
        int fails = p.getInt("fails", 0);
        long wait = fails > 0 && fails <= CALENDAR_QUICK_RETRIES ? CALENDAR_RETRY_MS : CALENDAR_INTERVAL_MS;
        if (since >= 0 && since < wait) return;
        calendarBusy = true;
        p.edit().putLong("tried", now).apply();
        ZoneId zone = calZone;
        boolean titles = showEventTitles;
        new Thread(() -> {
            String[] result = fetchCalendars(zone, titles);
            runOnUiThread(() -> {
                calendarBusy = false;
                SharedPreferences.Editor e = p.edit();
                if (result[0].equals("ok")) {
                    e.putString("events", result[1]).putLong("at", System.currentTimeMillis())
                            .putString("status", "ok").putInt("fails", 0);
                } else {
                    e.putInt("fails", p.getInt("fails", 0) + 1);
                    if (result[0].equals("error")) e.putString("status", "error");
                }
                e.apply();
                if (!isDestroyed()) showCalendar();
            });
        }).start();
    }

    /**
     * Read every link on a worker thread and keep today's and tomorrow's events. Returns {"ok", json},
     * {"error", null} when Google answers with an error or something that isn't a calendar, or
     * {"offline", null} for network failures. Links are never logged.
     */
    private static String[] fetchCalendars(ZoneId zone, boolean titles) {
        ZonedDateTime from = LocalDate.now(zone).atStartOfDay(zone), to = from.plusDays(2);
        List<IcsParser.Event> all = new ArrayList<>();
        for (String link : CalendarLinks.URLS) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(link).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return new String[]{"error", null};
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    all.addAll(IcsParser.parse(r, from, to, titles));
                }
            } catch (IcsParser.NotACalendar e) {
                return new String[]{"error", null};
            } catch (IOException | RuntimeException e) {
                return new String[]{"offline", null};
            } finally {
                if (c != null) c.disconnect();
            }
        }
        IcsParser.sortByStart(all);
        JSONArray out = new JSONArray();
        for (IcsParser.Event e : all) {
            out.put(new JSONArray().put(e.start).put(e.end).put(e.allDay ? 1 : 0).put(e.title));
        }
        return new String[]{"ok", out.toString()};
    }

    private static List<IcsParser.Event> decodeEvents(String json) {
        List<IcsParser.Event> events = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONArray e = a.getJSONArray(i);
                events.add(new IcsParser.Event(e.getLong(0), e.getLong(1), e.getInt(2) == 1, e.getString(3)));
            }
        } catch (JSONException ignored) { }
        return events;
    }

    // ---------------------------------------------------------------- verse

    /** Bundled verses, one "reference<TAB>text" per line; lines starting with # are comments. */
    private void loadVerses(String asset) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(asset), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] v = line.split("\t", 2);
                if (v.length == 2) allVerses.add(v);
            }
        } catch (IOException ignored) { }
    }

    /**
     * Keep only verses that fit the card: at most four lines, measured with the card's own font,
     * size and width, and (once laid out) no taller than the room above the card, so at a large
     * system font size a long verse is skipped instead of covering the weather line. Verse text is
     * never shortened.
     */
    private void keepVersesThatFit() {
        verses.clear();
        for (String[] v : allVerses) if (fitsCard(v[1])) verses.add(v);
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
        return l.getLineCount() <= VERSE_MAX_LINES && (verseTextMaxPx <= 0 || l.getHeight() <= verseTextMaxPx);
    }

    /** One verse per calendar day from the ones that fit, the same all day, chosen by the local date. */
    private void updateVerse(Calendar now) {
        if (verses.isEmpty()) { verseCard.setVisibility(View.GONE); return; }
        long day = (now.getTimeInMillis() + now.get(Calendar.ZONE_OFFSET) + now.get(Calendar.DST_OFFSET)) / DAY_MS;
        if (day == verseDay) return;
        verseDay = day;
        showVerse();
    }

    /** Today's {reference, BSB text} from the bundled list. */
    private String[] todaysVerse() {
        return verses.get((int) (verseDay % verses.size()));
    }

    /**
     * The ESV text for today's reference (see esvRef) when it is cached and fits the card, else the
     * ESV text of the bundled list's verse; otherwise the bundled BSB text, labelled BSB, plus why the
     * ESV isn't shown when that is the key.
     */
    private void showVerse() {
        String[] v = todaysVerse();
        String ref = esvRef(), esv = cachedEsv(ref);
        if (!esvFits(esv)) esv = cachedEsv(ref = v[0]);
        String label;
        esvShown = esvFits(esv);
        if (esvShown) {
            verseText.setText(esv);
            label = "ESV";
        } else {
            ref = v[0];
            verseText.setText(v[1]);
            label = verseTranslation;
            SharedPreferences p = esvPrefs();
            if (EsvKey.KEY.isEmpty()) label += "  ·  ESV key missing";
            else if (p.getLong("rejected_day", -1) == verseDay && p.getInt("rejected_key", 0) == EsvKey.KEY.hashCode()) {
                label += "  ·  ESV key rejected";
            }
        }
        verseRef.setText((ref + "  ·  " + label).toUpperCase(Locale.getDefault()));
        updateNotice();
    }

    /** Cached ESV text that can go on the card ("" marks a passage the API sent too long to use). */
    private boolean esvFits(String esv) {
        return esv != null && !esv.isEmpty() && fitsCard(esv);
    }

    /**
     * The reference whose ESV text the card wants today. With a YouVersion verse of the day: that
     * verse or range; if the range's text is cached but doesn't fit, its first verse; if that doesn't
     * fit either (or a single verse doesn't), the bundled list's verse. Each step waits for the text
     * of the one before it. Without one, the bundled list's verse.
     */
    private String esvRef() {
        String[] yv = votdToday();
        if (yv != null) {
            String t = cachedEsv(yv[0]);
            if (t == null || esvFits(t)) return yv[0];
            if (yv[1] != null) {
                String first = cachedEsv(yv[1]);
                if (first == null || esvFits(first)) return yv[1];
            }
        }
        return todaysVerse()[0];
    }

    /** When enabled, the notice shows while ESV text is on the card, except during move mode. */
    private void updateNotice() {
        if (esvNotice != null) esvNotice.setVisibility(esvShown && movingPkg == null ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- ESV

    /**
     * Shrink the notice in quarter steps until it fits one line across the screen. If it can't fit
     * at the smallest legible size, it wraps instead, since its wording must never be cut off.
     */
    private void fitNoticeToOneLine() {
        float avail = getResources().getDisplayMetrics().widthPixels - 2 * dp(NOTICE_SIDE_DP);
        String t = esvNotice.getText().toString();
        float sp = NOTICE_MAX_SP;
        esvNotice.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        while (esvNotice.getPaint().measureText(t) > avail && sp > NOTICE_MIN_SP) {
            sp -= 0.25f;
            esvNotice.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
        esvNotice.setSingleLine(esvNotice.getPaint().measureText(t) <= avail);
    }

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
     * (onResume and the minute tick call this), and only if it isn't cached. A success ends the day's
     * fetching; a failure may retry an hour later, up to three requests a day per key. Any failure
     * leaves the BSB text up.
     */
    private void refreshEsv() {
        if (EsvKey.KEY.isEmpty() || verseDay < 0 || verses.isEmpty() || esvBusy || votdBusy || !isOnline()) return;
        String ref = esvRef(), bundledRef = todaysVerse()[0];
        SharedPreferences p = esvPrefs();
        int keyId = EsvKey.KEY.hashCode(); // tells a rebuilt key apart without storing the key
        if (cachedEsv(ref) != null) return;
        long now = System.currentTimeMillis();
        boolean sameDay = p.getLong("tried_day", -1) == verseDay && p.getInt("tried_key", 0) == keyId;
        int tries = sameDay ? p.getInt("tries", 0) : 0;
        long since = now - p.getLong("tried_at", 0);
        if (tries >= ESV_TRIES_PER_DAY || (tries > 0 && since >= 0 && since < ESV_RETRY_MS)) return;
        esvBusy = true;
        long day = verseDay;
        p.edit().putLong("tried_day", day).putInt("tried_key", keyId).putInt("tries", tries + 1).putLong("tried_at", now).apply();
        new Thread(() -> {
            int[] status = new int[1];
            String text = fetchEsv(ref, status);
            runOnUiThread(() -> {
                esvBusy = false;
                SharedPreferences.Editor e = p.edit();
                if (status[0] == HttpURLConnection.HTTP_UNAUTHORIZED || status[0] == HttpURLConnection.HTTP_FORBIDDEN) {
                    e.putLong("rejected_day", day).putInt("rejected_key", keyId);
                }
                if (text != null || (status[0] == HttpURLConnection.HTTP_OK && !ref.equals(bundledRef))) {
                    // A YouVersion passage sent but unusable (too long) is kept as "" so the next
                    // reference is tried; the bundled verse keeps its retries as before.
                    pruneEsvCache(e, day);
                    e.putString("text:" + ref, text != null ? text : "").putLong("at:" + ref, day);
                    // Only a failure waits an hour; after a success the next reference may follow now.
                    e.putLong("tried_at", 0);
                }
                e.apply();
                if (!isDestroyed() && day == verseDay) {
                    showVerse();
                    refreshEsv(); // the range's first verse or the bundled verse, if that is wanted next
                }
            });
        }).start();
    }

    /**
     * Drop expired passages and any reference in neither the bundled list nor the YouVersion list.
     * The cache can then hold the list's 30 references (32 verses) plus at most two references a day
     * for 30 days from YouVersion, still well below the API's 500-verse limit.
     */
    private void pruneEsvCache(SharedPreferences.Editor e, long today) {
        Set<String> refs = new HashSet<>();
        for (String[] v : allVerses) refs.add(v[0]);
        if (votdByDay != null) {
            for (String id : votdByDay) {
                String[] r = Votd.reference(id);
                if (r != null) Collections.addAll(refs, r);
            }
        }
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

    // ---------------------------------------------------------------- YouVersion verse of the day

    private SharedPreferences votdPrefs() {
        return getSharedPreferences("votd", MODE_PRIVATE);
    }

    /** Whether the cached year list is recent enough to use: under 30 days old and from this year. */
    private boolean votdFresh() {
        if (votdByDay == null) return false;
        SharedPreferences p = votdPrefs();
        long age = System.currentTimeMillis() - p.getLong("fetched_at", 0);
        return age >= 0 && age < VOTD_LIST_DAYS * DAY_MS
                && p.getInt("fetched_year", 0) == LocalDate.now(VOTD_ZONE).getYear();
    }

    /** Today's {reference, first verse or null} from the YouVersion list, or null to use the bundled list. */
    private String[] votdToday() {
        if (!useVotd || !votdFresh()) return null;
        String id = votdByDay[LocalDate.now(VOTD_ZONE).getDayOfYear()];
        if (id == null) return null;
        String[] r = Votd.reference(id);
        if (r == null && !id.equals(votdLoggedId)) {
            votdLoggedId = id;
            android.util.Log.w("TvHome", "YouVersion passage id not understood, using the bundled verse: " + id);
        }
        return r;
    }

    /**
     * Fetch the YouVersion year list when there is no usable one: only with both keys and the switch
     * on, only while the launcher is resumed and online. A 429 waits at least its Retry-After; a 401,
     * 403 or 412 stops for the day; any other failure (or an empty list) may retry an hour later, up
     * to three requests a day. Until a list arrives the bundled verses are used.
     */
    private void refreshVotd() {
        if (!useVotd || votdBusy || esvBusy || !isOnline() || votdFresh()) return;
        SharedPreferences p = votdPrefs();
        long now = System.currentTimeMillis(), today = LocalDate.now(VOTD_ZONE).toEpochDay();
        if (p.getLong("blocked_day", Long.MIN_VALUE) == today || now < p.getLong("wait_until", 0)) return;
        int tries = p.getLong("tried_day", Long.MIN_VALUE) == today ? p.getInt("tries", 0) : 0;
        long since = now - p.getLong("tried_at", 0);
        if (tries >= ESV_TRIES_PER_DAY || (tries > 0 && since >= 0 && since < ESV_RETRY_MS)) return;
        votdBusy = true;
        p.edit().putLong("tried_day", today).putInt("tries", tries + 1).putLong("tried_at", now).apply();
        new Thread(() -> {
            int[] status = new int[2];
            String body = fetchVotd(status);
            String[] list = body == null ? null : Votd.parseYear(body);
            runOnUiThread(() -> {
                votdBusy = false;
                SharedPreferences.Editor e = p.edit();
                int s = status[0];
                if (list != null) {
                    e.putString("list", body).putLong("fetched_at", System.currentTimeMillis())
                            .putInt("fetched_year", LocalDate.now(VOTD_ZONE).getYear());
                    votdByDay = list;
                } else if (s == 429) {
                    e.putLong("wait_until", System.currentTimeMillis() + Math.max(status[1] * 1000L, ESV_RETRY_MS));
                } else if (s == HttpURLConnection.HTTP_UNAUTHORIZED || s == HttpURLConnection.HTTP_FORBIDDEN || s == 412) {
                    e.putLong("blocked_day", today);
                }
                e.apply();
                if (list == null) android.util.Log.w("TvHome", "YouVersion verse of the day list not loaded, HTTP " + s);
                if (isDestroyed()) return;
                if (list != null && verseDay >= 0 && !verses.isEmpty()) showVerse();
                refreshEsv();
            });
        }).start();
    }

    /**
     * The year list request on a worker thread: the body on HTTP 200, else null. status[0] gets the
     * HTTP code (0 without a response), status[1] a 429's Retry-After seconds. The status is checked
     * before anything is read, since a 429 body is plain text.
     */
    private String fetchVotd(int[] status) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.youversion.com/v1/verse-of-the-days").openConnection();
            c.setRequestProperty("X-YVP-App-Key", YouVersionKey.KEY);
            c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            status[0] = c.getResponseCode();
            if (status[0] == 429) {
                try { status[1] = Integer.parseInt(String.valueOf(c.getHeaderField("Retry-After")).trim()); }
                catch (NumberFormatException ignored) { }
                return null;
            }
            if (status[0] != HttpURLConnection.HTTP_OK) return null;
            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) body.append(line).append('\n');
            }
            return body.length() > 200_000 ? null : body.toString();
        } catch (IOException | RuntimeException e) {
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
        gridDimmed = alpha < 1f;
        gridShade.animate().alpha(gridDimmed ? 1f : restShade()).setDuration(300).start();
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
        clearWallpaper(wallBack);
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
            clearWallpaper(wallBack); // release the previous photo
        }).start();
    }

    /** Take a layer's photo off it, then free the pixels now instead of whenever the GC runs. */
    private static void clearWallpaper(ImageView v) {
        Drawable d = v.getDrawable();
        v.setImageDrawable(null);
        // Only decoded photos are ever set here, and no other view, field or task keeps one.
        if (d instanceof BitmapDrawable) {
            Bitmap bm = ((BitmapDrawable) d).getBitmap();
            if (bm != null && !bm.isRecycled()) bm.recycle();
        }
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

    /**
     * The pills' system screens: a plain startActivity from this activity, no extra flags, so Back
     * there returns straight home. Android 9 builds without the Wi-Fi screen get main Settings.
     */
    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (RuntimeException e) {
            if (Settings.ACTION_SETTINGS.equals(action)) return;
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) { }
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

    /** A vector icon sized in dp, for use beside text. */
    private Drawable icon(int res, int sizeDp) {
        Drawable d = getDrawable(res);
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        return d;
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
        // Move mode takes the remote: Left/Right slide (and Up/Down in the grid; in the dock they do
        // nothing), OK saves, Back cancels.
        if (movingPkg != null) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN, up = e.getAction() == KeyEvent.ACTION_UP;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (down) { if (movingInGrid) moveGridBy(-1); else moveBy(-1); } return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) { if (movingInGrid) moveGridBy(1); else moveBy(1); } return true;
                case KeyEvent.KEYCODE_DPAD_UP: if (down && movingInGrid) moveGridBy(-cols); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (down && movingInGrid) moveGridBy(cols); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (up) finishMove(true);
                    return true;
                case KeyEvent.KEYCODE_BACK: if (up) finishMove(false); return true;
                case KeyEvent.KEYCODE_MENU: return true;
                default: break;
            }
        }
        // Menu key on the remote opens the Inputs dropdown (it falls back to Settings).
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && popup == null) {
            showInputs(wifiButton);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        ImageView img;
        GradientDrawable bg;
        TextView label, badge; // grid tiles only
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
