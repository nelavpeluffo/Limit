package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * Pantalla de transición al ir al buscador de Instagram: tapa la grilla de la lupa
 * un instante con el fondo violeta del ícono de Limit y, en el centro, su reloj
 * blanco con las agujas girando como animación de carga. No recibe toques.
 */
final class Cover {
    private final AccessibilityService service;
    private final WindowManager wm;
    private FrameLayout view;     // ventana (transparente)
    private FrameLayout panel;    // la pantalla del reloj: toda la pantalla o justo sobre una historia
    private ClockSpinner spinner;
    private ValueAnimator anim;
    private boolean shown;

    /**
     * El reloj se dibuja y anima en su propio hilo: así gira parejo aunque Limit esté
     * ocupado revisando la pantalla de Instagram mientras cambia.
     */
    private final android.os.Handler ui;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    Cover(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        android.os.HandlerThread t = new android.os.HandlerThread("limit-reloj", android.os.Process.THREAD_PRIORITY_DISPLAY);
        t.start();
        ui = new android.os.Handler(t.getLooper());
    }

    void arm() { ui.post(this::armImpl); }
    void disarm() { ui.post(this::disarmImpl); }
    static final int VIOLET = 0xFF9364FF;   // buscador de Instagram (como el fondo del ícono)
    static final int DARK = 0xFF1C1C1C;     // pase entre Reels del chat

    private int color = VIOLET;

    void show() { show(null, 0); }
    void show(Runnable onShown) { show(onShown, 0); }
    void show(Runnable onShown, long fadeInMs) { show(onShown, fadeInMs, VIOLET); }
    /** onShown corre en el hilo principal, cuando el reloj ya tapa la pantalla. */
    void show(Runnable onShown, long fadeInMs, int background) {
        show(onShown, fadeInMs, background, null);
    }

    /** area: dónde ponerse (null = toda la pantalla); por ejemplo, justo sobre una historia. */
    void show(Runnable onShown, long fadeInMs, int background, android.graphics.Rect area) {
        Runnable onMain = onShown == null ? null : () -> main.post(onShown);
        final android.graphics.Rect a = area == null ? null : new android.graphics.Rect(area);
        ui.post(() -> {
            color = background;
            if (panel != null) panel.setBackgroundColor(color);
            setArea(a);
            showImpl(onMain, fadeInMs);
        });
    }

    private WindowManager.LayoutParams lp;
    private final android.graphics.Rect area = new android.graphics.Rect();

    /** Cambia el lugar de la pantalla del reloj (vacío = toda la pantalla). */
    private void setArea(android.graphics.Rect a) {
        android.graphics.Rect want = a == null ? new android.graphics.Rect() : a;
        if (want.equals(area)) return;
        area.set(want);
        applyArea();
    }

    /** La ventana ocupa siempre toda la pantalla; lo que cambia es dónde está la pantalla del reloj. */
    private void applyArea() {
        if (panel == null) return;
        FrameLayout.LayoutParams p;
        if (area.isEmpty()) {
            p = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        } else {
            p = new FrameLayout.LayoutParams(area.width(), area.height());
            p.leftMargin = area.left;
            p.topMargin = area.top;
        }
        panel.setLayoutParams(p);
        // Sobre una historia: un poco transparente (70 %).
        panel.setAlpha(area.isEmpty() ? 1f : 0.7f);
    }
    void fadeOut(long ms) { ui.post(() -> fadeOutImpl(ms)); }
    /** Se va girando como la cara de un cubo hacia la izquierda (como las historias de Instagram). */
    void cubeOut(long ms) { ui.post(() -> cubeOutImpl(ms)); }
    void hide() { ui.post(this::hideImpl); }

    private boolean attached;   // ventana puesta (visible o transparente)
    private boolean armed;      // dejarla puesta y transparente, lista para aparecer al instante

    /**
     * Deja la ventana del reloj puesta pero transparente, para que al hacer falta
     * aparezca en el mismo cuadro (agregar una ventana nueva tarda más).
     */
    private void armImpl() {
        armed = true;
        attach();
    }

    /** Ya no hace falta tenerla lista: se saca si no está a la vista. */
    private void disarmImpl() {
        armed = false;
        if (!shown) detach();
    }

