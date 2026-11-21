package ru.rokidhud.probe

import android.content.Context

/**
 * Exact fingerprint lookup. A direction is never guessed for a new bitmap.
 * Use for bench calibration only; do not label arrows while driving.
 */
object ArrowLabels {
    private const val PREF = "arrow_labels_v1"
    fun get(context: Context, digest: String): Maneuver? {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(digest, null)
            ?: return null
        return runCatching { Maneuver.valueOf(raw) }.getOrNull()
    }
    fun put(context: Context, digest: String, maneuver: Maneuver) {
        if (maneuver == Maneuver.UNKNOWN) return
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(digest, maneuver.name).apply()
    }
}
