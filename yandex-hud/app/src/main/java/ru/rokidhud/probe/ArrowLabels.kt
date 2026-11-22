package ru.rokidhud.probe

import android.content.Context

/**
 * Turn-image calibration by selected image slot and exact pixel SHA-256.
 * A Yandex notification may contain lane arrows, logos and traffic widgets:
 * do not mistake their symbols for the active maneuver.
 */
object ArrowLabels {
    private const val PREF = "arrow_labels_v2"
    private const val TARGET_IMAGE_NAME = "target_image_name"
    private const val TARGET_IMAGE_POSITION = "target_image_position"

    fun get(context: Context, digest: String): Maneuver? {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(digest, null)
            ?: return null
        return runCatching { Maneuver.valueOf(raw) }.getOrNull()
    }

    fun put(context: Context, sample: ArrowCapture.Sample, position: Int, maneuver: Maneuver) {
        if (maneuver == Maneuver.UNKNOWN) return
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(sample.digest, maneuver.name)
            .putString(TARGET_IMAGE_NAME, sample.id)
            .putInt(TARGET_IMAGE_POSITION, position)
            .apply()
    }

    fun resolve(context: Context, samples: List<ArrowCapture.Sample>): Maneuver {
        val preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val position = preferences.getInt(TARGET_IMAGE_POSITION, -1)
        val name = preferences.getString(TARGET_IMAGE_NAME, null) ?: return Maneuver.UNKNOWN
        if (position < 0) return Maneuver.UNKNOWN

        // If IDs are unique, bind to their ID. For repeated/no IDs, bind to image order.
        val sameId = samples.filter { it.id == name }
        val candidate = if (name != "unknown_image" && sameId.size == 1) {
            sameId.single()
        } else {
            samples.getOrNull(position)?.takeIf { it.id == name }
        }
        return candidate?.let { get(context, it.digest) } ?: Maneuver.UNKNOWN
    }
}
