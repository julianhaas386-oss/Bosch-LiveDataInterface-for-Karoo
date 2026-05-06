package de.dxmedia.bosch.ldi.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BikeProfileTest {

    @Test fun `create generates unique non-blank ids`() {
        val a = BikeProfile.create("Trek", "AA:BB:CC:DD:EE:FF")
        val b = BikeProfile.create("Trek", "AA:BB:CC:DD:EE:FF")
        assertTrue(a.id.isNotBlank())
        assertNotEquals(a.id, b.id)
    }

    @Test fun `create sets isActive false and full default field set`() {
        val p = BikeProfile.create("Trek", "AA:BB:CC:DD:EE:FF")
        assertFalse(p.isActive)
        assertEquals(BikeProfile.DEFAULT_FIELDS, p.enabledFields)
    }

    @Test fun `DEFAULT_FIELDS contains all 15 field ids`() {
        assertEquals(15, BikeProfile.DEFAULT_FIELDS.size)
    }

    @Test fun `serialize then deserialize round-trips single profile`() {
        val original = BikeProfile.create("Trek Allant", "AA:BB:CC:DD:EE:FF")
            .copy(isActive = true, enabledFields = setOf("bosch_ldi_speed", "bosch_ldi_cadence"))
        val result = BikeProfile.deserialize(BikeProfile.serialize(listOf(original)))
        assertEquals(listOf(original), result)
    }

    @Test fun `serialize then deserialize round-trips multiple profiles`() {
        val profiles = listOf(
            BikeProfile.create("Trek", "AA:BB:CC:DD:EE:FF"),
            BikeProfile.create("Giant", "11:22:33:44:55:66").copy(isActive = true)
        )
        assertEquals(profiles, BikeProfile.deserialize(BikeProfile.serialize(profiles)))
    }

    @Test fun `serialize then deserialize round-trips empty list`() {
        val result = BikeProfile.deserialize(BikeProfile.serialize(emptyList()))
        assertTrue(result.isEmpty())
    }

    @Test fun `isValidName accepts letters digits spaces hyphens underscores`() {
        assertTrue(BikeProfile.isValidName("Trek Allant"))
        assertTrue(BikeProfile.isValidName("My_Bike-1"))
        assertTrue(BikeProfile.isValidName("A".repeat(64)))
    }

    @Test fun `isValidName rejects blank, all-space, too long, and special chars`() {
        assertFalse(BikeProfile.isValidName(""))
        assertFalse(BikeProfile.isValidName("   "))
        assertFalse(BikeProfile.isValidName("A".repeat(65)))
        assertFalse(BikeProfile.isValidName("Bike+Pro"))
        assertFalse(BikeProfile.isValidName("Bike@Home"))
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
