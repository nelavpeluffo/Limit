package com.appcel;

/** Qué contenido "limitado" está en pantalla. */
enum Kind {
    NONE(null, null),
    REELS("reels_v2", "Reels, TikTok y Shorts"),   // los tres comparten los 15 minutos
    FOR_YOU("x_foryou_v2", "Para ti de X");

    final String key;
    final String label;

    Kind(String key, String label) {
        this.key = key;
        this.label = label;
    }
}
