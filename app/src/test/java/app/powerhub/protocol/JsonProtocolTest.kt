package app.powerhub.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonProtocolTest {

    private fun bytes(json: String) = json.toByteArray()

    @Test
    fun `data topic params are taken as flat keys`() {
        val p = JsonMessages.parse(bytes("""{"params":{"pd.soc":85,"bms_emsStatus.maxChargeSoc":100}}"""))
        assertEquals(85, p["pd.soc"])
        assertEquals(100, p["bms_emsStatus.maxChargeSoc"])
    }

    @Test
    fun `latestQuotas reply uses quotaMap only when online`() {
        val online = """{"operateType":"latestQuotas","data":{"online":1,"quotaMap":{"pd.wattsOutSum":42}}}"""
        assertEquals(42, JsonMessages.parse(bytes(online))["pd.wattsOutSum"])
        val offline = """{"operateType":"latestQuotas","data":{"online":0,"quotaMap":{"pd.wattsOutSum":42}}}"""
        assertTrue(JsonMessages.parse(bytes(offline)).isEmpty())
    }

    @Test
    fun `nested objects are flattened with dots and garbage is ignored`() {
        val p = JsonMessages.parse(bytes("""{"params":{"inv":{"inputWatts":10,"arr":[1,2]}}}"""))
        assertEquals(10, p["inv.inputWatts"])
        assertEquals(listOf(1, 2), p["inv.arr"])
        assertTrue(JsonMessages.parse(bytes("not json")).isEmpty())
    }

    @Test
    fun `json detection does not mistake protobuf for json`() {
        assertTrue(JsonMessages.looksLikeJson(bytes("{}")))
        // Protobuf frames start with 0x0A.
        assertFalse(JsonMessages.looksLikeJson(byteArrayOf(0x0A, '{'.code.toByte())))
    }

    @Test
    fun `command carries the app framing`() {
        val json = JSONObject(String(JsonMessages.command(5, "acOutCfg", mapOf("enabled" to 1), moduleSn = "SN1").payload))
        assertEquals("Android", json.getString("from"))
        assertEquals("1.0", json.getString("version"))
        assertEquals(5, json.getInt("moduleType"))
        assertEquals("acOutCfg", json.getString("operateType"))
        assertEquals("SN1", json.getString("moduleSn"))
        assertEquals(1, json.getJSONObject("params").getInt("enabled"))
    }

    @Test
    fun `delta 2 state maps keys and converts millivolts`() {
        val p = JsonMessages.parse(
            bytes(
                """{"params":{"bms_emsStatus.lcdShowSoc":85,"pd.wattsInSum":0,"pd.wattsOutSum":120,
                "inv.acInVol":140000,"inv.inputWatts":0,"bms_emsStatus.dsgRemainTime":300,
                "bms_emsStatus.chgRemainTime":5939}}""",
            ),
        )
        val s = Delta2Protocol.state(p)
        assertEquals(85, s.soc)
        assertEquals(140, s.acInVolt)
        assertEquals(true, s.gridConnected)
        assertEquals(GridStatus.WEAK, s.gridStatus(180))
        assertEquals(null, s.chargeRemainMin) // 5939 is the idle sentinel
        assertEquals(BatteryFlow.Discharging(300), s.batteryFlow())
    }

    @Test
    fun `delta 2 without grid voltage has no grid`() {
        val s = Delta2Protocol.state(mapOf("inv.acInVol" to 0, "inv.inputWatts" to 0))
        assertEquals(false, s.gridConnected)
    }

    @Test
    fun `delta 2 ac toggle builds the documented command`() {
        val toggle = Delta2Protocol.controls("SN").filterIsInstance<Control.Toggle>().first { it.id == "ac_out" }
        val json = JSONObject(String(toggle.command(true, emptyMap()).payload))
        assertEquals("acOutCfg", json.getString("operateType"))
        assertEquals(5, json.getInt("moduleType"))
        assertEquals(1, json.getJSONObject("params").getInt("enabled"))
        assertEquals(mapOf("mppt.cfgAcEnabled" to 1), toggle.optimistic(true))
    }

    @Test
    fun `delta pro 3 commands are TCP with a parameter id`() {
        val toggle = DeltaPro3Protocol.controls("SN").filterIsInstance<Control.Toggle>().first { it.id == "ac_hv" }
        val json = JSONObject(String(toggle.command(true, emptyMap()).payload))
        assertEquals("TCP", json.getString("operateType"))
        assertEquals(66, json.getJSONObject("params").getInt("id"))
        assertEquals(1, json.getJSONObject("params").getInt("cfgHvAcOutOpen"))
    }

    @Test
    fun `delta max uses its own keys`() {
        val s = DeltaMaxProtocol.state(mapOf("ems.lcdShowSoc" to 55, "bmsMaster.cycles" to 12, "pd.wattsOutSum" to 30))
        assertEquals(55, s.soc)
        assertEquals(12, s.cycles)
    }
}
