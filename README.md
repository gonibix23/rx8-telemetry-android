# RX-8 Telemetry Android

Android app in development for viewing live vehicle data and recording trips without an Internet connection.

**Current implementation (0.15.0-dev): clean Live viewing/editing modes, saved one- or two-column layouts, and a compact floating sensor overlay. Android records offline; the separate anonymous MongoDB API receives finished trips.**

Live opens in normal mode with the configured readings. Choose Edit for sensor search, one/two columns, card appearance/size/order and floating-panel settings; Done returns to the readings. Compact cards share rows in two-column mode, large cards span the width, and narrow windows or larger fonts use one column when needed. In Edit mode, long-press a card or choose its Edit action for digital, dial or bar presentation. All 120 implemented parameters are selectable, including RPM, speed and coolant; unavailable selections are labelled honestly. Up to six metrics can appear over a navigation app after explicit Android overlay permission. The compact overlay shows parameter, value/unit and age; drag it to move, or hold it still to close. In a scrolling overlay, drag a parameter label and scroll from the value area.

Save a favourite in Adapter to retry every 30 seconds while the app is visible. Disconnect/Stop pauses retries until Resume. Without a favourite, the original one-time last-adapter startup attempt remains. Adapter contains Bluetooth controls and adapter identification/protocol details; vehicle actions, VIN review and capability reports are available in Live's Edit mode. Trips uses a Chart parameter dropdown with separate ECU selection.

An upload banner in Adapter, Trips and Vehicles shows waiting, sending, failure and confirmed delivery. Confirmation includes terminal-trip metadata, current vehicle metadata and every sample acknowledgment; empty trips need metadata acknowledgment too. Recording and retained local history remain independent of network access. The API emits structured request/error logs without request bodies, VINs, credentials or raw resource paths.

The four tabs are **Adapter**, **Live**, **Trips**, and **Vehicles**, with single-line labels. The Simulator tab has been removed; its RPM dial now displays real per-ECU readings in Live. Synthetic data remains confined to development fixtures.

Without an explicit favourite, a new visible app process attempts the last successful Bluetooth Classic adapter once when permission and the radio are available. A favourite opts into periodic visible-app retries. Adapter retains nearby discovery, manual selection, Connect, Disconnect and pause/resume. Android may request pairing. No new attempt starts in the background; the service does not restore a killed session.

Once the socket connects, initialization, automatic protocol detection and supported-PID discovery start directly. Bluetooth connected remains distinct from successful ECU communication. Only advertised, implemented read-only parameters are queried. The expanded catalog includes fuel trims, airflow, ignition timing, pressures, oxygen-sensor values, temperatures, counters and other standard scalar readings. Actual RX-8 coverage depends on its responses; proprietary and unimplemented parameters remain clearly identified. Missing, malformed and stale readings are never fabricated zeros.

Select the intended vehicle in **Vehicles** once. Its permanent UUID works without a VIN; an optional VIN can be entered manually or explicitly read and reviewed later. The adapter never identifies the car. The selection persists, and subsequent connections automatically start telemetry journeys for that profile. The trip freezes its UUID and name and includes connection/initialization time. Initial display placeholders are not samples; the first actual reading is retained. If no profile is selected, live reading can continue and the app explains that a profile is needed; recording begins when selected, without backfilling earlier data.

**Stop recording** saves the journey while live readings continue, and suppresses automatic recording for that connection. The manual start control can resume with a new journey. **Disconnect** or notification **Stop** completes the active trip and closes the session; a lost connection or acquisition failure interrupts it. A new connection begins a new journey. Startup recovery marks unfinished trips interrupted at their last saved sample, without inventing a process-kill time.

Acquisition and recording continue across tabs, backgrounding, screen lock and Activity recreation through the foreground service. The visible Adapter and Live screens stay awake during real acquisition. Notification permission denial does not prevent connecting; the app provides recovery guidance. Active acquisition consumes battery, and no battery-optimization exemption is requested.

**Live** keeps each ECU separate in configurable cards. Cards preserve units, age, quality, valid zero and unavailable choices. Layout choices persist for the app. In Live > Edit > Choose sensors, Search sensors filters names or hexadecimal PIDs immediately. Clear restores the choices, and searching keeps hidden selections. **Vehicle capabilities** in Live's Edit mode captures the existing discovery and displayed readings into an explicit JSON report without extra vehicle queries; see [Capability reports](docs/capability-report.md).

**Trips** records every fresh supported reading, independently of visible cards or favorite choices. History shows up to 50 trips per page, with Newer/Older/Newest controls and a vehicle filter independent of recording selection. Refresh, details, tabs and Activity recreation preserve browsing context. Finished trips provide metric/ECU charts, full-stream statistics, estimated distance, JSON/CSV export to a chosen document destination, and confirmed deletion. Distance is an estimate, not an odometer reading.

