package de.dxmedia.bosch.ldi.ble

/**
 * Models the BLE connection lifecycle for the Bosch LDI interface.
 *
 * [Disconnected] — no BLE activity; initial and idle state.
 * [Advertising]  — peripheral is advertising service solicitation UUID;
 *                  [Advertising.bondedAddress] is null in pairing mode (no prior bond).
 * [Connected]    — eBike central has connected; [Connected.deviceAddress] holds the peer MAC.
 *
 * Exposed via [BleManager.state] as a `StateFlow<BleState>`.
 */
sealed class BleState {
    data object Disconnected : BleState()
    data class Advertising(val bondedAddress: String?) : BleState()
    data class Connected(val deviceAddress: String) : BleState()
}
