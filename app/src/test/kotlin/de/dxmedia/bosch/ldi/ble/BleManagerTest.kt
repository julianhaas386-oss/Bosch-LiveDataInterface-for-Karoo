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
}
