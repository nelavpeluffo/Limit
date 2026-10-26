package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * Tapa lila que se pone justo encima de un botón de Instagram cuando ya se usaron
 * los 15 minutos del día. Con candado (Reels) se "come" los toques; con lupa
 * (la lupa de Instagram) al tocarla abre el buscador sin pasar por la grilla.
 */
final class Blocker {
    private final AccessibilityService service;
    private final WindowManager wm;
    private FrameLayout view;
    private boolean shown;
    private final Rect current = new Rect();
    private String message = "";
    private final int iconRes;
    private final Runnable onTap;
    private boolean ripple;        // onda violeta al tocar (para las zonas invisibles)
    private RippleView rippleView;
    private float touchX = -1, touchY = -1;

    /** Al tocarla, una onda violeta con brillo crece desde el dedo y se desvanece. */
    void setRipple(boolean on) {
        ripple = on;
    }

    /** Con candado: al tocarla solo avisa. */
    Blocker(AccessibilityService service) {
        this(service, R.drawable.ic_lock, null);
    }

    /** Con el ícono indicado; si onTap no es null, se ejecuta al tocarla. */
    Blocker(AccessibilityService service, int iconRes, Runnable onTap) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.iconRes = iconRes;
        this.onTap = onTap;
    }

    void show(Rect bounds, String message) {
        this.message = message;
        if (shown && bounds.equals(current)) return;
        if (view == null) view = build();

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                bounds.width(),
                bounds.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = bounds.left;
        lp.y = bounds.top;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        try {
            if (shown) wm.updateViewLayout(view, lp);
            else wm.addView(view, lp);
            shown = true;
            current.set(bounds);
        } catch (Exception ignored) {
            shown = false;
        }
    }

    void hide() {
        if (!shown) return;
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        }
        shown = false;
        current.setEmpty();
    }

    boolean isShown() {
        return shown;
    }

    private FrameLayout build() {
        float d = service.getResources().getDisplayMetrics().density;

        FrameLayout outer = new FrameLayout(service);
        outer.setClickable(true);
        outer.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                touchX = ev.getX();
                touchY = ev.getY();
            }
            return false;   // el toque sigue y cuenta como clic
        });
        outer.setOnClickListener(v -> {
            if (rippleView != null) rippleView.play(touchX, touchY);
            if (onTap != null) onTap.run();
            else Toast.makeText(service.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        });

        if (iconRes == 0) {   // zona invisible que solo atrapa el toque
            if (ripple) {
                rippleView = new RippleView(service, d);
                outer.addView(rippleView, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            }
            return outer;
        }

        FrameLayout box = new FrameLayout(service);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF9364FF);
        bg.setCornerRadius(12 * d);
        box.setBackground(bg);
        int m = (int) (4 * d);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        boxLp.setMargins(m, m, m, m);
        outer.addView(box, boxLp);

        ImageView icon = new ImageView(service);
        icon.setImageResource(iconRes);
        int size = (int) (24 * d);
        box.addView(icon, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        return outer;
    }

    /**
     * Onda expansiva: un círculo violeta que crece desde donde tocaste, con un aro
     * de luz difusa en el borde, y se apaga a medida que se agranda.
     */
    private static final class RippleView extends android.view.View {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint glow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private android.animation.ValueAnimator anim;
        private float cx, cy, progress = -1f;

        RippleView(Context c, float d) {
            super(c);
            this.d = d;
            // El desenfoque del brillo necesita dibujarse por software.
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            glow.setStyle(android.graphics.Paint.Style.STROKE);
            glow.setStrokeWidth(10 * d);
            glow.setColor(0xFF9364FF);
            glow.setMaskFilter(new android.graphics.BlurMaskFilter(9 * d, android.graphics.BlurMaskFilter.Blur.NORMAL));
            ring.setStyle(android.graphics.Paint.Style.STROKE);
            ring.setStrokeWidth(2.5f * d);
            ring.setColor(0xFFD9C9FF);
        }

        void play(float x, float y) {
            cx = x >= 0 ? x : getWidth() / 2f;
            cy = y >= 0 ? y : getHeight() / 2f;
            if (anim != null) anim.cancel();
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(650);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            anim.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    progress = -1f;
                    invalidate();
                }
            });
            anim.start();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (progress < 0f) return;
            float w = getWidth(), h = getHeight();
            // Hasta la esquina más lejana del área, para que la onda la cubra entera.
            float maxR = (float) Math.max(Math.max(Math.hypot(cx, cy), Math.hypot(w - cx, cy)),
                    Math.max(Math.hypot(cx, h - cy), Math.hypot(w - cx, h - cy)));
            float r = Math.max(4 * d, maxR * progress);
            float fade = 1f - progress;
            int a = (int) (255 * fade);
            // Relleno: más transparente en el centro y más violeta hacia el borde.
            fill.setShader(new android.graphics.RadialGradient(cx, cy, r,
                    new int[]{0x109364FF, 0x449364FF, 0x669364FF}, new float[]{0f, 0.75f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            fill.setAlpha(a);
            canvas.drawCircle(cx, cy, r, fill);
            // Halo de luz difusa en el borde y un aro fino y claro encima.
            glow.setAlpha((int) (a * 0.9f));
            canvas.drawCircle(cx, cy, r, glow);
            ring.setAlpha(a);
            canvas.drawCircle(cx, cy, r, ring);
        }
    }
}
