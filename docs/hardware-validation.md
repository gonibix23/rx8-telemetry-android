# RX-8 hardware validation

## Goal and current evidence

Confirm actual communication and data availability before promising sensors or sampling rates.
Vehicle: 2004 RX-8, 231 metric horsepower (CV), Europe. Reference phone: Galaxy A53 running Android 16. Adapter: iLC B0BDRHRR22.

On October 10, 2026, the user reported that the simulator app worked after testing.
The user confirmed Android 16 on the A53, completing the remaining device information for RX8-002. Bluetooth pairing has not been tried yet; RX8-001 and RX8-003 hardware validation remain pending.
No detailed lifecycle test, adapter connection, ECU response, PID, or VIN result has been reported.
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

Use the actual adapter with the vehicle stationary. The Android version is confirmed; pairing and all adapter checks below remain pending.

- [x] Samsung Galaxy A53 runs Android 16, confirmed by the user.
- [ ] Record the app version used for the Bluetooth transport test.
- [ ] Pair the adapter in Android settings, then return to the app.
- [ ] On API 31+, check denied and granted Nearby devices permission; confirm the screen remains usable.
- [ ] Confirm the adapter appears among paired Classic/dual devices; record a missing or differently typed device as a limitation.
- [ ] Select it and connect; record success, error, or the 15-second application timeout.
- [ ] Confirm that a successful socket connection is labelled separately from ECU communication, which remains not checked.
- [ ] Cancel a connection attempt and disconnect a connected session; confirm the controls allow another manual attempt.
- [ ] Turn Bluetooth off or remove adapter power and record the observed response. No ECU heartbeat is implemented.
- [ ] Switch to Simulator, leave the app, and lock the screen in separate checks; confirm the connection closes and requires manual reconnection after returning to Adapter.
- [ ] Reconnect repeatedly and record failures or signs of duplicate connections.
- [ ] Repeat with Internet connectivity disabled and verify the simulator remains a separate synthetic-data screen.

The connection UI sends no ELM/OBD requests or other application bytes. System connection handshakes still occur.
This screen cannot yet validate byte responses, OBD protocols, sensor values, VIN retrieval, or recorded-trip continuity.
Bounded byte read/write and cancellation behavior also require transport-level checks; do not claim these were physically tested by opening a socket alone.
Secure Classic/SPP remains an assumption until the adapter test establishes support. BLE and active discovery are not implemented.

## ECU validation sequence (RX8-001 and later RX8-004)

Use an appropriate diagnostic tool for RX8-001, or the app once OBD acquisition is implemented.
These steps cannot be completed using the current connection screen alone.

1. With the vehicle stationary, distinguish adapter responses from ECU responses.
2. Detect the protocol and query supported PID blocks.
3. Test RPM and coolant temperature; verify zero speed while stationary.
4. Query the VIN once and check fragmented response reconstruction.
5. Maintain a session for at least 10 minutes; measure effective sampling rate and errors.
6. Disconnect/reconnect Bluetooth and check recovery without duplicate sessions.
7. Repeat without an Internet connection.

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
- [Querying paired devices](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices#query-paired-devices).
- [RFCOMM client connections](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices#connect-as-a-client).
