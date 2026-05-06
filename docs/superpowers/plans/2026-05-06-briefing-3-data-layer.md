# Briefing 3 — Data Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the data layer that decodes Bosch eBike BLE notifications into a typed domain model, persists bike profiles in encrypted storage, and wires `BoschLiveDataService` to start BLE advertising with the saved device address.

**Architecture:** `LiveDataDecoder` holds the last known `BoschLiveData` state and merges each incoming proto3 notification (only present optional fields override previous values), protected by a `ReentrantReadWriteLock`. `BikeRepository` serialises `BikeProfile` structs to JSON and stores them in `EncryptedSharedPreferences` (AES-256-GCM key in Android Keystore). `BoschLiveDataService` reads the active profile on `onCreate`, passes the BLE address to `bleManager.start()`, then collects raw notification bytes, decodes them, and exposes the result as `StateFlow<BoschLiveData?>` for Briefing 4's DataTypeProvider to consume.

**Tech Stack:** Kotlin, `protobuf-kotlin-lite` (CodedInputStream for size-limited parsing), `androidx.security.crypto:security-crypto:1.1.0-alpha06` (MasterKey + EncryptedSharedPreferences), `org.json.JSONObject`/`JSONArray` (bundled with Android — no extra dependency), `kotlinx-coroutines`, MockK for tests.

---

## File Map

| Action | Path | Responsibility |
|--------|------|---------------|
| Create | `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveData.kt` | Domain data class + `LightState` enum; zero proto imports |
| Create | `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/LiveDataDecoder.kt` | Stateful proto3 merge → `BoschLiveData`; owns `ReentrantReadWriteLock` |
| Create | `app/src/main/kotlin/de/dxmedia/bosch/ldi/data/BikeProfile.kt` | Data class + validation + `serialize`/`deserialize` (JSONObject) |
| Create | `app/src/main/kotlin/de/dxmedia/bosch/ldi/data/BikeRepository.kt` | EncryptedSharedPreferences CRUD for `BikeProfile` list |
| Modify | `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt` | Start BleManager with stored address; collect + decode; expose `StateFlow` |
| Create | `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/LiveDataDecoderTest.kt` | Unit tests for merge logic, size guard, parse error handling |
| Create | `app/src/test/kotlin/de/dxmedia/bosch/ldi/data/BikeProfileTest.kt` | Unit tests for serialize/deserialize round-trip, validation, factory |
| Modify | `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataServiceTest.kt` | Reflection tests for new `liveData` property and `decoder` field |

---

### Task 1: BoschLiveData domain class

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveData.kt`

- [ ] **Step 1: Create the file**

```kotlin
package de.dxmedia.bosch.ldi.extension

enum class LightState { OFF, ON }

data class BoschLiveData(
    val speedCmPerHour: Int? = null,
    val cadenceRpm: Int? = null,
    val riderPowerW: Int? = null,
    val ambientBrightnessMilliLux: Int? = null,
    val batterySocPercent: Int? = null,
    val timeUtcSeconds: Long? = null,
    val odometerMeters: Int? = null,
    val bikeLight: LightState? = null,
    val systemLocked: Boolean? = null,
    val chargerConnected: Boolean? = null,
    val lightReserveState: Boolean? = null,
    val diagnosisProgramActive: Boolean? = null,
    val bikeNotDriving: Boolean? = null
)
```

All fields are nullable: `null` means the eBike has not yet sent a value for that field. Field names match the proto field names from `ebike_live_data.proto` but use Kotlin conventions and carry display units in their names (e.g. `speedCmPerHour` = raw value ÷ 100 = km/h).

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew testReleaseUnitTest 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL` (no new tests yet).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveData.kt
git commit -m "feat: add BoschLiveData domain class and LightState enum"
```

---

### Task 2: LiveDataDecoder

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/LiveDataDecoder.kt`
- Create: `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/LiveDataDecoderTest.kt`

