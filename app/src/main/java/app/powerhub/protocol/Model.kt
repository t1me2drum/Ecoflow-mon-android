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
    /** Last name seen in the EcoFlow account; detects renames made in the official app. */
    val cloudName: String? = null,
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

enum class GridStatus { NONE, WEAK, OK }

const val DEFAULT_WEAK_GRID_VOLT = 180

/**
 * Grid present but below [weakBelowVolt]: the station typically refuses AC input at such
 * voltage, so it must not be shown as charging.
 */
fun DeviceState.gridStatus(weakBelowVolt: Int): GridStatus? = when (gridConnected) {
    null -> null
    false -> GridStatus.NONE
    true -> if (acInVolt != null && acInVolt in 1 until weakBelowVolt) GridStatus.WEAK else GridStatus.OK
}

/**
 * Charging from the grid only when AC power flows in at normal voltage and the battery is not
 * losing energy (a load larger than the grid input drains it despite the grid).
 */
fun DeviceState.chargingFromGrid(weakBelowVolt: Int): Boolean =
    gridStatus(weakBelowVolt) == GridStatus.OK && (acInW ?: 0) > 5 && batteryFlow() !is BatteryFlow.Discharging

/** What the battery is actually doing, derived from power flow rather than from remaining-time fields. */
sealed interface BatteryFlow {
    /** Battery gains energy (grid, solar or car). [minutes] null when the station reports no valid estimate. */
    data class Charging(val minutes: Int?) : BatteryFlow
    data class Discharging(val minutes: Int?) : BatteryFlow
    data object Full : BatteryFlow
    /**
     * Battery neither charging nor discharging. [loadW] is what the outputs still draw: on grid
     * the load is fed straight through (input ≈ output), so there can be a load while idle.
     */
    data class Idle(val loadW: Int) : BatteryFlow
    data object Unknown : BatteryFlow
}

/** Net power below this is treated as idle: standby draw and sensor noise. */
private const val FLOW_DEADBAND_W = 10

/**
 * Stations keep sending the last charge/discharge estimate even when the direction flips
 * (e.g. Delta Pro 3 still reports time-to-full after the grid drops), so the direction is
 * decided by input vs output power and only the matching estimate is used.
 */
fun DeviceState.batteryFlow(): BatteryFlow {
    val input = inputW
    val output = outputW
    if (input == null && output == null) return BatteryFlow.Unknown
    val net = (input ?: 0) - (output ?: 0)
    return when {
        net > FLOW_DEADBAND_W -> if ((soc ?: 0) >= 100) BatteryFlow.Full else BatteryFlow.Charging(chargeRemainMin)
        net < -FLOW_DEADBAND_W -> BatteryFlow.Discharging(dischargeRemainMin)
        (soc ?: 0) >= 100 -> BatteryFlow.Full
        else -> BatteryFlow.Idle(output ?: 0)
    }
}

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

/** Remaining-time fields report 5939 (or more) as "no estimate"; real estimates stay below it. */
fun validMinutes(v: Int?): Int? = v?.takeIf { it in 1 until 5939 }

val SCREEN_TIMEOUT_OPTIONS = listOf(
    "Ніколи" to 0, "10 с" to 10, "30 с" to 30, "1 хв" to 60, "5 хв" to 300, "30 хв" to 1800,
)

val STANDBY_OPTIONS = listOf(
    "Ніколи" to 0, "30 хв" to 30, "1 год" to 60, "2 год" to 120, "4 год" to 240,
    "6 год" to 360, "12 год" to 720, "24 год" to 1440,
)
