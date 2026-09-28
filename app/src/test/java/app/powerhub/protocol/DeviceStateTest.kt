package app.powerhub.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStateTest {

    // ---- battery flow: direction comes from power, not from which estimate the station sent ----

    @Test
    fun `no grid with stale time-to-full is discharging, not charging`() {
        // Delta Pro 3 keeps reporting its last charge estimate after the grid drops.
        val s = DeviceState(soc = 60, inputW = 0, outputW = 150, chargeRemainMin = 90, dischargeRemainMin = null)
        assertEquals(BatteryFlow.Discharging(null), s.batteryFlow())
    }

    @Test
    fun `charging uses only the charge estimate`() {
        val s = DeviceState(soc = 40, inputW = 800, outputW = 100, chargeRemainMin = 70, dischargeRemainMin = 300)
        assertEquals(BatteryFlow.Charging(70), s.batteryFlow())
    }

    @Test
    fun `discharging uses only the discharge estimate`() {
        val s = DeviceState(soc = 70, inputW = 0, outputW = 200, chargeRemainMin = 45, dischargeRemainMin = 255)
        assertEquals(BatteryFlow.Discharging(255), s.batteryFlow())
    }

    @Test
    fun `grid pass-through with a load is idle and keeps the load`() {
        // On grid a 16 W load is fed straight through: input equals output.
        val s = DeviceState(soc = 80, inputW = 16, outputW = 16)
        assertEquals(BatteryFlow.Idle(16), s.batteryFlow())
    }

    @Test
    fun `small difference inside the deadband is idle`() {
        assertEquals(BatteryFlow.Idle(0), DeviceState(soc = 80, inputW = 5, outputW = 0).batteryFlow())
    }

    @Test
    fun `full battery on grid is full, not charging`() {
        assertEquals(BatteryFlow.Full, DeviceState(soc = 100, inputW = 60, outputW = 20).batteryFlow())
        assertEquals(BatteryFlow.Full, DeviceState(soc = 100, inputW = 0, outputW = 0).batteryFlow())
    }

    @Test
    fun `no power data is unknown`() {
        assertEquals(BatteryFlow.Unknown, DeviceState(soc = 50).batteryFlow())
    }

    // ---- grid status ----

    @Test
    fun `weak grid below threshold`() {
        val s = DeviceState(gridConnected = true, acInVolt = 140)
        assertEquals(GridStatus.WEAK, s.gridStatus(180))
    }

    @Test
    fun `normal grid at or above threshold`() {
        assertEquals(GridStatus.OK, DeviceState(gridConnected = true, acInVolt = 180).gridStatus(180))
        assertEquals(GridStatus.OK, DeviceState(gridConnected = true, acInVolt = 228).gridStatus(180))
    }

    @Test
    fun `grid without voltage reading is treated as ok`() {
        assertEquals(GridStatus.OK, DeviceState(gridConnected = true, acInVolt = null).gridStatus(180))
    }

    @Test
    fun `no grid and unknown grid`() {
        assertEquals(GridStatus.NONE, DeviceState(gridConnected = false).gridStatus(180))
        assertNull(DeviceState().gridStatus(180))
    }

    // ---- charging animation ----

    @Test
    fun `weak grid never counts as charging`() {
        val s = DeviceState(soc = 50, gridConnected = true, acInVolt = 140, acInW = 0, inputW = 0, outputW = 30)
        assertFalse(s.chargingFromGrid(180))
    }

    @Test
    fun `normal grid with ac input charges`() {
        val s = DeviceState(soc = 50, gridConnected = true, acInVolt = 228, acInW = 600, inputW = 600, outputW = 50)
        assertTrue(s.chargingFromGrid(180))
    }

    @Test
    fun `load larger than grid input drains the battery, so no charging animation`() {
        val s = DeviceState(soc = 50, gridConnected = true, acInVolt = 228, acInW = 300, inputW = 300, outputW = 900)
        assertFalse(s.chargingFromGrid(180))
    }

    // ---- helpers ----

    @Test
    fun `remaining time sentinels are dropped`() {
        assertNull(validMinutes(5939))
        assertNull(validMinutes(0))
        assertNull(validMinutes(null))
        assertEquals(125, validMinutes(125))
    }

    @Test
    fun `model detection by serial prefix and product name`() {
        assertEquals(DeviceModel.DELTA_2, DeviceModel.detect("R331ZEB5SG8X0271", null))
        assertEquals(DeviceModel.DELTA_2_MAX, DeviceModel.detect("R351ZE1APH7K0022", "DELTA 2 Max"))
        assertEquals(DeviceModel.DELTA_3, DeviceModel.detect("P231ZE1APJ3E2930", null))
        assertEquals(DeviceModel.DELTA_PRO_3, DeviceModel.detect("MR51ZES5PG8K0043", "DELTA Pro 3"))
        assertEquals(DeviceModel.DELTA_MAX, DeviceModel.detect("DA0000000001400", "DELTA Max"))
        assertEquals(DeviceModel.RIVER_2_MAX, DeviceModel.detect("R611111111111424", "RIVER 2 Max"))
        assertEquals(DeviceModel.DELTA_3_MAX, DeviceModel.detect("XXXX0000", "DELTA 3 Max"))
        assertNull(DeviceModel.detect("ZZZZ0000", "PowerStream"))
    }
}
