package ru.rokidhud.probe

import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusGuidanceGlyphs
import com.anezium.rokidbus.client.plugin.NexusGuidancePlan
import com.anezium.rokidbus.client.plugin.NexusGuidancePlanner
import com.anezium.rokidbus.client.plugin.NexusGuidanceStep
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageRequest
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.shared.PageBody

/** An opt-in, independent Nexus plugin. No screen mirroring, no replacement of built-in navigation. */
class YandexHudNexusService : NexusPluginService() {
    override fun onNexusOpen() = Unit
    override fun onNexusClose() = Unit
    override fun onNexusInput(event: NexusInputEvent) = Unit

    private val handler = Handler(Looper.getMainLooper())
    private val planner = NexusGuidancePlanner()
    private var pageRevision = 1L
    private val listener: (NavSnapshot?) -> Unit = { snapshot ->
        handler.post { if (snapshot == null) clearRoute() else publishRoute(snapshot) }
    }
    override fun onCreate() {
        super.onCreate()
        NavStore.listeners.add(listener)
    }
    override fun onDestroy() {
        NavStore.listeners.remove(listener)
        super.onDestroy()
    }
    override fun onNexusPageRequest(request: NexusPageRequest): NexusPage {
        val s = NavStore.current
        val title = "Яндекс HUD"
        val summary = if (s == null) "Маршрут не получен" else
            "${s.distance ?: "Расстояние неизвестно"} · ${s.maneuver}"
        return NexusPage.Content(
            revision = maxOf(++pageRevision, (request.minimumRevision ?: 0L) + 1L),
            title = title,
            body = PageBody.Summary(primary = summary, text = s?.rawText ?: "Запустите маршрут в Яндекс Картах на телефоне.")
        )
    }
    override fun onNexusRegistrationState(result: Int) {
        // Route may already be visible when Nexus connects.
        if (result == PluginRegistrationResult.APPROVED) {
            NavStore.current?.let { handler.post { publishRoute(it) } }
        } else {
            planner.reset()
        }
    }
    override fun onNexusActivityClosed(reason: String) { planner.reset() }
    private fun clearRoute() {
        planner.reset()
        nexusClient?.endActivity()
    }
    private fun publishRoute(snapshot: NavSnapshot) {
        val client = nexusClient ?: return
        if (!client.supportsActivitySurface) return
        val glyph = when (snapshot.maneuver) {
            Maneuver.LEFT -> NexusGuidanceGlyphs.TURN_LEFT
            Maneuver.RIGHT -> NexusGuidanceGlyphs.TURN_RIGHT
            Maneuver.SLIGHT_LEFT -> NexusGuidanceGlyphs.TURN_SLIGHT_LEFT
            Maneuver.SLIGHT_RIGHT -> NexusGuidanceGlyphs.TURN_SLIGHT_RIGHT
            Maneuver.UTURN -> NexusGuidanceGlyphs.U_TURN
            Maneuver.STRAIGHT -> NexusGuidanceGlyphs.STRAIGHT
            Maneuver.ROUNDABOUT -> NexusGuidanceGlyphs.ROUNDABOUT
            Maneuver.ARRIVE -> NexusGuidanceGlyphs.ARRIVE
            Maneuver.UNKNOWN -> "route"
        }
        val step = NexusGuidanceStep(
            glyph = glyph,
            primary = snapshot.distance ?: "Маршрут",
            secondary = snapshot.street,
            badge = "YHUD",
            stepKey = "${snapshot.source}|${snapshot.street}|${snapshot.maneuver}",
            imminent = false
        )
        when (val plan = planner.plan(step)) {
            is NexusGuidancePlan.Start -> client.startActivity(plan.step.toActivity())
            is NexusGuidancePlan.Update -> client.updateActivity(
                plan.step.toActivity(), significant = plan.significant,
                urgent = plan.urgent && client.supportsActivityExtras
            )
            NexusGuidancePlan.Unchanged -> Unit
        }
    }
}
