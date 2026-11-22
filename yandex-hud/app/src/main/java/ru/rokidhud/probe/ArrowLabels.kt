package ru.rokidhud.probe

import android.content.Context

/**
 * Exact user-confirmed calibration. Each image is labeled individually.
 * Never infer a maneuver from drawable resource names or unrelated notification images.
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

    fun resolve(context: Context, samples: List<ArrowCapture.Sample>): Maneuver {
        val matches = samples.mapNotNull { get(context, it.digest) }.distinct()
        return if (matches.size == 1) matches.first() else Maneuver.UNKNOWN
    }
}
