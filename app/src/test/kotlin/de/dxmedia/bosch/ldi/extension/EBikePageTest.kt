package de.dxmedia.bosch.ldi.extension

import org.junit.Test
import kotlin.test.assertEquals

class EBikePageTest {

    @Test fun `formatDashboard formats all fields from connected BoschLiveData`() {
        val data = BoschLiveData(
            batterySocPercent = 87,
            riderPowerW = 245,
            cadenceRpm = 75,
            speedCmPerHour = 2830,
            odometerMeters = 12_847_000
        )
        val fmt = formatDashboard(data)
        assertEquals("87 %", fmt.soc)
        assertEquals("245 W", fmt.power)
        assertEquals("75 rpm", fmt.cadence)
        assertEquals("28 km/h", fmt.speed)
        assertEquals("Odo: 12847 km", fmt.odometer)
    }

    @Test fun `formatDashboard shows dashes for null BoschLiveData`() {
        val fmt = formatDashboard(null)
        assertEquals("—", fmt.soc)
        assertEquals("—", fmt.power)
        assertEquals("—", fmt.cadence)
        assertEquals("—", fmt.speed)
        assertEquals("Odo: —", fmt.odometer)
    }

    @Test fun `formatDashboard shows dashes for partial data`() {
        val data = BoschLiveData(batterySocPercent = 50)
        val fmt = formatDashboard(data)
        assertEquals("50 %", fmt.soc)
        assertEquals("—", fmt.power)
        assertEquals("—", fmt.cadence)
        assertEquals("—", fmt.speed)
    }

    @Test fun `speed rounds down to whole km per hour`() {
        val fmt = formatDashboard(BoschLiveData(speedCmPerHour = 2899))
        assertEquals("28 km/h", fmt.speed)
    }

    @Test fun `odometer converts metres to km`() {
        val fmt = formatDashboard(BoschLiveData(odometerMeters = 1500))
        assertEquals("Odo: 1 km", fmt.odometer)
    }
}
