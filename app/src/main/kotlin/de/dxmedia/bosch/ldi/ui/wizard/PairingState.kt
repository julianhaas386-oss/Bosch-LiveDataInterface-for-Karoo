package de.dxmedia.bosch.ldi.ui.wizard

sealed class PairingState {
    data object Explaining : PairingState()
    data class Advertising(val secondsLeft: Int) : PairingState()
    data class Success(val deviceAddress: String) : PairingState()
    data object Failure : PairingState()
}
