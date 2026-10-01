package app.powerhub.data

import app.powerhub.protocol.Device
import app.powerhub.protocol.DeviceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationMergeTest {
    private val model = DeviceModel.DELTA_PRO_3
    private fun cloud(sn: String, name: String) = Device(sn, name, model, imported = true)

    private fun merge(current: List<Device>, account: List<Device>, hidden: Set<String> = emptySet()) =
        mergeStations(current, account, hidden)

    @Test
    fun `account rename reaches a station never renamed locally`() {
        val local = listOf(Device("A", "Old", model, imported = true, cloudName = "Old"))
        val (list, r) = merge(local, listOf(cloud("A", "New from phone")))
        assertEquals("New from phone", list.single().name)
        assertEquals(1, r.updated)
    }

    @Test
    fun `local rename survives while the account name is unchanged`() {
        val local = listOf(Device("A", "My name", model, imported = true, customName = true, cloudName = "Account"))
        val (list, _) = merge(local, listOf(cloud("A", "Account")))
        assertEquals("My name", list.single().name)
        assertTrue(list.single().customName)
    }

    @Test
    fun `newer account rename replaces an older local rename`() {
        val local = listOf(Device("A", "My name", model, imported = true, customName = true, cloudName = "Account"))
        val (list, r) = merge(local, listOf(cloud("A", "Renamed on phone")))
        val d = list.single()
        assertEquals("Renamed on phone", d.name)
        assertFalse(d.customName)
        assertEquals("Renamed on phone", d.cloudName)
        assertEquals(1, r.updated)
    }

    @Test
    fun `upgrade from a version without cloudName keeps the local rename as baseline`() {
        val local = listOf(Device("A", "My name", model, imported = true, customName = true, cloudName = null))
        val (list, _) = merge(local, listOf(cloud("A", "Account")))
        assertEquals("My name", list.single().name)
        assertEquals("Account", list.single().cloudName)
    }

    @Test
    fun `new stations are added with their account name, removed ones dropped, manual ones kept`() {
        val local = listOf(
            Device("GONE", "Gone", model, imported = true),
            Device("MANUAL", "Manual", model, imported = false),
        )
        val (list, r) = merge(local, listOf(cloud("NEW", "Fresh")))
        assertEquals(listOf("MANUAL", "NEW"), list.map { it.sn })
        assertEquals("Fresh", list.last().cloudName)
        assertEquals(1, r.added)
        assertEquals(1, r.removed)
    }

    @Test
    fun `hidden stations are not brought back`() {
        val (list, r) = merge(emptyList(), listOf(cloud("H", "Hidden")), hidden = setOf("H"))
        assertTrue(list.isEmpty())
        assertEquals(0, r.added)
    }

    @Test
    fun `unchanged list reports no updates and keeps order`() {
        val local = listOf(
            Device("B", "Bee", model, imported = true, cloudName = "Bee"),
            Device("A", "Ay", model, imported = true, cloudName = "Ay"),
        )
        val (list, r) = merge(local, listOf(cloud("A", "Ay"), cloud("B", "Bee")))
        assertEquals(listOf("B", "A"), list.map { it.sn })
        assertEquals(SyncResult(0, 0, 0), r)
    }
}
