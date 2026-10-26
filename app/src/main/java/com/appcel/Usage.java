package com.appcel;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiempo usado hoy en cada contenido. Se reinicia solo al cambiar el día. */
final class Usage {
    static final long LIMIT_MS = 15 * 60 * 1000L;

    private static final String PREFS = "usage";
    private static final String KEY_DATE = "date";

    private final SharedPreferences prefs;

    Usage(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void ensureToday() {
        String t = today();
        if (!t.equals(prefs.getString(KEY_DATE, ""))) {
            prefs.edit().clear().putString(KEY_DATE, t).apply();
        }
    }

    long used(Kind k) {
        ensureToday();
        return prefs.getLong(k.key, 0);
    }

    long remaining(Kind k) {
        return Math.max(0, LIMIT_MS - used(k));
    }

    void add(Kind k, long ms) {
        if (ms <= 0) return;
        ensureToday();
        prefs.edit().putLong(k.key, prefs.getLong(k.key, 0) + ms).apply();
    }

    /** Devuelve true solo la primera vez del día (para avisar una sola vez). */
    boolean markWarned(Kind k) {
        ensureToday();
        String key = "warned_" + k.key;
        if (prefs.getBoolean(key, false)) return false;
        prefs.edit().putBoolean(key, true).apply();
        return true;
    }

    static String format(long ms) {
        long s = (ms + 999) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
