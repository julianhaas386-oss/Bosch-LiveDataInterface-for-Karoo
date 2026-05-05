package de.dxmedia.bosch.ldi.ble

sealed class BleState {
    data object Disconnected : BleState()
    data class Advertising(val bondedAddress: String?) : BleState()
    data class Connected(val deviceAddress: String) : BleState()
}
