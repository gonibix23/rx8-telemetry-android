# RX-8 Telemetry Android

Android app in development for viewing live vehicle data and recording trips without an Internet connection.

**Current implementation (0.8.0-dev): a durable local queue and versioned models for the future API, alongside offline trip recording/history/exports, live data, Bluetooth discovery, background acquisition, saved vehicles, and a separate simulator. Uploads remain disabled.**

The simulator uses deterministic synthetic readings and supports normal operation, timeouts, and missing data.
Connect, disconnect, and reset controls make demonstrations repeatable.
Sampling runs only while the Simulator tab is visible in the foreground; this foundation does not record in the background.

The Bluetooth screen searches for nearby devices, displays names and MAC addresses, and allows selection of paired or newly discovered Classic devices.
It attempts a secure RFCOMM/SPP connection; Android can request pairing during connection.
After connecting, choose **Read live data** to initialize the adapter, detect its protocol, and discover supported parameters.
The first six decoded parameters are engine speed, vehicle speed, coolant and intake air temperatures, throttle position, and engine load, when reported as supported.
Readings retain their ECU source and timestamp. Missing, malformed, unsupported, and stale readings are distinct from valid zero values.
A connection started with **Connect** continues across tabs, backgrounding, screen lock, and Activity recreation. **Read live data** still starts acquisition explicitly; discovery stops when leaving Adapter.
The visible Adapter and Dashboard screens stay awake during real initialization and live acquisition. Use **Disconnect** in the app or **Stop** in the ongoing session notification to end the session. Opening the notification returns to Adapter.
On Android 13+, notification access is requested on the first Connect action. Denial does not block connecting; the app explains how to enable the notification or stop from Adapter.
Active acquisition uses additional battery, including while the screen is locked. No battery-optimization exemption is requested. A stopped, failed, or process-terminated session requires a new manual connection.

In **Live**, RPM, speed, and coolant temperature occupy fixed primary positions when the ECU reports them as supported. Choose favorites from intake temperature, throttle position, and engine load. Each ECU keeps its own readings, units, age, and status.
Favorite choices are saved for the app, independent of the selected vehicle and ECU. A saved favorite unavailable in the current session stays identified as unavailable instead of becoming zero or silently disappearing. The Simulator tab previews the same cards with an explicit synthetic-data label.

In **Vehicles**, explicitly create a named profile with a permanent UUID, select a saved profile, or rename it. The profile and selection are saved locally in Room.
A VIN is optional: enter it manually or choose **Read VIN** after live acquisition starts, then review a detected candidate before saving it. VINs are masked by default and retain their manual or OBD source.
Adding a VIN keeps the UUID. A matching VIN requires explicit profile selection; a different VIN cannot overwrite an existing profile. The adapter's MAC address never identifies the car.
In **Trips**, choose **Start recording** after selecting a vehicle and starting real live data. The trip keeps that vehicle's UUID and name even if you change profiles. Only new readings are saved, with their source, unit, quality, UTC timestamp, sequence number, and monotonic elapsed time. Recording continues with the screen locked through the existing foreground service.
**Stop recording** saves the trip while live readings continue. **Disconnect** or notification **Stop** saves the trip and closes the connection. A lost connection ends an interrupted trip; reconnect manually and explicitly start a new one. After process termination, the next launch marks unfinished trips interrupted at their last saved reading.
History shows the latest 100 trips offline. Finished trips include a speed chart, peak readings, estimated distance from valid speed intervals, and uncovered time. Distance is not an odometer reading; gaps are never filled with invented values. Export JSON/CSV to a location you choose, or confirm deletion of a trip and its samples.
Trips also shows **Future uploads**, with an explicit **Uploads disabled** label and the last refreshed counts of samples waiting locally or needing attention. Saved samples and pending work commit together. Upgrading includes existing recorded samples in the local queue; it does not send them anywhere. There is no server configuration, upload button, network permission, or scheduled upload worker.
Profiles, selection, favorites, and explicitly recorded trips are persisted. VIN candidates remain temporary until explicitly saved. Backups and device transfer are disabled. Exports omit VIN and Bluetooth address but contain vehicle IDs, names, timestamps, and readings; export is not a restore feature. Clearing app data or reinstalling loses locally saved data and IDs.

