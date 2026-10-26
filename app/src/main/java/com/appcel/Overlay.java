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

    private static final int GLOW_PAD_DP = 62;   // espacio alrededor para que entre el brillo
    private static final int GLOW_RADIUS_DP = 42; // el triple que antes (14)

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
                ValueAnimator breathe = ValueAnimator.ofFloat(1f, 0.8f);
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
            // Margen solo arriba y abajo: a los costados la ventana ocupa todo el ancho
            // de la pantalla, así el texto no se achica ni se parte en dos líneas.
            int pad = (int) (GLOW_PAD_DP * d);
            frame.setPadding(0, pad, 0, pad);
            frame.setClipToPadding(false);
            frame.setClipChildren(false);
            pill = new TextView(service);
            pill.setTextColor(Color.WHITE);
            pill.setTextSize(13);
            pill.setSingleLine(true);
            pill.setMaxLines(1);
            pill.setEllipsize(android.text.TextUtils.TruncateAt.END);
            int ph = (int) (12 * d), pv = (int) (5 * d);
            pill.setPadding(ph, pv, ph, pv);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL);
            int side = (int) (16 * d);
            plp.leftMargin = side;
            plp.rightMargin = side;
            frame.addView(pill, plp);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(100 * d);
        pill.setBackground(bg);
        pill.setText(text);

        if (!shown) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
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

    /**
     * Contenedor que dibuja un brillo difuso por fuera del borde de la pastillita:
     * un halo amplio, uno intermedio más claro y uno pegado al borde casi blanco,
     * para que se note sobre cualquier fondo.
     */
    private static final class GlowFrame extends FrameLayout {
        private final Paint wide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float glow;
        private int color = VIOLET;

        GlowFrame(Context c, float radius) {
            super(c);
            setWillNotDraw(false);
            // El desenfoque necesita dibujarse por software.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            wide.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER));
            mid.setMaskFilter(new BlurMaskFilter(radius * 0.5f, BlurMaskFilter.Blur.OUTER));
            core.setMaskFilter(new BlurMaskFilter(radius * 0.2f, BlurMaskFilter.Blur.OUTER));
        }

        void setGlow(float g) {
            glow = g;
            invalidate();
        }

        void setGlowColor(int c) {
            color = c;
        }

        private static int lighten(int c, float f) {
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            r = Math.round(r + (255 - r) * f);
            g = Math.round(g + (255 - g) * f);
            b = Math.round(b + (255 - b) * f);
            return (r << 16) | (g << 8) | b;
        }

        private static int withAlpha(int rgb, float a) {
            return (Math.round(255 * Math.max(0f, Math.min(1f, a))) << 24) | (rgb & 0x00FFFFFF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (glow <= 0f || getChildCount() == 0) return;
            View child = getChildAt(0);
            rect.set(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
            float r = rect.height() / 2f;
            int base = color & 0x00FFFFFF;
            // Todo en violeta: la intensidad sale de superponer capas, no de aclarar
            // el color (si no, el brillo se ve blanco).
            wide.setColor(withAlpha(base, glow));
            canvas.drawRoundRect(rect, r, r, wide);
            canvas.drawRoundRect(rect, r, r, wide);
            canvas.drawRoundRect(rect, r, r, wide);
            mid.setColor(withAlpha(base, glow));
            canvas.drawRoundRect(rect, r, r, mid);
            canvas.drawRoundRect(rect, r, r, mid);
            core.setColor(withAlpha(base, glow));
            canvas.drawRoundRect(rect, r, r, core);
        }
    }
}
