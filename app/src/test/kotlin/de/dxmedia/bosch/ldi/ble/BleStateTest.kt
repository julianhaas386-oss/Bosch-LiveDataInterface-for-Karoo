package de.dxmedia.bosch.ldi.ble

import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BleStateTest {

    @Test
    fun `Disconnected is a singleton object`() {
        assertSame(BleState.Disconnected, BleState.Disconnected)
    }

    @Test
    fun `Disconnected differs from Advertising`() {
        assertNotEquals<BleState>(BleState.Disconnected, BleState.Advertising(null))
    }

    @Test
    fun `Advertising stores bondedAddress`() {
        val state = BleState.Advertising("AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", state.bondedAddress)
    }

    @Test
    fun `Advertising with null is pairing mode`() {
        assertNull(BleState.Advertising(null).bondedAddress)
    }

    @Test
    fun `Connected stores deviceAddress`() {
        val state = BleState.Connected("11:22:33:44:55:66")
        assertEquals("11:22:33:44:55:66", state.deviceAddress)
    }

    @Test
    fun `two Connected with same address are equal`() {
        assertEquals(
            BleState.Connected("AA:BB:CC:DD:EE:FF"),
            BleState.Connected("AA:BB:CC:DD:EE:FF")
        )
    }

    @Test
    fun `two Connected with different addresses are not equal`() {
        assertNotEquals(
            BleState.Connected("AA:BB:CC:DD:EE:FF"),
            BleState.Connected("11:22:33:44:55:66")
        )
    }

    @Test
    fun `two Advertising with null address are equal`() {
        assertEquals(BleState.Advertising(null), BleState.Advertising(null))
    }

    @Test
    fun `two Advertising with different addresses are not equal`() {
        assertNotEquals(BleState.Advertising("AA:BB:CC:DD:EE:FF"), BleState.Advertising("11:22:33:44:55:66"))
    }
}
