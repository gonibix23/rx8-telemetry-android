# RX-8 Telemetry Android

Android app in development for viewing live vehicle data and recording trips without an Internet connection.

**Current implementation: a simulator and a provisional connection screen for paired Bluetooth Classic devices. ECU/OBD queries, saved trips, and API integration are not implemented yet.**

The simulator uses deterministic synthetic readings and supports normal operation, timeouts, and missing data.
Connect, disconnect, and reset controls make demonstrations repeatable.
Sampling runs only while the Simulator tab is visible in the foreground; this foundation does not record in the background.

The Bluetooth screen selects an already-paired Classic or dual-mode device and attempts a secure RFCOMM/SPP connection.
It sends no application bytes or ELM/OBD commands and distinguishes Bluetooth connection from ECU communication, which is not checked.
Connections close when leaving the Adapter tab or putting the app in the background; reconnect manually when ready.

On October 10, 2026, the user reported that the app worked after testing the simulator foundation.
The reference phone is a Samsung A53; its Android version has not yet been supplied.
That report does not validate the new Bluetooth transport or compatibility with the adapter and car.
There are no published APK releases or deployed APIs.

## Reference vehicle and equipment

- 2004 Mazda RX-8, 231 metric horsepower (CV), European market.
- Samsung Galaxy A53; Android version still to be checked.
- iLC OBD2 Bluetooth adapter, ASIN B0BDRHRR22. [Selected product](https://www.amazon.es/dp/B0BDRHRR22).
- Classic Bluetooth and ELM327-compatible commands are the initial assumptions; both must be checked with the actual adapter.

The goal is to display **all available and verified data** from this vehicle. OBD2 does not guarantee access to every module or sensor.
A working simulator does not prove that the adapter or car supports any particular parameter.

## Planned MVP

- Connect the adapter and discover the supported protocol and parameters.
- Live dashboard with units and the status of each reading.
- Vehicle profile with a permanent ID and an optional vehicle identification number (VIN).
- Manual trip start and stop, local storage, history, charts, and export.
- Recording with the screen locked, managed as an explicit session.
- Data models and a queue for a future API, with uploads disabled.

These features describe the target MVP; opening a Bluetooth socket does not yet acquire vehicle data.
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
| [Architecture](docs/architecture.md) | Implemented foundation and planned Android, OBD, storage, and synchronization layers |
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
[RX8-002](https://github.com/gonibix23/rx8-telemetry-android/issues/2) records the successful user-reported app test; the phone's Android version is still required.
[RX8-003](https://github.com/gonibix23/rx8-telemetry-android/issues/3) adds provisional Bluetooth transport and still requires real-adapter validation.
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
