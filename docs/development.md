# Development

## Current implementation

Version 0.13.0-dev (code 15) adds a separately developed Atlas API and optional offline completed-trip synchronization. The user broadly confirmed 0.12.1-dev works perfectly. Sensor search remains available alongside automatic connection/read/recording, a broad standard scalar OBD catalog and the real RPM dial. The four tabs are Adapter, Live, Trips and Vehicles. History pages and independent vehicle filtering from 0.11.0-dev remain available. Phone validation is deferred at the user's request; record actual software outcomes in each PR.

| Module | Responsibility |
| --- | --- |
| `:app` | Compose screens, foreground Bluetooth/OBD, automatic journeys, Room records, offline charts/exports and optional completed-trip uploads |
| `api/` | Independent TypeScript API, MongoDB storage, bounded reads, deployment packaging and isolated integration tests |
| `:core:telemetry` | Pure standard OBD/VIN parsing, verified metric definitions, VIN validation and deterministic test telemetry |

Synthetic fixtures have no production navigation destination and never feed the recorder. No GPS is collected. The API is implemented separately; server deployment is deferred.

## Toolchain

Use the checked-in Gradle wrapper rather than a separately installed Gradle version.

| Tool or setting | Version |
| --- | --- |
| JDK for Gradle | 17 or 21 |
| Gradle wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| Android compile SDK | 36 |
| Android target SDK | 36 |
| Android minimum SDK | 26 |
| Android SDK Build-Tools | 35.0.0, the Android Gradle Plugin default |
| Compose BOM | 2025.12.01 |
| Room runtime/compiler/plugin | 2.8.5 |
| WorkManager | 2.11.2 |
| Kotlin Symbol Processing (KSP) | 2.3.10 |

A first build needs Internet access to download dependencies.
The app requests Internet access for optional synchronization. Acquisition, profiles, saved-trip history and local document export work without network access or API configuration.

## Continuous integration environment

All four repository-check jobs use the explicit `ubuntu-24.04` x64 GitHub-hosted runner. This avoids automatic OS migrations through `ubuntu-latest`; runner software and installed image packages still receive updates. Linux runners support Android hardware acceleration. The emulator job retains the KVM permission rule and API 36 Google APIs x86_64 image, with the documented `swiftshader` software renderer instead of deprecated `swiftshader_indirect`. Its explicit `-no-metrics` option disables emulator metrics collection and avoids a future interactive consent prompt in unattended CI.

The workflow pins actions to full commit SHAs from these stable releases, verified on October 11, 2026:

