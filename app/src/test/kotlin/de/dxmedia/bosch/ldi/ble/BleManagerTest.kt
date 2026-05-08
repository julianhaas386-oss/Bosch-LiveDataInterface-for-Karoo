package de.dxmedia.bosch.ldi.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import de.dxmedia.bosch.ldi.ble.BleManager.Companion.CHARACTERISTIC_UUID
import de.dxmedia.bosch.ldi.ble.BleManager.Companion.SERVICE_UUID
import de.dxmedia.bosch.ldi.ble.BleManager.Companion.TARGET_MTU
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class BleManagerTest {

    private val mockContext = mockk<Context>(relaxed = true)
    private val mockAdapter = mockk<BluetoothAdapter>(relaxed = true)

    private inner class TestBleManager : BleManager(
        context = mockContext,
        adapter = mockAdapter,
        dispatcher = UnconfinedTestDispatcher()
    ) {
        override fun buildAdvertiseSettings(mode: Int): AdvertiseSettings = mockk(relaxed = true)
        override fun buildAdvertiseData(): AdvertiseData = mockk(relaxed = true)
        override fun buildScanResponse(): AdvertiseData = mockk(relaxed = true)
        override fun openGattServer() {} // no-op: avoids BluetoothManager system service calls in tests
    }

    private fun manager() = TestBleManager()

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
            mockAdvertiser.startAdvertising(any(), any(), any(), any())
        }
    }

    @Test
    fun `start() stays Disconnected when advertiser is null`() = runTest {
        every { mockAdapter.bluetoothLeAdvertiser } returns null

        val m = manager()
        m.start(null)

        assertEquals(BleState.Disconnected, m.state.value)
    }

    @Test
    fun `onConnectionStateChange CONNECTED starts service discovery`() = runTest {
        val mockGatt = mockk<BluetoothGatt>(relaxed = true)
        val mockDevice = mockk<android.bluetooth.BluetoothDevice>(relaxed = true)
        every { mockGatt.device } returns mockDevice
        every { mockDevice.address } returns "AA:BB:CC:DD:EE:FF"
        every { mockGatt.discoverServices() } returns true

        val m = manager()
        m.gattCallback.onConnectionStateChange(
            mockGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED
        )

        io.mockk.verify { mockGatt.discoverServices() }
    }

    @Test
    fun `onConnectionStateChange DISCONNECTED sets state to Disconnected`() = runTest {
        val mockGatt = mockk<BluetoothGatt>(relaxed = true)
        every { mockAdapter.bluetoothLeAdvertiser } returns null // no advertiser → no re-advertise

        val m = manager()
        m.gattCallback.onConnectionStateChange(
            mockGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED
        )

        assertEquals(BleState.Disconnected, m.state.value)
    }

    @Test
    fun `onServicesDiscovered GATT_SUCCESS with missing service disconnects`() = runTest {
        val mockGatt = mockk<BluetoothGatt>(relaxed = true)
        every { mockGatt.getService(SERVICE_UUID) } returns null

        val m = manager()
        m.gattCallback.onServicesDiscovered(mockGatt, BluetoothGatt.GATT_SUCCESS)

        io.mockk.verify { mockGatt.disconnect() }
    }

    @Test
    fun `onServicesDiscovered with missing characteristic disconnects`() = runTest {
        val mockGatt = mockk<BluetoothGatt>(relaxed = true)
        val mockService = mockk<android.bluetooth.BluetoothGattService>(relaxed = true)
        every { mockGatt.getService(SERVICE_UUID) } returns mockService
        every { mockService.getCharacteristic(CHARACTERISTIC_UUID) } returns null

        val m = manager()
        m.gattCallback.onServicesDiscovered(mockGatt, BluetoothGatt.GATT_SUCCESS)

        io.mockk.verify { mockGatt.disconnect() }
    }

    @Test
    fun `onMtuChanged with mtu less than 247 disconnects`() = runTest {
        val mockGatt = mockk<BluetoothGatt>(relaxed = true)

        val m = manager()
        m.gattCallback.onMtuChanged(mockGatt, 23, BluetoothGatt.GATT_SUCCESS)

        io.mockk.verify { mockGatt.disconnect() }
    }

    @Test
    fun `onMtuChanged with mtu 247 requests HIGH connection priority`() = runTest {
        val mockGatt = mockk<BluetoothGatt>(relaxed = true)

        val m = manager()
        m.gattCallback.onMtuChanged(mockGatt, 247, BluetoothGatt.GATT_SUCCESS)

        io.mockk.verify {
            mockGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }
    }

    @Test
    fun `requestMtu is called after service discovery`() = runTest {
        val mockGatt = mockk<BluetoothGatt>(relaxed = true)
        val mockService = mockk<android.bluetooth.BluetoothGattService>(relaxed = true)
        val mockChar = mockk<android.bluetooth.BluetoothGattCharacteristic>(relaxed = true)
        every { mockGatt.getService(SERVICE_UUID) } returns mockService
        every { mockService.getCharacteristic(CHARACTERISTIC_UUID) } returns mockChar

        val m = manager()
        m.gattCallback.onServicesDiscovered(mockGatt, BluetoothGatt.GATT_SUCCESS)

        io.mockk.verify { mockGatt.requestMtu(TARGET_MTU) }
    }

    @Test
    fun `notifications larger than 16KB are dropped`() = runTest {
        val mockGatt = mockk<BluetoothGatt>(relaxed = true)
        val mockChar = mockk<android.bluetooth.BluetoothGattCharacteristic>(relaxed = true)
        val largePayload = ByteArray(17 * 1024)

        val m = manager()
        val received = mutableListOf<ByteArray>()
        val job = launch { m.notifications.collect { received.add(it) } }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            m.gattCallback.onCharacteristicChanged(mockGatt, mockChar, largePayload)
        } else {
            every { mockChar.value } returns largePayload
            @Suppress("DEPRECATION")
            m.gattCallback.onCharacteristicChanged(mockGatt, mockChar)
        }

        job.cancel()
        assertEquals(0, received.size)
    }

    @Test
    fun `valid notifications are emitted to notifications flow`() = runTest(UnconfinedTestDispatcher()) {
        val mockGatt = mockk<BluetoothGatt>(relaxed = true)
        val mockChar = mockk<android.bluetooth.BluetoothGattCharacteristic>(relaxed = true)
        val payload = byteArrayOf(1, 2, 3, 4)

        val m = manager()
        val received = mutableListOf<ByteArray>()
        val job = launch { m.notifications.collect { received.add(it) } }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            m.gattCallback.onCharacteristicChanged(mockGatt, mockChar, payload)
        } else {
            every { mockChar.value } returns payload
            @Suppress("DEPRECATION")
            m.gattCallback.onCharacteristicChanged(mockGatt, mockChar)
        }

        job.cancel()
        assertEquals(1, received.size)
        assertEquals(payload.toList(), received[0].toList())
    }

    @Test
    fun `onBondLost transitions state to Disconnected`() = runTest {
        val mockDevice = mockk<BluetoothDevice>(relaxed = true)

        val m = manager()
        m.simulateBondStateChange(mockDevice, BluetoothDevice.BOND_NONE)

        assertEquals(BleState.Disconnected, m.state.value)
    }

    @Test
    fun `onConnectionStateChange DISCONNECTED while Connected triggers re-advertise`() = runTest {
        val mockAdvertiser = mockk<android.bluetooth.le.BluetoothLeAdvertiser>(relaxed = true)
        every { mockAdapter.bluetoothLeAdvertiser } returns mockAdvertiser
        val mockGatt = mockk<BluetoothGatt>(relaxed = true)

        val m = manager()
        m.start(bondedAddress = "AA:BB:CC:DD:EE:FF")
        m.gattCallback.onConnectionStateChange(
            mockGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED
        )

        assertEquals(BleState.Advertising("AA:BB:CC:DD:EE:FF"), m.state.value)
    }

    @Test
    fun `disconnect then start re-enters Advertising state`() = runTest {
        val mockAdvertiser = mockk<android.bluetooth.le.BluetoothLeAdvertiser>(relaxed = true)
        every { mockAdapter.bluetoothLeAdvertiser } returns mockAdvertiser

        val m = manager()
        m.start(bondedAddress = null)
        assertEquals(BleState.Advertising(null), m.state.value)

        m.disconnect()
        assertEquals(BleState.Disconnected, m.state.value)

        m.start(bondedAddress = null)
        assertEquals(BleState.Advertising(null), m.state.value)
    }
}
