# Bosch LiveDataInterface for Karoo — Gesamt-Design

**Datum:** 2026-05-05  
**App-Name:** Bosch LiveDataInterface for Karoo  
**Entwickler:** DXMedia GmbH  
**Repository:** https://github.com/julianhaas386-oss/Bosch-LiveDataInterface-for-Karoo  
**Lizenz:** Apache 2.0  
**Zielgerät:** Hammerhead Karoo 3  

---

## 1. Ziel

Eine Android-Extension für den Karoo 3, die via Bluetooth Low Energy eine Verbindung zum Bosch eBike Smart System aufbaut und Live-Daten (Geschwindigkeit, Akkustand, Leistung, Kadenz u. a.) als native Karoo-Datenfelder und als dediziertes eBike-Dashboard anzeigt. Die App wird Open Source veröffentlicht und richtet sich an die Karoo-Community.

---

## 2. Anforderungen

### Funktional
- Alle 13 Bosch-Datenpunkte als eigenständige Karoo-Datenfelder, alle standardmäßig aktiviert
- Zusätzlich 1 app-eigenes Verbindungsstatus-Feld (`bosch_ldi_connection`) — insgesamt 14 Karoo DataTypes
- Einzelne Felder können pro eBike-Profil deaktiviert werden
- Dedizierte eBike-Dashboard-Seite für Ride-Profile (alle Bosch-Daten auf einen Blick)
- Unterstützung mehrerer eBike-Profile (Name, BLE-Adresse, Feld-Konfiguration)
- Geführter Pairing-Wizard beim Erststart
- Automatische Wiederverbindung nach Link Loss
- Gute Dokumentation (README, KDoc, CHANGELOG) — Community-Standard
- **Mehrsprachigkeit:** Deutsch und Englisch, wählbar in den App-Einstellungen (Fallback: Systemsprache)

### Nicht in Scope (v1)
- Steuerung des eBike-Antriebs (read-only Interface)
- Smartphone-Kompatibilität (nur Karoo)
- Eigene Karte / Navigation

---

## 3. Architektur

### Technologie-Stack
| Aspekt | Entscheidung |
|---|---|
| Sprache | Kotlin |
| UI | Jetpack Compose |
| Persistenz | DataStore (Preferences) |
| BLE | Android BLE APIs (kein Framework) |
| Protobuf | `com.google.protobuf:protobuf-kotlin-lite` + Gradle Plugin |
| DI | Keins (zu kleiner Scope) |
| Min SDK | 23 (Android 6.0, entspricht Karoo-Mindestanforderung) |
| Karoo SDK | `io.hammerhead:karoo-ext` (aktuelle Version) |
| Basis | `karoo-ext-template` |

### Paketstruktur
```
app/src/main/kotlin/de/dxmedia/bosch/ldi/
├── ble/
│   ├── BleManager.kt          # Advertising, GATT Client, Bond, Reconnect
│   └── BleState.kt            # Sealed class: Disconnected / Advertising / Connected
├── proto/                     # Generierter Protobuf-Code (nicht manuell bearbeiten)
├── data/
│   ├── BikeProfile.kt         # Data class: id, name, bleAddress, isActive, enabledFields
│   └── BikeRepository.kt      # DataStore CRUD für BikeProfile-Liste
├── extension/
│   ├── BoschLiveDataService.kt # KarooExtension Service
│   ├── LiveDataDecoder.kt      # Protobuf-Bytes → BoschLiveData (stateful merge)
│   ├── DataTypeProvider.kt     # Verteilt Werte an 13 Karoo DataTypes
│   └── EBikePage.kt            # RemoteViews Dashboard-Seite
├── ui/
│   ├── MainActivity.kt
│   ├── wizard/                 # PairingWizardScreen (3 Steps)
│   ├── bikes/                  # BikeListScreen, BikeDetailScreen
│   └── settings/               # FieldSettingsScreen
└── util/
    └── Extensions.kt
```

---

## 4. Bosch Live Data Interface — Technische Eckdaten

