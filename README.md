# RX-8 Telemetry Android

Planned Android app for viewing live vehicle data and recording trips without an Internet connection.

**Status: planning and repository preparation. There is no buildable app, APK, or deployed API yet.**

## Reference vehicle and equipment

- 2004 Mazda RX-8, 231 metric horsepower (CV), European market.
- Samsung Galaxy A53; Android version still to be checked.
- iLC OBD2 Bluetooth adapter, ASIN B0BDRHRR22. [Selected product](https://www.amazon.es/dp/B0BDRHRR22).
- Classic Bluetooth and ELM327-compatible commands are the initial assumptions; both must be checked with the actual adapter.

The goal is to display **all available and verified data** from this vehicle. OBD2 does not guarantee access to every module or sensor.

## First version

- Connect the adapter and discover the supported protocol and parameters.
- Live dashboard with units and the status of each reading.
- Vehicle profile with a permanent ID and an optional vehicle identification number (VIN).
- Manual trip start and stop, local storage, history, charts, and export.
- Recording with the screen locked, managed as an explicit session.
- Data models and a queue for a future API, with uploads disabled.

Fault diagnostics, Mazda-specific sensors, GPS/maps, and a server are deferred to later phases. The first version does not include actuator control, ECU reprogramming, or clearing fault codes.

## Documentation

| Document | Contents |
| --- | --- |
| [Product](docs/product.md) | Scope, screens, and acceptance criteria |
| [Architecture](docs/architecture.md) | Android, Bluetooth, OBD, storage, and synchronization |
| [API contract](docs/api-contract.md) | Future integration design; not an existing service |
| [Roadmap](docs/roadmap.md) | Phases, priorities, and dependencies |
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

## Checking this repository

Documentation checks require Node.js 22 or later and npm.

```sh
npm ci
npm run lint:docs
```

These checks review documentation. They do not build Android or validate the adapter. The Android foundation, setup instructions, and tests will be added in RX8-002.

## License

Private repository. No distribution license has been selected.
