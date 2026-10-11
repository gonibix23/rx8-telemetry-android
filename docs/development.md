# Development

## Current implementation

The 0.6.0-dev iteration (version code 7) adds a live dashboard and saved favorites to the service-owned Bluetooth/OBD session, optional VIN, saved-profile, and simulator foundation:

| Module | Responsibility |
| --- | --- |
| `:app` | Compose dashboard/simulator, SharedPreferences favorites, service-owned Bluetooth/OBD sessions, notification/power lifecycle, and Room profiles |
| `:core:telemetry` | Pure Kotlin Mode 01/VIN parsing, VIN validation, telemetry types, and deterministic simulation |

The simulator is identified in the interface and supplies synthetic engine speed, vehicle speed, coolant temperature, intake air temperature, throttle position, and engine load readings.
The simulator stays separate from real acquisition and saved profiles. Vehicles stores explicit profiles and active selection; no screen saves trips or uploads data.
Bluetooth connection alone opens a socket. Read live data explicitly starts ELM initialization and read-only Mode 01 queries.
The architecture and API documents describe later implementation work unless explicitly marked as implemented.

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
| Kotlin Symbol Processing (KSP) | 2.3.10 |

A first build needs Internet access to download dependencies.
The app does not request Internet access; acquisition and local vehicle profiles work without it.

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

## Finding and connecting the Bluetooth adapter

1. Power the adapter, enable phone Bluetooth, and disconnect other apps such as Car Scanner from the adapter.
2. Open the Adapter tab and choose Scan nearby. On Android 12/API 31 or later, allow Nearby devices access.
3. On Android 8-11/API 26-30 only, grant the location permission required for discovery and enable location services. No location is collected.
4. Select OBDII, comparing its MAC address with the device shown by Car Scanner. Unnamed nearby devices also show an address. Already-paired devices remain available.
5. Connect and complete Android's pairing prompt if it appears. A successful result means a Bluetooth socket is open; ECU communication remains unchecked until Read live data succeeds.
6. The connection continues across tabs, backgrounding, screen lock, and Activity recreation. Use Disconnect in Adapter or Stop in the ongoing notification to close it. Reconnect manually after an explicit stop or terminal error.

This is a provisional secure RFCOMM/SPP connection. Discovery is bounded and stops before connection or when leaving the Adapter tab.
The scan lasts up to 15 seconds, with up to two seconds beforehand to stop an existing inquiry. Connection attempts allow 15 seconds for a paired device or 45 seconds for an unpaired device so Android can request pairing.
Names do not determine compatibility; the app does not filter results to OBDII. If no adapter appears, confirm power, disconnect other diagnostic apps, and scan again.
BLE-only devices are outside the current transport scope, consistent with the user's plain Bluetooth setting in Car Scanner.
On API 31+, scan and connection permissions are separate; a paired adapter can be connected without scan access. Location permission is limited to API 26-30.
Android's [permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) and [device discovery guidance](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices) describe these requirements.

