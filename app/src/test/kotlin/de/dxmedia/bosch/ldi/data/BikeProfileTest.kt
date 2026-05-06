package de.dxmedia.bosch.ldi.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BikeProfileTest {

    @Test fun `BikeSlot has exactly 4 slots with expected display names`() {
        assertEquals(4, BikeSlot.values().size)
        assertEquals("Alpha", BikeSlot.ALPHA.displayName)
        assertEquals("Beta", BikeSlot.BETA.displayName)
        assertEquals("Gamma", BikeSlot.GAMMA.displayName)
        assertEquals("Delta", BikeSlot.DELTA.displayName)
    }

    @Test fun `default profile is unpaired and inactive with full default field set`() {
        val p = BikeProfile(slot = BikeSlot.ALPHA, bleAddress = null, isActive = false)
        assertNull(p.bleAddress)
        assertFalse(p.isActive)
        assertEquals(BikeProfile.DEFAULT_FIELDS, p.enabledFields)
    }

    @Test fun `DEFAULT_FIELDS contains all 15 field ids`() {
        assertEquals(15, BikeProfile.DEFAULT_FIELDS.size)
    }

    @Test fun `serialize then deserialize round-trips paired active profile`() {
        val original = BikeProfile(
            slot = BikeSlot.ALPHA,
            bleAddress = "AA:BB:CC:DD:EE:FF",
            isActive = true,
            enabledFields = setOf("bosch_ldi_speed", "bosch_ldi_cadence")
        )
        assertEquals(listOf(original), BikeProfile.deserialize(BikeProfile.serialize(listOf(original))))
    }

    @Test fun `serialize then deserialize round-trips unpaired profile`() {
        val original = BikeProfile(slot = BikeSlot.BETA, bleAddress = null, isActive = false)
        assertEquals(listOf(original), BikeProfile.deserialize(BikeProfile.serialize(listOf(original))))
    }

    @Test fun `serialize then deserialize round-trips all 4 slots`() {
        val profiles = BikeSlot.values().mapIndexed { i, slot ->
            BikeProfile(
                slot = slot,
                bleAddress = if (i == 0) "AA:BB:CC:DD:EE:FF" else null,
                isActive = i == 0
            )
        }
        assertEquals(profiles, BikeProfile.deserialize(BikeProfile.serialize(profiles)))
    }

    @Test fun `serialize then deserialize round-trips empty list`() {
        assertTrue(BikeProfile.deserialize(BikeProfile.serialize(emptyList())).isEmpty())
    }

    @Test fun `isValidBleAddress accepts valid MAC in upper and lower case`() {
        assertTrue(BikeProfile.isValidBleAddress("AA:BB:CC:DD:EE:FF"))
        assertTrue(BikeProfile.isValidBleAddress("aa:bb:cc:dd:ee:ff"))
        assertTrue(BikeProfile.isValidBleAddress("0A:1B:2C:3D:4E:5F"))
    }

    @Test fun `isValidBleAddress rejects malformed MAC`() {
        assertFalse(BikeProfile.isValidBleAddress("AA:BB:CC:DD:EE"))
        assertFalse(BikeProfile.isValidBleAddress("AABBCCDDEEFF"))
        assertFalse(BikeProfile.isValidBleAddress("GG:BB:CC:DD:EE:FF"))
        assertFalse(BikeProfile.isValidBleAddress("AA:BB:CC:DD:EE:FF:11"))
        assertFalse(BikeProfile.isValidBleAddress(""))
    }
}
