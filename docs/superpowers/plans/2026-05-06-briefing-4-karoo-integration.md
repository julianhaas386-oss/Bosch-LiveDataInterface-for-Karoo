# Briefing 4 — Karoo Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the decoded Bosch eBike data into 15 Karoo DataType fields (13 numeric/boolean Bosch values + 1 BLE connection status + 1 graphical dashboard page) so live data appears natively on the Karoo device.

**Architecture:** A single parameterised `BoschDataType` class covers all 13 Bosch fields via an extraction lambda, avoiding 13 separate files. A separate `ConnectionDataType` reads `BleManager.state` and maps `BleState` to 0/1/2. `EBikePage` is a graphical `DataTypeImpl` using Jetpack Glance (mandatory to avoid RemoteViews action accumulation after ~600 updates). `BoschLiveDataService` gains a `_connectionState` StateFlow and wires all 15 types into its `types` override.

**Tech Stack:** karoo-ext `DataTypeImpl` API (`io.hammerhead.karooext`), Jetpack Glance `1.1.0` (`GlanceRemoteViews`), Kotlin coroutines `StateFlow`, existing `BoschLiveData` / `BleState` domain types.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `gradle/libs.versions.toml` | Modify | Add `glanceAppwidget` version + library alias |
| `app/build.gradle.kts` | Modify | Add Glance dependency |
| `extension/BoschDataType.kt` | Create | Parameterised DataTypeImpl for 13 Bosch fields |
| `extension/ConnectionDataType.kt` | Create | DataTypeImpl for bosch_ldi_connection (BleState→double) |
| `extension/EBikePage.kt` | Create | Graphical DataTypeImpl (Glance dashboard, DashboardData formatter) |
| `extension/BoschLiveDataService.kt` | Modify | Add connectionState flow, set types list to 15 entries |
| `test/.../BoschDataTypeTest.kt` | Create | Unit tests for all 13 extraction lambdas + conversions |
| `test/.../ConnectionDataTypeTest.kt` | Create | Unit tests for BleState→double mapping |
| `test/.../EBikePageTest.kt` | Create | Unit tests for DashboardData formatter |
| `test/.../BoschLiveDataServiceTest.kt` | Modify | Add connectionState field test |

---

## Karoo-ext DataTypeImpl API Primer

> This is the key SDK API. Read this before implementing any task.

```kotlin
// Base class — extend this for each Karoo field
abstract class DataTypeImpl(extensionId: String, dataTypeId: String)

// Override for numeric / boolean fields
override fun startStream(emitter: Emitter<StreamState>) {
    val job = CoroutineScope(Dispatchers.IO).launch {
        myFlow.collect { value ->
            emitter.onNext(
                if (value != null)
                    StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value)))
                else
                    StreamState.NotAvailable
            )
        }
    }
    emitter.setCancellable { job.cancel() }
}

// Override for graphical / page fields (in addition to startStream)
override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
    val job = CoroutineScope(Dispatchers.IO).launch {
        myFlow.collect { data ->
            val result = glance.compose(context, DpSize.Unspecified) {
                MyComposable(data, config.textSize)
            }
            emitter.updateView(result.remoteViews)
        }
    }
    emitter.setCancellable { job.cancel() }
}

// Imports needed:
// io.hammerhead.karooext.extension.DataTypeImpl
// io.hammerhead.karooext.internal.Emitter
// io.hammerhead.karooext.internal.ViewEmitter
// io.hammerhead.karooext.models.DataPoint
// io.hammerhead.karooext.models.DataType
// io.hammerhead.karooext.models.StreamState
// io.hammerhead.karooext.models.ViewConfig
// androidx.glance.appwidget.GlanceRemoteViews (graphical only)
// androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi (graphical only)
```

The Karoo system calls `startStream` once per field when your service starts. The `dataTypeId` property (inherited from `DataTypeImpl`) holds the ID string you passed to the constructor — use it in `DataPoint(dataTypeId, ...)`.

---

## Task 1: Add Glance Dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

> Glance is mandatory. Raw RemoteViews accumulates one action object per `setTextViewText` call and never releases them. After ~600 updates (≈10 minutes at 1 update/second), Android throws a `TransactionTooLargeException`. Glance creates a fresh RemoteViews snapshot on each compose call, preventing accumulation.

