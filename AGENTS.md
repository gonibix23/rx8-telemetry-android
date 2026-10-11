# Agent guidelines

## Context and scope

Read README.md, docs/product.md, docs/architecture.md, and the issue before changing code.
Initial project: European 2004 Mazda RX-8, 231 metric horsepower (CV); Samsung A53 running Android 16; iLC B0BDRHRR22.
Priority: live data and local trip recording. Prepare for an API without deploying a backend.
The repository contains a simulator, Bluetooth Classic/SPP discovery, service-owned read-only ELM327 acquisition, Room vehicle profiles with optional manual/OBD VINs, and a live dashboard with app-wide favorites. Trip recording, telemetry persistence, and API integration remain planned.
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
- The user confirmed Bluetooth on October 10, 2026 and live readings on October 11, 2026; PRs #13 and #14 are merged. The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed correct readings in 0.5.0-dev. Individual sensors, ECU identifiers, measured rates, sustained lifecycle behavior, VIN retrieval, and profile persistence are not established by these reports. RX8-001, RX8-004, and remaining hardware checks stay open.
- RX8-004 adds explicit user-started, read-only Mode 01 requests and supported-PID discovery. Use automatic protocol detection, preserve ECU source identities, and never manufacture sensor support or values. BLE transport, GPS tracking, Internet access, fault clearing, and actuator commands remain outside scope.
- RX8-005 stores explicit profiles and active selection in Room. Keep the UUID unchanged when renaming or adding a VIN; reject duplicate/different VIN reassignment and require explicit selection or creation. Alias length is 1-60 characters after trimming. VIN validation is structural only.
- Read VIN is optional and explicitly requested during a live session. Supported reconstruction is limited to CAN's 20-byte Mode 09 PID 02 response and five numbered legacy packets. Do not claim general ISO-TP, headerless, extended-address, or all-adapter support.
- Mask VINs by default, retain manual/OBD provenance, and never save a detected candidate without an explicit user decision. Profiles, selected vehicle, and dashboard preferences are persisted; no telemetry history exists yet. Backups/device transfer are excluded, and export/restore is not implemented.
- Preserve the Room schema export and require explicit migrations for future schema versions. Do not add a destructive database fallback.
- Copy every APK ready for user testing with `scripts/export-test-apk.ps1`, using the ignored `apk-delivery.local.json` destination or the user's requested directory. Keep that machine-specific path outside committed files; document the version delivered.
- A connected RFCOMM socket is not proof of ECU communication. Preserve the UI distinction and manual reconnection. RX8-001 tracks physical adapter and car evidence.
- Issue #16 / 0.5.1-dev moves an explicitly started connection into a non-exported, started and bound connectedDevice foreground service. Keep attempts, sockets, and acquisition alive across tabs/background/lock/recreation; cancel discovery when leaving Adapter. New Connect, live-data, and VIN actions still require the visible UI.
- Keep the screen awake only for visible Adapter or Dashboard real initialization/discovery/live acquisition; simulator previews must not hold that flag. Hold and renew a bounded partial wake lock only during acquisition; stop/error/service cleanup must release resources even while the UI remains bound.
- Preserve notification Stop/open-Adapter controls without sensitive identifiers. Notification permission denial must not block connection; show recovery guidance. Do not request battery-optimization exemptions.
- Keep START_NOT_STICKY, no automatic reconnect, and no process-death session restoration. Background live data is not trip recording or telemetry persistence.
- The user reported idle screen sleep/background disconnection in 0.5.0-dev. Actual bond removal is unconfirmed; the app has no removeBond call. The user subsequently confirmed 0.5.1-dev worked and PR #17 is merged, supporting broad success of the fix. Do not infer measured durations, battery behavior, detailed notification scenarios, or bond-entry checks from that report.
- RX8-006 / 0.6.0-dev displays supported RPM, speed, and coolant in fixed primary positions; extra favorites are intake temperature, throttle position, and load. Persist only stable metric IDs in app-wide SharedPreferences, independently of vehicle/ECU identity. Keep ECU readings separate, show unavailable saved favorites honestly, and share cards with an explicitly synthetic simulator preview. No new sensor support, telemetry persistence, or automatic acquisition is implied.
- The current simulator samples only in the foreground. Do not describe it as background trip recording.
- Clearly state completed tests, pending tests, and limitations.
- Do not mark an issue complete until its criteria are met.
- Preserve other contributors' changes and do not force-push, delete resources, or change visibility on your own initiative.
