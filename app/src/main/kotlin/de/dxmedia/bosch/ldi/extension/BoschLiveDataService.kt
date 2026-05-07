package de.dxmedia.bosch.ldi.extension

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import de.dxmedia.bosch.ldi.ble.BleManager
import de.dxmedia.bosch.ldi.ble.BleState
import de.dxmedia.bosch.ldi.data.BikeRepository
import de.dxmedia.bosch.ldi.data.BikeSlot
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BoschLiveDataService : KarooExtension("bosch-ldi", "1.0.0") {

    inner class LocalBinder : Binder() {
        val service: BoschLiveDataService get() = this@BoschLiveDataService
    }

    private val binder = LocalBinder()

    private lateinit var repository: BikeRepository
    private val decoder = LiveDataDecoder()

    private val _liveData = MutableStateFlow<BoschLiveData?>(null)
    val liveData: StateFlow<BoschLiveData?> = _liveData.asStateFlow()

    private val _connectionState = MutableStateFlow<BleState>(BleState.Disconnected)
    val connectionState: StateFlow<BleState> = _connectionState.asStateFlow()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var stateJob: Job? = null
    private var notifJob: Job? = null

    private var _bleManager: BleManager? = null

    override val types: List<DataTypeImpl> =
        BoschDataType.allTypes(liveData) +
        listOf(
            ConnectionDataType(connectionState),
            EBikePage(liveData)
        )

    override fun onCreate() {
        super.onCreate()
        repository = BikeRepository(this)
        startBleManager(repository.getActiveProfile()?.bleAddress)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        _bleManager?.stop()
        super.onDestroy()
    }

    fun startPairing(slot: BikeSlot) {
        repository.setActive(slot)
        startBleManager(null)
    }

    fun onPairingSuccess(slot: BikeSlot, bleAddress: String) {
        val profile = repository.getProfiles().first { it.slot == slot }
        repository.upsert(profile.copy(bleAddress = bleAddress))
        startBleManager(bleAddress)
    }

    fun setActiveSlot(slot: BikeSlot) {
        repository.setActive(slot)
        startBleManager(repository.getActiveProfile()?.bleAddress)
    }

    private fun startBleManager(address: String?) {
        stateJob?.cancel()
        notifJob?.cancel()
        _bleManager?.stop()
        val mgr = BleManager(this)
        _bleManager = mgr
        mgr.start(address)
        stateJob = serviceScope.launch {
            mgr.state.collect { _connectionState.value = it }
        }
        notifJob = serviceScope.launch {
            mgr.notifications.collect { bytes ->
                _liveData.value = decoder.decode(bytes)
            }
        }
    }
}
