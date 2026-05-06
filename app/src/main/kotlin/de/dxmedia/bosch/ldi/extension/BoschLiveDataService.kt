package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleManager
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension

class BoschLiveDataService : KarooExtension("bosch-ldi", "1.0.0") {

    lateinit var bleManager: BleManager
        private set

    override val types: List<DataTypeImpl> = emptyList()
    // TODO Briefing 4: add DataTypeProvider instances for all 14 DataTypes

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this)
        // Briefing 3 reads the saved BikeProfile and calls bleManager.start(bondedAddress)
    }

    override fun onDestroy() {
        if (::bleManager.isInitialized) bleManager.stop()
        super.onDestroy()
    }
}
