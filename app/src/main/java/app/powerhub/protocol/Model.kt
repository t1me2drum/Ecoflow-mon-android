package app.powerhub.protocol

typealias Params = Map<String, Any?>

enum class DeviceModel(val title: String) {
    DELTA_2("Delta 2"),
    DELTA_2_MAX("Delta 2 Max"),
    DELTA_3("Delta 3"),
    DELTA_3_MAX("Delta 3 Max"),
    DELTA_PRO_3("Delta Pro 3");

    val protocol: DeviceProtocol
        get() = when (this) {
            DELTA_2 -> Delta2Protocol
            DELTA_2_MAX -> Delta2MaxProtocol
            DELTA_3 -> Delta3Protocol.standard
            DELTA_3_MAX -> Delta3Protocol.max
            DELTA_PRO_3 -> DeltaPro3Protocol
        }
}

data class Device(val sn: String, val name: String, val model: DeviceModel)

enum class TopicKind { DATA, GET_REPLY, SET_REPLY }

/** Normalized values shown on the dashboard, widget and used by alerts. */
data class DeviceState(
    val soc: Int? = null,
    val inputW: Int? = null,
    val outputW: Int? = null,
    val acInW: Int? = null,
    val acOutW: Int? = null,
    val solarW: Int? = null,
    val dcOutW: Int? = null,
    val usbOutW: Int? = null,
    val chargeRemainMin: Int? = null,
    val dischargeRemainMin: Int? = null,
    val batteryTempC: Int? = null,
    val acInVolt: Int? = null,
    val gridConnected: Boolean? = null,
    val cycles: Int? = null,
    val soh: Int? = null,
)

/** A message ready to be published to the device's set or get topic. */
class Outgoing(val payload: ByteArray)

sealed interface Control {
    val id: String
    val label: String
    val section: Section

    enum class Section(val title: String) {
        OUTPUTS("Виходи"),
        CHARGING("Заряджання"),
        BACKUP("Резерв"),
        SYSTEM("Система"),
    }

    data class Toggle(
        override val id: String,
        override val label: String,
        override val section: Section,
        val read: (Params) -> Boolean?,
        val command: (Boolean, Params) -> Outgoing,
        /** Values merged into local state right after sending, before the device confirms. */
        val optimistic: (Boolean) -> Params,
    ) : Control

    data class Slider(
        override val id: String,
        override val label: String,
        override val section: Section,
        val min: Int,
        val max: Int,
        val step: Int,
        val unit: String,
        val read: (Params) -> Int?,
        val command: (Int, Params) -> Outgoing,
        val optimistic: (Int) -> Params,
    ) : Control

    data class Choice(
        override val id: String,
        override val label: String,
        override val section: Section,
        val options: List<Pair<String, Int>>,
        val read: (Params) -> Int?,
        val command: (Int, Params) -> Outgoing,
        val optimistic: (Int) -> Params,
    ) : Control
}

interface DeviceProtocol {
    /** Decodes an MQTT payload into flat key/value pairs to merge into the device's params. */
    fun parse(kind: TopicKind, payload: ByteArray): Params

    /** Request for a full snapshot, published to the get topic. */
    fun quotaRequest(sn: String): Outgoing

    fun state(p: Params): DeviceState

    fun controls(sn: String): List<Control>
}

// ---- shared helpers ----

fun Params.num(key: String): Double? = when (val v = this[key]) {
    is Number -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    is String -> v.toDoubleOrNull()
    else -> null
}

fun Params.int(key: String): Int? = num(key)?.let { Math.round(it).toInt() }

fun Params.absInt(key: String): Int? = num(key)?.let { Math.round(kotlin.math.abs(it)).toInt() }

fun Params.flag(key: String): Boolean? = num(key)?.let { it != 0.0 }

fun Params.sumOf(vararg keys: String, abs: Boolean = false): Int? {
    val values = keys.mapNotNull { if (abs) absInt(it) else int(it) }
    return if (values.isEmpty()) null else values.sum()
}

/** Remaining-time fields use large sentinel values (e.g. 5939) when idle. */
fun validMinutes(v: Int?): Int? = v?.takeIf { it in 1..5998 }

val SCREEN_TIMEOUT_OPTIONS = listOf(
    "Ніколи" to 0, "10 с" to 10, "30 с" to 30, "1 хв" to 60, "5 хв" to 300, "30 хв" to 1800,
)

val STANDBY_OPTIONS = listOf(
    "Ніколи" to 0, "30 хв" to 30, "1 год" to 60, "2 год" to 120, "4 год" to 240,
    "6 год" to 360, "12 год" to 720, "24 год" to 1440,
)
