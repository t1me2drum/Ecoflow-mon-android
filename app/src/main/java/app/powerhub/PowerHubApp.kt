package app.powerhub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.powerhub.data.CredentialStore
import app.powerhub.data.HistoryDb
import app.powerhub.data.Repository
import app.powerhub.data.SettingsStore

class PowerHubApp : Application() {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = Repository(CredentialStore(this), SettingsStore(this), HistoryDb(this))

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Фоновий моніторинг", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Сповіщення станції", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_ALERTS = "alerts"

        lateinit var instance: PowerHubApp
            private set

        val repo: Repository get() = instance.repository
    }
}
