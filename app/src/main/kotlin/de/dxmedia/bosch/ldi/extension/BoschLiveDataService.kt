package de.dxmedia.bosch.ldi.extension

import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataTypeImpl

/**
 * Bosch LiveDataInterface KarooExtension Service.
 *
 * Registers with the Karoo OS and exposes 14 DataTypes (13 Bosch data points
 * + 1 connection status) and one eBike dashboard page.
 *
 * BLE logic: Briefing 2 (BleManager)
 * DataType implementation: Briefing 4 (DataTypeProvider)
 */
class BoschLiveDataService : KarooExtension("bosch-ldi", "1.0.0") {

    override val types: List<DataTypeImpl> = emptyList()
    // TODO Briefing 4: add DataTypeProvider instances for all 14 DataTypes

    override fun onCreate() {
        super.onCreate()
        // TODO Briefing 2: initialize BleManager
    }

    override fun onDestroy() {
        // TODO Briefing 2: call BleManager.disconnect()
        super.onDestroy()
    }
}
