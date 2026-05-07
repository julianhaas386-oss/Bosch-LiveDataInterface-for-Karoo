package de.dxmedia.bosch.ldi.ui.wizard

import de.dxmedia.bosch.ldi.ble.BleState
import de.dxmedia.bosch.ldi.data.BikeSlot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PairingWizardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun makeVm(
        connectionState: MutableStateFlow<BleState> = MutableStateFlow(BleState.Disconnected),
        onStartPairing: () -> Unit = {}
    ) = PairingWizardViewModel(
        slot = BikeSlot.ALPHA,
        connectionState = connectionState,
        onStartPairing = onStartPairing,
        scope = scope
    )

    @Test
    fun `initial state is Explaining`() = scope.runTest {
        val vm = makeVm()
        assertIs<PairingState.Explaining>(vm.state.value)
    }

    @Test
    fun `startPairing transitions to Advertising with 60 seconds`() = scope.runTest {
        val vm = makeVm()
        vm.startPairing()
        advanceTimeBy(1)
        assertIs<PairingState.Advertising>(vm.state.value)
        assertEquals(60, (vm.state.value as PairingState.Advertising).secondsLeft)
    }

    @Test
    fun `startPairing calls onStartPairing callback`() = scope.runTest {
        var called = false
        val vm = makeVm(onStartPairing = { called = true })
        vm.startPairing()
        advanceTimeBy(1)
        assertTrue(called)
    }

    @Test
    fun `countdown decrements every second`() = scope.runTest {
        val vm = makeVm()
        vm.startPairing()
        advanceTimeBy(3_001)
        assertEquals(57, (vm.state.value as PairingState.Advertising).secondsLeft)
    }

    @Test
    fun `BLE Connected state transitions to Success`() = scope.runTest {
        val connectionState = MutableStateFlow<BleState>(BleState.Disconnected)
        val vm = makeVm(connectionState = connectionState)
        vm.startPairing()
        advanceTimeBy(1)
        connectionState.value = BleState.Connected("AA:BB:CC:DD:EE:FF")
        advanceTimeBy(1)
        val state = vm.state.value
        assertIs<PairingState.Success>(state)
        assertEquals("AA:BB:CC:DD:EE:FF", state.deviceAddress)
    }

    @Test
    fun `timeout after 60 seconds transitions to Failure`() = scope.runTest {
        val vm = makeVm()
        vm.startPairing()
        advanceTimeBy(61_000)
        assertIs<PairingState.Failure>(vm.state.value)
    }

    @Test
    fun `cancel stops countdown and transitions to Explaining`() = scope.runTest {
        val vm = makeVm()
        vm.startPairing()
        advanceTimeBy(5_000)
        vm.cancel()
        advanceTimeBy(1)
        assertIs<PairingState.Explaining>(vm.state.value)
    }
}