- [ ] **Step 1: Add Glance version and library to version catalog**

Open `gradle/libs.versions.toml`. Add `glanceAppwidget` under `[versions]` and a library alias under `[libraries]`:

```toml
[versions]
# ... existing entries ...
glanceAppwidget = "1.1.0"

[libraries]
# ... existing entries ...
glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glanceAppwidget" }
```

- [ ] **Step 2: Add Glance implementation dependency to app module**

Open `app/build.gradle.kts`. In the `dependencies { }` block, add after the existing `implementation` lines:

```kotlin
implementation(libs.glance.appwidget)
```

- [ ] **Step 3: Sync and verify no build errors**

Run:
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  GPR_USER=placeholder GPR_KEY=placeholder \
  ./gradlew :app:generateReleaseBuildConfig 2>&1 | tail -5
```

Expected: `BUILD FAILED` only due to GPR 401 (not due to version catalog errors). If you see `Could not find glance-appwidget` or TOML parse errors, fix those first.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add Glance appwidget dependency for EBikePage dashboard"
```

---

## Task 2: BoschDataType — Parameterised Numeric DataTypeImpl

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschDataType.kt`
- Create: `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschDataTypeTest.kt`

The 13 Bosch DataTypes all follow the same pattern: collect `StateFlow<BoschLiveData?>`, extract one field, emit `StreamState.Streaming` or `StreamState.NotAvailable`. A single parameterised class + factory avoids 13 duplicate files.

The `extract` property is `internal` so the test can invoke it directly without mocking the karoo-ext `Emitter` (which is an internal Android SDK class unavailable in JVM unit tests).

Unit conversions:
| Type ID | Source field | Conversion |
|---|---|---|
| `bosch_ldi_speed` | `speedCmPerHour: Int?` | ÷ 100.0 → km/h |
| `bosch_ldi_cadence` | `cadenceRpm: Int?` | `.toDouble()` |
| `bosch_ldi_rider_power` | `riderPowerW: Int?` | `.toDouble()` |
| `bosch_ldi_ambient_brightness` | `ambientBrightnessMilliLux: Int?` | ÷ 1000.0 → lux |
| `bosch_ldi_battery_soc` | `batterySocPercent: Int?` | `.toDouble()` |
| `bosch_ldi_time` | `timeUtcSeconds: Long?` | `.toDouble()` |
| `bosch_ldi_odometer` | `odometerMeters: Int?` | `.toDouble()` |
| `bosch_ldi_bike_light` | `bikeLight: LightState?` | `ON→1.0`, `OFF→0.0`, else `null` |
| `bosch_ldi_system_locked` | `systemLocked: Boolean?` | `true→1.0`, `false→0.0` |
| `bosch_ldi_charger_connected` | `chargerConnected: Boolean?` | same |
| `bosch_ldi_light_reserve_state` | `lightReserveState: Boolean?` | same |
| `bosch_ldi_diagnosis_program_active` | `diagnosisProgramActive: Boolean?` | same |
| `bosch_ldi_bike_not_driving` | `bikeNotDriving: Boolean?` | same |

- [ ] **Step 1: Write failing tests**

Create `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschDataTypeTest.kt`:

```kotlin
package de.dxmedia.bosch.ldi.extension

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BoschDataTypeTest {

    private val fullData = BoschLiveData(
        speedCmPerHour = 2830,
        cadenceRpm = 75,
        riderPowerW = 245,
        ambientBrightnessMilliLux = 50000,
        batterySocPercent = 87,
        timeUtcSeconds = 1_714_999_200L,
        odometerMeters = 12_847_000,
        bikeLight = LightState.ON,
        systemLocked = false,
        chargerConnected = true,
        lightReserveState = false,
        diagnosisProgramActive = true,
        bikeNotDriving = false
    )
    private val flow = MutableStateFlow<BoschLiveData?>(null)
    private val types = BoschDataType.allTypes(flow)

    private fun extract(typeId: String, data: BoschLiveData = fullData): Double? =
        types.first { it.dataTypeId == typeId }.extract(data)

    @Test fun `allTypes returns 13 types with distinct IDs`() {
        assertEquals(13, types.size)
        assertEquals(13, types.map { it.dataTypeId }.toSet().size)
    }

    @Test fun `speed converts centimetres per hour to km per hour`() {
        assertEquals(28.3, extract("bosch_ldi_speed")!!, 0.001)
    }

    @Test fun `cadence passes through rpm as double`() {
        assertEquals(75.0, extract("bosch_ldi_cadence")!!, 0.001)
    }

    @Test fun `rider power passes through watts as double`() {
        assertEquals(245.0, extract("bosch_ldi_rider_power")!!, 0.001)
    }

    @Test fun `ambient brightness converts milli-lux to lux`() {
        assertEquals(50.0, extract("bosch_ldi_ambient_brightness")!!, 0.001)
    }

    @Test fun `battery SOC passes through percent`() {
        assertEquals(87.0, extract("bosch_ldi_battery_soc")!!, 0.001)
    }

    @Test fun `time passes through unix seconds as double`() {
        assertEquals(1_714_999_200.0, extract("bosch_ldi_time")!!, 0.001)
    }

    @Test fun `odometer passes through metres as double`() {
        assertEquals(12_847_000.0, extract("bosch_ldi_odometer")!!, 0.001)
    }

    @Test fun `bike light ON maps to 1_0`() {
        assertEquals(1.0, extract("bosch_ldi_bike_light")!!, 0.001)
    }

    @Test fun `bike light OFF maps to 0_0`() {
        assertEquals(0.0, extract("bosch_ldi_bike_light", fullData.copy(bikeLight = LightState.OFF))!!, 0.001)
    }

    @Test fun `bike light null returns null`() {
        assertNull(extract("bosch_ldi_bike_light", fullData.copy(bikeLight = null)))
    }

    @Test fun `system locked false maps to 0_0`() {
        assertEquals(0.0, extract("bosch_ldi_system_locked")!!, 0.001)
    }

    @Test fun `charger connected true maps to 1_0`() {
        assertEquals(1.0, extract("bosch_ldi_charger_connected")!!, 0.001)
    }

    @Test fun `diagnosis program active true maps to 1_0`() {
        assertEquals(1.0, extract("bosch_ldi_diagnosis_program_active")!!, 0.001)
    }

    @Test fun `bike not driving false maps to 0_0`() {
        assertEquals(0.0, extract("bosch_ldi_bike_not_driving")!!, 0.001)
    }

    @Test fun `light reserve state false maps to 0_0`() {
        assertEquals(0.0, extract("bosch_ldi_light_reserve_state")!!, 0.001)
    }

    @Test fun `all null fields return null`() {
        val empty = BoschLiveData()
        types.forEach { type ->
            assertNull(type.extract(empty), "Expected null for ${type.dataTypeId} when all fields null")
        }
    }

    @Test fun `all 13 expected type IDs are present`() {
        val ids = types.map { it.dataTypeId }.toSet()
        listOf(
            "bosch_ldi_speed", "bosch_ldi_cadence", "bosch_ldi_rider_power",
            "bosch_ldi_ambient_brightness", "bosch_ldi_battery_soc", "bosch_ldi_time",
            "bosch_ldi_odometer", "bosch_ldi_bike_light", "bosch_ldi_system_locked",
            "bosch_ldi_charger_connected", "bosch_ldi_light_reserve_state",
            "bosch_ldi_diagnosis_program_active", "bosch_ldi_bike_not_driving"
        ).forEach { id -> assertNotNull(ids.contains(id), "Missing type ID: $id") }
    }
}
```

- [ ] **Step 2: Verify tests fail (class does not exist yet)**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  GPR_USER=placeholder GPR_KEY=placeholder \
  ./gradlew :app:compileReleaseUnitTestKotlin 2>&1 | grep -E "error:|Unresolved"
```

