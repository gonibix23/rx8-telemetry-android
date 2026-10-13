# Architecture

## Trips feedback and history correction

Issue #52 routes the Activity-observed durable delivery state through Trips into the existing Uploads panel, rather than composing a banner above other tabs. The panel retains settings/runtime failures, progress and ACK-based confirmation. The bounded Room cursor queries still determine older/newer availability; one history pager uses those results and explains boundaries. No upload transport, acquisition owner, database schema or wire contract changes are required.

## RX8-021 Live presentation modes

Version 0.15.0-dev separates normal viewing from editing in the modular dashboard. Saveable presentation state retains mode/search across tab navigation and recreation; normal is the initial mode. The Activity keeps preference errors visible; upload feedback is now inside Trips > Uploads. Adapter metadata is rendered only in Adapter; Live edit mode retains vehicle actions and reports.

The application-owned ordered preference worker persists a one/two-column choice independently of card configurations. It publishes only confirmed writes and preserves the previous layout on failure; no Room or wire migration is involved. Compact cards form stable lazy rows, large cards span the width, and available width/font scale determine a safe one-column fallback. All rendering uses the same source/quality/age models.

The overlay host renders compact accessible rows. A gesture controller owns the cancellable stationary-hold deadline; movement beyond touch slop, extra pointers, up/cancel, detach and hide invalidate it. Short lists drag from any row; scrolling lists use parameter labels as drag regions and leave values available for scrolling. Accessible dismiss and long-click actions replace the visible Close button. The existing service continues owning acquisition and permission/lifecycle cleanup.

A synthetic integration regression exercises Elm327Session through BluetoothSessionController directly into TripRecorder while changing the actual dashboard preference store, hiding all cards and selecting an unsupported card. Fresh hidden/compound fields from separate ECUs still record, failed values remain null and cached ticker snapshots are not saved. Stop recording commits a terminal journey while live polling continues. Room durability remains covered separately.

## RX8-020 display and delivery changes

Version 0.14.0-dev supersedes fixed primary cards and the last-adapter-only reconnect policy described in the implementation history below. Persistent ordered card configurations extend private preferences without changing Room v4. The legacy basic sensors and saved extra favourites migrate together; afterwards every implemented metric is selectable/removable. Card rendering preserves source, canonical units, age and quality across digital, dial and bar views and two sizes. Display changes never affect acquisition or recorded coverage.

An optional TYPE_APPLICATION_OVERLAY window shares the existing foreground session and its snapshot. Explicit permission/enablement precedes showing it outside the visible app; drag/close and cleanup are owned by a manager attached to that service. Permission revocation, lock, disconnection and destruction remove the window. It does not own another connection, polling loop or GPS feed. Card overlay selection persists; session enablement does not.

The favourite adapter is private and unrelated to vehicle identity. A bounded retry policy feeds the existing visible-Activity connection promotion path every 30 seconds when eligible. Hidden apps, unavailable permission/radio, active attempts/scanning and explicit pause prevent starts. Disconnect/notification Stop pauses until Resume. The no-favourite path retains one last-successful-adapter attempt per process.

Delivery feedback counts terminal trips using one SQL snapshot over durable metadata/sample acknowledgments. It requires current vehicle and terminal-session revisions and every saved sample; missing references, partial ACKs, blocked work and empty metadata-only trips cannot become success by observing zero pending samples. The delivery state refreshes at most once every two seconds and stops observing when the UI is absent; its visible feedback now lives inside Trips > Uploads. Query errors remain unknown, never zero. No queue body, migration or ACK contract changes.

The API logs bounded allowlisted request lifecycle/error events and generated correlation IDs. It does not log raw errors, bodies, headers, actual paths/query strings, VINs or database configuration. The owner-added Portainer pipeline publishes and redeploys API changes after merge; its MongoDB setting can differ from ignored local Atlas configuration.

## Status

