package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

/**
 * Contorno violeta con brillo alrededor de toda la pantalla: indica que estás viendo
 * algo en una situación especial (un Reel que te mandaron, con el límite ya cumplido).
 * Aparece expandiéndose desde el borde y después "respira" suave. No recibe toques.
 */
final class EdgeGlow {
    private final AccessibilityService service;
    private final WindowManager wm;
    private GlowView view;
    private boolean shown;
    private ValueAnimator anim;

    EdgeGlow(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    void show() {
        if (shown) return;
        float d = service.getResources().getDisplayMetrics().density;
        if (view == null) view = new GlowView(service, d, cornerRadius(d));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
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
            view.setLevel(0f);
            view.setAlpha(1f);
            wm.addView(view, lp);
            shown = true;
        } catch (Exception ignored) {
            return;
        }
        // Aparece expandiéndose desde el borde…
        ValueAnimator in = ValueAnimator.ofFloat(0f, 1f);
        in.setDuration(650);
        in.setInterpolator(new DecelerateInterpolator(1.5f));
        in.addUpdateListener(a -> view.setLevel((float) a.getAnimatedValue()));
        in.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (anim != in) return;
                // …y después respira suave mientras está a la vista.
                ValueAnimator breathe = ValueAnimator.ofFloat(1f, 0.7f);
                breathe.setDuration(1400);
                breathe.setRepeatMode(ValueAnimator.REVERSE);
                breathe.setRepeatCount(ValueAnimator.INFINITE);
                // Solo cambia la transparencia (no se vuelve a dibujar el brillo): no gasta batería.
                breathe.addUpdateListener(b -> view.setAlpha((float) b.getAnimatedValue()));
                anim = breathe;
                breathe.start();
            }
        });
        anim = in;
        in.start();
    }

    void hide() {
        if (anim != null) {
            ValueAnimator a = anim;
            anim = null;
            a.cancel();
        }
        if (!shown) return;
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        }
        shown = false;
    }

    /** Lo redondeadas que son las esquinas de la pantalla (para seguir su forma). */
    private float cornerRadius(float d) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                android.view.Display display = service.getSystemService(android.hardware.display.DisplayManager.class)
                        .getDisplay(android.view.Display.DEFAULT_DISPLAY);
                android.view.RoundedCorner rc = display.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT);
                if (rc != null && rc.getRadius() > 0) return rc.getRadius();
            } catch (Exception ignored) {
            }
        }
        return 36 * d;
    }

    private static final class GlowView extends View {
        private final Paint wide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float d, corner;
        private float level;

        GlowView(Context c, float d, float corner) {
            super(c);
            this.d = d;
            this.corner = corner;
            setLayerType(LAYER_TYPE_SOFTWARE, null);   // el desenfoque necesita software
            wide.setStyle(Paint.Style.STROKE);
            wide.setColor(Overlay.VIOLET);
            wide.setMaskFilter(new BlurMaskFilter(22 * d, BlurMaskFilter.Blur.NORMAL));
            mid.setStyle(Paint.Style.STROKE);
            mid.setColor(Overlay.VIOLET);
            mid.setMaskFilter(new BlurMaskFilter(8 * d, BlurMaskFilter.Blur.NORMAL));
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0xFFC9B5FF);
        }

        void setLevel(float l) {
            level = l;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (level <= 0f) return;
            float w = getWidth(), h = getHeight();
            int a = (int) (255 * Math.min(1f, level));
            // El brillo crece hacia adentro desde el borde de la pantalla.
            wide.setStrokeWidth(28 * d * level);
            wide.setAlpha((int) (a * 0.75f));
            mid.setStrokeWidth(10 * d * level);
            mid.setAlpha(a);
            line.setStrokeWidth(2.5f * d);
            line.setAlpha(a);
            float inset = 1.5f * d;
            rect.set(inset, inset, w - inset, h - inset);
            canvas.drawRoundRect(rect, corner, corner, wide);
            canvas.drawRoundRect(rect, corner, corner, mid);
            canvas.drawRoundRect(rect, corner, corner, line);
        }
    }
}
