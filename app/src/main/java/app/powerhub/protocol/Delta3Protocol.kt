package app.powerhub.protocol

import app.powerhub.proto.Delta3Proto
import app.powerhub.protocol.Control.Section

/**
 * Delta 3 / Delta 3 Max: protobuf over the app MQTT API.
 * Message ids and field quirks follow tolwi/hassio-ecoflow-cloud (internal/delta3.py).
 */
class Delta3Protocol private constructor(private val maxAcChargeW: Int) : DeviceProtocol {

    companion object {
        val standard = Delta3Protocol(maxAcChargeW = 1500)
        val max = Delta3Protocol(maxAcChargeW = 2400)

        private val BMS_HEARTBEAT = setOf(
            3 to 1, 3 to 2, 3 to 30, 3 to 50, 32 to 1, 32 to 3, 32 to 50, 32 to 51, 32 to 52,
        )
        private val USB_FLOWS = listOf("flow_info_qcusb1", "flow_info_qcusb2", "flow_info_typec1", "flow_info_typec2")
    }

    private val setCommand = Delta3Proto.Delta3SetCommand.getDescriptor()

    override fun parse(kind: TopicKind, payload: ByteArray): Params {
        val out = HashMap<String, Any?>()
        for (frame in ProtoCodec.frames(payload)) {
            out.putAll(decode(frame))
        }
        return out
    }

    private fun decode(f: ProtoCodec.Frame): Map<String, Any?> {
        val key = f.cmdFunc to f.cmdId
        return when {
            key == (254 to 21) -> ProtoCodec.decodeFlat(Delta3Proto.Delta3DisplayPropertyUpload.getDescriptor(), f.pdata).also {
                ProtoCodec.deriveFlag(it, listOf("flow_info_12v"), "cfg_dc12v_out_open")
                ProtoCodec.deriveFlag(it, listOf("flow_info_ac_out"), "cfg_ac_out_open")
                ProtoCodec.deriveFlag(it, USB_FLOWS, "cfg_usb_open")
            }
            key == (254 to 22) -> ProtoCodec.decodeFlat(Delta3Proto.Delta3RuntimePropertyUpload.getDescriptor(), f.pdata)
            key == (254 to 18) -> {
                val reply = ProtoCodec.decodeFlat(Delta3Proto.Delta3SetReply.getDescriptor(), f.pdata)
                if (reply["config_ok"] == true) reply else emptyMap()
            }
            key == (32 to 2) -> ProtoCodec.decodeFlat(Delta3Proto.Delta3CMSHeartBeatReport.getDescriptor(), f.pdata)
            key in BMS_HEARTBEAT -> ProtoCodec.decodeFlat(Delta3Proto.Delta3BMSHeartBeatReport.getDescriptor(), f.pdata)
            else -> emptyMap()
        }
    }

    override fun quotaRequest(sn: String): Outgoing = ProtoCodec.snapshotRequest()

