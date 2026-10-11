# RX-8 hardware validation

## Goal and current evidence

Confirm actual communication and data availability before promising sensors or sampling rates.
Vehicle: 2004 RX-8, 231 metric horsepower (CV), Europe. Reference phone: Galaxy A53 running Android 16. Adapter: iLC B0BDRHRR22.

On October 10, 2026, the user reported that the simulator app worked after testing.
The user confirmed Android 16 on the A53, completing RX8-002. The paired-only Bluetooth build failed to list the adapter.
The user reports that Car Scanner searches inside the app with plain Bluetooth selected and displays OBDII with its MAC address.
The user subsequently confirmed that the Bluetooth connection now works, following delivery of the discovery APK. PR #13 is merged.
Detailed scan/pairing steps, interruptions, reconnection behavior, and physical adapter revision were not supplied. RX8-001 and remaining RX8-003 checks stay open.
On October 11, 2026, the user reported that live readings worked; PR #14 is merged. The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed that 0.5.0-dev reads data correctly. Individual sensors, ECU identifiers, measured response rates, and sustained lifecycle results remain unverified. RX8-004 stays open for those detailed checks. No VIN or saved-profile result has been reported.
The user also reported screen sleep while idle and loss of connection/readings after backgrounding in 0.5.0-dev, describing this as unpairing. The old policy deliberately disconnected outside Adapter; actual removal of the Android bond is unconfirmed, and the code has no removeBond call.
The user subsequently confirmed 0.5.1-dev worked, providing success evidence for the background/screen fix in merged PR #17. On October 11, 2026, the user explicitly requested closure of issue #16 with all acceptance checkboxes complete, and it was closed. No measured duration or detailed test log accompanied that confirmation; the extended lifecycle checklist below remains a separate record of those observations. The user then confirmed that the 0.6.0-dev dashboard build works perfectly; PR #18 is merged. This broad result does not establish the individual favorite/ECU scenarios below. The user subsequently confirmed that 0.7.0-dev works perfectly, and PR #19 is merged. Detailed trip duration, export-provider, interruption/storage, and battery results remain unreported. The user also confirmed 0.8.0-dev worked correctly and PR #20 is merged; individual migration and queue-display scenarios remain unreported.
A simulator test and a Bluetooth socket connection are separate from ECU validation.

## Test record

For each hardware test, record:

- Date, app/tool version, phone model, and Android version.
- An anonymous test session identifier; never the full VIN or adapter MAC address.
- Visible adapter model/revision, Bluetooth type, and pairing result.
- Ignition/engine conditions, test duration, and steps actually performed.
- Observed connection, permission, timeout, cancellation, and reconnection behavior.
- For ECU tests: identification response, detected protocol, supported PIDs, queries, and latency.
- VIN result only: valid, absent, error, or unsupported.

An adapter identification string is not proof of authenticity. Share anonymized evidence only.

## Bluetooth transport checklist (RX8-003)

Use the actual adapter with the vehicle stationary. Disconnect Car Scanner before testing this app. The general Bluetooth connection is confirmed by the user; the specific scenarios below require separate evidence.

- [x] Samsung Galaxy A53 runs Android 16, confirmed by the user.
- [x] Bluetooth connects successfully in this app, reported by the user on October 10, 2026 after the discovery APK was delivered.
- [ ] Record the app version used for the Bluetooth transport test.
- [x] Record the reference app setting: Car Scanner uses plain Bluetooth and shows OBDII with its MAC address, as reported by the user. Do not publish the real address.
- [ ] Choose Scan nearby in the Adapter tab with the adapter powered; compare the displayed name and MAC address with Car Scanner.
- [ ] On API 31+, check denied and granted Nearby devices permission; confirm the screen remains usable.
- [ ] Check denied and granted scan access, scan cancellation, no-results guidance, and a second scan without duplicate rows. On API 26-30 only, check location permission and disabled location services.
- [ ] Select the discovered adapter and connect; complete Android's pairing prompt if shown. Record success, error, or timeout.
- [ ] Confirm that a successful socket connection is labelled separately from ECU communication, which remains not checked.
- [ ] Cancel a connection attempt and disconnect a connected session; confirm the controls allow another manual attempt.
- [ ] Turn Bluetooth off or remove adapter power and record the observed response, both before and during live acquisition. A connected idle socket has no separate ECU heartbeat; live requests have deadlines.
- [ ] Switch to Live, Vehicles, or Trips, leave the app, and lock the screen in separate checks. Discovery must stop; an explicitly started connection/acquisition session must continue. Returning to Adapter must observe the same session without another connect attempt.
- [ ] Reconnect repeatedly and record failures or signs of duplicate connections.
- [ ] Repeat with Internet connectivity disabled and verify the simulator remains a separate synthetic-data screen.

