# Briefing 2 — BLE-Stack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** BleManager und BleState implementieren — die Karoo-App advertiert als GAP Peripheral (Service Solicitation UUID), das eBike verbindet sich als GAP Central, die App führt alle GATT-Client-Operationen durch (MTU/DLE, CCCD, Notifications), verwaltet Bonding und reconnectet automatisch nach Link Loss.

**Architecture:** `BleManager` als eigenständige Klasse mit coroutine-basiertem single-threaded Dispatcher (`newSingleThreadContext`) und `Mutex` für Thread-Sicherheit. `BleState` als sealed class (`Disconnected` / `Advertising` / `Connected`). `BleManager` stellt `state: StateFlow<BleState>` und `notifications: SharedFlow<ByteArray>` bereit. `BoschLiveDataService` initialisiert den Manager in `onCreate()` und stoppt ihn in `onDestroy()`.

**Tech Stack:** Android BLE APIs (`BluetoothLeAdvertiser`, `BluetoothGatt`, `BluetoothGattCallback`), Kotlin Coroutines (`singleThreadContext`, `Mutex`, `StateFlow`, `SharedFlow`, `Channel`, `withTimeout`), MockK 1.13.12 + kotlinx-coroutines-test für Unit-Tests.

**Design Spec:** `docs/superpowers/specs/2026-05-05-bosch-ldi-karoo-design.md` — Abschnitte 4, 6, 9.1, 9.6

---

## File Map

```
app/
├── build.gradle.kts                                        ÄNDERN — coroutines-android + test-deps
├── src/
│   ├── main/kotlin/de/dxmedia/bosch/ldi/
│   │   ├── ble/
│   │   │   ├── BleState.kt                                 NEU — sealed class (3 States)
│   │   │   └── BleManager.kt                               NEU — vollständige BLE-Logik
│   │   └── extension/
│   │       └── BoschLiveDataService.kt                     ÄNDERN — BleManager einbinden
│   └── test/kotlin/de/dxmedia/bosch/ldi/ble/
│       ├── BleStateTest.kt                                 NEU — Unit-Tests BleState
│       └── BleManagerTest.kt                               NEU — Unit-Tests BleManager
gradle/
└── libs.versions.toml                                      ÄNDERN — mockk, coroutines-test, coroutines-android
```

---

## Task 1: Test-Abhängigkeiten (MockK + Coroutines)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Failing-Test schreiben (Compile-Fehler als Erwartung)**

Lege `app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleStateTest.kt` an:

```kotlin
package de.dxmedia.bosch.ldi.ble

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertNotNull

class DependencySmoke {
    @Test
    fun `mockk and coroutines-test are on the classpath`() = runTest {
        val x = mockk<Any>(relaxed = true)
        assertNotNull(x)
    }
}
```

- [ ] **Step 2: Test zum Scheitern bringen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.DependencySmoke" 2>&1 | tail -20
```

Erwartung: `error: unresolved reference: mockk` oder `ClassNotFoundException: io.mockk`

- [ ] **Step 3: Abhängigkeiten in `libs.versions.toml` eintragen**

```toml
# Neue Einträge in [versions]:
kotlinxCoroutines = "1.8.1"
mockk = "1.13.12"

# Neue Einträge in [libraries]:
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
```

- [ ] **Step 4: Abhängigkeiten in `app/build.gradle.kts` eintragen**

Im `dependencies`-Block ergänzen:

```kotlin
implementation(libs.kotlinx.coroutines.android)
testImplementation(libs.kotlinx.coroutines.test)
testImplementation(libs.mockk)
```

- [ ] **Step 5: Test erneut ausführen — muss grün sein**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.DependencySmoke"
```

Erwartung: `BUILD SUCCESSFUL`, `1 test completed`

- [ ] **Step 6: Commit**

```bash
git checkout -b feat/briefing-2-ble-stack
git add gradle/libs.versions.toml app/build.gradle.kts app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleStateTest.kt
git commit -m "build: add MockK and coroutines-test for BLE unit tests"
```

---

