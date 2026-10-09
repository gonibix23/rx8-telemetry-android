# RX-8 hardware validation

## Goal

Confirm actual communication and data availability before promising sensors or sampling rates.
Vehicle: 2004 RX-8, 231 metric horsepower (CV), Europe. Phone: Galaxy A53. Adapter: iLC B0BDRHRR22.

## Test record

- Date, app/tool version, and Android version.
- Test session identifier without the full VIN.
- Visible adapter model/revision and identification response, without treating the reported text as proof of authenticity.
- Bluetooth type and detected OBD protocol.
- Ignition/engine conditions and test duration.
- ECU-supported PIDs, queries made, and latency.
- VIN result: valid, absent, error, or unsupported; never attach the full VIN.
- Disconnections, timeouts, and reconnection behavior.

## Validation sequence

1. With the vehicle stationary, check adapter pairing and connection.
2. Distinguish adapter responses from ECU responses.
3. Detect the protocol and query supported PID blocks.
4. Test RPM and coolant temperature; verify zero speed while stationary.
5. Query the VIN once and check fragmented response reconstruction.
6. Maintain a session for at least 10 minutes; measure effective sampling rate and errors.
7. Lock the screen, return to the app, and verify recording continuity.
8. Disconnect/reconnect Bluetooth and check recovery without duplicates.
9. Repeat without an Internet connection.
10. To validate speed while moving, use an already running recording or a passenger; review the results afterward.

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
Do not replace physical testing with simulator screenshots. Share only anonymized logs.