In 0.12.0-dev, connecting automatically initializes the ELM interface and starts read-only requests and a journey for the selected vehicle. Earlier manual-start checklists below describe their original versions; use the RX8-015 checklist for the current automatic behavior.
Bounded byte read/write and cancellation behavior require real I/O checks; do not claim these were physically tested by opening a socket alone.
The Classic/SPP connection and general live readings have successful user reports. Optional VIN retrieval remains unverified on the car. BLE is not implemented. Explicit recorded-trip continuity is implemented in 0.7.0-dev and awaits the separate checks below.

## ECU validation sequence (RX8-001 and RX8-004)

Use the current app for Mode 01 checks, or an appropriate diagnostic tool for separate RX8-001 evidence.
Record the exact app version and operating conditions. No real-car readings are established by automated parser or UI tests.

1. With the vehicle stationary and ignition on, connect Bluetooth and wait for automatic acquisition. Distinguish adapter initialization from an actual ECU reply.
2. Record the detected protocol, responding ECU identifiers, and supported-parameter count. Confirm multiple ECU groups remain separate when applicable.
3. Test RPM and coolant temperature; verify valid zero speed while stationary. Record unsupported, no-data, or parsing errors rather than replacing them with a value.
4. Maintain a session for at least 10 minutes; record the observed response rate, stale readings, errors, and any gradual slowdown.
5. Remove adapter power or interrupt Bluetooth. Check that reading deadlines end the session, old readings become stale, and a manual reconnect starts a fresh session.
6. Cancel initialization or use Disconnect/notification Stop and verify acquisition ends. Separately leave Adapter/background the app and verify acquisition continues; use the lifecycle checklist below.
7. Repeat without an Internet connection. No synthetic value may appear as a real reading.
8. Follow the optional VIN/profile checklist below. Do not publish the full VIN or treat a valid-looking response as proof of vehicle ownership.

Screen-locked live acquisition is part of issue #16. Saved recording continuity is a separate RX8-007 check in 0.7.0-dev.
For moving-vehicle speed checks, have a passenger operate the app or review a saved trip afterward.

## Background session and screen-awake checklist (issue #16)

Use the current 0.6.0-dev build on the A53 with the actual adapter and vehicle stationary. The user broadly confirmed that 0.5.1-dev worked, but the detailed scenarios below were not individually reported. This increment extends visible screen-awake behavior to Dashboard while retaining the same session policy.
Record duration, battery change, engine conditions, actual timestamps/rates, and observed failures without sharing VINs or MAC addresses.