| Action | Release | Embedded runtime |
| --- | --- | --- |
| `actions/checkout` | [v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) | Node 24 |
| `actions/setup-node` | [v7.1.0](https://github.com/actions/setup-node/releases/tag/v7.1.0) | Node 24 |
| `actions/setup-java` | [v6.0.1](https://github.com/actions/setup-java/releases/tag/v6.0.1) | Node 24 |
| `gradle/actions/setup-gradle` | [v6.4.0](https://github.com/gradle/actions/releases/tag/v6.4.0) | Node 24 |
| `android-actions/setup-android` | [v4.0.4](https://github.com/android-actions/setup-android/releases/tag/v4.0.4) | Node 24 |
| `reactivecircus/android-emulator-runner` | [v2.38.0](https://github.com/ReactiveCircus/android-emulator-runner/releases/tag/v2.38.0) | Node 24 |
| `actions/upload-artifact` | [v7.0.2](https://github.com/actions/upload-artifact/releases/tag/v7.0.2) | Node 24 |

Node 24 actions require Actions Runner 2.327.1 or newer, provided by GitHub-hosted runners. Their embedded runtime is independent of the installed project tools: documentation still uses Node 22 with `npm ci`, and Android uses Temurin 17 and the Gradle 8.13 wrapper. Command-line tools remain pinned to `13114758` (19.0) in an isolated SDK directory, with SDK 36 and Build-Tools 35.0.0. The emulator action additionally installs Build-Tools 37; that does not change the app's AGP-selected Build-Tools version.

Gradle explicitly uses `cache-provider: basic` to retain Actions cache behavior instead of opting into the newer enhanced cache. Artifact uploads explicitly retain ZIP archives, existing names, and 14-day APK / seven-day report retention. Build and UI reports are collected even on failure. All eleven screenshots remain required, including the history browsing fixture; neither passing screenshot capture nor report upload can hide a failing test run. Workflow permissions remain `contents: read`, and checkout does not persist credentials.

The independent API job uses Node 22 and `api/package-lock.json`, runs type/catalog checks, builds, unit/HTTP tests and real MongoDB integration tests on a disposable local replica set, then builds the production container. It receives no Atlas credentials. The API's exact dependencies and commands are documented in [`api/`](../api/README.md).

When updating this baseline, run documentation checks, all JVM tests, lint, both debug APK assemblies, and the complete Android 16 suite. Inspect every screenshot and the final run's annotations. Record remaining upstream notices in the PR: an older Gradle wrapper notice or package advisory is separate from action-runtime deprecation. Do not describe a run as warning-free without checking it. A CI-only update does not require a new application version or phone test.

References: [GitHub-hosted runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners), [runner image update policy](https://github.com/actions/runner-images#image-definitions), [Android emulator acceleration](https://developer.android.com/studio/run/emulator-acceleration), and [Gradle cache configuration](https://github.com/gradle/actions/blob/3f5f9adaf7d9fecd50b5935e54106014257a94e6/docs/setup-gradle.md).

## Android Studio and SDK setup

1. Install Android Studio and open the repository root.
2. In SDK Manager, install Android SDK Platform 36, Android SDK Build-Tools 35.0.0, and Android SDK Platform-Tools.
3. Accept the SDK licenses.
4. In the Gradle settings, select a JDK 17 or 21 installation for Gradle.
5. Allow Android Studio to create the local SDK configuration and synchronize the project.

For command-line builds, configure `JAVA_HOME` to use the selected JDK and make the SDK discoverable through `ANDROID_HOME` or the `sdk.dir` entry in `local.properties`.
Use the paths on your own machine. Do not commit `local.properties`, SDK installations, or local machine paths.

An Android emulator running API 26 or later can be used for simulator checks.
Testing an emulator does not satisfy the Samsung A53 installation criterion or the real-vehicle compatibility checks.

## Build, unit tests, and Android lint

Run commands from the repository root.

Windows PowerShell:

```powershell
.\gradlew.bat :core:telemetry:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS or Linux:

```sh
./gradlew :core:telemetry:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

These tasks run the pure Kotlin tests, Android local unit tests, Android lint, and debug APK assembly.
They do not install the app on a device or verify Bluetooth/OBD compatibility.
Record actual outcomes and any blocked checks in the PR; this guide does not assert that a particular run passed.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
It is a development artifact, not a published release.

### Delivering a test APK on Windows

After validating a build, run `./scripts/export-test-apk.ps1 -Destination <directory>` in PowerShell.
The script copies it as `RX8-Telemetry-<version>.apk` and verifies the copied file's SHA-256 hash.
It does not build or test the app; run the checks above first. Re-exporting the same version replaces that version's delivered APK.

To remember the user's requested destination, create the ignored `apk-delivery.local.json` file at the repository root with a `directory` string.
Then run `./scripts/export-test-apk.ps1` after each build that is ready for user testing.
Keep personal machine paths out of committed files.

## Install a development build

Select the `app` run configuration in Android Studio and choose an emulator or an authorized device.

Alternatively, enable USB debugging on the device, approve the computer when Android requests it, and confirm the intended device is connected.
With Android SDK Platform-Tools available, install the assembled APK:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If more than one device is connected, select the intended device with the `adb -s` option.
The application package is `com.gonibix.rx8`.

On October 10, 2026, the user reported that the app worked after testing the simulator foundation.
The user confirmed that the Samsung A53 runs Android 16, completing RX8-002. The paired-only Bluetooth build failed to list the adapter.
Car Scanner reportedly finds it through in-app search with plain Bluetooth selected, showing OBDII and its MAC address.
The user confirmed Bluetooth and reported live readings worked on October 11, 2026; PR #14 is merged. The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed correct readings in 0.5.0-dev. Individual sensor support, ECU identifiers, measured rates, and sustained lifecycle checks remain unverified; the report does not validate profiles or VIN retrieval.

The user broadly confirmed 0.6.0-dev and later 0.7.0-dev worked on October 11, 2026; PRs #18 and #19 are merged. These reports do not establish each detailed favorite, sensor, recording-duration, provider, or interruption scenario. Record the new 0.8.0-dev upgrade/queue results separately; current unit/CI counts are not asserted here.

## Finding and connecting the Bluetooth adapter

1. Power the adapter and enable Bluetooth. Disconnect other diagnostic apps from it.
2. For initial setup, open Adapter, choose Scan nearby, grant Nearby devices access, select OBDII by its displayed address and connect. Android handles any pairing prompt. API 26–30 additionally needs foreground location permission for discovery; GPS is not collected.
3. The last successful adapter is saved privately. On a new app process, the visible app attempts it once when permission and Bluetooth are available. No permission prompt or connection starts from a background receiver. Manual discovery/selection remains available.
4. After connection, read-only ELM initialization, protocol detection and capability discovery begin automatically. Wait for actual ECU readings; socket success alone does not prove compatibility.
5. On Android 13+, allow notification access if desired for Stop/open-Adapter controls. Denial does not block connecting. Disconnect or notification Stop ends the session; failures and explicit stops do not cause reconnect loops.

Discovery is bounded to 15 seconds and stops before connection or when Adapter is hidden. Secure Classic/SPP attempts allow 15 seconds for paired devices or 45 seconds for pairing. There is no BLE transport. Bluetooth permissions follow [Android guidance](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

### Reading and viewing real data

The engine polls every implemented scalar parameter advertised by the responding ECUs, regardless of favorites. See the catalog in [Architecture](architecture.md#current-read-only-obd-engine). RPM/speed retain priority, and bounded serialized exchanges prevent a backlog. A supported PID is not proof of a valid response; unknown/proprietary fields remain explicitly unimplemented. Real rates require measurement with the car and adapter.

Live presents separate ECU groups, the real RPM dial, speed/coolant and selectable extra favorites. Values retain their unit, quality and age; valid zero and negative values are not missing data. Freshness depends on polling class. Disconnection marks retained values historical. No session average or unmeasured value is inferred from a latest snapshot.

Open Live > Choose sensors and enter a name or hexadecimal PID in Search sensors. Queries are case-insensitive and accept multiple words, 0F or PID 0x0F. Clear restores the eligible list; selected favorites stay selected while filtered out. The query survives closing/reopening the picker and saved Activity state restoration. Use the keyboard Search action or Done to dismiss the keyboard.

Favorites affect display only and persist as stable metric IDs, independently of vehicles/ECUs. Unavailable favorites remain removable. The four menu labels stay on one line; larger text can scroll horizontally. Test fixtures exercise synthetic scenarios without exposing a Simulator tab.

### Keeping a session active

The visible app promotes the connected-device foreground service before a connection attempt. Automatic reading continues that authorized connection even if the app backgrounds before the socket opens. Tabs, screen lock and Activity recreation keep the same service-owned session. Adapter and Live stay awake only while real acquisition is visible; a bounded renewed partial wake lock supports background acquisition.

Stop/disconnect releases resources after the trip's final writes. `START_NOT_STICKY` prevents background process-death restoration. A later new app process can attempt a fresh connection and create a separate journey; it does not append across the missing interval. Explicit stops and failed attempts are not immediately retried. No battery-optimization exemption is requested. Actual A53 battery use and continuity remain hardware checks.

## Saved vehicles and optional VINs

1. Open Vehicles and choose Create vehicle. Enter a name with 1-60 characters. A VIN is optional; leaving it empty still creates a permanent UUID.
2. Select a saved profile explicitly. Rename changes its alias; Add VIN is available only for a profile without one. A manual VIN requires 17 letters/numbers excluding I, O, and Q.
3. Restart the app and verify the profile and selected ID remain. The database is local; no Internet or account is required.
4. To try OBD VIN retrieval, return to Adapter. If there is no active session, connect and wait for automatic live acquisition. Once acquisition is live, choose Read VIN. It runs between sensor requests, which resume afterward.
5. Review the masked candidates and ECU sources. Choosing a candidate opens Vehicles while the Bluetooth/acquisition session continues. This does not save the candidate automatically.
6. Explicitly attach it to the selected VIN-less profile, select the profile already holding that VIN, or create a separate vehicle. A different VIN cannot replace the selected profile's VIN or ID.
7. Use Show VIN only when needed and Hide VIN afterward. Screenshots and issues must contain fake identifiers or redacted values.

VIN support on the actual car remains unverified. NO DATA, unsupported, or malformed complete replies allow sensor polling to continue; a timeout or missing prompt closes the link and requires manual reconnection.
VIN validation checks format, not ownership, check digit, or vehicle specifications. A Bluetooth MAC is never a vehicle ID.
Profiles, their active selection, dashboard favorites, and recorded trips/samples are saved. Finished-trip export, deletion and configurable completed-trip uploads are available in Trips. Profile deletion and app import/restore remain unimplemented.
Backups/device transfer are disabled. Clearing app data or reinstalling removes the saved UUIDs, with no current recovery workflow.

Room schema version 3 adds API metadata revisions, recording app version, and pending work through an explicit 2-to-3 migration. Version 4 adds only two composite history indexes through a 3-to-4 migration. The complete 1-to-2-to-3-to-4 chain is retained. Existing profiles, selection, trips, samples, metadata revisions, and queue state survive the index upgrade. Legacy samples become pending during the earlier 2-to-3 migration, and old recording app versions remain unknown. Exported schemas remain under `app/schemas`. Future database changes need explicit migrations; destructive migration fallback is prohibited.
Instrumented checks include database close/reopen and Compose identity flows. With an emulator or authorized test device, use `./gradlew :app:connectedDebugAndroidTest` (or `./gradlew.bat` on Windows). The workflow also captures labeled, synthetic UI fixtures; these are not evidence from the RX-8.

## Recording, reviewing, and exporting a trip

Use the current update with existing app data intact; do not clear the user's database as a routine test. The Room 1-to-2-to-3-to-4 migration chain must retain saved vehicle UUIDs, selected profile, existing trip data, and pending work.

1. In Vehicles, create or explicitly select the intended profile. A VIN is optional. In Adapter, connect; initialization and real reading start automatically.
2. Open Trips and confirm automatic recording uses the selected vehicle and saved samples advance. The journey starts at connection time; initialization placeholders are not samples. With no selected profile, follow the visible guidance and select one; recording begins then without backfilling earlier values.
3. Change tabs, background the app, and lock the A53 for a measured interval. The same recording should continue through the service. Changing the selected profile must not change the active trip's frozen UUID/name. Record actual duration, saved count, and failures without publishing identifiers.
4. Choose Stop recording in Trips. Wait until the terminal write is confirmed and the trip appears in history. Live acquisition remains running, and recording must not restart on its own in that connection. The manual start control can begin a new journey. Separately test notification Stop: it closes live acquisition and completes the active trip after queued writes are saved.
5. Open a finished trip without Internet access. Review sample/valid counts, duration, peak recorded RPM, peak speed, and identified speed ECU. Select another recorded sensor/ECU and review its chart, canonical unit, full-stream min/max, valid count, and coverage. Valid zero and negative temperatures remain readings; missing/failed data and gaps must not become zero measurements or connecting chart lines. The trip overview stays unchanged when selecting a chart. Rapid switches, returning to a previous trip, and rotation must retain the intended selection without older results replacing it.
6. Treat estimated distance as covered valid speed intervals only, never as the odometer. Speed analysis uses one deterministic ECU and integrates only adjacent valid intervals of at most five seconds. Check the displayed covered/uncovered duration; RPM peak can come from any valid RPM source.
7. Choose Export JSON or Export CSV, then select the destination in Android's document picker. The screen retains the intended trip/format across Activity recreation. Canceling the picker creates no success message. Success must appear only after the output is written, flushed, and closed; provider/write failures remain visible and may leave an incomplete document.
8. Inspect an anonymized export: JSON uses schemaVersion 1 with trip metadata and samples; CSV uses schema_version 1, one sample per row and a metadata row for an empty trip. IDs, sequence, timestamps, units, ECU sources, quality, and null/error values remain available. Spreadsheet-sensitive text in CSV is escaped for safe display. No VIN, Bluetooth MAC, GPS, or upload is included.
9. Choose Delete on a finished trip, cancel once, then confirm on a disposable test fixture. Confirm only that trip and its samples disappear. Existing exported files remain; active recording cannot be deleted or exported. The app-database byte count covers shared storage, and deletion can make space reusable without immediately shrinking the file.
10. In a controlled hardware check, interrupt the connection or acquisition, and separately end the app process. Committed samples must remain; the trip becomes interrupted, and a new connection creates a separate journey. A new app process may attempt the saved adapter once; an explicit stop in the current process must not be immediately undone. Do not infer physical A53 process/lock behavior from synthetic tests.

History shows one page of up to 50 sessions, ordered newest first by start time and trip ID. Choose Older or Newer to browse, or Newest to return to the latest matching trips. Refresh reloads the current page and refreshes storage/queue information independently. The vehicle filter defaults to All vehicles and uses stable IDs; it does not change the selected recording profile. Filter choices use current names and distinguish identities, while existing trips keep their recorded names. A filter with no matches is different from a load failure; Retry repeats a failed history request. Same-filter failures retain the confirmed page, and obsolete requests cannot replace a newer filter or page.

The Activity-owned ViewModel retains browsing context across details, tabs, the document picker, and recreation. A process restart returns to the newest page for all vehicles. New trips become visible on the newest matching page; an older page does not jump merely because recording changes. Confirmed deletion refills the current page, or returns to newest if the page has become empty. Detailed analysis/export requires a completed or interrupted trip, so sample reads operate on a finished dataset. Recording uses ordered bounded sample buffering; the displayed saved count advances only after database commits. Insufficient space or failed writes stops recording and reports that some samples may not have been saved. A failed history query does not replace stored data with an empty database.
Exports reveal the vehicle UUID and recorded alias, times, and readings even though they exclude VIN/MAC values. Choose destinations and recipients deliberately. Android backups/device transfer remain disabled; trip export is not an app backup, and import/restore is not implemented. No GPS or simulator recording is added. API uploads preserve local history and the export format.
The user broadly confirmed 0.7.0-dev worked, and PR #19 is merged. Detailed measured A53 recording/export/storage scenarios remain open. Unit and Android 16 CI results belong in the PR; this guide makes no new pass-count claim.

## Checking offline uploads and the API

See [`api/README.md`](../api/README.md) for backend-only setup, independent checks and later server deployment. The API has its own lockfile; root `npm ci` installs documentation tools only. Never copy Atlas credentials into Android or committed configuration.

1. Upgrade without clearing app data and confirm saved profiles, UUIDs and trips remain.
2. With no server URL configured, record and stop a trip offline. Review charts and export it; pending work must remain local and no upload is attempted.
3. After later HTTPS deployment, enter the server base URL in Trips. Finished historical trips become eligible. An active trip must continue recording locally without upload until its terminal commit.
4. Disconnect network access before ending a journey. Confirm its local history immediately, then restore connectivity and check eventual server persistence without duplicate IDs after retries.
5. Pause and resume uploads. The saved server identity must remain; changes to another server after an attempt must be rejected. Check empty completed trips and startup-recovered interrupted trips too.
6. Invalid ACKs, conflicts and storage failures must remain visible; exports include pending/blocked samples. Deleting a local trip does not imply remote deletion.

Full JVM/Room/Android tests exercise isolated storage, strict ACK parsing, terminal gating, immutable retries and settings. Backend integration tests use a disposable replica set, never Atlas credentials. Record actual results in the PR. A database connectivity ping is separate from deploying an API or verifying uploads on the A53.

## Capturing capabilities without extra requests

After real capability discovery, open Vehicle capabilities in Adapter. Review the capture time, protocol, elapsed acquisition time, successful ECU-response rate, advertised PIDs per ECU, and latest display states. Use Capture latest to refresh deliberately. Advertised PIDs outside the implemented scalar catalog must remain clearly undecoded, without fabricated readings or support claims.

Choose Save JSON report and a destination. While the picker is open, switch tabs or recreate the Activity and verify that the original captured report is written. Cancel once and test a provider failure on a disposable destination; neither may show success. After stopping acquisition, capture retained data and check historical/stale labeling. A process restart loses the in-memory report and must not fabricate a replacement.

The report excludes VINs, MACs, profile/trip IDs, aliases, raw logs, adapter identity, and free-form status text. It still contains capture time and latest values. Inspect only synthetic fixtures in source control. See [the report contract](capability-report.md) and [the phone checklist](hardware-validation.md#capability-report-checklist-rx8-010). No new OBD command, persistent report history, Room migration, API enablement, or screen-awake owner is added.

## Internal synthetic fixtures

Synthetic telemetry remains available to isolated JVM/Compose fixtures. It covers valid, zero, missing, timeout and stale states, clearly labelled in screenshot evidence. It is not a production tab, background session or saved trip. The real dashboard must never substitute fixture values when the car is unavailable.

## Documentation checks

Use Node.js 22 or later and npm:

```sh
npm ci
npm run lint:docs
```

These checks validate documentation formatting separately from Android checks.

## Validation still required

The user has confirmed the simulator, Bluetooth connection, and general live readings on the A53. The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and correct readings in 0.5.0-dev. Individual sensor results, ECU identifiers, measured rates, and sustained lifecycle behavior still need detailed evidence. Profile persistence and VIN retrieval still need device checks.

- [RX8-001](https://github.com/gonibix23/rx8-telemetry-android/issues/1): test the real adapter and RX-8, including ECU communication, supported PIDs, VIN availability, stability, and reconnection.
- [RX8-003](https://github.com/gonibix23/rx8-telemetry-android/issues/3): validate scan and connection permissions, nearby discovery, pairing, connection, cancellation, disconnection, and manual reconnection with the actual adapter.
- [RX8-004](https://github.com/gonibix23/rx8-telemetry-android/issues/4): validate ELM initialization, detected protocol, capabilities, actual values, observed rate, and failure recovery.
- [RX8-005](https://github.com/gonibix23/rx8-telemetry-android/issues/5): validate profile persistence, selected UUID, optional VIN retrieval, provenance, and explicit conflict decisions.
- [Issue #16](https://github.com/gonibix23/rx8-telemetry-android/issues/16) is closed at the user's request with all issue checks complete. The broader hardware checklist still has no measured duration or battery log; this does not reopen the completed fix.
- [RX8-006](https://github.com/gonibix23/rx8-telemetry-android/issues/6): validate the dashboard layout, favorite persistence/unavailability, ECU separation, simulator preview, and navigation on the A53.
- [RX8-007](https://github.com/gonibix23/rx8-telemetry-android/issues/7): validate explicit recording, screen lock/background, committed-data recovery, gaps, summaries, document exports, deletion, and storage errors on the A53.
- [RX8-008](https://github.com/gonibix23/rx8-telemetry-android/issues/8): verify the 0.8.0-dev upgrade, preserved history, local queue counts, exports/deletion, and uploads-disabled behavior on the A53. RX8-017 adds real integration; actual deployment/phone uploads remain unverified.
- [RX8-010](https://github.com/gonibix23/rx8-telemetry-android/issues/21): validate capability preview, frozen JSON export, retained stopped state, and continued live/trip operation on the A53.
- [RX8-012](https://github.com/gonibix23/rx8-telemetry-android/issues/24): the user broadly confirmed 0.10.0-dev works well. Detailed recorded metric/ECU, selection retention, gaps, and overview/export scenarios remain unreported while verified software development continues.

Keep issues open while required evidence is missing. PR #14 is merged, but RX8-004 remains open for detailed hardware checks. RX8-005 software does not close RX8-001 or prove VIN compatibility.
There are no published APK releases.
