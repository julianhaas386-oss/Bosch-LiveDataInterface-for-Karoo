# Briefing 1 — Projektgerüst Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vollständiges, kompilierbares Android-Projektskelett für die Bosch LiveDataInterface for Karoo App — inklusive aller DataType-Deklarationen, registriertem KarooExtension-Service-Stub, Protobuf-Definition, EN/DE-Strings, BLE-Permissions und CI-Pipeline.

**Architecture:** Einzel-Modul Android-App (package `de.dxmedia.bosch.ldi`) basierend auf dem `karoo-ext-template`. Der `BoschLiveDataService` ist als KarooExtension registriert, enthält aber noch keine BLE-Logik (kommt Briefing 2). Die `extension_info.xml` deklariert alle 14 DataTypes und 1 Dashboard-Page vollständig. Das Protobuf-Schema ist exakt aus der Bosch LDI Spec v1.0 übernommen.

**Tech Stack:** Kotlin 2.0.0, Android Gradle Plugin 8.6.1, `io.hammerhead:karoo-ext:1.1.8`, Protobuf Kotlin Lite 4.x, Jetpack Compose, DataStore (deklariert, noch nicht verwendet), `compileSdk 35`, `minSdk 23`

**Design Spec:** `docs/superpowers/specs/2026-05-05-bosch-ldi-karoo-design.md`

---

## File Map

```
Bosch x Karoo/
├── .github/workflows/build.yml           NEU — CI pipeline
├── app/
│   ├── build.gradle.kts                  ÄNDERN — Namespace, Protobuf-Plugin, Deps
│   ├── proguard-rules.pro                ÄNDERN — Karoo SDK + Protobuf Keep-Rules
│   └── src/
│       ├── androidTest/kotlin/de/dxmedia/bosch/ldi/
│       │   └── ExtensionRegistrationTest.kt  NEU — Manifest-Verification
│       └── main/
│           ├── AndroidManifest.xml           ÄNDERN — BLE-Permissions, Service
│           ├── kotlin/de/dxmedia/bosch/ldi/
│           │   ├── MainActivity.kt           ÄNDERN — Namespace
│           │   └── extension/
│           │       └── BoschLiveDataService.kt  NEU — KarooExtension-Stub
│           ├── proto/
│           │   └── ebike_live_data.proto     NEU — Bosch Protobuf-Schema
│           └── res/
│               ├── values/strings.xml        ÄNDERN — alle EN-Strings
│               ├── values-de/strings.xml     NEU — alle DE-Strings
│               └── xml/
│                   ├── extension_info.xml    ÄNDERN — 14 DataTypes + 1 Page
│                   └── backup_rules.xml      NEU — DataStore-Ausschluss
├── gradle/libs.versions.toml             ÄNDERN — neue Deps ergänzen
├── gradle.properties                     ÄNDERN — GPR credentials hint
└── settings.gradle.kts                   ÄNDERN — Projektname
```

---

## Task 1: Template klonen und Projektstruktur initialisieren

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle.properties`

- [ ] **Step 1.1: Template-Dateien herunterladen**

```bash
# Im Projektverzeichnis ausführen (git ist bereits initialisiert)
cd "/Users/julianhaas/Desktop/Claude/Bosch x Karoo"

# Template als ZIP laden und entpacken
curl -L https://github.com/hammerheadnav/karoo-ext-template/archive/refs/heads/master.zip \
  -o /tmp/karoo-template.zip
unzip -o /tmp/karoo-template.zip -d /tmp/karoo-template
cp -r /tmp/karoo-template/karoo-ext-template-master/. .
rm /tmp/karoo-template.zip
rm -rf /tmp/karoo-template
```

- [ ] **Step 1.2: Kotlin-Quellpakete umbenennen**

```bash
# Altes Package-Verzeichnis in neues umbenennen
mkdir -p app/src/main/kotlin/de/dxmedia/bosch/ldi/extension
mkdir -p app/src/androidTest/kotlin/de/dxmedia/bosch/ldi
mkdir -p app/src/test/kotlin/de/dxmedia/bosch/ldi
mkdir -p app/src/main/proto

# Template-Dateien löschen (werden in späteren Tasks neu erstellt)
rm -rf app/src/main/kotlin/io
```

- [ ] **Step 1.3: `settings.gradle.kts` anpassen**

Ersetze den gesamten Inhalt:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GPR_USER")).get()
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GPR_KEY")).get()
            }
        }
    }
}

rootProject.name = "Bosch-LiveDataInterface-for-Karoo"
include("app")
```

- [ ] **Step 1.4: `gradle.properties` ergänzen**

Füge am Ende hinzu (keine echten Credentials — nur Hinweis):

```properties
# GitHub Packages Authentication
# Trage deine Werte in ~/.gradle/gradle.properties ein (NICHT hier!):
#   gpr.user=DEIN_GITHUB_USERNAME
#   gpr.key=DEIN_GITHUB_PAT_MIT_READ_PACKAGES_SCOPE
```

- [ ] **Step 1.5: Commit**

```bash
git add settings.gradle.kts gradle.properties app/src/main/kotlin/ \
        app/src/androidTest/ app/src/test/ app/src/main/proto/
git commit -m "chore: initialize project from karoo-ext-template"
```

---

## Task 2: Version-Katalog und Dependencies konfigurieren

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 2.1: `gradle/libs.versions.toml` — Versions und Libraries**

Ersetze den gesamten Inhalt:

```toml
[versions]
agp = "8.6.1"
kotlin = "2.0.0"
protobuf = "4.28.3"
protobufPlugin = "0.9.4"

androidxCore = "1.13.1"
androidxLifecycle = "2.8.6"
androidxActivity = "1.9.3"
androidxComposeUi = "1.7.4"
androidxComposeMaterial = "1.3.0"
androidxDatastore = "1.1.1"
androidxSecurityCrypto = "1.1.0-alpha06"
androidxAppcompat = "1.7.0"

karooExt = "1.1.8"

junit = "4.13.2"
androidxTestExt = "1.2.1"
espresso = "3.6.1"

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
jetbrains-kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
protobuf = { id = "com.google.protobuf", version.ref = "protobufPlugin" }

[libraries]
hammerhead-karoo-ext = { group = "io.hammerhead", name = "karoo-ext", version.ref = "karooExt" }

androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidxCore" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "androidxAppcompat" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "androidxLifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "androidxLifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidxActivity" }
androidx-compose-ui = { module = "androidx.compose.ui:ui", version.ref = "androidxComposeUi" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview", version.ref = "androidxComposeUi" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling", version.ref = "androidxComposeUi" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3", version.ref = "androidxComposeMaterial" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "androidxDatastore" }
androidx-security-crypto = { module = "androidx.security:security-crypto", version.ref = "androidxSecurityCrypto" }

protobuf-kotlin-lite = { module = "com.google.protobuf:protobuf-kotlin-lite", version.ref = "protobuf" }

junit = { module = "junit:junit", version.ref = "junit" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExt" }
androidx-espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "espresso" }

[bundles]
androidx-lifecycle = ["androidx-lifecycle-runtime-compose", "androidx-lifecycle-viewmodel-compose"]
compose-ui = ["androidx-compose-ui", "androidx-compose-material3", "androidx-compose-ui-tooling-preview"]
```

- [ ] **Step 2.2: `app/build.gradle.kts` — vollständig ersetzen**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "de.dxmedia.bosch.ldi"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.dxmedia.bosch.ldi"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.bundles.androidx.lifecycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.protobuf.kotlin.lite)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

- [ ] **Step 2.3: Verify Gradle sync**

```bash
./gradlew dependencies --configuration releaseRuntimeClasspath 2>&1 | grep -E "karoo-ext|protobuf-kotlin-lite|datastore" | head -10
```

Expected output enthält:
```
io.hammerhead:karoo-ext:1.1.8
com.google.protobuf:protobuf-kotlin-lite:4.28.3
androidx.datastore:datastore-preferences:1.1.1
```

- [ ] **Step 2.4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: configure dependencies and protobuf plugin"
```

---

## Task 3: Protobuf-Schema definieren

**Files:**
- Create: `app/src/main/proto/ebike_live_data.proto`

- [ ] **Step 3.1: Proto-Datei erstellen**

Erstelle `app/src/main/proto/ebike_live_data.proto` mit exakt diesem Inhalt (aus Bosch LDI Spec v1.0, Section 2.4):

```proto
// Bosch eBike Live Data Interface — Protocol Buffer Schema
// Source: Bosch Live Data Interface Specification v1.0.0, Section 2.4
// Compatible with proto3 revision >= 3.15 (optional field presence required)
syntax = "proto3";

package com.bosch.ebike;

option java_package = "com.bosch.ebike";
option java_outer_classname = "EBikeLiveDataProto";
option java_multiple_files = true;

// State of the eBike light system.
enum LightState {
  LIGHT_STATE_INVALID = 0;
  LIGHT_STATE_OFF = 1;
  LIGHT_STATE_ON = 2;
}

