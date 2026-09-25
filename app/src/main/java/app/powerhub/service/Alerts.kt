package app.powerhub.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.powerhub.PowerHubApp
import app.powerhub.R
import app.powerhub.data.AlertSettings
import app.powerhub.protocol.Device
import app.powerhub.protocol.DeviceState
import app.powerhub.ui.MainActivity

/**
 * Edge-triggered alerts: each rule fires once when its condition becomes true,
 * and re-arms only after the condition clears (with hysteresis for battery levels).
 */
class AlertEngine(private val context: Context) {
    private class Memory {
        var lowFired = false
        var fullFired = false
        var grid: Boolean? = null
        var online: Boolean? = null
    }

    private val memory = HashMap<String, Memory>()

    fun evaluate(device: Device, state: DeviceState, online: Boolean, settings: AlertSettings) {
        val m = memory.getOrPut(device.sn) { Memory() }
        val soc = state.soc

        if (online && soc != null) {
            if (soc <= settings.lowBatteryPercent && !m.lowFired) {
                m.lowFired = true
                if (settings.lowBattery) notify(device, 1, "Низький заряд: $soc%", "${device.name} скоро розрядиться")
            } else if (soc >= settings.lowBatteryPercent + 3) {
                m.lowFired = false
            }

            if (soc >= 100 && !m.fullFired) {
                m.fullFired = true
                if (settings.fullCharge) notify(device, 2, "Повністю заряджено", "${device.name}: 100%")
            } else if (soc <= 95) {
                m.fullFired = false
            }

            val grid = state.gridConnected
            if (grid != null) {
                val prev = m.grid
                m.grid = grid
                if (prev != null && prev != grid && settings.grid) {
                    if (grid) {
                        notify(device, 3, "Живлення з'явилося", "${device.name} знову заряджається від мережі")
                    } else {
                        notify(device, 3, "Живлення зникло", "${device.name} працює від батареї, заряд $soc%")
                    }
                }
            }
        }

        val prevOnline = m.online
        m.online = online
        if (prevOnline == true && !online && settings.offline) {
            notify(device, 4, "Станція не на зв'язку", "${device.name} не надсилає дані понад 3 хв")
        }
    }

    private fun notify(device: Device, kind: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, PowerHubApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(device.sn.hashCode() * 10 + kind, n)
    }
}
