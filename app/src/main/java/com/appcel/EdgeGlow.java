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
import android.view.animation.DecelerateInterpolator;

/**
 * Brillo violeta arriba de la pantalla: indica que estás viendo algo en una situación
 * especial (un Reel que te mandaron, con el límite ya cumplido). Aparece bajando desde
 * el borde y después "respira" suave. No recibe toques.
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
        if (view == null) view = new GlowView(service);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                (int) (220 * d),   // solo la parte de arriba de la pantalla
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

    /**
     * Brillo violeta difuso que baja desde el borde de arriba de la pantalla y se
     * desvanece: sin líneas ni bordes marcados.
     */
    private static final class GlowView extends View {
        private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bloom = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float level;

        GlowView(Context c) {
            super(c);
        }

        void setLevel(float l) {
            level = l;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (level <= 0f) return;
            float w = getWidth(), h = getHeight() * Math.max(0.05f, level);
            int v = 0x9364FF;
            // Franja suave de arriba hacia abajo.
            band.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                    new int[]{0x99000000 | v, 0x40000000 | v, 0x00000000 | v},
                    new float[]{0f, 0.45f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, band);
            // Un resplandor más intenso al centro, como una luz que entra desde arriba.
            canvas.save();
            canvas.scale(1f, h / (w * 0.5f), w / 2f, 0);
            bloom.setShader(new android.graphics.RadialGradient(w / 2f, 0, w * 0.5f,
                    new int[]{0x80000000 | v, 0x00000000 | v}, null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawCircle(w / 2f, 0, w * 0.5f, bloom);
            canvas.restore();
        }
    }
}