// Live data streamed from the Bosch eBike Smart System via BLE GATT notifications.
// Fields are optional: absent field = value unchanged since last notification.
// Clients must merge incoming messages with previous state (proto3 optional presence).
message LiveData {
  // Bike speed. Unit: 1/100 km/h. Range: 0–655.35 km/h. Divide by 100 for display.
  optional uint32 speed = 1;

  // Rider cadence. Unit: 1 rpm. Range: −32768–32767 rpm.
  optional int32 cadence = 2;

  // Rider power output. Unit: 1 W. Range: 0–65535 W.
  optional uint32 rider_power = 5;

  // Ambient brightness measured by eBike sensor. Unit: 1/1000 lux. Divide by 1000 for lux.
  optional uint32 ambient_brightness = 9;

  // Battery state of charge. Unit: 1 %. Range: 0–100 %.
  optional uint32 battery_soc = 10;

  // Current UTC time from eBike. Unit: seconds since Unix epoch (1970-01-01).
  optional int64 time = 11;

  // Total distance traveled. Unit: 1 meter.
  optional uint32 odometer = 12;

  // State of the eBike light system.
  optional LightState bike_light = 17;

  // True if the eBike system is in a locked state.
  optional bool system_locked = 21;

  // True if a charger is connected (does not mean charging is active).
  optional bool charger_connected = 22;

  // True if the light reserve state is active.
  optional bool light_reserve_state = 23;

  // True if a diagnosis tool (e.g. at bike dealer) is connected.
  optional bool diagnosis_program_active = 24;

  // True if the bike is standing still (may have minor movement depending on sensor accuracy).
  optional bool bike_not_driving = 25;
}
```

- [ ] **Step 3.2: Protobuf-Codegenerierung prüfen**

```bash
./gradlew generateDebugProto 2>&1 | tail -5
```

Expected:
```
BUILD SUCCESSFUL
```

Verify generated files:
```bash
find app/build/generated/source/proto -name "*.kt" | head -5
```

Expected: Mindestens `LiveData.kt`, `EBikeLiveDataProto.kt`, `LightState.kt` gefunden.

- [ ] **Step 3.3: Commit**

```bash
git add app/src/main/proto/ebike_live_data.proto
git commit -m "feat: add Bosch LDI protobuf schema (ebike_live_data.proto)"
```

---

## Task 4: Android Manifest — BLE-Permissions und Service

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 4.1: Failing Test schreiben**

Erstelle `app/src/androidTest/kotlin/de/dxmedia/bosch/ldi/ExtensionRegistrationTest.kt`:

```kotlin
package de.dxmedia.bosch.ldi

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtensionRegistrationTest {

    private val context by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun extensionServiceIsRegisteredWithKarooIntentFilter() {
        val pm = context.packageManager
        val intent = Intent("io.hammerhead.karooext.KAROO_EXTENSION")
            .setPackage(context.packageName)
        val services = pm.queryIntentServices(intent, 0)
        assertEquals("Exactly one KarooExtension service must be registered", 1, services.size)
        assertTrue(
            "Service must be BoschLiveDataService",
            services[0].serviceInfo.name.endsWith("BoschLiveDataService")
        )
    }

    @Test
    fun extensionServiceHasExtensionInfoMetaData() {
        val pm = context.packageManager
        val intent = Intent("io.hammerhead.karooext.KAROO_EXTENSION")
            .setPackage(context.packageName)
        val services = pm.queryIntentServices(intent, android.content.pm.PackageManager.GET_META_DATA)
        val metaData = services[0].serviceInfo.metaData
        assertTrue(
            "EXTENSION_INFO meta-data must be declared",
            metaData.containsKey("io.hammerhead.karooext.EXTENSION_INFO")
        )
    }

    @Test
    fun appHasBluetoothAdvertisePermission() {
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()
        assertTrue(
            "BLUETOOTH_ADVERTISE must be declared",
            permissions.contains("android.permission.BLUETOOTH_ADVERTISE")
        )
        assertTrue(
            "BLUETOOTH_CONNECT must be declared",
            permissions.contains("android.permission.BLUETOOTH_CONNECT")
        )
        assertTrue(
            "BLUETOOTH_SCAN must be declared",
            permissions.contains("android.permission.BLUETOOTH_SCAN")
        )
    }
}
```

- [ ] **Step 4.2: Test ausführen — erwartet FAIL**

```bash
./gradlew connectedDebugAndroidTest --tests "de.dxmedia.bosch.ldi.ExtensionRegistrationTest" 2>&1 | tail -20
```

Expected: Tests schlagen fehl (Service nicht registriert, Permissions fehlen).

- [ ] **Step 4.3: `AndroidManifest.xml` vollständig ersetzen**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- BLE Permissions: Android 12+ (API 31+) -->
    <uses-permission
        android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
    <!-- Required by Android OS for BLE advertising; app does not use location -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <!-- Legacy BLE permissions for API < 31 -->
    <uses-permission
        android:maxSdkVersion="30"
        android:name="android.permission.BLUETOOTH" />
    <uses-permission
        android:maxSdkVersion="30"
        android:name="android.permission.BLUETOOTH_ADMIN" />
    <!-- Foreground service for continuous BLE connection -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <!-- Vibration for pairing feedback -->
    <uses-permission android:name="android.permission.VIBRATE" />

    <uses-feature
        android:name="android.hardware.bluetooth_le"
        android:required="true" />

    <application
        android:allowBackup="true"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!--
            KarooExtension Service — registriert die App beim Karoo OS.
            exported="true" ist Pflicht, damit das Karoo OS den Service binden kann.
            tools:ignore="ExportedService": Export ist hier intentional und erforderlich.
        -->
        <service
            android:name=".extension.BoschLiveDataService"
            android:exported="true"
            android:foregroundServiceType="connectedDevice"
            tools:ignore="ExportedService">
            <intent-filter>
                <action android:name="io.hammerhead.karooext.KAROO_EXTENSION" />
            </intent-filter>
            <meta-data
                android:name="io.hammerhead.karooext.EXTENSION_INFO"
                android:resource="@xml/extension_info" />
        </service>

    </application>

</manifest>
```

- [ ] **Step 4.4: Tests ausführen — erwartet PASS**

```bash
./gradlew connectedDebugAndroidTest --tests "de.dxmedia.bosch.ldi.ExtensionRegistrationTest" 2>&1 | tail -10
```

Expected:
```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESSFUL
```

- [ ] **Step 4.5: `app/src/main/res/xml/backup_rules.xml` erstellen**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Schließt sensible DataStore-Dateien vom Android-Backup aus -->
<full-backup-content>
    <exclude domain="sharedpref" path="." />
    <exclude domain="database" path="." />
    <exclude domain="file" path="datastore" />
</full-backup-content>
```

- [ ] **Step 4.6: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/main/res/xml/backup_rules.xml \
        app/src/androidTest/
git commit -m "feat: add BLE permissions and register KarooExtension service in manifest"
```

---

## Task 5: extension_info.xml — alle DataTypes und Dashboard-Page

**Files:**
- Modify: `app/src/main/res/xml/extension_info.xml`