- **Protokoll:** Bluetooth Low Energy (BLE Core Spec 4.2+)
- **Rollen:** eBike = GAP Central + GATT Server; Karoo-App = GAP Peripheral + GATT Client
- **Service UUID:** `0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4`
- **Characteristic UUID:** `0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4` (Read + Notify)
- **Encoding:** Protocol Buffers proto3 (optional field presence, proto3 revision ≥ 3.15)
- **Sicherheit:** LE Secure Connections, Security Mode 1 Level 2, Mandatory Bonding (Just Works)
- **ATT_MTU:** ≥ 247 Bytes — App muss Exchange initiieren
- **DLE:** LL PDU Payload ≥ 251 Bytes — App muss initiieren (Workaround für eBike-Bug LDI-001)
- **Advertising:** App advertiert Service Solicitation UUID + Local Name + Appearance Category
- **Bekannte Bugs (v19 / LDI v1.0):**
  - LDI-001: eBike initiiert kein DLE → App muss es initiieren
  - LDI-002: Unveränderte Felder können in Notifications enthalten sein → ignorieren
  - LDI-003: eBike trennt bei unzureichendem MTU/DLE nicht → App muss Korrektheit sicherstellen

### Verfügbare Datenpunkte
| Feld | Typ | Einheit |
|---|---|---|
| speed | uint16 | Einheit aus Proto verifizieren (vermutlich mm/s oder cm/s) |
| cadence | uint16 | rpm |
| rider_power | uint16 | W |
| battery_soc | uint8 | % |
| odometer | uint32 | m |
| time | — | Systemzeit |
| light_status | bool | — |
| ambient_brightness | uint32 | lux |
| light_reserve | bool | — |
| system_lock | bool | — |
| bike_not_driving | bool | — |
| charger_connected | bool | — |
| diagnosis_connected | bool | — |

---

## 5. Datenfluss

```
eBike (GATT Server, GAP Central)
  │ BLE Connection + Bond
  │ GATT Notification (Protobuf-Bytes)
  ▼
BleManager
  │ ByteArray
  ▼
LiveDataDecoder          ← stateful merge (proto3 optional presence)
  │ BoschLiveData
  ▼
BoschLiveDataService (KarooExtension)
  ├─ DataTypeProvider → 13 × Karoo DataType (Ride-Felder)
  └─ EBikePage        → RemoteViews Dashboard-Seite
```

**Merge-Logik:** Da Protobuf-Notifications nur geänderte Felder enthalten (LDI-002), hält `LiveDataDecoder` intern den letzten vollständigen Zustand und merged eingehende Updates. Der Karoo-Service erhält immer den vollständigen `BoschLiveData`-Zustand.

---

## 6. BLE-Verbindungsmanagement

### Verbindungsaufbau (Unbonded)
1. Nutzer startet Pairing-Wizard
2. `BleManager.startAdvertising()` — Limited Discoverable Mode, Bondable Mode, Service Solicitation UUID
3. eBike erkennt Karoo und initiiert Verbindung
4. LE Secure Connections Pairing + Bonding (Just Works)
5. App initiiert ATT_MTU Exchange (247 Bytes)
6. App initiiert Data Length Update (251 Bytes)
7. GATT Discover → CCCD schreiben (Notifications aktivieren)
8. Daten fließen

### Verbindungsaufbau (Bonded)
- App startet Advertising mit Filter Accept List (nur gebundene eBikes)
- Non-Discoverable Mode, kein Bonding-Modus nötig

### Wiederverbindung
- Bei Link Loss → sofort erneut advertisen
- Timeout konfigurierbar (Standard: unbegrenzt im Hintergrund)

---

## 7. Bike-Verwaltung

```kotlin
data class BikeProfile(
    val id: String,                    // UUID (lokal generiert)
    val name: String,                  // Nutzer-vergeben, z.B. "Trek Allant+"
    val bleAddress: String,            // BLE MAC-Adresse des eBike
    val isActive: Boolean,             // Welches eBike aktuell verbunden werden soll
    val enabledFields: Set<String>     // IDs der aktiven Datenpunkte (default: alle 13)
)
```

