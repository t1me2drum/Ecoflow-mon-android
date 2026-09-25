package app.powerhub.protocol

import app.powerhub.protocol.Control.Section

/**
 * Delta 2 and Delta 2 Max speak JSON on the app MQTT API.
 * Key names and commands follow tolwi/hassio-ecoflow-cloud (internal/delta2.py, delta2_max.py).
 */
abstract class Delta2Family : DeviceProtocol {
    override fun parse(kind: TopicKind, payload: ByteArray): Params = when (kind) {
        TopicKind.DATA, TopicKind.GET_REPLY -> JsonMessages.parse(payload)
        TopicKind.SET_REPLY -> emptyMap()
    }

    override fun quotaRequest(sn: String): Outgoing = JsonMessages.latestQuotas()

    protected abstract val solarKeys: Array<String>

    override fun state(p: Params): DeviceState {
        val acInVolt = p.num("inv.acInVol")?.let { (it / 1000).toInt() }
        val acIn = p.int("inv.inputWatts")
        return DeviceState(
            soc = p.int("bms_emsStatus.lcdShowSoc") ?: p.int("bms_bmsStatus.soc"),
            inputW = p.int("pd.wattsInSum"),
            outputW = p.int("pd.wattsOutSum"),
            acInW = acIn,
            acOutW = p.int("inv.outputWatts"),
            solarW = p.sumOf(*solarKeys),
            dcOutW = p.int("mppt.outWatts") ?: p.int("pd.carWatts"),
            usbOutW = p.sumOf(
                "pd.typec1Watts", "pd.typec2Watts", "pd.usb1Watts",
                "pd.usb2Watts", "pd.qcUsb1Watts", "pd.qcUsb2Watts",
            ),
            chargeRemainMin = validMinutes(p.int("bms_emsStatus.chgRemainTime")),
            dischargeRemainMin = validMinutes(p.int("bms_emsStatus.dsgRemainTime")),
            batteryTempC = p.int("bms_bmsStatus.temp"),
            acInVolt = acInVolt,
            gridConnected = when {
                acInVolt == null && acIn == null -> null
                else -> (acInVolt ?: 0) > 100 || (acIn ?: 0) > 0
            },
            cycles = p.int("bms_bmsStatus.cycles"),
            soh = p.int("bms_bmsStatus.soh"),
        )
    }

    protected fun toggle(
        id: String, label: String, section: Section, key: String,
        cmd: (Int, Params) -> Outgoing,
    ) = Control.Toggle(
        id, label, section,
        read = { it.flag(key) },
        command = { on, p -> cmd(if (on) 1 else 0, p) },
        optimistic = { on -> mapOf(key to if (on) 1 else 0) },
    )

    protected fun slider(
        id: String, label: String, section: Section, key: String,
        min: Int, max: Int, step: Int, unit: String,
        cmd: (Int) -> Outgoing,
    ) = Control.Slider(
        id, label, section, min, max, step, unit,
        read = { it.int(key) },
        command = { v, _ -> cmd(v) },
        optimistic = { v -> mapOf(key to v) },
    )

    protected fun choice(
        id: String, label: String, section: Section, key: String,
        options: List<Pair<String, Int>>, cmd: (Int) -> Outgoing,
    ) = Control.Choice(
        id, label, section, options,
        read = { it.int(key) },
        command = { v, _ -> cmd(v) },
        optimistic = { v -> mapOf(key to v) },
    )

    protected fun socLimits(moduleSn: String?) = listOf(
        slider("max_chg", "Макс. рівень заряду", Section.CHARGING, "bms_emsStatus.maxChargeSoc", 50, 100, 1, "%") {
            JsonMessages.command(2, "upsConfig", mapOf("maxChgSoc" to it), moduleSn)
        },
        slider("min_dsg", "Мін. рівень розряду", Section.CHARGING, "bms_emsStatus.minDsgSoc", 0, 30, 1, "%") {
            JsonMessages.command(2, "dsgCfg", mapOf("minDsgSoc" to it), moduleSn)
        },
    )

    protected fun backup() = listOf(
        toggle("bp_enabled", "Резерв заряду", Section.BACKUP, "pd.watchIsConfig") { v, _ ->
            JsonMessages.command(
                1, "watthConfig",
                mapOf("bpPowerSoc" to v * 50, "minChgSoc" to 0, "isConfig" to v, "minDsgSoc" to 0),
            )
        },
        slider("bp_level", "Рівень резерву", Section.BACKUP, "pd.bpPowerSoc", 5, 100, 5, "%") {
            JsonMessages.command(
                1, "watthConfig",
                mapOf("isConfig" to 1, "bpPowerSoc" to it, "minDsgSoc" to 0, "minChgSoc" to 0),
            )
        },
    )
}

object Delta2Protocol : Delta2Family() {
    override val solarKeys = arrayOf("mppt.inWatts")

