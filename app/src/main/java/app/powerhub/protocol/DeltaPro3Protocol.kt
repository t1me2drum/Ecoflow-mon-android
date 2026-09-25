package app.powerhub.protocol

import app.powerhub.proto.DeltaPro3Proto
import app.powerhub.protocol.Control.Section

/**
 * Delta Pro 3: telemetry arrives as protobuf, commands are JSON "TCP" messages with a
 * numeric parameter id. Follows tolwi/hassio-ecoflow-cloud (internal/delta_pro_3.py).
 */
object DeltaPro3Protocol : DeviceProtocol {

    private val BMS_HEARTBEAT = setOf(
        3 to 1, 3 to 2, 3 to 30, 3 to 50,
        254 to 24, 254 to 25, 254 to 26, 254 to 27, 254 to 28, 254 to 29, 254 to 30,
        32 to 1, 32 to 3, 32 to 50, 32 to 51, 32 to 52,
    )

    override fun parse(kind: TopicKind, payload: ByteArray): Params {
        if (JsonMessages.looksLikeJson(payload)) {
            return if (kind == TopicKind.SET_REPLY) emptyMap() else JsonMessages.parse(payload)
        }
        val out = HashMap<String, Any?>()
        for (f in ProtoCodec.frames(payload)) {
            val key = f.cmdFunc to f.cmdId
            val decoded: Map<String, Any?> = when {
                key == (254 to 21) -> ProtoCodec.decodeFlat(DeltaPro3Proto.DP3DisplayPropertyUpload.getDescriptor(), f.pdata).also {
                    ProtoCodec.deriveFlag(it, listOf("flow_info_ac_hv_out"), "cfg_hv_ac_out_open")
                    ProtoCodec.deriveFlag(it, listOf("flow_info_ac_lv_out"), "cfg_lv_ac_out_open")
                    ProtoCodec.deriveFlag(it, listOf("flow_info_12v"), "cfg_dc_12v_out_open")
                    ProtoCodec.deriveFlag(it, listOf("flow_info_24v"), "cfg_dc_24v_out_open")
                }
                key == (254 to 22) -> ProtoCodec.decodeFlat(DeltaPro3Proto.DP3RuntimePropertyUpload.getDescriptor(), f.pdata)
                key == (32 to 2) -> ProtoCodec.decodeFlat(DeltaPro3Proto.DP3CMSHeartBeatReport.getDescriptor(), f.pdata)
                key in BMS_HEARTBEAT -> ProtoCodec.decodeFlat(DeltaPro3Proto.DP3BMSHeartBeatReport.getDescriptor(), f.pdata)
                else -> emptyMap()
            }
            out.putAll(decoded)
        }
        return out
    }

    override fun quotaRequest(sn: String): Outgoing = JsonMessages.latestQuotas()

    override fun state(p: Params): DeviceState {
        val acInVolt = p.int("plug_in_info_ac_in_vol")
        return DeviceState(
            soc = p.int("cms_batt_soc") ?: p.int("bms_batt_soc"),
            inputW = p.int("pow_in_sum_w"),
            outputW = p.int("pow_out_sum_w"),
            acInW = p.int("pow_get_ac_in"),
            acOutW = p.sumOf("pow_get_ac_hv_out", "pow_get_ac_lv_out", abs = true) ?: p.absInt("pow_get_ac"),
            solarW = p.sumOf("pow_get_pv_h", "pow_get_pv_l"),
            dcOutW = p.sumOf("pow_get_12v", "pow_get_24v", abs = true),
            usbOutW = p.sumOf("pow_get_qcusb1", "pow_get_qcusb2", "pow_get_typec1", "pow_get_typec2", abs = true),
            chargeRemainMin = validMinutes(p.int("cms_chg_rem_time") ?: p.int("bms_chg_rem_time")),
            dischargeRemainMin = validMinutes(p.int("cms_dsg_rem_time") ?: p.int("bms_dsg_rem_time")),
            batteryTempC = p.int("bms_max_cell_temp"),
            acInVolt = acInVolt,
            gridConnected = p.flag("plug_in_info_ac_in_flag") ?: acInVolt?.let { it > 100 },
            cycles = p.int("cycles"),
            soh = p.int("bms_batt_soh"),
        )
    }

    private fun tcp(id: Int, param: String, value: Int): Outgoing =
        JsonMessages.command(0, "TCP", mapOf("id" to id, param to value))

    private fun toggle(id: String, label: String, section: Section, key: String, cmdId: Int, param: String) =
        Control.Toggle(
            id, label, section,
            read = { it.flag(key) },
            command = { on, _ -> tcp(cmdId, param, if (on) 1 else 0) },
            optimistic = { on -> mapOf(key to if (on) 1 else 0) },
        )

    private fun slider(
        id: String, label: String, section: Section, key: String, cmdId: Int, param: String,
        min: Int, max: Int, step: Int, unit: String,
    ) = Control.Slider(
        id, label, section, min, max, step, unit,
        read = { it.int(key) },
        command = { v, _ -> tcp(cmdId, param, v) },
        optimistic = { v -> mapOf(key to v) },
    )

    private fun choice(id: String, label: String, section: Section, key: String, cmdId: Int, param: String, options: List<Pair<String, Int>>) =
        Control.Choice(
            id, label, section, options,
            read = { it.int(key) },
            command = { v, _ -> tcp(cmdId, param, v) },
            optimistic = { v -> mapOf(key to v) },
        )

    override fun controls(sn: String): List<Control> = listOf(
        toggle("ac_hv", "AC вихід (230 В)", Section.OUTPUTS, "cfg_hv_ac_out_open", 66, "cfgHvAcOutOpen"),
        toggle("ac_lv", "AC вихід (низьковольтний)", Section.OUTPUTS, "cfg_lv_ac_out_open", 66, "cfgLvAcOutOpen"),
        toggle("xboost", "X-Boost", Section.OUTPUTS, "xboost_en", 66, "xboostEn"),
        toggle("dc12", "DC 12V вихід", Section.OUTPUTS, "cfg_dc_12v_out_open", 81, "cfgDc12vOutOpen"),
        toggle("dc24", "DC 24V вихід", Section.OUTPUTS, "cfg_dc_24v_out_open", 81, "cfgDc24vOutOpen"),
        toggle("ac_saving", "Енергозбереження AC", Section.OUTPUTS, "ac_energy_saving_open", 95, "acEnergySavingOpen"),
        slider("ac_chg_w", "Потужність заряду від мережі", Section.CHARGING, "plug_in_info_ac_in_chg_pow_max", 69, "plugInInfoAcInChgPowMax", 200, 3000, 100, "Вт"),
        slider("max_chg", "Макс. рівень заряду", Section.CHARGING, "cms_max_chg_soc", 49, "cmsMaxChgSoc", 50, 100, 1, "%"),
        slider("min_dsg", "Мін. рівень розряду", Section.CHARGING, "cms_min_dsg_soc", 51, "cmsMinDsgSoc", 0, 30, 1, "%"),
        toggle("beep", "Звуковий сигнал", Section.SYSTEM, "en_beep", 38, "enBeep"),
        choice("screen", "Вимкнення екрана", Section.SYSTEM, "screen_off_time", 39, "screenOffTime", SCREEN_TIMEOUT_OPTIONS),
        choice("ac_standby", "Автовимкнення AC", Section.SYSTEM, "ac_standby_time", 10, "acStandbyTime", STANDBY_OPTIONS),
        choice("dc_standby", "Автовимкнення DC", Section.SYSTEM, "dc_standby_time", 33, "dcStandbyTime", STANDBY_OPTIONS),
    )
}
