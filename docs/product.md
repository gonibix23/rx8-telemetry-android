# Product and scope

## Confirmed context

The user has a European-market 2004 Mazda RX-8 with 231 PS and a Samsung Galaxy A53 running Android 16.
User's adapter: iLC OBD2 Bluetooth, ASIN B0BDRHRR22.
Selected priority: live data and trip recording. Prepare for a future API without developing it yet.
The user reported a successful simulator app test on October 10, 2026. The paired-only Bluetooth build failed to list the adapter.
Car Scanner reportedly finds OBDII and its MAC address through in-app search with plain Bluetooth selected.
The user confirmed Bluetooth works and reported successful live readings on October 11, 2026; PR #14 is merged. The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed correct readings in 0.5.0-dev. Individual sensors, ECU identifiers, measured rates, sustained lifecycle behavior, VIN retrieval, and profile persistence remain unverified.
The user broadly confirmed that 0.6.0-dev worked on October 11, 2026, and PR #18 is merged. This does not establish individual sensor results, every favorite-persistence scenario, or measured session duration/battery use. The current 0.7.0-dev increment (version code 8) adds explicit local trip recording, offline history, summaries, speed charts, JSON/CSV export, and confirmed deletion. RX8-004 and the detailed hardware checks remain open.

## Current live dashboard

The Live tab presents the dashboard: RPM, speed, and coolant temperature in fixed primary positions when reported as supported. Intake temperature, throttle position, and engine load can be selected as extra favorites from the session's known capabilities.
Each ECU has its own values; the app does not average different ECU responses or identify an engine controller by assumption. Cards show units, age, and quality, preserving valid zero readings and distinguishing missing, unsupported, failed, and stale readings.
Favorite selections persist across app restarts as app-wide preferences, independent of the selected vehicle or ECU. A saved favorite unavailable in another session remains visibly unavailable. Selecting a favorite changes presentation, not sensor support or polling priority.
Live shows real ECU data and connection/acquisition status. Opening it does not connect, start polling, or record a trip; use Adapter for connection/acquisition and Trips for explicit recording. It does not display simulator values as car readings. Estimated distance is confined to saved-trip summaries.
The Simulator tab previews the same cards with a prominent synthetic-data label and its normal, timeout, and missing-data controls. Simulator sampling remains limited to that visible foreground tab.
The broad 0.6.0-dev success report is recorded; detailed layout, favorite-persistence, and navigation scenarios still need the A53 checks in RX8-006.

## Current vehicle profiles

The Vehicles tab lets the user explicitly create a named profile, select it, rename it, and add a VIN later. Room retains the UUID and selected profile across app restarts.
Names contain 1-60 characters after trimming. The optional VIN contains 17 letters/numbers excluding I, O, and Q; this validates its structure, not ownership or the car's identity.
Read VIN is available only after live acquisition starts. The user chooses a candidate in Adapter, then reviews it in Vehicles before attaching it to a VIN-less profile or creating a separate profile.
A matching VIN offers explicit selection of its existing profile. A different VIN cannot overwrite the selected profile. Adding a VIN or changing the name never changes the UUID.
VINs are masked by default and record manual entry or OBD/ECU provenance. A car can be used without a VIN; its Bluetooth adapter does not identify it.
Switching to Vehicles leaves an active Bluetooth/acquisition session running. Profiles, selected vehicle, dashboard favorites, and explicitly recorded trips/samples are saved locally. API synchronization remains planned.
Backups/device transfer are disabled. Finished trips can be exported, but importing/restoring the app is not implemented. Clearing app data or reinstalling loses saved profiles, UUIDs, and local trips.

## Current session behavior