- [ ] **Step 5.1: `extension_info.xml` vollständig ersetzen**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
    Bosch LiveDataInterface for Karoo — Extension Declaration
    Registriert 13 Bosch-Datenpunkte + 1 Verbindungsstatus-Feld + 1 Dashboard-Page
    beim Karoo OS.

    DataType-IDs orientieren sich an den Proto-Feldnamen aus ebike_live_data.proto.
    Bosch LDI Spec v1.0.0 — https://github.com/julianhaas386-oss/Bosch-LiveDataInterface-for-Karoo
-->
<ExtensionInfo
    displayName="@string/extension_name"
    icon="@drawable/ic_launcher_foreground"
    id="bosch-ldi"
    scansDevices="false">

    <!-- ── Fahrleistungs-Daten ─────────────────────────────────────── -->

    <!-- Geschwindigkeit vom Bosch eBike-Antrieb (1/100 km/h → in km/h oder mph umgerechnet) -->
    <DataType
        typeId="bosch_ldi_speed"
        displayName="@string/field_speed_name"
        description="@string/field_speed_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- Trittfrequenz des Fahrers in rpm -->
    <DataType
        typeId="bosch_ldi_cadence"
        displayName="@string/field_cadence_name"
        description="@string/field_cadence_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- Fahrerleistung in Watt (nur Fahrerleistung, keine Motor-Unterstützung) -->
    <DataType
        typeId="bosch_ldi_rider_power"
        displayName="@string/field_rider_power_name"
        description="@string/field_rider_power_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- ── Akku & System ───────────────────────────────────────────── -->

    <!-- Akkustand des eBike-Akkus in Prozent (0–100) -->
    <DataType
        typeId="bosch_ldi_battery_soc"
        displayName="@string/field_battery_soc_name"
        description="@string/field_battery_soc_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- Gesamtstrecke des eBikes in Kilometern (Odometer) -->
    <DataType
        typeId="bosch_ldi_odometer"
        displayName="@string/field_odometer_name"
        description="@string/field_odometer_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- UTC-Zeit vom eBike-System -->
    <DataType
        typeId="bosch_ldi_time"
        displayName="@string/field_time_name"
        description="@string/field_time_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- ── Licht ───────────────────────────────────────────────────── -->

    <!-- Lichtstatus (AUS / AN) -->
    <DataType
        typeId="bosch_ldi_bike_light"
        displayName="@string/field_bike_light_name"
        description="@string/field_bike_light_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- Umgebungshelligkeit in Lux (1/1000 lux → umgerechnet) -->
    <DataType
        typeId="bosch_ldi_ambient_brightness"
        displayName="@string/field_ambient_brightness_name"
        description="@string/field_ambient_brightness_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- Lichtreserve aktiv (Bool) -->
    <DataType
        typeId="bosch_ldi_light_reserve_state"
        displayName="@string/field_light_reserve_name"
        description="@string/field_light_reserve_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- ── Systemstatus ────────────────────────────────────────────── -->

    <!-- System gesperrt (Bool) -->
    <DataType
        typeId="bosch_ldi_system_locked"
        displayName="@string/field_system_locked_name"
        description="@string/field_system_locked_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- Ladegerät angeschlossen (Bool) -->
    <DataType
        typeId="bosch_ldi_charger_connected"
        displayName="@string/field_charger_connected_name"
        description="@string/field_charger_connected_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- Diagnoseprogramm aktiv, z.B. beim Händler (Bool) -->
    <DataType
        typeId="bosch_ldi_diagnosis_program_active"
        displayName="@string/field_diagnosis_name"
        description="@string/field_diagnosis_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- Fahrrad steht still (Bool) -->
    <DataType
        typeId="bosch_ldi_bike_not_driving"
        displayName="@string/field_bike_not_driving_name"
        description="@string/field_bike_not_driving_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- ── App-eigenes Status-Feld (kein Bosch-Datenpunkt) ──────────── -->

    <!-- BLE-Verbindungsstatus: CONNECTED / SEARCHING / DISCONNECTED -->
    <DataType
        typeId="bosch_ldi_connection"
        displayName="@string/field_connection_name"
        description="@string/field_connection_desc"
        icon="@drawable/ic_launcher_foreground" />

    <!-- ── Dashboard-Seite ────────────────────────────────────────── -->

    <!-- Dedizierte eBike-Übersichtsseite mit SOC, Leistung, Kadenz, Geschwindigkeit + Odometer -->
    <DataType
        typeId="bosch_ldi_ebike_dashboard"
        displayName="@string/page_dashboard_name"
        description="@string/page_dashboard_desc"
        icon="@drawable/ic_launcher_foreground"
        graphical="true" />

</ExtensionInfo>
```

- [ ] **Step 5.2: Commit**

```bash
git add app/src/main/res/xml/extension_info.xml
git commit -m "feat: declare all 14 DataTypes and eBike dashboard page in extension_info.xml"
```

---

## Task 6: BoschLiveDataService — KarooExtension Stub

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt`

- [ ] **Step 6.1: Service-Stub erstellen**

