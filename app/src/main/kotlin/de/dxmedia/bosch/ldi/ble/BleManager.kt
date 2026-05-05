package de.dxmedia.bosch.ldi.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class BleManager(
    private val context: Context,
    private val adapter: BluetoothAdapter =
        context.getSystemService<BluetoothManager>()!!.adapter,
    dispatcher: CoroutineDispatcher = newSingleThreadContext("BleManager")
) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val TARGET_MTU = 247
        const val GATT_TIMEOUT_MS = 30_000L
        const val NOTIFICATION_WATCHDOG_MS = 60_000L
        const val MAX_NOTIFICATIONS_PER_SECOND = 10
        const val MAX_PROTO_BYTES = 16 * 1024

        private const val TAG = "BleManager"
    }

    private val closeableDispatcher: Closeable? = dispatcher as? Closeable
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val mutex = Mutex()

    private val _state = MutableStateFlow<BleState>(BleState.Disconnected)
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val notifications: SharedFlow<ByteArray> = _notifications.asSharedFlow()

    private var activeGatt: BluetoothGatt? = null
    private var ldiCharacteristic: BluetoothGattCharacteristic? = null
    private var bondedAddress: String? = null
    private var watchdogJob: Job? = null
    private var notificationWindowStart = 0L
    private var notificationWindowCount = 0

    private val mtuChannel = Channel<Int>(Channel.CONFLATED)
    private val cccdChannel = Channel<Int>(Channel.CONFLATED)

    internal val gattCallback = GattCallback()
    private val advertiseCallback = BoschAdvertiseCallback()

    fun start(bondedAddress: String? = null) {
        // TODO Task 4
    }

    fun stop() {
        scope.cancel()
        closeableDispatcher?.close()
        try { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        _state.value = BleState.Disconnected
    }

    fun disconnect() {
        scope.launch { mutex.withLock { disconnectInternal() } }
    }

    private fun disconnectInternal() {
        watchdogJob?.cancel()
        watchdogJob = null
        try { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        ldiCharacteristic = null
        _state.value = BleState.Disconnected
    }

    inner class GattCallback : BluetoothGattCallback() {
        // Tasks 5–9
    }

    private inner class BoschAdvertiseCallback : AdvertiseCallback() {
        // Task 4
    }
}
