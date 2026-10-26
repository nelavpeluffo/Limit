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
    private Blocker searchBlocker;  // botón de la lupa (Instagram): abre el buscador
    private Cover cover;
    private Blocker backCatcher;   // zona invisible sobre la flecha de atrás del buscador
    private boolean holdingBack;
    private boolean exploreCheckQueued;
    private long lastBlockerUpdate;
    private boolean blockerUpdateQueued;
    private boolean keyboardSeenInSearch;   // en este buscador ya estuvo el teclado abierto
    private boolean leaving;   // saliendo del buscador hacia el Inicio, con el reloj puesto
    private int homeStable;   // revisiones seguidas con el Inicio ya puesto
    private long searchFlowUntil;
    private long exploreRedirectUntil;
    private boolean xCovered;
    private long xCoveredSince;
    private long lastExploreCheck;
    // Última sección de Instagram vista con la barra de abajo (lupa, reels u otra).
    private String igSection = "otra";

    private boolean ticking;
    private long lastTick;
    private long lastActive;
    private Kind prevKind = Kind.NONE;

    private long lastEnforce;
    private long lastQuickCheck;
    private long swipePendingUntil;
    // Timeline de cada pestaña de X (para reconocerlos con las pestañas escondidas).
    private AccessibilityNodeInfo xForYouList;
    private AccessibilityNodeInfo xFollowingList;
    private int swipeAttempt;
    // Aviso "Seguidos" después de pasarte de Para ti con las pestañas escondidas.
    private long noticeUntil;
    private static final long RED_MS = 2500;
    private static final long VIOLET_MS = 4000;
    private final Runnable noticeToViolet = () -> {
        if (SystemClock.elapsedRealtime() >= noticeUntil) return;
        overlay.showGlowing("Siguiendo", Overlay.VIOLET);
        // Los 4 s se cuentan desde que aparece la violeta (no desde la roja).
        noticeUntil = SystemClock.elapsedRealtime() + VIOLET_MS;
        handler.removeCallbacks(this.noticeEnd);
        handler.postDelayed(this.noticeEnd, VIOLET_MS);
    };
    private final Runnable noticeEnd = () -> {
        noticeUntil = 0;
        overlay.hide();
    };
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
        searchBlocker = new Blocker(this, R.drawable.ic_search, this::openInstagramSearch);
        cover = new Cover(this);
        backCatcher = new Blocker(this, 0, this::onBackArrowTapped);
        setKeyboardHidden(false);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (usage == null || e == null) return;
        CharSequence pkg = e.getPackageName();
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // Se abrió o se cerró una ventana (por ejemplo, el teclado).
            checkKeyboardClosed();
            return;
        }
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
        if (X.contentEquals(pkg)) {
            trackXEvent(e);
            quickCheckX();
        }
        if (IG.contentEquals(pkg) && leaving) {
            startTicking();   // saliendo hacia el Inicio: no hace falta revisar nada más
            return;
        }
        if (IG.contentEquals(pkg)) {
            if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) onInstagramClick(e);
            quickCheckExplore();
            checkKeyboardClosed();
        }
        if (IG.contentEquals(pkg) && e.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            onInstagramLongPress(e);
        }
        long nowB = SystemClock.elapsedRealtime();
        if (pkg != null && nowB - lastBlockerUpdate < 80) {
            // Muchos eventos seguidos: una sola revisión al final, para no trabar nada.
            if (!blockerUpdateQueued) {
                blockerUpdateQueued = true;
                handler.postDelayed(() -> {
                    blockerUpdateQueued = false;
                    lastBlockerUpdate = SystemClock.elapsedRealtime();
                    try {
                        AccessibilityNodeInfo r = getRootInActiveWindow();
                        if (r != null && r.getPackageName() != null) updateBlocker(r, r.getPackageName().toString());
                    } catch (Exception ignored) {
                    }
                }, 80);
            }
        } else if (pkg != null) {
            lastBlockerUpdate = nowB;
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
        backCatcher.hide();
        cover.hide();
        cover.disarm();
        if (leaving) { leaving = false; setKeyboardHidden(false); }
        xCovered = false;
        cancelNotice();
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

    /**
     * Con el límite de X cumplido, reaccionar apenas X avisa que cambió algo en
     * pantalla (sin esperar a la revisión de cada segundo).
     */
    private void quickCheckX() {
        if (usage.remaining(Kind.FOR_YOU) > 0) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastQuickCheck < 150) return;
        lastQuickCheck = now;
        Kind k = detect();
        if (k == Kind.FOR_YOU) enforce(Kind.FOR_YOU);
        else uncoverX();
    }

    /** Ya no estás en Para ti: sacar la pantalla del reloj. */
    private void uncoverX() {
        if (!xCovered) return;
        xCovered = false;
        cover.hide();
    }

    /**
     * Te pasamos de Para ti a Siguiendo: la pastillita roja de
     * "límite alcanzado" queda 2,5 s y después se vuelve violeta, con brillo, y dice
     * "Siguiendo" durante 4 s, como aviso del cambio de pestaña. Después desaparece.
     */
    private void startSwitchNotice() {
        handler.removeCallbacks(noticeToViolet);
        handler.removeCallbacks(noticeEnd);
        noticeUntil = SystemClock.elapsedRealtime() + RED_MS + VIOLET_MS;
        overlay.show(Kind.FOR_YOU.label + ": límite de hoy alcanzado", true);
        handler.postDelayed(noticeToViolet, RED_MS);  // roja 2,5 s, después violeta 4 s
    }

    private void cancelNotice() {
        noticeUntil = 0;
        handler.removeCallbacks(noticeToViolet);
        handler.removeCallbacks(noticeEnd);
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
        }
        // En X, con el límite cumplido, el límite sigue funcionando aunque se esté
        // grabando un diagnóstico (así queda registrado cómo te pasa a Siguiendo).
        boolean xLimitLive = X.equals(lastPkg) && usage.remaining(Kind.FOR_YOU) <= 0;
        if (Diag.recording() && lastPkg != null && !xLimitLive) {
            String what = k == Kind.NONE ? "nada" : k.label;
            overlay.show("Diagnóstico · detecto: " + what, false);
            prevKind = k;
            lastTick = now;
            handler.postDelayed(tickRunnable, TICK_MS);
            return;
        }

        if (xCovered && (k != Kind.FOR_YOU || now - xCoveredSince > 10_000)) {
            // Saliste de Para ti (o por las dudas, nunca más de 10 s tapado).
            uncoverX();
        }

        if (k != Kind.NONE) {
            lastActive = now;
            if (prevKind == k) usage.add(k, Math.min(now - lastTick, 2500));

            long remaining = usage.remaining(k);
            if (remaining <= 0) {
                // En X la pastillita roja (y después la violeta) la maneja el aviso de cambio.
                if (k == Kind.REELS) overlay.show(k.label + ": límite de hoy alcanzado", true);
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
            if (now >= noticeUntil) overlay.hide();
            else lastActive = now;
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
            cover.arm();   // el reloj queda listo para tapar la grilla al instante
            // Con un Reel o la sección Reels abierta, la barra de abajo no está: sin candados.
            if (reelsTabSelected(root) || reelsPlayerFullScreen(root)) {
                blocker.hide();
                searchBlocker.hide();
                backCatcher.hide();
                return;
            }
            String msg = "Reels está bloqueado hasta mañana";
            place(blocker, reelsTabButton(root), root, true, msg);
            place(searchBlocker, searchTabButton(root), root, true, msg);
            if (inSearchScreen(root)) place(backCatcher, searchBackArrow(root), root, false, "");
            else backCatcher.hide();
            return;
        }
        backCatcher.hide();
        searchBlocker.hide();
        if (root != null && pkg != null) cover.disarm();
        // En X no se pone candado: al cumplir el límite se pasa a Siguiendo.
        blocker.hide();
    }

    private static void place(Blocker b, AccessibilityNodeInfo tab, AccessibilityNodeInfo root,
                              boolean bottomBar, String msg) {
        Rect r = new Rect();
        if (tab != null && tab.isVisibleToUser()) tab.getBoundsInScreen(r);
        if (looksLikeTabButton(r, root, bottomBar)) b.show(r, msg);
        else b.hide();
    }

    /** ¿Se ve la grilla de publicaciones de la lupa (Explorar)? */
    private static boolean exploreGridVisible(AccessibilityNodeInfo root) {
        for (String name : new String[]{"grid_card_layout_container", "image_button", "image_preview", "play_count_logo"}) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/" + name);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                if (n != null && n.isVisibleToUser()) return true;
            }
        }
        return false;
    }

    /**
     * Con el límite cumplido, apenas aparece la grilla de la lupa (por ejemplo al volver
     * atrás desde el buscador) se tapa con la pantalla del reloj y se va al Inicio,
     * sin esperar a la revisión de cada segundo.
     */
    private void quickCheckExplore() {
        if (usage.remaining(Kind.REELS) > 0) return;
        long now = SystemClock.elapsedRealtime();
        if (now < searchFlowUntil || now < exploreRedirectUntil) return;
        if (now - lastExploreCheck < 50) {
            if (!exploreCheckQueued) {
                exploreCheckQueued = true;
                handler.postDelayed(() -> { exploreCheckQueued = false; quickCheckExplore(); }, 50);
            }
            return;
        }
        lastExploreCheck = now;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !IG.contentEquals(root.getPackageName())) return;
            if (searchTabSelected(root) && exploreGridVisible(root)) redirectExploreToHome(root);
        } catch (Exception ignored) {
        }
    }

    private void redirectExploreToHome(AccessibilityNodeInfo root) {
        long now = SystemClock.elapsedRealtime();
        if (now < exploreRedirectUntil) return;
        exploreRedirectUntil = now + 1200;
        note("Instagram: grilla de la lupa con el límite cumplido → reloj e Inicio");
        // Primero el reloj; recién cuando ya tapa la pantalla se toca Inicio.
        cover.show(() -> {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null || !clickHome(r)) performGlobalAction(GLOBAL_ACTION_BACK);
            // El reloj queda hasta confirmar que ya estás en el Inicio.
            homeStable = 0;
        handler.postDelayed(() -> hideCoverWhenHome(0), 150);
        });
    }

    /** Toca el botón Inicio de Instagram (aunque el teclado lo tape). */
    private static boolean clickHome(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> home = root.findAccessibilityNodeInfosByViewId(IG_HOME_TAB_ID);
        if (home == null) return false;
        for (AccessibilityNodeInfo h : home) if (h != null && clickUp(h)) return true;
        return false;
    }

    private static boolean homeSelected(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> home = root.findAccessibilityNodeInfosByViewId(IG_HOME_TAB_ID);
        if (home == null) return false;
        for (AccessibilityNodeInfo h : home) {
            if (h != null && h.isVisibleToUser() && (h.isSelected() || isSelectedDown(h, 2))) return true;
        }
        return false;
    }

    private static boolean searchFieldVisible(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/action_bar_search_edit_text");
        if (found == null) return false;
        for (AccessibilityNodeInfo n : found) if (n != null && n.isVisibleToUser()) return true;
        return false;
    }

    /**
     * Salir del buscador con el límite cumplido: primero el reloj y, con el reloj
     * ya en pantalla, directo al Inicio (sin pasar por la grilla de la lupa).
     */
    /**
     * Con el límite cumplido, en el buscador vacío: si se baja el teclado (el primer
     * deslizamiento de "atrás"), se toma como que querés salir y te lleva al Inicio
     * con el reloj, sin pasar por la grilla. Si escribiste algo, no hace nada, así
     * podés mirar los resultados.
     */
    private void checkKeyboardClosed() {
        if (usage == null || leaving || usage.remaining(Kind.REELS) > 0) {
            keyboardSeenInSearch = false;
            return;
        }
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence p = root == null ? null : root.getPackageName();
            if (p == null || !IG.contentEquals(p) || !inSearchScreen(root)) {
                keyboardSeenInSearch = false;
                return;
            }
            if (keyboardVisible()) {
                keyboardSeenInSearch = true;
                return;
            }
            if (keyboardSeenInSearch && SystemClock.elapsedRealtime() >= searchFlowUntil && searchFieldEmpty(root)) {
                keyboardSeenInSearch = false;
                note("Instagram: se bajó el teclado en el buscador vacío → reloj e Inicio");
                leaveSearchToHome();
            }
        } catch (Exception ignored) {
        }
    }

    private boolean keyboardVisible() {
        List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
        if (ws == null) return false;
        for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
            if (w != null && w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        }
        return false;
    }

    /** ¿El campo de búsqueda está vacío? (solo mira si muestra la pista, no lee lo escrito) */
    private static boolean searchFieldEmpty(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/action_bar_search_edit_text");
        if (found == null) return false;
        for (AccessibilityNodeInfo n : found) {
            if (n != null && n.isVisibleToUser()) return n.isShowingHintText();
        }
        return false;
    }

    private void leaveSearchToHome() {
        searchFlowUntil = 0;
        exploreRedirectUntil = SystemClock.elapsedRealtime() + 6000;   // este camino se encarga
        leaving = true;
        backCatcher.hide();
        homeStable = 0;
        // El teclado tapa la barra de abajo (y el botón Inicio): se esconde mientras está el reloj.
        setKeyboardHidden(true);
        cover.show(() -> leaveStep(0), 140);   // aparece con un fundido corto
    }

    /**
     * El Inicio ya está puesto: el reloj se queda un momento más para que termine de
     * dibujarse y después se desvanece suave, dejando ver el Inicio.
     */
    private void revealHome() {
        handler.postDelayed(() -> {
            cover.fadeOut(320);
            handler.postDelayed(() -> {
                exploreRedirectUntil = 0;
                leaving = false;
                setKeyboardHidden(false);
            }, 400);
        }, 350);
    }

    private void setKeyboardHidden(boolean hidden) {
        try {
            getSoftKeyboardController().setShowMode(hidden ? SHOW_MODE_HIDDEN : SHOW_MODE_AUTO);
        } catch (Exception ignored) {
        }
    }

    private static boolean homeTabVisible(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> home = root.findAccessibilityNodeInfosByViewId(IG_HOME_TAB_ID);
        if (home == null) return false;
        for (AccessibilityNodeInfo h : home) if (h != null && h.isVisibleToUser()) return true;
        return false;
    }

    private void leaveStep(int attempt) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence pkg = root == null ? null : root.getPackageName();
            if (pkg == null || !IG.contentEquals(pkg)) { finishLeave(0); return; }
            boolean grid = exploreGridVisible(root);
            boolean search = searchFieldVisible(root);
            if (attempt > 0 && homeSelected(root) && !grid && !search) {
                if (++homeStable >= 2) { revealHome(); return; }
            } else {
                homeStable = 0;
                if (search) {
                    // En el buscador no está la barra de abajo: primero se sale con la flecha
                    // de Instagram (debajo del reloj) y ahí aparece la barra con Inicio.
                    if (attempt % 4 == 0) {
                        AccessibilityNodeInfo arrow = searchBackArrow(root);
                        boolean ok = arrow != null && arrow.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        if (!ok || attempt >= 12) performGlobalAction(GLOBAL_ACTION_BACK);
                    }
                } else if (homeTabVisible(root) && attempt % 2 == 0) {
                    clickHome(root);
                }
            }
        } catch (Exception ignored) {
        }
        if (attempt < 35) handler.postDelayed(() -> leaveStep(attempt + 1), 100);
        else finishLeave(0);   // nunca queda tapado más de ~4 s
    }

    private void finishLeave(long delay) {
        handler.postDelayed(() -> {
            cover.hide();
            exploreRedirectUntil = 0;
            leaving = false;
            setKeyboardHidden(false);
        }, delay);
    }

    private void hideCoverWhenHome(int attempt) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence pkg = root == null ? null : root.getPackageName();
            if (pkg == null || !IG.contentEquals(pkg)) { cover.hide(); return; }
            List<AccessibilityNodeInfo> home = root.findAccessibilityNodeInfosByViewId(IG_HOME_TAB_ID);
            boolean homeSelected = false;
            if (home != null) for (AccessibilityNodeInfo h : home) {
                if (h != null && h.isVisibleToUser()) homeSelected |= h.isSelected() || isSelectedDown(h, 2);
            }
            if (homeSelected && !exploreGridVisible(root) && !searchFieldVisible(root)) {
                if (++homeStable >= 2) { revealHome(); return; }
            } else {
                homeStable = 0;
            }
            // Si a los ~1,2 s sigue en la lupa, volver a tocar Inicio.
            if (attempt == 8 && home != null && !home.isEmpty()) clickUp(home.get(0));
        } catch (Exception ignored) {
        }
        if (attempt < 30) handler.postDelayed(() -> hideCoverWhenHome(attempt + 1), 150);
        else cover.hide();   // nunca queda tapado más de ~5 s
    }

    /** ¿Estás en el buscador de Instagram (no en la grilla de la lupa)? */
    private static boolean inSearchScreen(AccessibilityNodeInfo root) {
        if (exploreGridVisible(root)) return false;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/action_bar_search_edit_text");
        if (found == null) return false;
        for (AccessibilityNodeInfo n : found) if (n != null && n.isVisibleToUser()) return true;
        return false;
    }

    /** Flecha de atrás (arriba a la izquierda) tocada en el buscador, con el límite cumplido. */
    private void onInstagramClick(AccessibilityEvent e) {
        if (usage.remaining(Kind.REELS) > 0) return;
        try {
            AccessibilityNodeInfo src = e.getSource();
            if (src == null) return;
            Rect r = new Rect();
            src.getBoundsInScreen(r);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            Rect screen = new Rect();
            root.getBoundsInScreen(screen);
            boolean topLeft = r.right < screen.width() * 0.25f && r.bottom < screen.height() * 0.15f;
            if (topLeft && inSearchScreen(root)) coverBackFromSearch();
        } catch (Exception ignored) {
        }
    }

    /**
     * Volviendo atrás desde el buscador con el límite cumplido: el reloj tapa la
     * pantalla antes de que aparezca la grilla, y después te lleva al Inicio.
     */
    private void coverBackFromSearch() {
        leaveSearchToHome();
    }

    /** Botón "atrás" del teléfono (o el gesto de atrás). */
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK) return false;
        if (event.getAction() == android.view.KeyEvent.ACTION_UP && holdingBack) {
            // Se retuvo el "atrás": se ejecuta recién cuando el reloj ya tapa la pantalla.
            holdingBack = false;
            leaveSearchToHome();
            return true;
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) note("botón atrás del teléfono: Limit lo recibió");
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && usage != null && usage.remaining(Kind.REELS) <= 0) {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                CharSequence pkg = root == null ? null : root.getPackageName();
                if (pkg != null && IG.contentEquals(pkg) && inSearchScreen(root)) {
                    note("Instagram: atrás en el buscador → reloj primero");
                    holdingBack = true;
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;   // la tecla sigue su camino normal
    }

    /** Flecha de atrás de Instagram (zona invisible encima): reloj primero, después atrás. */
    private void onBackArrowTapped() {
        note("Instagram: flecha atrás en el buscador → reloj e Inicio");
        leaveSearchToHome();
    }

    /** La flecha de atrás arriba a la izquierda del buscador de Instagram. */
    private static AccessibilityNodeInfo searchBackArrow(AccessibilityNodeInfo root) {
        // La flecha tiene nombre propio; tocar el contenedor de al lado no hace nada.
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(IG + ":id/action_bar_button_back");
        if (byId != null) for (AccessibilityNodeInfo b : byId) {
            if (b != null && b.isVisibleToUser()) return b;
        }
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        List<AccessibilityNodeInfo> field = root.findAccessibilityNodeInfosByViewId(IG + ":id/action_bar_search_edit_text");
        Rect fieldRect = new Rect();
        if (field != null) for (AccessibilityNodeInfo f : field) {
            if (f != null && f.isVisibleToUser()) { f.getBoundsInScreen(fieldRect); break; }
        }
        if (fieldRect.isEmpty()) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen < 250) {
            AccessibilityNodeInfo n = queue.poll();
            seen++;
            if (n == null || !n.isVisibleToUser()) continue;
            if (n.isClickable()) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                // A la izquierda del campo de búsqueda y a su misma altura.
                boolean leftOfField = r.right <= fieldRect.left + 4 && r.width() < screen.width() * 0.25f;
                boolean sameRow = r.centerY() >= fieldRect.top - 20 && r.centerY() <= fieldRect.bottom + 20;
                if (leftOfField && sameRow) return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return null;
    }

    /**
     * Botón violeta con lupa (límite cumplido): abre el buscador de Instagram directo,
     * con una cortina oscura un instante para que no se vea la grilla de publicaciones.
     */
    private void openInstagramSearch() {
        searchFlowUntil = SystemClock.elapsedRealtime() + 3000;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            // Si ya está el campo de búsqueda a la vista, solo tocarlo.
            if (clickSearchField(root)) {
                searchFlowUntil = SystemClock.elapsedRealtime() + 400;
                return;
            }
            cover.show();
            AccessibilityNodeInfo tab = searchTabButton(root);
            if (tab == null || !clickUp(tab)) {
                cover.hide();
                return;
            }
            handler.postDelayed(() -> focusSearchField(0), 250);
            handler.postDelayed(cover::hide, 2500);   // por las dudas, nunca queda tapado
        } catch (Exception e) {
            cover.hide();
        }
    }

    private void focusSearchField(int attempt) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && clickSearchField(root)) {
                handler.postDelayed(cover::hide, 350);
                // Listo el buscador: desde ya, volver a la grilla se vigila otra vez.
                searchFlowUntil = SystemClock.elapsedRealtime() + 400;
                return;
            }
        } catch (Exception ignored) {
        }
        if (attempt < 8) handler.postDelayed(() -> focusSearchField(attempt + 1), 150);
        else cover.hide();
    }

    /** Toca la barra "Buscar" de arriba de la lupa, que abre el buscador. */
    private static boolean clickSearchField(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/action_bar_search_edit_text");
        if (found == null) return false;
        for (AccessibilityNodeInfo n : found) {
            if (n != null && n.isVisibleToUser() && clickUp(n)) return true;
        }
        return false;
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
            if (inSearch) {
                // Yendo al buscador desde el botón violeta: no cuenta ni te saca.
                if (SystemClock.elapsedRealtime() < searchFlowUntil) {
                    igSection = "busqueda";
                    return false;
                }
                // La grilla de publicaciones de la lupa comparte el límite con Reels.
                // Buscar cuentas (sin la grilla a la vista) no cuenta.
                boolean grid = exploreGridVisible(root);
                igSection = grid ? "lupa" : "busqueda";
                return grid;
            }
            igSection = reelsTabSelected(root) ? "reels" : "otra";
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
        boolean inTimeline = findIdSuffix(root, "scaffold_home_tabbed", 120) != null;
        if (tabs != null) {
            int sel = selectedIndex(tabs);
            if (sel == 0) setForYou(true);
            else if (sel > 0) setForYou(false);
            // Recordar qué timeline es cada pestaña, para reconocerlos después
            // cuando las pestañas se escondan.
            if (sel >= 0 && inTimeline) {
                AccessibilityNodeInfo list = findVerticalList(root, screen);
                if (list != null) {
                    // Si los dos resultaran ser el mismo elemento no sirven para distinguir.
                    if (sel == 0) {
                        xForYouList = list;
                        if (list.equals(xFollowingList)) xFollowingList = null;
                    } else {
                        xFollowingList = list;
                        if (list.equals(xForYouList)) xForYouList = null;
                    }
                }
            }
            return xForYou;
        }

        // Pestañas escondidas (bajaste): las páginas de X (Para ti, Siguiendo…) están una
        // al lado de la otra. Si desde la página actual no se puede deslizar hacia la
        // izquierda, es la primera: Para ti. Funciona aunque hayas cambiado deslizando.
        if (inTimeline) {
            AccessibilityNodeInfo pager = findPager(root, screen);
            if (pager != null) {
                boolean[] lr = pagerDirections(pager);
                if (lr[1] && !lr[0]) { setForYou(true); return true; }
                if (lr[0]) { setForYou(false); return false; }
            }
        }

        // Si no: reconocer en cuál de los dos timelines estás,
        // aunque hayas pasado de uno a otro deslizando el dedo.
        if (inTimeline) {
            AccessibilityNodeInfo list = findVerticalList(root, screen);
            if (list != null) {
                if (xForYouList != null && list.equals(xForYouList)) {
                    if (!xForYou) note("X: reconocí el timeline de Para ti");
                    setForYou(true);
                    return true;
                }
                if (xFollowingList != null && list.equals(xFollowingList)) {
                    if (xForYou) note("X: reconocí el timeline de Siguiendo");
                    setForYou(false);
                    return false;
                }
            }
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
        // La X nueva no responde a la búsqueda directa por nombre interno, así que
        // se recorre la pantalla hasta encontrar el título del encabezado.
        AccessibilityNodeInfo title = findIdSuffix(root, "home_navigation_bar_title", 400);
        if (title == null) return null;
        AccessibilityNodeInfo header = title.getParent();
        if (header == null) return null;
        return findTabRow(header, screen, true);
    }

    /** Recorre la pantalla buscando un elemento visible cuyo nombre interno termine así. */
    private static AccessibilityNodeInfo findIdSuffix(AccessibilityNodeInfo root, String suffix, int maxNodes) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen < maxNodes) {
            AccessibilityNodeInfo n = queue.poll();
            seen++;
            if (n == null || !n.isVisibleToUser()) continue;
            String id = n.getViewIdResourceName();
            if (id != null && (id.equals(suffix) || id.endsWith("/" + suffix))) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return null;
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
        // En X se reintenta cada medio segundo; en Instagram, cada segundo y medio.
        long gap = k == Kind.FOR_YOU ? 500 : 1500;
        if (now - lastEnforce < gap) return;
        if (now - lastEnforce > 15_000) {
            enforceStreak = 0;
            if (k == Kind.REELS) toast("Ya usaste tus 15 minutos de " + k.label + " de hoy");
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
            // Pastillita roja al instante; a los 2,5 s pasa a la violeta "Siguiendo".
            if (now >= noticeUntil) startSwitchNotice();
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
                // Estás dentro de un tuit o un video que abriste desde Para ti: primero
                // volver al timeline; en el próximo intento (medio segundo) se pasa a Siguiendo.
                if (findIdSuffix(root, "scaffold_home_tabbed", 120) == null) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    return;
                }
                // Pestañas escondidas (bajaste): deslizar el dedo de derecha a izquierda
                // sobre el timeline, como harías vos para pasar a Siguiendo.
                if (now < swipePendingUntil) return;   // ya hay un deslizamiento en curso
                swipeToFollowing(root, screen);
            }
            // Nunca se cierra X: si hiciera falta, se vuelve a intentar en un segundo y medio.
        } else {
            if (root != null) {
                // En la grilla de la lupa: pantalla violeta con el reloj mientras te lleva al Inicio.
                if (searchTabSelected(root) && exploreGridVisible(root)) {
                    redirectExploreToHome(root);
                    return;
                }
                List<AccessibilityNodeInfo> homeTab = root.findAccessibilityNodeInfosByViewId(IG_HOME_TAB_ID);
                if (homeTab != null && !homeTab.isEmpty() && clickUp(homeTab.get(0))) return;
            }
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    /**
     * Simula un deslizamiento de derecha a izquierda (Para ti → Siguiendo). Después
     * se confirma que de verdad cambió: el timeline que se ve tiene que ser otro.
     * Si no cambió, se intenta a otra altura o se suben las pestañas y se toca Siguiendo.
     */
    private void swipeToFollowing(AccessibilityNodeInfo root, Rect screen) {
        final AccessibilityNodeInfo before = findVerticalList(root, screen);
        // Se alterna la altura del gesto por si a esa altura hay una galería de fotos.
        float[] heights = {0.55f, 0.35f, 0.75f};
        float y = screen.top + screen.height() * heights[swipeAttempt % heights.length];
        swipeAttempt++;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(screen.left + screen.width() * 0.88f, y);
        path.lineTo(screen.left + screen.width() * 0.12f, y);
        android.accessibilityservice.GestureDescription gesture =
                new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 180))
                        .build();
        swipePendingUntil = SystemClock.elapsedRealtime() + 1500;
        boolean sent;
        try {
            sent = dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            sent = false;
        }
        note("X: deslizamiento hacia Siguiendo " + (sent ? "enviado" : "no permitido"));
        if (!sent) {
            swipePendingUntil = 0;
            revealTabsAndClick(root, screen);
            return;
        }
        handler.postDelayed(() -> verifySwitch(before), 700);
    }

    private void verifySwitch(AccessibilityNodeInfo before) {
        swipePendingUntil = 0;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !X.contentEquals(root.getPackageName())) return;
            Rect screen = new Rect();
            root.getBoundsInScreen(screen);
            List<AccessibilityNodeInfo> tabs = xTopTabs(root, screen);
            if (tabs != null) {
                // Las pestañas se ven: ellas dicen dónde estás (lo resuelve la detección normal).
                if (selectedIndex(tabs) > 0) { setForYou(false); swipeAttempt = 0; }
                return;
            }
            AccessibilityNodeInfo pager = findPager(root, screen);
            if (pager != null && pagerDirections(pager)[0]) {
                // Ya se puede deslizar a la izquierda: no estás más en la primera página.
                setForYou(false);
                swipeAttempt = 0;
                note("X: confirmado (por las páginas), pasó a Siguiendo");
                return;
            }
            AccessibilityNodeInfo after = findVerticalList(root, screen);
            if (before != null && after != null && !after.equals(before)) {
                // El timeline que se ve es otro: ya estás en Siguiendo.
                xForYouList = before;
                xFollowingList = after;
                setForYou(false);
                swipeAttempt = 0;
                note("X: confirmado, pasó a Siguiendo");
            } else {
                note("X: el deslizamiento no cambió de pestaña");
                if (swipeAttempt >= 2) revealTabsAndClick(root, screen);
            }
        } catch (Exception ignored) {
        }
    }

    /** Plan B: subir el timeline para que X muestre las pestañas y tocar Siguiendo. */
    private void revealTabsAndClick(AccessibilityNodeInfo root, Rect screen) {
        AccessibilityNodeInfo list = findVerticalList(root, screen);
        if (list != null && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            handler.postDelayed(this::clickXFollowing, 350);
        }
    }

    /** El contenedor grande de las páginas de X (se desliza hacia los costados). */
    private static AccessibilityNodeInfo findPager(AccessibilityNodeInfo root, Rect screen) {
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
                boolean[] lr = pagerDirections(n);
                if ((lr[0] || lr[1]) && r.width() >= screen.width() * 0.9f && r.height() >= screen.height() * 0.5f) {
                    return n;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return null;
    }

    /** {¿se puede deslizar a la izquierda?, ¿a la derecha?} */
    private static boolean[] pagerDirections(AccessibilityNodeInfo n) {
        boolean left = false, right = false;
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
            int id = a.getId();
            // Solo "desplazar": X ofrece "página izquierda/derecha" siempre, en cualquier
            // página, así que eso no sirve para saber en cuál estás.
            if (id == android.R.id.accessibilityActionScrollLeft) left = true;
            if (id == android.R.id.accessibilityActionScrollRight) right = true;
        }
        return new boolean[]{left, right};
    }

    /** La lista del timeline (se desplaza hacia arriba/abajo, no hacia los costados). */
    private static AccessibilityNodeInfo findVerticalList(AccessibilityNodeInfo root, Rect screen) {
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
                boolean horizontal = false;
                for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
                    int id = a.getId();
                    if (id == android.R.id.accessibilityActionScrollLeft
                            || id == android.R.id.accessibilityActionScrollRight
                            || (android.os.Build.VERSION.SDK_INT >= 29
                                && (id == android.R.id.accessibilityActionPageLeft
                                    || id == android.R.id.accessibilityActionPageRight))) {
                        horizontal = true;
                    }
                }
                if (!horizontal && r.height() >= screen.height() * 0.5f) return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return null;
    }

    /** Toca la pestaña Siguiendo (la segunda de arriba) si está visible. */
    private void clickXFollowing() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !X.contentEquals(root.getPackageName())) return;
            Rect screen = new Rect();
            root.getBoundsInScreen(screen);
            List<AccessibilityNodeInfo> tabs = xTopTabs(root, screen);
            if (tabs != null && tabs.size() >= 2 && clickUp(tabs.get(1))) setForYou(false);
        } catch (Exception ignored) {
        }
    }

    /**
     * Las pestañas de X son páginas una al lado de la otra. Se busca el contenedor
     * que permite pasar de página hacia la derecha y se le pide pasar a la siguiente
     * (Para ti → Siguiendo).
     */
    private static boolean pageToFollowing(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen < 300) {
            AccessibilityNodeInfo n = queue.poll();
            seen++;
            if (n == null || !n.isVisibleToUser()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            Rect screen = new Rect();
            root.getBoundsInScreen(screen);
            // Solo el contenedor grande de las páginas (no una galería dentro de un tuit).
            boolean big = r.width() >= screen.width() * 0.9f && r.height() >= screen.height() * 0.5f;
            if (n.isScrollable() && big) {
                for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
                    int id = a.getId();
                    boolean right = id == android.R.id.accessibilityActionScrollRight
                            || (android.os.Build.VERSION.SDK_INT >= 29 && id == android.R.id.accessibilityActionPageRight);
                    if (right && n.performAction(id)) return true;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return false;
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
