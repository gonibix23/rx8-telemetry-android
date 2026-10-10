# Agent guidelines

## Context and scope

Read README.md, docs/product.md, docs/architecture.md, and the issue before changing code.
Initial project: European 2004 Mazda RX-8, 231 metric horsepower (CV); Samsung A53; iLC B0BDRHRR22.
Priority: live data and local trip recording. Prepare for an API without deploying a backend.
The repository contains an initial Android simulator foundation. Real Bluetooth/OBD acquisition, persistence, trip recording, and API integration remain planned.
Read docs/development.md for the pinned toolchain and current build commands. Never claim that a check passed or that hardware was validated without evidence.

## Language

Use English for documentation, source comments, UI text, issues, pull requests, and commit messages unless the user explicitly changes this requirement.

## Implementation

- Keep Bluetooth transport, OBD protocol, persistence, presentation, and synchronization separate.
- Use an OBD command queue with cancellation, timeouts, and bounded retries.
- Discover capabilities and preserve each sample's source, unit, timestamp, and quality.
- A missing or invalid reading is not zero. Indicate stale readings.
- Do not hardcode a protocol, proprietary PID, or sampling rate as supported without evidence.
- Use a permanent UUID for each vehicle; VIN is optional. Do not identify the vehicle by its adapter.
- Save locally before attempting synchronization. Confirm delivery only after an ACK.
- Identify simulator data clearly and keep it separate from real data.
- Do not add actuator control, clearing fault codes, or ECU reprogramming to the current scope.
- Do not commit real VINs, tokens, signing keys, databases, or captures that have not been anonymized.

## Workflow and validation

- Implement one issue per branch and keep PRs focused.
- Update documentation when the scope or contract changes.
- Run relevant checks. For documentation: `npm ci` and `npm run lint:docs`.
- For Android or telemetry changes on Windows: `.\gradlew.bat :core:telemetry:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. On macOS/Linux, use `./gradlew` with the same tasks.
- Keep meaningful unit tests, Android lint, and debug assembly in the validation workflow. Add parser, persistence, and synchronization tests as those features are implemented.
- Do not claim A53 installation or physical compatibility based on a build or simulator. Keep RX8-002 open until its human device check passes; RX8-001 tracks the real adapter and car.
- The current simulator samples only in the foreground. Do not describe it as background trip recording.
- Clearly state completed tests, pending tests, and limitations.
- Do not mark an issue complete until its criteria are met.
- Preserve other contributors' changes and do not force-push, delete resources, or change visibility on your own initiative.