- [x] User broadly confirmed that the 0.5.1-dev background/screen fix worked; this does not assert a measured duration or the individual checks below.
- [ ] Connect explicitly from Adapter. Confirm the ongoing session notification appears when notification permission is granted and read-only acquisition starts automatically after connection.
- [ ] On Android 13+, test denied notification permission. Connection must remain usable, recovery guidance must appear, and Disconnect in Adapter must stop the session.
- [ ] During initialization, protocol/capability discovery, and live acquisition, leave Adapter and then Dashboard visible beyond the configured display timeout; confirm each stays awake.
- [ ] Before acquisition or after stopping, confirm the app no longer holds the display awake. Switching to Vehicles/Trips must also release the Adapter screen-on flag.
- [ ] While reading, switch between Adapter, Live, Vehicles, and Trips, then return. Confirm the same connection and advancing real-sample timestamps, with no automatic second session.
- [ ] Open another app and separately lock the screen for a measured interval. Return and confirm acquisition continued, rather than merely redisplaying old values.
- [ ] Recreate the Activity, for example by rotating the screen; confirm the same session continues without duplicate sockets or polling loops.
- [ ] While a connection attempt is pending, background and return; verify it can complete or reach its normal deadline without an extra attempt.
- [ ] Use notification Stop while the app is backgrounded, and then while Adapter is visible/bound. In both cases, confirm the socket/acquisition stop, notification disappears, and old values become stale.
- [ ] Open the notification and confirm it returns to Adapter without connecting or starting acquisition again. Confirm the notification contains no VIN, MAC, UUID, or raw readings.
- [ ] Disconnect from the UI; confirm notification and wake lock are released even though the Activity remains open. Repeat Stop/disconnect to check cleanup remains safe.
- [ ] Interrupt Bluetooth, adapter power, or permissions during acquisition; verify a terminal failure releases resources and manual reconnect is required. Observe battery/wake-lock state with an appropriate test tool where available.
- [ ] End the process/force-stop on a test installation; confirm there is no automatic service restart, reconnect, or restoration of in-memory readings. Saved profiles must remain after an ordinary restart.
- [ ] Measure battery use during an active session and after stopping. Confirm the app does not request a battery-optimization exemption.
- [ ] If unpairing is suspected, separately inspect Android's paired-device entry before and after the interruption. Record socket disconnection and actual bond removal as different outcomes.

Current connections automatically save telemetry for the selected profile. Stop recording suppresses saving for the rest of that connection unless resumed manually. The service is non-sticky and cannot promise survival after process termination or all device power policies.

## Dashboard and favorites checklist (RX8-006)

The 0.6.0-dev build has broad user confirmation. These individual dashboard checks remain unreported; use the current build, whose dashboard tab is labeled Live.

- [ ] Open Dashboard before connecting. Confirm it shows the actual connection/acquisition state without fabricated values or an automatic connection.
- [ ] Connect and start live acquisition in Adapter, then open Dashboard. Confirm the same session continues, with no duplicate connection or polling loop.
- [ ] Compare RPM, speed, and coolant cards with the ECU's known capabilities. Unsupported primary metrics must not appear as working sensors. Keep valid stationary zero speed distinct from missing/error data.
- [ ] If several ECUs respond, compare their groups and source identifiers. Do not treat values as averaged or assume an ECU's role from its identifier.
- [ ] Verify readable units, reading age, quality, and adapter/session metadata on the A53, including larger text settings and scrolling.
- [ ] Open Choose sensors; add and remove supported intake temperature, throttle position, and engine load. Primary metrics remain fixed. Record only actually supported choices.
- [ ] Restart the app and change selected vehicle profiles. Confirm saved favorites are restored app-wide without changing a vehicle UUID or assigning telemetry to a profile.
- [ ] With a controlled simulator/test fixture or an actual different capability set, verify a previously selected favorite stays explicitly unavailable and removable. Do not claim a synthetic capability change was tested on the RX-8.
- [ ] Stop or interrupt acquisition. Confirm old values are stale, age is identified as Age at stop, and missing/error readings never become zero.
- [ ] Use isolated synthetic test fixtures for normal, timeout and missing-data scenarios. Confirm production navigation has no Simulator tab and no real reading is replaced by a fixture value.
- [ ] Navigate Adapter/Dashboard/Vehicles/Trips and background/lock the app. Confirm the real session policy remains unchanged, only visible real acquisition screens stay awake, and no synthetic source runs from production navigation.
- [ ] Confirm favorites work without Internet and no trip, sample history, or upload is created. Record any visible preference-save failure instead of assuming persistence succeeded.

## Vehicle identity and VIN checklist (RX8-005)

Use fake identifiers for screenshots and automated fixtures. Record real-car outcomes without recording the actual VIN in the issue.
These checks are pending; a general live-reading success does not establish them.

