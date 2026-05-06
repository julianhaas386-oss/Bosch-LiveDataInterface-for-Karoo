package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleState
import de.dxmedia.bosch.ldi.extension.ConnectionDataType.Companion.toConnectionDouble
import org.junit.Test
import kotlin.test.assertEquals

class ConnectionDataTypeTest {

    @Test fun `Disconnected maps to 0_0`() {
        assertEquals(0.0, BleState.Disconnected.toConnectionDouble(), 0.001)
    }

    @Test fun `Advertising with null address maps to 1_0`() {
        assertEquals(1.0, BleState.Advertising(null).toConnectionDouble(), 0.001)
    }

    @Test fun `Advertising with bonded address maps to 1_0`() {
        assertEquals(1.0, BleState.Advertising("AA:BB:CC:DD:EE:FF").toConnectionDouble(), 0.001)
    }

    @Test fun `Connected maps to 2_0`() {
        assertEquals(2.0, BleState.Connected("AA:BB:CC:DD:EE:FF").toConnectionDouble(), 0.001)
    }
}
