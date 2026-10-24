package com.rameshinampudi.frostfold;

import android.accessibilityservice.AccessibilityService;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.util.function.Consumer;

/**
 * Plays the frosted-glass fold over everything on screen.
 *
 * The Fold 7's public hinge sensor only reports 0°, 90° and 180°, and only one
 * panel is lit at a time, so the fold is played as two timed phases:
 *
 *   1. Frost in: the hinge leaves rest (closed → 90° on the cover screen, or
 *      flat → 90° on the inner screen). Screenshot the panel, tilt it away.
 *   2. Reveal: Android switches to the other panel. Show that panel's last
 *      screenshot fully tilted and frosted at once, ease it flat and sharp,
 *      and cross-fade to the live screen at the end.
 *
 * If the hinge stops halfway (Flex mode) or goes back to where it started,
 * the glass clears again, so it never covers the screen for long.
 */
public class FoldService extends AccessibilityService
        implements SensorEventListener, DisplayManager.DisplayListener {

    static final String ACTION_PREVIEW = "com.rameshinampudi.frostfold.PREVIEW";
    static final String PREFS = "frost";
    private static final String TAG = "FrostFold";

    private static final long FROST_IN_MS = 420;
    /** Glass on the cover screen as the phone starts to open. */
    private static final float COVER_OPENING = 0.6f;
    /** Glass on the inner screen as the phone starts to close. */
    private static final float INNER_CLOSING = 0.55f;
    /** Inner screen half open: the glass waits here until the hinge reaches flat. */
    private static final float INNER_HALF_OPEN = 0.45f;
    private static final long SETTLE_MS = 450;
    private static final long SHUT_MS = 140;
    /** Clear by itself if the other panel hasn't lit up by then. */
    private static final long HOLD_TIMEOUT_MS = 2200;
    /** Android allows one accessibility screenshot about every 333 ms. */
    private static final long SCREENSHOT_GAP_MS = 340;
    /** A freshly lit panel is black for a moment; give up on it after this. */
    private static final long REVEAL_DEADLINE_MS = 1000;
    /** After a fold finishes, re-capture the panel so the next fold starts from what is on it now. */
    private static final long REFRESH_AFTER_MS = 600;
    /** Below this progress, cross-fade from the screenshot to the live screen. */
    private static final float CROSSFADE_BELOW = 0.35f;

    private enum Phase { IDLE, FROSTING, HOLDING, CLEARING }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private DisplayManager displays;
    private SensorManager sensors;
    private GlassView view;
    private WindowManager.LayoutParams lp;
    private boolean attached;

    private Phase phase = Phase.IDLE;
    private ValueAnimator animator;
    private float progress;
    private float lastAngle = -1f;
    private boolean inner;
    private Bitmap innerShot;
    private Bitmap coverShot;
    private long lastShotAt;

    private final Runnable holdTimeout = this::clear;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) reset();
            else if (ACTION_PREVIEW.equals(intent.getAction())) preview();
        }
    };

    @Override
    protected void onServiceConnected() {
        wm = getSystemService(WindowManager.class);
        displays = getSystemService(DisplayManager.class);
        sensors = getSystemService(SensorManager.class);
        view = new GlassView(this);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        lp.setTitle("FrostFold");
        lp.preferredRefreshRate = 60f;

        inner = isInnerPanel();
        Sensor hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (hinge != null) sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_FASTEST, main);
        displays.registerDisplayListener(this, main);
        IntentFilter filter = new IntentFilter(ACTION_PREVIEW);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        // Lets `adb shell am broadcast -a ...PREVIEW` replay the effect for testing;
        // only the shell (which holds DUMP) can send it, not other apps.
        registerReceiver(receiver, new IntentFilter(ACTION_PREVIEW), android.Manifest.permission.DUMP,
                null, Context.RECEIVER_EXPORTED);
        Log.i(TAG, "connected, hinge=" + (hinge != null) + " inner=" + inner);
    }

    // --- Hinge and panel events ---------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent event) {
        float angle = event.values[0];
        float prev = lastAngle;
        lastAngle = angle;
        if (prev < 0) return;
        boolean wasClosed = prev < 10f, wasFlat = prev > 170f;
        boolean nowClosed = angle < 10f, nowFlat = angle > 170f;
        Log.i(TAG, "hinge " + prev + " → " + angle + " (inner=" + inner + ", " + phase + ")");

        if (wasClosed && !nowClosed && !inner) {
            frostIn(COVER_OPENING);                      // opening, cover screen
        } else if (wasFlat && !nowFlat && inner && prefs().getBoolean("closing", true)) {
            frostIn(INNER_CLOSING);                      // closing, inner screen
        } else if (nowClosed && inner) {
            shut();                                      // closing, last few degrees
        } else if ((nowFlat && inner) || (nowClosed && !inner)) {
            if (phase == Phase.FROSTING || phase == Phase.HOLDING) clear();  // went back
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) return;
        boolean nowInner = isInnerPanel();
        if (nowInner == inner) return;
        inner = nowInner;
        Log.i(TAG, "panel switch → " + (inner ? "inner" : "cover") + " (" + phase + ")");
        reset();
        // A panel switch means the hinge moved, even when the coarse sensor
        // reports it late, so it always plays the reveal.
        boolean wanted = inner || prefs().getBoolean("closing", true);
        if (wanted && getSystemService(PowerManager.class).isInteractive()) reveal();
    }

    @Override public void onDisplayAdded(int displayId) {}
    @Override public void onDisplayRemoved(int displayId) {}
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    // --- Phases -------------------------------------------------------------

    private void frostIn(float target) {
        if (phase != Phase.IDLE && phase != Phase.CLEARING) return;
        reset();
        phase = Phase.FROSTING;
        boolean panel = inner;
        capture(shot -> {
            // A panel that is switching off screenshots black: never use or keep that.
            if (shot == null || isMostlyBlack(shot) || panel != inner || phase != Phase.FROSTING) {
                if (phase == Phase.FROSTING) phase = Phase.IDLE;
                return;
            }
            if (panel) innerShot = shot; else coverShot = shot;
            show(shot, 0f);
            animateTo(target, FROST_IN_MS, new DecelerateInterpolator(1.5f), () -> {
                phase = Phase.HOLDING;
                main.postDelayed(holdTimeout, HOLD_TIMEOUT_MS);
            });
        });
    }

    /**
     * The phone is about to shut: fill the glass quickly, then fade it out
     * before Android swaps to the cover screen, which plays its own reveal.
     */
    private void shut() {
        main.removeCallbacks(holdTimeout);
        if (!attached) {
            reset();
            return;
        }
        phase = Phase.HOLDING;
        animateTo(1f, SHUT_MS, new AccelerateInterpolator(), () ->
                view.animate().alpha(0f).setDuration(120).withEndAction(this::reset).start());
    }

    /** The other panel just lit up: play its half of the fold. */
    private void reveal() {
        Bitmap cached = inner ? innerShot : coverShot;
        if (cached != null) {
            show(cached, 1f);
            settle();
            return;
        }
        // Nothing cached yet (first fold since start): wait for the panel to draw.
        long deadline = SystemClock.uptimeMillis() + REVEAL_DEADLINE_MS;
        boolean panel = inner;
        phase = Phase.HOLDING;
        main.postDelayed(() -> captureLit(panel, deadline), 150);
    }

    private void captureLit(boolean panel, long deadline) {
        capture(shot -> {
            if (panel != inner || phase != Phase.HOLDING) return;
            if (shot == null || isMostlyBlack(shot)) {
                if (SystemClock.uptimeMillis() + SCREENSHOT_GAP_MS < deadline) captureLit(panel, deadline);
                else phase = Phase.IDLE;
                return;
            }
            if (panel) innerShot = shot; else coverShot = shot;
            show(shot, 1f);
            settle();
        });
    }

    /**
     * Freshly lit panel, fully frosted. The cover (phone shut) clears straight
     * away; the inner screen eases to half frost and waits for the hinge to
     * reach flat, so the glass clears as you finish opening.
     */
    private void settle() {
        if (!inner || lastAngle > 170f) {
            clear();
            return;
        }
        phase = Phase.HOLDING;
        animateTo(INNER_HALF_OPEN, SETTLE_MS, new DecelerateInterpolator(1.5f), () -> {});
        main.postDelayed(holdTimeout, HOLD_TIMEOUT_MS);
    }

    private void clear() {
        main.removeCallbacks(holdTimeout);
        if (!attached) {
            phase = Phase.IDLE;
            return;
        }
        phase = Phase.CLEARING;
        long ms = 450 + Math.round(650 * progress);
        animateTo(0f, ms, new DecelerateInterpolator(2f), () -> {
            detach();
            phase = Phase.IDLE;
            main.postDelayed(this::refreshShot, REFRESH_AFTER_MS);
        });
    }

    /** Keeps the cached screenshot of the lit panel current for the next fold. */
    private void refreshShot() {
        if (phase != Phase.IDLE) return;
        boolean panel = inner;
        capture(shot -> {
            if (shot == null || isMostlyBlack(shot) || panel != inner || phase != Phase.IDLE) return;
            if (panel) innerShot = shot; else coverShot = shot;
            Log.i(TAG, "refreshed " + (panel ? "inner" : "cover") + " screenshot");
        });
    }

    /** Plays a close-then-open on the current screen. */
    private void preview() {
        if (phase != Phase.IDLE) return;
        phase = Phase.FROSTING;
        capture(shot -> {
            if (shot == null) {
                phase = Phase.IDLE;
                return;
            }
            show(shot, 0f);
            animateTo(1f, 520, new AccelerateInterpolator(1.4f), () -> {
                phase = Phase.HOLDING;
                animateTo(INNER_HALF_OPEN, SETTLE_MS, new DecelerateInterpolator(1.5f),
                        () -> main.postDelayed(this::clear, 300));
            });
        });
    }

    /** Stops everything and removes the overlay. Screenshots stay cached. */
    private void reset() {
        main.removeCallbacksAndMessages(null);
        if (animator != null) animator.cancel();
        detach();
        phase = Phase.IDLE;
    }

    // --- Drawing ------------------------------------------------------------

    private void show(Bitmap shot, float startProgress) {
        view.setLook(readLook());
        view.setImage(shot, !inner);
        setProgress(startProgress);
        if (!attached) {
            wm.addView(view, lp);
            attached = true;
        }
    }

    private void setProgress(float p) {
        progress = p;
        view.setProgress(p);
        view.setAlpha(phase == Phase.CLEARING ? Math.min(1f, p / CROSSFADE_BELOW) : 1f);
    }

    private void animateTo(float target, long ms, android.animation.TimeInterpolator ease, Runnable done) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(ms);
        animator.setInterpolator(ease);
        animator.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) { if (!cancelled) done.run(); }
        });
        animator.start();
    }

    private void detach() {
        if (!attached) return;
        wm.removeViewImmediate(view);
        attached = false;
    }

    // --- Screenshots --------------------------------------------------------

    /** Screenshot of the current panel at half resolution, or null. Respects the rate limit. */
    private void capture(Consumer<Bitmap> done) {
        long wait = Math.max(0, lastShotAt + SCREENSHOT_GAP_MS - SystemClock.uptimeMillis());
        main.postDelayed(() -> {
            long started = SystemClock.uptimeMillis();
            lastShotAt = started;
            Log.i(TAG, "screenshot requested (waited " + wait + " ms)");
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    Log.i(TAG, "screenshot ok in " + (SystemClock.uptimeMillis() - started) + " ms");
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    Bitmap shot = null;
                    if (hw != null) {
                        Bitmap full = hw.copy(Bitmap.Config.ARGB_8888, false);
                        shot = Bitmap.createScaledBitmap(full, full.getWidth() / 2, full.getHeight() / 2, true);
                        full.recycle();
                        hw.recycle();
                    }
                    buffer.close();
                    done.accept(shot);
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.w(TAG, "screenshot failed: " + errorCode);
                    done.accept(null);
                }
            });
        }, wait);
    }

    /** True for a frame from a panel that is off or still turning on. */
    private static boolean isMostlyBlack(Bitmap b) {
        int brightest = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int c = b.getPixel(b.getWidth() * (2 * x + 1) / 32, b.getHeight() * (2 * y + 1) / 32);
                brightest = Math.max(brightest, Math.max((c >> 16) & 0xff, Math.max((c >> 8) & 0xff, c & 0xff)));
            }
        }
        boolean black = brightest < 16;
        if (black) Log.i(TAG, "screenshot is black, ignoring it");
        return black;
    }

    // --- Helpers ------------------------------------------------------------

    /** The inner panel is nearly square; the cover screen is tall and narrow. */
    private boolean isInnerPanel() {
        Display.Mode m = displays.getDisplay(Display.DEFAULT_DISPLAY).getMode();
        float w = m.getPhysicalWidth(), h = m.getPhysicalHeight();
        return Math.min(w, h) / Math.max(w, h) > 0.7f;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private GlassView.Look readLook() {
        SharedPreferences p = prefs();
        GlassView.Look look = new GlassView.Look();
        look.tiltDegrees = p.getInt("tilt", 30);
        look.blurPx = p.getInt("frost", 70);
        look.darkness = p.getInt("dark", 45) / 100f;
        return look;
    }

    @Override
    public void onDestroy() {
        reset();
        sensors.unregisterListener(this);
        displays.unregisterDisplayListener(this);
        unregisterReceiver(receiver);
        super.onDestroy();
    }
}
