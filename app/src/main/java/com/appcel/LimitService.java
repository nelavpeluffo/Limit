package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Mira qué pantalla está abierta en Instagram y en X (Twitter).
 * Mientras estás en Reels o en "Para ti" cuenta los segundos; al pasar
 * los 15 minutos del día te saca de ahí.
 */
public class LimitService extends AccessibilityService {

    static final String IG = "com.instagram.android";
    static final String X = "com.twitter.android";

    private static final String[] IG_REELS_IDS = {
            IG + ":id/clips_viewer_view_pager",
            IG + ":id/clips_video_container",
            IG + ":id/clips_viewer_container",
    };
    private static final String IG_HOME_TAB_ID = IG + ":id/feed_tab";

    private static final String[] X_FOR_YOU = {"For you", "Para ti", "Para tí", "Para vos"};
    private static final String[] X_FOLLOWING = {"Following", "Siguiendo"};
    private static final String[] X_HOME_TAB = {"Home", "Inicio"};

    private static final long TICK_MS = 1000;
    private static final long IDLE_STOP_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = this::tick;

    private Usage usage;
    private Overlay overlay;

    private boolean ticking;
    private long lastTick;
    private long lastActive;
    private Kind prevKind = Kind.NONE;

    private long lastEnforce;
    private int enforceStreak;

    // Estado de X: la barra de pestañas se esconde al scrollear, así que
    // recordamos si la última pestaña vista fue "Para ti".
    private boolean xForYou;
    private boolean xForYouKnown;
    private String xActivity;
    private String xForYouActivity;

    @Override
    protected void onServiceConnected() {
        usage = new Usage(this);
        overlay = new Overlay(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (usage == null || e == null) return;
        CharSequence pkg = e.getPackageName();
        if (pkg != null && X.contentEquals(pkg)) trackXEvent(e);
        startTicking();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopTicking();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopTicking();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- reloj

    private void startTicking() {
        lastActive = SystemClock.elapsedRealtime();
        if (ticking) return;
        ticking = true;
        lastTick = SystemClock.elapsedRealtime();
        handler.post(tickRunnable);
    }

    private void stopTicking() {
        ticking = false;
        handler.removeCallbacks(tickRunnable);
        prevKind = Kind.NONE;
        if (overlay != null) overlay.hide();
    }

    private void tick() {
        if (!ticking) return;
        long now = SystemClock.elapsedRealtime();
        Kind k = detect();

        if (k != Kind.NONE) {
            lastActive = now;
            if (prevKind == k) usage.add(k, Math.min(now - lastTick, 2500));

            long remaining = usage.remaining(k);
            if (remaining <= 0) {
                overlay.show(k.label + ": límite de hoy alcanzado", true);
                enforce(k);
            } else {
                enforceStreak = 0;
                if (remaining <= 60_000 && usage.markWarned(k)) {
                    toast("Te queda 1 minuto de " + k.label + " por hoy");
                }
                if (AppSettings.overlayEnabled(this)) {
                    overlay.show(k.label + " · quedan " + Usage.format(remaining), remaining <= 60_000);
                } else {
                    overlay.hide();
                }
            }
        } else {
            overlay.hide();
            if (now - lastActive > IDLE_STOP_MS) {
                ticking = false;
                prevKind = Kind.NONE;
                return;
            }
        }

        prevKind = k;
        lastTick = now;
        handler.postDelayed(tickRunnable, TICK_MS);
    }

    // ------------------------------------------------------------ detección

    private Kind detect() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return Kind.NONE;

        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception ex) {
            return Kind.NONE;
        }
        if (root == null || root.getPackageName() == null) return Kind.NONE;

        String pkg = root.getPackageName().toString();
        try {
            if (IG.equals(pkg)) return reelsVisible(root) ? Kind.REELS : Kind.NONE;
            if (X.equals(pkg)) return forYouVisible(root) ? Kind.FOR_YOU : Kind.NONE;
        } catch (Exception ignored) {
            // Si la app cambia algo raro en su interfaz, no rompemos el servicio.
        }
        return Kind.NONE;
    }