Background: the Bosch LDI spec (LDI-002) states that GATT notifications contain only *changed* fields. `LiveDataDecoder.decode()` merges each update into the last known full state so consumers always see a complete picture. The proto schema uses `optional` fields (proto3 syntax), which generate `hasXxx()` accessors in the Kotlin/Java code — `hasSpeed()` returns `true` only when the field is present in the incoming message.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/LiveDataDecoderTest.kt`:

```kotlin
package de.dxmedia.bosch.ldi.extension

import com.bosch.ebike.LiveData
import com.bosch.ebike.LightState as ProtoLightState
import de.dxmedia.bosch.ldi.ble.BleManager
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveDataDecoderTest {

    private fun proto(block: LiveData.Builder.() -> Unit): ByteArray =
        LiveData.newBuilder().apply(block).build().toByteArray()

    @Test fun `decode sets only present fields, leaves rest null`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto { speed = 2500 })
        assertEquals(2500, result.speedCmPerHour)
        assertNull(result.cadenceRpm)
        assertNull(result.riderPowerW)
    }

    @Test fun `decode merges with previous state`() {
        val decoder = LiveDataDecoder()
        decoder.decode(proto { speed = 2500 })
        val result = decoder.decode(proto { cadence = 90 })
        assertEquals(2500, result.speedCmPerHour)  // from previous message
        assertEquals(90, result.cadenceRpm)         // from this message
    }

    @Test fun `later value overwrites earlier for same field`() {
        val decoder = LiveDataDecoder()
        decoder.decode(proto { speed = 2500 })
        val result = decoder.decode(proto { speed = 3000 })
        assertEquals(3000, result.speedCmPerHour)
    }

    @Test fun `decode maps LIGHT_STATE_ON to LightState dot ON`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto { bikeLight = ProtoLightState.LIGHT_STATE_ON })
        assertEquals(LightState.ON, result.bikeLight)
    }

    @Test fun `decode maps LIGHT_STATE_OFF to LightState dot OFF`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto { bikeLight = ProtoLightState.LIGHT_STATE_OFF })
        assertEquals(LightState.OFF, result.bikeLight)
    }

    @Test fun `decode maps LIGHT_STATE_INVALID to null`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto { bikeLight = ProtoLightState.LIGHT_STATE_INVALID })
        assertNull(result.bikeLight)
    }

    @Test fun `decode returns last known state on parse error`() {
        val decoder = LiveDataDecoder()
        decoder.decode(proto { speed = 1000 })
        val result = decoder.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertEquals(1000, result.speedCmPerHour)  // unchanged
    }

    @Test fun `decode drops oversized payload and returns last state`() {
        val decoder = LiveDataDecoder()
        decoder.decode(proto { speed = 999 })
        val oversized = ByteArray(BleManager.MAX_PROTO_BYTES + 1)
        val result = decoder.decode(oversized)
        assertEquals(999, result.speedCmPerHour)  // unchanged
    }

    @Test fun `decode handles all numeric fields`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto {
            speed = 3500
            cadence = 85
            riderPower = 200
            ambientBrightness = 50000
            batterySoc = 80
            time = 1_700_000_000L
            odometer = 12345
        })
        assertEquals(3500, result.speedCmPerHour)
        assertEquals(85, result.cadenceRpm)
        assertEquals(200, result.riderPowerW)
        assertEquals(50000, result.ambientBrightnessMilliLux)
        assertEquals(80, result.batterySocPercent)
        assertEquals(1_700_000_000L, result.timeUtcSeconds)
        assertEquals(12345, result.odometerMeters)
    }

    @Test fun `decode handles all boolean fields`() {
        val decoder = LiveDataDecoder()
        val result = decoder.decode(proto {
            systemLocked = true
            chargerConnected = false
            lightReserveState = true
            diagnosisProgramActive = false
            bikeNotDriving = true
        })
        assertEquals(true, result.systemLocked)
        assertEquals(false, result.chargerConnected)
        assertEquals(true, result.lightReserveState)
        assertEquals(false, result.diagnosisProgramActive)
        assertEquals(true, result.bikeNotDriving)
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
./gradlew testReleaseUnitTest --tests "de.dxmedia.bosch.ldi.extension.LiveDataDecoderTest" 2>&1 | tail -10
```

Expected: `FAILED` — `LiveDataDecoder` does not exist yet.

- [ ] **Step 3: Implement LiveDataDecoder**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/LiveDataDecoder.kt`:

```kotlin
package de.dxmedia.bosch.ldi.extension

import android.util.Log
import com.bosch.ebike.LiveData
import com.bosch.ebike.LightState as ProtoLightState
import com.google.protobuf.CodedInputStream
import de.dxmedia.bosch.ldi.ble.BleManager
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class LiveDataDecoder {

    private val lock = ReentrantReadWriteLock()
    private var current = BoschLiveData()

    fun decode(bytes: ByteArray): BoschLiveData {
        if (bytes.size > BleManager.MAX_PROTO_BYTES) {
            Log.w(TAG, "Payload ${bytes.size}B exceeds limit — returning last known state")
            return lock.read { current }
        }
        val proto = try {
            val input = CodedInputStream.newInstance(bytes)
            input.setSizeLimit(BleManager.MAX_PROTO_BYTES)
            input.setRecursionLimit(16)
            LiveData.parseFrom(input)
        } catch (e: Exception) {
            Log.e(TAG, "Proto parse error — returning last known state", e)
            return lock.read { current }
        }
        return lock.write {
            current = current.copy(
                speedCmPerHour            = if (proto.hasSpeed())                 proto.speed                     else current.speedCmPerHour,
                cadenceRpm                = if (proto.hasCadence())               proto.cadence                   else current.cadenceRpm,
                riderPowerW               = if (proto.hasRiderPower())            proto.riderPower                else current.riderPowerW,
                ambientBrightnessMilliLux = if (proto.hasAmbientBrightness())    proto.ambientBrightness         else current.ambientBrightnessMilliLux,
                batterySocPercent         = if (proto.hasBatterySoc())            proto.batterySoc                else current.batterySocPercent,
                timeUtcSeconds            = if (proto.hasTime())                  proto.time                      else current.timeUtcSeconds,
                odometerMeters            = if (proto.hasOdometer())              proto.odometer                  else current.odometerMeters,
                bikeLight               = if (proto.hasBikeLight())              proto.bikeLight.toDomain()          else current.bikeLight,
                systemLocked            = if (proto.hasSystemLocked())           proto.systemLocked                  else current.systemLocked,
                chargerConnected        = if (proto.hasChargerConnected())       proto.chargerConnected              else current.chargerConnected,
                lightReserveState       = if (proto.hasLightReserveState())      proto.lightReserveState             else current.lightReserveState,
                diagnosisProgramActive  = if (proto.hasDiagnosisProgramActive()) proto.diagnosisProgramActive        else current.diagnosisProgramActive,
                bikeNotDriving          = if (proto.hasBikeNotDriving())         proto.bikeNotDriving                else current.bikeNotDriving
            )
            current
        }
    }

    private fun ProtoLightState.toDomain(): LightState? = when (this) {
        ProtoLightState.LIGHT_STATE_ON  -> LightState.ON
        ProtoLightState.LIGHT_STATE_OFF -> LightState.OFF
        else                            -> null
    }

    companion object {
        private const val TAG = "LiveDataDecoder"
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew testReleaseUnitTest --tests "de.dxmedia.bosch.ldi.extension.LiveDataDecoderTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — 11 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/LiveDataDecoder.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/LiveDataDecoderTest.kt
git commit -m "feat: add LiveDataDecoder with stateful proto3 merge and error handling"
```

---

### Task 3: BikeProfile data class with serialization

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/data/BikeProfile.kt`
- Create: `app/src/test/kotlin/de/dxmedia/bosch/ldi/data/BikeProfileTest.kt`

`BikeProfile` is a pure data class with no Android dependencies. Its companion holds the validation regexes (from the design spec) and the `serialize`/`deserialize` functions using `org.json.JSONObject`/`JSONArray` — both are bundled with Android and available in JVM unit tests.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/de/dxmedia/bosch/ldi/data/BikeProfileTest.kt`:

```kotlin
package de.dxmedia.bosch.ldi.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BikeProfileTest {

    // ── Factory ──────────────────────────────────────────────────────────

    @Test fun `create generates unique non-blank ids`() {
        val a = BikeProfile.create("Trek", "AA:BB:CC:DD:EE:FF")
        val b = BikeProfile.create("Trek", "AA:BB:CC:DD:EE:FF")
        assertTrue(a.id.isNotBlank())
        assertNotEquals(a.id, b.id)
    }

    @Test fun `create sets isActive false and full default field set`() {
        val p = BikeProfile.create("Trek", "AA:BB:CC:DD:EE:FF")
        assertFalse(p.isActive)
        assertEquals(BikeProfile.DEFAULT_FIELDS, p.enabledFields)
    }

    @Test fun `DEFAULT_FIELDS contains all 15 field ids`() {
        assertEquals(15, BikeProfile.DEFAULT_FIELDS.size)
    }

    // ── Serialization round-trips ────────────────────────────────────────

    @Test fun `serialize then deserialize round-trips single profile`() {
        val original = BikeProfile.create("Trek Allant", "AA:BB:CC:DD:EE:FF")
            .copy(isActive = true, enabledFields = setOf("bosch_ldi_speed", "bosch_ldi_cadence"))
        val result = BikeProfile.deserialize(BikeProfile.serialize(listOf(original)))
        assertEquals(listOf(original), result)
    }

    @Test fun `serialize then deserialize round-trips multiple profiles`() {
        val profiles = listOf(
            BikeProfile.create("Trek", "AA:BB:CC:DD:EE:FF"),
            BikeProfile.create("Giant", "11:22:33:44:55:66").copy(isActive = true)
        )
        assertEquals(profiles, BikeProfile.deserialize(BikeProfile.serialize(profiles)))
    }

    @Test fun `serialize then deserialize round-trips empty list`() {
        val result = BikeProfile.deserialize(BikeProfile.serialize(emptyList()))
        assertTrue(result.isEmpty())
    }

    // ── Name validation ──────────────────────────────────────────────────

    @Test fun `isValidName accepts letters digits spaces hyphens underscores`() {
        assertTrue(BikeProfile.isValidName("Trek Allant"))
        assertTrue(BikeProfile.isValidName("My_Bike-1"))
        assertTrue(BikeProfile.isValidName("A".repeat(64)))
    }

    @Test fun `isValidName rejects blank, all-space, too long, and special chars`() {
        assertFalse(BikeProfile.isValidName(""))
        assertFalse(BikeProfile.isValidName("   "))          // blank after logic check
        assertFalse(BikeProfile.isValidName("A".repeat(65))) // too long
        assertFalse(BikeProfile.isValidName("Bike+Pro"))     // + not in whitelist
        assertFalse(BikeProfile.isValidName("Bike@Home"))    // @ not in whitelist
    }

    // ── BLE address validation ────────────────────────────────────────────

    @Test fun `isValidBleAddress accepts valid MAC in upper and lower case`() {
        assertTrue(BikeProfile.isValidBleAddress("AA:BB:CC:DD:EE:FF"))
        assertTrue(BikeProfile.isValidBleAddress("aa:bb:cc:dd:ee:ff"))
        assertTrue(BikeProfile.isValidBleAddress("0A:1B:2C:3D:4E:5F"))
    }

    @Test fun `isValidBleAddress rejects malformed MAC`() {
        assertFalse(BikeProfile.isValidBleAddress("AA:BB:CC:DD:EE"))       // too short
        assertFalse(BikeProfile.isValidBleAddress("AABBCCDDEEFF"))         // no colons
        assertFalse(BikeProfile.isValidBleAddress("GG:BB:CC:DD:EE:FF"))    // invalid hex
        assertFalse(BikeProfile.isValidBleAddress("AA:BB:CC:DD:EE:FF:11")) // too long
        assertFalse(BikeProfile.isValidBleAddress(""))
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
./gradlew testReleaseUnitTest --tests "de.dxmedia.bosch.ldi.data.BikeProfileTest" 2>&1 | tail -10
```

Expected: `FAILED` — `BikeProfile` does not exist yet.

- [ ] **Step 3: Implement BikeProfile**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/data/BikeProfile.kt`:

```kotlin
package de.dxmedia.bosch.ldi.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BikeProfile(
    val id: String,
    val name: String,
    val bleAddress: String,
    val isActive: Boolean,
    val enabledFields: Set<String> = DEFAULT_FIELDS
) {
    companion object {
        val DEFAULT_FIELDS: Set<String> = setOf(
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
            "bosch_ldi_connection",
            "bosch_ldi_ebike_dashboard"
        )

        private val NAME_REGEX = Regex("""^[a-zA-Z0-9 _\-]{1,64}$""")
        private val BLE_ADDRESS_REGEX = Regex("""^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$""")

        fun create(name: String, bleAddress: String): BikeProfile = BikeProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            bleAddress = bleAddress,
            isActive = false
        )

        fun isValidName(name: String): Boolean =
            name.isNotBlank() && NAME_REGEX.matches(name)

        fun isValidBleAddress(address: String): Boolean =
            BLE_ADDRESS_REGEX.matches(address)

        fun serialize(profiles: List<BikeProfile>): String {
            val arr = JSONArray()
            profiles.forEach { p ->
                val fields = JSONArray()
                p.enabledFields.sorted().forEach { fields.put(it) }
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("bleAddress", p.bleAddress)
                    put("isActive", p.isActive)
                    put("enabledFields", fields)
                })
            }
            return arr.toString()
        }

        fun deserialize(json: String): List<BikeProfile> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val fieldsArr = obj.getJSONArray("enabledFields")
                BikeProfile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    bleAddress = obj.getString("bleAddress"),
                    isActive = obj.getBoolean("isActive"),
                    enabledFields = (0 until fieldsArr.length())
                        .map { fieldsArr.getString(it) }.toSet()
                )
            }
        }
    }
}
```

Note: `enabledFields` is sorted before serializing so that the `Set`→`List`→`Set` round-trip preserves equality in tests.

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew testReleaseUnitTest --tests "de.dxmedia.bosch.ldi.data.BikeProfileTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — 11 tests passed.

- [ ] **Step 5: Run all tests to check nothing regressed**

```bash
./gradlew testReleaseUnitTest 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/data/BikeProfile.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/data/BikeProfileTest.kt
git commit -m "feat: add BikeProfile with validation and JSON serialization"
```

---

### Task 4: BikeRepository

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/data/BikeRepository.kt`

