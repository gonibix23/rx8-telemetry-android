# API v1 preparation and local export contract

## Status and boundaries

RX8-008 / 0.8.0-dev implements typed version-1 payloads, deterministic JSON serialization, a durable local outbox, a transport interface, and an isolated-test coordinator. No API, live URL, configured authentication, HTTP client, response JSON decoder, worker, or remote database exists.
The app has no Internet permission and instantiates no coordinator. Its production transport is disabled; there is no setting that enables uploads. Backend implementation, HTTPS transport, authentication, scheduling, and server validation belong to a later phase.
All examples are synthetic. Fault diagnostics and remote deletion are outside this initial contract.

The app records trips locally and exports files. Those files retain the separate version-1 contract below; they are not uploads or an implementation of the proposed API routes. Trips displays explicitly disabled uploads and last-refreshed sample queue counts. Queue-query failures remain visible without preventing local recording or export.

## Implemented local trip export

RX8-007 exports completed or interrupted trips through Android's document picker. Stop an active recording first. Export is an explicit local action that does not attempt synchronization or change outbox state.
Exports preserve the vehicle UUID and alias captured when recording began, the trip/sample UUIDs, sample order, UTC timestamps, monotonic elapsed time, units, ECU sources, and quality. They contain no VIN or Bluetooth address. Selecting another vehicle does not relabel a saved trip.
The current origin is `REAL_OBD`; simulator preview data is not recorded as a trip. Units are the stored canonical app units for each metric: `rpm`, `km/h`, `°C`, `%`, `kPa`, `Pa`, `deg`, `g/s`, `s`, `km`, `count`, `V`, `lambda`, `min`, `L/h`, `N·m`, and `mA`. The API mapper converts Celsius to `Cel`, torque to `N.m`, and dimensionless ratio/count to `1`; other canonical units remain unchanged. Local export version 1 retains its fields; consumers must accept the expanded metric catalog.

### JSON version 1

The root has `schemaVersion: 1`, a `trip` object, and a sequence-ordered `samples` array. An empty trip has an empty array and retains all its metadata.

| Object | Fields |
| --- | --- |
| trip | id, vehicleId, vehicleAlias, startedAtUtc, startedAtEpochMillis, endedAtUtc, endedAtEpochMillis, durationMillis, status, endReason, protocolLabel, origin |
| sample | id, tripId, sequence, observedAtUtc, observedAtEpochMillis, elapsedMillis, metric, value, unit, ecuId, quality |

