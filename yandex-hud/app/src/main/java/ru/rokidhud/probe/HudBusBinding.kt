package ru.rokidhud.probe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

/** Same bindService lifecycle pattern as Rokid Nexus Navigation/NavControl.
 * The NotificationListener owns this binding only while it has live route guidance.
 */
object HudBusBinding {
    private var connection: ServiceConnection? = null
    @Synchronized
    fun hold(context: Context) {
        if (connection != null) return
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        if (runCatching {
            context.bindService(
                Intent(context, YandexHudNexusService::class.java),
                next, Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)) connection = next
    }

    @Synchronized
    fun release(context: Context) {
        val old = connection ?: return
        connection = null
        runCatching { context.unbindService(old) }
    }
}
