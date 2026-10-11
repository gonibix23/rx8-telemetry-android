# Agent guidelines

## Context and scope

Read README.md, docs/product.md, docs/architecture.md, and the issue before changing code.
Initial project: European 2004 Mazda RX-8, 231 metric horsepower (CV); Samsung A53 running Android 16; iLC B0BDRHRR22.
Priority: live data and local trip recording. Prepare for an API without deploying a backend.
The repository contains a simulator, Bluetooth Classic/SPP discovery, service-owned read-only ELM327 acquisition, Room vehicle profiles with optional manual/OBD VINs, a live dashboard with favorites, offline trip recording/history/exports, and local API models/outbox preparation. Real API integration remains disabled.
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
- CI uses full-SHA action pins and the explicit Ubuntu 24.04 runner baseline. Preserve minimum permissions, non-persisted checkout credentials, basic Gradle caching, archived artifacts, retention periods, and test/screenshot failure propagation. Validate runtime/runner changes with the full Android 16 suite and inspect all ten screenshots and final annotations. CI-only maintenance does not require a new app version or hardware gate.
- The user confirmed Bluetooth on October 10, 2026 and live readings on October 11, 2026; PRs #13 and #14 are merged. The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed correct readings in 0.5.0-dev. Individual sensors, ECU identifiers, measured rates, sustained lifecycle behavior, VIN retrieval, and profile persistence are not established by these reports. RX8-001, RX8-004, and remaining hardware checks stay open.
- RX8-004 adds explicit user-started, read-only Mode 01 requests and supported-PID discovery. Use automatic protocol detection, preserve ECU source identities, and never manufacture sensor support or values. BLE transport, GPS tracking, Internet access, fault clearing, and actuator commands remain outside scope.
- RX8-005 stores explicit profiles and active selection in Room. Keep the UUID unchanged when renaming or adding a VIN; reject duplicate/different VIN reassignment and require explicit selection or creation. Alias length is 1-60 characters after trimming. VIN validation is structural only.
- Read VIN is optional and explicitly requested during a live session. Supported reconstruction is limited to CAN's 20-byte Mode 09 PID 02 response and five numbered legacy packets. Do not claim general ISO-TP, headerless, extended-address, or all-adapter support.
- Mask VINs by default, retain manual/OBD provenance, and never save a detected candidate without an explicit user decision. Profiles, selection, preferences, and explicitly recorded trips persist. Backups/device transfer are excluded; trip export is available but import/restore is not.
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
- RX8-007 / 0.7.0-dev freezes the selected profile UUID/name at manual trip start. Save every fresh controller callback through the bounded ordered recorder, never a conflated UI flow; do not resave cached/stale readings. Preserve UTC, monotonic elapsed time, per-trip sequences, quality, source, and units. Invalid values remain null. Room v2 migrates v1 explicitly without losing profiles or selection.
- Stop recording preserves live acquisition. Disconnect/notification Stop commits a completed trip before releasing started service ownership. Loss/errors finish an interrupted trip; reconnect and trip restart remain manual. Process startup marks unfinished trips interrupted using only the last saved sample, not an invented termination time. Keep the process-owned writer independent of Activity/service binding.
- Exports and summaries use only terminal trips with bounded sample pages; keep distance estimates on one deterministic ECU and never bridge invalid or greater-than-five-second intervals. Exports omit VIN/MAC, preserve identity/quality, and require an explicit document destination. Delete only after confirmation, and reject active-trip deletion. Keep storage failures visible and never report an uncommitted start/finish/export as success.
- The user broadly confirmed 0.6.0-dev worked and PR #18 is merged. Detailed dashboard scenarios remain unverified until reported.
- The user subsequently confirmed 0.7.0-dev worked perfectly, and PR #19 is merged. Record broad success without inferring measured recording durations, provider-specific export results, interruption/storage scenarios, or battery use. Detailed RX8-007 checks remain open.
- RX8-008 / 0.8.0-dev adds a durable sample-reference queue, metadata revisions, versioned wire models, immutable prepared requests, and isolated synchronization tests. Samples and their pending work must commit together; migrate existing samples without destructive fallback. Adding a VIN must retain the vehicle UUID and increment metadata revision, not rewrite sent payloads.
- Uploads remain disabled: no INTERNET permission, endpoint, credentials, worker, production coordinator, or test-server toggle. A disabled transport or non-isolated store must return before leasing/mutating work. Fake ACKs belong only to isolated tests and cannot mark the user's real records uploaded.
- Acknowledge samples only after validated per-sample stored/already_stored results; reject foreign, duplicate, or mismatched ACKs atomically. Lost responses retry identical payloads/IDs. Partial ACKs and oversized batches produce new batches while preserving previously attempted bodies. Respect leases, bounded paging/payloads, retry delays, and permanent blocks.
- Preserve error quality on the wire, including no_data and adapter_error; map Celsius explicitly to Cel. Legacy recording app versions remain unknown/null; never replace them with the current app version. Unconfirmed vehicle metadata stays null. Local export version 1 remains unchanged and includes saved readings regardless of queue state.
- Trips shows uploads disabled and last-refreshed queue counts. A failed queue query must not become zero or prevent recording/history/export. New 0.8.0-dev upgrade and queue UI checks remain pending until reported.
- The user broadly confirmed 0.8.0-dev worked correctly and PR #20 is merged. RX8-008 software preparation is complete; individual upgrade/export/deletion scenarios remain unreported. Never infer a real API or server test from this report.
- RX8-010 / issue #21 / 0.9.0-dev adds a captured capabilities/display-state report. Observe existing Mode 01 discovery only: no extra commands, cumulative outcome counters, raw logging, new sensor decoding, or proprietary queries. Advertised support is different from decoder availability and a valid current reading. Initial no-data placeholders and ticker/stale updates are display states, not request outcomes.
- Build reports from a bounded, deep-immutable allowlisted DTO. Exclude VINs/candidates, Bluetooth names/MACs, profile/vehicle/trip identifiers/aliases, raw logs, adapter identity, and free-form errors. Recognize protocols and ECU address formats explicitly. Retain valid zero, error/null, and stopped/stale semantics. Report timestamps and latest sensor values are still potentially private.
- Capture previews explicitly and freeze the selected body before document selection. Register the picker above tab navigation, retain pending data across Activity recreation, and fail clearly after process loss. Only write/flush/close completion is export success. Capability previews do not acquire screen-awake ownership, issue queries, or modify trips/queue state; Room v3 and local trip export v1 remain unchanged.
- Clearly state completed tests, pending tests, and limitations.
- The user explicitly requested continued development with phone validation deferred. PR #22 / 0.9.0-dev is merged on verified software checks; issue #21 retains its unreported phone scenarios. Do not impose a phone-test gate for every increment or infer new hardware evidence. Issue #16 was closed at the user's request after all its acceptance checkboxes were completed; no measured test log was supplied.
- RX8-012 / 0.10.0-dev reviews saved terminal-trip samples by metric and ECU, independently of live capabilities, favorites, or the active profile. Bound the series index, sample pages, and chart points. Retain valid zero and negative Celsius; wrong units, invalid quality/value/time, and intervals over five seconds must break coverage. Compute selected-series statistics from full paged data, preserve existing overview distance semantics, and reject stale asynchronous results after selection/trip changes. Room v3, export v1, OBD acquisition, and disabled API preparation remain unchanged.
- The user broadly confirmed 0.10.0-dev works well and PR #25 is merged. Individual chart/ECU, rotation, and export scenarios were not described; preserve remaining detailed checks without holding verified software development for another phone test.
- Do not mark an issue complete until its criteria are met.
- Preserve other contributors' changes and do not force-push, delete resources, or change visibility on your own initiative.