The connection action sends no application bytes. Read live data uses the transport's bounded byte read/write operations for OBD commands.
There is no automatic reconnection or saved trip recording. An explicitly started live session can continue in the background. The user-reported successful Bluetooth connection does not establish sensor support.
Use the [hardware checklist](hardware-validation.md#bluetooth-transport-checklist-rx8-003) to record actual behavior.

## Reading live OBD data

1. With the vehicle stationary and ignition on, connect the adapter and select Read live data.
2. Wait for adapter initialization, automatic protocol detection, and supported-parameter discovery. Initialization failures remain separate from a successful Bluetooth connection.
3. Inspect the protocol, responding ECU identifiers, and six parameter cards. Each ECU is shown separately when more than one responds.
4. Confirm engine speed, stationary vehicle speed, and coolant temperature against the actual operating conditions. A valid zero value is allowed; a missing reading must show its status instead.
5. Observe the timestamps, stale markers, and measured response rate. A parameter appearing in the capability bitmap does not guarantee a valid reading on every request.
6. Use Disconnect or notification Stop to stop polling. Leaving Adapter/the app keeps acquisition running. After a stop or terminal error, reconnect and choose Read live data again for a fresh session.

The current decoders cover engine speed (rpm), vehicle speed (km/h), coolant and intake temperatures (°C), throttle position (%), and engine load (%).
The engine only requests metrics reported as supported. Mode 01 capability bitmaps can report additional parameters that this build does not yet decode.
Missing replies, unsupported parameters, adapter errors, malformed frames, and stale cached values have distinct states. Failed readings are never represented by a fabricated zero.
Values older than five seconds are marked stale. Disconnecting marks retained valid readings stale immediately; selecting another adapter clears the previous readings.

The engine serializes all exchanges, assembles Bluetooth fragments through the ELM prompt, and limits command time and reply size.
Losing prompt framing closes the connection and requires a manual reconnect, preventing delayed responses from being mistaken for new readings.
RPM and speed receive priority; measured latency and errors slow requests instead of allowing a backlog. Actual rates still require a real-car test.

This build sends read-only Mode 01 diagnostics and, only when requested, Mode 09 PID 02 for VIN. It does not clear faults, control actuators, or save a trip.
Mode 01 replies fit a single CAN frame. VIN support is limited to its 20-byte CAN message or five numbered legacy packets; general ISO-TP support is not implemented.
Read [Architecture](architecture.md#current-read-only-obd-engine) for the separation between transport, parser, session, and UI.

## Using the dashboard and favorites

1. Connect and start Read live data in Adapter, then open Dashboard. Navigation reuses the same service-owned session; opening Dashboard alone does not connect or start acquisition.
2. Inspect RPM, speed, and coolant where the responding ECU reports support. Each ECU stays separate, and every value shows its unit, quality, and age.
3. Open Choose sensors to add intake temperature, throttle position, or engine load when available. Primary metrics stay fixed; the extra favorites are optional and initially empty.
4. Change the favorites, restart the app, and verify they remain selected. They are app-wide preferences, independent of vehicle and ECU selection. A saved favorite unavailable in a later session stays listed as unavailable and can be removed.
5. Distinguish ECU-reported values from adapter/session metadata. A valid zero must remain visible; missing/failed results cannot become zero. After a stop or terminal error, retained values are stale and the frozen age is identified as Age at stop.
6. In Simulator, open the dashboard preview to use the same cards with clearly labeled synthetic data. Normal, timeout, and missing-data scenarios remain available. This preview does not replace or stop a real background session.

Favorites change presentation only; they do not change supported sensors or polling priority. The private SharedPreferences store contains stable metric IDs, not vehicle identifiers or readings. Saving failures must remain visible instead of claiming the choice was saved.
Dashboard layout and favorite persistence still need A53 validation. No history, trip controls, sensor estimates, or upload feature is added.

## Keeping a session active

Connect from the visible app starts the connected-device foreground service. On Android 13+, the first Connect action requests notification permission; allow it to see the session notification and its Stop/open-Adapter controls. Denial does not block connection. Follow the in-app guidance to enable notifications, or stop from Adapter.
Read live data is still a separate explicit action. During initialization, protocol/capability discovery, and live acquisition, the visible Adapter or real Dashboard screen stays awake. Switching to Vehicles or Simulator, using another app, or locking the screen releases that screen-on flag but leaves acquisition running.
A bounded, renewed partial wake lock supports acquisition while the screen is off. Active acquisition consumes battery; use Disconnect or notification Stop when finished. The app does not request a battery-optimization exemption.
Discovery stops when Adapter is hidden. The simulator remains foreground-only and does not share the real session's background behavior.

Stop/disconnect and terminal failures release session resources even while the UI stays bound. The service does not restart automatically (`START_NOT_STICKY`), reconnect, or restore a session after process death. Profiles, selected vehicle, and dashboard favorites persist; live readings do not become trip history.
The user's 0.5.0-dev screen-sleep/background-disconnection report matched the old lifecycle policy. The reported protocol remains `ISO 15765-CAN (11 bit, 500 kbaud)`; actual removal of the Android Bluetooth bond is not confirmed and the code has no `removeBond` call.
The user subsequently confirmed 0.5.1-dev worked and PR #17 is merged, providing broad success evidence for the fix. The [issue #16 hardware checklist](hardware-validation.md#background-session-and-screen-awake-checklist-issue-16) retains detailed scenarios: measured durations, battery use, notification behavior, and bond-entry checks were not supplied. Automated tests alone do not establish these hardware results.

## Saved vehicles and optional VINs

1. Open Vehicles and choose Create vehicle. Enter a name with 1-60 characters. A VIN is optional; leaving it empty still creates a permanent UUID.
2. Select a saved profile explicitly. Rename changes its alias; Add VIN is available only for a profile without one. A manual VIN requires 17 letters/numbers excluding I, O, and Q.
3. Restart the app and verify the profile and selected ID remain. The database is local; no Internet or account is required.
4. To try OBD VIN retrieval, return to Adapter. If there is no active session, connect and choose Read live data. Once acquisition is live, choose Read VIN. It runs between sensor requests, which resume afterward.
5. Review the masked candidates and ECU sources. Choosing a candidate opens Vehicles while the Bluetooth/acquisition session continues. This does not save the candidate automatically.
6. Explicitly attach it to the selected VIN-less profile, select the profile already holding that VIN, or create a separate vehicle. A different VIN cannot replace the selected profile's VIN or ID.
7. Use Show VIN only when needed and Hide VIN afterward. Screenshots and issues must contain fake identifiers or redacted values.

VIN support on the actual car remains unverified. NO DATA, unsupported, or malformed complete replies allow sensor polling to continue; a timeout or missing prompt closes the link and requires manual reconnection.
VIN validation checks format, not ownership, check digit, or vehicle specifications. A Bluetooth MAC is never a vehicle ID.
Only profiles, their active selection, and dashboard favorites are saved. No telemetry history, trip recording, profile deletion, upload, export, or restore feature is implemented.
Backups/device transfer are disabled. Clearing app data or reinstalling removes the saved UUIDs, with no current recovery workflow.

Room version 1 exports its schema under `app/schemas`. Future database changes need explicit migrations; destructive migration fallback is prohibited.
Instrumented checks include database close/reopen and Compose identity flows. With an emulator or authorized test device, use `./gradlew :app:connectedDebugAndroidTest` (or `./gradlew.bat` on Windows). The workflow also captures labeled, synthetic UI fixtures; these are not evidence from the RX-8.

## Using the simulator

- Connect to start generating synthetic sensor readings; disconnect to stop.
- Select normal mode for valid deterministic readings.
- Select timeout mode to make all readings report a timeout.
- Select missing-data mode to make coolant and intake air temperatures unavailable while the other readings continue.
- Reset to return to normal mode, disconnect, and reset simulated time to zero; reconnect to repeat the sequence.

Missing or failed readings must remain visibly distinct from numerical zero.
All readings in Simulator and its dashboard preview are synthetic; they are not measurements from the RX-8.

Sampling runs only while the Simulator tab is visible in the foreground. Switching to Adapter, Dashboard, or Vehicles, or moving the app to the background, pauses sampling; returning to Simulator allows a connected simulation to resume.
The pause does not represent a saved trip, background service, or continuous recording.
Screen-locked trip recording belongs to RX8-007 and needs separate implementation and device validation.

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
- [Issue #16](https://github.com/gonibix23/rx8-telemetry-android/issues/16): validate background/lock continuity, Activity recreation, visible screen-awake behavior, notification denial/Stop, and battery/resource cleanup on the A53.
- [RX8-006](https://github.com/gonibix23/rx8-telemetry-android/issues/6): validate the dashboard layout, favorite persistence/unavailability, ECU separation, simulator preview, and navigation on the A53.
- Later issues: implement and validate telemetry persistence, trip recording, and API preparation.

Keep issues open while required evidence is missing. PR #14 is merged, but RX8-004 remains open for detailed hardware checks. RX8-005 software does not close RX8-001 or prove VIN compatibility.
There are no published APK releases.