Version 0.13.0-dev adds an independently developed Atlas API and optional completed-trip uploads. Sensor search remains available. It implements automatic real-car sessions, a broader verified standard scalar catalog and a real RPM dial. It retains paged history, vehicle filtering, offline charts/exports, capability snapshots, explicit vehicle identity and immutable upload work. Production navigation has four tabs; synthetic telemetry is confined to development fixtures.

The visible app attempts a privately saved successful Classic adapter once per process when permission and Bluetooth are available. A connected socket continues directly into ELM initialization and a journey for the selected persisted vehicle. Socket success remains distinct from ECU communication. Connection/acquisition/recording continue across tabs, background and lock through the foreground service.

The reference hardware is the European 2004 RX-8, 231 PS, Samsung A53 on Android 16 and iLC B0BDRHRR22. The user reported working Bluetooth, live readings over ISO 15765-CAN (11 bit, 500 kbaud), and broad success of earlier increments through 0.10.0-dev. Those reports do not establish new sensor support, individual lifecycle scenarios or measured rates/battery behavior. New phone validation is deferred.

The diagram shows local-first storage and configurable synchronization after trips end. Actual server deployment is deferred. No GPS is collected.

## Components

```mermaid
flowchart LR
  Car[RX-8] <--> Adapter[OBD adapter]
  Adapter <--> Bluetooth[Bluetooth transport]
  Bluetooth <--> Obd[OBD engine]
  Obd --> Live[Live state]
  Live --> UI[Compose interface]
  Obd --> Recorder[Trip recording]
  Recorder --> DB[(Room)]
  DB --> UI
  DB --> Export[Export]
  DB --> Outbox[Persistent queue]
  Outbox --> Worker[Network-constrained work]
  Worker --> API[Anonymous HTTPS API]
  API --> Atlas[(MongoDB Atlas)]
```

Use Kotlin and Jetpack Compose for the interface, ViewModel/StateFlow for its state, and coroutines for asynchronous work.
Room stores vehicle profiles, active selection, trips, samples, and synchronization preparation; dashboard favorites use private SharedPreferences. Private synchronization settings retain the configured HTTPS server identity. Broader acquisition/storage settings remain future work.
Components can start as packages; there is no need to create a Gradle module for each component from day one.

## Transport and OBD engine

### Current discovery and transport

The Android transport lists paired devices and performs user-started bounded Classic discovery, merging by address without name filtering. Discovery stops on connection or when Adapter is hidden. Secure RFCOMM uses the Serial Port Profile UUID; Android handles pairing. BLE and unverified socket fallbacks are not implemented.

Connection attempts allow 15 seconds for bonded devices or 45 seconds for pairing. Discovery allows 15 seconds after up to two seconds stopping another inquiry. Byte operations are bounded and cancellable; closing the socket cancels blocked operations.

The service privately stores the last successful adapter and a separately selected favourite. A favourite enables the visible-only retry policy; without one, the process-level policy allows one visible-app attempt. Manual Stop/Disconnect pauses favourite retries. Changing tabs does not duplicate a connection; process death never restores a killed service/session.

The non-exported connectedDevice service is promoted while the app is visible, before connecting. Its controller runs an authorized continuation after the socket opens: freeze connection time, prepare automatic recording, then initialize OBD. Generation/cancellation checks prevent late connections or profile loads from starting obsolete work. This continuation can finish after the app backgrounds without treating it as a new background connection.

A socket does not prove ECU compatibility. Manual retry remains available in Adapter. Android guidance: [RFCOMM clients](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices), [foreground-service start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), and [connected-device service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).

### Current read-only OBD engine

RX8-004 separates pure parsing in `:core:telemetry` from the session scheduler in `:app`:

