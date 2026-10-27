package com.appcel;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String LATEST_APK_URL =
            "https://github.com/nelavpeluffo/Limit/releases/latest/download/Limit.apk";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 1000);
        }
    };

    private Usage usage;
    private TextView status, reelsText, xText, diagStatus;
    private Button diagButton, diagSend;
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

        // Desactivar Limit desde acá (para volver a activarlo hay que pasar por Accesibilidad:
        // Android no deja que una app se active sola).
        findViewById(R.id.btn_toggle).setOnClickListener(v -> {
            LimitService svc = LimitService.instance;
            if (isServiceEnabled() && svc != null) {
                svc.turnOff();
                handler.postDelayed(this::update, 400);
            } else {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        findViewById(R.id.btn_app_info).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null))));

        TextView versionText = findViewById(R.id.version_text);
        versionText.setText("Versión instalada: " + installedVersion()
                + "\nEl botón descarga la versión más nueva con el navegador. "
                + "Cuando termine, abrí el archivo y tocá «Actualizar» o «Instalar». "
                + "No hace falta volver a activarla.");
        findViewById(R.id.btn_update).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_APK_URL)));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "No encontré un navegador para descargarla",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });

        diagStatus = findViewById(R.id.diag_status);
        diagButton = findViewById(R.id.btn_diag);
        diagSend = findViewById(R.id.btn_diag_send);
        diagButton.setOnClickListener(v -> {
            if (Diag.recording()) Diag.stop();
            else Diag.start(this);
            update();
        });
        diagSend.setOnClickListener(v -> shareDiag());

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
        ((android.widget.Button) findViewById(R.id.btn_toggle)).setText(on
                ? "Desactivar Limit"
                : "Activar Limit (en Accesibilidad)");

        show(Kind.REELS, "Reels y lupa de Instagram", reelsText, reelsBar);
        show(Kind.FOR_YOU, "Para ti de X", xText, xBar);

        if (Diag.recording()) {
            diagStatus.setText("Grabando… quedan " + Usage.format(Diag.remainingMs())
                    + ". Andá a Instagram y X.");
            diagButton.setText("Terminar diagnóstico");
        } else {
            diagStatus.setText(Diag.hasData(this)
                    ? "Diagnóstico listo para enviar."
                    : "Sin diagnóstico grabado.");
            diagButton.setText("Empezar diagnóstico");
        }
        diagSend.setEnabled(Diag.hasData(this) && !Diag.recording());
    }

    private void shareDiag() {
        android.net.Uri uri = DiagProvider.uri(this);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico Limit")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("diagnostico", uri));
        startActivity(Intent.createChooser(send, "Enviar diagnóstico"));
    }

    private void show(Kind k, String name, TextView text, ProgressBar bar) {
        long used = Math.min(usage.used(k), Usage.LIMIT_MS);
        long remaining = usage.remaining(k);
        text.setText(name + ": " + Usage.format(used) + " de 15:00"
                + (remaining == 0 ? "  (límite alcanzado)" : "  (quedan " + Usage.format(remaining) + ")"));
        bar.setProgress((int) (used / 1000));
    }

    private String installedVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName cn = new ComponentName(this, LimitService.class);
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString());
    }
}
