package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Cortina oscura que tapa toda la pantalla por un instante, para que al ir al
 * buscador de Instagram no se llegue a ver la grilla de publicaciones de la lupa.
 * No recibe toques.
 */
final class Cover {
    private final AccessibilityService service;
    private final WindowManager wm;
    private View view;
    private boolean shown;

    Cover(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    void show() {
        if (shown) return;
        if (view == null) {
            view = new View(service);
            view.setBackgroundColor(0xFF000000);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(view, lp);
            shown = true;
        } catch (Exception ignored) {
        }
    }

    void hide() {
        if (!shown) return;
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        }
        shown = false;
    }
}