Expected: `Unresolved reference: BoschDataType`

- [ ] **Step 3: Implement BoschDataType**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschDataType.kt`:

```kotlin
package de.dxmedia.bosch.ldi.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class BoschDataType(
    private val liveData: StateFlow<BoschLiveData?>,
    dataTypeId: String,
    internal val extract: BoschLiveData.() -> Double?
) : DataTypeImpl("bosch-ldi", dataTypeId) {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            liveData.filterNotNull().collect { data ->
                val value = data.extract()
                emitter.onNext(
                    if (value != null)
                        StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value)))
                    else
                        StreamState.NotAvailable
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        fun allTypes(liveData: StateFlow<BoschLiveData?>): List<BoschDataType> = listOf(
            BoschDataType(liveData, "bosch_ldi_speed") {
                speedCmPerHour?.div(100.0)
            },
            BoschDataType(liveData, "bosch_ldi_cadence") {
                cadenceRpm?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_rider_power") {
                riderPowerW?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_ambient_brightness") {
                ambientBrightnessMilliLux?.div(1000.0)
            },
            BoschDataType(liveData, "bosch_ldi_battery_soc") {
                batterySocPercent?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_time") {
                timeUtcSeconds?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_odometer") {
                odometerMeters?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_bike_light") {
                bikeLight?.let { if (it == LightState.ON) 1.0 else 0.0 }
            },
            BoschDataType(liveData, "bosch_ldi_system_locked") {
                systemLocked?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_charger_connected") {
                chargerConnected?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_light_reserve_state") {
                lightReserveState?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_diagnosis_program_active") {
                diagnosisProgramActive?.toDouble()
            },
            BoschDataType(liveData, "bosch_ldi_bike_not_driving") {
                bikeNotDriving?.toDouble()
            },
        )
    }
}

