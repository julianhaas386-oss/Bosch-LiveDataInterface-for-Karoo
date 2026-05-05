package de.dxmedia.bosch.ldi.ble

import android.bluetooth.BluetoothAdapter
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class BleManagerTest {

    private val mockContext = mockk<Context>(relaxed = true)
    private val mockAdapter = mockk<BluetoothAdapter>(relaxed = true)

    private fun manager() = BleManager(
        context = mockContext,
        adapter = mockAdapter,
        dispatcher = UnconfinedTestDispatcher()
    )

    @Test
    fun `initial state is Disconnected`() = runTest {
        val m = manager()
        assertEquals(BleState.Disconnected, m.state.value)
    }

    @Test
    fun `stop() resets state to Disconnected`() = runTest {
        val m = manager()
        // Access internal state — stop() should always land in Disconnected
        m.stop()
        assertEquals(BleState.Disconnected, m.state.value)
    }

    @Test
    fun `start() transitions state to Advertising`() = runTest {
        val mockAdvertiser = mockk<android.bluetooth.le.BluetoothLeAdvertiser>(relaxed = true)
        every { mockAdapter.bluetoothLeAdvertiser } returns mockAdvertiser

        val m = manager()
        m.start(bondedAddress = null)

        assertEquals(BleState.Advertising(null), m.state.value)
    }

    @Test
    fun `start() with bonded address stores it in state`() = runTest {
        val mockAdvertiser = mockk<android.bluetooth.le.BluetoothLeAdvertiser>(relaxed = true)
        every { mockAdapter.bluetoothLeAdvertiser } returns mockAdvertiser

        val m = manager()
        m.start(bondedAddress = "AA:BB:CC:DD:EE:FF")

        assertEquals(BleState.Advertising("AA:BB:CC:DD:EE:FF"), m.state.value)
    }

    @Test
    fun `start() no-ops when already Advertising`() = runTest {
        val mockAdvertiser = mockk<android.bluetooth.le.BluetoothLeAdvertiser>(relaxed = true)
        every { mockAdapter.bluetoothLeAdvertiser } returns mockAdvertiser

        val m = manager()
        m.start(null)
        m.start(null) // second call must be ignored

        io.mockk.verify(exactly = 1) {
            mockAdvertiser.startAdvertising(any(), any(), any())
        }
    }

    @Test
    fun `start() stays Disconnected when advertiser is null`() = runTest {
        every { mockAdapter.bluetoothLeAdvertiser } returns null

        val m = manager()
        m.start(null)

        assertEquals(BleState.Disconnected, m.state.value)
    }
}
