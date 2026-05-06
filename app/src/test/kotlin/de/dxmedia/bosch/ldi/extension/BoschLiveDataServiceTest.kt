package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleManager
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoschLiveDataServiceTest {

    @Test fun `BoschLiveDataService declares bleManager property`() {
        val field = BoschLiveDataService::class.java.declaredFields
            .firstOrNull { it.name == "bleManager" }
        assertNotNull(field, "bleManager field must be declared on BoschLiveDataService")
        assertTrue(
            BleManager::class.java.isAssignableFrom(field!!.type),
            "bleManager must be of type BleManager"
        )
    }

    @Test fun `BoschLiveDataService declares decoder field`() {
        val field = BoschLiveDataService::class.java.declaredFields
            .firstOrNull { it.name == "decoder" }
        assertNotNull(field, "decoder field must be declared on BoschLiveDataService")
        assertTrue(
            LiveDataDecoder::class.java.isAssignableFrom(field!!.type),
            "decoder must be of type LiveDataDecoder"
        )
    }

    @Test fun `BoschLiveDataService exposes liveData as StateFlow`() {
        val getter = BoschLiveDataService::class.java.methods
            .firstOrNull { it.name == "getLiveData" }
        assertNotNull(getter, "liveData getter must be accessible on BoschLiveDataService")
        assertTrue(
            StateFlow::class.java.isAssignableFrom(getter!!.returnType),
            "liveData must return StateFlow"
        )
    }
}
