package app.powerhub.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

data class CloudDevice(val sn: String, val name: String, val productName: String?, val online: Boolean)

/**
 * Official EcoFlow Developer API (developer.ecoflow.com), used only to list the stations bound
 * to the account. Requests are signed with HMAC-SHA256 over
 * `<sorted params>&accessKey=..&nonce=..&timestamp=..`.
 */
class EcoflowOpenApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    /** Keys are region-bound; the first host that accepts them wins. */
    suspend fun listDevices(accessKey: String, secretKey: String, preferredHost: String): List<CloudDevice> =
        withContext(Dispatchers.IO) {
            val hosts = listOf(preferredHost, "api-e.ecoflow.com", "api.ecoflow.com", "api-a.ecoflow.com").distinct()
            var lastError: EcoflowException? = null
            for (host in hosts) {
                try {
                    return@withContext fetchList(host, accessKey, secretKey)
                } catch (e: EcoflowException) {
                    lastError = e
                    if (!e.isAuthError) throw e
                }
            }
            throw lastError ?: EcoflowException("Немає доступних серверів")
        }

    private fun fetchList(host: String, accessKey: String, secretKey: String): List<CloudDevice> {
        val nonce = Random.nextInt(100_000, 999_999).toString()
        val timestamp = System.currentTimeMillis().toString()
        val sign = hmacSha256(secretKey, "accessKey=$accessKey&nonce=$nonce&timestamp=$timestamp")
        // No Content-Type on GET: the server rejects the signature of a GET that declares a JSON body.
        val request = Request.Builder()
            .url("https://$host/iot-open/sign/device/list")
            .header("accessKey", accessKey)
            .header("nonce", nonce)
            .header("timestamp", timestamp)
            .header("sign", sign)
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw EcoflowException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val code = json.optString("code")
            if (code != "0") {
                // 8513: invalid key or key issued for another region; 8521: bad signature (wrong secret).
                throw EcoflowException(
                    json.optString("message").ifBlank { "Помилка $code" },
                    isAuthError = code == "8513" || code == "8521",
                )
            }
            val arr = json.optJSONArray("data") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CloudDevice(
                    sn = o.getString("sn"),
                    name = o.optString("deviceName").trim().ifBlank { o.getString("sn") },
                    productName = o.optString("productName").takeIf { it.isNotBlank() },
                    online = o.optInt("online") == 1,
                )
            }
        }
    }

    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
