package com.appcel;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;

/**
 * Aviso de perfil con Reels: una pastillita violeta alargada, con candado y brillo,
 * centrada abajo de la pantalla. Queda 4 s y se va sola. No recibe toques.
 */
final class LockNotice {
    private static final long SHOW_MS = 4000;

    private final Overlay pill;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable;

    LockNotice(AccessibilityService service) {
        pill = new Overlay(service, true);
        pill.setIcon(R.drawable.ic_lock);
        hideRunnable = pill::hide;
    }

    void show() {
        handler.removeCallbacks(hideRunnable);
        pill.showGlowing("Reels bloqueados hasta mañana", Overlay.VIOLET);
        handler.postDelayed(hideRunnable, SHOW_MS);
    }

    void hide() {
        handler.removeCallbacks(hideRunnable);
        pill.hide();
    }
}
