package de.dxmedia.bosch.ldi.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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

/**
 * Manages the full BLE lifecycle for the Bosch LiveDataInterface:
 * advertising (GAP Peripheral), GATT client operations, bonding, and reconnect.
 *
 * **Threading:** All state mutations run on a single-threaded [CoroutineScope] and are
 * additionally protected by [mutex] for atomicity. GATT callbacks from the Android BLE
 * stack are dispatched into the scope via [kotlinx.coroutines.launch].
 *
 * **Lifecycle:** Call [start] to begin advertising (bonded address = null for pairing mode).
 * The eBike initiates the GATT connection after seeing the solicitation UUID. Call [stop]
 * from [android.app.Service.onDestroy] to release all resources.
 *
 * Exposes [state] ([BleState]) and [notifications] (raw Protobuf [ByteArray]) as flows
 * for upstream consumers ([BoschLiveDataService], DataTypeProvider in Briefing 4).
 */
@SuppressLint("MissingPermission")
open class BleManager(
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
    private var bondLostPending = false

    private val mtuChannel = Channel<Int>(Channel.CONFLATED)
    private val cccdChannel = Channel<Int>(Channel.CONFLATED)

    internal val gattCallback = GattCallback()
    private val advertiseCallback = BoschAdvertiseCallback()
    private val bondReceiver = BondReceiver()

    fun start(bondedAddress: String? = null) {
        scope.launch {
            mutex.withLock {
                if (_state.value !is BleState.Disconnected) return@withLock
                this@BleManager.bondedAddress = bondedAddress
                val advertiser = adapter.bluetoothLeAdvertiser ?: run {
                    Log.e(TAG, "LE advertising not supported on this device")
                    return@withLock
                }
                val mode = if (bondedAddress == null) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                           else AdvertiseSettings.ADVERTISE_MODE_BALANCED
                val settings = buildAdvertiseSettings(mode)
                val data = buildAdvertiseData()
                advertiser.startAdvertising(settings, data, advertiseCallback)
                val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                context.registerReceiver(bondReceiver, filter)
                _state.value = BleState.Advertising(bondedAddress)
            }
        }
    }

    fun stop() {
        scope.cancel()
        closeableDispatcher?.close()
        try { context.unregisterReceiver(bondReceiver) } catch (_: IllegalArgumentException) {}
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
        try { context.unregisterReceiver(bondReceiver) } catch (_: IllegalArgumentException) {}
        watchdogJob?.cancel()
        watchdogJob = null
        try { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        ldiCharacteristic = null
        bondLostPending = false
        _state.value = BleState.Disconnected
    }

    inner class GattCallback : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                mutex.withLock {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            val expectedAddress = bondedAddress
                            if (expectedAddress != null && gatt.device.address != expectedAddress) {
                                Log.w(TAG, "Connection from unexpected device ${gatt.device.address}, expected $expectedAddress — rejecting")
                                gatt.disconnect()
                                return@withLock
                            }
                            Log.i(TAG, "GATT connected — starting service discovery")
                            activeGatt = gatt
                            _state.value = BleState.Connected(gatt.device.address)
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.i(TAG, "GATT disconnected (status=$status)")
                            gatt.close()
                            activeGatt = null
                            ldiCharacteristic = null
                            watchdogJob?.cancel()
                            watchdogJob = null
                            _state.value = BleState.Disconnected
                            if (!bondLostPending) {
                                val lastAddress = bondedAddress
                                Log.i(TAG, "Link loss detected — re-advertising for reconnect")
                                val advertiser = adapter.bluetoothLeAdvertiser
                                if (advertiser != null) {
                                    try { context.unregisterReceiver(bondReceiver) } catch (_: IllegalArgumentException) {}
                                    val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                                    context.registerReceiver(bondReceiver, filter)
                                    val settings = buildAdvertiseSettings(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                                    val data = buildAdvertiseData()
                                    advertiser.startAdvertising(settings, data, advertiseCallback)
                                    _state.value = BleState.Advertising(lastAddress)
                                }
                            }
                            bondLostPending = false
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            scope.launch {
                mutex.withLock {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "Service discovery failed: status=$status")
                        gatt.disconnect()
                        return@withLock
                    }
                    val service = gatt.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic == null) {
                        Log.e(TAG, "LDI service or characteristic not found — disconnecting")
                        gatt.disconnect()
                        return@withLock
                    }
                    ldiCharacteristic = characteristic
                    Log.i(TAG, "LDI characteristic found — requesting MTU")
                    gatt.requestMtu(TARGET_MTU)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                mutex.withLock {
                    mtuChannel.trySend(mtu)
                    if (status != BluetoothGatt.GATT_SUCCESS || mtu < TARGET_MTU) {
                        Log.e(TAG, "MTU negotiated=$mtu (need $TARGET_MTU) — disconnecting (LDI-003)")
                        gatt.disconnect()
                        return@withLock
                    }
                    Log.i(TAG, "MTU=$mtu OK — requesting HIGH priority (triggers DLE for LDI-001)")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    delay(500L)
                    enableNotifications(gatt)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            cccdChannel.trySend(status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "CCCD written — notifications enabled, watchdog armed")
                scope.launch { mutex.withLock { resetWatchdog() } }
            } else {
                Log.e(TAG, "CCCD write failed: status=$status — disconnecting")
                scope.launch { mutex.withLock { disconnectInternal() } }
            }
        }

        @Deprecated("Deprecated in Android 13 (API 33)")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handleNotification(characteristic.value ?: return)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(value)
        }
    }

    internal open fun buildAdvertiseSettings(mode: Int): AdvertiseSettings =
        AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setConnectable(true)
            .setTimeout(0)
            .build()

    internal open fun buildAdvertiseData(): AdvertiseData =
        AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceSolicitationUuid(ParcelUuid(SERVICE_UUID))
            .build()

    private fun enableNotifications(gatt: BluetoothGatt) {
        val characteristic = ldiCharacteristic ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD_UUID) ?: run {
            Log.e(TAG, "CCCD descriptor not found — disconnecting")
            gatt.disconnect()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private fun handleNotification(value: ByteArray) {
        scope.launch {
            val shouldEmit = mutex.withLock {
                if (value.size > MAX_PROTO_BYTES) {
                    Log.e(TAG, "Proto payload ${value.size}B > 16KB limit — disconnecting")
                    disconnectInternal()
                    return@withLock false
                }
                val now = System.currentTimeMillis()
                if (now - notificationWindowStart >= 1_000L) {
                    notificationWindowStart = now
                    notificationWindowCount = 0
                }
                notificationWindowCount++
                if (notificationWindowCount > MAX_NOTIFICATIONS_PER_SECOND) {
                    Log.w(TAG, "Rate limit exceeded — dropping notification")
                    return@withLock false
                }
                resetWatchdog()
                true
            }
            if (shouldEmit) _notifications.tryEmit(value)
        }
    }

    private fun resetWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(NOTIFICATION_WATCHDOG_MS)
            Log.e(TAG, "No notification for ${NOTIFICATION_WATCHDOG_MS}ms — disconnecting")
            mutex.withLock { disconnectInternal() }
        }
    }

    internal fun simulateBondStateChange(device: BluetoothDevice, newState: Int) {
        bondReceiver.handleBondStateChanged(device, newState)
    }

    private inner class BondReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            val newBondState = intent.getIntExtra(
                BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.BOND_NONE
            )
            handleBondStateChanged(device, newBondState)
        }

        fun handleBondStateChanged(device: BluetoothDevice, newBondState: Int) {
            if (newBondState == BluetoothDevice.BOND_NONE) {
                Log.w(TAG, "Bond lost with ${device.address} — removing bond and disconnecting")
                try {
                    device.javaClass.getMethod("removeBond").invoke(device)
                } catch (e: Exception) {
                    Log.e(TAG, "removeBond() reflection failed", e)
                }
                bondLostPending = true
                scope.launch { mutex.withLock { disconnectInternal() } }
            }
        }
    }

    private inner class BoschAdvertiseCallback : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: errorCode=$errorCode")
            scope.launch { mutex.withLock { _state.value = BleState.Disconnected } }
        }
    }
}
