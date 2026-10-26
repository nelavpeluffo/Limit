package com.appcel;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 1000);
        }
    };

    private Usage usage;
    private TextView status, reelsText, xText;
    private ProgressBar reelsBar, xBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        usage = new Usage(this);

        status = findViewById(R.id.status);
        reelsText = findViewById(R.id.reels_text);
        xText = findViewById(R.id.x_text);
        reelsBar = findViewById(R.id.reels_bar);
        xBar = findViewById(R.id.x_bar);

        findViewById(R.id.btn_accessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.btn_app_info).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null))));

        Switch overlaySwitch = findViewById(R.id.overlay_switch);
        overlaySwitch.setChecked(AppSettings.overlayEnabled(this));
        overlaySwitch.setOnCheckedChangeListener((b, checked) ->
                AppSettings.setOverlayEnabled(this, checked));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void update() {
        boolean on = isServiceEnabled();
        status.setText(on
                ? "✓ Activada: el límite está funcionando"
                : "✗ Desactivada: seguí los pasos de abajo para activarla");
        status.setBackgroundColor(getColor(on ? R.color.ok : R.color.warn));

        show(Kind.REELS, "Reels de Instagram", reelsText, reelsBar);
        show(Kind.FOR_YOU, "Para ti de X", xText, xBar);
    }

    private void show(Kind k, String name, TextView text, ProgressBar bar) {
        long used = Math.min(usage.used(k), Usage.LIMIT_MS);
        long remaining = usage.remaining(k);
        text.setText(name + ": " + Usage.format(used) + " de 15:00"
                + (remaining == 0 ? "  (límite alcanzado)" : "  (quedan " + Usage.format(remaining) + ")"));
        bar.setProgress((int) (used / 1000));
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName cn = new ComponentName(this, LimitService.class);
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString());
    }
}