```kotlin
package de.dxmedia.bosch.ldi.extension

import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataTypeImpl

/**
 * Bosch LiveDataInterface KarooExtension Service.
 *
 * Registriert sich beim Karoo OS und stellt 14 DataTypes (13 Bosch-Datenpunkte
 * + 1 Verbindungsstatus) sowie eine eBike-Dashboard-Seite bereit.
 *
 * BLE-Logik: Briefing 2 (BleManager)
 * DataType-Implementierung: Briefing 4 (DataTypeProvider)
 */
class BoschLiveDataService : KarooExtension("bosch-ldi", "1.0.0") {

    override val types: List<DataTypeImpl> = emptyList()
    // TODO Briefing 4: DataTypeProvider-Instanzen für alle 14 DataTypes ergänzen

    override fun onCreate() {
        super.onCreate()
        // TODO Briefing 2: BleManager initialisieren
    }

    override fun onDestroy() {
        // TODO Briefing 2: BleManager.disconnect() aufrufen
        super.onDestroy()
    }
}
```

- [ ] **Step 6.2: Prüfen ob Projekt kompiliert**

```bash
./gradlew assembleDebug 2>&1 | tail -10
```

Expected:
```
BUILD SUCCESSFUL
```

- [ ] **Step 6.3: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/extension/BoschLiveDataService.kt
git commit -m "feat: add BoschLiveDataService KarooExtension stub"
```

---

## Task 7: Strings EN und DE

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values-de/strings.xml`

- [ ] **Step 7.1: `res/values/strings.xml` — Englisch (Default)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- App -->
    <string name="app_name">Bosch LiveDataInterface</string>
    <string name="extension_name">Bosch eBike</string>

    <!-- DataType: Speed -->
    <string name="field_speed_name">eBike Speed</string>
    <string name="field_speed_desc">Speed from the Bosch eBike drive system</string>

    <!-- DataType: Cadence -->
    <string name="field_cadence_name">Cadence (eBike)</string>
    <string name="field_cadence_desc">Rider cadence measured by the eBike system</string>

    <!-- DataType: Rider Power -->
    <string name="field_rider_power_name">Rider Power</string>
    <string name="field_rider_power_desc">Rider power output in watts (excluding motor assistance)</string>

    <!-- DataType: Battery SOC -->
    <string name="field_battery_soc_name">eBike Battery</string>
    <string name="field_battery_soc_desc">State of charge of the eBike battery in percent</string>

    <!-- DataType: Odometer -->
    <string name="field_odometer_name">eBike Odometer</string>
    <string name="field_odometer_desc">Total distance traveled by the eBike</string>

    <!-- DataType: Time -->
    <string name="field_time_name">eBike Time</string>
    <string name="field_time_desc">Current UTC time from the eBike system</string>

    <!-- DataType: Bike Light -->
    <string name="field_bike_light_name">Bike Light</string>
    <string name="field_bike_light_desc">Current state of the eBike light system</string>
    <string name="field_bike_light_on">ON</string>
    <string name="field_bike_light_off">OFF</string>

    <!-- DataType: Ambient Brightness -->
    <string name="field_ambient_brightness_name">Ambient Brightness</string>
    <string name="field_ambient_brightness_desc">Ambient light level measured by the eBike sensor</string>

    <!-- DataType: Light Reserve -->
    <string name="field_light_reserve_name">Light Reserve</string>
    <string name="field_light_reserve_desc">Indicates if the light reserve is active</string>
    <string name="field_light_reserve_active">ACTIVE</string>
    <string name="field_light_reserve_inactive">OK</string>

    <!-- DataType: System Locked -->
    <string name="field_system_locked_name">System Lock</string>
    <string name="field_system_locked_desc">Indicates if the eBike system is locked</string>
    <string name="field_system_locked_yes">LOCKED</string>
    <string name="field_system_locked_no">UNLOCKED</string>

    <!-- DataType: Charger Connected -->
    <string name="field_charger_connected_name">Charger</string>
    <string name="field_charger_connected_desc">Indicates if a charger is connected to the eBike</string>
    <string name="field_charger_connected_yes">CONNECTED</string>
    <string name="field_charger_connected_no">DISCONNECTED</string>

    <!-- DataType: Diagnosis Program Active -->
    <string name="field_diagnosis_name">Diagnosis</string>
    <string name="field_diagnosis_desc">Indicates if a diagnosis tool is connected (e.g. at a bike dealer)</string>
    <string name="field_diagnosis_active">ACTIVE</string>
    <string name="field_diagnosis_inactive">INACTIVE</string>

    <!-- DataType: Bike Not Driving -->
    <string name="field_bike_not_driving_name">Bike Stopped</string>
    <string name="field_bike_not_driving_desc">Indicates if the bike is standing still</string>
    <string name="field_bike_not_driving_yes">STOPPED</string>
    <string name="field_bike_not_driving_no">MOVING</string>

    <!-- DataType: Connection Status (app-derived) -->
    <string name="field_connection_name">eBike Connection</string>
    <string name="field_connection_desc">BLE connection status to the Bosch eBike</string>
    <string name="field_connection_connected">CONNECTED</string>
    <string name="field_connection_searching">SEARCHING</string>
    <string name="field_connection_disconnected">DISCONNECTED</string>

    <!-- Page: eBike Dashboard -->
    <string name="page_dashboard_name">eBike Dashboard</string>
    <string name="page_dashboard_desc">All Bosch eBike live data at a glance</string>

    <!-- Pairing Wizard -->
    <string name="wizard_title">Connect Your eBike</string>
    <string name="wizard_step1_title">How It Works</string>
    <string name="wizard_step1_body">Your Karoo will broadcast a Bluetooth signal. Your Bosch eBike will detect it and initiate the connection. Make sure your eBike is powered on and within range (approx. 10 m).</string>
    <string name="wizard_step2_title">Searching for eBike…</string>
    <string name="wizard_step2_body">Your Karoo is now advertising. Confirm the connection on your eBike display when prompted.</string>
    <string name="wizard_step3_success_title">Connected!</string>
    <string name="wizard_step3_success_body">Your eBike has been successfully paired. Live data will now appear in your ride profiles.</string>
    <string name="wizard_step3_failure_title">No eBike Found</string>
    <string name="wizard_step3_failure_body">No Bosch eBike was found within 60 seconds. Please ensure your eBike is powered on and nearby, then try again.</string>
    <string name="wizard_btn_next">Next</string>
    <string name="wizard_btn_start">Start Pairing</string>
    <string name="wizard_btn_retry">Try Again</string>
    <string name="wizard_btn_done">Done</string>
    <string name="wizard_btn_cancel">Cancel</string>
    <string name="wizard_security_note">Note: This pairing uses Bluetooth LE Secure Connections (Just Works). Encryption is active, but there is no man-in-the-middle protection. Only pair in trusted environments.</string>

    <!-- Bike Management -->
    <string name="bike_list_title">My eBikes</string>
    <string name="bike_list_empty">No eBike paired yet.\nTap + to add your first eBike.</string>
    <string name="bike_list_btn_add">Pair New eBike</string>
    <string name="bike_detail_title">eBike Settings</string>
    <string name="bike_detail_name_label">Name</string>
    <string name="bike_detail_name_hint">e.g. Trek Allant+</string>
    <string name="bike_detail_active_label">Active (connect to this eBike)</string>
    <string name="bike_detail_fields_title">Data Fields</string>
    <string name="bike_detail_fields_subtitle">Select which fields appear in your ride profiles</string>
    <string name="bike_detail_btn_remove">Forget This eBike</string>
    <string name="bike_detail_btn_save">Save</string>
    <string name="bike_detail_remove_confirm_title">Forget eBike?</string>
    <string name="bike_detail_remove_confirm_body">This will remove the pairing for \"%s\". You can re-pair at any time.</string>
    <string name="bike_detail_remove_confirm_ok">Forget</string>
    <string name="bike_detail_remove_confirm_cancel">Cancel</string>

    <!-- Settings -->
    <string name="settings_title">Settings</string>
    <string name="settings_language_label">Language</string>
    <string name="settings_language_system">System Default</string>
    <string name="settings_language_en">English</string>
    <string name="settings_language_de">Deutsch</string>
    <string name="settings_version_label">Version</string>

    <!-- General -->
    <string name="btn_ok">OK</string>
    <string name="btn_cancel">Cancel</string>
    <string name="error_ble_unavailable">Bluetooth is not available on this device.</string>
    <string name="error_permissions_required">Bluetooth permissions are required for eBike connectivity.</string>
    <string name="error_permissions_open_settings">Open Settings</string>
