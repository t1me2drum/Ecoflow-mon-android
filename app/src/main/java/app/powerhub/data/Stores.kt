package app.powerhub.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.powerhub.protocol.Device
import app.powerhub.protocol.DeviceModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

val API_HOSTS = listOf(
    "api.ecoflow.com" to "Глобальний",
    "api-e.ecoflow.com" to "Європа",
    "api-a.ecoflow.com" to "Америка",
)

data class Credentials(val email: String, val password: String, val apiHost: String)

data class DeveloperKeys(val accessKey: String, val secretKey: String)

/** EcoFlow account credentials, encrypted with a key held in the Android Keystore. */
class CredentialStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): Credentials? {
        val email = prefs.getString("email", null) ?: return null
        val password = prefs.getString("password", null) ?: return null
        return Credentials(email, password, prefs.getString("host", null) ?: API_HOSTS[0].first)
    }

    fun save(c: Credentials) {
        prefs.edit().putString("email", c.email).putString("password", c.password).putString("host", c.apiHost).apply()
    }

    fun loadKeys(): DeveloperKeys? {
        val ak = prefs.getString("open_ak", null) ?: return null
        val sk = prefs.getString("open_sk", null) ?: return null
        return DeveloperKeys(ak, sk)
    }

    fun saveKeys(k: DeveloperKeys?) {
        val e = prefs.edit()
        if (k == null) e.remove("open_ak").remove("open_sk")
        else e.putString("open_ak", k.accessKey).putString("open_sk", k.secretKey)
        e.apply()
    }

    fun clear() = prefs.edit().clear().apply()
}

data class SyncResult(val added: Int, val updated: Int, val removed: Int, val unsupported: List<String> = emptyList())

data class AlertSettings(
    val lowBattery: Boolean = true,
    val lowBatteryPercent: Int = 20,
    val fullCharge: Boolean = true,
    val grid: Boolean = true,
    val offline: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _devices = MutableStateFlow(readDevices())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _alerts = MutableStateFlow(readAlerts())
    val alerts: StateFlow<AlertSettings> = _alerts.asStateFlow()

    private fun readDevices(): List<Device> {
        val arr = runCatching { JSONArray(prefs.getString("devices", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val model = runCatching { DeviceModel.valueOf(o.getString("model")) }.getOrNull() ?: return@mapNotNull null
            Device(
                o.getString("sn"), o.optString("name", o.getString("sn")), model,
                imported = o.optBoolean("imported"), customName = o.optBoolean("customName"),
            )
        }
    }

    private fun writeDevices(list: List<Device>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("sn", it.sn).put("name", it.name).put("model", it.model.name)
                    .put("imported", it.imported).put("customName", it.customName),
            )
        }
        prefs.edit().putString("devices", arr.toString()).apply()
        _devices.value = list
    }

    fun addDevice(d: Device) = writeDevices(_devices.value.filterNot { it.sn == d.sn } + d)

    /** Saves a new display order; stations missing from [sns] keep their place at the end. */
    fun reorder(sns: List<String>) {
        val bySn = _devices.value.associateBy { it.sn }
        val ordered = sns.mapNotNull { bySn[it] }
        writeDevices(ordered + _devices.value.filterNot { it.sn in sns })
    }

    fun removeDevice(sn: String) = writeDevices(_devices.value.filterNot { it.sn == sn })

    fun renameDevice(sn: String, name: String) =
        writeDevices(_devices.value.map { if (it.sn == sn) it.copy(name = name, customName = true) else it })

    /** Applies the account's station list; manually added stations are never touched. */
    fun applyCloudList(cloud: List<Device>): SyncResult {
        val current = _devices.value
        val bySn = cloud.associateBy { it.sn }
        var updated = 0
        var removed = 0
        val kept = current.mapNotNull { d ->
            val c = bySn[d.sn]
            when {
                c == null && d.imported -> { removed++; null }
                c == null -> d
                else -> {
                    val next = d.copy(
                        name = if (d.customName) d.name else c.name,
                        model = c.model,
                        imported = true,
                    )
                    if (next != d) updated++
                    next
                }
            }
        }
        val known = current.map { it.sn }.toSet()
        val added = cloud.filter { it.sn !in known }
        writeDevices(kept + added)
        return SyncResult(added.size, updated, removed)
    }

    private fun readAlerts() = AlertSettings(
        lowBattery = prefs.getBoolean("alert_low", true),
        lowBatteryPercent = prefs.getInt("alert_low_pct", 20),
        fullCharge = prefs.getBoolean("alert_full", true),
        grid = prefs.getBoolean("alert_grid", true),
        offline = prefs.getBoolean("alert_offline", true),
    )

    fun saveAlerts(a: AlertSettings) {
        prefs.edit()
            .putBoolean("alert_low", a.lowBattery)
            .putInt("alert_low_pct", a.lowBatteryPercent)
            .putBoolean("alert_full", a.fullCharge)
            .putBoolean("alert_grid", a.grid)
            .putBoolean("alert_offline", a.offline)
            .apply()
        _alerts.value = a
    }

    /** Last known summary for the home-screen widget, survives process death. */
    var widgetCache: String?
        get() = prefs.getString("widget_cache", null)
        set(v) = prefs.edit().putString("widget_cache", v).apply()
}
