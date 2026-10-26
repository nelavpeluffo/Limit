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
 * Tapa lila con un candado que se pone justo encima del botón de Reels
 * cuando ya se usaron los 15 minutos del día. Se "come" los toques, así
 * que el botón deja de funcionar hasta la medianoche.
 */
final class Blocker {
    private final AccessibilityService service;
    private final WindowManager wm;
    private FrameLayout view;
    private boolean shown;
    private final Rect current = new Rect();

    Blocker(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    void show(Rect bounds) {
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
        outer.setOnClickListener(v -> Toast.makeText(service.getApplicationContext(),
                "Reels está bloqueado hasta mañana", Toast.LENGTH_SHORT).show());

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

        ImageView lock = new ImageView(service);
        lock.setImageResource(R.drawable.ic_lock);
        int size = (int) (22 * d);
        box.addView(lock, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        return outer;
    }
}
