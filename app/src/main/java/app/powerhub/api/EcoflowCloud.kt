package app.powerhub.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EcoflowException(message: String, val isAuthError: Boolean = false) : Exception(message)

data class Session(val token: String, val userId: String, val userName: String)

data class MqttCredentials(val host: String, val port: Int, val username: String, val password: String)

/**
 * The same REST endpoints the official mobile app uses (unofficial, may change without notice):
 *  - POST /auth/login                 → token + userId
 *  - GET  /iot-auth/app/certification → MQTT broker credentials
 */
class EcoflowCloud(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun login(host: String, email: String, password: String): Session = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", email)
            .put("password", Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP))
            .put("scene", "IOT_APP")
            .put("userType", "ECOFLOW")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://$host/auth/login")
            .header("lang", "en_US")
            .post(body)
            .build()
        val data = execute(request, authCall = true)
        val user = data.optJSONObject("user") ?: throw EcoflowException("Сервер не повернув дані користувача")
        Session(
            token = data.getString("token"),
            userId = user.get("userId").toString(),
            userName = user.optString("name", ""),
        )
    }

    suspend fun mqttCredentials(host: String, session: Session): MqttCredentials = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(host)
            .addPathSegments("iot-auth/app/certification")
            .addQueryParameter("userId", session.userId)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("lang", "en_US")
            .header("authorization", "Bearer ${session.token}")
            .get()
            .build()
        val data = execute(request, authCall = false)
        MqttCredentials(
            host = data.getString("url"),
            port = data.get("port").toString().toInt(),
            username = data.getString("certificateAccount"),
            password = data.getString("certificatePassword"),
        )
    }

    private fun execute(request: Request, authCall: Boolean): JSONObject {
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw EcoflowException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val message = json.optString("message")
            if (!message.equals("success", ignoreCase = true)) {
                // Any coded error from /auth/login means the credentials were rejected.
                throw EcoflowException(message.ifBlank { "Помилка ${json.opt("code")}" }, isAuthError = authCall)
            }
            return json.optJSONObject("data") ?: throw EcoflowException("Порожня відповідь сервера")
        }
    }
}
