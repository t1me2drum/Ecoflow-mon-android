package app.powerhub.protocol

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** JSON framing used by the app-side MQTT API for Delta 2 family and Delta Pro 3 commands. */
object JsonMessages {
    fun seq(): Int = 999_900_000 + Random.nextInt(10_000, 99_999)

    fun command(
        moduleType: Int,
        operateType: String,
        params: Map<String, Any>,
        moduleSn: String? = null,
        version: String = "1.0",
    ): Outgoing {
        val json = JSONObject()
            .put("from", "Android")
            .put("id", seq().toString())
            .put("version", version)
            .put("moduleType", moduleType)
            .put("operateType", operateType)
            .put("params", JSONObject(params))
        if (moduleSn != null) json.put("moduleSn", moduleSn)
        return Outgoing(json.toString().toByteArray())
    }

    fun latestQuotas(): Outgoing = command(0, "latestQuotas", emptyMap(), version = "1.1")

    // Protobuf frames start with 0x0A ('\n'), so no whitespace skipping here.
    fun looksLikeJson(payload: ByteArray): Boolean =
        payload.isNotEmpty() && payload[0] == '{'.code.toByte()

    /**
     * Data topic: `{"params": {"pd.soc": 80, ...}}`.
     * Get reply: `{"operateType": "latestQuotas", "data": {"online": 1, "quotaMap": {...}}}`.
     */
    fun parse(payload: ByteArray): Params {
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return emptyMap()
        if (json.optString("operateType") == "latestQuotas") {
            val data = json.optJSONObject("data") ?: return emptyMap()
            if (data.optInt("online", 0) != 1) return emptyMap()
            return flatten(data.optJSONObject("quotaMap") ?: return emptyMap())
        }
        val params = json.optJSONObject("params") ?: return emptyMap()
        return flatten(params)
    }

    fun flatten(obj: JSONObject, prefix: String = "", out: MutableMap<String, Any?> = HashMap()): MutableMap<String, Any?> {
        for (key in obj.keys()) {
            val full = if (prefix.isEmpty()) key else "$prefix.$key"
            when (val v = obj.opt(key)) {
                is JSONObject -> flatten(v, full, out)
                is JSONArray -> out[full] = List(v.length()) { v.opt(it) }
                JSONObject.NULL -> out[full] = null
                else -> out[full] = v
            }
        }
        return out
    }
}
