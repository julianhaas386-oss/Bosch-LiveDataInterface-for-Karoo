package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleManager
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoschLiveDataServiceTest {

    @Test
    fun `BoschLiveDataService exposes bleManager property`() {
        val field = BoschLiveDataService::class.java.declaredFields
            .firstOrNull { it.name == "bleManager" }
        assertNotNull(field, "bleManager field must be declared on BoschLiveDataService")
        assertTrue(
            BleManager::class.java.isAssignableFrom(field!!.type),
            "bleManager must be of type BleManager"
        )
    }
}
