# Briefing 5 — UI & i18n Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the full app UI — BikeListScreen, PairingWizardScreen, BikeDetailScreen, SettingsScreen — plus MainActivity service binding and Navigation Compose routing.

**Architecture:** `BoschLiveDataService` gains a `LocalBinder` so `MainActivity` can bind to it in-process, then exposes `startPairing(slot)` and `setActiveSlot(slot)` methods. Navigation Compose routes `"bikes"`, `"bikes/{slot}/wizard"`, `"bikes/{slot}/detail"`, `"settings"` from a `NavHost` in `MainActivity`. ViewModels are created with constructor-injected lambdas for testability and passed via `remember` (Karoo doesn't rotate, so no config-change survival needed). The service ref is a Compose `State<BoschLiveDataService?>` so the UI reacts automatically when binding completes.

**Tech Stack:** Navigation Compose 2.8.4, Compose Material3 (already present), ViewModel + viewModelScope, kotlinx-coroutines (already present), BikeRepository (already present), BleManager/BleState (already present).

---

## File Map

**New files:**
- `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingState.kt`
- `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingWizardViewModel.kt`
- `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingWizardScreen.kt`
- `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeListViewModel.kt`
- `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeListScreen.kt`
- `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeDetailViewModel.kt`
- `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeDetailScreen.kt`
- `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/settings/SettingsScreen.kt`
- `app/src/test/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingWizardViewModelTest.kt`
- `app/src/test/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeDetailViewModelTest.kt`

**Modified files:**
- `gradle/libs.versions.toml` — add navigation-compose version + library entry
- `app/build.gradle.kts` — add navigation-compose dep, bump versionCode/versionName
- `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt` — add LocalBinder, onBind, startPairing, setActiveSlot, private startBleManager helper
- `app/src/main/kotlin/de/dxmedia/bosch/ldi/MainActivity.kt` — full impl: service binding, NavHost
- `app/src/main/res/values/strings.xml` — add `bike_slot_not_paired`, `bike_slot_paired`
- `app/src/main/res/values-de/strings.xml` — same strings in German

---

## Task 1: Add Navigation Compose Dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add version + library to version catalog**

In `gradle/libs.versions.toml`, add after `androidxAppcompat = "1.7.0"`:
```toml
androidxNavigation = "2.8.4"
```

Add after the `glance-appwidget` library entry:
```toml
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "androidxNavigation" }
```

- [ ] **Step 2: Add to app/build.gradle.kts**

In the `dependencies` block, after `implementation(libs.androidx.activity.compose)`:
```kotlin
implementation(libs.androidx.navigation.compose)
```

- [ ] **Step 3: Sync and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "chore: add navigation-compose 2.8.4 dependency"
```

---

## Task 2: Refactor BoschLiveDataService — LocalBinder + Pairing API

**Files:**
- Modify: `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt`

The service currently starts BleManager once in `onCreate()`. We need:
1. `LocalBinder` inner class so MainActivity can get the service reference
2. `private fun startBleManager(address: String?)` helper that tears down any existing BleManager and starts a fresh one
3. `fun startPairing(slot: BikeSlot)` — calls repository to mark slot's bleAddress null (will be set after bonding), then calls `startBleManager(null)` to enter pairing mode
4. `fun setActiveSlot(slot: BikeSlot)` — persists the new active slot, restarts BleManager with the new address

- [ ] **Step 1: Replace BoschLiveDataService with the full implementation**

```kotlin
package de.dxmedia.bosch.ldi.extension

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import de.dxmedia.bosch.ldi.ble.BleManager
import de.dxmedia.bosch.ldi.ble.BleState
import de.dxmedia.bosch.ldi.data.BikeRepository
import de.dxmedia.bosch.ldi.data.BikeSlot
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BoschLiveDataService : KarooExtension("bosch-ldi", "1.0.0") {

    inner class LocalBinder : Binder() {
        val service: BoschLiveDataService get() = this@BoschLiveDataService
    }

    private val binder = LocalBinder()

    private lateinit var repository: BikeRepository
    private val decoder = LiveDataDecoder()

    private val _liveData = MutableStateFlow<BoschLiveData?>(null)
    val liveData: StateFlow<BoschLiveData?> = _liveData.asStateFlow()

    private val _connectionState = MutableStateFlow<BleState>(BleState.Disconnected)
    val connectionState: StateFlow<BleState> = _connectionState.asStateFlow()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var stateJob: Job? = null
    private var notifJob: Job? = null

    private var _bleManager: BleManager? = null

    override val types: List<DataTypeImpl> =
        BoschDataType.allTypes(liveData) +
        listOf(
            ConnectionDataType(connectionState),
            EBikePage(liveData)
        )

    override fun onCreate() {
        super.onCreate()
        repository = BikeRepository(this)
        startBleManager(repository.getActiveProfile()?.bleAddress)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        _bleManager?.stop()
        super.onDestroy()
    }

    fun startPairing(slot: BikeSlot) {
        repository.setActive(slot)
        startBleManager(null)
    }

    fun onPairingSuccess(slot: BikeSlot, bleAddress: String) {
        val profile = repository.getProfiles().first { it.slot == slot }
        repository.upsert(profile.copy(bleAddress = bleAddress))
        startBleManager(bleAddress)
    }

    fun setActiveSlot(slot: BikeSlot) {
        repository.setActive(slot)
        startBleManager(repository.getActiveProfile()?.bleAddress)
    }

    private fun startBleManager(address: String?) {
        stateJob?.cancel()
        notifJob?.cancel()
        _bleManager?.stop()
        val mgr = BleManager(this)
        _bleManager = mgr
        mgr.start(address)
        stateJob = serviceScope.launch {
            mgr.state.collect { _connectionState.value = it }
        }
        notifJob = serviceScope.launch {
            mgr.notifications.collect { bytes ->
                _liveData.value = decoder.decode(bytes)
            }
        }
    }
}
```

- [ ] **Step 2: Build to confirm no compile errors**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run existing unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt
git commit -m "feat: add LocalBinder, startPairing, setActiveSlot to BoschLiveDataService"
```

---

## Task 3: PairingState + PairingWizardViewModel (TDD)

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingState.kt`
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingWizardViewModel.kt`
- Create: `app/src/test/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingWizardViewModelTest.kt`

- [ ] **Step 1: Write PairingState sealed class**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingState.kt`:
```kotlin
package de.dxmedia.bosch.ldi.ui.wizard

sealed class PairingState {
    data object Explaining : PairingState()
    data class Advertising(val secondsLeft: Int) : PairingState()
    data class Success(val deviceAddress: String) : PairingState()
    data object Failure : PairingState()
}
```

- [ ] **Step 2: Write failing tests**

Create `app/src/test/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingWizardViewModelTest.kt`:
```kotlin
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
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ui.wizard.*"`
Expected: FAIL (PairingWizardViewModel not found)

- [ ] **Step 4: Implement PairingWizardViewModel**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingWizardViewModel.kt`:
```kotlin
package de.dxmedia.bosch.ldi.ui.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.dxmedia.bosch.ldi.ble.BleState
import de.dxmedia.bosch.ldi.data.BikeSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PairingWizardViewModel(
    val slot: BikeSlot,
    private val connectionState: StateFlow<BleState>,
    private val onStartPairing: () -> Unit,
    private val scope: CoroutineScope = viewModelScope // overridden in tests
) : ViewModel() {

    private val _state = MutableStateFlow<PairingState>(PairingState.Explaining)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var pairingJob: Job? = null

    fun startPairing() {
        pairingJob?.cancel()
        onStartPairing()
        pairingJob = scope.launch {
            _state.value = PairingState.Advertising(60)
            val countdownJob = launch {
                repeat(60) { elapsed ->
                    delay(1_000L)
                    val remaining = 59 - elapsed
                    if (_state.value is PairingState.Advertising) {
                        _state.value = PairingState.Advertising(remaining)
                    }
                }
            }
            val result = withTimeoutOrNull(60_000L) {
                connectionState.first { it is BleState.Connected }
            }
            countdownJob.cancel()
            _state.value = if (result is BleState.Connected) {
                PairingState.Success(result.deviceAddress)
            } else {
                PairingState.Failure
            }
        }
    }

    fun cancel() {
        pairingJob?.cancel()
        pairingJob = null
        _state.value = PairingState.Explaining
    }

    override fun onCleared() {
        pairingJob?.cancel()
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ui.wizard.*"`
Expected: All 7 tests PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/wizard/ \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/ui/wizard/
git commit -m "feat: PairingWizardViewModel with countdown and BLE state machine"
```

---

## Task 4: BikeDetailViewModel (TDD)

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeDetailViewModel.kt`
- Create: `app/src/test/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeDetailViewModelTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeDetailViewModelTest.kt`:
```kotlin
package de.dxmedia.bosch.ldi.ui.bikes

import de.dxmedia.bosch.ldi.data.BikeProfile
import de.dxmedia.bosch.ldi.data.BikeSlot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BikeDetailViewModelTest {

    private fun makeProfile(slot: BikeSlot = BikeSlot.ALPHA, active: Boolean = false) =
        BikeProfile(slot = slot, bleAddress = "AA:BB:CC:DD:EE:FF", isActive = active)

    @Test
    fun `isActive reflects initial profile state`() {
        val vm = BikeDetailViewModel(makeProfile(active = true), {}, {}, {})
        assertTrue(vm.isActive.value)
    }

    @Test
    fun `toggleActive flips isActive`() {
        val vm = BikeDetailViewModel(makeProfile(active = false), {}, {}, {})
        vm.toggleActive()
        assertTrue(vm.isActive.value)
        vm.toggleActive()
        assertFalse(vm.isActive.value)
    }

    @Test
    fun `isFieldEnabled returns true for default fields`() {
        val vm = BikeDetailViewModel(makeProfile(), {}, {}, {})
        assertTrue(vm.isFieldEnabled("bosch_ldi_speed"))
    }

    @Test
    fun `toggleField disables an enabled field`() {
        val vm = BikeDetailViewModel(makeProfile(), {}, {}, {})
        vm.toggleField("bosch_ldi_speed")
        assertFalse(vm.isFieldEnabled("bosch_ldi_speed"))
    }

    @Test
    fun `toggleField re-enables a disabled field`() {
        val profile = makeProfile().let {
            it.copy(enabledFields = it.enabledFields - "bosch_ldi_speed")
        }
        val vm = BikeDetailViewModel(profile, {}, {}, {})
        vm.toggleField("bosch_ldi_speed")
        assertTrue(vm.isFieldEnabled("bosch_ldi_speed"))
    }

    @Test
    fun `save calls onSave with updated profile`() {
        var saved: BikeProfile? = null
        val vm = BikeDetailViewModel(makeProfile(), onSave = { saved = it }, {}, {})
        vm.toggleActive()
        vm.save()
        assertTrue(saved?.isActive == true)
    }

    @Test
    fun `forget calls onForget`() {
        var forgotSlot: BikeSlot? = null
        val vm = BikeDetailViewModel(makeProfile(BikeSlot.BETA), {}, onForget = { forgotSlot = it }, {})
        vm.forget()
        assertTrue(forgotSlot == BikeSlot.BETA)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ui.bikes.BikeDetailViewModelTest"`
Expected: FAIL (BikeDetailViewModel not found)

- [ ] **Step 3: Implement BikeDetailViewModel**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeDetailViewModel.kt`:
```kotlin
package de.dxmedia.bosch.ldi.ui.bikes

import androidx.lifecycle.ViewModel
import de.dxmedia.bosch.ldi.data.BikeProfile
import de.dxmedia.bosch.ldi.data.BikeSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BikeDetailViewModel(
    private var profile: BikeProfile,
    private val onSave: (BikeProfile) -> Unit,
    private val onForget: (BikeSlot) -> Unit,
    private val onSetActive: (BikeSlot) -> Unit
) : ViewModel() {

    private val _isActive = MutableStateFlow(profile.isActive)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _enabledFields = MutableStateFlow(profile.enabledFields)
    val enabledFields: StateFlow<Set<String>> = _enabledFields.asStateFlow()

    val slot: BikeSlot get() = profile.slot
    val bleAddress: String? get() = profile.bleAddress

    fun isFieldEnabled(fieldId: String): Boolean = _enabledFields.value.contains(fieldId)

    fun toggleActive() {
        _isActive.value = !_isActive.value
    }

    fun toggleField(fieldId: String) {
        val current = _enabledFields.value
        _enabledFields.value = if (fieldId in current) current - fieldId else current + fieldId
    }

    fun save() {
        val updated = profile.copy(
            isActive = _isActive.value,
            enabledFields = _enabledFields.value
        )
        if (_isActive.value && !profile.isActive) onSetActive(profile.slot)
        onSave(updated)
    }

    fun forget() {
        onForget(profile.slot)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "de.dxmedia.bosch.ldi.ui.bikes.BikeDetailViewModelTest"`
Expected: All 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeDetailViewModel.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeDetailViewModelTest.kt
git commit -m "feat: BikeDetailViewModel with field/active toggle and save/forget"
```

---

## Task 5: BikeListViewModel

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeListViewModel.kt`

No new tests here — BikeRepository is already tested; the ViewModel just reads from it.

- [ ] **Step 1: Create BikeListViewModel**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeListViewModel.kt`:
```kotlin
package de.dxmedia.bosch.ldi.ui.bikes

import androidx.lifecycle.ViewModel
import de.dxmedia.bosch.ldi.data.BikeProfile
import de.dxmedia.bosch.ldi.data.BikeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BikeListViewModel(private val repository: BikeRepository) : ViewModel() {

    private val _profiles = MutableStateFlow<List<BikeProfile>>(emptyList())
    val profiles: StateFlow<List<BikeProfile>> = _profiles.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _profiles.value = repository.getProfiles()
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeListViewModel.kt
git commit -m "feat: BikeListViewModel reading all four BikeSlot profiles"
```

---

## Task 6: Missing UI Strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`

- [ ] **Step 1: Add strings to EN file**

In `app/src/main/res/values/strings.xml`, inside the `<!-- Bike Management -->` block, after the last `<string>`:
```xml
    <string name="bike_slot_not_paired">Not paired — tap to connect</string>
    <string name="bike_slot_paired">Paired</string>
```

- [ ] **Step 2: Add strings to DE file**

In `app/src/main/res/values-de/strings.xml`, in the equivalent position:
```xml
    <string name="bike_slot_not_paired">Nicht verbunden — tippen zum Koppeln</string>
    <string name="bike_slot_paired">Verbunden</string>
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml
git commit -m "feat: add bike_slot_not_paired and bike_slot_paired UI strings (EN+DE)"
```

---

## Task 7: PairingWizardScreen

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingWizardScreen.kt`

The screen has three visual states driven by `PairingState`:
- `Explaining` → show step 1 explanation + "Start Pairing" button
- `Advertising(secondsLeft)` → show countdown progress + "Cancel" button
- `Success(deviceAddress)` → show success message + "Done" button
- `Failure` → show failure message + "Try Again" / "Cancel" buttons

- [ ] **Step 1: Create PairingWizardScreen**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingWizardScreen.kt`:
```kotlin
package de.dxmedia.bosch.ldi.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.dxmedia.bosch.ldi.R
import de.dxmedia.bosch.ldi.data.BikeSlot

@Composable
fun PairingWizardScreen(
    viewModel: PairingWizardViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.wizard_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val s = state) {
                is PairingState.Explaining -> ExplainingStep(
                    slot = viewModel.slot,
                    onStart = { viewModel.startPairing() },
                    onCancel = onCancel
                )
                is PairingState.Advertising -> AdvertisingStep(
                    secondsLeft = s.secondsLeft,
                    onCancel = {
                        viewModel.cancel()
                        onCancel()
                    }
                )
                is PairingState.Success -> SuccessStep(onDone = onDone)
                is PairingState.Failure -> FailureStep(
                    onRetry = { viewModel.startPairing() },
                    onCancel = {
                        viewModel.cancel()
                        onCancel()
                    }
                )
            }
        }
    }
}

@Composable
private fun ExplainingStep(slot: BikeSlot, onStart: () -> Unit, onCancel: () -> Unit) {
    Text(
        text = slot.displayName,
        style = MaterialTheme.typography.headlineSmall
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.wizard_step1_title),
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_step1_body),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_security_note),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_start))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_cancel))
    }
}

@Composable
private fun AdvertisingStep(secondsLeft: Int, onCancel: () -> Unit) {
    Text(
        text = stringResource(R.string.wizard_step2_title),
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(16.dp))
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text(
        text = "$secondsLeft s",
        style = MaterialTheme.typography.headlineMedium
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_step2_body),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_cancel))
    }
}

@Composable
private fun SuccessStep(onDone: () -> Unit) {
    Text(
        text = stringResource(R.string.wizard_step3_success_title),
        style = MaterialTheme.typography.titleLarge
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_step3_success_body),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_done))
    }
}

@Composable
private fun FailureStep(onRetry: () -> Unit, onCancel: () -> Unit) {
    Text(
        text = stringResource(R.string.wizard_step3_failure_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.wizard_step3_failure_body),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_retry))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_btn_cancel))
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/wizard/PairingWizardScreen.kt
git commit -m "feat: PairingWizardScreen — explaining/advertising/success/failure states"
```

---

## Task 8: BikeListScreen + BikeDetailScreen + SettingsScreen

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeListScreen.kt`
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeDetailScreen.kt`
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Create BikeListScreen**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeListScreen.kt`:
```kotlin
package de.dxmedia.bosch.ldi.ui.bikes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.dxmedia.bosch.ldi.R
import de.dxmedia.bosch.ldi.data.BikeProfile
import de.dxmedia.bosch.ldi.data.BikeSlot

@Composable
fun BikeListScreen(
    viewModel: BikeListViewModel,
    onPairSlot: (BikeSlot) -> Unit,
    onEditSlot: (BikeSlot) -> Unit,
    onNavigateSettings: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bike_list_title)) },
                actions = {
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            items(profiles, key = { it.slot.name }) { profile ->
                BikeSlotCard(
                    profile = profile,
                    onClick = {
                        if (profile.bleAddress == null) onPairSlot(profile.slot)
                        else onEditSlot(profile.slot)
                    }
                )
            }
        }
    }
}

@Composable
private fun BikeSlotCard(profile: BikeProfile, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.slot.displayName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (profile.bleAddress != null)
                        stringResource(R.string.bike_slot_paired)
                    else
                        stringResource(R.string.bike_slot_not_paired),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (profile.isActive) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
```

- [ ] **Step 2: Create BikeDetailScreen**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/bikes/BikeDetailScreen.kt`:
```kotlin
package de.dxmedia.bosch.ldi.ui.bikes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.dxmedia.bosch.ldi.R
import de.dxmedia.bosch.ldi.data.BikeProfile

private val ALL_FIELDS = listOf(
    "bosch_ldi_speed",
    "bosch_ldi_cadence",
    "bosch_ldi_rider_power",
    "bosch_ldi_battery_soc",
    "bosch_ldi_odometer",
    "bosch_ldi_time",
    "bosch_ldi_bike_light",
    "bosch_ldi_ambient_brightness",
    "bosch_ldi_light_reserve_state",
    "bosch_ldi_system_locked",
    "bosch_ldi_charger_connected",
    "bosch_ldi_diagnosis_program_active",
    "bosch_ldi_bike_not_driving",
    "bosch_ldi_connection"
)

private fun fieldLabel(fieldId: String): Int = when (fieldId) {
    "bosch_ldi_speed" -> R.string.field_speed_name
    "bosch_ldi_cadence" -> R.string.field_cadence_name
    "bosch_ldi_rider_power" -> R.string.field_rider_power_name
    "bosch_ldi_battery_soc" -> R.string.field_battery_soc_name
    "bosch_ldi_odometer" -> R.string.field_odometer_name
    "bosch_ldi_time" -> R.string.field_time_name
    "bosch_ldi_bike_light" -> R.string.field_bike_light_name
    "bosch_ldi_ambient_brightness" -> R.string.field_ambient_brightness_name
    "bosch_ldi_light_reserve_state" -> R.string.field_light_reserve_name
    "bosch_ldi_system_locked" -> R.string.field_system_locked_name
    "bosch_ldi_charger_connected" -> R.string.field_charger_connected_name
    "bosch_ldi_diagnosis_program_active" -> R.string.field_diagnosis_name
    "bosch_ldi_bike_not_driving" -> R.string.field_bike_not_driving_name
    "bosch_ldi_connection" -> R.string.field_connection_name
    else -> R.string.app_name
}

@Composable
fun BikeDetailScreen(
    viewModel: BikeDetailViewModel,
    onBack: () -> Unit
) {
    val isActive by viewModel.isActive.collectAsState()
    val enabledFields by viewModel.enabledFields.collectAsState()
    var showForgetDialog by remember { mutableStateOf(false) }

    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text(stringResource(R.string.bike_detail_remove_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.bike_detail_remove_confirm_body,
                        viewModel.slot.displayName
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showForgetDialog = false
                    viewModel.forget()
                    onBack()
                }) { Text(stringResource(R.string.bike_detail_remove_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showForgetDialog = false }) {
                    Text(stringResource(R.string.bike_detail_remove_confirm_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.slot.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.bike_detail_active_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(checked = isActive, onCheckedChange = { viewModel.toggleActive() })
                }
                Divider()
                Text(
                    text = stringResource(R.string.bike_detail_fields_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.bike_detail_fields_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(ALL_FIELDS) { fieldId ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = fieldId in enabledFields,
                        onCheckedChange = { viewModel.toggleField(fieldId) }
                    )
                    Text(
                        text = stringResource(fieldLabel(fieldId)),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item {
                Divider(modifier = Modifier.padding(top = 16.dp))
                Button(
                    onClick = { viewModel.save(); onBack() },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Text(stringResource(R.string.bike_detail_btn_save))
                }
                Button(
                    onClick = { showForgetDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.bike_detail_btn_remove))
                }
            }
        }
    }
}
```

- [ ] **Step 3: Create SettingsScreen**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/settings/SettingsScreen.kt`:
```kotlin
package de.dxmedia.bosch.ldi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.dxmedia.bosch.ldi.R

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrDefault("—")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_version_label),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = versionName ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/ui/
git commit -m "feat: BikeListScreen, BikeDetailScreen, SettingsScreen Compose UI"
```

---

## Task 9: MainActivity — Service Binding + NavHost

**Files:**
- Modify: `app/src/main/kotlin/de/dxmedia/bosch/ldi/MainActivity.kt`

This wires everything together: binds to `BoschLiveDataService`, creates all ViewModels, and hosts the NavGraph.

- [ ] **Step 1: Replace MainActivity**

```kotlin
package de.dxmedia.bosch.ldi

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.dxmedia.bosch.ldi.data.BikeProfile
import de.dxmedia.bosch.ldi.data.BikeRepository
import de.dxmedia.bosch.ldi.data.BikeSlot
import de.dxmedia.bosch.ldi.extension.BoschLiveDataService
import de.dxmedia.bosch.ldi.ui.bikes.BikeDetailScreen
import de.dxmedia.bosch.ldi.ui.bikes.BikeDetailViewModel
import de.dxmedia.bosch.ldi.ui.bikes.BikeListScreen
import de.dxmedia.bosch.ldi.ui.bikes.BikeListViewModel
import de.dxmedia.bosch.ldi.ui.settings.SettingsScreen
import de.dxmedia.bosch.ldi.ui.wizard.PairingWizardScreen
import de.dxmedia.bosch.ldi.ui.wizard.PairingWizardViewModel

class MainActivity : ComponentActivity() {

    private val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private var boschService by mutableStateOf<BoschLiveDataService?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            boschService = (binder as BoschLiveDataService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) {
            boschService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missing = blePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())

        val repository = BikeRepository(this)

        setContent {
            MaterialTheme {
                Surface {
                    val service = boschService
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "bikes") {

                        composable("bikes") {
                            val vm = remember { BikeListViewModel(repository) }
                            BikeListScreen(
                                viewModel = vm,
                                onPairSlot = { slot ->
                                    navController.navigate("bikes/${slot.name}/wizard")
                                },
                                onEditSlot = { slot ->
                                    navController.navigate("bikes/${slot.name}/detail")
                                },
                                onNavigateSettings = { navController.navigate("settings") }
                            )
                        }

                        composable("bikes/{slot}/wizard") { backStackEntry ->
                            val slotName = backStackEntry.arguments?.getString("slot") ?: return@composable
                            val slot = BikeSlot.valueOf(slotName)
                            if (service == null) return@composable
                            val vm = remember(slot, service) {
                                PairingWizardViewModel(
                                    slot = slot,
                                    connectionState = service.connectionState,
                                    onStartPairing = { service.startPairing(slot) }
                                )
                            }
                            PairingWizardScreen(
                                viewModel = vm,
                                onDone = {
                                    // Notify service of successful pairing address
                                    val address = service.connectionState.value
                                    if (address is de.dxmedia.bosch.ldi.ble.BleState.Connected) {
                                        service.onPairingSuccess(slot, address.deviceAddress)
                                    }
                                    navController.popBackStack("bikes", inclusive = false)
                                },
                                onCancel = { navController.popBackStack() }
                            )
                        }

                        composable("bikes/{slot}/detail") { backStackEntry ->
                            val slotName = backStackEntry.arguments?.getString("slot") ?: return@composable
                            val slot = BikeSlot.valueOf(slotName)
                            val profile = remember(slot) {
                                repository.getProfiles().first { it.slot == slot }
                            }
                            val vm = remember(slot) {
                                BikeDetailViewModel(
                                    profile = profile,
                                    onSave = { updated -> repository.upsert(updated) },
                                    onForget = { s ->
                                        repository.delete(s)
                                        service?.setActiveSlot(
                                            repository.getActiveProfile()?.slot ?: BikeSlot.ALPHA
                                        )
                                    },
                                    onSetActive = { s -> service?.setActiveSlot(s) }
                                )
                            }
                            BikeDetailScreen(viewModel = vm, onBack = {
                                navController.popBackStack()
                            })
                        }

                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, BoschLiveDataService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
        boschService = null
    }
}
```

- [ ] **Step 2: Build debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/MainActivity.kt
git commit -m "feat: MainActivity — service binding, NavHost, all screens wired up"
```

---

## Task 10: Bump Version + Final Build

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump version**

In `app/build.gradle.kts`, update:
```kotlin
versionCode = 5
versionName = "0.3.0"
```

- [ ] **Step 2: Final test run**

Run: `./gradlew :app:testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 3: Build release APK**

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to 0.3.0 (Briefing 5 complete)"
```

---

## Self-Review

**Spec coverage check:**
- ✅ PairingWizardScreen: 3 steps (Explaining → Advertising with countdown → Success/Failure)
- ✅ BikeListScreen: shows all 4 BikeSlots, highlights active, tap unpaired → wizard, tap paired → detail
- ✅ BikeDetailScreen: active toggle, field checkboxes, forget with confirmation dialog
- ✅ SettingsScreen: version display
- ✅ Navigation Compose routing for all 4 destinations
- ✅ Service binding via LocalBinder
- ✅ startPairing / setActiveSlot wired to service
- ✅ DE/EN strings — all UI strings already in strings.xml; added bike_slot_not_paired + bike_slot_paired
- ✅ TDD for PairingWizardViewModel (7 tests) and BikeDetailViewModel (7 tests)

**Notes on intentional simplifications:**
- BikeProfile has no free-form name (uses BikeSlot.displayName: Alpha/Beta/Gamma/Delta). The `bike_detail_name_label` string exists in strings.xml but is not used — BikeDetailScreen uses slot.displayName in the TopAppBar. This matches the actual data model.
- Language selection setting (mentioned in spec) is deferred — it requires `AppCompatDelegate.setApplicationLocales()` which is a separate feature; the SettingsScreen only shows version for now.
- ViewModels are created with `remember` instead of `viewModel()` because Karoo doesn't rotate and the service reference must be captured at ViewModel creation time.