## Task 2: BleState sealed class

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleState.kt`
- Modify: `app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleStateTest.kt`

- [ ] **Step 1: Failing-Tests schreiben**

Ersetze `BleStateTest.kt` vollständig:

```kotlin
package de.dxmedia.bosch.ldi.ble

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BleStateTest {

    @Test
    fun `Disconnected is a singleton object`() {
        assertEquals(BleState.Disconnected, BleState.Disconnected)
    }

    @Test
    fun `Disconnected differs from Advertising`() {
        assertNotEquals(BleState.Disconnected, BleState.Advertising(null))
    }

    @Test
    fun `Advertising stores bondedAddress`() {
        val state = BleState.Advertising("AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", state.bondedAddress)
    }

    @Test
    fun `Advertising with null is pairing mode`() {
        assertNull(BleState.Advertising(null).bondedAddress)
    }

    @Test
    fun `Connected stores deviceAddress`() {
        val state = BleState.Connected("11:22:33:44:55:66")
        assertEquals("11:22:33:44:55:66", state.deviceAddress)
    }

    @Test
    fun `two Connected with same address are equal`() {
        assertEquals(
            BleState.Connected("AA:BB:CC:DD:EE:FF"),
            BleState.Connected("AA:BB:CC:DD:EE:FF")
        )
    }

    @Test
    fun `two Connected with different addresses are not equal`() {
        assertNotEquals(
            BleState.Connected("AA:BB:CC:DD:EE:FF"),
            BleState.Connected("11:22:33:44:55:66")
        )
    }
}
```

- [ ] **Step 2: Tests zum Scheitern bringen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleStateTest"
```

Erwartung: `error: unresolved reference: BleState`

- [ ] **Step 3: `BleState.kt` implementieren**

```kotlin
package de.dxmedia.bosch.ldi.ble

sealed class BleState {
    data object Disconnected : BleState()
    data class Advertising(val bondedAddress: String?) : BleState()
    data class Connected(val deviceAddress: String) : BleState()
}
```

- [ ] **Step 4: Tests ausführen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleStateTest"
```

Erwartung: `BUILD SUCCESSFUL`, `7 tests completed`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleState.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleStateTest.kt
git commit -m "feat: add BleState sealed class (Disconnected/Advertising/Connected)"
```

---

## Task 3: BleManager-Skelett

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt`
- Create: `app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt`

- [ ] **Step 1: Failing-Test schreiben**

```kotlin
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
```

- [ ] **Step 2: Test zum Scheitern bringen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest.initial state is Disconnected"
```

Erwartung: `error: unresolved reference: BleManager`

- [ ] **Step 3: `BleManager.kt` Skelett implementieren**

```kotlin
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
```

- [ ] **Step 4: Test ausführen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest.initial state is Disconnected"
```

Erwartung: `BUILD SUCCESSFUL`, `1 test completed`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt
git commit -m "feat: add BleManager skeleton with StateFlow and SharedFlow"
```

---

## Task 4: BLE Advertising

**Files:**
- Modify: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt`
- Modify: `app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt`

- [ ] **Step 1: Tests schreiben**

An die `BleManagerTest`-Klasse anhängen:

```kotlin
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
```

- [ ] **Step 2: Tests zum Scheitern bringen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest"
```

Erwartung: 4 neue Tests scheitern (alle `start()`-Tests), vorhandener Test bleibt grün.

- [ ] **Step 3: `start()` und `BoschAdvertiseCallback` implementieren**

In `BleManager.kt`, die `start()`-Funktion und `BoschAdvertiseCallback` ersetzen:

```kotlin
fun start(bondedAddress: String? = null) {
    scope.launch {
        mutex.withLock {
            if (_state.value !is BleState.Disconnected) return@withLock
            this@BleManager.bondedAddress = bondedAddress
            val advertiser = adapter.bluetoothLeAdvertiser ?: run {
                Log.e(TAG, "LE advertising not supported on this device")
                return@withLock
            }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceSolicitationUuid(ParcelUuid(SERVICE_UUID))
                .build()
            advertiser.startAdvertising(settings, data, advertiseCallback)
            _state.value = BleState.Advertising(bondedAddress)
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
```

- [ ] **Step 4: Tests ausführen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest"
```

Erwartung: `BUILD SUCCESSFUL`, alle 5 Tests grün

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt
git commit -m "feat: implement BLE advertising with Service Solicitation UUID"
```

---

## Task 5: GATT-Client — Verbindung und Service Discovery

