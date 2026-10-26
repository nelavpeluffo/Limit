package com.appcel;

import android.content.Context;

/** Ajustes del usuario (separados del contador diario para que no se borren a medianoche). */
final class AppSettings {
    private static final String PREFS = "settings";
    private static final String KEY_OVERLAY = "show_overlay";
    private static final String KEY_IG_FOLLOWING = "ig_open_following";

    private AppSettings() {}

    static boolean overlayEnabled(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_OVERLAY, true);
    }

    static boolean igOpenFollowing(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_IG_FOLLOWING, true);
    }

    static void setIgOpenFollowing(Context c, boolean enabled) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_IG_FOLLOWING, enabled).apply();
    }

    static void setOverlayEnabled(Context c, boolean enabled) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_OVERLAY, enabled).apply();
    }
}
