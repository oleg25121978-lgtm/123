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
    private lateinit var labelButtons: LinearLayout
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
        root.addView(TextView(this).apply { text = "Разметка стрелки (только стоя, не за рулём):" })
        labelButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val choices = listOf(
            "← Налево" to Maneuver.LEFT,
            "→ Направо" to Maneuver.RIGHT,
            "↑ Прямо" to Maneuver.STRAIGHT,
            "↶ Разворот" to Maneuver.UTURN,
            "↖ Левее" to Maneuver.SLIGHT_LEFT,
            "↗ Правее" to Maneuver.SLIGHT_RIGHT
        )
        for ((title, kind) in choices) {
            labelButtons.addView(Button(this).apply {
                text = title
                setOnClickListener {
                    NavStore.current?.samples?.firstOrNull()?.let {
                        ArrowLabels.put(this@MainActivity, it.digest, kind)
                        refresh()
                    }
                }
            })
        }
        root.addView(labelButtons)
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onResume() { super.onResume(); NavStore.listeners.add(listener); refresh() }
    override fun onPause() { NavStore.listeners.remove(listener); super.onPause() }
    private fun refresh() {
        val n = NavStore.current
        val sample = n?.samples?.firstOrNull()
        preview.setImageBitmap(sample?.bitmap)
        labelButtons.visibility = if (sample != null) android.view.View.VISIBLE else android.view.View.GONE
        status.text = if (n == null) "Ожидание уведомления навигации Яндекса.\nМаршрут запускается на телефоне." else
            "Источник: ${n.source}\nМанёвр: ${n.maneuver}\nДистанция: ${n.distance ?: "не извлечена"}\nУлица: ${n.street ?: "не извлечена"}\nТекст: ${n.rawText}"
    }
}