**Screens:**
- **BikeListScreen:** Liste aller Profile, aktives hervorgehoben, „+ Neues eBike pairen"
- **BikeDetailScreen:** Name editieren, Felder aktivieren/deaktivieren, Bond entfernen + Profil löschen
- **PairingWizardScreen:** Schritt 1 Erklärung → Schritt 2 Advertising (60s Countdown) → Schritt 3 Erfolg/Fehler

---

## 8. Karoo-Integration

### DataTypes (extension_info.xml)
13 DataType-Einträge mit je eigenem `typeId`, `displayName`, `description`, `icon`.  
Namenskonvention: `bosch_ldi_speed`, `bosch_ldi_cadence`, `bosch_ldi_rider_power`, usw.

### EBikePage
- Registriert als Karoo Page in `extension_info.xml`
- RemoteViews-Layout: 2×2 Grid für die 4 wichtigsten Werte (SOC, Leistung, Kadenz, Geschwindigkeit) + Odometer
- Aktualisierung bei jedem `BoschLiveData`-Update

### Verbindungsstatus-Feld
Ein app-eigener (nicht Bosch-) DataType `bosch_ldi_connection` zeigt `CONNECTED` / `SEARCHING` / `DISCONNECTED` — damit der Nutzer während der Fahrt den BLE-Status im Blick hat. Zählt nicht zu den 13 Bosch-Datenpunkten.

### Aktives eBike-Profil
Nur ein BikeProfile kann `isActive = true` sein. Wechselt der Nutzer in BikeDetailScreen das aktive Profil, stoppt `BleManager` das laufende Advertising/Verbindung und startet neu mit der Filter Accept List des neuen Profils.

---

## 9. Sicherheit

### 9.1 BLE-Protokollsicherheit

**Just Works Pairing (kein MITM-Schutz)**
Die Bosch-Spec schreibt LE Secure Connections mit „Just Works" vor — Verschlüsselung ist vorhanden, aber kein Man-in-the-Middle-Schutz. Das ist eine Spec-Vorgabe, die wir nicht ändern können. Mitigierung: im README und im Wizard explizit dokumentieren; da die App read-only ist, kann ein Angreifer nur eBike-Telemetrie abgreifen, keine Steuerung vornehmen.

**BLE-Adress-Randomisierung (RPA)**
Statt einer fixen MAC-Adresse advertiert die App mit Resolvable Private Addresses (RPA, alle 15 min rotierend). Verhindert dauerhaftes Tracking des Karoo-Nutzers via BLE-Passiv-Scanning. Gebundene eBikes können die Adresse via Identity Resolving Key (IRK) weiterhin auflösen.

**Bond-Validierung bei Wiederverbindung**
Bei Link Loss oder Bond-Verlust: `BleManager.onBondLost()` entfernt den Bond explizit (`BluetoothDevice.removeBond()`), markiert das BikeProfile als un-bonded und fordert den Nutzer zur Neu-Kopplung auf. Kein stiller Reconnect nach Bond-Verlust.

**GATT-Timeouts**
Alle GATT-Operationen (readCharacteristic, requestMtu, writeCccd) haben einen 30-Sekunden-Timeout. Wenn innerhalb von 60 Sekunden keine Notification eintrifft, wird die Verbindung getrennt (Watchdog).

**MTU/DLE-Enforcement**
Nach ATT_MTU-Exchange: wenn negotiatedMtu < 247 → Verbindung trennen, UI-Fehlermeldung. Nach DLE-Request: wenn LL PDU < 251 → Verbindung trennen. Workaround für LDI-001 und LDI-003.

### 9.2 Android-Berechtigungen

