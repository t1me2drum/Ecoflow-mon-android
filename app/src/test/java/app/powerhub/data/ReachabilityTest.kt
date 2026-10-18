package app.powerhub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReachabilityTest {
    private val min = 60_000L
    private val now = 100 * min

    @Test
    fun `fresh data means reachable even while PowerHub is offline`() {
        assertEquals(true, stationReachable(offlineReportedAt = 0, lastSeen = now - min, now = now, cloudUpSince = null))
    }

    @Test
    fun `PowerHub offline makes silent stations unknown, not offline`() {
        // Internet on the PC drops: no alert flood for every station.
        assertNull(stationReachable(offlineReportedAt = 0, lastSeen = now - 10 * min, now = now, cloudUpSince = null))
    }

    @Test
    fun `right after reconnect stations get the offline window before counting as offline`() {
        assertNull(stationReachable(offlineReportedAt = 0, lastSeen = now - 30 * min, now = now, cloudUpSince = now - min))
    }

    @Test
    fun `silent station with a stable cloud link is offline`() {
        assertEquals(false, stationReachable(offlineReportedAt = 0, lastSeen = now - 10 * min, now = now, cloudUpSince = now - 20 * min))
        assertEquals(false, stationReachable(offlineReportedAt = 0, lastSeen = 0, now = now, cloudUpSince = now - 20 * min))
    }

    @Test
    fun `cloud saying offline wins over the silence window`() {
        // Station dropped 1 min ago; without the cloud's verdict it would still look online for 2 more minutes.
        assertEquals(false, stationReachable(lastSeen = now - min, offlineReportedAt = now - 10_000, now = now, cloudUpSince = now - 20 * min))
        // Also right after PowerHub reconnects, when silence alone says nothing.
        assertEquals(false, stationReachable(lastSeen = now - 30 * min, offlineReportedAt = now - 10_000, now = now, cloudUpSince = now - 10_000))
    }

    @Test
    fun `data after an offline report brings the station back`() {
        assertEquals(true, stationReachable(lastSeen = now - 5_000, offlineReportedAt = now - min, now = now, cloudUpSince = now - 20 * min))
    }

    @Test
    fun `durations`() {
        assertEquals("1 хв", durationText(10_000))
        assertEquals("45 хв", durationText(45 * min))
        assertEquals("2 год 5 хв", durationText(125 * min))
        assertEquals("1 д 3 год", durationText((27 * 60 + 10) * min))
    }
}