- Reset and configure the ELM interface, use automatic vehicle-protocol selection, and read the detected protocol. Configuration replies must be valid before acquisition continues.
- Assemble Bluetooth byte fragments into complete prompt-terminated replies. A single serialized exchange owns the connection; bounded buffers and whole-command deadlines prevent a slow or broken adapter from hanging indefinitely.
- Close the link after a missing prompt or transport failure so a late reply cannot be treated as the answer to a new command. Retry only complete, recoverable replies within a bounded policy.
- Read Mode 01 supported-PID bitmaps and retain each ECU's capabilities. Request only implemented scalar PIDs reported by at least one responding ECU. One PID can yield multiple independently identified scalar components.
- Parse CAN 11/29-bit single-frame and supported legacy header formats, keeping ECU source identifiers. Unexpected layouts, wrong PIDs, and malformed data fail visibly rather than being interpreted as values.
- Schedule RPM and speed more often than slower parameters, with serial requests and latency/error backoff. Report the observed response rate without promising a fixed sampling frequency.
- Preserve timestamps and units, distinguish missing/unsupported/error results, and mark old valid values stale. Explicit disconnection or a terminal error cancels acquisition and marks retained values stale. Leaving Adapter keeps an active session running.

The expanded catalog contains 120 scalar fields from 84 sensor PIDs through hexadecimal PID64, plus two scaling metadata PIDs (4F/50). It covers verified scalar sensor/counter fields, including oxygen-sensor components, fuel trims, pressures, airflow, timing, temperatures and counters. Bitfields, enumerated status and unverified/proprietary PIDs remain advertised without invented scalar decoders.
Capability blocks are discovered separately from decoding; a reported PID is not a promise that this build displays that parameter.
Mode 01 replies fit a single CAN data frame. VIN reconstruction is separately limited to the Mode 09 layouts below; neither path implements general ISO-TP or arbitrary multi-frame diagnostics.
The live panel displays separate ECU groups when several modules respond; it does not silently combine their values or identify an ECU as the engine controller based only on its address.
The engine uses read-only diagnostic requests and adapter configuration. It sends no fault-clearing, actuator, or ECU programming commands.

Do not commit to a per-sensor sampling rate before measuring it. Sequential readings are not simultaneous: each has its own timestamp.
The adapter's identification string alone does not prove its authenticity or all of its capabilities.
Command and response layouts follow the [ELM327 data sheet](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf). Adapter clones and the actual RX-8 still require hardware testing.

The catalog includes the original six channels; four primary and four secondary fuel-trim PIDs with optional extra bank fields; fuel/manifold/rail/evaporative/barometric pressures; ignition/injection timing; mass airflow; engine run time, distance and MIL counters; EGR/purge/fuel/alcohol/load/throttle/accelerator values; voltage, temperatures, fuel rate and torque; and separate voltage/ratio/current/trim channels for O2 slots. Slot labels do not guess the bank topology. An unavailable optional component remains null, not a fabricated sensor.

