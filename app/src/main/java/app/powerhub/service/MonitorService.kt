package app.powerhub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.powerhub.PowerHubApp
import app.powerhub.R
import app.powerhub.data.ConnState
import app.powerhub.ui.MainActivity
import app.powerhub.widget.BatteryWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the MQTT connection alive in the background, records history once a minute,
 * raises alerts and refreshes the home-screen widget.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo get() = PowerHubApp.repo
    private lateinit var alerts: AlertEngine
    private var lastRecordedMinute = 0L
    private var lastPrune = 0L
    private var tick = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alerts = AlertEngine(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Підключення…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        repo.start()

        scope.launch {
            repo.connection.map { it::class }.distinctUntilChanged().collect { refreshNotification() }
        }
        scope.launch {
            while (isActive) {
                runCatching { onTick() }
                delay(TICK_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        repo.start()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun onTick() {
        val now = System.currentTimeMillis()
        val minute = now / 60_000L
        val devices = repo.settings.devices.value
        val snapshots = repo.snapshots.value
        val alertSettings = repo.settings.alerts.value
        val record = minute != lastRecordedMinute

        for (device in devices) {
            val snap = snapshots[device.sn]
            val online = snap?.isOnline(now) == true
            val state = repo.state(device)
            if (record && online) repo.history.insert(device.sn, minute * 60_000L, state)
            alerts.evaluate(device, state, online, alertSettings)
        }
        if (record) lastRecordedMinute = minute

        // Devices push deltas; a periodic snapshot request keeps rarely-sent fields fresh.
        if (tick++ % 10 == 0) repo.requestAllQuotas()

        if (now - lastPrune > 24 * 3600_000L) {
            repo.history.prune(now - 30L * 24 * 3600_000L)
            lastPrune = now
        }

        writeWidgetCache(now)
        BatteryWidget.refresh(this)
        refreshNotification()
    }

    private fun writeWidgetCache(now: Long) {
        val arr = JSONArray()
        for (d in repo.settings.devices.value) {
            val s = repo.state(d)
            arr.put(
                JSONObject()
                    .put("name", d.name)
                    .put("soc", s.soc ?: -1)
                    .put("in", s.inputW ?: 0)
                    .put("out", s.outputW ?: 0)
                    .put("online", repo.snapshots.value[d.sn]?.isOnline(now) == true)
                    .put("grid", s.gridConnected == true),
            )
        }
        repo.settings.widgetCache = arr.toString()
    }

    private fun summary(): String {
        val conn = repo.connection.value
        if (conn !is ConnState.Connected) {
            return when (conn) {
                is ConnState.Failed -> "Помилка: ${conn.reason}"
                is ConnState.Reconnecting -> "Перепідключення… (${conn.reason})"
                else -> "Підключення…"
            }
        }
        val devices = repo.settings.devices.value
        if (devices.isEmpty()) return "Додайте станцію в застосунку"
        return devices.joinToString(" · ") { d ->
            val s = repo.state(d)
            val soc = s.soc?.let { "$it%" } ?: "—"
            "${d.name}: $soc ↓${s.inputW ?: 0} ↑${s.outputW ?: 0} Вт"
        }
    }

    private fun refreshNotification() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(summary()))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, PowerHubApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PowerHub")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 30_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && PowerHubApp.repo.isLoggedIn) {
            MonitorService.start(context)
        }
    }
}
