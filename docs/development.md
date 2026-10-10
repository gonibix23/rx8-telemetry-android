# Development

## Current implementation

The 0.3.0-dev iteration adds in-app Classic Bluetooth discovery to the provisional transport and simulator foundation:

| Module | Responsibility |
| --- | --- |
| `:app` | Compose interface, Bluetooth permissions and transport, and foreground lifecycle integration |
| `:core:telemetry` | Pure Kotlin telemetry types and deterministic simulated data |

The simulator is identified in the interface and supplies synthetic engine speed, vehicle speed, coolant temperature, intake air temperature, throttle position, and engine load readings.
The simulator stays separate from the Bluetooth screen. Neither screen queries an ECU, identifies the car, saves trips, or uploads data.
The Bluetooth transport currently opens a socket only; the connection screen does not send application bytes or ELM/OBD commands.
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
The new discovery workflow still requires testing on that adapter. No ECU communication has been validated.

## Finding and connecting the Bluetooth adapter

1. Power the adapter, enable phone Bluetooth, and disconnect other apps such as Car Scanner from the adapter.
2. Open the Adapter tab and choose Scan nearby. On Android 12/API 31 or later, allow Nearby devices access.
3. On Android 8-11/API 26-30 only, grant the location permission required for discovery and enable location services. No location is collected.
4. Select OBDII, comparing its MAC address with the device shown by Car Scanner. Unnamed nearby devices also show an address. Already-paired devices remain available.
5. Connect and complete Android's pairing prompt if it appears. A successful result means a Bluetooth socket is open; the screen must still say ECU communication is not checked.
6. Disconnect explicitly, switch to Simulator, or leave the app to close the connection. Reconnect manually after returning to Adapter.

This is a provisional secure RFCOMM/SPP connection. Discovery is bounded and stops before connection or when leaving the Adapter tab.
The scan lasts up to 15 seconds, with up to two seconds beforehand to stop an existing inquiry. Connection attempts allow 15 seconds for a paired device or 45 seconds for an unpaired device so Android can request pairing.
Names do not determine compatibility; the app does not filter results to OBDII. If no adapter appears, confirm power, disconnect other diagnostic apps, and scan again.
BLE-only devices are outside the current transport scope, consistent with the user's plain Bluetooth setting in Car Scanner.
On API 31+, scan and connection permissions are separate; a paired adapter can be connected without scan access. Location permission is limited to API 26-30.
Android's [permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) and [device discovery guidance](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices) describe these requirements.

The connection UI sends no application bytes or ELM/OBD commands. System Bluetooth handshakes still occur.
The transport has bounded byte read/write operations for future OBD work, but no sensor decoding or ECU heartbeat.
It does not automatically reconnect, retain an adapter connection in the background, or prove the iLC adapter supports this transport.
Use the [hardware checklist](hardware-validation.md#bluetooth-transport-checklist-rx8-003) to record actual behavior.

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

The RX8-002 foundation is complete with the user-reported successful simulator test and confirmed Android 16 version. The paired-only adapter list failed; in-app discovery and connection need a new device test.

- [RX8-001](https://github.com/gonibix23/rx8-telemetry-android/issues/1): test the real adapter and RX-8, including ECU communication, supported PIDs, VIN availability, stability, and reconnection.
- [RX8-003](https://github.com/gonibix23/rx8-telemetry-android/issues/3): validate scan and connection permissions, nearby discovery, pairing, connection, cancellation, disconnection, and manual reconnection with the actual adapter.
- Later issues: implement and validate OBD acquisition, vehicle identity, persistence, trip recording, and API preparation.

Keep issues open while required evidence is missing. A software-only transport PR uses `Refs #3`; it does not close RX8-001 or establish adapter compatibility.
There are no published APK releases.
