package de.dxmedia.bosch.ldi.ui.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.dxmedia.bosch.ldi.ble.BleState
import de.dxmedia.bosch.ldi.data.BikeSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PairingWizardViewModel(
    val slot: BikeSlot,
    private val connectionState: StateFlow<BleState>,
    private val onStartPairing: () -> Unit,
    private val testScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = testScope ?: viewModelScope

    private val _state = MutableStateFlow<PairingState>(PairingState.Explaining)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var pairingJob: Job? = null

    fun startPairing() {
        pairingJob?.cancel()
        onStartPairing()
        pairingJob = scope.launch {
            _state.value = PairingState.Advertising(60)
            val countdownJob = launch {
                repeat(60) { elapsed ->
                    delay(1_000L)
                    val remaining = 59 - elapsed
                    if (_state.value is PairingState.Advertising) {
                        _state.value = PairingState.Advertising(remaining)
                    }
                }
            }
            val result = withTimeoutOrNull(60_000L) {
                connectionState.first { it is BleState.Connected }
            }
            countdownJob.cancel()
            _state.value = if (result is BleState.Connected) {
                PairingState.Success(result.deviceAddress)
            } else {
                PairingState.Failure
            }
        }
    }

    fun cancel() {
        pairingJob?.cancel()
        pairingJob = null
        _state.value = PairingState.Explaining
    }

    override fun onCleared() {
        pairingJob?.cancel()
    }
}
