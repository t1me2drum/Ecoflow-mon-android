package app.powerhub.api

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import javax.net.ssl.SSLSocketFactory

/** Thin wrapper over Paho: TLS connection to the EcoFlow broker with auto-reconnect and resubscribe. */
class MqttLink(
    private val creds: MqttCredentials,
    userId: String,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val onFatal: (String) -> Unit,
) {
    // The broker only accepts client ids shaped like the official app's.
    private val clientId = "ANDROID_${UUID.randomUUID().toString().replace("-", "").uppercase()}_$userId"
    private val client = MqttAsyncClient("ssl://${creds.host}:${creds.port}", clientId, MemoryPersistence())
    private val topics = LinkedHashSet<String>()

    fun connect(initialTopics: Collection<String>) {
        synchronized(topics) { topics.addAll(initialTopics) }
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                onConnected(true)
                resubscribe()
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "connection lost", cause)
                onConnected(false)
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                try {
                    onMessage(topic, message.payload)
                } catch (e: Exception) {
                    Log.e(TAG, "message handling failed on $topic", e)
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val options = MqttConnectOptions().apply {
            userName = creds.username
            password = creds.password.toCharArray()
            socketFactory = SSLSocketFactory.getDefault()
            isCleanSession = true
            isAutomaticReconnect = true
            keepAliveInterval = 30
            connectionTimeout = 20
        }
        client.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                val code = (exception as? MqttException)?.reasonCode
                if (code == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                    code == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
                ) {
                    onFatal("MQTT: доступ заборонено")
                } else {
                    onFatal("MQTT: ${exception?.message ?: "не вдалося підключитися"}")
                }
            }
        })
    }

    fun subscribe(newTopics: Collection<String>) {
        val added = synchronized(topics) { newTopics.filter { topics.add(it) } }
        if (added.isNotEmpty() && client.isConnected) {
            client.subscribe(added.toTypedArray(), IntArray(added.size) { 1 })
        }
    }

    fun unsubscribe(oldTopics: Collection<String>) {
        val removed = synchronized(topics) { oldTopics.filter { topics.remove(it) } }
        if (removed.isNotEmpty() && client.isConnected) client.unsubscribe(removed.toTypedArray())
    }

    private fun resubscribe() {
        val all = synchronized(topics) { topics.toList() }
        if (all.isNotEmpty()) client.subscribe(all.toTypedArray(), IntArray(all.size) { 1 })
    }

    fun publish(topic: String, payload: ByteArray): Boolean {
        if (!client.isConnected) return false
        return try {
            client.publish(topic, payload, 1, false)
            true
        } catch (e: MqttException) {
            Log.e(TAG, "publish failed", e)
            false
        }
    }

    val isConnected: Boolean get() = client.isConnected

    fun close() {
        runCatching { if (client.isConnected) client.disconnect().waitForCompletion(3000) }
        runCatching { client.close() }
    }

    private companion object {
        const val TAG = "MqttLink"
    }
}
