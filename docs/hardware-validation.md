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
Issue #16 / 0.5.1-dev changes the lifecycle policy. Background/lock continuity, screen-awake behavior, notifications, and battery use still need the checks below.
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
- [ ] Switch to Vehicles or Simulator, leave the app, and lock the screen in separate checks. Discovery must stop; an explicitly started connection/acquisition session must continue. Returning to Adapter must observe the same session without another connect attempt.
- [ ] Reconnect repeatedly and record failures or signs of duplicate connections.
- [ ] Repeat with Internet connectivity disabled and verify the simulator remains a separate synthetic-data screen.

The Connect action only opens Bluetooth. Selecting Read live data additionally initializes the ELM interface and starts read-only Mode 01 requests.
Bounded byte read/write and cancellation behavior require real I/O checks; do not claim these were physically tested by opening a socket alone.
The Classic/SPP connection and general live readings have successful user reports. Optional VIN retrieval was introduced in 0.5.0-dev but remains unverified on the car. BLE transport and recorded-trip continuity are not implemented.

## ECU validation sequence (RX8-001 and RX8-004)

Use Read live data in 0.5.1-dev for Mode 01 checks, or an appropriate diagnostic tool for separate RX8-001 evidence.
Record the exact app version and operating conditions. No real-car readings are established by automated parser or UI tests.

1. With the vehicle stationary and ignition on, connect Bluetooth and choose Read live data. Distinguish adapter initialization from an actual ECU reply.
2. Record the detected protocol, responding ECU identifiers, and supported-parameter count. Confirm multiple ECU groups remain separate when applicable.
3. Test RPM and coolant temperature; verify valid zero speed while stationary. Record unsupported, no-data, or parsing errors rather than replacing them with a value.
4. Maintain a session for at least 10 minutes; record the observed response rate, stale readings, errors, and any gradual slowdown.
5. Remove adapter power or interrupt Bluetooth. Check that reading deadlines end the session, old readings become stale, and a manual reconnect starts a fresh session.
6. Cancel initialization or use Disconnect/notification Stop and verify acquisition ends. Separately leave Adapter/background the app and verify acquisition continues; use the lifecycle checklist below.
7. Repeat without an Internet connection. The Simulator tab must remain clearly separate from these real readings.
8. Follow the optional VIN/profile checklist below. Do not publish the full VIN or treat a valid-looking response as proof of vehicle ownership.

Screen-locked live acquisition is part of issue #16. Saved recording continuity remains a separate RX8-007 check after telemetry persistence and recording controls are implemented.
To validate speed while moving, use an already running recording or a passenger; review results afterward.

## Background session and screen-awake checklist (issue #16)

Use 0.5.1-dev on the A53 with the actual adapter and vehicle stationary. These checks remain pending; the successful 0.5.0-dev live-reading report does not validate this lifecycle change.
Record duration, battery change, engine conditions, actual timestamps/rates, and observed failures without sharing VINs or MAC addresses.

- [ ] Connect explicitly from Adapter. Confirm the ongoing session notification appears when notification permission is granted and no OBD requests start until Read live data is chosen.
- [ ] On Android 13+, test denied notification permission. Connection must remain usable, recovery guidance must appear, and Disconnect in Adapter must stop the session.
- [ ] During initialization, protocol/capability discovery, and live acquisition, leave Adapter visible beyond the configured display timeout; confirm it stays awake.
- [ ] Before acquisition or after stopping, confirm the app no longer holds the display awake. Switching to Vehicles/Simulator must also release the Adapter screen-on flag.
- [ ] While reading, switch to Vehicles and Simulator, then return. Confirm the same connection and advancing real-sample timestamps, with no automatic second session.
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

No trip or telemetry history is saved by these checks. The service is non-sticky and cannot promise survival after process termination or all device power policies. The simulator remains foreground-only.

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
Only profiles are stored. No trip history exists to test for continuity yet. Backups/device transfer are disabled and there is no export/restore feature; clearing app data or reinstalling loses saved IDs. Do not clear a user's data as a routine validation step.

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
