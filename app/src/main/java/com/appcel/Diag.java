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
import java.util.List;
import java.util.Locale;

/**
 * Modo diagnóstico: durante unos minutos guarda el estado de los BOTONES DE
 * PESTAÑAS de Instagram y X (y si está abierto el reproductor de Reels) (si están seleccionados, su id y su posición).
 * No guarda ningún texto ni nada más de la pantalla. Queda solo en el teléfono.
 */
final class Diag {
    static final long DURATION_MS = 3 * 60 * 1000L;
    private static final long MAX_BYTES = 1_500_000;

    private static final String[] IG_TAB_IDS = {
            LimitService.IG_HOME_TAB_ID,
            LimitService.IG_REELS_TAB_ID,
            LimitService.IG + ":id/search_tab",
            LimitService.IG + ":id/direct_tab",
            LimitService.IG + ":id/creation_tab",
            LimitService.IG + ":id/profile_tab",
            LimitService.IG_REELS_PLAYER_IDS[0],
            LimitService.IG_REELS_PLAYER_IDS[1],
            LimitService.IG_REELS_PLAYER_IDS[2],
    };

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
        if (f.length() > MAX_BYTES || root == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== ")
                .append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
                .append("  app=").append(pkg.equals(LimitService.IG) ? "Instagram" : "X")
                .append("  detectado=").append(detected.name())
                .append(" =====\n");

        if (pkg.equals(LimitService.IG)) {
            for (String id : IG_TAB_IDS) {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
                String name = id.substring(id.indexOf('/') + 1);
                if (found == null || found.isEmpty()) {
                    sb.append("boton ").append(name).append(": no encontrado\n");
                } else {
                    describeTab(sb, "boton " + name, found.get(0));
                }
            }
            label(sb, root, LimitService.IG_REELS_LABEL);
            sb.append("forma de la pantalla (sin textos ni contenido):\n");
            int[] count = {0};
            shape(root, 0, sb, count);
        } else {
            label(sb, root, LimitService.X_FOR_YOU);
            label(sb, root, LimitService.X_FOLLOWING);
            label(sb, root, LimitService.X_HOME_TAB);
        }

        try (FileWriter w = new FileWriter(f, true)) {
            w.write(sb.toString());
        } catch (IOException ignored) {
        }
    }

    /**
     * Forma de la pantalla: solo tipo de elemento, nombre interno y tamaño.
     * Nunca textos, descripciones, fotos ni videos.
     */
    private static void shape(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] count) {
        if (n == null || count[0] >= 350 || depth > 25) return;
        if (!n.isVisibleToUser()) return;
        count[0]++;
        for (int i = 0; i < depth; i++) sb.append(' ');
        describe(sb, n);
        for (int i = 0; i < n.getChildCount(); i++) {
            shape(n.getChild(i), depth + 1, sb, count);
        }
    }

    private static void label(StringBuilder sb, AccessibilityNodeInfo root, String[] labels) {
        AccessibilityNodeInfo n = LimitService.findExact(root, labels);
        if (n == null) sb.append("pestaña \"").append(labels[0]).append("\": no encontrada\n");
        else describeTab(sb, "pestaña \"" + labels[0] + "\"", n);
    }

    /** El botón, sus contenedores y sus partes: tipo, id, seleccionado o no. Sin textos. */
    private static void describeTab(StringBuilder sb, String title, AccessibilityNodeInfo n) {
        sb.append(title).append(":\n");
        AccessibilityNodeInfo cur = n;
        AccessibilityNodeInfo clickable = null;
        for (int i = 0; cur != null && i < 5; i++) {
            sb.append("  ").append(i == 0 ? "boton    " : "contiene^").append(' ');
            describe(sb, cur);
            if (clickable == null && cur.isClickable()) clickable = cur;
            cur = cur.getParent();
        }
        if (clickable != null) {
            for (int i = 0; i < clickable.getChildCount() && i < 6; i++) {
                AccessibilityNodeInfo ch = clickable.getChild(i);
                if (ch == null) continue;
                sb.append("  parte    ");
                describe(sb, ch);
            }
        }
    }

    private static void describe(StringBuilder sb, AccessibilityNodeInfo n) {
        CharSequence cls = n.getClassName();
        String c = cls == null ? "?" : cls.toString();
        sb.append(c.substring(c.lastIndexOf('.') + 1));
        String id = n.getViewIdResourceName();
        if (id != null) sb.append(" #").append(id.substring(id.indexOf('/') + 1));
        if (n.isSelected()) sb.append(" [SELECCIONADO]");
        if (n.isChecked()) sb.append(" [CHK]");
        if (n.isClickable()) sb.append(" [clic]");
        if (!n.isVisibleToUser()) sb.append(" [oculto]");
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        sb.append(' ').append(r.left).append(',').append(r.top)
                .append(' ').append(r.width()).append('x').append(r.height()).append('\n');
    }
}