    override fun controls(sn: String): List<Control> = listOf(
        toggle("ac_out", "AC вихід", Section.OUTPUTS, "mppt.cfgAcEnabled") { v, _ ->
            JsonMessages.command(5, "acOutCfg", mapOf("enabled" to v, "out_voltage" to -1, "out_freq" to 255, "xboost" to 255))
        },
        toggle("xboost", "X-Boost", Section.OUTPUTS, "mppt.cfgAcXboost") { v, _ ->
            JsonMessages.command(5, "acOutCfg", mapOf("enabled" to 255, "out_voltage" to -1, "out_freq" to 255, "xboost" to v))
        },
        toggle("dc_out", "DC 12V вихід", Section.OUTPUTS, "pd.carState") { v, _ ->
            JsonMessages.command(5, "mpptCar", mapOf("enabled" to v))
        },
        toggle("usb_out", "USB виходи", Section.OUTPUTS, "pd.dcOutState") { v, _ ->
            JsonMessages.command(1, "dcOutCfg", mapOf("enabled" to v))
        },
        toggle("ac_auto", "AC завжди увімкнений", Section.OUTPUTS, "pd.acAutoOutConfig") { v, p ->
            JsonMessages.command(
                1, "acAutoOutConfig",
                mapOf("acAutoOutConfig" to v, "minAcOutSoc" to (p.int("bms_emsStatus.minDsgSoc") ?: 0) + 5),
            )
        },
        slider("ac_chg_w", "Потужність заряду від мережі", Section.CHARGING, "mppt.cfgChgWatts", 200, 1200, 100, "Вт") {
            JsonMessages.command(5, "acChgCfg", mapOf("chgWatts" to it, "chgPauseFlag" to 255))
        },
        choice(
            "dc_chg_a", "Струм заряду DC", Section.CHARGING, "mppt.dcChgCurrent",
            listOf("4 А" to 4000, "6 А" to 6000, "8 А" to 8000),
        ) { JsonMessages.command(5, "dcChgCfg", mapOf("dcChgCfg" to it)) },
        toggle("pv_prio", "Пріоритет сонячного заряду", Section.CHARGING, "pd.pvChgPrioSet") { v, _ ->
            JsonMessages.command(1, "pvChangePrio", mapOf("pvChangeSet" to v))
        },
    ) + socLimits(null) + backup() + listOf(
        toggle("quiet", "Беззвучний режим", Section.SYSTEM, "mppt.beepState") { v, _ ->
            JsonMessages.command(5, "quietMode", mapOf("enabled" to v))
        },
        choice("screen", "Вимкнення екрана", Section.SYSTEM, "pd.lcdOffSec", SCREEN_TIMEOUT_OPTIONS) {
            JsonMessages.command(1, "lcdCfg", mapOf("brighLevel" to 255, "delayOff" to it))
        },
        choice("unit_standby", "Автовимкнення станції", Section.SYSTEM, "pd.standbyMin", STANDBY_OPTIONS) {
            JsonMessages.command(1, "standbyTime", mapOf("standbyMin" to it))
        },
        choice("ac_standby", "Автовимкнення AC", Section.SYSTEM, "mppt.acStandbyMins", STANDBY_OPTIONS) {
            JsonMessages.command(5, "standbyTime", mapOf("standbyMins" to it))
        },
        choice("dc_standby", "Автовимкнення DC", Section.SYSTEM, "mppt.carStandbyMin", STANDBY_OPTIONS) {
            JsonMessages.command(5, "carStandby", mapOf("standbyMins" to it))
        },
    )
}

object Delta2MaxProtocol : Delta2Family() {
    override val solarKeys = arrayOf("mppt.inWatts", "mppt.pv2InWatts")

    override fun controls(sn: String): List<Control> = listOf(
        toggle("ac_out", "AC вихід", Section.OUTPUTS, "inv.cfgAcEnabled") { v, _ ->
            JsonMessages.command(3, "acOutCfg", mapOf("enabled" to v, "out_voltage" to -1, "out_freq" to 255, "xboost" to 255), sn)
        },
        toggle("xboost", "X-Boost", Section.OUTPUTS, "inv.cfgAcXboost") { v, _ ->
            JsonMessages.command(3, "acOutCfg", mapOf("xboost" to v), sn)
        },
        toggle("dc_out", "DC 12V вихід", Section.OUTPUTS, "pd.carState") { v, _ ->
            JsonMessages.command(5, "mpptCar", mapOf("enabled" to v))
        },
        toggle("usb_out", "USB виходи", Section.OUTPUTS, "pd.dcOutState") { v, _ ->
            JsonMessages.command(1, "dcOutCfg", mapOf("enabled" to v), sn)
        },
        toggle("ac_auto", "AC завжди увімкнений", Section.OUTPUTS, "pd.newAcAutoOnCfg") { v, _ ->
            JsonMessages.command(1, "newAcAutoOnCfg", mapOf("enabled" to v, "minAcSoc" to 5), sn)
        },
        slider("ac_chg_w", "Потужність заряду від мережі", Section.CHARGING, "inv.SlowChgWatts", 200, 2400, 100, "Вт") {
            JsonMessages.command(3, "acChgCfg", mapOf("slowChgWatts" to it, "fastChgWatts" to 2000, "chgPauseFlag" to 0), sn)
        },
    ) + socLimits(sn) + backup() + listOf(
        toggle("quiet", "Беззвучний режим", Section.SYSTEM, "pd.beepMode") { v, _ ->
            JsonMessages.command(1, "quietCfg", mapOf("enabled" to v), sn)
        },
        choice("screen", "Вимкнення екрана", Section.SYSTEM, "pd.lcdOffSec", SCREEN_TIMEOUT_OPTIONS) {
            JsonMessages.command(1, "lcdCfg", mapOf("brighLevel" to 255, "delayOff" to it), sn)
        },
        choice("unit_standby", "Автовимкнення станції", Section.SYSTEM, "inv.standbyMin", STANDBY_OPTIONS) {
            JsonMessages.command(1, "standbyTime", mapOf("standbyMin" to it), sn)
        },
        choice("dc_standby", "Автовимкнення DC", Section.SYSTEM, "mppt.carStandbyMin", STANDBY_OPTIONS) {
            JsonMessages.command(5, "standbyTime", mapOf("standbyMins" to it), sn)
        },
    )
}
