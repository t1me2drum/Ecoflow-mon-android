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
import app.powerhub.data.durationText
import app.powerhub.protocol.Device
import app.powerhub.protocol.DeviceState
import app.powerhub.protocol.GridStatus
import app.powerhub.protocol.gridStatus
import app.powerhub.ui.MainActivity

/**
 * Edge-triggered alerts: each rule fires once when its condition becomes true,
 * and re-arms only after the condition clears (with hysteresis for battery levels).
 */
class AlertEngine(private val context: Context) {
    private class Memory {
        var lowFired = false
        var fullFired = false
        var grid: GridStatus? = null
        var online: Boolean? = null
        /** Last data time when the station went silent; for the "back online" duration. */
        var offlineSince: Long? = null
        /** When the grid disappeared; for the "power is back" duration. */
        var gridLostAt: Long? = null
    }

    private var cloudAlertFired = false

    private val memory = HashMap<String, Memory>()

    /**
     * [reachable] comes from [stationReachable]: `null` means PowerHub cannot tell (it is offline
     * from the cloud itself), so no reachability alert fires and the previous state is kept.
     */
    fun evaluate(device: Device, state: DeviceState, reachable: Boolean?, lastSeen: Long, now: Long, settings: AlertSettings) {
        val m = memory.getOrPut(device.sn) { Memory() }
        val soc = state.soc

        if (reachable == true && soc != null) {
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

            // 5 V hysteresis so voltage hovering at the threshold does not flap between weak and ok.
            val threshold = settings.weakGridVolt + if (m.grid == GridStatus.WEAK) 5 else 0
            val grid = state.gridStatus(threshold)
            if (grid != null) {
                val prev = m.grid
                m.grid = grid
                if (grid == GridStatus.NONE && prev != GridStatus.NONE) m.gridLostAt = now
                if (prev != null && prev != grid && settings.grid) {
                    val volt = state.acInVolt?.let { "$it В" } ?: "—"
                    when (grid) {
                        GridStatus.NONE ->
                            notify(device, 3, "Живлення зникло", "${device.name} працює від батареї, заряд $soc%")
                        GridStatus.WEAK ->
                            notify(device, 3, "Слабка мережа: $volt", "${device.name} не заряджається від мережі, заряд $soc%")
                        GridStatus.OK ->
                            if (prev == GridStatus.WEAK) {
                                notify(device, 3, "Напруга відновилася: $volt", "${device.name}: мережа в нормі, заряд $soc%")
                            } else {
                                val outage = m.gridLostAt?.let { " · не було ${durationText(now - it)}" } ?: ""
                                notify(device, 3, "Живлення з'явилося$outage", "${device.name}: мережа $volt, заряд $soc%")
                            }
                    }
                }
            }
        }

        if (reachable == null) return
        val prevOnline = m.online
        m.online = reachable
        if (prevOnline == true && !reachable) {
            m.offlineSince = lastSeen.takeIf { it > 0 } ?: now
            if (settings.offline) notify(device, 4, "Станція не на зв'язку", "${device.name} не надсилає дані понад 3 хв")
        } else if (prevOnline == false && reachable) {
            val gone = m.offlineSince?.let { " · не було ${durationText(now - it)}" } ?: ""
            m.offlineSince = null
            if (settings.offline) notify(device, 4, "Станція знову на зв'язку$gone", "${device.name}: заряд ${soc ?: "—"}%")
        }
    }

    /**
     * One alert for PowerHub's own link to the EcoFlow cloud instead of one per station.
     * [downSince] is null while connected.
     */
    fun evaluateCloud(downSince: Long?, now: Long, settings: AlertSettings) {
        if (downSince != null && now - downSince >= CLOUD_ALERT_AFTER_MS && !cloudAlertFired) {
            cloudAlertFired = true
            if (settings.offline) {
                notifyRaw(CLOUD_NOTIFICATION_ID, "PowerHub без зв'язку з хмарою", "Дані станцій не оновлюються. Перевірте інтернет на цьому пристрої.")
            }
        } else if (downSince == null && cloudAlertFired) {
            cloudAlertFired = false
            if (settings.offline) notifyRaw(CLOUD_NOTIFICATION_ID, "Зв'язок з хмарою відновлено", "Дані станцій знову оновлюються")
        }
    }

    private fun notify(device: Device, kind: Int, title: String, text: String) =
        notifyRaw(device.sn.hashCode() * 10 + kind, title, text)

    private fun notifyRaw(id: Int, title: String, text: String) {
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
        NotificationManagerCompat.from(context).notify(id, n)
    }

    private companion object {
        const val CLOUD_ALERT_AFTER_MS = 3 * 60_000L
        const val CLOUD_NOTIFICATION_ID = 9_000
    }
}
