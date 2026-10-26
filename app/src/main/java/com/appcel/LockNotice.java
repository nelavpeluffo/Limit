package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Aviso chiquito: un globo violeta con un candado abajo de la pantalla, que aparece
 * y se va solo. No recibe toques.
 */
final class LockNotice {
    private static final long SHOW_MS = 2200;

    private final AccessibilityService service;
    private final WindowManager wm;
    private FrameLayout view;
    private boolean shown;
    private final Runnable hideRunnable = this::fadeOut;

    LockNotice(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    void show() {
        float d = service.getResources().getDisplayMetrics().density;
        if (view == null) view = build(d);
        view.removeCallbacks(hideRunnable);
        view.animate().cancel();
        if (!shown) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = (int) (96 * d);   // por encima de la barra de abajo de Instagram
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            try {
                view.setAlpha(0f);
                view.setTranslationY(8 * d);
                wm.addView(view, lp);
                shown = true;
            } catch (Exception ignored) {
                return;
            }
        }
        view.animate().alpha(1f).translationY(0f).setDuration(180).start();
        view.postDelayed(hideRunnable, SHOW_MS);
    }

    void hide() {
        if (view != null) {
            view.removeCallbacks(hideRunnable);
            view.animate().cancel();
        }
        if (!shown) return;
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        }
        shown = false;
    }

    private void fadeOut() {
        if (!shown) return;
        view.animate().alpha(0f).setDuration(250).withEndAction(this::hide).start();
    }

    private FrameLayout build(float d) {
        FrameLayout pill = new FrameLayout(service);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Overlay.VIOLET);
        bg.setCornerRadius(100 * d);
        pill.setBackground(bg);
        pill.setPadding((int) (22 * d), (int) (10 * d), (int) (22 * d), (int) (10 * d));
        pill.setElevation(6 * d);
        ImageView icon = new ImageView(service);
        icon.setImageResource(R.drawable.ic_lock);
        int size = (int) (24 * d);
        pill.addView(icon, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        FrameLayout outer = new FrameLayout(service);
        int m = (int) (10 * d);   // lugar para la sombra
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        plp.setMargins(m, m, m, m);
        outer.addView(pill, plp);
        return outer;
    }
}