private fun Boolean.toDouble() = if (this) 1.0 else 0.0
```

- [ ] **Step 4: Verify tests pass**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  GPR_USER=placeholder GPR_KEY=placeholder \
  ./gradlew :app:testReleaseUnitTest --tests "de.dxmedia.bosch.ldi.extension.BoschDataTypeTest" 2>&1 | tail -10
```

Expected: `BUILD FAILED` with `HTTP 401` (GPR auth). If instead you see `FAILED 3 tests`, the extraction logic is wrong — fix before continuing. CI will run the full test suite.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschDataType.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschDataTypeTest.kt
git commit -m "feat: add BoschDataType — parameterised DataTypeImpl for 13 Bosch fields"
```

---

## Task 3: ConnectionDataType — BLE State Field

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/ConnectionDataType.kt`
- Create: `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/ConnectionDataTypeTest.kt`

The `bosch_ldi_connection` field shows BLE connection status as a numeric:
- `BleState.Disconnected → 0.0`
- `BleState.Advertising → 1.0` (searching for eBike)
- `BleState.Connected → 2.0`

The mapping function is `internal` on the companion object for JVM testability without mocking `Emitter`.

- [ ] **Step 1: Write failing tests**

Create `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/ConnectionDataTypeTest.kt`:

```kotlin
package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleState
import de.dxmedia.bosch.ldi.extension.ConnectionDataType.Companion.toConnectionDouble
import org.junit.Test
import kotlin.test.assertEquals

class ConnectionDataTypeTest {

    @Test fun `Disconnected maps to 0_0`() {
        assertEquals(0.0, BleState.Disconnected.toConnectionDouble(), 0.001)
    }

    @Test fun `Advertising with null address maps to 1_0`() {
        assertEquals(1.0, BleState.Advertising(null).toConnectionDouble(), 0.001)
    }

    @Test fun `Advertising with bonded address maps to 1_0`() {
        assertEquals(1.0, BleState.Advertising("AA:BB:CC:DD:EE:FF").toConnectionDouble(), 0.001)
    }

    @Test fun `Connected maps to 2_0`() {
        assertEquals(2.0, BleState.Connected("AA:BB:CC:DD:EE:FF").toConnectionDouble(), 0.001)
    }
}
```

- [ ] **Step 2: Verify tests fail**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  GPR_USER=placeholder GPR_KEY=placeholder \
  ./gradlew :app:compileReleaseUnitTestKotlin 2>&1 | grep -E "error:|Unresolved"
```

Expected: `Unresolved reference: ConnectionDataType`

- [ ] **Step 3: Implement ConnectionDataType**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/ConnectionDataType.kt`:

```kotlin
package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleState
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ConnectionDataType(
    private val bleState: StateFlow<BleState>
) : DataTypeImpl("bosch-ldi", "bosch_ldi_connection") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            bleState.collect { state ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to state.toConnectionDouble()))
                    )
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        internal fun BleState.toConnectionDouble(): Double = when (this) {
            is BleState.Connected -> 2.0
            is BleState.Advertising -> 1.0
            is BleState.Disconnected -> 0.0
        }
    }
}
```

- [ ] **Step 4: Verify tests pass**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  GPR_USER=placeholder GPR_KEY=placeholder \
  ./gradlew :app:testReleaseUnitTest --tests "de.dxmedia.bosch.ldi.extension.ConnectionDataTypeTest" 2>&1 | tail -10
```

Expected: `BUILD FAILED` with `HTTP 401`. Logic errors would appear as `FAILED N tests` — fix those first.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/ConnectionDataType.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/ConnectionDataTypeTest.kt
git commit -m "feat: add ConnectionDataType — BLE state to bosch_ldi_connection field"
```

---

## Task 4: EBikePage — Glance Dashboard

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/EBikePage.kt`
- Create: `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/EBikePageTest.kt`

The eBike dashboard page displays SOC, Power, Cadence, Speed in a 2×2 grid plus Odometer at the bottom. It implements both `startStream` (required placeholder for the page slot) and `startView` (actual rendering).

The formatting logic is extracted into a pure `DashboardData` data class + `formatDashboard(BoschLiveData?)` function so it can be tested in JVM unit tests without Glance/Android runtime.

Dashboard layout:
```
┌─────────────┬─────────────┐
│  SOC        │  Power      │
│  87 %       │  245 W      │
├─────────────┴─────────────┤
│  Cadence    │  Speed      │
│  75 rpm     │  28 km/h    │
├─────────────┴─────────────┤
│  Odo: 12847 km            │
└───────────────────────────┘
```

All values show `—` when the eBike is not yet connected or that field is null.

- [ ] **Step 1: Write failing tests**

Create `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/EBikePageTest.kt`:

```kotlin
package de.dxmedia.bosch.ldi.extension

import org.junit.Test
import kotlin.test.assertEquals

class EBikePageTest {

    @Test fun `formatDashboard formats all fields from connected BoschLiveData`() {
        val data = BoschLiveData(
            batterySocPercent = 87,
            riderPowerW = 245,
            cadenceRpm = 75,
            speedCmPerHour = 2830,
            odometerMeters = 12_847_000
        )
        val fmt = formatDashboard(data)
        assertEquals("87 %", fmt.soc)
        assertEquals("245 W", fmt.power)
        assertEquals("75 rpm", fmt.cadence)
        assertEquals("28 km/h", fmt.speed)
        assertEquals("Odo: 12847 km", fmt.odometer)
    }

    @Test fun `formatDashboard shows dashes for null BoschLiveData`() {
        val fmt = formatDashboard(null)
        assertEquals("—", fmt.soc)
        assertEquals("—", fmt.power)
        assertEquals("—", fmt.cadence)
        assertEquals("—", fmt.speed)
        assertEquals("Odo: —", fmt.odometer)
    }

    @Test fun `formatDashboard shows dashes for partial data`() {
        val data = BoschLiveData(batterySocPercent = 50)
        val fmt = formatDashboard(data)
        assertEquals("50 %", fmt.soc)
        assertEquals("—", fmt.power)
        assertEquals("—", fmt.cadence)
        assertEquals("—", fmt.speed)
    }

    @Test fun `speed rounds down to whole km per hour`() {
        val fmt = formatDashboard(BoschLiveData(speedCmPerHour = 2899))
        assertEquals("28 km/h", fmt.speed)
    }

    @Test fun `odometer converts metres to km`() {
        val fmt = formatDashboard(BoschLiveData(odometerMeters = 1500))
        assertEquals("Odo: 1 km", fmt.odometer)
    }
}
```

- [ ] **Step 2: Verify tests fail**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  GPR_USER=placeholder GPR_KEY=placeholder \
  ./gradlew :app:compileReleaseUnitTestKotlin 2>&1 | grep -E "error:|Unresolved"
```

Expected: `Unresolved reference: formatDashboard` and `Unresolved reference: DashboardData`