`BikeRepository` wraps `EncryptedSharedPreferences`. It requires an Android `Context` and therefore cannot be unit-tested without instrumented tests or Robolectric (neither is in scope here — the serialization is already covered by `BikeProfileTest`). After implementing, compile-check is sufficient; end-to-end correctness is verified on-device in Task 5.

- [ ] **Step 1: Implement BikeRepository**

Create `app/src/main/kotlin/de/dxmedia/bosch/ldi/data/BikeRepository.kt`:

```kotlin
package de.dxmedia.bosch.ldi.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class BikeRepository(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "bikes_data",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getProfiles(): List<BikeProfile> {
        val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            BikeProfile.deserialize(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize profiles — resetting", e)
            prefs.edit().remove(KEY_PROFILES).apply()
            emptyList()
        }
    }

    fun getActiveProfile(): BikeProfile? = getProfiles().firstOrNull { it.isActive }

    fun upsert(profile: BikeProfile) {
        val profiles = getProfiles().toMutableList()
        val idx = profiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
        save(profiles)
    }

    fun delete(id: String) {
        save(getProfiles().filter { it.id != id })
    }

    fun setActive(id: String) {
        save(getProfiles().map { it.copy(isActive = it.id == id) })
    }

    private fun save(profiles: List<BikeProfile>) {
        prefs.edit().putString(KEY_PROFILES, BikeProfile.serialize(profiles)).apply()
    }

    companion object {
        private const val TAG = "BikeRepository"
        private const val KEY_PROFILES = "profiles"
    }
}
```

