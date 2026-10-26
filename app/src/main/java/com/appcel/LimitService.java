package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Mira qué pantalla está abierta en Instagram y en X (Twitter).
 * Mientras estás en Reels o en "Para ti" cuenta los segundos; al pasar
 * los 15 minutos del día te saca de ahí.
 */
public class LimitService extends AccessibilityService {

    static final String IG = "com.instagram.android";
    static final String X = "com.twitter.android";

    // Solo se buscan estos botones de pestañas; nada más de la pantalla.
    static final String IG_REELS_TAB_ID = IG + ":id/clips_tab";
    static final String IG_HOME_TAB_ID = IG + ":id/feed_tab";
    static final String[] IG_REELS_LABEL = {"Reels"};

    static final String[] X_FOR_YOU = {"For you", "Para ti", "Para tí", "Para vos"};
    static final String[] X_FOLLOWING = {"Following", "Siguiendo"};
    static final String[] X_HOME_TAB = {"Home", "Inicio"};

    private static final long TICK_MS = 1000;
    private static final long IDLE_STOP_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = this::tick;

    private Usage usage;
    private Overlay overlay;
    private Blocker blocker;

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

    // Diagnóstico
    private AccessibilityNodeInfo lastRoot;
    private String lastPkg;
    private Kind lastDiagKind;
    private long lastDiagAt;

    @Override
    protected void onServiceConnected() {
        usage = new Usage(this);
        overlay = new Overlay(this);
        blocker = new Blocker(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (usage == null || e == null) return;
        CharSequence pkg = e.getPackageName();
        if (pkg != null && X.contentEquals(pkg)) trackXEvent(e);
        if (pkg != null) {
            // Reaccionar enseguida (sin esperar al próximo segundo) para tapar el botón a tiempo.
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && root.getPackageName() != null) {
                    String rootPkg = root.getPackageName().toString();
                    if (rootPkg.contentEquals(pkg)) updateBlocker(root, rootPkg);
                }
            } catch (Exception ignored) {
            }
        }
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
        if (blocker != null) blocker.hide();
    }

    private void tick() {
        if (!ticking) return;
        long now = SystemClock.elapsedRealtime();
        Kind k = detect();

        if (lastPkg != null) updateBlocker(lastRoot, lastPkg);
        else blocker.hide();
        if (blocker.isShown()) lastActive = now;

        if (Diag.recording() && lastPkg != null) {
            lastActive = now;
            if (k != lastDiagKind || now - lastDiagAt > 5000) {
                Diag.record(this, lastPkg, k, lastRoot);
                lastDiagKind = k;
                lastDiagAt = now;
            }
            String what = k == Kind.NONE ? "nada" : k.label;
            overlay.show("Diagnóstico · detecto: " + what, false);
            prevKind = k;
            lastTick = now;
            handler.postDelayed(tickRunnable, TICK_MS);
            return;
        }

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
        lastRoot = null;
        lastPkg = null;
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
        if (IG.equals(pkg) || X.equals(pkg)) {
            lastRoot = root;
            lastPkg = pkg;
        }
        try {
            if (IG.equals(pkg)) return reelsVisible(root) ? Kind.REELS : Kind.NONE;
            if (X.equals(pkg)) return forYouVisible(root) ? Kind.FOR_YOU : Kind.NONE;
        } catch (Exception ignored) {
            // Si la app cambia algo raro en su interfaz, no rompemos el servicio.
        }
        return Kind.NONE;
    }

    /** Pone o saca la tapa con candado sobre el botón de Reels o de Para ti. */
    private void updateBlocker(AccessibilityNodeInfo root, String pkg) {
        AccessibilityNodeInfo tab = null;
        String msg = null;
        if (root != null && IG.equals(pkg) && usage.remaining(Kind.REELS) <= 0) {
            tab = reelsTabButton(root);
            msg = "Reels está bloqueado hasta mañana";
        } else if (root != null && X.equals(pkg) && usage.remaining(Kind.FOR_YOU) <= 0) {
            tab = clickableUp(findExact(root, X_FOR_YOU));
            msg = "Para ti está bloqueado hasta mañana";
        }
        Rect r = new Rect();
        if (tab != null && tab.isVisibleToUser()) tab.getBoundsInScreen(r);
        if (r.width() < 10 || r.height() < 10) blocker.hide();
        else blocker.show(r, msg);
    }

    private static AccessibilityNodeInfo clickableUp(AccessibilityNodeInfo n) {
        for (int i = 0; n != null && i < 4; i++) {
            if (n.isClickable()) return n;
            n = n.getParent();
        }
        return null;
    }

    /** El botón de la pestaña Reels de la barra de abajo de Instagram. */
    private static AccessibilityNodeInfo reelsTabButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(IG_REELS_TAB_ID);
        if (byId != null) {
            for (AccessibilityNodeInfo n : byId) {
                if (n != null && n.isVisibleToUser()) return n;
            }
        }
        return clickableUp(findExact(root, IG_REELS_LABEL));
    }

    /**
     * Instagram: ¿está seleccionado el botón de la pestaña Reels (barra de abajo)?
     * Se busca ese botón por su id o por su nombre "Reels"; no se mira nada más.
     */
    private boolean reelsVisible(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(IG_REELS_TAB_ID);
        if (byId != null && !byId.isEmpty()) {
            for (AccessibilityNodeInfo n : byId) {
                if (n != null && n.isVisibleToUser() && (n.isSelected() || isSelectedDown(n, 2))) return true;
            }
            return false;
        }
        AccessibilityNodeInfo byName = findExact(root, IG_REELS_LABEL);
        return byName != null && tabSelected(byName);
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
        boolean forYouSel = forYou != null && tabSelected(forYou);
        boolean followingSel = following != null && tabSelected(following);

        // Solo cuenta si "Para ti" figura seleccionada y "Siguiendo" no
        // (o si tocaste "Para ti" y todavía no cambiaste).
        if (forYouSel && !followingSel) setForYou(true);
        else if (followingSel && !forYouSel) setForYou(false);

        if (forYou != null && following != null) return xForYou;

        // Las pestañas se ocultaron al scrollear: seguimos en lo último que vimos,
        // mientras no hayas abierto otra pantalla de X.
        return xForYou && xActivity != null && xActivity.equals(xForYouActivity);
    }

    /** ¿La pestaña (el botón tocable que contiene este texto) está seleccionada? */
    private static boolean tabSelected(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 4; i++) {
            if (cur.isSelected()) return true;
            if (cur.isClickable()) return isSelectedDown(cur, 2);
            cur = cur.getParent();
        }
        return false;
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

    static AccessibilityNodeInfo findExact(AccessibilityNodeInfo root, String[] labels) {
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