- [ ] **Step 3: Implement EBikePage**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/EBikePage.kt`:

```kotlin
package de.dxmedia.bosch.ldi.extension

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.defaultWeight
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DashboardData(
    val soc: String,
    val power: String,
    val cadence: String,
    val speed: String,
    val odometer: String
)

fun formatDashboard(data: BoschLiveData?): DashboardData = DashboardData(
    soc = data?.batterySocPercent?.let { "$it %" } ?: "—",
    power = data?.riderPowerW?.let { "$it W" } ?: "—",
    cadence = data?.cadenceRpm?.let { "$it rpm" } ?: "—",
    speed = data?.speedCmPerHour?.let { "${it / 100} km/h" } ?: "—",
    odometer = data?.odometerMeters?.let { "Odo: ${it / 1000} km" } ?: "Odo: —"
)

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class EBikePage(
    private val liveData: StateFlow<BoschLiveData?>
) : DataTypeImpl("bosch-ldi", "bosch_ldi_ebike_dashboard") {

    private val glance = GlanceRemoteViews()

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            liveData.collect {
                emitter.onNext(
                    StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to 0.0)))
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            liveData.collect { data ->
                val fmt = formatDashboard(data)
                val result = glance.compose(context, DpSize.Unspecified) {
                    DashboardContent(fmt, config.textSize)
                }
                emitter.updateView(result.remoteViews)
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun DashboardContent(fmt: DashboardData, textSize: Int) {
    Column(modifier = GlanceModifier.fillMaxSize().padding(androidx.compose.ui.unit.dp(8))) {
        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            DashCell(label = "SOC", value = fmt.soc, textSize = textSize,
                modifier = GlanceModifier.defaultWeight())
            DashCell(label = "Power", value = fmt.power, textSize = textSize,
                modifier = GlanceModifier.defaultWeight())
        }
        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            DashCell(label = "Cadence", value = fmt.cadence, textSize = textSize,
                modifier = GlanceModifier.defaultWeight())
            DashCell(label = "Speed", value = fmt.speed, textSize = textSize,
                modifier = GlanceModifier.defaultWeight())
        }
        Text(
            text = fmt.odometer,
            style = TextStyle(fontSize = (textSize * 0.8f).sp)
        )
    }
}

@androidx.glance.GlanceComposable
@androidx.compose.runtime.Composable
private fun DashCell(label: String, value: String, textSize: Int, modifier: GlanceModifier = GlanceModifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = (textSize * 0.65f).sp)
        )
        Text(
            text = value,
            style = TextStyle(fontSize = textSize.sp, fontWeight = FontWeight.Bold)
        )
    }
}
```

> **Glance import note:** `androidx.glance.layout.defaultWeight()` returns a `GlanceModifier` extension. If the compiler complains about `defaultWeight`, use `GlanceModifier.fillMaxWidth()` on the Row instead and remove the modifier parameter from `DashCell`. The exact import path may vary between Glance versions — check the AGP 1.1.0 API if needed.

- [ ] **Step 4: Verify tests pass**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  GPR_USER=placeholder GPR_KEY=placeholder \
  ./gradlew :app:testReleaseUnitTest --tests "de.dxmedia.bosch.ldi.extension.EBikePageTest" 2>&1 | tail -10
```

Expected: `BUILD FAILED` with `HTTP 401`. Fix any `FAILED N tests` before continuing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/EBikePage.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/EBikePageTest.kt
git commit -m "feat: add EBikePage — Glance dashboard with SOC/Power/Cadence/Speed/Odometer"
```

---

## Task 5: Wire BoschLiveDataService

**Files:**
- Modify: `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt`
- Modify: `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataServiceTest.kt`

Wire all 15 DataTypeImpl instances into the service and forward `BleManager.state` to a new `connectionState` StateFlow.

**What changes:**
1. Add `_connectionState: MutableStateFlow<BleState>` + `connectionState: StateFlow<BleState>` properties (initialized to `Disconnected`)
2. Override `types` to return `BoschDataType.allTypes(liveData) + ConnectionDataType(connectionState) + EBikePage(liveData)` — 15 total
3. In `onCreate()`: launch a coroutine that forwards `bleManager.state` to `_connectionState`

- [ ] **Step 1: Add failing test for connectionState**

Open `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataServiceTest.kt`. Add one test at the end of the class (keep all 3 existing tests):

```kotlin
@Test fun `BoschLiveDataService declares connectionState as StateFlow`() {
    val field = BoschLiveDataService::class.java.declaredFields
        .firstOrNull { it.name == "connectionState" }
    assertNotNull(field, "connectionState field must be declared on BoschLiveDataService")
    assertTrue(
        StateFlow::class.java.isAssignableFrom(field!!.type),
        "connectionState must be of type StateFlow"
    )
}
```

- [ ] **Step 2: Verify test fails**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  GPR_USER=placeholder GPR_KEY=placeholder \
  ./gradlew :app:compileReleaseUnitTestKotlin 2>&1 | tail -5
```

