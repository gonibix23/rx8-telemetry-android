# Development roadmap

Current status: the simulator foundation was merged in [PR #12](https://github.com/gonibix23/rx8-telemetry-android/pull/12) after the user reported a successful app test on October 10, 2026.
The user confirmed that the Samsung A53 runs Android 16, completing RX8-002. The paired-only Bluetooth build failed to list the adapter.
Car Scanner reportedly finds OBDII through in-app search with plain Bluetooth selected. After the discovery fix, the user confirmed the app's Bluetooth connection works, and [PR #13](https://github.com/gonibix23/rx8-telemetry-android/pull/13) is merged.
On October 11, 2026, the user reported that live readings worked. [PR #14](https://github.com/gonibix23/rx8-telemetry-android/pull/14) is merged at `7eb75463adbb28f1e00b80b7687b6d9f83e0859d`.
The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed that 0.5.0-dev reads data correctly. These reports do not establish each of the six sensors, ECU identifiers, measured rates, sustained lifecycle behavior, VIN retrieval, or profile persistence; RX8-004 remains open for detailed hardware checks.
RX8-005 / 0.5.0-dev added Room vehicle profiles with stable UUIDs, persisted selection, and optional manual/OBD VIN review in merged [PR #15](https://github.com/gonibix23/rx8-telemetry-android/pull/15). Issue #5 remains open for device validation.
The 0.5.1-dev fix for [issue #16](https://github.com/gonibix23/rx8-telemetry-android/issues/16) is merged in [PR #17](https://github.com/gonibix23/rx8-telemetry-android/pull/17) at `adbc1ba`. It keeps an explicitly started service-owned session across tabs/background/lock/recreation, with visible screen-awake behavior and notification controls. The user subsequently confirmed that 0.5.1-dev worked.
The user reported screen sleep and background disconnection in 0.5.0-dev. Actual bond removal, measured durations, battery use, and detailed notification scenarios remain unverified despite the broad success report.

The user confirmed that 0.6.0-dev works perfectly, and [PR #18](https://github.com/gonibix23/rx8-telemetry-android/pull/18) is merged. Its dashboard/favorites have broad user confirmation; individual scenario results remain unreported.
The current 0.7.0-dev increment (version code 8) implements RX8-007: manual recording for a fixed vehicle UUID, local session/sample storage, interruption recovery, offline history, speed charts, estimated distance with coverage, JSON/CSV exports, and confirmed trip deletion. Real-device trip validation remains pending. API preparation follows in RX8-008.

## Milestones and issues

| Code | Work | Milestone | Dependencies |
| --- | --- | --- | --- |
| [RX8-001](https://github.com/gonibix23/rx8-telemetry-android/issues/1) | Validate compatibility with the vehicle and A53 | M0 - Hardware compatibility | None |
| [RX8-002](https://github.com/gonibix23/rx8-telemetry-android/issues/2) | Android foundation and simulator (complete) | M1 - Connection and acquisition | None |
| [RX8-003](https://github.com/gonibix23/rx8-telemetry-android/issues/3) | Bluetooth transport and permissions | M1 - Connection and acquisition | RX8-001, RX8-002 |
| [RX8-004](https://github.com/gonibix23/rx8-telemetry-android/issues/4) | OBD reading and capability discovery | M1 - Connection and acquisition | RX8-003 |
| [RX8-005](https://github.com/gonibix23/rx8-telemetry-android/issues/5) | Permanent vehicle identity | M2 - Live data and trips | RX8-004 |
| [RX8-006](https://github.com/gonibix23/rx8-telemetry-android/issues/6) | Live data dashboard | M2 - Live data and trips | RX8-004, RX8-005 |
| [RX8-007](https://github.com/gonibix23/rx8-telemetry-android/issues/7) | Local trip recording and history | M2 - Live data and trips | RX8-005, RX8-006 |
| [RX8-008](https://github.com/gonibix23/rx8-telemetry-android/issues/8) | Contract and synchronization preparation | M3 - API preparation | RX8-007 |
| [RX8-009](https://github.com/gonibix23/rx8-telemetry-android/issues/9) | Investigate Mazda sensors | Later - Mazda extensions | RX8-001, RX8-004 |

[Project issues](https://github.com/gonibix23/rx8-telemetry-android/issues) and [milestones](https://github.com/gonibix23/rx8-telemetry-android/milestones) are the operational tracker.
RX8-xxx codes remain stable even if an issue number changes. The focused [lifecycle fix, issue #16](https://github.com/gonibix23/rx8-telemetry-android/issues/16), supports acquisition without completing RX8-007 trip recording.
The reported live-reading and 0.5.1-dev success allow dashboard development to proceed. RX8-003 and RX8-004 retain their unverified device-specific checks, while RX8-001 tracks compatibility and sustained behavior. RX8-005 is not complete until its required checks are satisfied.

## Repository status

- Initial documentation, issue forms, and PR template.
- Android foundation with deterministic simulation in normal, timeout, and missing-data modes; foreground-only sampling.
- Bounded Classic discovery, paired and nearby device selection with names and MAC addresses, separate scan/connection permissions, secure SPP connection, cancellation, and manual reconnection. No BLE transport.
- User-started read-only OBD initialization, protocol and supported-PID discovery, six standard decoders, and per-ECU live values with explicit error and stale states. RX8-006 adds a dedicated dashboard without changing decoder support or acquisition scheduling.
- Fixed supported RPM/speed/coolant cards, optional intake/throttle/load favorites saved in app-wide SharedPreferences, separate ECU groups, honest unavailable/stale states, and a synthetic simulator preview using the same cards.
- Explicit manual trip recording through the existing foreground service; ordered bounded writes to Room v2 with a non-destructive v1 migration. Latest 100 trips are available offline with terminal summaries, a bounded speed chart, JSON/CSV document exports, and confirmed deletion. Process recovery preserves committed readings and marks unfinished sessions interrupted. No GPS, simulated-trip recording, or uploads.
- Explicit vehicle creation/selection/rename, stable UUIDs, optional VINs and provenance, and Room persistence. Duplicate or different VINs never silently reassign a profile.
- Optional, user-started Mode 09 PID 02 acquisition during LIVE; limited CAN/legacy VIN reconstruction, masked candidates, and explicit review in Vehicles before saving. No general ISO-TP support or automatic vehicle assignment.
- Non-exported connected-device foreground service owning an explicitly started session; background/lock/recreation continuity, notification Stop/open-Adapter, visible acquisition screen-awake behavior, and bounded partial wake-lock renewal. No automatic reconnect or process-death recovery; discovery and simulation retain their foreground limits.
- Android build, unit-test, lint, and Markdown check commands are documented in [Development](development.md). Record actual check results in each PR.
- Squash merging and automatic deletion of merged branches are enabled.
- Main branch protection is enabled: pull requests, passing Documentation checks on an up-to-date branch, and resolved review conversations are required, including for administrators. Force-push and branch deletion are disabled. No additional reviewer approval is required for solo maintenance.
- Debug APK assembly and delivery to a configured local folder are part of the development workflow. The simulator, Bluetooth connection, and general live readings work on the A53 running Android 16 according to the user. No backend, deployment, published APK release, or GitHub Projects board has been created.

## Pending decisions

- A53 dashboard layout, favorite persistence across app restarts/profile changes, unavailable favorites, multiple-ECU presentation, simulator preview, and navigation checks.
- Detailed background/lock/recreation, notification denial/Stop, battery/resource cleanup, permission, and reconnection validation on the A53 running Android 16. The minimum SDK is 26; compile and target SDK are 36.
- Remaining compatibility with the adapter's ELM command implementation, beyond the reported successful live readings over `ISO 15765-CAN (11 bit, 500 kbaud)`.
- Supported PIDs and sustained sampling rate with this adapter.
- A53 trip recording across lock/background, notification Stop, Bluetooth loss, storage interruption, upgrade preservation, and JSON/CSV document-provider behavior. Retention is manual deletion; real storage growth remains to be measured.
- Authentication, hosting, and database engine for the future server.
- Distribution license.

## Next verifiable outcome

Run 0.7.0-dev on the A53, select a vehicle, start real live data in Adapter, then explicitly Start recording in Trips. Record across a screen-locked interval, stop, and inspect the saved trip offline. Verify JSON/CSV exports, gaps after an interruption, notification Stop, and existing profiles after upgrading.
Follow the [hardware checklist](hardware-validation.md) for the complete trip checks and remaining lifecycle/battery, VIN/profile, sensor, ECU identifier, rate, and reconnection evidence. API preparation follows in the next increment.
No backend is required.
