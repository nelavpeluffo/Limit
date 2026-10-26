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

import java.util.ArrayDeque;
import java.util.ArrayList;
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
    static final String IG_SEARCH_TAB_ID = IG + ":id/search_tab";
    static final String[] IG_REELS_LABEL = {"Reels"};
    static final String[] IG_REELS_PLAYER_IDS = {
            IG + ":id/clips_viewer_view_pager",
            IG + ":id/clips_viewer_container",
            IG + ":id/clips_video_container",
    };

    static final String[] X_FOR_YOU = {"For you", "Para ti", "Para tí", "Para vos"};
    static final String[] X_FOLLOWING = {"Following", "Siguiendo"};
    static final String[] X_HOME_TAB = {"Home", "Inicio"};

    private static final long TICK_MS = 1000;
    private static final long IDLE_STOP_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = this::tick;

    private Usage usage;
    private Overlay overlay;
    private Blocker blocker;        // botón Reels (Instagram) o Para ti (X)
    private Blocker searchBlocker;  // botón de la lupa (Instagram)
    // Última sección de Instagram vista con la barra de abajo (lupa, reels u otra).
    private String igSection = "otra";

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
        searchBlocker = new Blocker(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (usage == null || e == null) return;
        CharSequence pkg = e.getPackageName();
        boolean target = pkg != null && (IG.contentEquals(pkg) || X.contentEquals(pkg));
        if (!target) {
            // Otra app pasó al frente (inicio del teléfono, otra app...): solo se mira
            // cuál app está abierta, para sacar el candado y el contador al instante.
            if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                hideIfLeft();
                // Durante la animación de cierre Instagram puede seguir figurando unos instantes.
                handler.postDelayed(this::hideIfLeft, 150);
                handler.postDelayed(this::hideIfLeft, 400);
            }
            return;
        }
        if (X.contentEquals(pkg)) trackXEvent(e);
        if (IG.contentEquals(pkg) && e.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            onInstagramLongPress(e);
        }
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

    private void hideIfLeft() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence p = root == null ? null : root.getPackageName();
            if (p != null && (IG.contentEquals(p) || X.contentEquals(p))) return;
        } catch (Exception ignored) {
        }
        blocker.hide();
        searchBlocker.hide();
        overlay.hide();
    }

    /**
     * Mantener apretado un Reel en la grilla de la lupa abre una previsualización que
     * lo reproduce. Con el límite cumplido, se cierra enseguida. Se reconoce que es un
     * Reel porque su miniatura tiene el contador de reproducciones.
     */
    private void onInstagramLongPress(AccessibilityEvent e) {
        AccessibilityNodeInfo src;
        try {
            src = e.getSource();
        } catch (Exception ex) {
            return;
        }
        boolean reel = isReelTile(src);
        if (Diag.recording()) {
            String id = src == null ? null : src.getViewIdResourceName();
            Diag.note(this, "mantener apretado en " + (id == null ? "?" : id.substring(id.indexOf('/') + 1))
                    + " → " + (reel ? "es un Reel" : "no es un Reel"));
        }
        if (usage.remaining(Kind.REELS) > 0 || !reel) return;
        toast("Reels está bloqueado hasta mañana");
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 300);
        handler.postDelayed(this::closeLeftoverPreview, 1200);
    }

    /** Si la previsualización sigue abierta (sigue habiendo un video), otro "atrás". */
    private void closeLeftoverPreview() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !IG.contentEquals(root.getPackageName())) return;
            List<AccessibilityNodeInfo> grid = root.findAccessibilityNodeInfosByViewId(IG + ":id/recycler_view");
            boolean gridVisible = false;
            if (grid != null) for (AccessibilityNodeInfo g : grid) if (g != null && g.isVisibleToUser()) gridVisible = true;
            List<AccessibilityNodeInfo> video = root.findAccessibilityNodeInfosByViewId(IG + ":id/clips_video_container");
            boolean videoVisible = false;
            if (video != null) for (AccessibilityNodeInfo v : video) if (v != null && v.isVisibleToUser()) videoVisible = true;
            if (videoVisible && gridVisible) performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Exception ignored) {
        }
    }

    private static boolean isReelTile(AccessibilityNodeInfo n) {
        for (int level = 0; n != null && level < 3; level++) {
            if (hasPlayCount(n, 0)) return true;
            n = n.getParent();
        }
        return false;
    }

    private static boolean hasPlayCount(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 2) return false;
        String id = n.getViewIdResourceName();
        if (id != null && (id.endsWith("/play_count_container") || id.endsWith("/play_count_logo"))) return true;
        // No subir hasta la grilla entera: solo dentro de la miniatura.
        if (id != null && id.endsWith("/recycler_view")) return false;
        for (int i = 0; i < n.getChildCount() && i < 6; i++) {
            if (hasPlayCount(n.getChild(i), depth + 1)) return true;
        }
        return false;
    }

    private void note(String text) {
        if (Diag.recording()) Diag.note(this, text);
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
        if (searchBlocker != null) searchBlocker.hide();
    }

    private void tick() {
        if (!ticking) return;
        long now = SystemClock.elapsedRealtime();
        Kind k = detect();

        if (lastPkg != null) updateBlocker(lastRoot, lastPkg);
        else {
            blocker.hide();
            searchBlocker.hide();
        }
        if (blocker.isShown() || searchBlocker.isShown()) lastActive = now;

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
        if (root != null && IG.equals(pkg) && usage.remaining(Kind.REELS) <= 0) {
            // Con un Reel o la sección Reels abierta, la barra de abajo no está: sin candados.
            if (reelsTabSelected(root) || reelsPlayerFullScreen(root)) {
                blocker.hide();
                searchBlocker.hide();
                return;
            }
            String msg = "Reels y la lupa están bloqueados hasta mañana";
            place(blocker, reelsTabButton(root), root, true, msg);
            place(searchBlocker, searchTabButton(root), root, true, msg);
            return;
        }
        searchBlocker.hide();
        if (root != null && X.equals(pkg) && usage.remaining(Kind.FOR_YOU) <= 0) {
            Rect screen = new Rect();
            root.getBoundsInScreen(screen);
            List<AccessibilityNodeInfo> tabs = xTopTabs(root, screen);
            AccessibilityNodeInfo forYouTab = tabs != null ? tabs.get(0) : clickableUp(findExact(root, X_FOR_YOU));
            place(blocker, forYouTab, root, false, "Para ti está bloqueado hasta mañana");
        } else {
            blocker.hide();
        }
    }

    private static void place(Blocker b, AccessibilityNodeInfo tab, AccessibilityNodeInfo root,
                              boolean bottomBar, String msg) {
        Rect r = new Rect();
        if (tab != null && tab.isVisibleToUser()) tab.getBoundsInScreen(r);
        if (looksLikeTabButton(r, root, bottomBar)) b.show(r, msg);
        else b.hide();
    }

    /** El botón de la lupa (Explorar) de la barra de abajo de Instagram. */
    private static AccessibilityNodeInfo searchTabButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(IG_SEARCH_TAB_ID);
        if (byId != null) {
            for (AccessibilityNodeInfo n : byId) {
                if (n != null && n.isVisibleToUser()) return n;
            }
        }
        return null;
    }

    /** ¿Está seleccionada la lupa? */
    private static boolean searchTabSelected(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo n = searchTabButton(root);
        return n != null && (n.isSelected() || isSelectedDown(n, 2));
    }

    /**
     * Mientras la app anima la barra (al abrir un Reel, por ejemplo), el botón puede
     * informar posiciones o tamaños raros. Solo se tapa si tiene forma de botón y,
     * en Instagram, si está en la parte de abajo de la pantalla.
     */
    private static boolean looksLikeTabButton(Rect r, AccessibilityNodeInfo root, boolean bottomBar) {
        if (r.width() < 10 || r.height() < 10) return false;
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (screen.isEmpty()) return false;
        if (r.height() > screen.height() * 0.12f || r.width() > screen.width() * 0.6f) return false;
        if (bottomBar && r.top < screen.height() * 0.8f) return false;
        return true;
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
        AccessibilityNodeInfo search = searchTabButton(root);
        if (search != null) {
            // La barra de abajo está visible: anotamos en qué sección estás.
            boolean inSearch = search.isSelected() || isSelectedDown(search, 2);
            igSection = inSearch ? "lupa" : (reelsTabSelected(root) ? "reels" : "otra");
            // La lupa comparte el mismo límite que Reels: todo el tiempo ahí cuenta.
            if (inSearch) return true;
        }
        boolean tab = reelsTabSelected(root);
        boolean player = reelsPlayerFullScreen(root);
        if (!tab && !player) return false;
        // Lo que abrís desde la lupa (fotos y carruseles incluidos) también cuenta.
        if (player && !tab && "lupa".equals(igSection)) return true;
        // En otros lados, los carruseles y fotos del reproductor no cuentan.
        return !currentItemIsStill(root);
    }

    /**
     * ¿Lo que se ve ahora en el reproductor es un carrusel o una foto (no un video)?
     * Se decide solo por los nombres internos de los elementos ("carousel",
     * "single_image", "photo"); no se mira la foto ni el video.
     */
    private static boolean currentItemIsStill(AccessibilityNodeInfo root) {
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        List<AccessibilityNodeInfo> items = root.findAccessibilityNodeInfosByViewId(IG + ":id/clips_media_component");
        if (items == null) return false;
        for (AccessibilityNodeInfo item : items) {
            if (item == null || !item.isVisibleToUser()) continue;
            Rect r = new Rect();
            item.getBoundsInScreen(r);
            if (!r.intersect(screen) || r.height() < screen.height() * 0.6f) continue;
            return hasStillMedia(item, 0);
        }
        return false;
    }

    private static boolean hasStillMedia(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 4) return false;
        String id = n.getViewIdResourceName();
        if (id != null && depth > 0) {
            String name = id.substring(id.indexOf('/') + 1);
            if (name.contains("carousel") || name.contains("single_image") || name.contains("photo")) return true;
        }
        for (int i = 0; i < n.getChildCount() && i < 12; i++) {
            if (hasStillMedia(n.getChild(i), depth + 1)) return true;
        }
        return false;
    }

    private boolean reelsTabSelected(AccessibilityNodeInfo root) {
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

    /**
     * Reels abiertos desde la lupa, el feed o un mensaje: Instagram los muestra en su
     * reproductor de Reels a pantalla completa. Se busca ese reproductor solo por su
     * nombre interno y su tamaño; no se mira su contenido.
     */
    private static boolean reelsPlayerFullScreen(AccessibilityNodeInfo root) {
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (screen.isEmpty()) return false;
        for (String id : IG_REELS_PLAYER_IDS) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                if (n == null || !n.isVisibleToUser()) continue;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (!r.intersect(screen)) continue;
                if (r.width() >= screen.width() * 0.9f && r.height() >= screen.height() * 0.7f) return true;
            }
        }
        return false;
    }

    /** X: pestaña "Para ti" seleccionada en el Inicio. */
    private boolean forYouVisible(AccessibilityNodeInfo root) {
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);

        // Barra de abajo: si está seleccionado otro botón que no sea Inicio (el primero),
        // estás en Buscar, Notificaciones, etc.: no cuenta.
        List<AccessibilityNodeInfo> bottom = findTabRow(root, screen, false);
        if (bottom != null) {
            int sel = selectedIndex(bottom);
            if (sel > 0) return false;
        }

        // Fila de pestañas de arriba del Inicio: la primera (izquierda) es "Para ti".
        // Se reconoce por su forma y por cuál está seleccionada, sin leer textos.
        List<AccessibilityNodeInfo> tabs = xTopTabs(root, screen);
        if (tabs != null) {
            int sel = selectedIndex(tabs);
            if (sel == 0) setForYou(true);
            else if (sel > 0) setForYou(false);
            return xForYou;
        }

        // Versiones anteriores de X: por el nombre de las pestañas.
        AccessibilityNodeInfo home = findExact(root, X_HOME_TAB);
        if (home != null && !isSelectedUp(home) && !isSelectedDown(home, 2) && hasSelectedSibling(home)) {
            return false;
        }
        AccessibilityNodeInfo forYou = findExact(root, X_FOR_YOU);
        AccessibilityNodeInfo following = findExact(root, X_FOLLOWING);
        boolean forYouSel = forYou != null && tabSelected(forYou);
        boolean followingSel = following != null && tabSelected(following);
        if (forYouSel && !followingSel) setForYou(true);
        else if (followingSel && !forYouSel) setForYou(false);
        if (forYou != null && following != null) return xForYou;

        // Las pestañas se ocultaron al scrollear: seguimos en lo último que vimos,
        // mientras no hayas abierto otra pantalla de X.
        return xForYou && (xActivity == null || xActivity.equals(xForYouActivity));
    }

    /**
     * Pestañas de arriba del Inicio de X. Solo se buscan junto al título del
     * encabezado ("home_navigation_bar_title"), nunca dentro del timeline: así una
     * tarjeta o galería de un tuit no se confunde con las pestañas.
     */
    private static List<AccessibilityNodeInfo> xTopTabs(AccessibilityNodeInfo root, Rect screen) {
        AccessibilityNodeInfo title = null;
        for (String id : new String[]{X + ":id/home_navigation_bar_title", "home_navigation_bar_title"}) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                if (n != null && n.isVisibleToUser()) { title = n; break; }
            }
            if (title != null) break;
        }
        if (title == null) return null;
        AccessibilityNodeInfo header = title.getParent();
        if (header == null) return null;
        return findTabRow(header, screen, true);
    }

    /**
     * Busca una fila de pestañas por su forma: varios botones uno al lado del otro,
     * a la misma altura, con uno solo seleccionado. Arriba (top=true): las pestañas
     * del Inicio de X, anchas. Abajo (top=false): la barra de navegación.
     * Devuelve las pestañas ordenadas de izquierda a derecha, o null.
     */
    private static List<AccessibilityNodeInfo> findTabRow(AccessibilityNodeInfo root, Rect screen, boolean top) {
        if (screen.isEmpty()) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen < 500) {
            AccessibilityNodeInfo n = queue.poll();
            seen++;
            if (n == null || !n.isVisibleToUser()) continue;
            int count = n.getChildCount();
            if (count >= 2) {
                List<AccessibilityNodeInfo> row = new ArrayList<>();
                int rowTop = Integer.MIN_VALUE;
                boolean ok = true;
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c == null || !c.isVisibleToUser()) continue;
                    Rect r = new Rect();
                    c.getBoundsInScreen(r);
                    boolean wide = top ? r.width() >= screen.width() * 0.25f : r.width() >= screen.width() * 0.12f;
                    boolean tall = r.height() >= 60 && r.height() <= screen.height() * 0.1f;
                    if (!wide || !tall) continue;  // p. ej. el botón de configuración al costado
                    if (rowTop == Integer.MIN_VALUE) rowTop = r.top;
                    else if (Math.abs(r.top - rowTop) > 12) continue;  // otra fila (p. ej. el subrayado)
                    row.add(c);
                }
                Rect nb = new Rect();
                n.getBoundsInScreen(nb);
                boolean inRegion = rowTop != Integer.MIN_VALUE && (top
                        ? rowTop < screen.height() * 0.25f
                        // La barra de abajo ocupa todo el ancho (las filas de botones de un tuit no).
                        : rowTop > screen.height() * 0.82f && nb.width() >= screen.width() * 0.95f);
                int needed = top ? 2 : 4;
                if (ok && inRegion && row.size() >= needed) {
                    int selected = 0;
                    for (AccessibilityNodeInfo c : row) if (c.isSelected() || isSelectedDown(c, 2)) selected++;
                    if (selected == 1) {
                        row.sort((a, b) -> {
                            Rect ra = new Rect(), rb = new Rect();
                            a.getBoundsInScreen(ra);
                            b.getBoundsInScreen(rb);
                            return Integer.compare(ra.left, rb.left);
                        });
                        return row;
                    }
                }
            }
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return null;
    }

    private static int selectedIndex(List<AccessibilityNodeInfo> row) {
        for (int i = 0; i < row.size(); i++) {
            AccessibilityNodeInfo c = row.get(i);
            if (c.isSelected() || isSelectedDown(c, 2)) return i;
        }
        return -1;
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
            if (matchesAny(text, X_FOR_YOU)) {
                setForYou(true);
                note("X: tocaste o se seleccionó Para ti");
            } else if (matchesAny(text, X_FOLLOWING)) {
                setForYou(false);
                note("X: tocaste o se seleccionó Siguiendo");
            }
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

        // En Instagram, si los intentos suaves no funcionan, vamos a la pantalla de inicio
        // del teléfono. En X nunca: solo se cambia de Para ti a Siguiendo.
        if (k == Kind.REELS && enforceStreak >= 4) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (k == Kind.FOR_YOU) {
            if (root != null) {
                Rect screen = new Rect();
                root.getBoundsInScreen(screen);
                List<AccessibilityNodeInfo> tabs = xTopTabs(root, screen);
                if (tabs != null && tabs.size() >= 2 && clickUp(tabs.get(1))) {
                    setForYou(false);
                    return;
                }
                AccessibilityNodeInfo following = findExact(root, X_FOLLOWING);
                if (following != null && clickUp(following)) {
                    setForYou(false);
                    return;
                }
                // Las pestañas están escondidas porque bajaste: subir un poco el timeline
                // hace que X las vuelva a mostrar; en el próximo intento se toca Siguiendo.
                AccessibilityNodeInfo list = findTimeline(root, screen);
                if (list != null && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return;
                // Si no, tocar "Inicio" (primer botón de abajo) también las vuelve a mostrar.
                List<AccessibilityNodeInfo> bottom = findTabRow(root, screen, false);
                if (bottom != null) clickUp(bottom.get(0));
            }
            // Nunca se cierra X: se vuelve a intentar en un segundo y medio.
        } else {
            if (root != null) {
                List<AccessibilityNodeInfo> homeTab = root.findAccessibilityNodeInfosByViewId(IG_HOME_TAB_ID);
                if (homeTab != null && !homeTab.isEmpty() && clickUp(homeTab.get(0))) return;
            }
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    /** La lista grande que se puede desplazar (el timeline de X). */
    private static AccessibilityNodeInfo findTimeline(AccessibilityNodeInfo root, Rect screen) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen < 300) {
            AccessibilityNodeInfo n = queue.poll();
            seen++;
            if (n == null || !n.isVisibleToUser()) continue;
            if (n.isScrollable()) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.height() >= screen.height() * 0.5f) return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return null;
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