**Android 12+ BLE Permissions**
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```
Runtime-Permission-Handling im Wizard: App prüft via `ContextCompat.checkSelfPermission()` und erklärt dem Nutzer den Grund vor dem System-Dialog.

**Komponenten nicht exportiert**
Alle Services, BroadcastReceiver und Activities außer `MainActivity` erhalten `android:exported="false"`. `BoschLiveDataService` als Foreground Service mit `foregroundServiceType="connectedDevice"`.

**PendingIntents immutable**
Alle PendingIntents werden mit `FLAG_IMMUTABLE` erstellt (Android 12+ Pflicht).

### 9.3 Eingabevalidierung

**Protobuf-Parsing**
```kotlin
val parser = CodedInputStream.newInstance(bytes)
parser.setSizeLimit(16 * 1024)  // Max 16 KB pro Nachricht
parser.setRecursionLimit(16)
val data = BoschLiveData.parseFrom(parser)
```
Rate-Limiting: Max 10 Notifications/Sekunde. Try/catch um alle parseFrom()-Aufrufe; bei Fehler → letzten bekannten Zustand behalten und Fehler loggen.

**Bike-Name-Validierung**
Max. 64 Zeichen, Whitelist: Buchstaben, Ziffern, Leerzeichen, Bindestrich, Unterstrich. Wird im ViewModel validiert, bevor es ins DataStore gelangt.

**MAC-Adress-Validierung**
Regex: `^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$`. Zusätzlich nach Verbindungsaufbau: `BluetoothDevice.address` gegen gespeicherte Adresse prüfen.

### 9.4 Datenspeicherung

**Verschlüsseltes DataStore**
`BikeProfile`-Daten (Name, BLE-Adresse) werden mit `EncryptedDataStore` gespeichert (AES-256-GCM, Schlüssel im Android Keystore). Standard-DataStore ist unverschlüsselt und bei rooted Devices auslesbar.

**Kein Backup sensibler Daten**
```xml
<!-- res/xml/backup_rules.xml -->
<full-backup-content>
    <exclude domain="sharedpref" path="bikes_data.xml" />