</resources>
```

- [ ] **Step 7.2: `res/values-de/strings.xml` — Deutsch erstellen**

Erstelle den Ordner und die Datei:

```bash
mkdir -p app/src/main/res/values-de
```

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- App -->
    <string name="app_name">Bosch LiveDataInterface</string>
    <string name="extension_name">Bosch eBike</string>

    <!-- DataType: Geschwindigkeit -->
    <string name="field_speed_name">eBike-Geschwindigkeit</string>
    <string name="field_speed_desc">Geschwindigkeit vom Bosch eBike-Antriebssystem</string>

    <!-- DataType: Kadenz -->
    <string name="field_cadence_name">Kadenz (eBike)</string>
    <string name="field_cadence_desc">Trittfrequenz gemessen vom eBike-System</string>

    <!-- DataType: Fahrerleistung -->
    <string name="field_rider_power_name">Fahrerleistung</string>
    <string name="field_rider_power_desc">Fahrerleistung in Watt (ohne Motorunterstützung)</string>

    <!-- DataType: Akkustand -->
    <string name="field_battery_soc_name">eBike-Akku</string>
    <string name="field_battery_soc_desc">Ladezustand des eBike-Akkus in Prozent</string>

    <!-- DataType: Odometer -->
    <string name="field_odometer_name">eBike-Gesamtstrecke</string>
    <string name="field_odometer_desc">Gesamte zurückgelegte Strecke des eBikes</string>

    <!-- DataType: Zeit -->
    <string name="field_time_name">eBike-Zeit</string>
    <string name="field_time_desc">Aktuelle UTC-Zeit vom eBike-System</string>

    <!-- DataType: Fahrrradlicht -->
    <string name="field_bike_light_name">Fahrrradlicht</string>
    <string name="field_bike_light_desc">Aktueller Status des eBike-Lichtsystems</string>
    <string name="field_bike_light_on">AN</string>
    <string name="field_bike_light_off">AUS</string>

    <!-- DataType: Umgebungshelligkeit -->
    <string name="field_ambient_brightness_name">Umgebungshelligkeit</string>
    <string name="field_ambient_brightness_desc">Gemessene Umgebungslichtstärke vom eBike-Sensor</string>

    <!-- DataType: Lichtreserve -->
    <string name="field_light_reserve_name">Lichtreserve</string>
    <string name="field_light_reserve_desc">Zeigt an, ob die Lichtreserve aktiv ist</string>
    <string name="field_light_reserve_active">AKTIV</string>
    <string name="field_light_reserve_inactive">OK</string>

    <!-- DataType: System gesperrt -->
    <string name="field_system_locked_name">System gesperrt</string>
    <string name="field_system_locked_desc">Zeigt an, ob das eBike-System gesperrt ist</string>
    <string name="field_system_locked_yes">GESPERRT</string>
    <string name="field_system_locked_no">ENTSPERRT</string>

    <!-- DataType: Ladegerät -->
    <string name="field_charger_connected_name">Ladegerät</string>
    <string name="field_charger_connected_desc">Zeigt an, ob ein Ladegerät am eBike angeschlossen ist</string>
    <string name="field_charger_connected_yes">VERBUNDEN</string>
    <string name="field_charger_connected_no">GETRENNT</string>

    <!-- DataType: Diagnose -->
    <string name="field_diagnosis_name">Diagnose</string>
    <string name="field_diagnosis_desc">Zeigt an, ob ein Diagnosegerät angeschlossen ist (z.B. beim Händler)</string>
    <string name="field_diagnosis_active">AKTIV</string>
    <string name="field_diagnosis_inactive">INAKTIV</string>

    <!-- DataType: Fahrrad steht -->
    <string name="field_bike_not_driving_name">Fahrrad steht</string>
    <string name="field_bike_not_driving_desc">Zeigt an, ob das Fahrrad stillsteht</string>
    <string name="field_bike_not_driving_yes">STEHT</string>
    <string name="field_bike_not_driving_no">FÄHRT</string>

    <!-- DataType: Verbindungsstatus -->
    <string name="field_connection_name">eBike-Verbindung</string>
    <string name="field_connection_desc">BLE-Verbindungsstatus zum Bosch eBike</string>
    <string name="field_connection_connected">VERBUNDEN</string>
    <string name="field_connection_searching">SUCHE…</string>
    <string name="field_connection_disconnected">GETRENNT</string>

    <!-- Seite: eBike-Dashboard -->
    <string name="page_dashboard_name">eBike-Dashboard</string>
    <string name="page_dashboard_desc">Alle Bosch eBike Livedaten auf einen Blick</string>

    <!-- Pairing-Wizard -->
    <string name="wizard_title">eBike verbinden</string>
    <string name="wizard_step1_title">So funktioniert es</string>
    <string name="wizard_step1_body">Dein Karoo sendet ein Bluetooth-Signal. Dein Bosch eBike erkennt es und baut die Verbindung auf. Stelle sicher, dass dein eBike eingeschaltet und in der Nähe ist (ca. 10 m).</string>
    <string name="wizard_step2_title">Suche läuft…</string>
    <string name="wizard_step2_body">Dein Karoo advertiert jetzt. Bestätige die Verbindung am eBike-Display, wenn du dazu aufgefordert wirst.</string>
    <string name="wizard_step3_success_title">Verbunden!</string>
    <string name="wizard_step3_success_body">Dein eBike wurde erfolgreich gekoppelt. Livedaten erscheinen jetzt in deinen Fahrprofilen.</string>
    <string name="wizard_step3_failure_title">Kein eBike gefunden</string>
    <string name="wizard_step3_failure_body">Innerhalb von 60 Sekunden wurde kein Bosch eBike gefunden. Bitte stelle sicher, dass dein eBike eingeschaltet und in der Nähe ist, und versuche es erneut.</string>
    <string name="wizard_btn_next">Weiter</string>
    <string name="wizard_btn_start">Kopplung starten</string>
    <string name="wizard_btn_retry">Nochmal versuchen</string>
    <string name="wizard_btn_done">Fertig</string>
    <string name="wizard_btn_cancel">Abbrechen</string>
    <string name="wizard_security_note">Hinweis: Die Kopplung verwendet Bluetooth LE Secure Connections (Just Works). Verschlüsselung ist aktiv, aber ohne Man-in-the-Middle-Schutz. Koppel nur in vertrauenswürdiger Umgebung.</string>

    <!-- Bike-Verwaltung -->
    <string name="bike_list_title">Meine eBikes</string>
    <string name="bike_list_empty">Noch kein eBike gekoppelt.\nTippe auf +, um dein erstes eBike hinzuzufügen.</string>
    <string name="bike_list_btn_add">Neues eBike koppeln</string>
    <string name="bike_detail_title">eBike-Einstellungen</string>
    <string name="bike_detail_name_label">Name</string>
    <string name="bike_detail_name_hint">z.B. Trek Allant+</string>
    <string name="bike_detail_active_label">Aktiv (mit diesem eBike verbinden)</string>
    <string name="bike_detail_fields_title">Datenfelder</string>
    <string name="bike_detail_fields_subtitle">Wähle, welche Felder in deinen Fahrprofilen erscheinen</string>
    <string name="bike_detail_btn_remove">eBike vergessen</string>
    <string name="bike_detail_btn_save">Speichern</string>
    <string name="bike_detail_remove_confirm_title">eBike vergessen?</string>
    <string name="bike_detail_remove_confirm_body">Die Kopplung für \"%s\" wird entfernt. Du kannst jederzeit neu koppeln.</string>
    <string name="bike_detail_remove_confirm_ok">Vergessen</string>
    <string name="bike_detail_remove_confirm_cancel">Abbrechen</string>

    <!-- Einstellungen -->
    <string name="settings_title">Einstellungen</string>
    <string name="settings_language_label">Sprache</string>
    <string name="settings_language_system">Systemsprache</string>
    <string name="settings_language_en">English</string>
    <string name="settings_language_de">Deutsch</string>
    <string name="settings_version_label">Version</string>

    <!-- Allgemein -->
    <string name="btn_ok">OK</string>
    <string name="btn_cancel">Abbrechen</string>
    <string name="error_ble_unavailable">Bluetooth ist auf diesem Gerät nicht verfügbar.</string>
    <string name="error_permissions_required">Bluetooth-Berechtigungen sind für die eBike-Verbindung erforderlich.</string>
    <string name="error_permissions_open_settings">Einstellungen öffnen</string>
</resources>
```