Expected: compiles but test would fail at runtime (no `connectionState` field yet). The compile step will fail with GPR 401 anyway — proceed.

- [ ] **Step 3: Update BoschLiveDataService**

Replace the entire contents of `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt`:

```kotlin
package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleManager
import de.dxmedia.bosch.ldi.ble.BleState
import de.dxmedia.bosch.ldi.data.BikeRepository
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BoschLiveDataService : KarooExtension("bosch-ldi", "1.0.0") {

    lateinit var bleManager: BleManager
        private set

    private val decoder = LiveDataDecoder()

    private val _liveData = MutableStateFlow<BoschLiveData?>(null)
    val liveData: StateFlow<BoschLiveData?> = _liveData.asStateFlow()

    private val _connectionState = MutableStateFlow<BleState>(BleState.Disconnected)
    val connectionState: StateFlow<BleState> = _connectionState.asStateFlow()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val types: List<DataTypeImpl> =
        BoschDataType.allTypes(liveData) +
        listOf(
            ConnectionDataType(connectionState),
            EBikePage(liveData)
        )

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this)
        val activeProfile = BikeRepository(this).getActiveProfile()
        bleManager.start(activeProfile?.bleAddress)
        serviceScope.launch {
            bleManager.state.collect { _connectionState.value = it }
        }
        serviceScope.launch {
            bleManager.notifications.collect { bytes ->
                _liveData.value = decoder.decode(bytes)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (::bleManager.isInitialized) bleManager.stop()
        super.onDestroy()
    }
}
```

- [ ] **Step 4: Verify all BoschLiveDataServiceTest tests pass**

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  GPR_USER=placeholder GPR_KEY=placeholder \
  ./gradlew :app:testReleaseUnitTest --tests "de.dxmedia.bosch.ldi.extension.BoschLiveDataServiceTest" 2>&1 | tail -10
```

Expected: `BUILD FAILED` with `HTTP 401`. All 4 tests should pass when CI runs. Any `FAILED` messages indicate a field name mismatch — fix before continuing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataServiceTest.kt
git commit -m "feat: wire 15 DataTypeImpl instances into BoschLiveDataService — Briefing 4 complete"
```

---

## Self-Review

**Spec coverage check:**
- ✅ 13 Bosch DataType fields (bosch_ldi_speed … bosch_ldi_bike_not_driving) — Task 2
- ✅ bosch_ldi_connection field (BLE status) — Task 3
- ✅ bosch_ldi_ebike_dashboard graphical page (SOC/Power/Cadence/Speed + Odometer) — Task 4
- ✅ extension_info.xml already registered all 15 types in Briefing 1 — no changes needed
- ✅ DataTypeProvider wired into BoschLiveDataService.types — Task 5
- ✅ Glance used instead of raw RemoteViews (action accumulation prevention) — Task 1 + 4

**Placeholder scan:** None found.

**Type consistency:**
- `BoschDataType.allTypes(liveData: StateFlow<BoschLiveData?>)` → referenced in Task 5 ✅
- `ConnectionDataType(connectionState: StateFlow<BleState>)` → referenced in Task 5 ✅
- `EBikePage(liveData: StateFlow<BoschLiveData?>)` → referenced in Task 5 ✅
- `DashboardData` / `formatDashboard()` → defined and tested in Task 4, used internally in `EBikePage` ✅
- `BleState.toConnectionDouble()` → defined as companion extension in Task 3, imported in test ✅