- [ ] Create a named vehicle without a VIN; record an anonymous reference to its UUID and selected state.
- [ ] Restart the app and confirm the same UUID, alias, and selected profile are restored.
- [ ] Rename the profile and add a valid manual VIN; confirm its UUID is unchanged and source is manual.
- [ ] Check invalid manual input, a VIN already held by another profile, and explicit selection of the matching profile. No duplicate profile or silent selection should occur.
- [ ] Confirm saved and detected VINs are masked initially and that Show VIN/Hide VIN works. Do not share the revealed value.
- [ ] Connect and start live acquisition; confirm Read VIN is explicit and unavailable before the LIVE phase.
- [ ] Request the VIN, record available/no data/error and ECU source only, and confirm Mode 01 resumes after a complete response.
- [ ] Choose a candidate explicitly; confirm opening Vehicles keeps the current Bluetooth/acquisition session running and that reviewing the candidate alone does not save it.
- [ ] Attach a candidate to a VIN-less profile and verify the same UUID plus OBD/ECU provenance after restart.
- [ ] If a candidate matches a saved profile, verify explicit selection. If it differs from the selected profile's VIN, verify creation/selection without overwriting the existing VIN or UUID.
- [ ] Interrupt a VIN request; confirm timeout/framing loss closes the session, candidate state is cleared, and reconnect is manual.
- [ ] Confirm no-VIN/no-data operation remains usable and profiles can be managed without Internet access.

A structurally valid VIN is not proof of the car's identity. The implemented parser accepts the specific CAN 20-byte VIN message and five numbered legacy packets; this does not establish compatibility with every adapter or response layout.
Profiles, active selection, dashboard preferences, and recorded trips are stored. Backups/device transfer are disabled; trip exports do not provide an import/restore workflow. Clearing app data or reinstalling loses saved IDs. Do not clear a user's data as a routine validation step.

## Trip recording checklist (RX8-007)

The user broadly confirmed 0.7.0-dev works. Use that version or later on the A53 for the remaining scenarios below; individual results remain unreported. Automated tests use synthetic data and do not replace phone/car checks.

- [ ] Install over the existing app and confirm saved profiles, UUIDs, selection, and favorites remain available.
- [ ] Select a profile in Vehicles, connect and start real data in Adapter, then choose Start recording in Trips. Confirm the displayed name and increasing saved-sample count. Do not claim unsupported sensors were recorded.
- [ ] Change the selected profile during recording; confirm the existing trip still belongs to the original UUID/name.
- [ ] Record while another tab/app is visible and while the screen is locked for a measured interval. Stop recording and verify advancing timestamps in the export across that interval.
- [ ] Stop recording in Trips. Confirm history becomes available and live readings continue. Disconnect separately when finished.
- [ ] Record a separate trip and use notification Stop while backgrounded. Confirm the trip completes, its samples remain saved, and acquisition/notification end.
- [ ] Interrupt Bluetooth or adapter power during recording. Confirm the trip becomes Interrupted, existing samples remain, and no values are invented during the gap. Reconnect and confirm a separate journey starts automatically for the selected profile.
- [ ] On a dedicated test session, force-stop the process and relaunch. Confirm an unfinished trip becomes Interrupted at the last saved reading, without automatic connection or continuation. Uncommitted tail readings can be lost.
- [ ] Open finished history without Internet or an adapter. Check duration, valid/sample counts, peak values, speed source, coverage gaps, and the speed chart. Estimated distance must remain clearly different from the odometer.
- [ ] Export JSON and CSV through a chosen document provider and inspect both files. Check null/error samples, source/unit, per-trip sequences, UTC timestamps, vehicle/trip/sample UUIDs, and monotonic elapsed times. No VIN or adapter address fields should be exported.
- [ ] Cancel an export picker and return. Separately exercise a provider failure on a test destination and confirm no false success message. Remove any partial export manually.
- [ ] Confirm trip deletion removes its saved samples after confirmation and leaves profiles and previously exported copies intact. Active trips must not be deletable/exportable.
- [ ] Measure database usage during a realistic trip. Test insufficient-storage behavior only on a controlled test device/fixture; it must visibly stop recording rather than claim all readings were saved.

Issue #16 is closed at the user's explicit request with its acceptance checkboxes complete. Detailed measurements were not supplied; broader compatibility and recording evidence remains tracked here without reopening that completed fix. Broad success does not establish each individual scenario above.

