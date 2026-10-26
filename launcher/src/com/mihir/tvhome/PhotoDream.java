package com.mihir.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * Screensaver: the bundled photos full screen, one at a time, with a small clock and date that move
 * to another of six fixed spots every few minutes. Android starts it on an idle TV and ends it on any
 * key; nothing runs outside that. No network and nothing personal: only the photo, time and date.
 *
 * Photos are full 1920x1080 ARGB_8888 (RGB_565 bands in dark skies at this size), so only one is
 * held at a time: the old one fades to black, is freed, and the next fades in.
 */
public class PhotoDream extends DreamService {

    static final long FADE_MS = 1000;
    static final int SHADE_RADIUS_DP = 300; // the dark pool behind the clock fades to nothing at this radius
    // Where the clock block goes, as fractions of the free space (left/top 0, right/bottom 1): the
    // four corners and the lower third at one and two thirds across. The block's own 48dp padding
    // keeps the text inside the safe margin.
    static final float[][] SPOTS = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {1 / 3f, 1}, {2 / 3f, 1}};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private FrameLayout root;
    private ImageView photo;
    private View nightShade;
    private LinearLayout clockBlock;
    private View clockShade;
    private TextView clock, ampm, date;
    private String[] wallpapers = new String[0];
    private int index;
    private int spot;
    private long photoMs, clockMoveMs;
    private boolean nightEnabled, attached;
    private int nightStart, nightEnd;
    private Boolean nightOn;

    private final Runnable nextPhoto = this::nextPhoto;
    private final Runnable moveClock = this::moveClock;
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // any key or touch ends it
        setFullscreen(true);
        setScreenBright(true);
        attached = true;
        photoMs = getResources().getInteger(R.integer.dream_photo_seconds) * 1000L;
        clockMoveMs = getResources().getInteger(R.integer.dream_clock_move_minutes) * 60_000L;
        nightEnabled = getResources().getBoolean(R.bool.night_mode_enabled);
        nightStart = getResources().getInteger(R.integer.night_start_hour);
        nightEnd = getResources().getInteger(R.integer.night_end_hour);
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (IOException ignored) { }
        // Start after the home screen's photo; the home screen's position is read, never written.
        index = getSharedPreferences("MainActivity", MODE_PRIVATE).getInt("wallpaper", -1);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setAlpha(0f);
        root.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        nightShade = new View(this);
        nightShade.setBackgroundColor(0xB3000000); // the home screen's dim layer
        nightShade.setAlpha(0f);
        root.addView(nightShade, new FrameLayout.LayoutParams(-1, -1));

        // A soft dark pool behind the text, like the home screen's header shade: no card edge, no blur.
        // Its own square view, centred on the text and twice the radius, so it is clear at every edge.
        clockShade = new View(this);
        GradientDrawable shade = new GradientDrawable();
        shade.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        shade.setGradientRadius(dp(SHADE_RADIUS_DP));
        shade.setColors(new int[]{0x99000000, 0x66000000, 0x00000000});
        clockShade.setBackground(shade);
        root.addView(clockShade, new FrameLayout.LayoutParams(dp(2 * SHADE_RADIUS_DP), dp(2 * SHADE_RADIUS_DP)));
        clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        clockBlock.setPadding(dp(48), dp(48), dp(48), dp(48)); // keeps the text inside the safe margin
        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin");
        clockRow.addView(clock);
        ampm = text(14, "sans-serif-medium");
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.setMargins(dp(8), 0, 0, dp(12));
        clockRow.addView(ampm, ap);
        clockBlock.addView(clockRow);
        date = text(14, "sans-serif-medium");
        date.setLetterSpacing(0.12f);
        clockBlock.addView(date);
        root.addView(clockBlock, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        setContentView(root);

        spot = 2; // lower left first
        updateClock();
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        clockBlock.setAlpha(0f);
        clockShade.setAlpha(0f);
        root.post(() -> {
            placeClock();
            fadeClock(1f, null);
        });
        handler.postDelayed(moveClock, clockMoveMs);
        showNext();
    }

    @Override
    public void onDetachedFromWindow() {
        attached = false;
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(tick);
        photo.animate().cancel();
        clockBlock.animate().cancel();
        clockShade.animate().cancel();
        MainActivity.clearWallpaper(photo);
        super.onDetachedFromWindow();
    }

    /** Fade the photo to black, free it, then decode the next and fade it in. */
    private void nextPhoto() {
        photo.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            MainActivity.clearWallpaper(photo);
            showNext();
        }).start();
    }

    private void showNext() {
        if (wallpapers.length == 0) return;
        index = (index + 1) % wallpapers.length;
        String name = "wallpapers/" + wallpapers[index];
        new Thread(() -> {
            Bitmap bm = decode(name);
            handler.post(() -> {
                if (!attached) {
                    if (bm != null) bm.recycle();
                    return;
                }
                if (bm != null) {
                    photo.setImageBitmap(bm);
                    photo.animate().alpha(1f).setDuration(FADE_MS).start();
                }
                handler.postDelayed(nextPhoto, photoMs);
            });
        }).start();
    }

    /** The whole photo at its own size in ARGB_8888 (the bundled ones are 1920x1080). */
    private Bitmap decode(String asset) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inScaled = false;
        try (InputStream in = getAssets().open(asset)) {
            Bitmap bm = BitmapFactory.decodeStream(in, null, o);
            if (bm != null) bm.setDensity(Bitmap.DENSITY_NONE);
            return bm;
        } catch (IOException e) {
            return null;
        }
    }

    /** Every few minutes: fade the clock out, move it to another spot, fade it back in. */
    private void moveClock() {
        fadeClock(0f, () -> {
            int next = random.nextInt(SPOTS.length - 1);
            spot = next >= spot ? next + 1 : next; // never the same spot twice in a row
            placeClock();
            fadeClock(1f, null);
        });
        handler.postDelayed(moveClock, clockMoveMs);
    }

    private void fadeClock(float alpha, Runnable then) {
        clockShade.animate().alpha(alpha).setDuration(FADE_MS).start();
        ViewPropertyAnimator a = clockBlock.animate().alpha(alpha).setDuration(FADE_MS);
        if (then != null) a.withEndAction(then);
        a.start();
    }

    private void placeClock() {
        boolean right = SPOTS[spot][0] > 0.5f;
        clockBlock.setGravity(right ? Gravity.END : Gravity.START);
        ((LinearLayout) clock.getParent()).setGravity(Gravity.BOTTOM | (right ? Gravity.END : Gravity.START));
        // Whole pixels, so the text is never drawn between pixels.
        int x = Math.round(SPOTS[spot][0] * (root.getWidth() - clockBlock.getWidth()));
        int y = Math.round(SPOTS[spot][1] * (root.getHeight() - clockBlock.getHeight()));
        clockBlock.setTranslationX(x);
        clockBlock.setTranslationY(y);
        clockShade.setTranslationX(x + clockBlock.getWidth() / 2 - dp(SHADE_RADIUS_DP));
        clockShade.setTranslationY(y + clockBlock.getHeight() / 2 - dp(SHADE_RADIUS_DP));
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
        if (root.getWidth() > 0) root.post(this::placeClock); // a wider time ("10:00") keeps its spot
        boolean night = nightEnabled && MainActivity.isNight(h, nightStart, nightEnd);
        if (nightOn != null && nightOn == night) return;
        boolean first = nightOn == null;
        nightOn = night;
        // Night: the photo gets the home screen's night dimming and the clock drops to 80% white.
        // AM/PM takes the clock's colour: at 80% it fell below 4.5:1 on bright sunset skies top right.
        int time = night ? 0xCCFFFFFF : Color.WHITE;
        clock.setTextColor(time);
        ampm.setTextColor(time);
        date.setTextColor(night ? 0xB3FFFFFF : 0xCCFFFFFF);
        float shade = night ? MainActivity.NIGHT_SHADE : 0f;
        if (first) nightShade.setAlpha(shade);
        else nightShade.animate().alpha(shade).setDuration(MainActivity.NIGHT_FADE_MS).start();
    }

    private TextView text(float sp, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        t.setSingleLine(true);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
