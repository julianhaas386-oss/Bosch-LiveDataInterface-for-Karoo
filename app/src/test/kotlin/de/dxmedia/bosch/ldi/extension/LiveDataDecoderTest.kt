package de.dxmedia.bosch.ldi.extension

import com.bosch.ebike.LiveData
import com.bosch.ebike.LightState as ProtoLightState
import de.dxmedia.bosch.ldi.ble.BleManager
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveDataDecoderTest {

    private fun proto(block: LiveData.Builder.() -> Unit): ByteArray =
        LiveData.newBuilder().apply(block).build().toByteArray()

    @Test fun `decode sets only present fields, leaves rest null`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto { speed = 2500 })
        assertEquals(2500, result.speedCmPerHour)
        assertNull(result.cadenceRpm)
        assertNull(result.riderPowerW)
    }

    @Test fun `decode merges with previous state`() {
        val decoder = LiveDataDecoder()
        decoder.decode(proto { speed = 2500 })
        val result = decoder.decode(proto { cadence = 90 })
        assertEquals(2500, result.speedCmPerHour)  // from previous message
        assertEquals(90, result.cadenceRpm)         // from this message
    }

    @Test fun `later value overwrites earlier for same field`() {
        val decoder = LiveDataDecoder()
        decoder.decode(proto { speed = 2500 })
        val result = decoder.decode(proto { speed = 3000 })
        assertEquals(3000, result.speedCmPerHour)
    }

    @Test fun `decode maps LIGHT_STATE_ON to LightState dot ON`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto { bikeLight = ProtoLightState.LIGHT_STATE_ON })
        assertEquals(LightState.ON, result.bikeLight)
    }

    @Test fun `decode maps LIGHT_STATE_OFF to LightState dot OFF`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto { bikeLight = ProtoLightState.LIGHT_STATE_OFF })
        assertEquals(LightState.OFF, result.bikeLight)
    }

    @Test fun `decode maps LIGHT_STATE_INVALID to null`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto { bikeLight = ProtoLightState.LIGHT_STATE_INVALID })
        assertNull(result.bikeLight)
    }

    @Test fun `decode returns last known state on parse error`() {
        val decoder = LiveDataDecoder()
        decoder.decode(proto { speed = 1000 })
        val result = decoder.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertEquals(1000, result.speedCmPerHour)  // unchanged
    }

    @Test fun `decode drops oversized payload and returns last state`() {
        val decoder = LiveDataDecoder()
        decoder.decode(proto { speed = 999 })
        val oversized = ByteArray(BleManager.MAX_PROTO_BYTES + 1)
        val result = decoder.decode(oversized)
        assertEquals(999, result.speedCmPerHour)  // unchanged
    }

    @Test fun `decode handles all numeric fields`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto {
            speed = 3500
            cadence = 85
            riderPower = 200
            ambientBrightness = 50000
            batterySoc = 80
            time = 1_700_000_000L
            odometer = 12345
        })
        assertEquals(3500, result.speedCmPerHour)
        assertEquals(85, result.cadenceRpm)
        assertEquals(200, result.riderPowerW)
        assertEquals(50000, result.ambientBrightnessMilliLux)
        assertEquals(80, result.batterySocPercent)
        assertEquals(1_700_000_000L, result.timeUtcSeconds)
        assertEquals(12345, result.odometerMeters)
    }

    @Test fun `decode handles all boolean fields`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto {
            systemLocked = true
            chargerConnected = false
            lightReserveState = true
            diagnosisProgramActive = false
            bikeNotDriving = true
        })
        assertEquals(true, result.systemLocked)
        assertEquals(false, result.chargerConnected)
        assertEquals(true, result.lightReserveState)
        assertEquals(false, result.diagnosisProgramActive)
        assertEquals(true, result.bikeNotDriving)
    }
}
