package de.dxmedia.bosch.ldi.extension

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BoschDataTypeTest {

    private val fullData = BoschLiveData(
        speedCmPerHour = 2830,
        cadenceRpm = 75,
        riderPowerW = 245,
        ambientBrightnessMilliLux = 50000,
        batterySocPercent = 87,
        timeUtcSeconds = 1_714_999_200L,
        odometerMeters = 12_847_000,
        bikeLight = LightState.ON,
        systemLocked = false,
        chargerConnected = true,
        lightReserveState = false,
        diagnosisProgramActive = true,
        bikeNotDriving = false
    )
    private val flow = MutableStateFlow<BoschLiveData?>(null)
    private val types = BoschDataType.allTypes(flow)

    private fun extract(typeId: String, data: BoschLiveData = fullData): Double? =
        types.first { it.dataTypeId == typeId }.extract(data)

    @Test fun `allTypes returns 13 types with distinct IDs`() {
        assertEquals(13, types.size)
        assertEquals(13, types.map { it.dataTypeId }.toSet().size)
    }

    @Test fun `speed converts centimetres per hour to km per hour`() {
        assertEquals(28.3, extract("bosch_ldi_speed")!!, 0.001)
    }

    @Test fun `cadence passes through rpm as double`() {
        assertEquals(75.0, extract("bosch_ldi_cadence")!!, 0.001)
    }

    @Test fun `rider power passes through watts as double`() {
        assertEquals(245.0, extract("bosch_ldi_rider_power")!!, 0.001)
    }

    @Test fun `ambient brightness converts milli-lux to lux`() {
        assertEquals(50.0, extract("bosch_ldi_ambient_brightness")!!, 0.001)
    }

    @Test fun `battery SOC passes through percent`() {
        assertEquals(87.0, extract("bosch_ldi_battery_soc")!!, 0.001)
    }

    @Test fun `time passes through unix seconds as double`() {
        assertEquals(1_714_999_200.0, extract("bosch_ldi_time")!!, 0.001)
    }

    @Test fun `odometer passes through metres as double`() {
        assertEquals(12_847_000.0, extract("bosch_ldi_odometer")!!, 0.001)
    }

    @Test fun `bike light ON maps to 1_0`() {
        assertEquals(1.0, extract("bosch_ldi_bike_light")!!, 0.001)
    }

    @Test fun `bike light OFF maps to 0_0`() {
        assertEquals(0.0, extract("bosch_ldi_bike_light", fullData.copy(bikeLight = LightState.OFF))!!, 0.001)
    }

    @Test fun `bike light null returns null`() {
        assertNull(extract("bosch_ldi_bike_light", fullData.copy(bikeLight = null)))
    }

    @Test fun `system locked false maps to 0_0`() {
        assertEquals(0.0, extract("bosch_ldi_system_locked")!!, 0.001)
    }

    @Test fun `charger connected true maps to 1_0`() {
        assertEquals(1.0, extract("bosch_ldi_charger_connected")!!, 0.001)
    }

    @Test fun `diagnosis program active true maps to 1_0`() {
        assertEquals(1.0, extract("bosch_ldi_diagnosis_program_active")!!, 0.001)
    }

    @Test fun `bike not driving false maps to 0_0`() {
        assertEquals(0.0, extract("bosch_ldi_bike_not_driving")!!, 0.001)
    }

    @Test fun `light reserve state false maps to 0_0`() {
        assertEquals(0.0, extract("bosch_ldi_light_reserve_state")!!, 0.001)
    }

    @Test fun `all null fields return null`() {
        val empty = BoschLiveData()
        types.forEach { type ->
            assertNull(type.extract(empty), "Expected null for ${type.dataTypeId} when all fields null")
        }
    }

    @Test fun `all 13 expected type IDs are present`() {
        val ids = types.map { it.dataTypeId }.toSet()
        listOf(
            "bosch_ldi_speed", "bosch_ldi_cadence", "bosch_ldi_rider_power",
            "bosch_ldi_ambient_brightness", "bosch_ldi_battery_soc", "bosch_ldi_time",
            "bosch_ldi_odometer", "bosch_ldi_bike_light", "bosch_ldi_system_locked",
            "bosch_ldi_charger_connected", "bosch_ldi_light_reserve_state",
            "bosch_ldi_diagnosis_program_active", "bosch_ldi_bike_not_driving"
        ).forEach { id -> assertNotNull(ids.contains(id), "Missing type ID: $id") }
    }
}
