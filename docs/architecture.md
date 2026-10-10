# Architecture

## Status

The app contains a deterministic simulator and provisional paired Bluetooth Classic/SPP transport.
The Bluetooth screen lists already-paired Classic or dual-mode devices, handles connection permission, and opens a secure RFCOMM socket.
It sends no application bytes or ELM/OBD requests and keeps **Bluetooth connection** separate from **ECU communication: not checked**.

Both screens are foreground-only: simulation pauses when its tab is hidden, and leaving the Adapter tab or app closes the connection and requires manual reconnection.
The user reported the simulator app worked after testing on October 10, 2026 and confirmed the Samsung A53 runs Android 16. Bluetooth pairing has not been tried yet.
No detailed lifecycle or adapter test results can be inferred from that report.

The diagram and later storage, identity, recording, and synchronization sections describe the **target MVP architecture**.
Actual OBD queries, vehicle identity, saved trips, Room/DataStore storage, and API integration remain planned.
See [Development](development.md) for setup and current behavior.

Target: European-market 2004 RX-8 with 231 PS, Samsung A53 running Android 16, and iLC B0BDRHRR22 adapter.
Secure Classic/SPP is an initial implementation assumption; RX8-001 must establish physical compatibility.

## Components

```mermaid
flowchart LR
  Car[RX-8] <--> Adapter[OBD adapter]
  Adapter <--> Bluetooth[Bluetooth transport]
  Bluetooth <--> Obd[OBD engine]
  Obd --> Live[Live state]
  Live --> UI[Compose interface]
  Obd --> Recorder[Trip recording]
  Recorder --> DB[(Room)]
  DB --> UI
  DB --> Export[Export]
  DB --> Outbox[Persistent queue]
  Outbox -. Future integration .-> API[API]
```

Use Kotlin and Jetpack Compose for the interface, ViewModel/StateFlow for its state, and coroutines for asynchronous work.
Room stores structured data; DataStore stores settings.
Components can start as packages; there is no need to create a Gradle module for each component from day one.

## Transport and OBD engine

### Current paired-device transport

The Android transport is separate from Compose presentation and the synthetic telemetry source.
It lists bonded Classic/dual devices and attempts secure RFCOMM using the standard Serial Port Profile service UUID.
It does not perform device discovery, BLE operations, or a fallback to an unverified connection method.

Connection attempts run away from the UI thread with a 15-second application timeout.
Byte reads and writes are bounded and cancellable; closing the socket cancels blocked operations.
These byte operations prepare for RX8-004. The connection screen does not call them to send ELM initialization or ECU commands.
Android still performs the protocol handshakes needed to establish the Bluetooth connection.

No automatic reconnection, background service, or ECU heartbeat is implemented.
The user reconnects manually, and a connected socket does not establish adapter command compatibility or sensor availability.
This follows Android's [paired-device](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices) and [RFCOMM client](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices) guidance.

### Planned OBD engine

RX8-004 will:

- Initialize the adapter and discover the protocol and capabilities.
- Serialize requests through a queue with timeouts and bounded retries.
- Reassemble fragments and preserve the source ECU when multiple modules respond.
- Distinguish adapter errors, missing responses, and unsupported parameters.
- Prioritize visible/selected sensors and adjust polling intervals to latency.
- Avoid repeatedly querying parameters reported as unsupported.

Do not commit to a per-sensor sampling rate before measuring it. Sequential readings are not simultaneous: each has its own timestamp.
The adapter's identification string alone does not prove its authenticity or all of its capabilities.

## Local model

| Entity | Contents |
| --- | --- |
| Vehicle | Stable UUID, optional VIN, provenance, and alias |
| Adapter | Connection information and observed capabilities |
| Capability | Parameters by vehicle, ECU, and profile |
| Session | Start, end, duration, status, and real/simulated origin |
| TelemetrySample | UUID, sequence, timestamps, metric, value, unit, source, and status |
| OutboxEntry | Pending work, attempts, next execution, and acknowledgments |

Samples are immutable. Sessions use the recording, completed, or interrupted states.
Each reading carries a UTC timestamp and monotonic elapsed time since the start; sequence determines ordering.
Values are normalized to canonical units and converted only for display.

A failed reading has a null value. The interface may show the last valid reading as stale without storing it again as a new measurement.
Distance integrated from speed is identified as an estimate and records intervals with missing data.

## Identity

Always create a vehicleId UUID when registering a car.
The VIN is optional, retrieved through mode 09 PID 02 or entered manually, with validation and provenance.

Adding a VIN does not change the identity or split the history.
A different VIN must not be silently assigned to the active profile: the user must select or create the corresponding vehicle.
The adapter's MAC address does not identify the car. Without a VIN, the user selects the profile when necessary.

After clearing app data or reinstalling, recovering the same UUIDs requires restoring a compatible backup/export.
Import/restore has not been implemented and is not guaranteed in the MVP; JSON retains the necessary information.

## Sessions and permissions

### Current connection permissions

On Android 12/API 31 and later, the app requests `BLUETOOTH_CONNECT` through the Nearby devices runtime prompt.
On API 26-30, it uses the legacy `BLUETOOTH` permission limited to those versions.
Pairing and Bluetooth settings are handled in Android settings. The app does not request scan, advertise, location, or Internet permissions.
See Android's [Bluetooth permission guidance](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

The connection closes when the Adapter tab is hidden or the app leaves the foreground. Returning requires a manual reconnect.
This is not screen-locked trip recording.

### Planned recording sessions

RX8-007 will add user-started recording with a `connectedDevice` foreground service, a notification, and a stop action.
Declare the permissions and service type required by the Android version/target when implementing that feature.

The MVP does not include GPS. WorkManager is reserved for future synchronization, not vehicle polling.
Recording with the screen locked will need explicit A53 testing; indefinite survival after process termination is not promised.
Record a gap when Bluetooth is lost; after restarting, recover persisted data and mark the unfinished session as interrupted.

## Future synchronization

Define a remote interface, versioned models, and a fake implementation solely for isolated tests.
The app used with real data keeps uploads disabled until an API and authentication are available.

Local writes and pending-work creation are atomic.
The queue references persisted samples without duplicating the entire history.
A future worker sends batches, observes connectivity requirements, and confirms only records that receive an acknowledgment (ACK).
A server simulator must never mark the user's real data as uploaded.
See [the contract](api-contract.md) for details.

## Retention and export

Keep trips until explicitly deleted, show storage usage, and warn before storage runs out.
If data cannot be saved, stop recording in a controlled way and display the error.
When deleting records pending upload, explain which data will be lost and remove their queued work.

Export versioned JSON with IDs, units, and statuses; use CSV for analysis.
Do not commit real databases or private exports.
Define exclusions/encryption for Android backups before distribution.

## Validation

Parser tests with complete, fragmented, multi-ECU, and malformed responses.
Tests for persistence, migrations, identity, unexpected termination, the queue, and retries.
Interface tests with a clearly identified simulator.
Real-vehicle validation for compatibility, sampling rate, reconnection, and recording with the screen locked.

## References

- [Android architecture](https://developer.android.com/topic/architecture/recommendations).
- [Bluetooth Classic](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices).
- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
- [Foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).
- [Offline-first operation](https://developer.android.com/topic/architecture/data-layer/offline-first).
