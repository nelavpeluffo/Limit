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
        outer.setOnClickListener(v -> {
            if (onTap != null) onTap.run();
            else Toast.makeText(service.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        });

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
}
