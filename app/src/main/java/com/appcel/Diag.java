package com.appcel;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Modo diagnóstico: durante unos minutos guarda cómo está armada la pantalla
 * de Instagram y X (ids de los elementos, pestañas seleccionadas, tamaños),
 * para poder ajustar la detección. Se guarda solo en el teléfono.
 */
final class Diag {
    static final long DURATION_MS = 3 * 60 * 1000L;
    private static final long MAX_BYTES = 1_500_000;
    private static final int MAX_NODES = 450;

    private static long until;

    private Diag() {}

    static void start(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
        until = SystemClock.elapsedRealtime() + DURATION_MS;
    }

    static void stop() {
        until = 0;
    }

    static boolean recording() {
        return SystemClock.elapsedRealtime() < until;
    }

    static long remainingMs() {
        return Math.max(0, until - SystemClock.elapsedRealtime());
    }

    static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "diagnostico.txt");
    }

    static boolean hasData(Context c) {
        return file(c).length() > 0;
    }

    static void record(Context c, String pkg, Kind detected, AccessibilityNodeInfo root) {
        File f = file(c);
        if (f.length() > MAX_BYTES) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== ")
                .append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
                .append("  app=").append(pkg)
                .append("  detectado=").append(detected.name())
                .append(" =====\n");
        int[] count = {0};
        dump(root, 0, sb, count);
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(sb.toString());
        } catch (IOException ignored) {
        }
    }

    private static void dump(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] count) {
        if (n == null || count[0] >= MAX_NODES) return;
        count[0]++;
        for (int i = 0; i < depth; i++) sb.append(' ');

        CharSequence cls = n.getClassName();
        String c = cls == null ? "?" : cls.toString();
        sb.append(c.substring(c.lastIndexOf('.') + 1));

        String id = n.getViewIdResourceName();
        if (id != null) sb.append(" #").append(id.substring(id.indexOf('/') + 1));

        appendShort(sb, " t=", n.getText());
        appendShort(sb, " d=", n.getContentDescription());

        if (n.isSelected()) sb.append(" [SEL]");
        if (n.isChecked()) sb.append(" [CHK]");
        if (n.isClickable()) sb.append(" [clic]");
        if (!n.isVisibleToUser()) sb.append(" [oculto]");

        Rect r = new Rect();
        n.getBoundsInScreen(r);
        sb.append(' ').append(r.left).append(',').append(r.top)
                .append(' ').append(r.width()).append('x').append(r.height())
                .append('\n');

        for (int i = 0; i < n.getChildCount(); i++) {
            dump(n.getChild(i), depth + 1, sb, count);
        }
    }

    private static void appendShort(StringBuilder sb, String prefix, CharSequence cs) {
        if (cs == null || cs.length() == 0) return;
        String s = cs.toString().replace('\n', ' ').trim();
        if (s.length() > 30) s = s.substring(0, 30) + "…";
        sb.append(prefix).append('"').append(s).append('"');
    }
}