## API preparation upgrade checklist (RX8-008)

The user broadly confirmed 0.8.0-dev worked correctly, and PR #20 is merged. Individual scenarios below were not separately reported. No real server is used.

- [ ] Confirm saved profiles, selected UUID, favorites, trip history, and exports remain available after the Room 2-to-3 upgrade.
- [ ] Open Trips and Refresh. Confirm Future uploads clearly says Uploads disabled and shows the existing samples waiting locally. No upload or server-configuration action should appear.
- [ ] Record a short new trip, stop, and refresh history. Confirm its saved readings are included in the local waiting count; restart the app and refresh again to check persistence.
- [ ] Export an old and a new terminal trip without Internet access. Confirm both formats remain available and the original readings/IDs are preserved. Export does not remove pending work or mark samples uploaded.
- [ ] On a disposable recorded trip, cancel deletion once, then confirm it. Refresh and verify its samples no longer contribute to the waiting count while other trips/profiles remain.
- [ ] Confirm recording, background acquisition, history, and exports remain usable with uploads disabled. Report a status-load failure separately from a history or recording failure.

Fake-server ACK, retry, lease, conflict, and oversized-batch behavior is validated in isolated automated fixtures. Do not connect this app to a test server or mark production data uploaded to satisfy these checks.

## Capability report checklist (RX8-010)

Use 0.9.0-dev or newer as an update with existing data intact. The user explicitly deferred these phone checks while development continues. PR #22 is merged on software verification; these checks remain pending until reported.

- [ ] Start real live data in Adapter and wait for discovery. Open Vehicle capabilities without selecting a profile or starting a trip; confirm the protocol and separate ECU PID lists appear.
- [ ] Distinguish an advertised PID from an implemented decoder and a valid reading. Confirm valid zero, missing/error, and stale states remain visibly different. Unimplemented PIDs must not gain invented readings.
- [ ] Capture a report and leave live acquisition running. Confirm the preview's time/values stay fixed until Capture latest is chosen; existing acquisition/recording continues.
- [ ] Choose Save JSON report and a document destination. Confirm the report's capture time, protocol, PIDs, units, values/quality, and ages match the preview. It must omit VINs/candidates, Bluetooth names/MACs, profile/trip IDs/aliases, and raw/free-form logs.
- [ ] Cancel the picker once and confirm no success message. Recreate the Activity or navigate away while a destination is pending; verify the intended frozen report survives. After process loss, an unmatched result must report that a new capture is needed rather than create an empty report.
- [ ] Stop acquisition and capture the retained snapshot. Confirm stopped/error data is identified as historical and not current. A new connection must not mix the earlier report with fresh discovery.
- [ ] Check Back, Adapter tab navigation, and notification open/Stop. Existing saved profiles, trips, exports, and disabled-upload queue must remain usable.

The snapshot uses the latest displayed state, not request counts or a sensor trace. Initial no-data states may predate a query. A report helps fill in the evidence matrix but is not proof of a 10-minute sustained session, background continuity, accuracy, battery behavior, or proprietary Mazda compatibility. Report engine conditions and test duration separately when providing hardware evidence.

## Trip sensor review checklist (RX8-012)

Use 0.10.0-dev or newer with existing app data intact. On October 11, 2026, the user reported that this version works well, and PR #25 is merged. The individual scenarios below were not described and remain deferred at the user's request; synthetic automated fixtures do not complete them.

- [ ] Open an existing completed or interrupted trip offline without an adapter. Confirm recorded sensor/ECU choices appear even if the current profile or favorites differ.
- [ ] Switch between recorded metrics and, where present, ECU sources. Check the unit, valid/sample count, observed minimum/maximum, and coverage. Sources must remain separate; unavailable or failed readings must not become zero.
- [ ] Where the trip actually contains them, inspect valid zero, negative Celsius, constant readings, and gaps. Do not manufacture hardware evidence for absent cases; automated fixtures cover them separately.
- [ ] Switch quickly between charts and trips, return to the same trip, and rotate. Confirm the visible chart matches the selected sensor/ECU and completed data never replaces a later selection.
- [ ] Confirm the trip overview's identified speed ECU and distance estimate stay unchanged while selecting charts. JSON/CSV export, confirmed deletion, live acquisition, and recording remain usable.