    /** Instagram: el visor de Reels tiene ids "clips_..."; también la pestaña Reels seleccionada. */
    private boolean reelsVisible(AccessibilityNodeInfo root) {
        for (String id : IG_REELS_IDS) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
            if (found != null) {
                for (AccessibilityNodeInfo n : found) {
                    if (n != null && n.isVisibleToUser()) return true;
                }
            }
        }

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen < 400) {
            AccessibilityNodeInfo n = queue.poll();
            seen++;
            if (n == null) continue;
            String id = n.getViewIdResourceName();
            if (id != null && n.isVisibleToUser()) {
                if (id.contains("clips_viewer")) return true;
                if (id.endsWith("/clips_tab") && n.isSelected()) return true;
            }
            if (n.isSelected() && startsWithLabel(n.getContentDescription(), "Reels")) return true;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return false;
    }

    /** X: pestaña "Para ti" seleccionada en el Inicio. */
    private boolean forYouVisible(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo home = findExact(root, X_HOME_TAB);
        if (home != null && !isSelectedUp(home) && !isSelectedDown(home, 2) && hasSelectedSibling(home)) {
            // Estás en otra pestaña de abajo (Buscar, Notificaciones, etc.)
            xForYou = false;
            xForYouKnown = true;
            return false;
        }

        AccessibilityNodeInfo forYou = findExact(root, X_FOR_YOU);
        AccessibilityNodeInfo following = findExact(root, X_FOLLOWING);

        if (forYou != null) {
            if (isSelectedUp(forYou)) {
                setForYou(true);
            } else if (following != null && isSelectedUp(following)) {
                setForYou(false);
            } else if (!xForYouKnown) {
                // X no indica cuál está seleccionada: "Para ti" es la pestaña por defecto.
                setForYou(true);
            }
            return xForYou;
        }
        if (following != null && isSelectedUp(following)) {
            setForYou(false);
            return false;
        }

        // Las pestañas se ocultaron al scrollear: seguimos en lo último que vimos,
        // mientras no hayas abierto otra pantalla de X.
        return xForYou && Objects.equals(xActivity, xForYouActivity);
    }

    private void setForYou(boolean value) {
        xForYou = value;
        xForYouKnown = true;
        if (value) xForYouActivity = xActivity;
    }

    private void trackXEvent(AccessibilityEvent e) {
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cls = e.getClassName();
            if (cls != null && cls.toString().endsWith("Activity")) xActivity = cls.toString();
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            String text = eventText(e);
            if (matchesAny(text, X_FOR_YOU)) setForYou(true);
            else if (matchesAny(text, X_FOLLOWING)) setForYou(false);
        }
    }

    // ----------------------------------------------------------- aplicar límite

    private void enforce(Kind k) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastEnforce < 1500) return;
        if (now - lastEnforce > 15_000) {
            enforceStreak = 0;
            toast("Ya usaste tus 15 minutos de " + k.label + " de hoy");
        }
        lastEnforce = now;
        enforceStreak++;

        // Si los intentos suaves no funcionan, vamos a la pantalla de inicio del teléfono.
        if (enforceStreak >= 4) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (k == Kind.FOR_YOU) {
            if (root != null) {
                AccessibilityNodeInfo following = findExact(root, X_FOLLOWING);
                if (following != null && clickUp(following)) {
                    setForYou(false);
                    return;
                }
                // Tocar "Inicio" sube al principio y vuelve a mostrar las pestañas.
                AccessibilityNodeInfo home = findExact(root, X_HOME_TAB);
                if (home != null && clickUp(home)) return;
            }
            performGlobalAction(GLOBAL_ACTION_HOME);
        } else {
            if (root != null) {
                List<AccessibilityNodeInfo> homeTab = root.findAccessibilityNodeInfosByViewId(IG_HOME_TAB_ID);
                if (homeTab != null && !homeTab.isEmpty() && clickUp(homeTab.get(0))) return;
            }
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    // ------------------------------------------------------------- utilidades

    private static AccessibilityNodeInfo findExact(AccessibilityNodeInfo root, String[] labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                if (n == null || !n.isVisibleToUser()) continue;
                if (startsWithLabel(n.getText(), label) || startsWithLabel(n.getContentDescription(), label)) {
                    return n;
                }
            }
        }
        return null;
    }

    /** "For you" o "For you, pestaña 1 de 2", pero no un tuit que diga "For you I would…". */
    private static boolean startsWithLabel(CharSequence cs, String label) {
        if (cs == null) return false;
        String s = cs.toString().trim().toLowerCase(Locale.ROOT);
        String l = label.toLowerCase(Locale.ROOT);
        return s.equals(l) || s.startsWith(l + ",") || s.startsWith(l + ".");
    }

    private static boolean matchesAny(String text, String[] labels) {
        if (text == null) return false;
        for (String l : labels) if (startsWithLabel(text, l)) return true;
        return false;
    }

    private static String eventText(AccessibilityEvent e) {
        if (e.getText() != null && !e.getText().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence cs : e.getText()) if (cs != null) sb.append(cs);
            if (sb.length() > 0) return sb.toString();
        }
        return e.getContentDescription() != null ? e.getContentDescription().toString() : null;
    }

    private static boolean isSelectedUp(AccessibilityNodeInfo n) {
        for (int i = 0; n != null && i < 4; i++) {
            if (n.isSelected()) return true;
            n = n.getParent();
        }
        return false;
    }

    /** ¿Alguno de los botones vecinos (barra de abajo) figura seleccionado? */
    private static boolean hasSelectedSibling(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo item = n;
        for (int level = 0; level < 3 && item != null; level++) {
            AccessibilityNodeInfo parent = item.getParent();
            if (parent == null) return false;
            if (parent.getChildCount() >= 3) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    AccessibilityNodeInfo c = parent.getChild(i);
                    if (c != null && !c.equals(item) && isSelectedDown(c, 2)) return true;
                }
                return false;
            }
            item = parent;
        }
        return false;
    }

    private static boolean isSelectedDown(AccessibilityNodeInfo n, int depth) {
        if (n.isSelected()) return true;
        if (depth == 0) return false;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null && isSelectedDown(c, depth - 1)) return true;
        }
        return false;
    }

    private static boolean clickUp(AccessibilityNodeInfo n) {
        for (int i = 0; n != null && i < 5; i++) {
            if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            n = n.getParent();
        }
        return false;
    }

    private void toast(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
    }
}
