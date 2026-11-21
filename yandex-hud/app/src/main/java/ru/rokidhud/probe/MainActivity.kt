package ru.rokidhud.probe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private val listener = { _: NavSnapshot? -> runOnUiThread { refresh() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 35, 28, 28) }
        status = TextView(this).apply { textSize = 21f }
        val button = Button(this).apply {
            text = "Разрешить чтение уведомлений"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        root.addView(button)
        root.addView(status)
        preview = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = 140
        }
        root.addView(preview)
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onResume() { super.onResume(); NavStore.listeners.add(listener); refresh() }
    override fun onPause() { NavStore.listeners.remove(listener); super.onPause() }
    private fun refresh() {
        val n = NavStore.current
        preview.setImageBitmap(n?.samples?.firstOrNull()?.bitmap)
        status.text = if (n == null) "Ожидание уведомления навигации Яндекса.\nМаршрут запускается на телефоне." else
            "Источник: ${n.source}\nМанёвр: ${n.maneuver}\nДистанция: ${n.distance ?: "не извлечена"}\nУлица: ${n.street ?: "не извлечена"}\nТекст: ${n.rawText}"
    }
}
