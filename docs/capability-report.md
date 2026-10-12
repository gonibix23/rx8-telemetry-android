# Capability snapshot report

## Purpose and limits

RX8-010 / 0.9.0-dev adds Vehicle capabilities inside Live. It previews the already-discovered Mode 01 PIDs for each ECU and the latest displayed states of the implemented scalar metrics. A user can explicitly save that snapshot as JSON.

Opening, capturing, or exporting a report sends no additional OBD command. It neither reconnects nor requires a saved vehicle or trip. It does not change recording, history, favorites, or the pending API queue. There is no server, upload, automatic sharing, or raw diagnostic logging.

An ECU advertising a PID is not proof of a valid reading. The app decodes the verified standard scalar catalog; scaling metadata PIDs 4F/50 are labelled separately, while other advertised PID numbers remain unimplemented. An ECU address does not identify a particular vehicle or prove that the module is the engine controller.

The report is a **snapshot of displayed state**, not a sequence of measurements or request outcomes. Initial no-data placeholders may appear before the first request. A latest value does not establish accuracy, sustained sampling rate, session stability, battery behavior, screen-lock continuity, or proprietary Mazda support.

## User flow

1. Connect in Adapter; read-only acquisition starts automatically. Wait until capability discovery completes.
2. Open Vehicle capabilities. The app freezes the current snapshot and displays its capture time, acquisition state, protocol, elapsed acquisition time, and response rate.
3. Review each ECU's advertised PID list and the implemented scalar metrics. Keep advertised support separate from the latest valid, failed, unsupported, or stale display state.
4. Use Capture latest to replace the preview explicitly. A report is not a live screen; subsequent readings do not silently change the captured data.
5. Choose Save JSON report and select a destination in Android's document picker. Canceling leaves no success message. Success appears only after the chosen file has been written, flushed, and closed.

Completed discovery retained after Stop or an error can still be captured until the current controller replaces or loses that snapshot. Such values are historical, not current readings. A new connection starts its own discovery; the app must never mix an earlier report into the new live state.

The preview is held only in memory by an Activity-scoped ViewModel. It survives Activity recreation and tab navigation, but not process death. An export freezes the selected report before the picker opens and prevents replacement while a destination or write is pending. If Android delivers a document result after the process lost the report, the app reports that the capture must be repeated; it never saves an empty substitute.

## JSON version 1

The report has its own schema, separate from trip export v1 and future API payloads.

| Field | Meaning |
| --- | --- |
| schemaVersion | `1` |
| kind | `capability_snapshot` |
| dataOrigin | `real_obd` |
| snapshotSemantics | `latest_display_state` |
| reportId | New UUID for this capture, unrelated to vehicle/trip identifiers |
| capturedAtUtc / capturedAtEpochMillis | The same UTC capture instant as ISO 8601 text and Unix milliseconds |
| appVersion / androidApiLevel | Application build and Android API level |
| phase | Acquisition state at capture |
| protocolLabel | Recognized protocol description, or null for unknown text |
| elapsedMillis | Existing snapshot's acquisition elapsed time, including initialization |
| effectiveResponsesPerSecond | Existing engine's successful ECU-response rate across sensors, or null if invalid |
| ecus | Separate ECU capability and latest-display summaries |

Each ECU contains a validated diagnostic `address`, sorted hexadecimal `reportedPids`, and `implementedMetrics` for the implemented app metrics. Each metric includes its ID, PID, unit, `advertised` flag, and an optional `reading` containing value, quality status, and age at the captured snapshot. It does not contain raw supported-PID response bytes.

The rate is not a per-sensor frequency or command latency. Multiple responding ECUs can contribute successful responses. Elapsed time does not prove continuous valid readings. Ages are frozen at capture and never advance while reviewing or exporting an old report.

Valid zero values remain zero. Missing/error readings remain null; they are never replaced by a valid zero. A stale value, if present, is retained only as explicitly historical data. Stopped/error acquisition cannot expose a retained value as current. Exact individual reading timestamps and traces are excluded.

Reports allow at most 32 ECUs, 248 non-continuation Mode 01 PIDs per ECU, 120 implemented metric summaries per ECU, and 512 KiB of UTF-8 JSON. Collection contents are copied and immutable. Duplicate/conflicting entries, invalid capture metadata, and excessive collections fail capture; invalid identifiers/free text are never copied into the report.

## Privacy and storage

The dedicated report model excludes VINs and candidates, Bluetooth names and MAC addresses, profile aliases, vehicle/profile/trip IDs, adapter identity, raw requests/responses, and free-form status or exception text. Protocol descriptions and ECU address formats are allowlisted. Report IDs are generated independently for each capture.

The capture time, sensor values, ECU addresses, and capabilities still describe vehicle use. Review a file before sharing it. This is a reduced-data report, not a guarantee of anonymity. A chosen document provider can synchronize it under that provider's settings; the app makes no network request and retains no URI.

The app does not store report history in Room, add a database migration, or mark any reading uploaded. Trip exports remain schema version 1. A failed or cancelled write can leave an incomplete chosen document and is not reported as saved.

## Validation

Unit tests cover eligibility, advertised versus implemented metrics, multi-ECU isolation, invalid/duplicate input, deep immutability, valid zero, error/stale handling, unknown text, privacy exclusions, bounded output, frozen export data, and failure/cancellation. Android fixtures cover preview controls and document-picker lifecycle. Actual executed results belong in the PR.

The A53 check is to capture from real live data, review the supported PID lists, export a report, and confirm that the existing live/trip workflow continues. Capture again after stopping to check historical labeling. Existing hardware issues still require actual reported evidence; a generated report alone does not close them.

The 512 KiB bound is retained for the expanded catalog. A very large rich capture can exceed it and must fail visibly; the exporter never truncates a report and calls it complete.