Recording, charts and history work offline. Trips stay saved on the phone; a configured HTTPS API receives completed/interrupted journeys when network access is available. With no server configured, data waits locally. MongoDB credentials belong only to the API. No GPS is collected. Exports omit VIN/MAC but include vehicle IDs/names, timestamps and readings. Profiles, selections, favorites and trips persist; backups/device transfer and import/restore are not implemented. Clearing app data or reinstalling loses saved data.
On October 10, 2026, the user reported that the app worked after testing the simulator foundation.
The user confirmed that the Samsung A53 runs Android 16. The paired-only Bluetooth build failed to list the adapter.
The user reports that Car Scanner finds it through an in-app search with plain Bluetooth selected, displaying OBDII and its MAC address.
The user subsequently confirmed Bluetooth works. On October 11, 2026, the user reported that live readings worked, and PR #14 is merged.
The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed that 0.5.0-dev reads data correctly. These reports do not confirm each of the original six sensors, ECU identifiers, measured rates, or sustained lifecycle behavior. RX8-001, RX8-004, and the remaining hardware checks stay open; VIN retrieval and profile persistence still need device validation.
The user also reported the screen sleeping when idle and readings disconnecting after backgrounding in 0.5.0-dev. That matched the previous foreground-only connection policy; actual Bluetooth bond removal was not confirmed.
The user subsequently confirmed that 0.5.1-dev worked, providing broad confirmation of the background/screen fix in merged [PR #17](https://github.com/gonibix23/rx8-telemetry-android/pull/17). The separate [hardware checklist](docs/hardware-validation.md) tracks broader evidence; measured durations, battery use, notification scenarios, and actual bond removal are not established by that report. There are no published APK releases. The owner subsequently added the API deployment workflow in PRs #39-41.
The user also confirmed that 0.6.0-dev works perfectly; [PR #18](https://github.com/gonibix23/rx8-telemetry-android/pull/18) is merged. This is broad dashboard-build confirmation, not a report of each favorite/ECU scenario. The user subsequently confirmed that 0.7.0-dev works perfectly, and [PR #19](https://github.com/gonibix23/rx8-telemetry-android/pull/19) is merged. The user also confirmed that 0.8.0-dev worked correctly, and [PR #20](https://github.com/gonibix23/rx8-telemetry-android/pull/20) is merged. [Issue #16](https://github.com/gonibix23/rx8-telemetry-android/issues/16) is closed at the user's request with all checks complete. Measured durations, individual upgrade/export/storage scenarios, and battery use remain unreported. [PR #22](https://github.com/gonibix23/rx8-telemetry-android/pull/22) is merged for 0.9.0-dev; the user explicitly deferred its phone validation and requested continued development. Capability reports and the new 0.10.0-dev trip charts retain their pending phone checklists.

## Reference vehicle and equipment

- 2004 Mazda RX-8, 231 metric horsepower (CV), European market.
- Samsung Galaxy A53 running Android 16, confirmed by the user.
- iLC OBD2 Bluetooth adapter, ASIN B0BDRHRR22. [Selected product](https://www.amazon.es/dp/B0BDRHRR22).
- Plain Bluetooth is the user-reported Car Scanner setting, and the app's Bluetooth connection now works according to the user. ELM327/ECU responses still need checking with the actual adapter.

The goal is to display **all available and verified data** from this vehicle. OBD2 does not guarantee access to every module or sensor.
A working simulator does not prove that the adapter or car supports any particular parameter.

## MVP scope

- Automatically retry an explicit favourite while visible, or attempt the last successful adapter once per process; retain manual controls.
- Automatically discover the protocol and supported parameters, then read the implemented sensors.
- Modular per-ECU dashboard with selectable sensors, three display styles, sizes, ordering and an optional floating overlay.
- Vehicle profile with a permanent ID and an optional vehicle identification number (VIN).
- Automatic journeys for the selected vehicle, with Stop recording, manual resume, local history, charts, and export.
- Service-owned acquisition and recording through screen lock, with explicit Disconnect and notification Stop controls.
- Durable local uploads with a configurable HTTPS URL, completed-trip gating and network-constrained retries.
- Separate TypeScript API in `api/`, storing vehicles, sessions and readings in MongoDB Atlas without a login.

These features describe the implemented MVP scope; detailed phone checks remain pending.
Fault diagnostics, Mazda-specific sensors and GPS/maps are deferred. The owner configured API image publication and Portainer redeployment in PRs #39-41.
The first version does not include actuator control, ECU reprogramming, or clearing fault codes.

## Getting started

Open the repository in Android Studio with JDK 17 or 21 and Android SDK Platform 36.
Android development uses `:app` and the pure Kotlin `:core:telemetry` module. API development has its own package, lockfile, tests and deployment files in [`api/`](api/README.md); it does not depend on the Android toolchain.

See [Development](docs/development.md) for pinned tool versions, SDK setup, automatic session behavior, build commands, and pending device checks.
Build and test results belong in the relevant PR; setup instructions alone are not evidence that checks passed.

## Documentation

| Document | Contents |
| --- | --- |
| [Development](docs/development.md) | Android setup, automatic sessions, commands, and device validation |
| [Product](docs/product.md) | Target scope, screens, and acceptance criteria |
| [Architecture](docs/architecture.md) | Transport, OBD, local records and completed-trip synchronization |
| [API contract](docs/api-contract.md) | Implemented anonymous v1 API, wire acknowledgments and local export formats |
| [Capability reports](docs/capability-report.md) | Snapshot semantics, privacy exclusions, and explicit JSON export |
| [Mazda parameter research](docs/mazda-parameters.md) | Candidate signals, original wire definitions, evidence gaps and validation plan |
| [Roadmap](docs/roadmap.md) | Phases, current status, priorities, and dependencies |
| [Hardware validation](docs/hardware-validation.md) | Evidence needed to confirm compatibility |
| [Contributing](CONTRIBUTING.md) | Issues, branches, PRs, reviews, and releases |
| [Agent guidelines](AGENTS.md) | Implementation and verification rules |
| [Privacy and security](SECURITY.md) | Handling VINs, logs, and credentials |

## Tracking

- [Issues](https://github.com/gonibix23/rx8-telemetry-android/issues)
- [Milestones](https://github.com/gonibix23/rx8-telemetry-android/milestones)
- [New issue](https://github.com/gonibix23/rx8-telemetry-android/issues/new/choose)
- [Pull requests](https://github.com/gonibix23/rx8-telemetry-android/pulls)

Each issue has a scope and acceptance criteria. Implementation changes go through a PR linked to an issue.
[RX8-002](https://github.com/gonibix23/rx8-telemetry-android/issues/2) records the completed foundation and successful user-reported simulator test on the Samsung A53 running Android 16.
[RX8-003](https://github.com/gonibix23/rx8-telemetry-android/issues/3) records the successful user-reported Bluetooth connection and remaining transport checks.
[RX8-004](https://github.com/gonibix23/rx8-telemetry-android/issues/4) records the general live-reading success and pending detailed hardware checks.
[RX8-005](https://github.com/gonibix23/rx8-telemetry-android/issues/5) tracks permanent vehicle profiles and optional VINs, implemented in merged [PR #15](https://github.com/gonibix23/rx8-telemetry-android/pull/15). Its device checks remain open.
[RX8-006](https://github.com/gonibix23/rx8-telemetry-android/issues/6) tracks the dashboard, saved favorites, and pending A53 layout checks.
[RX8-007](https://github.com/gonibix23/rx8-telemetry-android/issues/7) tracks trip recording, offline history, exports, and pending device validation.
[RX8-008](https://github.com/gonibix23/rx8-telemetry-android/issues/8) tracks versioned API models, the persistent queue, and isolated retry/ACK tests. The original preparation shipped without a backend. RX8-017 now implements synchronization; deployment remains deferred.
[RX8-010](https://github.com/gonibix23/rx8-telemetry-android/issues/21) adds the capabilities view and report to help collect evidence for future sensor work.
[RX8-012](https://github.com/gonibix23/rx8-telemetry-android/issues/24) adds offline trip charts selectable by recorded metric and ECU.
[RX8-014](https://github.com/gonibix23/rx8-telemetry-android/issues/28) adds bounded history pages, an independent vehicle filter, and an index-only Room 3-to-4 upgrade in merged [PR #30](https://github.com/gonibix23/rx8-telemetry-android/pull/30). Existing saved trips and pending work remain local.
[RX8-015](https://github.com/gonibix23/rx8-telemetry-android/issues/29) is merged in [PR #31](https://github.com/gonibix23/rx8-telemetry-android/pull/31) for 0.12.0-dev: automatic connection, acquisition and journeys, broader standard readings, the real RPM dial, and four single-line tabs.
[PR #25](https://github.com/gonibix23/rx8-telemetry-android/pull/25) is merged after the user reported that 0.10.0-dev works well. This is broad build confirmation; individual phone scenarios remain tracked in RX8-012. [RX8-011](https://github.com/gonibix23/rx8-telemetry-android/issues/23) maintains the automated build/test environment without changing the app version.
[RX8-016](https://github.com/gonibix23/rx8-telemetry-android/issues/32) adds sensor search and selection feedback in 0.12.1-dev.
[RX8-017](https://github.com/gonibix23/rx8-telemetry-android/issues/34) adds the Atlas API and offline completed-trip uploads. The user broadly confirmed 0.12.1-dev works perfectly before requesting this increment.
[RX8-001](https://github.com/gonibix23/rx8-telemetry-android/issues/1) tracks separate validation with the real car and adapter.

## Documentation checks

Documentation checks require Node.js 22 or later and npm.

```sh
npm ci
npm run lint:docs
```

These commands check Markdown. Android build, unit-test, and lint commands are listed in [Development](docs/development.md).

## License

Original project code and documentation are licensed under the [MIT License](LICENSE). This public repository includes both the Android app and API.
Third-party dependencies and the Gradle wrapper retain their own licenses and copyright notices. Externally linked manuals and reference documents are not covered by this grant.

[RX8-020](https://github.com/gonibix23/rx8-telemetry-android/issues/42) tracks 0.14.0-dev and the owner-supplied private trip validation. New overlay, customisation and reconnect scenarios still need A53 validation.