- [ ] **Step 2: Compile-check**

```bash
./gradlew assembleRelease 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/data/BikeRepository.kt
git commit -m "feat: add BikeRepository with EncryptedSharedPreferences persistence"
```

---

### Task 5: Wire BoschLiveDataService

**Files:**
- Modify: `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt`
- Modify: `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataServiceTest.kt`

`BoschLiveDataService` extends `KarooExtension` (which extends Android `Service`). It cannot be instantiated cleanly in JVM unit tests (no real `Context`). The existing test uses reflection to verify field declarations; we extend that pattern to cover the two new fields (`decoder` and `liveData`). The collection/decode wiring is integration-tested on-device after ADB install.

- [ ] **Step 1: Write the updated test**

Replace the full contents of `app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataServiceTest.kt`:

```kotlin
package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleManager
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoschLiveDataServiceTest {

    @Test fun `BoschLiveDataService declares bleManager property`() {
        val field = BoschLiveDataService::class.java.declaredFields
            .firstOrNull { it.name == "bleManager" }
        assertNotNull(field, "bleManager field must be declared on BoschLiveDataService")
        assertTrue(
            BleManager::class.java.isAssignableFrom(field!!.type),
            "bleManager must be of type BleManager"
        )
    }

    @Test fun `BoschLiveDataService declares decoder field`() {
        val field = BoschLiveDataService::class.java.declaredFields
            .firstOrNull { it.name == "decoder" }
        assertNotNull(field, "decoder field must be declared on BoschLiveDataService")
        assertTrue(
            LiveDataDecoder::class.java.isAssignableFrom(field!!.type),
            "decoder must be of type LiveDataDecoder"
        )
    }

    @Test fun `BoschLiveDataService exposes liveData as StateFlow`() {
        val getter = BoschLiveDataService::class.java.methods
            .firstOrNull { it.name == "getLiveData" }
        assertNotNull(getter, "liveData getter must be accessible on BoschLiveDataService")
        assertTrue(
            StateFlow::class.java.isAssignableFrom(getter!!.returnType),
            "liveData must return StateFlow"
        )
    }
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
./gradlew testReleaseUnitTest --tests "de.dxmedia.bosch.ldi.extension.BoschLiveDataServiceTest" 2>&1 | tail -10
```

