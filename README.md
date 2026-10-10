# RX-8 Telemetry Android

Android app in development for viewing live vehicle data and recording trips without an Internet connection.

**Current implementation: Bluetooth Classic discovery and connection, read-only live OBD acquisition, and a separate simulator. Vehicle identity, saved trips, and API integration are not implemented yet.**

The simulator uses deterministic synthetic readings and supports normal operation, timeouts, and missing data.
Connect, disconnect, and reset controls make demonstrations repeatable.
Sampling runs only while the Simulator tab is visible in the foreground; this foundation does not record in the background.

The Bluetooth screen searches for nearby devices, displays names and MAC addresses, and allows selection of paired or newly discovered Classic devices.
It attempts a secure RFCOMM/SPP connection; Android can request pairing during connection.
After connecting, choose **Read live data** to initialize the adapter, detect its protocol, and discover supported parameters.
The first six decoded parameters are engine speed, vehicle speed, coolant and intake air temperatures, throttle position, and engine load, when reported as supported.
Readings retain their ECU source and timestamp. Missing, malformed, unsupported, and stale readings are distinct from valid zero values.
Connections close when leaving the Adapter tab or putting the app in the background; reconnect manually when ready.

On October 10, 2026, the user reported that the app worked after testing the simulator foundation.
The user confirmed that the Samsung A53 runs Android 16. The paired-only Bluetooth build failed to list the adapter.
The user reports that Car Scanner finds it through an in-app search with plain Bluetooth selected, displaying OBDII and its MAC address.
The user subsequently confirmed that the Bluetooth connection works, and PR #13 is merged. No detailed lifecycle or ECU response results have been reported.
The new OBD acquisition engine still requires a real-car test; available parameters and effective sampling rates remain unverified on the RX-8.
There are no published APK releases or deployed APIs.

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
[RX8-002](https://github.com/gonibix23/rx8-telemetry-android/issues/2) records the completed foundation and successful user-reported simulator test on the Samsung A53 running Android 16.
[RX8-003](https://github.com/gonibix23/rx8-telemetry-android/issues/3) records the successful user-reported Bluetooth connection and remaining transport checks.
[RX8-004](https://github.com/gonibix23/rx8-telemetry-android/issues/4) adds the read-only OBD engine and tracks pending real readings.
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
