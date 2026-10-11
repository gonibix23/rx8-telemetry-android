# Agent guidelines

## Context and scope

Read README.md, docs/product.md, docs/architecture.md, and the issue before changing code.
Initial project: European 2004 Mazda RX-8, 231 metric horsepower (CV); Samsung A53 running Android 16; iLC B0BDRHRR22.
Priority: live data and local trip recording. Prepare for an API without deploying a backend.
The repository contains a simulator, Bluetooth Classic/SPP transport with in-app discovery, and a read-only ELM327/Mode 01 acquisition engine. Vehicle identity, persistence, trip recording, and API integration remain planned.
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
- The user confirmed a successful Bluetooth connection on October 10, 2026 after the discovery APK was delivered; PR #13 is merged. This establishes the reported phone-to-adapter connection, not ECU responses, detailed lifecycle tests, or all RX8-003 criteria. RX8-001 and the remaining hardware checks stay open.
- RX8-004 adds explicit user-started, read-only Mode 01 requests and supported-PID discovery. Use automatic protocol detection, preserve ECU source identities, and never manufacture sensor support or values. BLE transport, GPS tracking, Internet access, fault clearing, and actuator commands remain outside scope.
- Current OBD queries fit single CAN frames; Bluetooth byte fragmentation is reassembled separately. Do not claim VIN or general ISO-TP multi-frame support before implementing and testing it.
- Copy every APK ready for user testing with `scripts/export-test-apk.ps1`, using the ignored `apk-delivery.local.json` destination or the user's requested directory. Keep that machine-specific path outside committed files; document the version delivered.
- A connected RFCOMM socket is not proof of ECU communication. Preserve the UI distinction, foreground-only connection policy, and manual reconnection. RX8-001 tracks physical adapter and car evidence.
- The current simulator samples only in the foreground. Do not describe it as background trip recording.
- Clearly state completed tests, pending tests, and limitations.
- Do not mark an issue complete until its criteria are met.
- Preserve other contributors' changes and do not force-push, delete resources, or change visibility on your own initiative.