The UTC strings use ISO 8601 and end in `Z`. Epoch fields are milliseconds since the Unix epoch; elapsed/duration fields are monotonic milliseconds. Nullable end timestamps, end reason, protocol, and sample values remain JSON nulls. Missing values are never replaced by zero.
`metric` uses stable `ObdMetric` enum names. The original six IDs remain unchanged; version 0.12.0-dev adds scalar fields through PID64, including distinct components when a PID returns multiple values. See the [sensor catalog](architecture.md#current-read-only-obd-engine). A shared PID does not make its component metric IDs interchangeable.
`status` is `COMPLETED` or `INTERRUPTED` for exported trips. `quality` preserves the recorded enum name; normal recording stores `VALID`, `NO_DATA`, `TIMEOUT`, `PARSE_ERROR`, or `ADAPTER_ERROR`, while STALE/UNSUPPORTED display states are not appended as fresh samples.
JSON escapes quotes, backslashes, and control characters without modifying normal field values. A non-finite numeric value aborts export rather than producing invalid JSON or a fabricated number.

### CSV version 1

CSV is one flat table with a header, CRLF record separators, quoted text, doubled embedded quotes, and support for multiline text cells. Numeric values use a locale-independent decimal point; nulls are empty cells.

| Group | Columns in order |
| --- | --- |
| Version/type | schema_version, record_type |
| Trip | trip_id, vehicle_id, vehicle_alias, trip_started_at_utc, trip_started_at_epoch_ms, trip_ended_at_utc, trip_ended_at_epoch_ms, trip_duration_ms, trip_status, trip_end_reason, protocol_label, origin |
| Sample | sample_id, sequence, observed_at_utc, observed_at_epoch_ms, elapsed_ms, metric, value, unit, ecu_id, quality |

`schema_version` is `1`. Every sample row has `record_type` equal to `SAMPLE` and repeats the trip metadata, including its terminal status and reason. An empty trip has one `METADATA` row with blank sample fields.
Text that could be interpreted as a spreadsheet formula receives a leading apostrophe, including formula triggers hidden behind leading whitespace or control characters. This is deliberate text protection; the JSON export preserves the exact original text. Numeric sample cells are not converted into formulas or protected text.

### Local export lifecycle

Both formats read pages of at most 500 samples, honor cancellation, and reject repeated/out-of-order sequences or foreign-trip samples. They do not load the entire history into memory. Success appears only after the destination has been written, flushed, and closed; cancellation, storage failure, or destination failure can leave a partial file and is not reported as success.
The file contains private vehicle/trip information and is shared only through the user's selected destination. Deleting a saved trip does not delete previously exported files. Android backup/device transfer remain disabled, and importing or restoring these exports is not implemented.

## Conventions

Future transport: HTTPS/JSON and /v1 routes. Implemented payloads use schemaVersion equal to 1 and UTC ISO 8601 timestamps ending in Z.
Client-generated UUIDs are retained for vehicles, sessions, samples, and batches.
Explicit canonical units do not depend on the phone's language.
VIN is optional; neither a VIN nor a UUID is a credential. Authorization must verify ownership of each resource.
Payloads preserve ECU source addresses but omit Bluetooth MAC addresses. All implemented scalar metrics use `obd_standard` and service `01`; actual availability is determined by discovery and responses, not by the contract.

## Planned resources

| Method | Route | Purpose |
| --- | --- | --- |
| PUT | /v1/vehicles/{vehicleId} | Create or update vehicle metadata |
| PUT | /v1/sessions/{sessionId} | Create or update a trip |
| POST | /v1/telemetry/batches | Store samples in batches |

Confirm the vehicle first, then the session, and finally the samples.
These routes are a future server contract. The current app makes no requests.

## Vehicle and session

Vehicle: schemaVersion, vehicleId, revision, nullable vin, vinSource (obd/manual/null), alias, and nullable make, model, modelYear, market, and powerCv.
The mapper leaves unconfirmed make/model/year/market/power null. The project car's known specifications are not automatically assigned to every stored profile, and OBD is not assumed to supply them.
Adding a VIN preserves vehicleId and creates a new metadata revision. VIN and provenance are either both present or both null; structural validation does not require a North American check digit.

Session: schemaVersion, sessionId, vehicleId, revision, startedAt, nullable endedAt, nullable durationMs, status, nullable appVersion, nullable obdProtocol, and dataOrigin (real/simulated).
States: recording, completed, interrupted.
Duration uses a monotonic clock; wall-clock changes must not produce negative durations.
Recording-session metadata has null endedAt and durationMs. Appending samples does not change that metadata revision; first protocol discovery creates an immutable metadata revision when an automatic journey started before initialization; completion or interruption creates the next revision with the saved duration. Earlier queued JSON bodies remain unchanged. New recordings capture their actual app version at start. Existing recordings migrated from version 2 have appVersion null, never the version installed during migration.

revision is a positive integer that increases with every metadata change. The future server must implement these rules:

- Higher than the stored revision: apply it.
- Equal with the same content: acknowledge without duplication.
- Equal with different content: conflict.
- Lower: reject as outdated.

This assumes a single writer per vehicle; concurrent editing across devices is a later extension.
Closing a session increases revision so an old retry cannot reopen a trip.
Each metadata revision freezes its complete JSON inside the local mutation transaction. Retrying an old revision cannot silently read the latest profile or session instead.

## Telemetry batch

A batch belongs to a single vehicle and session. Its JSON body and member sample IDs are frozen before it is leased and remain immutable across retries.
The future Idempotency-Key header must match batchId. Metadata uses its resource UUID and revision instead.

```json
{
  "schemaVersion": 1,
  "batchId": "0dfd5cf5-c8dc-40d1-9b86-d38d55afba9a",
  "vehicleId": "17ca18ac-8df9-4af1-8d78-b44759c6e993",
  "sessionId": "af32b50c-4c11-40ea-bd9b-a6ebc46d236d",
  "samples": [
    {
      "sampleId": "8463ab69-1c02-4c93-a86a-d4bf580f1378",
      "sequence": 1,
      "observedAt": "2026-10-10T09:00:01.240Z",
      "elapsedMs": 1240,
      "metric": "engine_rpm",
      "value": 850,
      "unit": "rpm",
      "status": "ok",
      "source": "obd_standard",
      "service": "01",
      "pid": "0C",
      "ecuAddress": null
    }
  ]
}
```

sampleId is unique; sequence increases within the session and is preserved across retries.
Each sample has its own timestamp. service, pid, and ecuAddress are optional.
status accepts ok, timeout, unsupported, parse_error, no_data, and adapter_error. If it is not ok, value is null. A valid zero remains zero; an ok value must be finite and present.
A stale reading is a display state, not a new sample containing the last value.

source accepts obd_standard, obd_vendor, adapter, and derived.
A calculated metric must document its formula and version before being added.
Voltage measured by the adapter uses a different metric from voltage reported by the ECU.

| Quantity | Unit |
| --- | --- |
| Engine speed | rpm |
| Vehicle speed | km/h |
| Temperature | Cel |
| Percentage | % |

Wire metric IDs are the lower-case stable `ObdMetric` names, with a one-to-one `ApiMetric` mapping. The original six IDs and payload shape remain unchanged; 0.12.0-dev extends the enum with the 120-field scalar catalog. Future server implementations must accept each implemented metric/unit/PID combination and retain unknown extension IDs if forwarding records. No live server currently consumes this contract.
Default limits are 100 samples and 256 KiB of UTF-8 JSON, including the envelope. Configurable hard bounds are 1-500 samples and 1 byte-1 MiB. A singleton exceeding the configured byte ceiling is blocked visibly in queue state. Batch preparation reads at most 500 sample rows at once and does not copy the full trip history into a payload.
JSON field ordering and numeric formatting are deterministic. Quotes, backslashes, controls, and UTF-16 surrogate code units are escaped; non-finite values, inconsistent units, foreign-trip samples, duplicate IDs, and non-increasing sequences are rejected.
Do not upload simulated data as real sessions.

## Acknowledgments and duplicates

The future HTTP decoder must produce the typed acknowledgments validated by the local coordinator and store. Metadata acknowledgment has exactly the matching schemaVersion, resourceId, and revision:

```json
{"schemaVersion":1,"resourceId":"17ca18ac-8df9-4af1-8d78-b44759c6e993","revision":2}
```

Proposed batch HTTP 200 response:

```json
{
  "schemaVersion": 1,
  "batchId": "0dfd5cf5-c8dc-40d1-9b86-d38d55afba9a",
  "results": [
    {
      "sampleId": "8463ab69-1c02-4c93-a86a-d4bf580f1378",
      "status": "stored"
    }
  ]
}
```

Per-sample states: stored, already_stored, or rejected. A rejection requires a bounded machine-readable code (`[A-Za-z0-9_.-]{1,64}`); free-form server messages are not stored or shown.
Only stored and already_stored acknowledge durable persistence. HTTP 200 without those results does not acknowledge the batch.

The server deduplicates by sampleId within the authorized scope, including across different batches.
Reusing a sampleId with different content or an Idempotency-Key with a different body causes a conflict.
Never overwrite an existing sample because of a retry.

If the response is lost, resend the same immutable batch.
For a partial ACK, store acknowledgments atomically and create a new batch containing pending samples eligible for retry.
Validate the entire response before any mutation: schema version, acknowledgment kind, matching resource/revision or batch ID, nonempty results, unique known sample IDs, and rejection codes. A malformed or mismatched ACK causes a retry of the original immutable request with no partial ACK writes.
An omitted sample remains pending; a permanent rejection is blocked for review.
An acknowledged or partially resolved batch is retired without rewriting its attempted body. Partial-ACK remainder and split batches receive new UUIDs. Attempted bodies remain locally available until their trip is deleted.

## Local queue

Room schema version 3 migrates version 2 explicitly, preserving profiles, selection, and trips. Existing samples receive pending references and existing vehicle/session metadata receives revision-1 snapshots; migration never fabricates historical app versions.
The subsequent schema version 4 migration adds history indexes only. It does not change wire models, export v1, metadata revisions, sample identities, pending work, or acknowledgment state.
Sample states are pending, sending, acknowledged, and blocked. The four UI counters count samples only, not vehicle/session metadata or retired batches.
Save a sample and its pending reference in the same transaction. Vehicle/session mutations also save their immutable revision snapshots atomically. Unbatched pending data uses sample references; payloads are prepared in bounded batches when claimed.
The store records attempts, a safe error code, the next attempt time, and a temporary lease. Default coordinator timing is a 30-second lease and a 10-second remote-call timeout. The store validates token, expiry, request identity, membership, and exact body inside the ACK transaction. Stale, expired, replaced, or deleted leases cannot acknowledge data.
An expired lease can be reclaimed with a new token while retaining the same request ID/body. Cancellation leaves recovery to lease expiry. A transport becoming disabled after claiming releases the lease without an ACK or artificial error. Metadata dependencies enforce vehicle before session before samples.

`SyncCoordinator.runOnce()` checks both transport availability and `SyncStore.isIsolatedTestStore` before leasing or mutating anything. The real Room store defaults to false. Only isolated fixture stores opt in, and fake transports exist only in test sources. The app constructs no runner, so neither fixture ACKs nor accidental coordinator construction can mark real records uploaded.

## Errors and retention

| Case | Action |
| --- | --- |
| Offline, timeout, 408, 5xx | Increasing delay with random jitter and retry |
| 429 | Retry with bounded policy and respect Retry-After when present |
| 401/403 | Block until future authentication/access recovery is implemented |
| 409 | Block and preserve the conflicting data/body |
| 413 | Halve sample count, preserve the configured byte ceiling, create new batchId values; a rejected singleton is blocked |
| Permanent validation error | Block the affected sample and retain it locally |

Synchronization does not delete local history.
Exports include pending and blocked records.
Local deletion removes work referencing those records and does not imply deletion from a future server.
Retry policy uses exponential backoff from one second with ±20% jitter, capped at one hour, and at most eight attempts before blocking. A nonnegative Retry-After up to 24 hours is honored if longer than the computed delay. Larger hints block for review rather than retrying earlier than the server requested. Timestamp addition is overflow-safe. No background scheduling or unblocking UI is implemented yet.

## Verification and remaining work

Isolated JVM and Room tests cover DTO mapping/escaping, null versus zero, disabled and real-store guards, lost-response retry, partial/malformed ACKs, conflicts, lease recovery, VIN metadata revisions, migration, deletion, bounded batches, 413 splitting, and retry/cancellation policy. See the PR validation results for checks actually executed.
These tests do not validate a remote service. A later increment must implement and verify HTTPS/authentication, response decoding, server-side authorization/deduplication/revision rules, scheduling and recovery controls, then explicitly authorize real synchronization.