Expected: `FAILED` — `decoder` field and `liveData` getter not yet present.

- [ ] **Step 3: Update BoschLiveDataService**

Replace the full contents of `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt`:

```kotlin
package de.dxmedia.bosch.ldi.extension

import de.dxmedia.bosch.ldi.ble.BleManager
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

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val types: List<DataTypeImpl> = emptyList()
    // Briefing 4: replace with DataTypeProvider instances for all 15 DataTypes

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this)
        val activeProfile = BikeRepository(this).getActiveProfile()
        bleManager.start(activeProfile?.bleAddress)
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

- [ ] **Step 4: Run all tests**

```bash
./gradlew testReleaseUnitTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all tests in `BleManagerTest`, `BleStateTest`, `LiveDataDecoderTest`, `BikeProfileTest`, `BoschLiveDataServiceTest` pass.

- [ ] **Step 5: Build release APK and sideload for on-device smoke-test**

```bash
./gradlew assembleRelease 2>&1 | tail -5
adb install -r app/build/outputs/apk/release/app-release.apk
```

After install, restart the Karoo. The extension service should start automatically. Verify in `adb logcat -s BleManager BoschLiveDataService` that advertising begins (even without a paired eBike, the service should log `"Advertising started"` with a `null` bonded address).

```bash
adb logcat -s BleManager:I BoschLiveDataService:I 2>/dev/null
```

Expected log output:
```
BleManager: Advertising started
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt \
        app/src/test/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataServiceTest.kt
git commit -m "feat: wire BoschLiveDataService — BLE start with stored address, decode notifications, expose StateFlow"
```
