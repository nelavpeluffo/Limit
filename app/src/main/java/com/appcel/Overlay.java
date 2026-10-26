package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Pastillita flotante con el tiempo que queda. No recibe toques, así no molesta. */
final class Overlay {
    static final int DARK = 0xCC202124;
    static final int RED = 0xE6C5221F;
    static final int VIOLET = 0xFF9364FF;

    private static final int GLOW_PAD_DP = 22;   // espacio alrededor para que entre el brillo
    private static final int GLOW_RADIUS_DP = 14;

    private final AccessibilityService service;
    private final WindowManager wm;
    private GlowFrame frame;
    private TextView pill;
    private boolean shown;
    private ValueAnimator glowAnim;

    Overlay(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    void show(String text, boolean alert) {
        show(text, alert ? RED : DARK);
    }

    /** Pastillita sin brillo. */
    void show(String text, int color) {
        stopGlow();
        render(text, color);
        frame.setGlow(0f);
    }

    /**
     * Pastillita con brillo violeta que sale desde el borde hacia afuera: aparece
     * con un fundido y después "respira" suavemente mientras está a la vista.
     */
    void showGlowing(String text, int color) {
        render(text, color);
        stopGlow();
        frame.setGlowColor(color);
        ValueAnimator in = ValueAnimator.ofFloat(0f, 1f);
        in.setDuration(450);
        in.setInterpolator(new DecelerateInterpolator());
        in.addUpdateListener(a -> frame.setGlow((float) a.getAnimatedValue()));
        in.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (glowAnim != in) return;
                ValueAnimator breathe = ValueAnimator.ofFloat(1f, 0.65f);
                breathe.setDuration(1100);
                breathe.setRepeatMode(ValueAnimator.REVERSE);
                breathe.setRepeatCount(ValueAnimator.INFINITE);
                breathe.addUpdateListener(b -> frame.setGlow((float) b.getAnimatedValue()));
                glowAnim = breathe;
                breathe.start();
            }
        });
        glowAnim = in;
        in.start();
    }

    void hide() {
        stopGlow();
        if (!shown) return;
        try {
            wm.removeView(frame);
        } catch (Exception ignored) {
        }
        shown = false;
    }

    private void stopGlow() {
        if (glowAnim != null) {
            ValueAnimator a = glowAnim;
            glowAnim = null;
            a.cancel();
        }
    }

    private void render(String text, int color) {
        float d = service.getResources().getDisplayMetrics().density;
        if (frame == null) {
            frame = new GlowFrame(service, GLOW_RADIUS_DP * d);
            int pad = (int) (GLOW_PAD_DP * d);
            frame.setPadding(pad, pad, pad, pad);
            pill = new TextView(service);
            pill.setTextColor(Color.WHITE);
            pill.setTextSize(13);
            int ph = (int) (12 * d), pv = (int) (5 * d);
            pill.setPadding(ph, pv, ph, pv);
            frame.addView(pill, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(100 * d);
        pill.setBackground(bg);
        pill.setText(text);

        if (!shown) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            // La pastillita queda en el mismo lugar de siempre; el margen es para el brillo.
            lp.y = statusBarHeight() + (int) (6 * d) - (int) (GLOW_PAD_DP * d);
            try {
                wm.addView(frame, lp);
                shown = true;
            } catch (Exception ignored) {
                // Si el sistema no deja mostrarla, el límite sigue funcionando igual.
            }
        }
    }

    private int statusBarHeight() {
        int id = service.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? service.getResources().getDimensionPixelSize(id) : 0;
    }

    /** Contenedor que dibuja un brillo difuso por fuera del borde de la pastillita. */
    private static final class GlowFrame extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float glow;
        private int color = VIOLET;

        GlowFrame(Context c, float radius) {
            super(c);
            setWillNotDraw(false);
            // El desenfoque necesita dibujarse por software.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            paint.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER));
            core.setMaskFilter(new BlurMaskFilter(radius * 0.45f, BlurMaskFilter.Blur.OUTER));
        }

        void setGlow(float g) {
            glow = g;
            invalidate();
        }

        void setGlowColor(int c) {
            color = c;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (glow <= 0f || getChildCount() == 0) return;
            View child = getChildAt(0);
            rect.set(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
            float r = rect.height() / 2f;
            int rgb = color & 0x00FFFFFF;
            // Halo amplio y suave…
            paint.setColor(rgb | (Math.round(255 * 0.85f * glow) << 24));
            canvas.drawRoundRect(rect, r, r, paint);
            // …y uno más intenso pegado al borde.
            core.setColor(rgb | (Math.round(255 * glow) << 24));
            canvas.drawRoundRect(rect, r, r, core);
        }
    }
}
