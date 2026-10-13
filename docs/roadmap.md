# Development roadmap

## Current update (0.15.3-dev)

[Issue #54](https://github.com/gonibix23/rx8-telemetry-android/issues/54) removes unused fixed-width space from the floating sensor panel using native content measurement, screen limits and preserved gestures. The owner completed the remaining acceptance criteria in #4, #6, #7 and #24; they were closed on October 13, 2026, superseding their historical pending notes below. #1, #3, #9 and #36 retain independent unchecked criteria. Deployed database inspection remains deferred.

Implementation history: the simulator foundation was merged in [PR #12](https://github.com/gonibix23/rx8-telemetry-android/pull/12) after the user reported a successful app test on October 10, 2026.
The user confirmed that the Samsung A53 runs Android 16, completing RX8-002. The paired-only Bluetooth build failed to list the adapter.
Car Scanner reportedly finds OBDII through in-app search with plain Bluetooth selected. After the discovery fix, the user confirmed the app's Bluetooth connection works, and [PR #13](https://github.com/gonibix23/rx8-telemetry-android/pull/13) is merged.
On October 11, 2026, the user reported that live readings worked. [PR #14](https://github.com/gonibix23/rx8-telemetry-android/pull/14) is merged at `7eb75463adbb28f1e00b80b7687b6d9f83e0859d`.
The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed that 0.5.0-dev reads data correctly. These reports do not establish each of the six sensors, ECU identifiers, measured rates, sustained lifecycle behavior, VIN retrieval, or profile persistence; RX8-004 remains open for detailed hardware checks.
RX8-005 / 0.5.0-dev added Room vehicle profiles with stable UUIDs, persisted selection, and optional manual/OBD VIN review in merged [PR #15](https://github.com/gonibix23/rx8-telemetry-android/pull/15). Issue #5 remains open for device validation.
The 0.5.1-dev fix for [issue #16](https://github.com/gonibix23/rx8-telemetry-android/issues/16) is merged in [PR #17](https://github.com/gonibix23/rx8-telemetry-android/pull/17) at `adbc1ba`. It keeps an explicitly started service-owned session across tabs/background/lock/recreation, with visible screen-awake behavior and notification controls. The user subsequently confirmed that 0.5.1-dev worked.
The user reported screen sleep and background disconnection in 0.5.0-dev. Actual bond removal, measured durations, battery use, and detailed notification scenarios remain unverified despite the broad success report.

The user confirmed that 0.6.0-dev works perfectly, and [PR #18](https://github.com/gonibix23/rx8-telemetry-android/pull/18) is merged. Its dashboard/favorites have broad user confirmation; individual scenario results remain unreported.
The user subsequently confirmed that 0.7.0-dev works perfectly, and [PR #19](https://github.com/gonibix23/rx8-telemetry-android/pull/19) is merged at `d855c99`. RX8-007 introduced manual recording for a fixed vehicle UUID, local session/sample storage, interruption recovery, offline history, charts, estimates, exports, and deletion. Detailed measured recording, provider, storage/interruption, and battery checks remain open.
The user confirmed 0.8.0-dev worked correctly, and [PR #20](https://github.com/gonibix23/rx8-telemetry-android/pull/20) is merged at `bba3e61`. RX8-008 API preparation is complete: versioned DTOs, a durable sample-reference queue with migration/backfill, metadata revisions, immutable requests, isolated ACK/retry handling, and a read-only Uploads disabled status. This does not validate a real server or every individual phone scenario.
PR #22 / 0.9.0-dev implements RX8-010: per-ECU capabilities and explicit JSON snapshot reports from existing discovery and display state. It is merged with phone validation explicitly deferred by the user. Issue #16 is closed at the user's request with all acceptance checkboxes complete; no additional measured logs were provided.
The user reports that 0.10.0-dev (version code 11) works well, and PR #25 is merged at `b3e71a9`. RX8-012 provides offline saved-trip charts selectable by recorded metric and ECU, with bounded loading, honest gaps, and full-stream statistics. Individual phone scenarios remain unreported. No extra OBD request, database migration, backend, HTTP transport, production upload worker, or enabled upload configuration is added.
RX8-011 is complete in PR #26: CI action runtimes and the Ubuntu runner baseline are verified on Android 16. RX8-013 tracks remaining documentation dependency advisories separately. RX8-014 / 0.11.0-dev adds bounded history pages and independent vehicle filtering in merged PR #30. RX8-015 / issue #29 / 0.12.0-dev is merged in PR #31 and implements the user-requested automatic connection/read/record flow, expanded standard sensor catalog, real RPM dial and four single-line tabs. Journeys are telemetry only, without GPS.

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
| [RX8-008](https://github.com/gonibix23/rx8-telemetry-android/issues/8) | Contract and synchronization preparation (complete) | M3 - API preparation | RX8-007 |
| [RX8-009](https://github.com/gonibix23/rx8-telemetry-android/issues/9) | Investigate Mazda sensors | Later - Mazda extensions | RX8-001, RX8-004 |
| [RX8-010](https://github.com/gonibix23/rx8-telemetry-android/issues/21) | View capabilities and export a snapshot | Hardware evidence support | RX8-004 software |
| [RX8-011](https://github.com/gonibix23/rx8-telemetry-android/issues/23) | Update CI action runtimes and runner pinning | Maintenance | Existing verification workflow |
| [RX8-012](https://github.com/gonibix23/rx8-telemetry-android/issues/24) | Explore recorded sensors by metric and ECU | M2 - Live data and trips | RX8-007 software |
| [RX8-013](https://github.com/gonibix23/rx8-telemetry-android/issues/27) | Resolve documentation dependency advisories | Maintenance | Existing documentation checks |
| [RX8-014](https://github.com/gonibix23/rx8-telemetry-android/issues/28) | Browse older trips and filter by vehicle | M2 - Live data and trips | RX8-012 software |
| [RX8-015](https://github.com/gonibix23/rx8-telemetry-android/issues/29) | Automatic journeys, broader readings and real dashboard | M2 - Live data and trips | RX8-014 |
| [RX8-016](https://github.com/gonibix23/rx8-telemetry-android/issues/32) | Search and filter dashboard sensor choices | M2 - Live data and trips | RX8-015 |
| [RX8-017](https://github.com/gonibix23/rx8-telemetry-android/issues/34) | Separate Atlas API and offline completed-trip uploads | M4 - API integration | RX8-008, RX8-016 |

[Project issues](https://github.com/gonibix23/rx8-telemetry-android/issues) and [milestones](https://github.com/gonibix23/rx8-telemetry-android/milestones) are the operational tracker.
RX8-xxx codes remain stable even if an issue number changes. The focused [lifecycle fix, issue #16](https://github.com/gonibix23/rx8-telemetry-android/issues/16), supports acquisition without completing RX8-007 trip recording.
The reported live-reading and 0.5.1-dev success allow dashboard development to proceed. RX8-003 and RX8-004 retain their unverified device-specific checks, while RX8-001 tracks compatibility and sustained behavior. RX8-005 is not complete until its required checks are satisfied.

## Repository status

- The repository is public. Original Android/API code and documentation use the [MIT License](../LICENSE); third-party components retain their existing licenses.

- Initial documentation, issue forms, and PR template.
- Android foundation with deterministic simulation in normal, timeout, and missing-data modes; foreground-only sampling.
- Bounded Classic discovery, paired and nearby device selection with names and MAC addresses, separate scan/connection permissions, secure SPP connection, cancellation, and manual reconnection. No BLE transport.
- Automatic read-only OBD initialization, protocol and supported-PID discovery, an expanded standard scalar catalog, and per-ECU live values with explicit error and stale states. RX8-015 expands decoding and automatic acquisition while preserving source identities.
- Fixed supported RPM/speed/coolant cards, optional supported-sensor favorites saved in app-wide SharedPreferences, separate ECU groups, honest unavailable/stale states, and a real per-ECU RPM dial; synthetic scenarios remain internal fixtures.
- Automatic connection-to-disconnection telemetry journeys and explicit recording controls through the existing foreground service; ordered bounded writes to Room with explicit migrations. History loads at most 50 trips per page, with Newer/Older/Newest controls and independent vehicle filtering. Room v4 adds indexes without changing existing records. Terminal summaries, sensor charts, JSON/CSV exports, and confirmed deletion remain available. Process recovery preserves committed readings and marks unfinished sessions interrupted. No GPS or simulated-trip recording. RX8-017 adds optional terminal-trip uploads after configuration.
- Room v3 adds atomic pending work for new and previously recorded samples, immutable metadata revisions/prepared requests, bounded batches, leases, per-sample ACKs, and retry/conflict/split handling. The original preparation used disabled status/counts; RX8-017 adds the guarded HTTPS transport and network-constrained work. Fake-server exercises remain isolated from real records. Explicit 1-to-2 and 2-to-3 migrations preserve stored data.
- Vehicle capabilities captures advertised PID lists and the latest display state into an immutable, allowlisted preview. Explicit JSON export excludes VINs, MACs, profiles, trip identifiers, raw logs, and free-form adapter/errors. It adds no requests and does not claim sustained measurement evidence or new sensor support.
- Explicit vehicle creation/selection/rename, stable UUIDs, optional VINs and provenance, and Room persistence. Duplicate or different VINs never silently reassign a profile.
- Optional, user-started Mode 09 PID 02 acquisition during LIVE; limited CAN/legacy VIN reconstruction, masked candidates, and explicit review in Vehicles before saving. No general ISO-TP support or automatic vehicle assignment.
- Non-exported connected-device foreground service owning automatic or manually started sessions; background/lock/recreation continuity, notification Stop/open-Adapter, visible acquisition screen-awake behavior, and bounded partial wake-lock renewal. One last-adapter automatic attempt is allowed per visible new app process; no background reconnect loop or process-death session restoration. Discovery retains its foreground limit.
- Android build, unit-test, lint, and Markdown check commands are documented in [Development](development.md). Record actual check results in each PR.
- Squash merging and automatic deletion of merged branches are enabled.
- Main branch protection is enabled: pull requests, passing Documentation checks on an up-to-date branch, and resolved review conversations are required, including for administrators. Force-push and branch deletion are disabled. No additional reviewer approval is required for solo maintenance.
- Debug APK assembly and delivery to a configured local folder are part of the development workflow. The simulator, Bluetooth connection, and general live readings work on the A53 running Android 16 according to the user. RX8-017 adds the backend in `api/` with independent development/CI. Deployment, published APK releases and a GitHub Projects board remain deferred.

## Pending decisions

RX8-009 now has a [Mazda parameter evidence matrix](mazda-parameters.md). Knock retard and trailing ignition timing have published wire candidates; oil metering, intake valves and cluster readings still need accepted definitions. No proprietary query is enabled or car-verified. The owner has deferred vehicle tests; isolated decoder/transport design can proceed independently.

- A53 real RPM dial layout, favorite persistence across app restarts/profile changes, unavailable favorites, multiple-ECU presentation, and four-tab navigation checks.
- Detailed background/lock/recreation, notification denial/Stop, battery/resource cleanup, permission, and reconnection validation on the A53 running Android 16. The minimum SDK is 26; compile and target SDK are 36.
- Remaining compatibility with the adapter's ELM command implementation, beyond the reported successful live readings over `ISO 15765-CAN (11 bit, 500 kbaud)`.
- Supported PIDs and sustained sampling rate with this adapter.
- A53 trip recording across lock/background, notification Stop, Bluetooth loss, storage interruption, upgrade preservation, and JSON/CSV document-provider behavior. Retention is manual deletion; real storage growth remains to be measured.
- MongoDB Atlas is selected and the API has no login as requested. Actual server hosting and public HTTPS URL remain deferred.
- Individual A53 0.8.0-dev upgrade, count, export/deletion scenarios beyond the broad successful test; 0.9.0-dev capability reports and 0.10.0-dev sensor charts. The user has deferred phone validation while development continues.

## Next verifiable outcome

The owner broadly confirmed 0.12.1-dev works perfectly before explicitly requesting an anonymous Atlas API and offline synchronization. RX8-017 / issue #34 implements API and Android tracks in the same repository, with independent build/test commands and labels. Verify isolated MongoDB persistence/idempotency, terminal-only upload eligibility, offline recovery, strict ACKs and configurable HTTPS settings. Run all local checks and full Android 16 CI, inspect all eleven screenshots, and deliver 0.13.0-dev through the configured APK script.

The actual API deployment and public URL are deferred. After deployment, configure the URL in Trips and validate real completed-trip delivery, offline queuing/reconnection, server persistence and retained local history. Phone/software evidence remains distinct; earlier broad success does not establish remote uploads or detailed sensor/battery behavior.

## RX8-020: daily telemetry workflow

[Issue #42](https://github.com/gonibix23/rx8-telemetry-android/issues/42) tracks 0.14.0-dev: structured API logging, durable delivery feedback, Bluetooth-only Adapter, a trip chart dropdown, configurable Live cards with every metric selectable, visible-app favourite reconnect, and an opt-in floating overlay. Root documentation describes the new behavior; older sections record the previous versions' contracts.

The owner reported successful 0.13.0-dev use and supplied a private stationary start/rev/shutdown trip. Its 457 readings across 30 metrics passed export validation; three readings retain explicit error states. The original capture is not committed. The owner explicitly deferred real database storage verification and will perform it later; it is outside RX8-020. PRs #39-41 introduced the existing image publication and server redeployment workflow.

## RX8-021: clean Live modes and compact overlay

[Issue #44](https://github.com/gonibix23/rx8-telemetry-android/issues/44) tracks 0.15.0-dev: normal/edit Live modes, durable one/two-column layouts, adapter details in Adapter, and compact hold-to-dismiss floating readings. Acquisition and trip coverage remain independent of selected Live/overlay cards. Software validation includes a full engine/controller/recorder regression for hidden fields; phone/car checks remain deferred.

Documentation dependency remediation in [PR #45](https://github.com/gonibix23/rx8-telemetry-android/pull/45) is merged and #27 is closed. The same Markdown engine/rules/coverage are retained with a smaller wrapper and a tested KaTeX override; the dated audit has zero known vulnerabilities.
