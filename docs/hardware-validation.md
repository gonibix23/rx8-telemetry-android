# RX-8 hardware validation

## Goal and current evidence

Confirm actual communication and data availability before promising sensors or sampling rates.
Vehicle: 2004 RX-8, 231 metric horsepower (CV), Europe. Reference phone: Galaxy A53 running Android 16. Adapter: iLC B0BDRHRR22.

On October 10, 2026, the user reported that the simulator app worked after testing.
The user confirmed Android 16 on the A53, completing RX8-002. The paired-only Bluetooth build failed to list the adapter.
The user reports that Car Scanner searches inside the app with plain Bluetooth selected and displays OBDII with its MAC address.
The user subsequently confirmed that the Bluetooth connection now works, following delivery of the discovery APK. PR #13 is merged.
Detailed scan/pairing steps, interruptions, reconnection behavior, and physical adapter revision were not supplied. RX8-001 and remaining RX8-003 checks stay open.
No ECU response, PID reading, or VIN result has been reported. RX8-004 provides the next read-only acquisition build for testing.
A simulator test and a Bluetooth socket connection are separate from ECU validation.

## Test record

For each hardware test, record:

- Date, app/tool version, phone model, and Android version.
- An anonymous test session identifier; never the full VIN or adapter MAC address.
- Visible adapter model/revision, Bluetooth type, and pairing result.
- Ignition/engine conditions, test duration, and steps actually performed.
- Observed connection, permission, timeout, cancellation, and reconnection behavior.
- For later ECU tests: identification response, detected protocol, supported PIDs, queries, and latency.
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
- [ ] Turn Bluetooth off or remove adapter power and record the observed response. No ECU heartbeat is implemented.
- [ ] Switch to Simulator, leave the app, and lock the screen in separate checks; confirm discovery stops and the connection closes. Returning to Adapter requires manual reconnection.
- [ ] Reconnect repeatedly and record failures or signs of duplicate connections.
- [ ] Repeat with Internet connectivity disabled and verify the simulator remains a separate synthetic-data screen.

The Connect action only opens Bluetooth. Selecting Read live data additionally initializes the ELM interface and starts read-only Mode 01 requests.
Bounded byte read/write and cancellation behavior require real I/O checks; do not claim these were physically tested by opening a socket alone.
The Classic/SPP connection has a successful user report. BLE transport, VIN retrieval, and recorded-trip continuity are not implemented.

## ECU validation sequence (RX8-001 and RX8-004)

Use Read live data in 0.4.0-dev for Mode 01 checks, or an appropriate diagnostic tool for separate RX8-001 evidence.
Record the exact app version and operating conditions. No real-car readings are established by automated parser or UI tests.

1. With the vehicle stationary and ignition on, connect Bluetooth and choose Read live data. Distinguish adapter initialization from an actual ECU reply.
2. Record the detected protocol, responding ECU identifiers, and supported-parameter count. Confirm multiple ECU groups remain separate when applicable.
3. Test RPM and coolant temperature; verify valid zero speed while stationary. Record unsupported, no-data, or parsing errors rather than replacing them with a value.
4. Maintain a session for at least 10 minutes; record the observed response rate, stale readings, errors, and any gradual slowdown.
5. Remove adapter power or interrupt Bluetooth. Check that reading deadlines end the session, old readings become stale, and a manual reconnect starts a fresh session.
6. Cancel initialization, leave Adapter, and background the app; verify polling stops and does not restart automatically.
7. Repeat without an Internet connection. The Simulator tab must remain clearly separate from these real readings.
8. VIN support remains a later app check: use a separate tool for RX8-001 evidence, or wait for RX8-005 implementation. Do not publish the full VIN.

Screen-locked recording continuity is a separate RX8-007 check after persistence and a recording service are implemented.
To validate speed while moving, use an already running recording or a passenger; review results afterward.

## Evidence matrix

| Data | Documented | Reported as supported | Reading validated | Unit | Notes |
| --- | --- | --- | --- | --- | --- |
| RPM | Yes, first-series service manual | Pending | Pending | rpm | |
| Coolant temperature | Yes, first-series service manual | Pending | Pending | °C | |
| Speed | Yes, first-series service manual | Pending | Pending | km/h | |
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
