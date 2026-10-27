package com.appcel;

import android.content.Context;

/** Ajustes del usuario (separados del contador diario para que no se borren a medianoche). */
final class AppSettings {
    private static final String PREFS = "settings";
    private static final String KEY_OVERLAY = "show_overlay";
    private static final String KEY_PAUSED = "paused";

    private AppSettings() {}

    static boolean overlayEnabled(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_OVERLAY, true);
    }

    static void setOverlayEnabled(Context c, boolean enabled) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_OVERLAY, enabled).apply();
    }

    /** Limit en pausa: sigue activado en Accesibilidad pero no hace nada. */
    static boolean paused(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PAUSED, false);
    }

    static void setPaused(Context c, boolean paused) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PAUSED, paused).apply();
    }
}
