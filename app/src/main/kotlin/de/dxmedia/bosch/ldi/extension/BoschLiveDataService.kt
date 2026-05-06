package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleManager
import de.dxmedia.bosch.ldi.data.BikeRepository
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BoschLiveDataService : KarooExtension("bosch-ldi", "1.0.0") {

    lateinit var bleManager: BleManager
        private set

    private val decoder = LiveDataDecoder()

    private val _liveData = MutableStateFlow<BoschLiveData?>(null)
    val liveData: StateFlow<BoschLiveData?> = _liveData.asStateFlow()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val types: List<DataTypeImpl> = emptyList()
    // Briefing 4: replace with DataTypeProvider instances for all 15 DataTypes

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this)
        val activeProfile = BikeRepository(this).getActiveProfile()
        bleManager.start(activeProfile?.bleAddress)
        serviceScope.launch {
            bleManager.notifications.collect { bytes ->
                _liveData.value = decoder.decode(bytes)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (::bleManager.isInitialized) bleManager.stop()
        super.onDestroy()
    }
}
