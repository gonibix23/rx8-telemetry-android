# Proposed API v1 contract

## Status and boundaries

Design for preparing the app. No API, live URL, configured authentication, or remote database exists.
RX8-008 will implement models, an interface, and isolated tests; the backend belongs to a later phase.
All examples are synthetic. Fault diagnostics and remote deletion are outside this initial contract.

The 0.7.0-dev app records trips locally and exports files. Those implemented files have the separate contract below; they are not uploads or an implementation of these proposed API routes.

## Implemented local trip export

RX8-007 exports completed or interrupted trips through Android's document picker. Stop an active recording first. Export is an explicit local action with no API, authentication, outbox, or synchronization attempt.
Exports preserve the vehicle UUID and alias captured when recording began, the trip/sample UUIDs, sample order, UTC timestamps, monotonic elapsed time, units, ECU sources, and quality. They contain no VIN or Bluetooth address. Selecting another vehicle does not relabel a saved trip.
The current origin is `REAL_OBD`; simulator preview data is not recorded as a trip. Units are the stored canonical app units: `rpm`, `km/h`, `°C`, and `%`. An eventual API adapter must explicitly map these units and enum names to the proposed wire contract below.

### JSON version 1

The root has `schemaVersion: 1`, a `trip` object, and a sequence-ordered `samples` array. An empty trip has an empty array and retains all its metadata.

| Object | Fields |
| --- | --- |
| trip | id, vehicleId, vehicleAlias, startedAtUtc, startedAtEpochMillis, endedAtUtc, endedAtEpochMillis, durationMillis, status, endReason, protocolLabel, origin |
| sample | id, tripId, sequence, observedAtUtc, observedAtEpochMillis, elapsedMillis, metric, value, unit, ecuId, quality |

The UTC strings use ISO 8601 and end in `Z`. Epoch fields are milliseconds since the Unix epoch; elapsed/duration fields are monotonic milliseconds. Nullable end timestamps, end reason, protocol, and sample values remain JSON nulls. Missing values are never replaced by zero.
`metric` uses stable enum names: `ENGINE_RPM`, `SPEED`, `COOLANT_TEMPERATURE`, `INTAKE_TEMPERATURE`, `THROTTLE_POSITION`, and `ENGINE_LOAD`.
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

HTTPS/JSON; /v1 routes; schemaVersion equal to 1; UTC ISO 8601 timestamps ending in Z.
Client-generated UUIDs are retained for vehicles, sessions, samples, and batches.
Explicit canonical units do not depend on the phone's language.
VIN is optional; neither a VIN nor a UUID is a credential. Authorization must verify ownership of each resource.

## Planned resources

| Method | Route | Purpose |
| --- | --- | --- |
| PUT | /v1/vehicles/{vehicleId} | Create or update vehicle metadata |
| PUT | /v1/sessions/{sessionId} | Create or update a trip |
| POST | /v1/telemetry/batches | Store samples in batches |

Confirm the vehicle first, then the session, and finally the samples.
Do not make requests until a server is configured.

## Vehicle and session

Vehicle: vehicleId, revision, nullable vin, vinSource (obd/manual/null), alias, make, model, modelYear, market, and powerCv.
The user confirms RX-8 metadata; do not assume OBD provides it.
Adding a VIN preserves vehicleId.

Session: sessionId, vehicleId, revision, startedAt, nullable endedAt, nullable durationMs, status, appVersion, nullable obdProtocol, and dataOrigin (real/simulated).
States: recording, completed, interrupted.
Duration uses a monotonic clock; wall-clock changes must not produce negative durations.

revision is a positive integer that increases with every change:

- Higher than the stored revision: apply it.
- Equal with the same content: acknowledge without duplication.
- Equal with different content: conflict.
- Lower: reject as outdated.

This assumes a single writer per vehicle; concurrent editing across devices is a later extension.
Closing a session increases revision so an old retry cannot reopen a trip.

## Telemetry batch

A batch belongs to a single vehicle and session. Its body becomes immutable when an upload is attempted.
The Idempotency-Key header must match batchId.

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
status accepts ok, timeout, unsupported, and parse_error. If it is not ok, value is null.
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
| Pressure | kPa |
| Air mass flow | g/s |
| Voltage | V |

The maximum batch size will be defined and tested with the backend. It must be configurable and support splitting.
Do not upload simulated data as real sessions.

## Acknowledgments and duplicates

Proposed HTTP 200 response:

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

Per-sample states: stored, already_stored, or rejected. A rejection adds code and message.
Only stored and already_stored acknowledge durable persistence. HTTP 200 without those results does not acknowledge the batch.

The server deduplicates by sampleId within the authorized scope, including across different batches.
Reusing a sampleId with different content or an Idempotency-Key with a different body causes a conflict.
Never overwrite an existing sample because of a retry.

If the response is lost, resend the same immutable batch.
For a partial ACK, store acknowledgments atomically and create a new batch containing pending samples eligible for retry.
Do not blindly accept unknown or repeated sampleId values in a response.
An omitted sample remains pending; a permanent rejection is blocked for review.

## Local queue

States: pending, sending, acknowledged, blocked.
Save a sample and its pending work in the same transaction.
Record attempts, the latest error, the next attempt, and a temporary lease to prevent duplicate workers.
An expired lease after process termination allows work stuck in sending to be recovered.

Until a server exists, synchronization is shown as disabled and does not generate connection attempts.
Tests use isolated storage; their ACKs do not modify real records.

## Errors and retention

| Case | Action |
| --- | --- |
| Offline, timeout, 5xx | Increasing delay with random jitter and retry |
| 429 | Respect Retry-After when present |
| 401/403 | Pause until authentication or access is resolved |
| 409 | Preserve data and resolve the conflict |
| 413 | Create smaller batches with new batchId values |
| Permanent validation error | Block the affected sample and retain it locally |

Synchronization does not delete local history.
Exports include pending and blocked records.
Local deletion removes work referencing those records and does not imply deletion from a future server.

## Planned verification

Identical resend; lost response; partial ACK; the same sample in another batch; conflicts; recovery from sending; VIN added later; old revision; interrupted session; authorization errors; export without a server.
These tests are pending implementation in RX8-008.