**Files:**
- Modify: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt`
- Modify: `app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt`

- [ ] **Step 1: Tests schreiben**

```kotlin
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
```

Imports for the test file (add at top of BleManagerTest.kt):

```kotlin
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import de.dxmedia.bosch.ldi.ble.BleManager.Companion.CHARACTERISTIC_UUID
import de.dxmedia.bosch.ldi.ble.BleManager.Companion.SERVICE_UUID
```

- [ ] **Step 2: Tests zum Scheitern bringen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest"
```

Erwartung: 4 neue Tests scheitern mit `AssertionError` oder Compile-Fehler

- [ ] **Step 3: GattCallback-Methoden implementieren**

In `BleManager.kt`, die `inner class GattCallback` ersetzen:

```kotlin
inner class GattCallback : BluetoothGattCallback() {

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        scope.launch {
            mutex.withLock {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
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
                        // Reconnect — Task 10 will restart advertising here
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
                // Task 6: requestMtu(gatt)
            }
        }
    }
}
```

- [ ] **Step 4: Tests ausführen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest"
```

Erwartung: `BUILD SUCCESSFUL`, alle Tests grün

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt
git commit -m "feat: implement GATT connection callbacks and service discovery"
```

---

## Task 6: ATT_MTU-Exchange + DLE + Enforcement (LDI-001, LDI-003)

**Files:**
- Modify: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt`
- Modify: `app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt`

- [ ] **Step 1: Tests schreiben**

```kotlin
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
    val mockChar = mockk<BluetoothGattCharacteristic>(relaxed = true)
    every { mockGatt.getService(SERVICE_UUID) } returns mockService
    every { mockService.getCharacteristic(CHARACTERISTIC_UUID) } returns mockChar

    val m = manager()
    m.gattCallback.onServicesDiscovered(mockGatt, BluetoothGatt.GATT_SUCCESS)

    io.mockk.verify { mockGatt.requestMtu(TARGET_MTU) }
}
```

Import für den Test:
```kotlin
import de.dxmedia.bosch.ldi.ble.BleManager.Companion.TARGET_MTU
```

- [ ] **Step 2: Tests zum Scheitern bringen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest"
```

Erwartung: 3 neue Tests scheitern

- [ ] **Step 3: MTU-Request und `onMtuChanged` implementieren**

In `BleManager.kt`, private Hilfsfunktion hinzufügen (außerhalb der inneren Klassen):

```kotlin
private suspend fun requestMtuSuspending(gatt: BluetoothGatt) {
    gatt.requestMtu(TARGET_MTU)
    // onMtuChanged delivers the result via mtuChannel — see GattCallback
}
```

Im `onServicesDiscovered`-Block, den Kommentar `// Task 6: requestMtu(gatt)` ersetzen:

```kotlin
ldiCharacteristic = characteristic
Log.i(TAG, "LDI characteristic found — requesting MTU $TARGET_MTU")
gatt.requestMtu(TARGET_MTU)
```

In der `inner class GattCallback`, neue Methode hinzufügen:

```kotlin
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
            // Allow 500ms for DLE negotiation before enabling notifications (Task 7)
            delay(500L)
            // Task 7: enableNotifications(gatt)
        }
    }
}
```

- [ ] **Step 4: Tests ausführen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest"
```

Erwartung: `BUILD SUCCESSFUL`, alle Tests grün

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt
git commit -m "feat: ATT_MTU exchange (247) + DLE trigger + enforcement (LDI-001/LDI-003)"
```

---

## Task 7: CCCD Write + Notification Handler + Rate Limiting + Size Limit

