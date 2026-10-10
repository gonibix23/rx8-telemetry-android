# Development roadmap

Current status: the simulator foundation was merged in [PR #12](https://github.com/gonibix23/rx8-telemetry-android/pull/12) after the user reported a successful app test on October 10, 2026.
The user confirmed that the Samsung A53 runs Android 16, completing RX8-002. The paired-only Bluetooth build failed to list the adapter.
Car Scanner reportedly finds OBDII through in-app search with plain Bluetooth selected. RX8-003 adds Classic discovery to address that failure.
Secure SPP and ECU communication still require hardware validation in this app.

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
RX8-003 can develop against the reported Classic Bluetooth setting, but physical RX8-001 evidence is still required before claiming compatibility or completing dependent hardware criteria.
Actual connection failures constrain RX8-003 and RX8-004.

## Repository status

- Initial documentation, issue forms, and PR template.
- Android foundation with deterministic simulation in normal, timeout, and missing-data modes; foreground-only sampling.
- Bounded Classic discovery, paired and nearby device selection with names and MAC addresses, separate scan/connection permissions, secure SPP connection, cancellation, and manual reconnection. No BLE transport or ECU queries.
- Android build, unit-test, lint, and Markdown check commands are documented in [Development](development.md). Record actual check results in each PR.
- Squash merging and automatic deletion of merged branches are enabled.
- Main branch protection is enabled: pull requests, passing Documentation checks on an up-to-date branch, and resolved review conversations are required, including for administrators. Force-push and branch deletion are disabled. No additional reviewer approval is required for solo maintenance.
- Debug APK assembly is part of the development workflow. The simulator works on the A53 running Android 16 according to the user; the new discovery workflow awaits testing after the paired-only list failed. No backend, deployment, published APK release, or GitHub Projects board has been created.

## Pending decisions

- In-app discovery, pairing, and detailed adapter validation on the A53 running Android 16. The minimum SDK is 26; compile and target SDK are 36.
- Actual transport and protocol detected.
- Supported PIDs and sustained sampling rate with this adapter.
- Local retention policy and limits measured with real data.
- Authentication, hosting, and database engine for the future server.
- Distribution license.

## Next verifiable outcome

Find OBDII with Scan nearby on the A53 running Android 16, connect through Android's pairing prompt if required, then exercise the [Bluetooth connection checklist](hardware-validation.md#bluetooth-transport-checklist-rx8-003).
A successful socket connection proves only the phone-to-adapter link. ECU requests and PID/VIN validation require the later OBD implementation or an appropriate diagnostic tool under RX8-001.
No backend is required.