Definitions follow the SAE-authored [J1979-DA October 2011 tables](https://f30.bimmerpost.com/forums/attachment.php?attachmentid=1800282&d=1523076762), reproduced by a third party, and the [ELM327 data sheet](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf). The catalog is a software decoder list, not evidence that this RX-8 has oil-temperature, fuel-rate, torque or every O2 sensor. Bitfields, enumerated status, DTCs and later compound/proprietary PIDs are not presented as decoded scalar readings.

Advertised scaling metadata 4F/50 is read per ECU before dependent fields. If that advertised metadata fails, affected fields remain unavailable instead of silently using default scaling. A single request can produce multiple metrics with one timestamp. Polling fairly interleaves RPM/speed, the original slower sensors, added sensors and slow counters. Freshness limits are 5/15/120/180 seconds by group, not promised sampling frequencies; real adapter rates must be measured. Capability snapshots still describe displayed state rather than cumulative outcomes.

### Optional VIN acquisition

The user chooses Read VIN only after the OBD session reaches LIVE. The request is queued between complete sensor exchanges; it does not run automatically on Bluetooth connection, profile selection, or live-data startup.
For CAN, the session first enables automatic adapter flow control with `ATCFC1`, then requests `0902`. Sensor polling resumes after the bounded VIN request.
The pure VIN parser supports CAN 11/29-bit identifiers with the 20-byte Mode 09 response (`49 02 01` plus 17 VIN characters), and five ordered legacy packets with three leading zero filler bytes.
It checks per-ECU completeness, sequence, service/PID, and VIN structure. Conflicting, partial, or malformed sequences fail the complete reply; no partial VIN becomes a candidate. Headerless, extended-address, long KWP, and general ISO-TP layouts are not inferred.
The adapter validates legacy checksums/CRCs; printed error markers are rejected. This software support does not establish that the actual adapter/car supplies a VIN.

A complete prompt containing NO DATA, an unsupported request, or malformed VIN data reports a VIN-specific result while Mode 01 polling continues. A transport timeout, missing prompt, or unsafe framing closes the link and requires manual reconnection.
Multiple valid ECU candidates remain separate. Candidates are masked, held in memory, and cleared with the session. Choosing one explicitly copies it into Vehicles for review before any database write.

## Capability snapshot reports

RX8-010 maps an existing completed-discovery `ObdSessionSnapshot` into a dedicated immutable report only after an explicit capture action. It does not subscribe to an acquisition event stream, add requests, or count outcomes from a conflated UI flow. The report describes latest displayed state; initial no-data, ticker, and stale transitions are not represented as completed requests.

`CapabilityReports` bounds and validates the input, copies nested collections, recognizes protocols/ECU addresses, and retains only the approved fields. `CapabilityReportJson` encodes a separate bounded schema version 1. No serializer receives the whole Bluetooth state, VIN snapshot, adapter identity, free-form status/error text, or profile/trip metadata.

The Activity-scoped `CapabilityReportViewModel` owns the captured preview and pending immutable JSON. A root-level `CreateDocument` registration remains active across navigation/recreation. A pending export refuses another capture or picker until it is resolved. IO runs outside the UI thread; success follows write/flush/close, while process loss or failed providers produce generic errors without exporting a substitute report. Returning from a picker does not reconnect, query, or start recording.

Capabilities remains a child of Live, with explicit Back and Capture latest snapshot controls. It does not own a screen-awake flag. Retained stopped/error snapshots stay labeled historical; the preview remains frozen even if live acquisition continues. No Room/schema, trip-export contract, or synchronization change is involved. See [Capability reports](capability-report.md).

## Current dashboard and preferences

The Live dashboard consumes service snapshots without owning a socket or another polling loop. Its per-ECU RPM dial uses real quality/value/age, and the displayed scale does not claim an engine limit. All implemented metrics, including speed/coolant/RPM, are selectable and removable with persistent card configuration. Stopped/error values remain historical, missing values remain absent, and valid zero/negative values survive formatting.

Four single-line tabs replace the previous five-tab layout. Larger labels can scroll horizontally. Restored Simulator or unknown routes resolve to Live; production no longer instantiates the Simulator ViewModel. Internal labelled fixtures retain synthetic scenarios for tests only.

Private app-wide `dashboard_preferences` stores stable `favorite_metric_ids_v1`, independent of profile or ECU identity. Existing IDs remain valid. In 0.14.0-dev the ordered layout includes primary metrics too; unknown IDs are ignored and unavailable saved choices remain removable. Favorites never change acquisition or recording coverage. Serialized commits publish success only after saving, with loading/error states. No readings are stored in these preferences.

The sensor search is presentation state saved with rememberSaveable. The production picker searches all implemented metrics and labels unsupported choices, then matches every case-insensitive query word against the label or exact two-digit hexadecimal PID. An optional PID/0x prefix is accepted. Query changes never call the preference writer or alter acquisition/recording. Clear search restores all eligible choices; Activity state restoration and closing/reopening the picker retain the query.

## Local model

| Entity | Contents |
| --- | --- |
| Vehicle | Stable UUID, optional VIN, provenance, and alias |
| Adapter | Connection information and observed capabilities |
| Capability | Parameters by vehicle, ECU, and profile |
| Session | Start, end, duration, status, and real/simulated origin |
| TelemetrySample | UUID, sequence, timestamps, metric, value, unit, source, and status |
| OutboxEntry | Pending work, attempts, next execution, and acknowledgments |

Vehicle, active selection, Session, TelemetrySample, and pending work are persisted; dashboard preferences and the last successful adapter are stored separately. Capabilities remain in memory; the detected protocol is copied into the current trip after discovery. Profile changes never reassign previously stored telemetry.

Recorded samples are immutable. Sessions use the recording, completed, or interrupted states.
Each reading carries a UTC timestamp and monotonic elapsed time since the start; sequence determines ordering.
Values are normalized to canonical units and converted only for display.

A failed reading has a null value. The interface may show the last valid reading as stale without storing it again as a new measurement.
Estimated distance integrates only adjacent valid speed samples from one selected ECU; uncovered intervals remain gaps.

## Identity and current persistence

A profile is created only after an explicit user action. The repository generates one UUID, stores its alias and creation timestamp, and selects it in the same Room transaction.
Aliases are trimmed, contain 1-60 characters, and reject control characters. Rename changes only the alias.
A VIN is optional. Manual input trims surrounding whitespace and uppercases ASCII characters, requiring 17 letters/numbers excluding I, O, and Q. This is structural validation; it does not infer region/model/year, impose a regional check digit, or prove ownership.
Saved provenance is MANUAL or OBD; OBD provenance includes the ECU source. Attaching the same VIN preserves original provenance; adding a VIN to a VIN-less profile preserves the UUID.

The Vehicles screen shows saved profiles, selected state, permanent IDs, and masked VINs with explicit show/hide controls.
A duplicate VIN points to its existing profile and requires explicit selection. A different VIN cannot overwrite a profile: the user must select the corresponding vehicle or create a separate one. No automatic profile assignment or merging is implemented.
The adapter MAC address never identifies a vehicle. Starting a trip freezes the selected profile's UUID and alias. Later profile selection or renaming does not relabel that trip or its samples.

Room schema version 3 retains `vehicle_profiles`, `active_vehicle`, `trip_sessions`, and `trip_samples`; it adds metadata revisions, nullable recording app version, `sync_work`, and `sync_samples`. Explicit migrations from 1 to 2 and 2 to 3 preserve profiles, selection, trips, and samples. The 2-to-3 migration inserts pending references for existing samples through SQLite rather than loading all telemetry into memory; metadata is encoded one cursor row at a time. It never marks old data delivered or invents its original app version. VIN uniqueness and foreign keys protect the relationships; a unique trip/sequence index prevents duplicate sample positions.
Room schema version 4 adds only the history indexes `(startedAtEpochMillis, id)` and `(vehicleId, startedAtEpochMillis, id)` through an explicit 3-to-4 migration. The existing migration chain is retained; no profiles, selection, trip/sample data, metadata revisions, or queue state are rewritten by this upgrade.
The application-scoped `rx8-vehicles.db` lives in private app storage. A failed read or write reports an error rather than replacing profiles with a new empty database. There is no destructive migration fallback; future changes require explicit migrations and schema review.
Only successful storage results reach the UI as saved. Trip deletion and JSON/CSV export are implemented; profile deletion, importing, and restoring an app database are not.
Android backups and device transfer are excluded. Clearing app data or reinstalling removes saved profiles and trips. An exported trip can be inspected separately, but cannot currently restore the app's database or vehicle profiles.

## Sessions and permissions

### Current connection permissions

On Android 12/API 31 and later, connecting requires `BLUETOOTH_CONNECT`, and discovery also requires `BLUETOOTH_SCAN`, through Nearby devices runtime prompts.
Scan access uses `neverForLocation`; the app does not derive physical location from scan results.
On API 26-30, legacy `BLUETOOTH` and `BLUETOOTH_ADMIN` apply, and discovery requires foreground fine-location permission and enabled location services.
Those legacy permissions are limited to API 30. Android 12+ does not request location permission. No GPS collection or advertising is implemented. INTERNET and network-state access support optional API synchronization, without a runtime permission prompt.
Android can present pairing during connection. Bluetooth and permission settings remain available for recovery.
See Android's [Bluetooth permission guidance](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

### Current background session lifecycle

The started and bound connected-device foreground service owns controller jobs, Bluetooth events and acquisition. MainActivity reports visible-app eligibility separately from Adapter discovery visibility. Automatic/manual connection promotion happens while visible; the already authorized initialization can continue after tab changes/background/lock/recreation. Read VIN remains explicit.

The ongoing notification offers Stop/open-Adapter without VIN, MAC, UUID or readings. On API 33+, notification access is requested through the visible UI; denial does not block the session. Discovery stops when Adapter is hidden. Adapter/Live hold the screen-on flag only during visible real initialization/discovery/live acquisition. Acquisition uses a bounded 120-second partial wake lock renewed every 60 seconds and released on stop/error/cleanup.

Stop/disconnect releases sockets/jobs but retains foreground ownership until final recording writes settle. The service uses START_NOT_STICKY, no background reconnect loop and no process-death session restoration. The app never removes a Bluetooth bond. Battery use and sustained A53 behavior still require measurements; no battery-optimization exemption is requested.

### Current trip recording

At socket connection the service captures UTC and monotonic time, then reads the persisted selected vehicle with a bounded lookup. It waits for any preceding trip's final write before starting the next recorder. The selected UUID/name are frozen. The recorder starts at the connection anchor before the first OBD callback, so initialization time is represented without inventing samples. If selection is missing or fails, reading continues with visible guidance; selecting a vehicle later starts from then, without backfilling earlier readings/time.

`TripRuntime` owns the process-scoped ordered writer, independent of UI collectors and service binding. Controller callbacks feed it directly. Initial placeholder readings carry `isRequestResult = false` and are excluded; the first actual valid/error result is retained. Cached, STALE and UNSUPPORTED display states are not resaved. Each sample preserves UUID, sequence, UTC, monotonic elapsed time, metric, canonical unit, ECU and quality; failed values remain null.

The command channel serializes create, discovered-protocol update, samples and finish. Pending samples are bounded at 512; overload interrupts visibly. Start/count/finish success reflects durable commits. At least 10 MiB is checked before create/append; storage failure stops recording and attempts an interrupted marker, with startup recovery handling an uncommitted marker.

Automatic start initially has no detected protocol. Its first known protocol is committed once in a new session metadata revision with an immutable outbox body. Earlier prepared revisions are unchanged; the established protocol cannot be reassigned. No schema change is required. Finish/recovery creates the next revision.

Stop recording completes the trip while acquisition continues and suppresses automatic restart for that connection. Explicit manual start can resume as a separate journey. Disconnect/notification Stop completes; loss/OBD failure interrupts. A fresh connection creates a new journey and never joins across the gap. Startup marks unfinished rows interrupted using the last saved reading or the start of an empty trip, not a guessed termination time. It never appends to the old trip.

Journeys contain telemetry only, without GPS. WorkManager runs network-constrained completed-trip synchronization independently of Bluetooth acquisition.

### Trip summaries and charts

History retains one page of at most 50 sessions and the app database footprint, including profiles and trips. The page query never loads samples. Complete summaries, exports, and deletion require a completed or interrupted trip, keeping sample reads independent of an actively growing recording.

RX8-014 orders history by `(startedAtEpochMillis DESC, id DESC)`. A cursor contains both immutable values, so equal timestamps and deletion of the cursor row do not lose the boundary. Separate filtered/unfiltered indexed queries support newest, older, newer, and inclusive at-or-older requests; newer queries reverse only the bounded returned page. Indexed existence probes compute both navigation flags in a short read transaction. There is no OFFSET, full-history count, accumulated page cache, or long-lived database snapshot.

Filtering uses the permanent vehicle UUID from existing profiles and does not mutate profile selection, recording, or pending work. Current names label filter choices; saved aliases label trips. The Activity-owned ViewModel keeps the filter, confirmed page, refresh anchor, and request generation. Cancellation plus identity/generation checks reject obsolete successes and failures, including repeated A-to-B-to-A changes. Same-filter errors preserve correctly labelled rows; changing filters clears rows from the previous filter. Storage and queue errors stay independent of history errors. Lifecycle refresh retains the current anchor, while Newest explicitly resets it. Successful deletion refills the page, with a bounded newest-page fallback if it becomes empty. Navigation/deletion must invalidate earlier requests. Activity recreation retains browsing state; process-death restoration is not promised.

Analysis reads sequence-ordered pages of at most 500 samples. It selects the lexicographically first speed ECU with usable valid km/h data, falling back to the first observed speed ECU if none has usable values. Speed maxima, the chart, and distance all use that one source; different ECU readings are never averaged or joined. Peak recorded RPM considers finite, valid canonical RPM readings across sources.

Estimated distance uses trapezoidal integration between adjacent valid, nonnegative speed samples from the selected source only when the monotonic interval is positive and at most five seconds. Missing/invalid readings, wrong units, reversed timestamps, and longer intervals break coverage. There is no endpoint extrapolation. An estimate requires at least one covered interval; valid stationary intervals can produce a true zero. The UI labels the result as an estimate, identifies the source, and shows covered and uncovered duration; it is not an odometer reading.

RX8-012 collects recorded metric/ECU options during the first summary pass, including error-only sources. The index is bounded to 3,840 combinations (120 metrics across at most 32 ECU sources) and fails explicitly above that limit. Selection defaults to the overview's speed ECU, then stable metric/ECU order. It never depends on current live capabilities, selected profile, or favorites.

Each selected series reads pages of at most 500 samples and retains at most 500 chart points. Counts, observed min/max, and coverage use the original selected stream before reduction. A usable value needs VALID quality, the canonical unit, a finite value within the existing decoder domain, and an in-range, strictly increasing elapsed timestamp. Zero and signed values are valid when inside each metric domain; temperature ranges depend on the sensor. Failed/stale/wrong-unit/out-of-range samples, duplicate/reversed timestamps, and intervals beyond the selected metric gap limit break the line and coverage. Original six metrics retain five seconds; added metrics use their polling-class freshness limit. No endpoint extrapolation or joining across ECU sources is allowed. Streaming reduction preserves null gap barriers; it may omit chart detail but cannot change full-stream statistics. The existing overview speed chart model and distance calculation retain their semantics.

The Activity-owned Trips ViewModel loads summaries and selected series separately. Selection cancels previous chart work and advances a generation token; trip changes, closing details, and successful deletion invalidate outstanding work. Both generation and selected identity must match before accepting a result, including A-to-B-to-A changes and reopening the same trip. Chart errors retain the trip overview and offer a chart-only retry. Activity recreation retains selection; process-death restoration is not promised. Analysis and export support coroutine cancellation and reject broken page ordering or foreign-trip samples. History navigation does not change chart analysis, export v1, acquisition, or API preparation.

## Completed-trip synchronization

Pure Kotlin v1 DTOs and deterministic JSON retain explicit field/unit/quality mapping. `RoomTripRepository.append` commits the original sample and its pending reference together. Profile and trip mutations freeze metadata revisions atomically; retries never regenerate an older body from current data. Legacy app versions and unconfirmed vehicle specifications stay null.

The production HTTPS transport sends the exact prepared body to the configured server, refuses redirects, bounds response size and strictly decodes ACKs. A real Room store requires this concrete transport; fake acknowledgments remain isolated in test stores. The endpoint starts absent and its durable identity is locked before its first attempt. Pause/resume retains identity, preventing prior acknowledgments from being silently reassigned to another server.

WorkManager requires connectivity and processes bounded work in vehicle/session/sample dependency order. Terminal eligibility covers both prepared requests and unbatched sample references. Completed/interrupted trips may replay their historical recording metadata revisions before the closing revision; active local trips cannot be sent. Samples wait for all required session metadata acknowledgments. Finishing and startup recovery schedule delivery after commits, including metadata-only empty trips.

Each run starts at most 250 requests and stops starting requests after four monotonic minutes. The last started request retains its ten-second timeout. This permits large saved trips to progress without excessive delays between small chunks while keeping each background run bounded. Pending retry times remain enforced by Room.

Leases, immutable batch membership, per-sample ACK validation and retry backoff remain durable. An entire response must match its request before any ACK is committed. Only stored/already_stored sample results confirm delivery. Partial ACKs and oversized requests create new batches without changing attempted bodies. Offline work remains local; storage and queue failures do not become empty-history claims. Explicit retry can recover exhausted transient attempts, while permanent conflicts remain blocked.

### API development and storage

The independently buildable `api/` package uses TypeScript, Fastify and the official MongoDB driver. There is no login/authentication flow as requested; resource UUIDs are identifiers, not credentials. The HTTPS reverse proxy and actual server deployment are configured later. Atlas credentials belong only to backend environment configuration, excluded from version control and the APK.

Vehicle and session revisions reject conflicting content, immutable identity changes and terminal-to-recording transitions. Transactional batch writes preserve each sample's content, prevent duplicate session sequences and retain idempotency results. Unique indexes protect resource/sample/batch identities. Durable ACKs follow transaction commits; a lost response is retried with unchanged IDs/body. Read routes use bounded keyset pages. Health/readiness checks reveal no credentials or telemetry.

Tests use disposable MongoDB replica sets and synthetic data, never the owner's Atlas database. Root CI validates Android, documentation and API independently. See [the API contract](api-contract.md) and [backend setup](../api/README.md) for routes, validation, deployment and operational limits.

## Retention and export

Trips remain stored until explicitly deleted. Deletion requires confirmation and removes that trip, its samples, and related pending/prepared work in a transaction; it does not delete vehicle profiles or previously exported files. Database space may be retained for reuse rather than immediately shrinking the file. Local deletion does not delete server copies.

The user chooses a JSON or CSV destination through Android's document picker, without a broad storage permission or automatic upload. Exports stream sequence-ordered pages instead of materializing the full history. Success is reported only after writing, flushing, and closing the destination; a failed or cancelled write may leave a partial file and is not reported as saved.

Local export schema version 1 includes trip/vehicle/sample IDs, the recorded vehicle alias, timestamps, monotonic times, protocol, origin, terminal status/reason, units, ECU sources, and sample quality. It contains no VIN or Bluetooth address. JSON preserves null values and escaped text; CSV repeats trip metadata on each sample row and writes a metadata-only row for an empty trip. CSV text beginning with spreadsheet formula triggers receives a protective apostrophe. Non-finite numeric values cause export failure instead of invalid output or replacement zeros. See [the local export contract](api-contract.md#implemented-local-trip-export) for fields and its boundary with the future API.

Exports contain private trip information and are shared only through the user's chosen destination. Do not commit real databases or private exports. Android backup/device transfer remain disabled, and importing or restoring exports is not implemented.

## Validation

Parser tests cover complete, fragmented, multi-ECU, and malformed Mode 01/VIN responses. Identity tests cover duplicate/different VINs, UUID/provenance preservation, explicit selection, storage failures, and reopening Room. Actual run results belong in the PR.
Tests for persistence, migrations, identity, unexpected termination, the queue, and retries.
Interface checks cover the real live dashboard with clearly labelled synthetic test fixtures, per-ECU capabilities, stale/error/zero states, unavailable favorites, and preference persistence/failure. Actual run results belong in the PR.
Lifecycle checks cover Activity recreation, continued background samples, notification denial/Stop, and resource release. Trip checks cover ordered durable recording, bounded backpressure, storage failures, migration/recovery, vehicle identity freezing, source-isolated analytics, chart gaps, cancellation, export escaping, and deletion. Actual test results belong in the PR. Real-vehicle validation still needs compatibility, measured rates, battery use, and saved recording with the A53 screen locked.

## References

- [Android architecture](https://developer.android.com/topic/architecture/recommendations).
- [Bluetooth Classic](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices).
- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
- [Connected-device foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).
- [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).
- [Keeping the screen on](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on).
- [Wake locks](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock).
- [Offline-first operation](https://developer.android.com/topic/architecture/data-layer/offline-first).
