package com.rameshinampudi.frostfold;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
    private LinearLayout root;
    private TextView angleText;
    private TextView serviceText;
    private Button serviceButton;
    private Button preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        prefs = getSharedPreferences(FoldService.PREFS, MODE_PRIVATE);
        sensors = getSystemService(SensorManager.class);
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);

        int pad = dp(24);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(48), pad, pad);

        TextView title = new TextView(this);
        title.setText("Frost Fold");
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("The iPhone Duo frosted-glass fold, for your Galaxy Fold. "
                + "Each half of the screen tilts away like a pane of frosted glass and "
                + "settles into focus as the phone opens."));

        angleText = text(hinge == null ? "No hinge sensor found on this phone." : "Hinge: —");
        angleText.setTextSize(18);
        root.addView(angleText);

        serviceText = text("");
        root.addView(serviceText);
        serviceButton = new Button(this);
        serviceButton.setText("Turn on in Accessibility");
        serviceButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(serviceButton);

        Switch closing = new Switch(this);
        closing.setText("Also when closing");
        closing.setTextSize(16);
        closing.setPadding(0, dp(16), 0, dp(8));
        closing.setChecked(prefs.getBoolean("closing", true));
        closing.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("closing", on).apply());
        root.addView(closing);

        slider("Tilt", "tilt", 30, 10, 45, "°");
        slider("Frost", "frost", 70, 0, 140, " px");
        slider("Darkening", "dark", 45, 0, 80, "%");

        preview = new Button(this);
        preview.setText("Preview animation");
        preview.setOnClickListener(v -> sendBroadcast(new Intent(FoldService.ACTION_PREVIEW).setPackage(getPackageName())));
        root.addView(preview);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isServiceOn();
        serviceText.setText(on
                ? "✓ Fold animation is on"
                : "Turn on \"Frost Fold animation\" in Accessibility settings (Installed apps).");
        serviceButton.setVisibility(on ? View.GONE : View.VISIBLE);
        preview.setEnabled(on);
        if (hinge != null) sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensors.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        angleText.setText(String.format("Hinge: %.0f°", event.values[0]));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private boolean isServiceOn() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String me = new ComponentName(this, FoldService.class).flattenToString();
        return enabled != null && enabled.contains(me);
    }

    private void slider(String label, String key, int def, int min, int max, String unit) {
        TextView name = text("");
        root.addView(name);
        SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(prefs.getInt(key, def));
        name.setText(label + ": " + bar.getProgress() + unit);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                name.setText(label + ": " + value + unit);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt(key, s.getProgress()).apply();
            }
        });
        root.addView(bar);
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
