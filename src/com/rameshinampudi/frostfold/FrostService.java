package com.rameshinampudi.frostfold;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Display;
import android.view.WindowManager;

/**
 * Listens to the hinge-angle sensor and drives a non-touchable overlay on the
 * inner screen: frosted while the phone is part-open, clearing as it goes flat.
 */
public class FrostService extends Service implements SensorEventListener, Choreographer.FrameCallback {

    static final String ACTION_PREVIEW = "preview";
    static final String ACTION_RELOAD = "reload";
    static final String PREFS = "frost";

    /** Inner screen: fully frosted at or below this angle... */
    private static final float INNER_FROST_ANGLE = 95f;
    /** ...and settled into sharp focus here, just before flat. */
    private static final float INNER_CLEAR_ANGLE = 178f;
    /** Cover screen: frosts over the first degrees of opening, before the inner screen takes over. */
    private static final float COVER_FROST_ANGLE = 30f;
    /** Movement smaller than this (degrees) is treated as sensor noise. */
    private static final float MOVE_EPSILON = 1.0f;
    /** Fade out when the hinge stops moving, so Flex mode is never covered. */
    private static final long REST_MS = 700;
    private static final long PREVIEW_MS = 1400;
    /** Critically damped spring: follows the hinge smoothly with no overshoot. */
    private static final float SPRING_STIFFNESS = 380f;
    private static final float SPRING_DAMPING = 2f * (float) Math.sqrt(SPRING_STIFFNESS);

    private WindowManager wm;
    private FrostView view;
    private WindowManager.LayoutParams lp;
    private boolean attached;
    private boolean ticking;
    private boolean blurSupported;

    private SensorManager sensors;
    private Sensor hinge;
    private boolean listening;

    private boolean enabled;
    private boolean onClosing;
    private int maxBlur;

    private float angle = -1f;
    private float movedFrom = -1f;
    private long lastMoveAt;
    private boolean closingNow;
    private float current;
    private float velocity;
    private long lastFrameNanos;
    private long previewStart = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        // A window context on the main display follows it as it moves between
        // the cover and inner panels when the phone folds.
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Context windowContext = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        wm = windowContext.getSystemService(WindowManager.class);
        view = new FrostView(windowContext);
        blurSupported = wm.isCrossWindowBlurEnabled();

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        lp.setTitle("FrostFold");
        // Android only lets touches pass through an app overlay up to this opacity.
        lp.alpha = Math.min(0.8f, getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch());
        if (blurSupported) lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;

        sensors = getSystemService(SensorManager.class);
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        enabled = p.getBoolean("enabled", false);
        onClosing = p.getBoolean("closing", true);
        maxBlur = p.getInt("blur", 110);

        if (enabled && !listening && hinge != null) {
            listening = sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_FASTEST);
        } else if (!enabled && listening) {
            sensors.unregisterListener(this);
            listening = false;
        }

        if (intent != null && ACTION_PREVIEW.equals(intent.getAction())) {
            previewStart = SystemClock.uptimeMillis();
            startTicking();
        }
        stopIfIdle();
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float a = event.values[0];
        if (movedFrom < 0 || Math.abs(a - movedFrom) > MOVE_EPSILON) {
            if (movedFrom >= 0) closingNow = a < movedFrom;
            movedFrom = a;
            lastMoveAt = SystemClock.uptimeMillis();
        }
        angle = a;
        startTicking();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void doFrame(long frameTimeNanos) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrameNanos == 0 ? 1f / 60f
                : Math.min(0.032f, (frameTimeNanos - lastFrameNanos) / 1e9f);
        lastFrameNanos = frameTimeNanos;

        boolean inner = onInnerScreen();
        float shownAngle = angle;
        float target;
        if (previewStart >= 0) {
            // Preview: play a simulated unfold from half-open to flat.
            float t = Math.min(1f, (now - previewStart) / (float) PREVIEW_MS);
            shownAngle = 70f + 110f * easeInOut(t);
            inner = true;
            target = innerFrost(shownAngle);
            if (t >= 1f) previewStart = -1;
        } else {
            target = hingeTarget(now, inner);
        }

        // Spring towards the target, so the glass eases in and settles like iOS.
        float accel = SPRING_STIFFNESS * (target - current) - SPRING_DAMPING * velocity;
        velocity += accel * dt;
        current = Math.max(0f, Math.min(1f, current + velocity * dt));

        if (current < 0.004f && target == 0f && previewStart < 0) {
            current = 0f;
            velocity = 0f;
            lastFrameNanos = 0;
            detach();
            ticking = false;
            stopIfIdle();
            return;
        }

        attach();
        float tilt = inner ? clamp((180f - shownAngle) / 90f) : 0f;
        view.setState(current, tilt, inner);
        if (blurSupported) {
            int radius = Math.round(maxBlur * current);
            if (attached && radius != lp.getBlurBehindRadius()) {
                lp.setBlurBehindRadius(radius);
                wm.updateViewLayout(view, lp);
            }
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private float hingeTarget(long now, boolean inner) {
        if (angle < 0 || now - lastMoveAt > REST_MS) return 0f;
        if (closingNow && !onClosing) return 0f;
        if (inner) return innerFrost(angle);
        // Cover screen fogs up as the phone starts to open (or finishes closing).
        return smoothstep(clamp(angle / COVER_FROST_ANGLE));
    }

    /** Stays frosted through most of the opening, then settles into focus near flat. */
    private static float innerFrost(float a) {
        return smoothstep(clamp((INNER_CLEAR_ANGLE - a) / (INNER_CLEAR_ANGLE - INNER_FROST_ANGLE)));
    }

    private static float smoothstep(float t) {
        return t * t * (3f - 2f * t);
    }

    private static float easeInOut(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** The inner panel is nearly square; the cover screen is tall and narrow. */
    private boolean onInnerScreen() {
        Rect b = wm.getCurrentWindowMetrics().getBounds();
        float ratio = Math.min(b.width(), b.height()) / (float) Math.max(b.width(), b.height());
        return ratio > 0.7f;
    }

    private void startTicking() {
        if (ticking) return;
        ticking = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void attach() {
        if (attached || !Settings.canDrawOverlays(this)) return;
        lp.setBlurBehindRadius(0);
        wm.addView(view, lp);
        attached = true;
    }

    private void detach() {
        if (!attached) return;
        wm.removeViewImmediate(view);
        attached = false;
    }

    private void stopIfIdle() {
        if (!enabled && !ticking && previewStart < 0) stopSelf();
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("frost", "Fold animation", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "frost")
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle("Frost Fold is on")
                .setContentText("Open or close your Fold to see it")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (listening) sensors.unregisterListener(this);
        Choreographer.getInstance().removeFrameCallback(this);
        detach();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
