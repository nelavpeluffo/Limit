package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Zona invisible sobre el video de un Reel que te mandaron por mensaje. Reconoce los
 * deslizamientos hacia arriba o hacia abajo (para ir al Reel siguiente o al anterior
 * del chat) y todo lo demás (tocar para pausar, doble toque para "me gusta"…) se lo
 * devuelve a Instagram tal cual, repitiendo el mismo gesto.
 */
final class SwipeCatcher {
    interface Listener {
        /**
         * +1: el dedo fue hacia arriba (Reel siguiente). -1: hacia abajo (anterior).
         * En modo horizontal: +2 hacia la izquierda, -2 hacia la derecha.
         */
        void onSwipe(int dir);
    }

    private boolean horizontal;   // reconoce deslizamientos a los costados (y devuelve el resto)

    SwipeCatcher(AccessibilityService service, Listener listener, boolean horizontal) {
        this(service, listener);
        this.horizontal = horizontal;
    }

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<float[]> points = new ArrayList<>();
    private final Rect current = new Rect();
    private FrameLayout view;
    private WindowManager.LayoutParams lp;
    private boolean shown;
    private long downTime;

    SwipeCatcher(AccessibilityService service, Listener listener) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.listener = listener;
    }

    void show(Rect bounds) {
        if (shown && bounds.equals(current)) return;
        if (view == null) {
            view = new FrameLayout(service);
            view.setOnTouchListener((v, ev) -> onTouch(ev));
        }
        if (lp == null) {
            lp = new WindowManager.LayoutParams(
                    bounds.width(), bounds.height(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }
        lp.width = bounds.width();
        lp.height = bounds.height();
        lp.x = bounds.left;
        lp.y = bounds.top;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
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
        points.clear();
    }

    private boolean onTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                points.clear();
                downTime = ev.getEventTime();
                points.add(new float[]{ev.getRawX(), ev.getRawY()});
                return true;
            case MotionEvent.ACTION_MOVE:
                for (int h = 0; h < ev.getHistorySize(); h++) {
                    points.add(new float[]{ev.getHistoricalX(h) + rawOffsetX(ev), ev.getHistoricalY(h) + rawOffsetY(ev)});
                }
                points.add(new float[]{ev.getRawX(), ev.getRawY()});
                return true;
            case MotionEvent.ACTION_UP: {
                points.add(new float[]{ev.getRawX(), ev.getRawY()});
                float[] a = points.get(0), b = points.get(points.size() - 1);
                float dx = b[0] - a[0], dy = b[1] - a[1];
                float d = service.getResources().getDisplayMetrics().density;
                float min = 40 * d, slop = 20 * d;
                if (horizontal) {
                    boolean side = Math.abs(dx) > Math.abs(dy) * 1.4f && Math.abs(dx) > min;
                    if (side) {
                        points.clear();
                        listener.onSwipe(dx < 0 ? +2 : -2);
                    } else {
                        replay(new ArrayList<>(points), Math.max(1, ev.getEventTime() - downTime));
                        points.clear();
                    }
                    return true;
                }
                boolean vertical = Math.abs(dy) > Math.abs(dx) * 1.4f;
                if (vertical && Math.abs(dy) > min) {
                    // Deslizamiento hacia arriba o abajo: Reel siguiente o anterior.
                    points.clear();
                    listener.onSwipe(dy < 0 ? +1 : -1);
                } else if (vertical && Math.abs(dy) > slop) {
                    // Un deslizamiento corto hacia arriba/abajo: no se hace nada (si se le
                    // devolviera a Instagram como toque, pausaría el video).
                    points.clear();
                } else {
                    // Toque, mantener apretado o deslizar a los costados (carruseles): a Instagram.
                    replay(new ArrayList<>(points), Math.max(1, ev.getEventTime() - downTime));
                    points.clear();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                points.clear();
                return true;
        }
        return true;
    }

    private static float rawOffsetX(MotionEvent ev) {
        return ev.getRawX() - ev.getX();
    }

    private static float rawOffsetY(MotionEvent ev) {
        return ev.getRawY() - ev.getY();
    }

    /** Devuelve a Instagram el mismo gesto (toque, doble toque, mantener…), con la zona apagada. */
    private void replay(List<float[]> pts, long duration) {
        if (!shown || pts.isEmpty()) return;
        setTouchable(false);
        Path path = new Path();
        path.moveTo(pts.get(0)[0], pts.get(0)[1]);
        for (int i = 1; i < pts.size(); i++) path.lineTo(pts.get(i)[0], pts.get(i)[1]);
        long max = GestureDescription.getMaxGestureDuration();
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.min(duration, max)))
                .build();
        boolean ok = service.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                handler.postDelayed(() -> setTouchable(true), 40);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                handler.postDelayed(() -> setTouchable(true), 40);
            }
        }, null);
        if (!ok) setTouchable(true);
    }

    private void setTouchable(boolean touchable) {
        if (!shown || lp == null) return;
        if (touchable) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            wm.updateViewLayout(view, lp);
        } catch (Exception ignored) {
        }
    }
}
