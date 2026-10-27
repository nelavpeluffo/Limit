package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
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
    private Blocker profileReelsCatcher;   // zona invisible sobre la pestaña Reels de un perfil (y su flechita)
    private boolean holdingBack;
    private boolean exploreCheckQueued;
    private long lastBlockerUpdate;
    private boolean blockerUpdateQueued;
    private LockNotice lockNotice;
    private Overlay centerNotice;           // aviso en el medio: Instagram no puede reproducir el video
    private int dmOpenToken;                // cambia cada vez que se abre un Reel del chat
    // Reels de un chat, uno atrás del otro
    private SwipeCatcher dmCatcher;          // zona invisible sobre el video que reconoce el deslizamiento
    private boolean dmNavBusy;               // pasando de un Reel del chat a otro (con el reloj puesto)
    private boolean dmSuppressed;            // abriste comentarios, compartir…: la zona se aparta
    private boolean wasInDmPlayer;
    private boolean dmPlayerStable;         // mirando un Reel del chat, con la zona ya puesta
    private long lastDmCheck;
    private final Rect dmCurrentRect = new Rect();   // dónde estaba en el chat el Reel que estás viendo
    private AccessibilityNodeInfo dmCurNode;
    private long dmNavOpenedAt;
    private boolean dmOpenedByLimit;        // el próximo reproductor que aparezca lo abrió Limit (ya se sabe cuál es)
    private int dmPlayerSeen;               // veces seguidas que se vio abierto el Reel del chat
    private int dmSkips;
    private long dmNavStartedAt;                    // cosas abiertas que no eran Reels ni publicaciones
    private int dmNetOlder;                 // páginas que se movió el chat hacia lo más viejo (negativo: hacia lo nuevo)
    private boolean dmOrientLearned;
    private int dmApproachSteps;
    private boolean dmApproachDone;
    private int dmSearchDir;
    private boolean dmScrollRetried;
    private int dmNoScrollWaits;            // esperas a que Instagram cargue mensajes más viejos
    private int dmCurrentRow = -1;          // número de mensaje (orden en el chat) del que estás viendo
    private int dmRowOrient = 0;            // +1: los números crecen hacia abajo; -1: hacia arriba; 0: no se sabe               // páginas que se movió el chat buscando (para volver)
    private boolean dmOlderIsBackward = true;   // "hacia mensajes más viejos" = desplazar hacia atrás
    private final List<Rect> lastThreadBubbles = new ArrayList<>();
    private final List<Integer> lastThreadRows = new ArrayList<>();
    private final List<Boolean> lastThreadHidden = new ArrayList<>();
    private final Rect lastThreadList = new Rect();   // contorno violeta: Reel de un mensaje con el límite cumplido
    private boolean profileReelsNoticed;
    private long lastProfileSeen;
    private long lastProfileNotice;
    private long profileGoneSince;
    private long lastProfileSwitch;
    private long lastDmScrollBlock;
    private long lastScrollCheck;
    private long lastReelsTabAction;   // última vez que se tocó/eligió la pestaña Reels de un perfil
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
    private static final long RED_MS = 4000;   // todos los globos con mensaje: 4 s
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
        instance = this;
        paused = false;
        AppSettings.setPaused(this, false);   // la pausa desde la app ya no existe
        usage = new Usage(this);
        overlay = new Overlay(this);
        blocker = new Blocker(this, R.drawable.ic_lock,
                () -> notice("Reels está bloqueado hasta mañana", Overlay.VIOLET));
        searchBlocker = new Blocker(this, R.drawable.ic_search, this::openInstagramSearch);
        cover = new Cover(this);
        lockNotice = new LockNotice(this);
        centerNotice = Overlay.centered(this);
        dmCatcher = new SwipeCatcher(this, this::onDmSwipe);
        backCatcher = new Blocker(this, 0, this::onBackArrowTapped);
        profileReelsCatcher = new Blocker(this, 0, this::onProfileReelsCatcherTap);
        profileReelsCatcher.setRipple(true);   // al tocarla, una onda violeta: "tocaste, pero no hace nada"
        setKeyboardHidden(false);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (usage == null || e == null || paused) return;   // en pausa: no se mira nada
        CharSequence pkg = e.getPackageName();
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // Se abrió o se cerró una ventana (por ejemplo, el teclado o un menú).
            checkKeyboardClosed();
            checkProfileReels();
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
        if (dmPlayerStable && IG.contentEquals(pkg)
                && (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    || e.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            // Mirando un Reel del chat: el video avisa cambios todo el tiempo (la barra de
            // avance). Revisar la pantalla en cada aviso le quita tiempo a Instagram y el video
            // se traba, así que mientras tanto se revisa como mucho cada 0,8 s.
            long nowG = SystemClock.elapsedRealtime();
            if (nowG - lastDmCheck < 800) return;
            lastDmCheck = nowG;
            try {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r != null) updateDmReel(r);
            } catch (Exception ignored) {
            }
            if (dmPlayerStable) return;
        }
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Los deslizamientos solo importan en un Reel abierto desde un mensaje.
            long nowS = SystemClock.elapsedRealtime();
            if (IG.contentEquals(pkg) && nowS - lastScrollCheck > 150) {
                lastScrollCheck = nowS;
                try {
                    AccessibilityNodeInfo r = getRootInActiveWindow();
                    if (r != null && reelsPlayerFullScreen(r)
                            && ("mensajes".equals(igSection) || isDmReel(r))) blockDmReelScroll(r);
                } catch (Exception ignored) {
                }
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
        if (IG.contentEquals(pkg)) checkStoryAd();
        if (IG.contentEquals(pkg)) {
            if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                dmPlayerStable = false;   // tocaste algo: revisar de nuevo enseguida
                onDmReelButton(e);
                onThreadClick(e);
                onInstagramClick(e);
            }
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
        profileReelsCatcher.hide();
        cover.hide();
        cover.disarm();
        lockNotice.hide();
        centerNotice.hide();
        storyAdActive = false;
        dmCatcher.hide();
        dmPlayerStable = false;
        profileReelsNoticed = false;
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
        notice("Reels está bloqueado hasta mañana", Overlay.VIOLET);
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
     * "límite alcanzado" queda 4 s y después se vuelve violeta, con brillo, y dice
     * "Siguiendo" durante 4 s, como aviso del cambio de pestaña. Después desaparece.
     */
    private void startSwitchNotice() {
        handler.removeCallbacks(noticeToViolet);
        handler.removeCallbacks(noticeEnd);
        noticeUntil = SystemClock.elapsedRealtime() + RED_MS + VIOLET_MS;
        overlay.show(Kind.FOR_YOU.label + ": límite de hoy alcanzado", true);
        handler.postDelayed(noticeToViolet, RED_MS);  // roja 4 s, después violeta 4 s
    }

    /** Globo con un mensaje: queda 4 s a la vista, para que dé tiempo a leerlo. */
    private void notice(String text, int color) {
        handler.removeCallbacks(noticeToViolet);
        handler.removeCallbacks(noticeEnd);
        overlay.show(text, color);
        noticeUntil = SystemClock.elapsedRealtime() + NOTICE_MS;
        handler.postDelayed(noticeEnd, NOTICE_MS);
    }

    private static final long NOTICE_MS = 4000;

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
        if (instance == this) instance = null;
        stopTicking();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        stopTicking();
        super.onDestroy();
    }

    /** El servicio funcionando ahora (para el botón "Desactivar" de la app), o null. */
    static volatile LimitService instance;

    /** Pausa (o reanuda) Limit desde la app: en pausa no mira nada ni pone nada en pantalla. */
    void setPaused(boolean on) {
        paused = on;
        if (!on) return;
        try {
            blocker.hide();
            searchBlocker.hide();
            backCatcher.hide();
            profileReelsCatcher.hide();
            dmCatcher.hide();
            dmPlayerStable = false;
            dmNavBusy = false;
            lockNotice.hide();
            centerNotice.hide();
            cover.hide();
            cover.disarm();
            cancelNotice();
            overlay.hide();
            setKeyboardHidden(false);
            stopTicking();
        } catch (Exception ignored) {
        }
    }

    private volatile boolean paused;

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
        if (!ticking || paused) return;
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
                // En la lupa no: ahí ya no se llega a ver nada (el reloj te lleva al Inicio).
                if (k == Kind.REELS && !"lupa".equals(igSection) && !"busqueda".equals(igSection)
                        && now >= noticeUntil) {
                    notice(k.label + ": límite de hoy alcanzado", Overlay.RED);
                }
                enforce(k);
            } else {
                enforceStreak = 0;
                if (remaining <= 60_000 && usage.markWarned(k)) {
                    notice("Te queda 1 minuto de " + k.label, Overlay.RED);
                }
                if (now < noticeUntil) {
                    // Hay un mensaje a la vista: el contador espera a que termine.
                } else if (AppSettings.overlayEnabled(this)) {
                    overlay.show(k.label + " · quedan " + Usage.format(remaining), remaining <= 60_000);
                } else {
                    overlay.hide();
                }
            }
        } else {
            // Ya no estás en Reels: la salida funcionó. Así, abrir varios Reels seguidos
            // (por ejemplo, de mensajes) no se toma como que Limit no pudo sacarte.
            enforceStreak = 0;
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
        if (root != null && IG.equals(pkg)) updateDmReel(root);
        else dmCatcher.hide();
        if (root != null && IG.equals(pkg) && usage.remaining(Kind.REELS) <= 0) {
            cover.arm();   // el reloj queda listo para tapar la grilla al instante
            // Con un Reel o la sección Reels abierta, la barra de abajo no está: sin candados.
            if (reelsTabSelected(root) || reelsPlayerFullScreen(root)) {
                blocker.hide();
                searchBlocker.hide();
                backCatcher.hide();
                profileReelsCatcher.hide();
                if (!reelsTabSelected(root) && isDmReel(root)) igSection = "mensajes";
                return;
            }
                // Perfiles: la pestaña Reels te devuelve a la grilla del perfil (con aviso).
            if (!checkProfileReels()) {
                // Resultados de búsqueda: la pestaña "Reels" te pasa a la primera pestaña.
                AccessibilityNodeInfo searchReels = profileReelsTab(root);
                if (searchReels != null && tabSelected(searchReels)) switchProfileToGrid(searchReels);
            }
            String msg = "Reels está bloqueado hasta mañana";
            place(blocker, reelsTabButton(root), root, true, msg);
            place(searchBlocker, searchTabButton(root), root, true, msg);
            if (inSearchScreen(root)) place(backCatcher, searchBackArrow(root), root, false, "");
            else backCatcher.hide();
            return;
        }
        backCatcher.hide();
        profileReelsCatcher.hide();
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
        profileReelsCatcher.hide();
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
        // Un perfil abierto desde el buscador: el buscador sigue debajo, pero estás en el perfil
        // (su flecha de atrás tiene que volver a los resultados, no al Inicio).
        if (hasId(root, "profile_tab_layout") || hasId(root, "profile_tabs_container")) return false;
        boolean field = false;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/action_bar_search_edit_text");
        if (found != null) for (AccessibilityNodeInfo n : found) if (n != null && n.isVisibleToUser()) field = true;
        if (!field) return false;
        // El buscador tiene la flecha de atrás al lado del campo (la grilla de la lupa no).
        // Así se reconoce aunque los resultados muestren publicaciones en forma de grilla.
        if (visibleBounds(root, "action_bar_button_back") != null) return true;
        return !exploreGridVisible(root);
    }

    /** Flecha de atrás (arriba a la izquierda) tocada en el buscador, con el límite cumplido. */
    private void onInstagramClick(AccessibilityEvent e) {
        if (usage.remaining(Kind.REELS) > 0) return;
        if (onProfileReelsClick(e)) return;
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
        if (paused || event.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK) return false;
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
        return null;   // solo el botón de la barra de abajo (no la pestaña Reels de un perfil)
    }

    /**
     * Estás en la pestaña Reels de un perfil: se toca la primera pestaña de esa misma
     * fila (la grilla de publicaciones), sin salir del perfil.
     */
    private void switchProfileToGrid(AccessibilityNodeInfo reelsTab) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastProfileSwitch < 600) return;
        lastProfileSwitch = now;
        AccessibilityNodeInfo tab = clickableUp(reelsTab);
        if (tab == null) tab = reelsTab;
        Rect tr = new Rect();
        tab.getBoundsInScreen(tr);
        // Subir hasta la fila que tiene las pestañas una al lado de la otra.
        AccessibilityNodeInfo row = tab.getParent();
        for (int level = 0; row != null && level < 4; level++) {
            AccessibilityNodeInfo best = null;
            int bestLeft = tr.left;
            for (int i = 0; i < row.getChildCount(); i++) {
                AccessibilityNodeInfo c = row.getChild(i);
                if (c == null || !c.isVisibleToUser()) continue;
                Rect r = new Rect();
                c.getBoundsInScreen(r);
                boolean sameRow = Math.abs(r.centerY() - tr.centerY()) < Math.max(tr.height(), 40)
                        && r.height() < tr.height() * 2 + 40;
                if (sameRow && r.left < bestLeft) {
                    best = c;
                    bestLeft = r.left;
                }
            }
            if (best != null) {
                note("Instagram: pestaña Reels de un perfil → grilla del perfil");
                if (!clickUp(best)) clickDown(best);
                return;
            }
            row = row.getParent();
        }
    }

    /**
     * Tocaste una pestaña de un perfil (la de Reels o la flechita de al lado, que abre
     * el menú "Más recientes / Más vistos"): se revisa enseguida, varias veces, para
     * cerrar ese menú y volver a la grilla.
     */
    private boolean onProfileReelsClick(AccessibilityEvent e) {
        AccessibilityNodeInfo src;
        try {
            src = e.getSource();
        } catch (Exception ex) {
            return false;
        }
        String id = src == null ? null : src.getViewIdResourceName();
        if (id == null || !id.contains(":id/profile_tab")) return false;
        lastReelsTabAction = SystemClock.elapsedRealtime();
        for (long delay : new long[]{60, 200, 450, 800}) {
            handler.postDelayed(this::checkProfileReels, delay);
        }
        return true;
    }

    /**
     * Con el límite cumplido, en un perfil: avisa una vez al entrar, y si quedó elegida
     * la pestaña Reels (o se abrió su menú) cierra el menú y vuelve a la grilla.
     * Se reconoce todo por los nombres internos de las pestañas, sin mirar el perfil.
     * Devuelve true si hay un perfil a la vista.
     */
    private boolean checkProfileReels() {
        if (usage == null || leaving || usage.remaining(Kind.REELS) > 0) return false;
        long now = SystemClock.elapsedRealtime();
        try {
            AccessibilityNodeInfo main = instagramMainRoot();
            List<AccessibilityNodeInfo> tabs = main == null ? null : profileTabs(main);
            if (tabs == null) {
                profileReelsCatcher.hide();
                if (profileReelsNoticed) {
                    // Recién cuando saliste del perfil (unos segundos sin verlo) puede volver a avisar.
                    if (profileGoneSince == 0) profileGoneSince = now;
                    else if (now - profileGoneSince > 3000) profileReelsNoticed = false;
                }
                return false;
            }
            lastProfileSeen = now;
            profileGoneSince = 0;
            int reels = reelsTabIndex(tabs);
            if (reels <= 0) {   // perfil sin pestaña Reels
                profileReelsCatcher.hide();
                return true;
            }
            // Encima de la pestaña Reels (y su flechita), una zona invisible: al tocarla se
            // va directo a la grilla, sin que Instagram llegue a abrir Reels ni su menú.
            if (main.equals(getRootInActiveWindow())) place(profileReelsCatcher, tabs.get(reels), main, false, "");
            else profileReelsCatcher.hide();

            // Al entrar al perfil: una vez por perfil, y nunca más seguido que cada 20 s.
            if (!profileReelsNoticed && now - lastProfileNotice > 20_000) {
                profileReelsNoticed = true;
                lastProfileNotice = now;
                lockNotice.show();
            }

            boolean reelsSelected = selectedDeep(tabs.get(reels));
            AccessibilityNodeInfo active = getRootInActiveWindow();
            boolean menuOpen = active != null && !active.equals(main) && hasId(active, "context_menu_options_list");
            if (menuOpen && (reelsSelected || now - lastReelsTabAction < 3000)) {
                // Se abrió el menú de la pestaña Reels: cerrarlo y seguir.
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastReelsTabAction = now;
                handler.postDelayed(this::checkProfileReels, 220);
                return true;
            }
            if (reelsSelected && now - lastProfileSwitch > 450) {
                lastProfileSwitch = now;
                lastReelsTabAction = now;
                note("Instagram: pestaña Reels de un perfil → grilla del perfil");
                if (!clickDown(tabs.get(0))) clickUp(tabs.get(0));
                if (now - lastProfileNotice > 4000) {
                    lastProfileNotice = now;
                    lockNotice.show();
                }
                handler.postDelayed(this::checkProfileReels, 350);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Tocaste la pestaña Reels de un perfil (o su flechita): directo a la grilla, con aviso. */
    private void onProfileReelsCatcherTap() {
        long now = SystemClock.elapsedRealtime();
        try {
            AccessibilityNodeInfo main = instagramMainRoot();
            List<AccessibilityNodeInfo> tabs = main == null ? null : profileTabs(main);
            if (tabs != null && !selectedDeep(tabs.get(0))) {
                if (!clickDown(tabs.get(0))) clickUp(tabs.get(0));
            }
        } catch (Exception ignored) {
        }
        note("Instagram: tocaste la pestaña Reels de un perfil → grilla del perfil");
        lastReelsTabAction = now;
        if (now - lastProfileNotice > 4000) {
            lastProfileNotice = now;
            profileReelsNoticed = true;
            lockNotice.show();
        }
    }

    /** La ventana principal de Instagram (no un menú encima). */
    private AccessibilityNodeInfo instagramMainRoot() {
        List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
        AccessibilityNodeInfo best = null;
        int bestArea = 0;
        if (ws != null) for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
            if (w == null || w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            CharSequence p = r == null ? null : r.getPackageName();
            if (p == null || !IG.contentEquals(p)) continue;
            Rect b = new Rect();
            w.getBoundsInScreen(b);
            int area = b.width() * b.height();
            if (area > bestArea) {
                bestArea = area;
                best = r;
            }
        }
        return best != null ? best : getRootInActiveWindow();
    }

    /** Las pestañas de un perfil (grilla, Reels, …), de izquierda a derecha. */
    private static List<AccessibilityNodeInfo> profileTabs(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> layouts = root.findAccessibilityNodeInfosByViewId(IG + ":id/profile_tab_layout");
        if (layouts == null) return null;
        for (AccessibilityNodeInfo layout : layouts) {
            if (layout == null || !layout.isVisibleToUser() || layout.getChildCount() == 0) continue;
            AccessibilityNodeInfo row = layout.getChild(0);
            if (row == null) continue;
            List<AccessibilityNodeInfo> tabs = new ArrayList<>();
            for (int i = 0; i < row.getChildCount(); i++) {
                AccessibilityNodeInfo t = row.getChild(i);
                if (t != null) tabs.add(t);
            }
            if (tabs.size() >= 2) return tabs;
        }
        return null;
    }

    /** Cuál es la pestaña Reels: la que tiene la flechita del menú, o la que se llama "Reels". */
    private static int reelsTabIndex(List<AccessibilityNodeInfo> tabs) {
        for (int i = 0; i < tabs.size(); i++) if (hasIdDown(tabs.get(i), "profile_tab_icon_dropdown", 0)) return i;
        for (int i = 0; i < tabs.size(); i++) if (hasReelsLabel(tabs.get(i), 0)) return i;
        return -1;
    }

    private static boolean hasReelsLabel(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 3) return false;
        if (startsWithLabel(n.getContentDescription(), IG_REELS_LABEL[0])
                || startsWithLabel(n.getText(), IG_REELS_LABEL[0])) return true;
        for (int i = 0; i < n.getChildCount() && i < 6; i++) {
            if (hasReelsLabel(n.getChild(i), depth + 1)) return true;
        }
        return false;
    }

    private static boolean hasIdDown(AccessibilityNodeInfo n, String name, int depth) {
        if (n == null || depth > 3) return false;
        String id = n.getViewIdResourceName();
        if (id != null && id.endsWith(":id/" + name)) return true;
        for (int i = 0; i < n.getChildCount() && i < 6; i++) {
            if (hasIdDown(n.getChild(i), name, depth + 1)) return true;
        }
        return false;
    }

    private static boolean selectedDeep(AccessibilityNodeInfo n) {
        return n.isSelected() || isSelectedDown(n, 3);
    }

    private static boolean hasId(AccessibilityNodeInfo root, String name) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/" + name);
        return found != null && !found.isEmpty();
    }

    /** ¿Es una pestaña dentro de una fila de al menos 3 pestañas parecidas una al lado de la otra? */
    private static boolean inTabRow(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo tab = clickableUp(n);
        if (tab == null) tab = n;
        Rect tr = new Rect();
        tab.getBoundsInScreen(tr);
        AccessibilityNodeInfo row = tab.getParent();
        for (int level = 0; row != null && level < 3; level++) {
            int same = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                AccessibilityNodeInfo c = row.getChild(i);
                if (c == null || !c.isVisibleToUser()) continue;
                Rect r = new Rect();
                c.getBoundsInScreen(r);
                if (Math.abs(r.centerY() - tr.centerY()) < Math.max(tr.height() / 2, 20)
                        && Math.abs(r.height() - tr.height()) < Math.max(tr.height() / 2, 20)) same++;
            }
            if (same >= 3) return true;
            row = row.getParent();
        }
        return false;
    }

    private static boolean clickDown(AccessibilityNodeInfo n) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(n);
        int seen = 0;
        while (!queue.isEmpty() && seen < 20) {
            AccessibilityNodeInfo c = queue.poll();
            seen++;
            if (c == null) continue;
            if (c.isClickable()) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            for (int i = 0; i < c.getChildCount(); i++) queue.add(c.getChild(i));
        }
        return false;
    }

    /**
     * La pestaña "Reels" de un perfil (no el botón de la barra de abajo). Se busca por
     * su nombre de pestaña, como el resto; no se mira nada del perfil.
     */
    private static AccessibilityNodeInfo profileReelsTab(AccessibilityNodeInfo root) {
        Rect bar = new Rect();
        List<AccessibilityNodeInfo> tabBar = root.findAccessibilityNodeInfosByViewId(IG + ":id/tab_bar");
        if (tabBar != null) for (AccessibilityNodeInfo t : tabBar) {
            if (t != null && t.isVisibleToUser()) { t.getBoundsInScreen(bar); break; }
        }
        for (String label : IG_REELS_LABEL) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                if (n == null || !n.isVisibleToUser()) continue;
                if (!startsWithLabel(n.getContentDescription(), label) && !startsWithLabel(n.getText(), label)) continue;
                String id = n.getViewIdResourceName();
                if (id != null && id.endsWith(":id/clips_tab")) continue;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (!bar.isEmpty() && Rect.intersects(r, bar)) continue;
                if (!inTabRow(n)) continue;   // un título "Reels" suelto (en el feed, etc.) no es la pestaña
                return n;
            }
        }
        return null;
    }

    /**
     * Instagram: ¿está seleccionado el botón de la pestaña Reels (barra de abajo)?
     * Se busca ese botón por su id o por su nombre "Reels"; no se mira nada más.
     */
    private boolean reelsVisible(AccessibilityNodeInfo root) {
        // Mirando un Reel del chat (ya reconocido): no cuenta, y no hace falta revisar más.
        if (dmPlayerStable) {
            igSection = "mensajes";
            return false;
        }
        // En la pestaña Reels de un perfil no se te saca del perfil: se pasa a su grilla.
        List<AccessibilityNodeInfo> pTabs = profileTabs(root);
        if (pTabs != null) {
            int ri = reelsTabIndex(pTabs);
            if (ri > 0 && selectedDeep(pTabs.get(ri))) {
                igSection = "perfil";
                return false;
            }
        } else {
            AccessibilityNodeInfo searchReels = profileReelsTab(root);
            if (searchReels != null && tabSelected(searchReels)) return false;
        }
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
            if (reelsTabSelected(root)) igSection = "reels";
            else if (directTabSelected(root)) igSection = "mensajes";
            else igSection = "otra";
        }
        boolean tab = reelsTabSelected(root);
        boolean player = reelsPlayerFullScreen(root);
        if (!tab && !player) return false;
        // Un Reel que te mandaron por mensaje: se puede ver (no cuenta ni te saca),
        // pero no seguir deslizando a otros.
        if (player && !tab && ("mensajes".equals(igSection) || isDmReel(root))) {
            igSection = "mensajes";
            blockDmReelScroll(root);
            return false;
        }
        // Lo que abrís desde la lupa (fotos y carruseles incluidos) también cuenta.
        if (player && !tab && "lupa".equals(igSection)) return true;
        // En otros lados, los carruseles y fotos del reproductor no cuentan.
        return !currentItemIsStill(root);
    }

    /**
     * ¿Es un Reel abierto desde un chat? Instagram le pone abajo la barra para responder
     * al mensaje y arriba quién te lo mandó (se reconoce por sus nombres internos).
     */
    private static boolean isDmReel(AccessibilityNodeInfo root) {
        for (String name : new String[]{"reel_viewer_message_composer", "sender_username_or_fullname", "reply_bar_container"}) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/" + name);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) if (n != null && n.isVisibleToUser()) return true;
        }
        return false;
    }

    private static boolean directTabSelected(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/direct_tab");
        if (found == null) return false;
        for (AccessibilityNodeInfo n : found) {
            if (n != null && n.isVisibleToUser() && (n.isSelected() || isSelectedDown(n, 2))) return true;
        }
        return false;
    }

    /**
     * Reel abierto desde un mensaje: si deslizaste a otro Reel (el reproductor ya se
     * puede mover "hacia atrás"), se vuelve al que te mandaron. Si no se puede, se
     * cierra el reproductor y volvés al chat.
     */
    private void blockDmReelScroll(AccessibilityNodeInfo root) {
        long now = SystemClock.elapsedRealtime();
        if (dmNavBusy || now - lastDmScrollBlock < 500) return;
        List<AccessibilityNodeInfo> pagers = root.findAccessibilityNodeInfosByViewId(IG + ":id/clips_viewer_view_pager");
        if (pagers == null) return;
        for (AccessibilityNodeInfo pager : pagers) {
            if (pager == null || !pager.isVisibleToUser()) continue;
            if (!canScrollBack(pager)) return;   // estás en el Reel que te mandaron
            lastDmScrollBlock = now;
            // Instagram pasó a un Reel recomendado: en vez de eso, el siguiente Reel del chat.
            note("Instagram: deslizaste en un Reel de un mensaje (Instagram) → siguiente del chat");
            dmNavigate(+1);
            return;
        }
    }

    // ------------------------------------------------- Reels del chat, uno atrás del otro

    /** En cada cambio de Instagram: poner o sacar la zona que reconoce el deslizamiento. */
    private void updateDmReel(AccessibilityNodeInfo root) {
        if (dmNavBusy) {
            dmCatcher.hide();
            dmPlayerStable = false;
            return;
        }
        boolean player = reelsPlayerFullScreen(root) && isDmReel(root);
        dmPlayerStable = false;
        if (!player) {
            dmCatcher.hide();
            if (wasInDmPlayer) centerNotice.hide();
            dmSuppressed = false;
            wasInDmPlayer = false;
            if (threadVisible(root)) rememberThreadBubbles(root);
            return;
        }
        if (!wasInDmPlayer) {
            wasInDmPlayer = true;
            onDmPlayerOpened();
            // A los 5 s, si es un video y no arrancó (Instagram no muestra la barra de avance), avisar.
            final int token = ++dmOpenToken;
            handler.postDelayed(() -> dmCheckPlaying(token), 5000);
        }
        if (dmSuppressed || keyboardVisible()) {
            dmCatcher.hide();
            return;
        }
        Rect area = dmSwipeArea(root);
        if (area != null) {
            dmCatcher.show(area);
            dmPlayerStable = true;
            lastDmCheck = SystemClock.elapsedRealtime();
        } else {
            dmCatcher.hide();
        }
    }

    // --------------------------------------------- publicidad en las historias de Instagram

    private boolean storyAdActive;      // hay una publicidad en pantalla y se está salteando
    private int storyAdSwipes;
    private long lastStoryAdCheck;
    private boolean storyAdCheckQueued;
    private boolean storyAdGaveUp;      // no se pudo saltear: no insistir hasta que se vaya

    /**
     * Historias: si la que se ve es una publicidad (Instagram le pone la etiqueta de
     * "Publicidad" abajo y un botón de mensaje propio, que se reconocen por su nombre
     * interno), se tapa con la pantalla oscura del reloj y se pasa a la historia siguiente.
     */
    private void checkStoryAd() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastStoryAdCheck < 60) {
            if (!storyAdCheckQueued) {
                storyAdCheckQueued = true;
                handler.postDelayed(() -> { storyAdCheckQueued = false; checkStoryAd(); }, 60);
            }
            return;
        }
        lastStoryAdCheck = now;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence p = root == null ? null : root.getPackageName();
            if (p == null || !IG.contentEquals(p)) return;
            boolean ad = isStoryAd(root);
            if (!ad) storyAdGaveUp = false;
            if (ad && !storyAdActive && !storyAdGaveUp) {
                storyAdActive = true;
                storyAdSwipes = 0;
                note("Instagram: publicidad en las historias → la salteo");
                skipStoryAd();   // sin esperar al reloj: el reloj no recibe toques, el deslizamiento pasa
            } else if (!ad && storyAdActive && storyAdSwipes > 0) {
                endStoryAd();
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isStoryAd(AccessibilityNodeInfo root) {
        if (visibleBounds(root, "reel_viewer_root") == null) return false;
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        for (String name : new String[]{"reel_item_sponsored_label_footer_pill", "sponsored_message_composer_container"}) {
            Rect r = visibleBounds(root, name);
            // En la historia que se ve ahora (no en la de al lado, a mitad de un deslizamiento).
            if (r != null && r.left < screen.width() * 0.6f && r.right > 0) return true;
        }
        return false;
    }

    /**
     * Desliza hacia la izquierda a lo ancho de toda la historia: Instagram gira el cubo
     * siguiendo al dedo, y la pantalla del reloj (del mismo tamaño y en el mismo lugar que
     * la historia) gira igual y al mismo ritmo, como si fuera la cara de la publicidad.
     */
    private void skipStoryAd() {
        if (!storyAdActive) return;
        Rect face = null;
        Rect screen = new Rect();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                root.getBoundsInScreen(screen);
                face = visibleBounds(root, "reel_viewer_root");
            }
        } catch (Exception ignored) {
        }
        if (screen.isEmpty()) screen.set(0, 0, 1080, 2400);
        if (face == null || face.width() < screen.width() / 2) face = new Rect(screen);
        cover.show(null, 0, Cover.DARK, face);
        float y = face.exactCenterY();
        Path path = new Path();
        path.moveTo(face.left + face.width() * 0.98f, y);
        path.lineTo(face.left + face.width() * 0.02f, y);
        final long ms = 300;
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, ms)).build(), null, null);
        storyAdSwipes++;
        cover.cubeOut(ms);
        handler.removeCallbacks(storyAdFollowUpRunnable);
        handler.postDelayed(storyAdFollowUpRunnable, ms + 250);
    }

    private final Runnable storyAdFollowUpRunnable = this::storyAdFollowUp;

    private void storyAdFollowUp() {
        if (!storyAdActive) return;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && isStoryAd(root) && storyAdSwipes < 4) {
                skipStoryAd();   // otra publicidad (o no pasó): deslizar de nuevo
                return;
            }
            if (root != null && isStoryAd(root)) {
                storyAdGaveUp = true;
                note("Instagram: no pude saltear la publicidad");
            }
        } catch (Exception ignored) {
        }
        endStoryAd();
    }

    private void endStoryAd() {
        if (!storyAdActive) return;
        storyAdActive = false;
        handler.removeCallbacks(storyAdFollowUpRunnable);
        // Si ya se deslizó, la pantalla se está yendo con el giro del cubo: no tocarla.
        if (storyAdSwipes == 0) cover.fadeOut(120);
    }

    /**
     * El video del chat no arrancó: Instagram no lo pudo reproducir (le pasa con algunos
     * Reels aunque Limit esté apagado). Se avisa en el medio de la pantalla, 4 s.
     */
    private void dmCheckPlaying(int token) {
        if (token != dmOpenToken || dmNavBusy || !wasInDmPlayer) return;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !(reelsPlayerFullScreen(root) && isDmReel(root))) return;
            if (currentItemIsStill(root)) return;                 // foto o carrusel: no tiene barra
            if (visibleBounds(root, "scrubber") != null) return;  // se está reproduciendo
            note("Instagram: este video no arrancó (sin barra de avance)");
            centerNotice.showGlowing("Instagram no puede reproducir este video", Overlay.VIOLET);
            handler.removeCallbacks(hideCenterNotice);
            handler.postDelayed(hideCenterNotice, 4000);
        } catch (Exception ignored) {
        }
    }

    private final Runnable hideCenterNotice = () -> centerNotice.hide();

    /** Tocaste algo del Reel (comentarios, compartir…): la zona se aparta hasta salir del Reel. */
    private void onDmReelButton(AccessibilityEvent e) {
        if (!wasInDmPlayer) return;
        try {
            AccessibilityNodeInfo src = e.getSource();
            String id = src == null ? null : src.getViewIdResourceName();
            if (id == null) return;
            String name = id.substring(id.indexOf('/') + 1);
            if (name.startsWith("comment") || name.startsWith("direct_share") || name.startsWith("clips_ufi_more")
                    || name.endsWith("_count") || name.startsWith("clips_author") || name.startsWith("sender_")) {
                dmSuppressed = true;
                dmCatcher.hide();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Toda la pantalla del Reel entre la barra de arriba y la barra para responder. Los
     * toques (botones de la derecha, texto, pausar…) se le devuelven a Instagram; los
     * deslizamientos hacia arriba o abajo los maneja Limit, así Instagram nunca pasa a
     * sus Reels recomendados.
     */
    private static Rect dmSwipeArea(AccessibilityNodeInfo root) {
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        int top = screen.top + (int) (screen.height() * 0.11f);
        int bottom = screen.top + (int) (screen.height() * 0.90f);
        Rect r = visibleBounds(root, "clips_viewer_action_bar");
        if (r != null) top = r.bottom;
        r = visibleBounds(root, "clips_footer_mask_container");
        if (r == null) r = visibleBounds(root, "reel_viewer_message_composer");
        if (r != null && r.top > top) bottom = r.top;
        if (bottom - top < 200) return null;
        return new Rect(0, top, screen.width(), bottom);
    }

    private static Rect visibleBounds(AccessibilityNodeInfo root, String name) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/" + name);
        if (found == null) return null;
        for (AccessibilityNodeInfo n : found) {
            if (n != null && n.isVisibleToUser()) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                return r;
            }
        }
        return null;
    }

    private static boolean threadVisible(AccessibilityNodeInfo root) {
        return visibleBounds(root, "message_list") != null;
    }

    /**
     * Los Reels y publicaciones (fotos y carruseles) compartidos en el chat que se ven
     * ahora. No cuentan las citas chiquitas de una respuesta ni los enlaces a páginas.
     */
    private static List<AccessibilityNodeInfo> threadBubbles(AccessibilityNodeInfo root) {
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (String name : new String[]{"reel_share_item_view", "xma_bubble_container"}) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/" + name);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                if (n == null || !n.isVisibleToUser()) continue;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.width() < screen.width() * 0.35f) continue;   // cita chiquita de una respuesta
                // Publicaciones de Instagram (foto o carrusel): llevan arriba la cabecera con
                // la cuenta que la publicó. Los enlaces a páginas (con o sin imagen) no la tienen.
                if (name.startsWith("xma") && !(hasIdDown(n, "header_container", 0) && hasIdDown(n, "media_container", 0))) continue;
                out.add(n);
            }
        }
        return out;
    }

    /** ¿Lo mandó la otra persona? (sus mensajes van a la izquierda; los tuyos, a la derecha) */
    private static boolean receivedBubble(Rect r, int screenWidth) {
        return r.left < screenWidth * 0.22f;
    }

    private void rememberThreadBubbles(AccessibilityNodeInfo root) {
        lastThreadBubbles.clear();
        lastThreadRows.clear();
        lastThreadHidden.clear();
        for (AccessibilityNodeInfo n : threadBubbles(root)) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            lastThreadBubbles.add(r);
            lastThreadRows.add(rowOf(n));
            // Al abrir un mensaje, Instagram suele esconder su miniatura mientras la agranda.
            boolean hidden = false;
            List<AccessibilityNodeInfo> img = n.findAccessibilityNodeInfosByViewId(IG + ":id/image");
            if (img != null) {
                hidden = !img.isEmpty();
                for (AccessibilityNodeInfo m : img) if (m != null && m.isVisibleToUser()) hidden = false;
            }
            lastThreadHidden.add(hidden);
        }
        Rect list = visibleBounds(root, "message_list");
        if (list != null) lastThreadList.set(list);
    }

    /** Tocaste algo en el chat: si fue un Reel, ese es el que vas a ver. */
    private void onThreadClick(AccessibilityEvent e) {
        if (dmNavBusy) return;   // fue un toque de Limit
        try {
            AccessibilityNodeInfo src = e.getSource();
            if (src == null) return;
            if (Diag.recording()) {
                String sid = src.getViewIdResourceName();
                note("Instagram: toque en " + (sid == null ? "(sin nombre)" : sid.substring(sid.indexOf('/') + 1))
                        + " · mensaje " + rowOf(src));
            }
            // ¿Tocaste un Reel o una publicación compartida? (el mensaje que lo contiene)
            AccessibilityNodeInfo bubble = null;
            AccessibilityNodeInfo cur = src;
            for (int i = 0; cur != null && i < 8 && bubble == null; i++) {
                String id = cur.getViewIdResourceName();
                if (id != null && (id.endsWith(":id/reel_share_item_view") || id.endsWith(":id/xma_bubble_container"))) bubble = cur;
                cur = cur.getParent();
            }
            if (bubble == null) {
                // A veces el toque lo informa el mensaje entero: buscar el Reel adentro.
                List<AccessibilityNodeInfo> in = threadBubblesIn(src);
                if (in.size() == 1) bubble = in.get(0);
            }
            if (bubble == null) return;
            int row = rowOf(bubble);
            Rect r = new Rect();
            bubble.getBoundsInScreen(r);
            dmCurrentRect.set(r);
            dmCurrentRow = row;
            dmCurNode = null;
            dmNavOpenedAt = SystemClock.elapsedRealtime();
            note("Instagram: tocaste el mensaje " + row + " del chat");
        } catch (Exception ignored) {
        }
    }

    private static List<AccessibilityNodeInfo> threadBubblesIn(AccessibilityNodeInfo n) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (String name : new String[]{"reel_share_item_view", "xma_bubble_container"}) {
            List<AccessibilityNodeInfo> found = n.findAccessibilityNodeInfosByViewId(IG + ":id/" + name);
            if (found != null) for (AccessibilityNodeInfo f : found) if (f != null) out.add(f);
        }
        return out;
    }

    /** Abriste un Reel del chat vos: cuál fue (el único a la vista, o el más centrado). */
    private void onDmPlayerOpened() {
        if (dmOpenedByLimit) {   // lo abrió Limit: ya se sabe cuál es
            dmOpenedByLimit = false;
            return;
        }
        if (SystemClock.elapsedRealtime() - dmNavOpenedAt < 10_000) return;   // se supo por el toque en el chat
        dmCurNode = null;
        dmCurrentRect.setEmpty();
        dmCurrentRow = -1;
        if (lastThreadBubbles.isEmpty()) return;
        int best = -1;
        String how = "el más visible";
        // 1) El que tenía la miniatura escondida (Instagram la estaba agrandando).
        int hiddenCount = 0;
        for (int i = 0; i < lastThreadHidden.size(); i++) if (lastThreadHidden.get(i)) { hiddenCount++; best = i; }
        if (hiddenCount == 1) how = "su miniatura se escondió al abrirse";
        else best = -1;
        // 2) Si no, el que más se veía en pantalla (el que tenías a la vista para tocar).
        if (best < 0) {
            float mid = lastThreadList.isEmpty() ? 1200 : lastThreadList.exactCenterY();
            for (int i = 0; i < lastThreadBubbles.size(); i++) {
                Rect b = lastThreadBubbles.get(i);
                if (best < 0) { best = i; continue; }
                Rect c = lastThreadBubbles.get(best);
                long ab = (long) b.width() * b.height(), ac = (long) c.width() * c.height();
                if (ab > ac * 1.15f || (ab * 1.15f >= ac && Math.abs(b.exactCenterY() - mid) < Math.abs(c.exactCenterY() - mid))) best = i;
            }
        }
        if (best >= 0) {
            dmCurrentRect.set(lastThreadBubbles.get(best));
            dmCurrentRow = lastThreadRows.get(best);
            note("Instagram: abriste uno del chat; supongo el " + dmCurrentRow + " (" + how + "; había "
                    + lastThreadBubbles.size() + " a la vista)");
        }
    }

    private void onDmSwipe(int dir) {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null || !(reelsPlayerFullScreen(r) && isDmReel(r))) {
                // Ya no estás en el Reel (la zona quedó de más): sacarla.
                dmCatcher.hide();
                dmPlayerStable = false;
                wasInDmPlayer = false;
                return;
            }
        } catch (Exception ignored) {
        }
        note("Instagram: deslizaste " + (dir > 0 ? "hacia arriba" : "hacia abajo") + " en un Reel de un mensaje");
        dmNavigate(dir);
    }

    /** Reloj violeta, volver al chat, buscar el Reel anterior (-1) o siguiente (+1) y abrirlo. */
    private void dmNavigate(int dir) {
        if (dmNavBusy) return;
        dmNavBusy = true;
        centerNotice.hide();
        dmScrollRetried = false;
        dmNoScrollWaits = 0;
        dmSearchDir = dir;
        dmNetOlder = 0;
        dmOrientLearned = false;
        dmApproachSteps = 0;
        dmApproachDone = false;
        dmSkips = 0;
        dmCatcher.hide();
        cover.show(() -> {
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> dmWaitThread(dir, 0), 90);
        }, 0, Cover.DARK);
        // Por las dudas, nunca queda tapado más de 15 s.
        final long startedAt = SystemClock.elapsedRealtime();
        dmNavStartedAt = startedAt;
        handler.postDelayed(() -> { if (dmNavBusy && dmNavStartedAt == startedAt) dmFinish(); }, 15_000);
    }

    private void dmWaitThread(int dir, int attempt) {
        if (!dmNavBusy) return;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && threadVisible(root)) {
                dmFindTarget(dir, 0);
                return;
            }
        } catch (Exception ignored) {
        }
        if (attempt < 50) handler.postDelayed(() -> dmWaitThread(dir, attempt + 1), 50);
        else dmFinish();
    }

    /** Dónde está ahora el Reel que estabas viendo (null si quedó fuera de la pantalla). */
    private Rect dmCurrentNow(List<AccessibilityNodeInfo> bubbles, boolean afterReturn) {
        if (dmCurNode != null) {
            try {
                if (dmCurNode.refresh() && dmCurNode.isVisibleToUser()) {
                    Rect r = new Rect();
                    dmCurNode.getBoundsInScreen(r);
                    return r;
                }
            } catch (Exception ignored) {
            }
            if (!afterReturn) return null;
        }
        if (afterReturn && !dmCurrentRect.isEmpty()) {
            // Al volver, Instagram deja el chat donde estaba: el Reel está en el mismo lugar.
            // El más parecido en lugar (por si el chat se movió un poco al volver).
            AccessibilityNodeInfo best = null;
            Rect bestRect = null;
            int bestDist = Integer.MAX_VALUE;
            for (AccessibilityNodeInfo b : bubbles) {
                Rect r = new Rect();
                b.getBoundsInScreen(r);
                int dist = Math.abs(r.centerY() - dmCurrentRect.centerY()) + Math.abs(r.left - dmCurrentRect.left);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = b;
                    bestRect = r;
                }
            }
            if (best != null && bestDist < 350) {
                dmCurNode = best;
                return bestRect;
            }
        }
        return null;
    }

    private void dmFindTarget(int dir, int scrolls) {
        if (!dmNavBusy) return;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !threadVisible(root)) { dmFinish(); return; }
            List<AccessibilityNodeInfo> bubbles = threadBubbles(root);
            Rect scr0 = new Rect();
            root.getBoundsInScreen(scr0);
            // Primero, por el número de mensaje dentro del chat (no cambia aunque el chat se mueva).
            int[] rows = new int[bubbles.size()];
            Rect[] rects = new Rect[bubbles.size()];
            boolean haveRows = dmCurrentRow >= 0;
            for (int i = 0; i < bubbles.size(); i++) {
                rects[i] = new Rect();
                bubbles.get(i).getBoundsInScreen(rects[i]);
                rows[i] = rowOf(bubbles.get(i));
                if (rows[i] < 0) haveRows = false;
            }
            if (haveRows) {
                for (int i = 0; i < rows.length; i++) for (int j = i + 1; j < rows.length; j++) {
                    if (rows[i] != rows[j] && rects[i].centerY() != rects[j].centerY()) {
                        dmRowOrient = Integer.signum((rows[j] - rows[i]) * (rects[j].centerY() - rects[i].centerY()));
                    }
                }
            }
            if (scrolls == 0) {
                StringBuilder sb = new StringBuilder("Instagram: buscando " + (dir < 0 ? "anterior" : "siguiente")
                        + " · actual=" + dmCurrentRow + " orden=" + dmRowOrient + " a la vista=");
                for (int i = 0; i < rows.length; i++) {
                    sb.append(rows[i]).append(receivedBubble(rects[i], scr0.width()) ? "r" : "m").append(' ');
                }
                note(sb.toString());
            }
            if (haveRows && dmRowOrient != 0) {
                // El que estabas viendo no está a la vista y todos los que se ven están del lado
                // buscado: puede haber otros más cerca en el medio. Primero acercarse a él.
                boolean curVisible = false, allWanted = rows.length > 0;
                for (int i = 0; i < rows.length; i++) {
                    if (rows[i] == dmCurrentRow) curVisible = true;
                    boolean above = (rows[i] - dmCurrentRow) * dmRowOrient < 0;
                    if (above != (dir < 0)) allWanted = false;
                }
                if (!curVisible && allWanted && !dmApproachDone && dmApproachSteps < 40) {
                    dmApproachSteps++;
                    dmScrollToward(root, dir > 0, dir, scrolls, null, true);
                    return;
                }
                dmApproachDone = true;
                int best = -1;
                for (int i = 0; i < rows.length; i++) {
                    if (rows[i] == dmCurrentRow || !receivedBubble(rects[i], scr0.width())) continue;
                    boolean above = (rows[i] - dmCurrentRow) * dmRowOrient < 0;
                    if (above != (dir < 0)) continue;
                    if (best < 0 || Math.abs(rows[i] - dmCurrentRow) < Math.abs(rows[best] - dmCurrentRow)) best = i;
                }
                if (best >= 0) {
                    dmOpen(bubbles.get(best), dir);
                    return;
                }
                // Ninguno a la vista para ese lado: mover el chat y volver a buscar.
                dmScrollAndRetry(root, dir, scrolls, null);
                return;
            }
            Rect cur = dmCurrentNow(bubbles, scrolls == 0);
            if (cur == null && scrolls == 0) {
                // No se sabe cuál estabas viendo: se toma el que está más al centro del chat.
                AccessibilityNodeInfo list0 = firstVisible(root, "message_list");
                Rect lr = new Rect();
                if (list0 != null) list0.getBoundsInScreen(lr);
                float mid = lr.isEmpty() ? 1200 : lr.exactCenterY();
                for (AccessibilityNodeInfo b : bubbles) {
                    Rect r = new Rect();
                    b.getBoundsInScreen(r);
                    if (cur == null || Math.abs(r.exactCenterY() - mid) < Math.abs(cur.exactCenterY() - mid)) {
                        cur = r;
                        dmCurNode = b;
                    }
                }
                if (cur == null) {
                    note("Instagram: no hay Reels ni publicaciones a la vista en el chat");
                    dmFinish();
                    return;
                }
                note("Instagram: no supe cuál estabas viendo; tomé el del centro del chat");
            }
            AccessibilityNodeInfo target = null;
            float bestY = 0;
            Rect scr = new Rect();
            root.getBoundsInScreen(scr);
            for (AccessibilityNodeInfo b : bubbles) {
                Rect r = new Rect();
                b.getBoundsInScreen(r);
                if (!receivedBubble(r, scr.width())) continue;   // los que mandaste vos no
                float y = r.exactCenterY();
                boolean ok;
                if (cur != null) ok = dir < 0 ? y < cur.exactCenterY() - 20 : y > cur.exactCenterY() + 20;
                else ok = true;   // el actual quedó fuera de la pantalla: todos están del lado buscado
                if (cur != null && Math.abs(r.top - cur.top) < 20 && Math.abs(r.left - cur.left) < 20) ok = false;
                if (!ok) continue;
                if (target == null || (dir < 0 ? y > bestY : y < bestY)) {
                    target = b;
                    bestY = y;
                }
            }
            if (target != null) {
                dmOpen(target, dir);
                return;
            }
            dmScrollAndRetry(root, dir, scrolls, cur);
        } catch (Exception ex) {
            dmFinish();
        }
    }

    /** Mueve el chat una página hacia el lado buscado y vuelve a buscar. */
    private void dmScrollAndRetry(AccessibilityNodeInfo root, int dir, int scrolls, Rect cur) {
        dmScrollToward(root, dir < 0, dir, scrolls, cur, false);
    }

    /**
     * Mueve el chat una página hacia los mensajes más viejos (older) o más nuevos y vuelve
     * a buscar. approaching: se está volviendo hacia el que estabas viendo, para no saltear
     * los que hay en el medio.
     */
    private void dmScrollToward(AccessibilityNodeInfo root, boolean older, int dir, int scrolls, Rect cur, boolean approaching) {
        try {
            AccessibilityNodeInfo list = firstVisible(root, "message_list");
            int action = older == dmOlderIsBackward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            if (approaching && (list == null || !hasAction(list, action))) {
                dmApproachDone = true;   // no se puede acercar más: buscar con lo que hay
                dmFindTarget(dir, scrolls + 1);
                return;
            }
            if (scrolls < 60 && list != null && !hasAction(list, action) && dmNoScrollWaits < 6) {
                // Arriba de todo Instagram carga los mensajes más viejos de a poco: mientras
                // carga, el chat no se puede mover. Esperar un poco antes de decir que no hay más.
                dmNoScrollWaits++;
                handler.postDelayed(() -> {
                    try {
                        dmScrollToward(getRootInActiveWindow(), older, dir, scrolls, cur, false);
                    } catch (Exception ex) {
                        dmAbort("error buscando");
                    }
                }, 450);
                return;
            }
            if (scrolls >= 60 || list == null || !hasAction(list, action)) {
                dmEnd(dir);
                return;
            }
            dmNoScrollWaits = 0;
            final Rect before = cur;
            list.performAction(action);
            dmNetOlder += older ? 1 : -1;
            handler.postDelayed(() -> {
                // La primera vez se aprende hacia dónde se mueve el chat con cada acción.
                if (before != null && !dmOrientLearned) {
                    dmOrientLearned = true;
                    try {
                        if (dmCurNode != null && dmCurNode.refresh() && dmCurNode.isVisibleToUser()) {
                            Rect after = new Rect();
                            dmCurNode.getBoundsInScreen(after);
                            boolean movedDown = after.top > before.top;   // se ven mensajes de más arriba
                            boolean wanted = older ? movedDown : !movedDown;
                            if (after.top != before.top && !wanted) dmOlderIsBackward = !dmOlderIsBackward;
                        }
                    } catch (Exception ignored) {
                    }
                }
                dmFindTarget(dir, scrolls + 1);
            }, 200);
        } catch (Exception ex) {
            dmAbort("error buscando");
        }
    }

    /**
     * No hay más Reels para ese lado. Hacia atrás (más viejos): el chat vuelve a la altura
     * del último Reel que viste. Hacia adelante: el chat queda abajo de todo, para responder.
     */
    private void dmEnd(int dir) {
        note("Instagram: no hay más Reels " + (dir < 0 ? "anteriores" : "siguientes") + " en el chat");
        // En los dos casos el chat se mueve hacia los mensajes más nuevos: hacia atrás, lo
        // justo para volver a donde estaba; hacia adelante, hasta abajo de todo.
        if (dir > 0) {
            dmScrollBack(newerAction(), 80, 0);   // abajo de todo, para responder
            return;
        }
        // Volver a la altura del último que viste (lo que se movió el chat buscando).
        if (dmNetOlder > 0) dmScrollBack(newerAction(), dmNetOlder, 0);
        else if (dmNetOlder < 0) dmScrollBack(olderAction(), -dmNetOlder, 0);
        else dmScrollBack(newerAction(), 0, 0);
    }

    private int olderAction() {
        return dmOlderIsBackward ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
    }

    /** Desplazar el chat hacia los mensajes más nuevos (hacia abajo). */
    private int newerAction() {
        return dmOlderIsBackward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
    }

    /** Algo salió mal: dejar el chat donde estaba antes de buscar, y sacar el reloj. */
    private void dmAbort(String why) {
        note("Instagram: " + why);
        if (dmNetOlder > 0) dmScrollBack(newerAction(), dmNetOlder, 0);
        else if (dmNetOlder < 0) dmScrollBack(olderAction(), -dmNetOlder, 0);
        else dmFinish();
    }

    private void dmScrollBack(int action, int steps, int done) {
        if (!dmNavBusy) return;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo list = root == null ? null : firstVisible(root, "message_list");
            if (done < steps && list != null && hasAction(list, action)) {
                list.performAction(action);
                handler.postDelayed(() -> dmScrollBack(action, steps, done + 1), 140);
                return;
            }
            // Puede que el chat siga moviéndose: un último intento un poco después.
            if (done < steps && list != null && done >= 0 && !dmScrollRetried) {
                dmScrollRetried = true;
                handler.postDelayed(() -> dmScrollBack(action, steps, done), 300);
                return;
            }
        } catch (Exception ignored) {
        }
        dmScrollRetried = false;
        handler.postDelayed(this::dmFinish, 120);
    }

    /** Abre el Reel del chat tocándolo (con el reloj puesto encima, que no recibe toques). */
    private void dmOpen(AccessibilityNodeInfo target, int dir) {
        Rect r = new Rect();
        target.getBoundsInScreen(r);
        dmCurNode = target;
        dmCurrentRect.set(r);
        dmCurrentRow = rowOf(target);
        dmOpenedByLimit = true;
        dmPlayerSeen = 0;
        dmSettleAndTap(target, dir, null, 0, false);
    }

    /** Dónde tocar: la imagen del mensaje (no el texto de abajo), como está ahora en pantalla. */
    private static Rect dmTapRect(AccessibilityNodeInfo target) {
        Rect tap = new Rect();
        target.getBoundsInScreen(tap);
        List<AccessibilityNodeInfo> media = target.findAccessibilityNodeInfosByViewId(IG + ":id/media_container");
        if (media != null) for (AccessibilityNodeInfo m : media) {
            if (m != null && m.isVisibleToUser()) {
                m.getBoundsInScreen(tap);
                break;
            }
        }
        return tap;
    }

    /**
     * Antes de tocar, esperar a que el chat termine de moverse (que el mensaje quede
     * quieto) y, si está cortado en un borde, pedir que se vea entero.
     */
    private void dmSettleAndTap(AccessibilityNodeInfo target, int dir, Rect last, int tries, boolean askedShow) {
        if (!dmNavBusy) return;
        try {
            target.refresh();
            Rect tap = dmTapRect(target);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            Rect list = root == null ? null : visibleBounds(root, "message_list");
            boolean cut = list != null && (tap.top <= list.top + 4 || tap.bottom >= list.bottom - 4 || tap.height() < 250);
            if (cut && !askedShow) {
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                handler.postDelayed(() -> dmSettleAndTap(target, dir, null, tries + 1, true), 160);
                return;
            }
            boolean still = last != null && last.equals(tap);
            if (!still && tries < 8) {
                handler.postDelayed(() -> dmSettleAndTap(target, dir, tap, tries + 1, askedShow), 110);
                return;
            }
            dmPress(target, list, 0);
            handler.postDelayed(() -> dmWaitPlayer(target, dir, 0), 80);
        } catch (Exception ex) {
            dmAbort("error al abrir; vuelvo a dejar el chat donde estaba");
        }
    }

    /**
     * Abre el mensaje: primero como lo haría el lector de pantalla (un "clic" sobre el
     * mensaje, que no depende de dónde esté), y si no, tocando el centro de la imagen
     * dentro de la parte visible del chat.
     */
    private void dmPress(AccessibilityNodeInfo target, Rect list, int attempt) {
        if (attempt % 2 == 0) {
            AccessibilityNodeInfo cur = target;
            for (int i = 0; cur != null && i < 3; i++) {   // el mensaje compartido, no la fila entera
                if (hasAction(cur, AccessibilityNodeInfo.ACTION_CLICK) && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
                cur = cur.getParent();
            }
        }
        Rect tap = dmTapRect(target);
        if (list != null && !tap.intersect(list)) return;
        tapAt(tap.exactCenterX(), tap.exactCenterY());
    }

    /**
     * El reloj queda hasta estar seguro de que se abrió el Reel o la publicación del
     * chat (su reproductor, con la barra para responder), visto dos veces seguidas.
     */
    private void dmWaitPlayer(AccessibilityNodeInfo target, int dir, int attempt) {
        if (!dmNavBusy) return;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence pkg = root == null ? null : root.getPackageName();
            if (pkg != null && IG.contentEquals(pkg)) {
                boolean thread = threadVisible(root);
                boolean player = !thread && reelsPlayerFullScreen(root) && isDmReel(root);
                if (player) {
                    if (++dmPlayerSeen >= 2) {
                        handler.postDelayed(this::dmFinish, 200);   // un instante para que se dibuje
                        return;
                    }
                } else {
                    dmPlayerSeen = 0;
                    // Sigue en el chat: el toque no llegó, se vuelve a tocar (como mucho dos veces).
                    if (thread && (attempt == 12 || attempt == 24)) {
                        target.refresh();
                        dmPress(target, visibleBounds(root, "message_list"), attempt / 12);
                    }
                    // Tres intentos y no se abre (Reel borrado o no disponible): saltearlo.
                    if (thread && attempt >= 40 && dmSkips < 4) {
                        dmSkips++;
                        note("Instagram: el mensaje " + dmCurrentRow + " no se abre; sigo con el próximo");
                        dmFindTarget(dir, 0);
                        return;
                    }
                    // Se abrió otra cosa (no es un Reel ni una publicación, ni el reproductor
                    // cargando): después de 2,5 s, volver y seguir buscando.
                    boolean loading = reelsPlayerFullScreen(root);
                    if (!thread && !loading && attempt >= 40 && dmSkips < 3) {
                        dmSkips++;
                        note("Instagram: eso no era un Reel ni una publicación; sigo buscando");
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        handler.postDelayed(() -> dmWaitThread(dir, 0), 150);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (attempt < 110) handler.postDelayed(() -> dmWaitPlayer(target, dir, attempt + 1), 60);
        else dmAbort("no se abrió el Reel; vuelvo a dejar el chat donde estaba");   // nunca más de ~7 s
    }

    private void dmFinish() {
        dmNavBusy = false;
        wasInDmPlayer = false;
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && threadVisible(r)) dmOpenedByLimit = false;   // terminó en el chat
            // La zona que reconoce el deslizamiento, puesta ya (antes de que se vaya el reloj):
            // si deslizás enseguida, Instagram no llega a pasar a sus Reels recomendados.
            if (r != null && r.getPackageName() != null && IG.contentEquals(r.getPackageName())) updateDmReel(r);
        } catch (Exception ignored) {
        }
        cover.fadeOut(160);
    }

    private void tapAt(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null);
    }

    private static AccessibilityNodeInfo firstVisible(AccessibilityNodeInfo root, String name) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(IG + ":id/" + name);
        if (found == null) return null;
        for (AccessibilityNodeInfo n : found) if (n != null && n.isVisibleToUser()) return n;
        return null;
    }

    /** Número de mensaje dentro del chat (la lista lo informa a la accesibilidad), o -1. */
    private static int rowOf(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 8; i++) {
            AccessibilityNodeInfo.CollectionItemInfo info = cur.getCollectionItemInfo();
            if (info != null) return info.getRowIndex();
            cur = cur.getParent();
        }
        return -1;
    }

    private static boolean hasAction(AccessibilityNodeInfo n, int action) {
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) if (a.getId() == action) return true;
        return false;
    }

    private static boolean canScrollBack(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
            int id = a.getId();
            if (id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    || id == android.R.id.accessibilityActionScrollUp) return true;
            if (android.os.Build.VERSION.SDK_INT >= 29 && id == android.R.id.accessibilityActionPageUp) return true;
        }
        return false;
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
        return false;   // sin la barra de abajo no hay botón de Reels (la pestaña de un perfil no cuenta)
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
            // Pastillita roja al instante; a los 4 s pasa a la violeta "Siguiendo".
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
                // Un Reel abierto desde un perfil: volver al perfil, no al Inicio.
                if (now - lastProfileSeen < 60_000 && reelsPlayerFullScreen(root)) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
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