</full-backup-content>
```

**Kein Loggen sensibler Daten**
Keine MAC-Adressen, Bike-Namen oder Live-Daten in Logcat. Verbose-Logs ausschließlich in `BuildConfig.DEBUG`. In Release-Builds werden Log.d/v via ProGuard entfernt.

### 9.5 APK-Signing & Distribution

**Signing-Key-Management**
Keystore wird ausschließlich als verschlüsseltes Base64-Secret in GitHub Actions gespeichert (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Nie im Repository. `.gitignore` schließt `*.jks`, `*.p12`, `*.keystore` explizit aus.

**Checksums & SBOM**
Jedes GitHub Release enthält:
- `checksums.txt` mit SHA-256-Hashes aller APKs
- GPG-Signatur der Checksums (`checksums.txt.asc`)
- Software Bill of Materials (SBOM, CycloneDX JSON)

**SECURITY.md**
Datei im Repository-Root mit Vulnerability Disclosure Policy, Kontaktadresse (`security@dxmedia.de`), bekannten Schwachstellen und Incident-Response-Plan.

### 9.6 Laufzeitsicherheit

**BleManager — Thread-Sicherheit**
Alle BLE-Zustandsänderungen laufen über einen single-threaded Coroutine-Dispatcher mit `Mutex`. Ungültige State-Übergänge werden explizit rejected (State Machine).

**LiveDataDecoder — Atomare Merges**
Merge von Protobuf-Updates mit `ReentrantReadWriteLock`. `DataTypeProvider` erhält immer eine Kopie des Zustands (copy-on-read).

**Foreground-Service-Notification**
Generischer Inhalt (kein Bike-Name, keine Daten), `VISIBILITY_SECRET` (nicht auf Sperrbildschirm sichtbar), `PendingIntent.FLAG_IMMUTABLE`.

**ProGuard/R8 in Release-Builds**
`minifyEnabled true`, `shrinkResources true`. Sensible Klassen (Karoo SDK, Protobuf) in `proguard-rules.pro` via `-keep` ausgenommen.

---

## 10. Fehlerbehandlung

| Szenario | Verhalten |
|---|---|
| ATT_MTU < 247 nach Exchange | Verbindung trennen, UI-Hinweis |
| DLE < 251 nach Request | Verbindung trennen, UI-Hinweis |
| Protobuf-Decode-Fehler | Letzten bekannten Zustand behalten, Fehler loggen (kein Crash) |
| Protobuf > 16 KB | Verbindung trennen (Protokollfehler) |
| Bond-Verlust | Bond explizit entfernen, Profil als un-bonded markieren, Wizard anbieten |
| eBike nicht gefunden (60s Timeout im Wizard) | Fehlerscreen mit Retry-Option |
| GATT-Timeout (30s) | Verbindung trennen, Status-Feld auf DISCONNECTED |
| Advertising-Fehler (BLE nicht verfügbar) | Hinweis in UI, BLE-Einstellungen öffnen |
| BLE-Permissions fehlen | Erklärungsscreen vor System-Dialog |
| Ungültiger Bike-Name / MAC-Format | Inline-Validierungsfehler in UI, kein Speichern |

---

## 10. Mehrsprachigkeit (i18n)

Die App unterstützt **Deutsch** und **Englisch**. Alle UI-Strings sind in `res/values/strings.xml` (EN, Default) und `res/values-de/strings.xml` (DE) externalisiert — nie Strings direkt im Kotlin-Code.

**Sprachauswahl:** In den App-Einstellungen kann der Nutzer explizit DE oder EN wählen (unabhängig von der Systemsprache). Gespeichert im DataStore, angewendet via `AppCompatDelegate.setApplicationLocales()` (API 33+) bzw. `Configuration`-Override für ältere APIs.

**Fallback-Reihenfolge:** Nutzerauswahl → Systemsprache (wenn DE oder EN) → EN.

**DataType-Namen auf Karoo:** `displayName` und `description` in `extension_info.xml` sind einsprachig (EN) — das Karoo SDK unterstützt derzeit keine lokalisierten DataType-Namen.

**README:** Zweisprachig (DE oben, EN darunter, durch Trennlinie getrennt).

---

## 11. Dokumentation

- **README.md:** Zweisprachig (DE/EN), Feature-Übersicht, Screenshots, Installationsanleitung (Side-Load), Felder-Tabelle, Contributing-Guide, Lizenz
- **SECURITY.md:** Vulnerability Disclosure Policy, Kontakt `security@dxmedia.de`, bekannte Einschränkungen (Just Works MITM), Incident-Response
- **CHANGELOG.md:** Keep-a-Changelog-Format, semantische Versionierung
- **KDoc:** Alle `public` Klassen, Interfaces und Methoden
- **`extension_info.xml`:** Inline-Kommentare für alle DataType-Einträge
- **GitHub Releases:** Signierte APK, `checksums.txt` + `checksums.txt.asc` (GPG), SBOM (CycloneDX JSON), Release Notes aus CHANGELOG

---

## 12. Projekt-Briefings (Implementierungsreihenfolge)

| # | Briefing | Inhalt | Abhängigkeit |
|---|---|---|---|
| 1 | Projektgerüst | Template, Gradle, Protobuf-Plugin, Permissions, leerer Service, CI | — |
| 2 | BLE-Stack | BleManager, RPA-Advertising, GATT Client, Bonding, Timeouts, Reconnect | 1 |
| 3 | Datenschicht | LiveDataDecoder, BikeProfile, EncryptedDataStore, BikeRepository | 1 |
| 4 | Karoo-Integration | DataTypes, DataTypeProvider, EBikePage, extension_info.xml | 2, 3 |
| 5 | UI & i18n | Wizard, Bike-Verwaltung, Navigation, DE/EN Strings, Sprachauswahl | 3, 4 |
| 6 | Dokumentation & Release | README, SECURITY.md, KDoc, ProGuard, CI APK-Signing, SBOM | 5 |

Briefing 6 kann ab Briefing 4 parallel begonnen werden.