Only eligible adjacent intervals of at most five seconds contribute coverage. Chart point reduction may omit visual detail; displayed selected-series counts and extrema use the complete recorded stream.

## History browsing checklist (RX8-014)

Use 0.11.0-dev as an update with existing app data intact. These phone checks are deferred while software development continues; automated migration and synthetic browsing tests do not establish phone results.

- [ ] Confirm the upgrade retains profiles, selected vehicle, existing trips/samples, and disabled-upload counts. Do not clear app data as a routine test.
- [ ] Browse Older and Newer where enough saved trips exist. Refresh an older page, open details and return, change tabs, and rotate; confirm the same filter and browsing context remain. Newest should return to the latest matching trips.
- [ ] Filter another vehicle's history without changing the selected recording profile. Where names repeat or a profile was renamed, distinguish identities and verify older trip cards retain their recorded names.
- [ ] While recording for one vehicle, view another vehicle's history and confirm the original recording card and Stop control remain available. Filtering must not start, stop, or relabel a recording.
- [ ] Export a finished trip from an older page. Cancel deletion once, then optionally delete a disposable trip; confirm only that trip disappears and the page refills or returns to newest if empty.

If there are fewer than 51 actual saved trips or only one vehicle, report that limitation instead of inventing a larger hardware dataset. Fixtures cover more than 100 trips, timestamp ties, failures, and races separately.

## Automatic journeys and expanded readings (RX8-015)

Phone validation is deferred; the following are pending checks, not claimed results. Use an update with existing app data intact. Journeys contain telemetry only, without GPS.

- [ ] Connect once, then open a new app process with permission/Bluetooth available. Confirm one attempt to the last successful adapter, including an adapter absent from the paired list; Android may ask to pair.
- [ ] Check unavailable adapter, Bluetooth off, permission denial and manual Stop/Disconnect. Returning from tabs, rotation or a document picker must not trigger a reconnect loop. Manual selection/retry remains usable.
- [ ] Confirm connection directly starts initialization/readings and a journey for the selected profile. Check frozen UUID/name, connection timestamp, first actual samples, and no initial placeholder samples. Without a selected profile, follow guidance and confirm recording begins only after selection.
- [ ] Stop recording while live readings continue; confirm it stays stopped until manual resume or a fresh connection. Disconnect/notification Stop completes; a lost link interrupts. Check background/lock and delayed connection without inventing missing intervals.
- [ ] Review actual additional supported parameters, units and ECU sources. Record which return valid values; software support for 120 fields does not establish that this RX-8 provides all of them. Check fuel trims, airflow, timing, pressures and oxygen channels where advertised.
- [ ] Compare live values with saved trip charts and JSON/CSV exports. Verify negative/fractional values, failed/null states, and old trip compatibility. Favorites must not limit what is recorded.
- [ ] Check the real RPM dial, four single-line tabs, enlarged text, and no Simulator destination. Record measured sampling and battery behavior only if actually tested.

## Evidence matrix

| Data | Documented | Reported as supported | Reading validated | Unit | Notes |
| --- | --- | --- | --- | --- | --- |
| RPM | Yes, first-series service manual | Pending | Pending | rpm | General live-reading report does not identify this sensor |
| Coolant temperature | Yes, first-series service manual | Pending | Pending | °C | Individual result not supplied |
| Speed | Yes, first-series service manual | Pending | Pending | km/h | Individual result not supplied |
| VIN | Standard request; optional support | Pending | Pending | text | Do not record the actual value here |
| Other PIDs | To investigate | Pending | Pending | Pending | |

## Acceptable outcome

Record both successes and incompatibilities. A `NO DATA` response does not mean the sensor value is zero.
Do not replace physical testing with simulator screenshots, infer ECU communication from a connected socket, or expand a general app-success report into unreported test results.

## Android references

Checked on October 10, 2026:

- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
- [Finding paired and nearby devices](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices).
- [RFCOMM client connections](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices#connect-as-a-client).
