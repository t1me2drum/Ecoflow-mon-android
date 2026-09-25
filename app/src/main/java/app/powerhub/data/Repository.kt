package app.powerhub.data

import android.util.Log
import app.powerhub.api.EcoflowCloud
import app.powerhub.api.EcoflowException
import app.powerhub.api.EcoflowOpenApi
import app.powerhub.api.MqttLink
import app.powerhub.api.Session
import app.powerhub.protocol.Control
import app.powerhub.protocol.Device
import app.powerhub.protocol.DeviceState
import app.powerhub.protocol.Params
import app.powerhub.protocol.TopicKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Reconnecting(val reason: String) : ConnState
    data class Failed(val reason: String, val authError: Boolean) : ConnState
}

data class DeviceSnapshot(
    val params: Params = emptyMap(),
    val lastSeen: Long = 0L,
) {
    fun isOnline(now: Long = System.currentTimeMillis()) = lastSeen > 0 && now - lastSeen < OFFLINE_AFTER_MS

    companion object {
        const val OFFLINE_AFTER_MS = 3 * 60_000L
    }
}

/**
 * Owns the cloud session and the MQTT link, and keeps the latest params of every device.
 * Everything UI/service-facing is exposed as StateFlows.
 */
class Repository(
    private val credentials: CredentialStore,
    val settings: SettingsStore,
    val history: HistoryDb,
    private val cloud: EcoflowCloud = EcoflowCloud(),
    private val openApi: EcoflowOpenApi = EcoflowOpenApi(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle)
    val connection: StateFlow<ConnState> = _conn.asStateFlow()

    private val _snapshots = MutableStateFlow<Map<String, DeviceSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, DeviceSnapshot>> = _snapshots.asStateFlow()

    @Volatile private var session: Session? = null
    @Volatile private var link: MqttLink? = null
    private var runJob: Job? = null
    private var subscribedSns = emptySet<String>()

    val isLoggedIn: Boolean get() = credentials.load() != null

    /** Validates credentials against the cloud, then stores them. */
    suspend fun login(c: Credentials) {
        cloud.login(c.apiHost, c.email, c.password)
        credentials.save(c)
    }

    val hasDeveloperKeys: Boolean get() = credentials.loadKeys() != null

    fun developerKeys(): DeveloperKeys? = credentials.loadKeys()

    /** Checks the keys against the cloud by syncing once; stores them only if that succeeds. */
    suspend fun saveDeveloperKeys(keys: DeveloperKeys): SyncResult {
        val result = syncStations(keys)
        credentials.saveKeys(keys)
        return result
    }

    fun clearDeveloperKeys() = credentials.saveKeys(null)

    /** Pulls the account's station list from the official Developer API. */
    suspend fun syncStations(keys: DeveloperKeys? = credentials.loadKeys()): SyncResult {
        keys ?: throw EcoflowException("Ключі Developer API не задано")
        val host = credentials.load()?.apiHost ?: API_HOSTS[0].first
        val cloudDevices = openApi.listDevices(keys.accessKey, keys.secretKey, host)
        val unsupported = ArrayList<String>()
        val supported = cloudDevices.mapNotNull { c ->
            val model = app.powerhub.protocol.DeviceModel.detect(c.sn, c.productName)
            if (model == null) unsupported += "${c.name} (${c.productName ?: c.sn})"
            model?.let { Device(c.sn, c.name, it, imported = true) }
        }
        return settings.applyCloudList(supported).copy(unsupported = unsupported)
    }

    fun logout() {
        stop()
        credentials.clear()
        _snapshots.value = emptyMap()
        _conn.value = ConnState.Idle
    }

    @Synchronized
    fun start() {
        val finished = _conn.value is ConnState.Failed || _conn.value is ConnState.Idle
        if (runJob?.isActive == true && !finished) return
        stop()
        runJob = scope.launch {
            launch { settings.devices.collect { syncSubscriptions(it) } }
            if (credentials.loadKeys() != null) {
                launch { runCatching { syncStations() }.onFailure { Log.w(TAG, "station sync failed", it) } }
            }
            connectLoop()
        }
    }

    @Synchronized
    fun stop() {
        runJob?.cancel()
        runJob = null
        session = null
        link?.close()
        link = null
        subscribedSns = emptySet()
    }

    private suspend fun connectLoop() {
        var backoff = 5_000L
        while (true) {
            val c = credentials.load() ?: run { _conn.value = ConnState.Idle; return }
            _conn.value = ConnState.Connecting
            try {
                val s = cloud.login(c.apiHost, c.email, c.password)
                val mqtt = cloud.mqttCredentials(c.apiHost, s)
                session = s
                val failure = CompletableSignal()
                val newLink = MqttLink(
                    mqtt, s.userId,
                    onMessage = ::onMessage,
                    onConnected = { ok ->
                        _conn.value = if (ok) ConnState.Connected else ConnState.Reconnecting("Зв'язок втрачено")
                        if (ok) requestAllQuotas()
                    },
                    onFatal = { failure.fire(it) },
                )
                link = newLink
                subscribedSns = settings.devices.value.map { it.sn }.toSet()
                newLink.connect(subscribedSns.flatMap { topicsFor(s, it) })
                backoff = 5_000L
                // Paho reconnects by itself once connected; only a failed first connect comes back here.
                val reason = failure.await()
                newLink.close()
                link = null
                _conn.value = ConnState.Reconnecting(reason)
            } catch (e: EcoflowException) {
                if (e.isAuthError) {
                    _conn.value = ConnState.Failed(e.message ?: "Помилка входу", authError = true)
                    return
                }
                _conn.value = ConnState.Reconnecting(e.message ?: "Помилка")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                _conn.value = ConnState.Reconnecting(e.message ?: e.javaClass.simpleName)
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
        }
    }

    private fun syncSubscriptions(devices: List<Device>) {
        val s = session ?: return
        val l = link ?: return
        val now = devices.map { it.sn }.toSet()
        val added = now - subscribedSns
        val removed = subscribedSns - now
        l.subscribe(added.flatMap { topicsFor(s, it) })
        l.unsubscribe(removed.flatMap { topicsFor(s, it) })
        subscribedSns = now
        added.forEach { sn -> devices.find { it.sn == sn }?.let(::requestQuota) }
        if (removed.isNotEmpty()) _snapshots.update { it - removed }
    }

    private fun dataTopic(sn: String) = "/app/device/property/$sn"
    private fun thingTopic(s: Session, sn: String, suffix: String) = "/app/${s.userId}/$sn/thing/property/$suffix"

    private fun topicsFor(s: Session, sn: String) = listOf(
        dataTopic(sn),
        thingTopic(s, sn, "get_reply"),
        thingTopic(s, sn, "set_reply"),
    )

    private fun onMessage(topic: String, payload: ByteArray) {
        val (sn, kind) = when {
            topic.startsWith("/app/device/property/") -> topic.substringAfterLast('/') to TopicKind.DATA
            topic.endsWith("/get_reply") -> topic.split('/')[3] to TopicKind.GET_REPLY
            topic.endsWith("/set_reply") -> topic.split('/')[3] to TopicKind.SET_REPLY
            else -> return
        }
        val device = settings.devices.value.find { it.sn == sn } ?: return
        val parsed = device.model.protocol.parse(kind, payload)
        _snapshots.update { all ->
            val old = all[sn] ?: DeviceSnapshot()
            val params = if (parsed.isEmpty()) old.params else old.params + parsed
            // Any traffic from the device proves it is online, even frames we can't decode.
            all + (sn to DeviceSnapshot(params, System.currentTimeMillis()))
        }
    }

    fun state(device: Device): DeviceState =
        device.model.protocol.state(_snapshots.value[device.sn]?.params.orEmpty())

    fun requestQuota(device: Device) {
        val s = session ?: return
        link?.publish(thingTopic(s, device.sn, "get"), device.model.protocol.quotaRequest(device.sn).payload)
    }

    fun requestAllQuotas() = settings.devices.value.forEach(::requestQuota)

    /** Sends a control change; local state updates optimistically until the device reports back. */
    fun send(device: Device, publish: (Params) -> Pair<ByteArray, Params>): Boolean {
        val s = session ?: return false
        val l = link ?: return false
        val current = _snapshots.value[device.sn]?.params.orEmpty()
        val (payload, optimistic) = publish(current)
        val ok = l.publish(thingTopic(s, device.sn, "set"), payload)
        if (ok) {
            _snapshots.update { all ->
                val old = all[device.sn] ?: DeviceSnapshot()
                all + (device.sn to old.copy(params = old.params + optimistic))
            }
        }
        return ok
    }

    fun toggle(device: Device, c: Control.Toggle, on: Boolean) =
        send(device) { p -> c.command(on, p).payload to c.optimistic(on) }

    fun setValue(device: Device, c: Control.Slider, v: Int) =
        send(device) { p -> c.command(v, p).payload to c.optimistic(v) }

    fun choose(device: Device, c: Control.Choice, v: Int) =
        send(device) { p -> c.command(v, p).payload to c.optimistic(v) }

    private class CompletableSignal {
        private val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        fun fire(reason: String) { deferred.complete(reason) }
        suspend fun await(): String = deferred.await()
    }

    private companion object {
        const val TAG = "Repository"
    }
}
