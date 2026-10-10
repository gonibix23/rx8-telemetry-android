# Development roadmap

Current status: the initial Android simulator foundation is implemented. RX8-002 remains open pending installation and validation on the Samsung A53. No adapter capability has been physically validated yet.

## Milestones and issues

| Code | Work | Milestone | Dependencies |
| --- | --- | --- | --- |
| [RX8-001](https://github.com/gonibix23/rx8-telemetry-android/issues/1) | Validate compatibility with the vehicle and A53 | M0 - Hardware compatibility | None |
| [RX8-002](https://github.com/gonibix23/rx8-telemetry-android/issues/2) | Android foundation and simulator | M1 - Connection and acquisition | None |
| [RX8-003](https://github.com/gonibix23/rx8-telemetry-android/issues/3) | Bluetooth transport and permissions | M1 - Connection and acquisition | RX8-001, RX8-002 |
| [RX8-004](https://github.com/gonibix23/rx8-telemetry-android/issues/4) | OBD reading and capability discovery | M1 - Connection and acquisition | RX8-003 |
| [RX8-005](https://github.com/gonibix23/rx8-telemetry-android/issues/5) | Permanent vehicle identity | M2 - Live data and trips | RX8-004 |
| [RX8-006](https://github.com/gonibix23/rx8-telemetry-android/issues/6) | Live data dashboard | M2 - Live data and trips | RX8-004, RX8-005 |
| [RX8-007](https://github.com/gonibix23/rx8-telemetry-android/issues/7) | Local trip recording and history | M2 - Live data and trips | RX8-005, RX8-006 |
| [RX8-008](https://github.com/gonibix23/rx8-telemetry-android/issues/8) | Contract and synchronization preparation | M3 - API preparation | RX8-007 |
| [RX8-009](https://github.com/gonibix23/rx8-telemetry-android/issues/9) | Investigate Mazda sensors | Later - Mazda extensions | RX8-001, RX8-004 |

[Project issues](https://github.com/gonibix23/rx8-telemetry-android/issues) and [milestones](https://github.com/gonibix23/rx8-telemetry-android/milestones) are the operational tracker.
RX8-xxx codes remain stable even if an issue number changes.
RX8-001 and RX8-002 can progress in parallel. Actual connection failures constrain RX8-003 and RX8-004.

## Repository status

- Initial documentation, issue forms, and PR template.
- Android foundation with deterministic simulation in normal, timeout, and missing-data modes; foreground-only sampling.
- Android build, unit-test, lint, and Markdown check commands are documented in [Development](development.md). Record actual check results in each PR.
- Squash merging and automatic deletion of merged branches are enabled.
- Main branch protection is enabled: pull requests, passing Documentation checks on an up-to-date branch, and resolved review conversations are required, including for administrators. Force-push and branch deletion are disabled. No additional reviewer approval is required for solo maintenance.
- Debug APK assembly is part of the development workflow. A53 installation is pending; no backend, deployment, published APK release, or GitHub Projects board has been created.

## Pending decisions

- Android version on the A53 and device validation. The initial minimum SDK is 26; compile and target SDK are 36.
- Actual transport and protocol detected.
- Supported PIDs and sustained sampling rate with this adapter.
- Local retention policy and limits measured with real data.
- Authentication, hosting, and database engine for the future server.
- Distribution license.

## Next verifiable outcome

Build and run the documented software checks, then install the simulator on the Samsung A53 and record its Android version and behavior.
RX8-002 remains open until this device check is complete.
Separately, RX8-001 requires a short documented test that confirms ECU communication, records capabilities, and obtains reliable readings from the actual car.
Neither validation requires a backend.
