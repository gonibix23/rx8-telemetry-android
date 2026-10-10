# Development

## Current implementation

The 0.4.0-dev iteration adds read-only OBD initialization, capability discovery, and six live parameters to the Bluetooth and simulator foundation:

| Module | Responsibility |
| --- | --- |
| `:app` | Compose interface, Bluetooth transport, OBD session scheduling, and foreground lifecycle integration |
| `:core:telemetry` | Pure Kotlin OBD parsing/decoding, telemetry types, and deterministic simulated data |

The simulator is identified in the interface and supplies synthetic engine speed, vehicle speed, coolant temperature, intake air temperature, throttle position, and engine load readings.
The simulator stays separate from real acquisition. Neither screen identifies the vehicle permanently, saves trips, or uploads data.
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

A first build needs Internet access to download dependencies.
The app does not request Internet access; simulator and Bluetooth connection checks work without it.

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
The user subsequently confirmed that Bluetooth connects successfully, and PR #13 is merged. No detailed interruption tests or ECU responses have been reported.

## Finding and connecting the Bluetooth adapter

1. Power the adapter, enable phone Bluetooth, and disconnect other apps such as Car Scanner from the adapter.
2. Open the Adapter tab and choose Scan nearby. On Android 12/API 31 or later, allow Nearby devices access.
3. On Android 8-11/API 26-30 only, grant the location permission required for discovery and enable location services. No location is collected.
4. Select OBDII, comparing its MAC address with the device shown by Car Scanner. Unnamed nearby devices also show an address. Already-paired devices remain available.
5. Connect and complete Android's pairing prompt if it appears. A successful result means a Bluetooth socket is open; ECU communication remains unchecked until Read live data succeeds.
6. Disconnect explicitly, switch to Simulator, or leave the app to close the connection. Reconnect manually after returning to Adapter.

This is a provisional secure RFCOMM/SPP connection. Discovery is bounded and stops before connection or when leaving the Adapter tab.
The scan lasts up to 15 seconds, with up to two seconds beforehand to stop an existing inquiry. Connection attempts allow 15 seconds for a paired device or 45 seconds for an unpaired device so Android can request pairing.
Names do not determine compatibility; the app does not filter results to OBDII. If no adapter appears, confirm power, disconnect other diagnostic apps, and scan again.
BLE-only devices are outside the current transport scope, consistent with the user's plain Bluetooth setting in Car Scanner.
On API 31+, scan and connection permissions are separate; a paired adapter can be connected without scan access. Location permission is limited to API 26-30.
Android's [permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) and [device discovery guidance](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices) describe these requirements.

The connection action sends no application bytes. Read live data uses the transport's bounded byte read/write operations for OBD commands.
There is no automatic reconnection or background recording. The user-reported successful Bluetooth connection does not establish sensor support.
Use the [hardware checklist](hardware-validation.md#bluetooth-transport-checklist-rx8-003) to record actual behavior.

## Reading live OBD data

1. With the vehicle stationary and ignition on, connect the adapter and select Read live data.
2. Wait for adapter initialization, automatic protocol detection, and supported-parameter discovery. Initialization failures remain separate from a successful Bluetooth connection.
3. Inspect the protocol, responding ECU identifiers, and six parameter cards. Each ECU is shown separately when more than one responds.
4. Confirm engine speed, stationary vehicle speed, and coolant temperature against the actual operating conditions. A valid zero value is allowed; a missing reading must show its status instead.
5. Observe the timestamps, stale markers, and measured response rate. A parameter appearing in the capability bitmap does not guarantee a valid reading on every request.
6. Disconnect to stop polling, or leave Adapter/the app. Reconnect and choose Read live data again for a fresh session.

The current decoders cover engine speed (rpm), vehicle speed (km/h), coolant and intake temperatures (°C), throttle position (%), and engine load (%).
The engine only requests metrics reported as supported. Mode 01 capability bitmaps can report additional parameters that this build does not yet decode.
Missing replies, unsupported parameters, adapter errors, malformed frames, and stale cached values have distinct states. Failed readings are never represented by a fabricated zero.
Values older than five seconds are marked stale. Disconnecting marks retained valid readings stale immediately; selecting another adapter clears the previous readings.

The engine serializes all exchanges, assembles Bluetooth fragments through the ELM prompt, and limits command time and reply size.
Losing prompt framing closes the connection and requires a manual reconnect, preventing delayed responses from being mistaken for new readings.
RPM and speed receive priority; measured latency and errors slow requests instead of allowing a backlog. Actual rates still require a real-car test.

This build configures the adapter and sends only read-only Mode 01 diagnostics. It does not clear faults, control actuators, query the VIN, or save a trip.
Current Mode 01 replies fit a single CAN frame; arbitrary ISO-TP multi-frame responses and VIN reconstruction are deferred.
Read [Architecture](architecture.md#current-read-only-obd-engine) for the separation between transport, parser, session, and UI.

## Using the simulator

- Connect to start generating synthetic sensor readings; disconnect to stop.
- Select normal mode for valid deterministic readings.
- Select timeout mode to make all readings report a timeout.
- Select missing-data mode to make coolant and intake air temperatures unavailable while the other readings continue.
- Reset to return to normal mode, disconnect, and reset simulated time to zero; reconnect to repeat the sequence.

Missing or failed readings must remain visibly distinct from numerical zero.
All readings in this foundation are simulated; they are not measurements from the RX-8.

Sampling runs only while the Simulator tab is visible in the foreground. Switching to Adapter or moving the app to the background pauses sampling; returning to Simulator allows a connected simulation to resume.
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

The RX8-002 foundation is complete, and the user has confirmed a successful Bluetooth connection after the discovery fix. OBD acquisition still needs its first real-car test.

- [RX8-001](https://github.com/gonibix23/rx8-telemetry-android/issues/1): test the real adapter and RX-8, including ECU communication, supported PIDs, VIN availability, stability, and reconnection.
- [RX8-003](https://github.com/gonibix23/rx8-telemetry-android/issues/3): validate scan and connection permissions, nearby discovery, pairing, connection, cancellation, disconnection, and manual reconnection with the actual adapter.
- [RX8-004](https://github.com/gonibix23/rx8-telemetry-android/issues/4): validate ELM initialization, detected protocol, capabilities, actual values, observed rate, and failure recovery.
- Later issues: implement and validate vehicle identity, persistence, trip recording, and API preparation.

Keep issues open while required evidence is missing. The software OBD PR uses `Refs #4`; it does not close RX8-001 or establish ECU compatibility.
There are no published APK releases.
