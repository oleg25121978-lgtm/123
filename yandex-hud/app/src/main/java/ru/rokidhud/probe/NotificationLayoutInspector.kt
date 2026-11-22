package ru.rokidhud.probe

import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** Inspects custom Yandex notification views without screen mirroring or recording pixels. */
object NotificationLayoutInspector {
    data class Result(val texts: List<String>, val imageResourceNames: List<String>, val samples: List<ArrowCapture.Sample> = emptyList())

    @Suppress("DEPRECATION")
    fun inspect(context: Context, notification: Notification): Result {
        val remoteViews = notification.bigContentView ?: notification.contentView
            ?: return Result(emptyList(), emptyList())
        val root = runCatching { remoteViews.apply(context, FrameLayout(context)) }.getOrNull()
            ?: return Result(emptyList(), emptyList())
        val texts = mutableListOf<String>()
        val imageNames = mutableListOf<String>()
        val samples = mutableListOf<ArrowCapture.Sample>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            val name = if (view.id != View.NO_ID)
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            else null
            if (view is TextView) view.text?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
            if (view is ImageView) {
                val imageName = name ?: "unknown_image"
                imageNames.add(imageName)
                view.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(texts::add)
                if (samples.size < 8) ArrowCapture.fromImage(view, imageName)?.let(samples::add)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return Result(texts.take(25), imageNames.take(25), samples)
    }
}