    /**
     * Muestra el reloj (con un fundido de fadeInMs, o de golpe si es 0); onShown corre
     * cuando ya tapa la pantalla por completo.
     */
    private void showImpl(Runnable onShown, long fadeInMs) {
        resetTransform();
        if (shown && view.getAlpha() < 1f) {
            // Se estaba yendo con el fundido: vuelve a aparecer entero.
            view.animate().cancel();
            view.setAlpha(1f);
        }
        Runnable once = onShown == null ? null : new Runnable() {
            boolean done;
            @Override public void run() {
                if (done) return;
                done = true;
                onShown.run();
            }
        };
        if (shown) {
            if (once != null) view.post(once);
            return;
        }
        if (!attach()) {
            if (once != null) once.run();
            return;
        }
        shown = true;
        view.animate().cancel();
        if (fadeInMs > 0) {
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(fadeInMs)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> { if (once != null) view.postOnAnimation(once); })
                    .start();
            if (once != null) view.postDelayed(once, fadeInMs + 200);
        } else {
            view.setAlpha(1f);
            if (once != null) {
                // Dos cuadros después ya está en pantalla; por las dudas, igual a los 150 ms.
                view.postOnAnimation(() -> view.postOnAnimation(once));
                view.postDelayed(once, 150);
            }
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1800);   // una vuelta cada 1,8 s
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> spinner.setPhase((float) a.getAnimatedValue()));
        anim.start();
    }

    /** Se va con un fundido suave, dejando ver lo que quedó abajo (el Inicio). */
    private void fadeOutImpl(long ms) {
        if (!shown) return;
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(ms)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> { if (shown && view.getAlpha() == 0f) hideImpl(); })
                .start();
    }

    private void cubeOutImpl(long ms) {
        if (!shown || panel == null) return;
        float d = service.getResources().getDisplayMetrics().density;
        float w = panel.getWidth() > 0 ? panel.getWidth() : service.getResources().getDisplayMetrics().widthPixels;
        panel.animate().cancel();
        panel.setCameraDistance(8000 * d);
        panel.setPivotX(w);                       // gira sobre su borde derecho…
        panel.setPivotY(panel.getHeight() / 2f);
        // Lo más "adelante" posible: apenas más grande que la historia, así sus bordes
        // no asoman por los costados mientras gira.
        panel.setScaleX(1.08f);
        panel.setScaleY(1.08f);
        panel.animate()
                .translationX(-w)                // …mientras se corre hacia la izquierda
                .rotationY(-90f)
                .setDuration(ms)
                // Lineal: Limit desliza el dedo a velocidad pareja e Instagram gira
                // la historia siguiendo al dedo, así los dos van juntos.
                .setInterpolator(new android.view.animation.LinearInterpolator())
                .withEndAction(() -> { if (shown) hideImpl(); })
                .start();
    }

    /** Vuelve a dejar la pantalla derecha, para la próxima vez que aparezca. */
    private void resetTransform() {
        if (panel == null) return;
        panel.animate().cancel();
        panel.setTranslationX(0f);
        panel.setRotationY(0f);
        panel.setScaleX(1f);
        panel.setScaleY(1f);
    }

    private void hideImpl() {
        if (view != null) view.animate().cancel();
        resetTransform();
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        if (!shown) return;
        shown = false;
        if (armed) view.setAlpha(0f);
        else detach();
    }

    private boolean attach() {
        if (attached) return true;
        float d = service.getResources().getDisplayMetrics().density;
        if (view == null) {
            view = new FrameLayout(service);
            view.setClipChildren(false);
            panel = new FrameLayout(service);
            spinner = new ClockSpinner(service, d);
            int size = (int) (72 * d);
            panel.addView(spinner, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            panel.setBackgroundColor(color);
            view.addView(panel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            applyArea();
        }
        view.setAlpha(0f);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(view, lp);
            attached = true;
        } catch (Exception ignored) {
            attached = false;
        }
        return attached;
    }

    private void detach() {
        if (!attached) return;
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        }
        attached = false;
    }

    /** El reloj del ícono de Limit: aro blanco y dos agujas que giran. */
    private static final class ClockSpinner extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hand = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF oval = new android.graphics.RectF();
        private float phase;

        ClockSpinner(Context c, float d) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(5 * d);
            ring.setColor(0x55FFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(5 * d);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(0xFFFFFFFF);
            hand.setStyle(Paint.Style.STROKE);
            hand.setStrokeWidth(5 * d);
            hand.setStrokeCap(Paint.Cap.ROUND);
            hand.setColor(0xFFFFFFFF);
        }

        void setPhase(float p) {
            phase = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f - ring.getStrokeWidth();
            oval.set(cx - r, cy - r, cx + r, cy + r);
            // Aro del reloj y un tramo que da vueltas (carga).
            canvas.drawCircle(cx, cy, r, ring);
            canvas.drawArc(oval, phase * 360f - 90f, 90f, false, arc);
            // Aguja larga: una vuelta por ciclo. Aguja corta: más lenta.
            double minute = Math.toRadians(phase * 360f - 90f);
            double hour = Math.toRadians(phase * 30f + 30f - 90f);
            canvas.drawLine(cx, cy, cx + (float) Math.cos(minute) * r * 0.62f,
                    cy + (float) Math.sin(minute) * r * 0.62f, hand);
            canvas.drawLine(cx, cy, cx + (float) Math.cos(hour) * r * 0.40f,
                    cy + (float) Math.sin(hour) * r * 0.40f, hand);
        }
    }
}