- [ ] **Step 7.3: Build prüfen**

```bash
./gradlew assembleDebug 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7.4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-de/
git commit -m "feat: add EN and DE string resources for all UI and data fields"
```

---

## Task 8: MainActivity — Placeholder

**Files:**
- Create: `app/src/main/kotlin/de/dxmedia/bosch/ldi/MainActivity.kt`

- [ ] **Step 8.1: `MainActivity.kt` erstellen**

```kotlin
package de.dxmedia.bosch.ldi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * Einstiegspunkt der App.
 * Vollständige Navigation und UI: Briefing 5.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = stringResource(R.string.app_name))
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 8.2: Commit**

```bash
git add app/src/main/kotlin/de/dxmedia/bosch/ldi/MainActivity.kt
git commit -m "feat: add MainActivity placeholder"
```

---

## Task 9: ProGuard-Regeln

**Files:**
- Modify: `app/proguard-rules.pro`

- [ ] **Step 9.1: `proguard-rules.pro` ersetzen**

```proguard
# Karoo Extension SDK — öffentliche API behalten
-keep class io.hammerhead.karooext.** { *; }

# Bosch Protobuf generierter Code — vollständig behalten
-keep class com.bosch.ebike.** { *; }
-keep class com.google.protobuf.** { *; }

# Protobuf-spezifische Regeln
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keepclassmembers class * extends com.google.protobuf.AbstractMessageLite {
    <fields>;
}