    override fun state(p: Params): DeviceState {
        val acInVolt = p.int("plug_in_info_ac_in_vol")
        return DeviceState(
            soc = p.int("cms_batt_soc") ?: p.int("bms_batt_soc"),
            inputW = p.int("pow_in_sum_w"),
            outputW = p.int("pow_out_sum_w"),
            acInW = p.int("pow_get_ac_in"),
            acOutW = p.absInt("pow_get_ac_out"),
            solarW = p.int("pow_get_pv"),
            dcOutW = p.absInt("pow_get_12v"),
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

    private fun set(sn: String, field: String, value: Int, dataLen: Int? = null): Outgoing {
        val pdata = ProtoCodec.setCommandPdata(setCommand, field, value)
        return ProtoCodec.setPacket(sn, pdata, dataLen ?: pdata.size)
    }

    /** The firmware ignores field 54 alone; the app always adds field 125 = 0 as a commit flag. */
    private fun acChargePower(sn: String, watts: Int): Outgoing {
        val pdata = byteArrayOf(0xB0.toByte(), 0x03) + ProtoCodec.encodeVarint(watts) +
            byteArrayOf(0xE8.toByte(), 0x07, 0x00)
        return ProtoCodec.setPacket(sn, pdata)
    }

    private fun energyBackup(sn: String, enabled: Int?, startSoc: Int): Outgoing {
        val backupDesc = Delta3Proto.Delta3CfgEnergyBackup.getDescriptor()
        val backup = com.google.protobuf.DynamicMessage.newBuilder(backupDesc)
            .setField(backupDesc.findFieldByName("energy_backup_start_soc"), startSoc)
        if (enabled != null) backup.setField(backupDesc.findFieldByName("energy_backup_en"), enabled)
        val pdata = com.google.protobuf.DynamicMessage.newBuilder(setCommand)
            .setField(setCommand.findFieldByName("cfg_energy_backup"), backup.build())
            .build().toByteArray()
        return ProtoCodec.setPacket(sn, pdata)
    }

    private fun toggle(sn: String, id: String, label: String, section: Section, field: String, dataLen: Int? = null) =
        Control.Toggle(
            id, label, section,
            read = { it.flag(field) },
            command = { on, _ -> set(sn, field, if (on) 1 else 0, dataLen) },
            optimistic = { on -> mapOf(field to if (on) 1 else 0) },
        )

    private fun choice(sn: String, id: String, label: String, section: Section, field: String, options: List<Pair<String, Int>>) =
        Control.Choice(
            id, label, section, options,
            read = { it.int(field) },
            command = { v, _ -> set(sn, field, v) },
            optimistic = { v -> mapOf(field to v) },
        )

    override fun controls(sn: String): List<Control> = listOf(
        toggle(sn, "ac_out", "AC вихід", Section.OUTPUTS, "cfg_ac_out_open"),
        toggle(sn, "xboost", "X-Boost", Section.OUTPUTS, "xboost_en"),
        toggle(sn, "dc_out", "DC 12V вихід", Section.OUTPUTS, "cfg_dc12v_out_open"),
        toggle(sn, "usb_out", "USB виходи", Section.OUTPUTS, "cfg_usb_open"),
        toggle(sn, "power_memory", "Пам'ять стану виходів", Section.OUTPUTS, "output_power_off_memory"),
        Control.Slider(
            "ac_chg_w", "Потужність заряду від мережі", Section.CHARGING, 100, maxAcChargeW, 50, "Вт",
            read = { it.int("plug_in_info_ac_in_chg_pow_max") },
            command = { v, _ -> acChargePower(sn, v) },
            optimistic = { v -> mapOf("plug_in_info_ac_in_chg_pow_max" to v) },
        ),
        Control.Slider(
            "max_chg", "Макс. рівень заряду", Section.CHARGING, 50, 100, 1, "%",
            read = { it.int("cms_max_chg_soc") },
            command = { v, _ -> set(sn, "cms_max_chg_soc", v) },
            optimistic = { v -> mapOf("cms_max_chg_soc" to v) },
        ),
        Control.Slider(
            "min_dsg", "Мін. рівень розряду", Section.CHARGING, 0, 30, 1, "%",
            read = { it.int("cms_min_dsg_soc") },
            command = { v, _ -> set(sn, "cms_min_dsg_soc", v) },
            optimistic = { v -> mapOf("cms_min_dsg_soc" to v) },
        ),
        choice(
            sn, "pv_amp", "Макс. струм сонячного заряду", Section.CHARGING, "plug_in_info_pv_dc_amp_max",
            listOf("4 А" to 4, "5 А" to 5, "6 А" to 6, "7 А" to 7, "8 А" to 8),
        ),
        Control.Toggle(
            "bp_enabled", "Резерв заряду", Section.BACKUP,
            read = { it.flag("energy_backup_en") },
            command = { on, p -> energyBackup(sn, if (on) 1 else 0, p.int("energy_backup_start_soc") ?: 5) },
            optimistic = { on -> mapOf("energy_backup_en" to if (on) 1 else 0) },
        ),
        Control.Slider(
            "bp_level", "Рівень резерву", Section.BACKUP, 5, 100, 5, "%",
            read = { it.int("energy_backup_start_soc") },
            command = { v, _ -> energyBackup(sn, 1, v) },
            optimistic = { v -> mapOf("energy_backup_start_soc" to v) },
        ),
        toggle(sn, "beep", "Звуковий сигнал", Section.SYSTEM, "en_beep", dataLen = 2),
        choice(sn, "screen", "Вимкнення екрана", Section.SYSTEM, "screen_off_time", SCREEN_TIMEOUT_OPTIONS),
        choice(sn, "unit_standby", "Автовимкнення станції", Section.SYSTEM, "dev_standby_time", STANDBY_OPTIONS),
        choice(sn, "ac_standby", "Автовимкнення AC", Section.SYSTEM, "ac_standby_time", STANDBY_OPTIONS),
        choice(sn, "dc_standby", "Автовимкнення DC", Section.SYSTEM, "dc_standby_time", STANDBY_OPTIONS),
    )
}
