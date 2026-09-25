package app.powerhub.protocol

typealias Params = Map<String, Any?>

enum class DeviceModel(val title: String) {
    DELTA_2("Delta 2"),
    DELTA_2_MAX("Delta 2 Max"),
    DELTA_3("Delta 3"),
    DELTA_3_MAX("Delta 3 Max"),
    DELTA_PRO_3("Delta Pro 3"),
    DELTA_MAX("Delta Max"),
    RIVER_2_MAX("River 2 Max");

    val protocol: DeviceProtocol
        get() = when (this) {
            DELTA_2 -> Delta2Protocol
            DELTA_2_MAX -> Delta2MaxProtocol
            DELTA_3 -> Delta3Protocol.standard
            DELTA_3_MAX -> Delta3Protocol.max
            DELTA_PRO_3 -> DeltaPro3Protocol
            DELTA_MAX -> DeltaMaxProtocol
            RIVER_2_MAX -> River2MaxProtocol
        }

    companion object {
        /**
         * Maps a station from the account list to a supported model. The cloud omits
         * productName for some models (e.g. Delta 3), so the serial-number prefix is checked first.
         */
        fun detect(sn: String, productName: String?): DeviceModel? {
            val bySn = when {
                sn.startsWith("R331") -> DELTA_2
                sn.startsWith("R351") -> DELTA_2_MAX
                sn.startsWith("MR51") -> DELTA_PRO_3
                sn.startsWith("P231") -> DELTA_3
                sn.startsWith("R611") -> RIVER_2_MAX
                sn.startsWith("DA") -> DELTA_MAX
                else -> null
            }
            if (bySn != null) return bySn
            return when (productName?.trim()?.uppercase()) {
                "DELTA 2" -> DELTA_2
                "DELTA 2 MAX" -> DELTA_2_MAX
                "DELTA 3", "DELTA 3 PLUS" -> DELTA_3
                "DELTA 3 MAX", "DELTA 3 MAX PLUS" -> DELTA_3_MAX
                "DELTA PRO 3" -> DELTA_PRO_3
                "DELTA MAX" -> DELTA_MAX
                "RIVER 2 MAX" -> RIVER_2_MAX
                else -> null
            }
        }
    }
}

/**
 * [imported]: came from the account list and is removed when it disappears from it.
 * [customName]: renamed in this app, so account sync keeps the local name.
 */
data class Device(
    val sn: String,
    val name: String,
    val model: DeviceModel,
    val imported: Boolean = false,
    val customName: Boolean = false,
)

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
