# Development roadmap

Current status: the simulator foundation was merged in [PR #12](https://github.com/gonibix23/rx8-telemetry-android/pull/12) after the user reported a successful app test on October 10, 2026.
The user confirmed that the Samsung A53 runs Android 16, completing RX8-002. The paired-only Bluetooth build failed to list the adapter.
Car Scanner reportedly finds OBDII through in-app search with plain Bluetooth selected. After the discovery fix, the user confirmed the app's Bluetooth connection works, and [PR #13](https://github.com/gonibix23/rx8-telemetry-android/pull/13) is merged.
RX8-004 now adds the first read-only OBD engine and a live panel for verifying six standard parameters. ECU communication and detailed transport behavior still require device testing.

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
RX8-xxx codes remain stable even if an issue number changes.
The reported successful connection allows RX8-004 software development to proceed. RX8-003 stays open for remaining device-specific scenarios, while RX8-001 tracks ECU support and sustained behavior.

## Repository status

- Initial documentation, issue forms, and PR template.
- Android foundation with deterministic simulation in normal, timeout, and missing-data modes; foreground-only sampling.
- Bounded Classic discovery, paired and nearby device selection with names and MAC addresses, separate scan/connection permissions, secure SPP connection, cancellation, and manual reconnection. No BLE transport.
- User-started read-only OBD initialization, protocol and supported-PID discovery, six standard decoders, and per-ECU live values with explicit error and stale states. The dedicated dashboard/favorites work remains RX8-006.
- Android build, unit-test, lint, and Markdown check commands are documented in [Development](development.md). Record actual check results in each PR.
- Squash merging and automatic deletion of merged branches are enabled.
- Main branch protection is enabled: pull requests, passing Documentation checks on an up-to-date branch, and resolved review conversations are required, including for administrators. Force-push and branch deletion are disabled. No additional reviewer approval is required for solo maintenance.
- Debug APK assembly and delivery to a configured local folder are part of the development workflow. The simulator and Bluetooth connection work on the A53 running Android 16 according to the user. No backend, deployment, published APK release, or GitHub Projects board has been created.

## Pending decisions

- Detailed interruption, permission, and reconnection validation on the A53 running Android 16. The minimum SDK is 26; compile and target SDK are 36.
- Actual ECU protocol and compatibility with the adapter's ELM command implementation.
- Supported PIDs and sustained sampling rate with this adapter.
- Local retention policy and limits measured with real data.
- Authentication, hosting, and database engine for the future server.
- Distribution license.

## Next verifiable outcome

Connect OBDII on the A53, select Read live data with the vehicle stationary and ignition on, and record the detected protocol and actual RPM, speed, and coolant values or explicit errors.
Follow the [hardware checklist](hardware-validation.md) for sustained readings, interruptions, and manual reconnection. VIN retrieval, permanent vehicle identity, and saved trips follow in later increments.
No backend is required.
