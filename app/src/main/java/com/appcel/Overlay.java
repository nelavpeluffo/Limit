package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/** Pastillita flotante con el tiempo que queda. No recibe toques, así no molesta. */
final class Overlay {
    private final AccessibilityService service;
    private final WindowManager wm;
    private TextView view;
    private boolean shown;

    Overlay(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    static final int DARK = 0xCC202124;
    static final int RED = 0xE6C5221F;
    static final int VIOLET = 0xF29364FF;

    void show(String text, boolean alert) {
        show(text, alert ? RED : DARK);
    }

    void show(String text, int color) {
        float d = service.getResources().getDisplayMetrics().density;
        if (view == null) {
            view = new TextView(service);
            view.setTextColor(Color.WHITE);
            view.setTextSize(13);
            int ph = (int) (12 * d), pv = (int) (5 * d);
            view.setPadding(ph, pv, ph, pv);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(100 * d);
        view.setBackground(bg);
        view.setText(text);

        if (!shown) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = statusBarHeight() + (int) (6 * d);
            try {
                wm.addView(view, lp);
                shown = true;
            } catch (Exception ignored) {
                // Si el sistema no deja mostrarla, el límite sigue funcionando igual.
            }
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

    private int statusBarHeight() {
        int id = service.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? service.getResources().getDimensionPixelSize(id) : 0;
    }
}
