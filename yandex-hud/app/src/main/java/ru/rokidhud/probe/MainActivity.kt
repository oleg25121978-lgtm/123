package ru.rokidhud.probe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Bench diagnostics: lets the operator inspect *each* ImageView. The first image can
 * be a logo, traffic indicator or lane pictogram, not the turn arrow.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var imageStatus: TextView
    private lateinit var preview: ImageView
    private lateinit var labelButtons: LinearLayout
    private lateinit var imageControls: LinearLayout
    private val listener: (NavSnapshot?) -> Unit = { runOnUiThread { refresh() } }
    private var selectedImage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 35, 28, 28)
        }
        root.addView(Button(this).apply {
            text = "Разрешить чтение уведомлений"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        status = TextView(this).apply { textSize = 18f }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "Изображения Яндекса. Выбери именно стрелку поворота, а не полосы или логотип."
            textSize = 16f
        })
        imageControls = LinearLayout(this)
        imageControls.addView(Button(this).apply {
            text = "◀ Предыдущее"
            setOnClickListener { selectedImage = (selectedImage - 1).coerceAtLeast(0); refresh() }
        })
        imageControls.addView(Button(this).apply {
            text = "Следующее ▶"
            setOnClickListener {
                val count = NavStore.current?.samples?.size ?: 0
                selectedImage = (selectedImage + 1).coerceAtMost((count - 1).coerceAtLeast(0))
                refresh()
            }
        })
        root.addView(imageControls)
        imageStatus = TextView(this).apply { textSize = 15f }
        root.addView(imageStatus)
        preview = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = 160
        }
        root.addView(preview)
        root.addView(TextView(this).apply { text = "Отмечать только на стоянке, после визуальной проверки изображения:" })
        labelButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((title, kind) in listOf(
            "← Налево" to Maneuver.LEFT,
            "→ Направо" to Maneuver.RIGHT,
            "↑ Прямо" to Maneuver.STRAIGHT,
            "↶ Разворот" to Maneuver.UTURN,
            "↖ Левее" to Maneuver.SLIGHT_LEFT,
            "↗ Правее" to Maneuver.SLIGHT_RIGHT,
            "↻ Кольцо" to Maneuver.ROUNDABOUT
        )) {
            labelButtons.addView(Button(this).apply {
                text = title
                setOnClickListener {
                    val current = NavStore.current ?: return@setOnClickListener
                    val sample = current.samples.getOrNull(selectedImage) ?: return@setOnClickListener
                    ArrowLabels.put(this@MainActivity, sample, selectedImage, kind)
                    val resolved = ArrowLabels.resolve(this@MainActivity, current.samples)
                    // Forward the corrected guidance immediately to Nexus; don't wait for
                    // Yandex to post another notification after calibration.
                    NavStore.publish(current.copy(maneuver = resolved))
                    refresh()
                }
            })
        }
        root.addView(labelButtons)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        NavStore.listeners.add(listener)
        refresh()
    }

    override fun onPause() {
        NavStore.listeners.remove(listener)
        super.onPause()
    }

    private fun refresh() {
        val n = NavStore.current
        val count = n?.samples?.size ?: 0
        selectedImage = selectedImage.coerceIn(0, (count - 1).coerceAtLeast(0))
        val sample = n?.samples?.getOrNull(selectedImage)
        preview.setImageBitmap(sample?.bitmap)
        imageControls.visibility = if (count > 1) View.VISIBLE else View.GONE
        labelButtons.visibility = if (sample != null) View.VISIBLE else View.GONE
        imageStatus.text = if (sample == null) "Картинки не найдены" else
            "Изображение ${selectedImage + 1} из $count · ${sample.id}\n" +
                "SHA: ${sample.digest.take(16)}…\n" +
                "Метка: ${ArrowLabels.get(this, sample.digest) ?: "не размечено"}"
        status.text = if (n == null) "Ожидание уведомления Яндекса. Запусти маршрут на телефоне." else
            "Источник: ${n.source}\nМанёвр: ${n.maneuver}\nДо поворота: ${n.distance ?: "не получено"}" +
                "\nУлица: ${n.street ?: "не получена"}\nИзображений: $count\nУведомление: ${n.rawText}"
    }
}
