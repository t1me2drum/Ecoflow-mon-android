package app.powerhub.protocol

import app.powerhub.protocol.Control.Section

/**
 * Delta Max: older JSON firmware with `bmsMaster.*` / `ems.*` keys and numeric "TCP" commands.
 * Follows tolwi/hassio-ecoflow-cloud (internal/delta_max.py).
 */
object DeltaMaxProtocol : Delta2Family() {
    override val solarKeys = arrayOf("mppt.inWatts")

    override fun state(p: Params): DeviceState {
        val acInVolt = p.num("inv.acInVol")?.let { (it / 1000).toInt() }
        val acIn = p.int("inv.inputWatts")
        return DeviceState(
            soc = p.int("ems.lcdShowSoc") ?: p.int("bmsMaster.soc"),
            inputW = p.int("pd.wattsInSum"),
            outputW = p.int("pd.wattsOutSum"),
            acInW = acIn,
            acOutW = p.int("inv.outputWatts"),
            solarW = p.int("mppt.inWatts"),
            dcOutW = p.int("mppt.outWatts"),
            usbOutW = p.sumOf(
                "pd.typec1Watts", "pd.typec2Watts", "pd.usb1Watts",
                "pd.usb2Watts", "pd.qcUsb1Watts", "pd.qcUsb2Watts",
            ),
            chargeRemainMin = validMinutes(p.int("ems.chgRemainTime")),
            dischargeRemainMin = validMinutes(p.int("ems.dsgRemainTime")),
            batteryTempC = p.int("bmsMaster.temp"),
            acInVolt = acInVolt,
            gridConnected = when {
                acInVolt == null && acIn == null -> null
                else -> (acInVolt ?: 0) > 100 || (acIn ?: 0) > 0
            },
            cycles = p.int("bmsMaster.cycles"),
            soh = p.int("bmsMaster.soh"),
        )
    }

    private fun tcp(moduleType: Int, id: Int, params: Map<String, Any>) =
        JsonMessages.command(moduleType, "TCP", params + ("id" to id))

    override fun controls(sn: String): List<Control> = listOf(
        toggle("ac_out", "AC вихід", Section.OUTPUTS, "inv.cfgAcEnabled") { v, _ -> tcp(0, 66, mapOf("enabled" to v)) },
        toggle("xboost", "X-Boost", Section.OUTPUTS, "inv.cfgAcXboost") { v, _ -> tcp(5, 66, mapOf("xboost" to v)) },
        toggle("dc_out", "DC 12V вихід", Section.OUTPUTS, "mppt.carState") { v, _ -> tcp(0, 81, mapOf("enabled" to v)) },
        toggle("usb_out", "USB виходи", Section.OUTPUTS, "pd.dcOutState") { v, _ -> tcp(0, 34, mapOf("enabled" to v)) },
        toggle("ac_auto", "AC завжди увімкнений", Section.OUTPUTS, "pd.acAutoOnCfg") { v, _ ->
            JsonMessages.command(1, "acAutoOn", mapOf("cfg" to v))
        },
        slider("ac_chg_w", "Потужність заряду від мережі", Section.CHARGING, "inv.cfgSlowChgWatts", 100, 2000, 100, "Вт") {
            tcp(0, 69, mapOf("slowChgPower" to it))
        },
        slider("max_chg", "Макс. рівень заряду", Section.CHARGING, "ems.maxChargeSoc", 50, 100, 1, "%") {
            tcp(2, 49, mapOf("maxChgSoc" to it))
        },
        slider("min_dsg", "Мін. рівень розряду", Section.CHARGING, "ems.minDsgSoc", 0, 30, 1, "%") {
            tcp(2, 51, mapOf("minDsgSoc" to it))
        },
        toggle("pv_prio", "Пріоритет сонячного заряду", Section.CHARGING, "pd.pvChgPrioSet") { v, _ ->
            JsonMessages.command(1, "pvChangePrio", mapOf("pvChangeSet" to v))
        },
        toggle("quiet", "Беззвучний режим", Section.SYSTEM, "pd.beepState") { v, _ -> tcp(5, 38, mapOf("enabled" to v)) },
    )
}

/**
 * River 2 Max: same JSON layout as Delta 2 with River-specific timeout and DC-mode commands.
 * Follows tolwi/hassio-ecoflow-cloud (internal/river2_max.py).
 */
object River2MaxProtocol : Delta2Family() {
    override val solarKeys = arrayOf("mppt.inWatts")

    override fun state(p: Params): DeviceState =
        super.state(p).copy(dcOutW = p.int("pd.carWatts") ?: p.int("mppt.outWatts"))

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
        toggle("ac_auto", "AC завжди увімкнений", Section.OUTPUTS, "pd.acAutoOutConfig") { v, p ->
            JsonMessages.command(
                1, "acAutoOutConfig",
                mapOf("acAutoOutConfig" to v, "minAcOutSoc" to (p.int("bms_emsStatus.minDsgSoc") ?: 0) + 5),
            )
        },
        slider("ac_chg_w", "Потужність заряду від мережі", Section.CHARGING, "mppt.cfgChgWatts", 50, 660, 10, "Вт") {
            JsonMessages.command(5, "acChgCfg", mapOf("chgWatts" to it, "chgPauseFlag" to 255))
        },
        choice(
            "dc_chg_a", "Струм заряду DC", Section.CHARGING, "mppt.dcChgCurrent",
            listOf("4 А" to 4000, "6 А" to 6000, "8 А" to 8000),
        ) { JsonMessages.command(5, "dcChgCfg", mapOf("dcChgCfg" to it)) },
        choice(
            "dc_mode", "Режим DC-входу", Section.CHARGING, "mppt.cfgChgType",
            listOf("Авто" to 0, "Сонце" to 1, "Автомобіль" to 2),
        ) { JsonMessages.command(5, "chaType", mapOf("chaType" to it)) },
    ) + socLimits(null) + backup() + listOf(
        choice("screen", "Вимкнення екрана", Section.SYSTEM, "mppt.scrStandbyMin", SCREEN_TIMEOUT_OPTIONS) {
            JsonMessages.command(5, "lcdCfg", mapOf("brighLevel" to 255, "delayOff" to it))
        },
        choice("unit_standby", "Автовимкнення станції", Section.SYSTEM, "mppt.powStandbyMin", STANDBY_OPTIONS) {
            JsonMessages.command(5, "standby", mapOf("standbyMins" to it))
        },
        choice("ac_standby", "Автовимкнення AC", Section.SYSTEM, "mppt.acStandbyMins", STANDBY_OPTIONS) {
            JsonMessages.command(5, "acStandby", mapOf("standbyMins" to it))
        },
    )
}
