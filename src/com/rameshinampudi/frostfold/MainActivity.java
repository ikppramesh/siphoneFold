package com.rameshinampudi.frostfold;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity implements SensorEventListener {

    private SharedPreferences prefs;
    private SensorManager sensors;
    private Sensor hinge;
    private TextView angleText;
    private TextView permissionText;
    private Button permissionButton;
    private Switch enableSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        prefs = getSharedPreferences(FrostService.PREFS, MODE_PRIVATE);
        sensors = getSystemService(SensorManager.class);
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);

        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(48), pad, pad);

        TextView title = new TextView(this);
        title.setText("Frost Fold");
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView about = text("A frosted-glass fold effect, driven by your hinge. "
                + "Open or close the phone and the glass clears as it goes flat.");
        root.addView(about);

        angleText = text(hinge == null ? "No hinge sensor found on this phone." : "Hinge angle: —");
        angleText.setTextSize(18);
        root.addView(angleText);

        permissionText = text("");
        root.addView(permissionText);
        permissionButton = new Button(this);
        permissionButton.setText("Allow display over other apps");
        permissionButton.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        root.addView(permissionButton);

        enableSwitch = new Switch(this);
        enableSwitch.setText("Fold animation");
        enableSwitch.setTextSize(18);
        enableSwitch.setPadding(0, dp(16), 0, dp(8));
        enableSwitch.setChecked(prefs.getBoolean("enabled", false));
        enableSwitch.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("enabled", on).apply();
            if (on) startFrost(null);
            else sendToRunningService();
        });
        root.addView(enableSwitch);

        Switch closingSwitch = new Switch(this);
        closingSwitch.setText("Also when closing");
        closingSwitch.setPadding(0, dp(8), 0, dp(8));
        closingSwitch.setChecked(prefs.getBoolean("closing", true));
        closingSwitch.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("closing", on).apply();
            sendToRunningService();
        });
        root.addView(closingSwitch);

        TextView blurLabel = text("");
        root.addView(blurLabel);
        SeekBar blur = new SeekBar(this);
        blur.setMax(200);
        blur.setProgress(prefs.getInt("blur", 110));
        blurLabel.setText("Frost strength: " + blur.getProgress());
        blur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                blurLabel.setText("Frost strength: " + value);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("blur", s.getProgress()).apply();
                sendToRunningService();
            }
        });
        root.addView(blur);

        Button preview = new Button(this);
        preview.setText("Preview animation");
        preview.setOnClickListener(v -> startFrost(FrostService.ACTION_PREVIEW));
        root.addView(preview);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        boolean canDraw = Settings.canDrawOverlays(this);
        permissionText.setText(canDraw
                ? "✓ Allowed to draw over other apps"
                : "Step 1: Frost Fold needs permission to draw over other apps.");
        permissionButton.setVisibility(canDraw ? View.GONE : View.VISIBLE);
        enableSwitch.setEnabled(canDraw);
        if (hinge != null) sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensors.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        angleText.setText(String.format("Hinge angle: %.0f°", event.values[0]));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void startFrost(String action) {
        Intent i = new Intent(this, FrostService.class);
        if (action != null) i.setAction(action);
        startForegroundService(i);
    }

    /** Lets a running service pick up new settings (or stop) without starting one. */
    private void sendToRunningService() {
        if (prefs.getBoolean("enabled", false)) startFrost(FrostService.ACTION_RELOAD);
        else stopService(new Intent(this, FrostService.class));
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