# DataStore / EncryptedSharedPreferences
-keep class androidx.datastore.** { *; }
-keep class androidx.security.crypto.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.** { *; }

# Kein Log.d/v/i in Release-Builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
```

- [ ] **Step 9.2: Release-Build prüfen**

```bash
./gradlew assembleRelease 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` (Release-APK unter `app/build/outputs/apk/release/`)

- [ ] **Step 9.3: Commit**

```bash
git add app/proguard-rules.pro
git commit -m "build: configure ProGuard rules for release builds"
```

---

## Task 10: GitHub Actions CI

**Files:**
- Create: `.github/workflows/build.yml`

- [ ] **Step 10.1: Workflow erstellen**

```bash
mkdir -p .github/workflows
```

Erstelle `.github/workflows/build.yml`:

```yaml
name: Build & Lint

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    name: Build & Lint
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug
        env:
          # GitHub Packages Authentication für io.hammerhead:karoo-ext
          # GPR_USER und GPR_KEY müssen als Repository Secrets hinterlegt sein.
          # GPR_KEY = Personal Access Token mit 'read:packages' scope
          GPR_USER: ${{ secrets.GPR_USER }}
          GPR_KEY: ${{ secrets.GPR_KEY }}

      - name: Run Lint
        run: ./gradlew lintDebug
        env:
          GPR_USER: ${{ secrets.GPR_USER }}
          GPR_KEY: ${{ secrets.GPR_KEY }}

      - name: Upload Debug APK
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: app/build/outputs/apk/debug/*.apk
          retention-days: 7

      - name: Upload Lint Report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: lint-report
          path: app/build/reports/lint-results-debug.html
          retention-days: 7
```

- [ ] **Step 10.2: GitHub Secrets einrichten**

Im Browser: https://github.com/julianhaas386-oss/Bosch-LiveDataInterface-for-Karoo/settings/secrets/actions

Zwei Secrets anlegen:
- `GPR_USER`: Dein GitHub-Username (`julianhaas386-oss`)
- `GPR_KEY`: GitHub Personal Access Token mit Scope `read:packages`
  → Erstellen unter: https://github.com/settings/tokens/new?scopes=read:packages

- [ ] **Step 10.3: Commit und Push**

```bash
git add .github/
git commit -m "ci: add GitHub Actions build and lint workflow"
git push origin main
```

- [ ] **Step 10.4: CI-Status prüfen**

```bash
gh run list --limit 3
```

Expected: Ein laufender oder erfolgreicher Run für den letzten Commit auf `main`.

---

## Task 11: Gesamtverifikation

- [ ] **Step 11.1: Alle Tests lokal ausführen**

```bash
./gradlew test lintDebug assembleRelease 2>&1 | tail -20
```

Expected:
```
BUILD SUCCESSFUL
```

- [ ] **Step 11.2: Projektstruktur prüfen**

```bash
find app/src/main/kotlin app/src/main/proto app/src/main/res \
     .github/workflows -type f | sort
```

Expected: Alle Dateien aus dem File Map am Anfang dieses Plans sind vorhanden.

- [ ] **Step 11.3: Finalen Stand pushen**

```bash
git status  # sollte "nothing to commit" zeigen
git push origin main
```

---

## Briefing 1 abgeschlossen — Was als nächstes kommt

- **Briefing 2:** BLE-Stack — `BleManager`, Advertising als GAP Peripheral, GATT Client, ATT_MTU/DLE-Verhandlung, LE Secure Connection Bonding, Reconnect-Logik
- **Briefing 3:** Datenschicht — `LiveDataDecoder` (Protobuf → `BoschLiveData`), `BikeProfile`, `EncryptedDataStore`, `BikeRepository`
