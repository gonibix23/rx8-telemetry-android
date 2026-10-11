# Product and scope

## Confirmed context

The user has a European-market 2004 Mazda RX-8 with 231 PS and a Samsung Galaxy A53 running Android 16.
User's adapter: iLC OBD2 Bluetooth, ASIN B0BDRHRR22.
Selected priority: live data and trip recording. Prepare for a future API without developing it yet.
The user reported a successful simulator app test on October 10, 2026. The paired-only Bluetooth build failed to list the adapter.
Car Scanner reportedly finds OBDII and its MAC address through in-app search with plain Bluetooth selected.
The user confirmed Bluetooth works and reported successful live readings on October 11, 2026; PR #14 is merged. The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed correct readings in 0.5.0-dev. Individual sensors, ECU identifiers, measured rates, sustained lifecycle behavior, VIN retrieval, and profile persistence remain unverified.
The current 0.5.1-dev increment keeps an explicitly started connection and acquisition active across tabs, backgrounding, screen lock, and Activity recreation. Saved vehicle profiles and optional manual/OBD VINs remain available alongside the six Mode 01 decoders. RX8-004 stays open for detailed hardware checks.

## Current vehicle profiles

The Vehicles tab lets the user explicitly create a named profile, select it, rename it, and add a VIN later. Room retains the UUID and selected profile across app restarts.
Names contain 1-60 characters after trimming. The optional VIN contains 17 letters/numbers excluding I, O, and Q; this validates its structure, not ownership or the car's identity.
Read VIN is available only after live acquisition starts. The user chooses a candidate in Adapter, then reviews it in Vehicles before attaching it to a VIN-less profile or creating a separate profile.
A matching VIN offers explicit selection of its existing profile. A different VIN cannot overwrite the selected profile. Adding a VIN or changing the name never changes the UUID.
VINs are masked by default and record manual entry or OBD/ECU provenance. A car can be used without a VIN; its Bluetooth adapter does not identify it.
Switching to Vehicles leaves an active Bluetooth/acquisition session running. Only profiles are saved: trips, telemetry history, and API synchronization remain planned.
Backups/device transfer are disabled, and export/restore is not implemented. Clearing app data or reinstalling loses the saved IDs.

## Current session behavior

Connect is an explicit action from the visible app. Read live data starts acquisition separately; a connection alone does not query the ECU.
The active session continues when changing tabs, using another app, locking the screen, or recreating the Activity. Discovery stops when leaving Adapter, and simulation still samples only while Simulator is visible.
Adapter stays awake only during visible initialization, protocol/capability discovery, and live acquisition. Other screens and an idle connected adapter do not hold the screen on.
Disconnect in Adapter or Stop in the ongoing notification ends the session. Opening the notification returns to Adapter. Notification denial does not block connection; in-app guidance explains how to enable notifications and disconnect.
Active acquisition consumes battery while running, including with the screen locked. No battery-optimization exemption is requested. There is no automatic reconnect or process-death recovery.

The user reported screen sleep and background disconnection in 0.5.0-dev. These were consequences of the old lifecycle policy, not confirmed Bluetooth bond removal. [Issue #16](https://github.com/gonibix23/rx8-telemetry-android/issues/16) tracks the fix; real A53 lifecycle and battery checks remain pending.
This is continued live acquisition, not saved trip recording.

## Expected MVP outcome

The user selects the adapter and vehicle profile, connects, sees the available data, and starts a trip.
They can lock the screen during recording, stop recording from a notification, and view history without Internet access.

## Planned screens

| Screen | Contents |
| --- | --- |
| Adapter (implemented) | Discovery, permissions, Bluetooth/ECU status, live readings, optional VIN request |
| Vehicles (implemented) | Saved profiles, selection, permanent UUID, alias, optional VIN and source |
| Live data | RPM, coolant temperature, speed, and favorites from supported sensors |
| Trips | Sessions, duration, charts, connection gaps, and summary |
| Trip details | Samples, valid minimum/maximum values, export, and deletion |
| Settings | Units, sampling rate/priorities, storage, and synchronization status |

The MVP does not include maps. A trip is a telemetry session.
Distance calculated from speed must be labeled as an estimate, not the car's odometer reading.

## Candidate data and limitations

The first-series workshop manual lists RPM, speed, coolant temperature, intake air temperature, load, MAF, throttle position, fuel trims, and ignition timing advance.
This is a candidate list: each parameter must be confirmed through capability discovery and readings from the real car.

The app optionally requests the VIN through Mode 09 PID 02 after an explicit action. It can continue without it; actual VIN availability on the RX-8 is not yet established.
Total mileage, oil temperature/pressure, and other Mazda data are not guaranteed through generic OBD.
An oil pressure switch does not provide continuous pressure in bar.
Compression requires an external measurement and will not be offered as an OBD reading.
ABS/SRS are not part of this adapter's promised compatibility.

## MVP acceptance criteria

- Works without Internet access or an API.
- Distinguishes a Bluetooth connection from successful ECU communication.
- Discovers accessible parameters and does not display errors as zero.
- Retains vehicles, sessions, and samples across restarts.
- Adding a VIN later does not split or lose history.
- Keeps an explicitly started recording running with the A53 screen locked.
- Preserves saved data after an interruption and marks the gaps.
- Exports and deletes trips with clear scope.
- Supports demonstrations using clearly identified synthetic data.
- Prepares batches for the future contract without making real uploads.

## Outside this first version

API/server/remote database, cloud accounts, GPS maps, advanced diagnostics, fault clearing, ECU reprogramming, and actuator control.
Additional Mazda sensors require specific investigation and testing before inclusion.

## References

Accessed on October 10, 2026. The Mazda manuals are reproduced on third-party sites.

- [Product, same ASIN on Amazon Italy](https://www.amazon.it/iLC-Bluetooth-Scansione-Collegare-Dispositivi/dp/B0BDRHRR22).
- [Mazda: diagnostic modes](https://foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE010200102T02.html).
- [Mazda: parameter monitor](https://www.foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE010200102T05.html).
- [Mazda: instrument cluster, ODOCOUNT, and OPSC](https://foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE092255430T06.html).
- [Mazda: compression inspection](https://rx8handbuch.de/esicont/en/srvc/html/BHE011002000W03.html).
- [ELM327: commands and VIN responses](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf).