On October 10, 2026, the user reported that the app worked after testing the simulator foundation.
The user confirmed that the Samsung A53 runs Android 16. The paired-only Bluetooth build failed to list the adapter.
The user reports that Car Scanner finds it through an in-app search with plain Bluetooth selected, displaying OBDII and its MAC address.
The user subsequently confirmed Bluetooth works. On October 11, 2026, the user reported that live readings worked, and PR #14 is merged.
The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed that 0.5.0-dev reads data correctly. These reports do not confirm all six sensors, ECU identifiers, measured rates, or sustained lifecycle behavior. RX8-001, RX8-004, and the remaining hardware checks stay open; VIN retrieval and profile persistence still need device validation.
The user also reported the screen sleeping when idle and readings disconnecting after backgrounding in 0.5.0-dev. That matched the previous foreground-only connection policy; actual Bluetooth bond removal was not confirmed.
The user subsequently confirmed that 0.5.1-dev worked, providing broad confirmation of the background/screen fix in merged [PR #17](https://github.com/gonibix23/rx8-telemetry-android/pull/17). [Issue #16](https://github.com/gonibix23/rx8-telemetry-android/issues/16) retains detailed A53 checks; measured durations, battery use, notification scenarios, and actual bond removal are not established by that report. There are no published APK releases or deployed APIs.
The user also confirmed that 0.6.0-dev works perfectly; [PR #18](https://github.com/gonibix23/rx8-telemetry-android/pull/18) is merged. This is broad dashboard-build confirmation, not a report of each favorite/ECU scenario. The user subsequently confirmed that 0.7.0-dev works perfectly, and [PR #19](https://github.com/gonibix23/rx8-telemetry-android/pull/19) is merged. Measured recording durations, individual export providers, interruption/storage scenarios, and battery use remain unreported. The new 0.8.0-dev upgrade and queue display need a phone test.

## Reference vehicle and equipment

- 2004 Mazda RX-8, 231 metric horsepower (CV), European market.
- Samsung Galaxy A53 running Android 16, confirmed by the user.
- iLC OBD2 Bluetooth adapter, ASIN B0BDRHRR22. [Selected product](https://www.amazon.es/dp/B0BDRHRR22).
- Plain Bluetooth is the user-reported Car Scanner setting, and the app's Bluetooth connection now works according to the user. ELM327/ECU responses still need checking with the actual adapter.

The goal is to display **all available and verified data** from this vehicle. OBD2 does not guarantee access to every module or sensor.
A working simulator does not prove that the adapter or car supports any particular parameter.

## Planned MVP

- Connect the adapter and discover the supported protocol and parameters.
- Live dashboard with units and the status of each reading.
- Vehicle profile with a permanent ID and an optional vehicle identification number (VIN).
- Manual trip start and stop, local storage, history, charts, and export.
- Recording with the screen locked, managed as an explicit session.
- Data models and a queue for a future API, with uploads disabled.

These features describe the target MVP. Opening a Bluetooth socket alone does not start vehicle acquisition; use Read live data explicitly.
Fault diagnostics, Mazda-specific sensors, GPS/maps, and a server are deferred to later phases.
The first version does not include actuator control, ECU reprogramming, or clearing fault codes.

## Getting started

Open the repository in Android Studio with JDK 17 or 21 and Android SDK Platform 36.
The project contains the Android `:app` module and the pure Kotlin `:core:telemetry` module.

See [Development](docs/development.md) for pinned tool versions, SDK setup, Bluetooth and simulator behavior, build commands, and pending device checks.
Build and test results belong in the relevant PR; setup instructions alone are not evidence that checks passed.

## Documentation

| Document | Contents |
| --- | --- |
| [Development](docs/development.md) | Android setup, simulator, commands, and device validation |
| [Product](docs/product.md) | Target scope, screens, and acceptance criteria |
| [Architecture](docs/architecture.md) | Transport, OBD, local profiles/trips, pending work, and future upload boundaries |
| [API contract](docs/api-contract.md) | Future integration design; not an existing service |
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
[RX8-008](https://github.com/gonibix23/rx8-telemetry-android/issues/8) tracks versioned API models, the persistent queue, and isolated retry/ACK tests. No backend is deployed or contacted.
[RX8-001](https://github.com/gonibix23/rx8-telemetry-android/issues/1) tracks separate validation with the real car and adapter.

## Documentation checks

Documentation checks require Node.js 22 or later and npm.

```sh
npm ci
npm run lint:docs
```

These commands check Markdown. Android build, unit-test, and lint commands are listed in [Development](docs/development.md).

## License

Private repository. No distribution license has been selected.