Connect is an explicit action from the visible app. Read live data starts acquisition separately; a connection alone does not query the ECU.
The active session continues when changing tabs, using another app, locking the screen, or recreating the Activity. Discovery stops when leaving Adapter, and simulation still samples only while Simulator is visible.
Adapter and Live stay awake only during visible real initialization, protocol/capability discovery, and live acquisition. Trips, Vehicles, Simulator, and an idle connected adapter do not hold the screen on.
Disconnect in Adapter or Stop in the ongoing notification ends live acquisition and completes an active trip after queued writes finish. Stop recording in Trips completes only the trip and leaves live acquisition running. Opening the notification returns to Adapter. Notification denial does not block connection; in-app guidance explains how to enable notifications and disconnect.
Active acquisition consumes battery while running, including with the screen locked. No battery-optimization exemption is requested. There is no automatic reconnect or automatic recording restart. After a process interruption, already committed samples remain and unfinished saved trips are marked interrupted on recovery.

The user reported screen sleep and background disconnection in 0.5.0-dev. These were consequences of the old lifecycle policy, not confirmed Bluetooth bond removal. The user subsequently confirmed 0.5.1-dev worked, broadly confirming the fix in merged PR #17. [Issue #16](https://github.com/gonibix23/rx8-telemetry-android/issues/16) retains detailed checks; duration, battery use, notification scenarios, and actual bond removal remain unverified.
Background live acquisition becomes a saved trip only after the user chooses Start recording in Trips.

## Current trip recording and history

The app has five tabs: Adapter, Live, Trips, Vehicles, and Simulator. In Vehicles, explicitly select a profile; connect and start real live data in Adapter; then choose Start recording in Trips. A trip freezes the vehicle UUID and alias at that moment. Changing the selected profile or renaming it never relabels an existing trip.
Only newly observed real OBD samples are recorded, with sample/session UUIDs, sequence, UTC and monotonic time, units, ECU source, and quality. The app does not record the simulator. A missing, stale, or failed reading never becomes a fabricated valid value.
An explicitly started recording uses the existing connected-device foreground service across tab changes, backgrounding, and screen lock. Stop recording saves the terminal state while leaving live readings active. Notification Stop ends both. Unexpected connection loss, acquisition failure, or storage failure ends the trip as interrupted; reconnecting requires a new manual recording.
Trips shows the latest 100 saved sessions offline, plus total app-database storage usage. The active card reports the frozen vehicle name and samples confirmed as saved. Storage errors stop recording and remain visible; queued or failed writes are not reported as saved.
Finished trips expose sample counts, duration, valid recorded peaks, and a bounded speed chart. Speed uses one identified ECU, and chart gaps stay unjoined. Estimated distance integrates valid speed intervals only, alongside covered and uncovered duration; it is not the odometer. Peak recorded RPM may come from any valid RPM source.
JSON/CSV export opens Android's document picker so the user chooses the location. Success is shown only after writing, flushing, and closing the document. Exports contain the vehicle UUID/recorded alias, timestamps, readings, ECU sources, and quality; they exclude VINs and Bluetooth addresses. Exports cannot currently be imported to restore the app.
Deletion requires confirmation and removes the selected trip and its samples; externally exported files remain. Active trips cannot be analyzed, exported, or deleted until recording ends. No backend, Internet permission, GPS/maps, or uploads are added.
Real A53 recording, interruption recovery, export-provider behavior, and storage-limit checks remain pending. Earlier live/dashboard success does not validate saved trips.

## Expected MVP outcome

The user selects the adapter and vehicle profile, connects, sees the available data, and starts a trip.
They can lock the screen during recording, stop recording from a notification, and view history without Internet access.

## Screens

| Screen | Contents |
| --- | --- |
| Adapter (implemented) | Discovery, permissions, Bluetooth/ECU status, live readings, optional VIN request |
| Vehicles (implemented) | Saved profiles, selection, permanent UUID, alias, optional VIN and source |
| Simulator (implemented) | Clearly synthetic dashboard preview and failure scenarios |
| Live (implemented) | Supported RPM, speed, coolant, extra favorites, per-ECU status and reading age |
| Trips (implemented) | Explicit recording, latest 100 saved sessions, storage usage, and offline history |
| Trip details (inside Trips) | Sample counts, valid peaks, estimated distance, speed chart/gaps, JSON/CSV export, and confirmed deletion |
| Settings (planned) | Units, sampling rate/priorities, storage policy, and synchronization status |

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