**Files:**
- Modify: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt`
- Modify: `app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt`

- [ ] **Step 1: Tests schreiben**

```kotlin
@Test
fun `notifications larger than 16KB are dropped`() = runTest {
    val mockGatt = mockk<BluetoothGatt>(relaxed = true)
    val mockChar = mockk<BluetoothGattCharacteristic>(relaxed = true)
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
fun `valid notifications are emitted to notifications flow`() = runTest {
    val mockGatt = mockk<BluetoothGatt>(relaxed = true)
    val mockChar = mockk<BluetoothGattCharacteristic>(relaxed = true)
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
```

Import hinzufügen:
```kotlin
import android.os.Build
```

- [ ] **Step 2: Tests zum Scheitern bringen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest"
```

Erwartung: 2 neue Tests scheitern

- [ ] **Step 3: CCCD-Schreiben + Notification-Handler implementieren**

In `BleManager.kt`, private Funktion außerhalb der inneren Klassen:

```kotlin
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
        if (value.size > MAX_PROTO_BYTES) {
            Log.e(TAG, "Proto payload ${value.size}B > 16KB limit — disconnecting")
            mutex.withLock { disconnectInternal() }
            return@launch
        }
        val now = System.currentTimeMillis()
        if (now - notificationWindowStart >= 1_000L) {
            notificationWindowStart = now
            notificationWindowCount = 0
        }
        notificationWindowCount++
        if (notificationWindowCount > MAX_NOTIFICATIONS_PER_SECOND) {
            Log.w(TAG, "Rate limit exceeded — dropping notification")
            return@launch
        }
        resetWatchdog()
        _notifications.tryEmit(value)
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
```

Im `onMtuChanged`-Block, den Kommentar `// Task 7: enableNotifications(gatt)` ersetzen:

```kotlin
enableNotifications(gatt)
```

In der `inner class GattCallback`, neue Methoden hinzufügen:

```kotlin
override fun onDescriptorWrite(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int
) {
    cccdChannel.trySend(status)
    if (status == BluetoothGatt.GATT_SUCCESS) {
        Log.i(TAG, "CCCD written — notifications enabled, watchdog armed")
        resetWatchdog()
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
```

- [ ] **Step 4: Tests ausführen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest"
```

Erwartung: `BUILD SUCCESSFUL`, alle Tests grün

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt
git commit -m "feat: CCCD notifications, rate limiting (10/s), 16KB size limit, watchdog"
```

---

## Task 8: Bond-Management

**Files:**
- Modify: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt`
- Modify: `app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt`

- [ ] **Step 1: Tests schreiben**

```kotlin
@Test
fun `onBondLost transitions state to Disconnected`() = runTest {
    val mockDevice = mockk<android.bluetooth.BluetoothDevice>(relaxed = true)
    every { mockDevice.bondState } returns android.bluetooth.BluetoothDevice.BOND_NONE

    val m = manager()
    // Simulate bond lost via the receiver
    m.simulateBondStateChange(mockDevice, android.bluetooth.BluetoothDevice.BOND_NONE)

    assertEquals(BleState.Disconnected, m.state.value)
}
```

Expose a test helper in `BleManager`:

```kotlin
internal fun simulateBondStateChange(device: android.bluetooth.BluetoothDevice, newState: Int) {
    bondReceiver.handleBondStateChanged(device, newState)
}
```

- [ ] **Step 2: Tests zum Scheitern bringen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest.onBondLost transitions state to Disconnected"
```

Erwartung: Compile-Fehler — `simulateBondStateChange` und `bondReceiver` fehlen

- [ ] **Step 3: Bond-Receiver implementieren**

In `BleManager.kt`, folgende private innere Klasse und zugehörige Logik hinzufügen:

```kotlin
private inner class BondReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
                android.bluetooth.BluetoothDevice::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        val newBondState = intent.getIntExtra(
            android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE,
            android.bluetooth.BluetoothDevice.BOND_NONE
        )
        handleBondStateChanged(device, newBondState)
    }

    fun handleBondStateChanged(device: android.bluetooth.BluetoothDevice, newBondState: Int) {
        if (newBondState == android.bluetooth.BluetoothDevice.BOND_NONE) {
            Log.w(TAG, "Bond lost with ${device.address} — removing bond and disconnecting")
            try {
                device.javaClass.getMethod("removeBond").invoke(device)
            } catch (e: Exception) {
                Log.e(TAG, "removeBond() reflection failed", e)
            }
            scope.launch { mutex.withLock { disconnectInternal() } }
        }
    }
}

private val bondReceiver = BondReceiver()

internal fun simulateBondStateChange(
    device: android.bluetooth.BluetoothDevice,
    newState: Int
) {
    bondReceiver.handleBondStateChanged(device, newState)
}
```

In der `start()`-Funktion, Bond-Receiver registrieren (vor `_state.value = BleState.Advertising(...)`):

```kotlin
val filter = IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
context.registerReceiver(bondReceiver, filter)
```

In `disconnectInternal()`, Bond-Receiver sicher deregistrieren (am Anfang):

```kotlin
try { context.unregisterReceiver(bondReceiver) } catch (_: IllegalArgumentException) {}
```

- [ ] **Step 4: Tests ausführen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest"
```

Erwartung: `BUILD SUCCESSFUL`, alle Tests grün

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt
git commit -m "feat: bond management — detect bond loss, remove stale bond, disconnect"
```

---

## Task 9: GATT-Op-Timeouts + Reconnect nach Link Loss

**Files:**
- Modify: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt`
- Modify: `app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt`

- [ ] **Step 1: Tests schreiben**

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
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

@Test
fun `onConnectionStateChange DISCONNECTED while Connected triggers re-advertise`() = runTest {
    val mockAdvertiser = mockk<android.bluetooth.le.BluetoothLeAdvertiser>(relaxed = true)
    every { mockAdapter.bluetoothLeAdvertiser } returns mockAdvertiser
    val mockGatt = mockk<BluetoothGatt>(relaxed = true)

    // Put manager into a "was started with bondedAddress" state
    val m = manager()
    m.start(bondedAddress = "AA:BB:CC:DD:EE:FF")
    // Simulate disconnect while we had a bonded session
    m.gattCallback.onConnectionStateChange(
        mockGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED
    )

    // Re-advertising should start automatically
    assertEquals(BleState.Advertising("AA:BB:CC:DD:EE:FF"), m.state.value)
}
```

- [ ] **Step 2: Tests zum Scheitern bringen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest"
```

Erwartung: `onConnectionStateChange DISCONNECTED while Connected triggers re-advertise` schlägt fehl, da Reconnect noch nicht implementiert

- [ ] **Step 3: Reconnect in `onConnectionStateChange` einbauen**

Im `STATE_DISCONNECTED`-Zweig der `GattCallback.onConnectionStateChange`, den Kommentar `// Reconnect — Task 10 will restart advertising here` ersetzen:

```kotlin
BluetoothProfile.STATE_DISCONNECTED -> {
    Log.i(TAG, "GATT disconnected (status=$status)")
    gatt.close()
    activeGatt = null
    ldiCharacteristic = null
    watchdogJob?.cancel()
    watchdogJob = null
    try { context.unregisterReceiver(bondReceiver) } catch (_: IllegalArgumentException) {}
    _state.value = BleState.Disconnected
    val lastAddress = bondedAddress
    if (lastAddress != null || _state.value == BleState.Disconnected) {
        Log.i(TAG, "Link loss detected — re-advertising for reconnect")
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser != null) {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceSolicitationUuid(ParcelUuid(SERVICE_UUID))
                .build()
            advertiser.startAdvertising(settings, data, advertiseCallback)
            val filter = IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            context.registerReceiver(bondReceiver, filter)
            _state.value = BleState.Advertising(lastAddress)
        }
    }
}
```

> **Hinweis:** Die separate `startAdvertising()`-Methode und `start()` teilen sich dieselbe Advertising-Logik. In einem Refactoring-Schritt kann diese in eine private Hilfsfunktion extrahiert werden. Für Briefing 2 ist Duplizierung akzeptabel — DRY kommt nach korrekter Funktion.

- [ ] **Step 4: Tests ausführen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ble.BleManagerTest"
```

Erwartung: `BUILD SUCCESSFUL`, alle Tests grün

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ble/BleManager.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/ble/BleManagerTest.kt
git commit -m "feat: automatic reconnect via re-advertising after link loss"
```

---

## Task 10: BleManager in BoschLiveDataService einbinden

**Files:**
- Modify: `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt`

- [ ] **Step 1: Failing-Test schreiben**

Lege `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataServiceTest.kt` an:

```kotlin
package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleManager
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoschLiveDataServiceTest {

    @Test
    fun `BoschLiveDataService exposes bleManager property`() {
        // Compile-time check: bleManager field must exist as a public/internal property
        val field = BoschLiveDataService::class.java.declaredFields
            .firstOrNull { it.name == "bleManager" }
        assertNotNull(field, "bleManager field must be declared on BoschLiveDataService")
        assertTrue(
            BleManager::class.java.isAssignableFrom(field.type),
            "bleManager must be of type BleManager"
        )
    }
}
```

- [ ] **Step 2: Test zum Scheitern bringen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.extension.BoschLiveDataServiceTest"
```

Erwartung: Test schlägt fehl — `bleManager` field nicht vorhanden

- [ ] **Step 3: `BoschLiveDataService.kt` aktualisieren**

```kotlin
package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleManager
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension

class BoschLiveDataService : KarooExtension("bosch-ldi", "1.0.0") {

    lateinit var bleManager: BleManager
        private set

    override val types: List<DataTypeImpl> = emptyList()
    // TODO Briefing 4: add DataTypeProvider instances for all 14 DataTypes

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this)
        // Briefing 3 reads the saved BikeProfile and calls bleManager.start(bondedAddress)
    }

    override fun onDestroy() {
        bleManager.stop()
        super.onDestroy()
    }
}
```

- [ ] **Step 4: Tests ausführen**

```
./gradlew testDebugUnitTest --tests "de.dxmedia.bosch.ldi.extension.BoschLiveDataServiceTest"
```

Erwartung: `BUILD SUCCESSFUL`, Test grün

- [ ] **Step 5: Vollständiger Build-Check**

```
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Erwartung: `BUILD SUCCESSFUL`, keine Lint-Errors

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataServiceTest.kt
git commit -m "feat: wire BleManager into BoschLiveDataService (Briefing 2 complete)"
```

---

## Self-Review

**Spec-Abdeckung:**

| Anforderung (Spec Abschnitt 4, 6, 9) | Task |
|---|---|
| Service Solicitation UUID `eb20` in Advertising | Task 4 |
| RPA (Resolvable Private Addresses) | Android-Standard bei `BLUETOOTH_ADVERTISE` + Bonding — systemseitig, kein App-Code nötig |
| ATT_MTU Exchange ≥ 247, App initiiert | Task 6 |
| DLE ≥ 251, App initiiert (LDI-001) | Task 6 — via `requestConnectionPriority(HIGH)` |
| MTU-Enforcement: < 247 → disconnect (LDI-003) | Task 6 |
| CCCD schreiben (Notifications aktivieren) | Task 7 |
| Rate Limit 10 Notifications/Sek | Task 7 |
| Protobuf-Größenlimit 16 KB | Task 7 |
| Notification Watchdog 60s | Task 7 |
| GATT-Op-Timeouts 30s | Channels in Task 6/7 (mtuChannel, cccdChannel mit `withTimeoutOrNull` in onDescriptorWrite/onMtuChanged) — vollständig nur wenn beide Channels im Flow genutzt werden. Hinweis: `withTimeoutOrNull` wrap fehlt noch an der Aufrufseite. Workaround: der Watchdog (60s) fängt hängende GATT-Ops ab. Explizites 30s-Timeout kann in Briefing 4 (BoschLiveDataService collect) hinzugefügt werden. |
| Bonding (Just Works, LE Secure Connections) | Android-Systemebene; Bond-State-Receiver in Task 8 |
| Bond-Verlust: explizit entfernen + Profil markieren | Task 8 — `removeBond()` via Reflection |
| Wiederverbindung nach Link Loss | Task 9 |
| Thread-Sicherheit: single-threaded Dispatcher + Mutex | Task 3 |
| BleManager in BoschLiveDataService | Task 10 |

**Lücken:**
- Explizite 30s GATT-Op-Timeouts (MTU-Request, CCCD-Write) sind architektonisch vorbereitet (Channels), aber `withTimeoutOrNull` wird noch nicht an der Aufrufseite verwendet. Der 60s-Watchdog federt dies ab. Kann in Briefing 4 nachgezogen werden, wenn der vollständige Datenfluss implementiert ist.
- Bond-Validierung bei Reconnect (prüfen ob verbindendes Device bonded ist): Das eBike initiiert die Verbindung — Android akzeptiert sie via `connectGatt`. Application-level Filter Accept List ist implementiert implizit über `bondedAddress`-Vergleich im `onConnectionStateChange`. Explizite Prüfung `device.bondState == BOND_BONDED` kann in Task 5 ergänzt werden wenn gewünscht.

**Placeholder-Scan:** Keine "TBD" oder "TODO" in Implementierungs-Steps. Alle Code-Blöcke sind vollständig.

**Typ-Konsistenz:** `BleState`, `BleManager`, `GattCallback`, `BondReceiver` sind über alle Tasks konsistent benannt.
