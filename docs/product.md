# Product and scope

## Content-sized floating readings (0.15.3-dev)

The overlay fits its widest parameter, value/unit or quality/age line plus padding, rather than reserving a fixed width. It adapts when the readings or available space change. Width stays within the display, long text wraps, and overflowing source rows scroll. Typography, dragging, stationary-hold dismissal and accessible source identity remain unchanged.

The owner completed the remaining validations in issues #4, #6, #7 and #24, which were closed on October 13, 2026. This supersedes their historical pending notes below. Independent compatibility, transport, proprietary-parameter and deployed-upload checks remain open; no additional measurements are inferred.

## Trips feedback and navigation (0.15.2-dev)

Upload feedback appears only inside Trips > Uploads, with the existing durable-confirmation, waiting, progress and failure meanings. History renders one navigation group for available filtered pages. A single page needs no directional navigation; explain the boundary and retain Refresh. Loading and genuine busy operations still prevent conflicting actions. Page filtering, ordering and restored browsing context remain unchanged.

## Live modes and compact floating readings (0.15.0-dev)

Live opens in normal mode with the configured cards and one Edit action. Edit mode contains sensor search, card customization, column selection, vehicle actions and floating-panel settings. Done hides these controls again; mode/search state survives tab navigation and Activity recreation. Adapter identity and protocol details are in Adapter. Upload feedback is now confined to Trips > Uploads.

One/two columns is a durable presentation preference independent of cards and trips. Compact cards share a row; an odd compact card keeps its half width and large cards span both columns. Narrow windows and larger fonts fall back to one column. Card numeric text fits its available width without converting valid values or hiding quality/age.

The overlay has no title, vehicle name, drag hint, Close button or visible ECU labels. Each row contains parameter, value/unit and age, with explicit non-current quality. Stationary hold closes the panel; dragging, scrolling, extra pointers, cancellation and removal cancel a pending hold. Drag the whole panel when rows fit, or a parameter label when the list scrolls. Source identities remain separate in rows and accessibility descriptions.

Trips record all implemented, advertised supported scalar fields independently of Live mode, search, visible cards, styles, columns or overlay selection. Actual fresh outcomes include null failed readings; unsupported/unimplemented fields, scaling metadata, VIN, cached/stale display states and placeholders are not trip sensor samples. Recording starts only for an active selected-profile journey and stops on explicit recording stop or failure.

## Customisation and connection workflow (0.14.0-dev)

RX8-020 supersedes the earlier fixed-primary-card description. Every implemented parameter can be selected or removed, including RPM, speed and coolant. Search includes unavailable sensors; selecting one never invents support or changes polling. Existing installations start with their original basic sensors and extra favourites. In Edit mode, long-press or the card Edit action opens a card editor for digital/dial/bar style, compact/large size, earlier/later position and overlay inclusion. Changes persist only after a successful preference write. Every ECU stays separate and readings retain quality, units and age. Gauge ranges describe display domains, not engine safety limits.

Adapter contains Bluetooth discovery, selection, permissions, connection/favourite controls and adapter identity/protocol details. Live contains readings, with optional VIN review and capability reports in Edit mode. Save an explicit favourite to retry at 30-second intervals only while the app is visible, disconnected, permitted and not scanning/connecting. Disconnect or notification Stop pauses retries until Resume; without a favourite the original single startup attempt applies.

The floating overlay is enabled explicitly for a connected session. Android's display-over-other-apps permission is requested only from that action. Up to six selected metrics retain separate ECU sources, quality and age above another app; dragging moves it and a stationary hold disables it. It shares the foreground session's readings and starts no OBD requests. It is hidden inside the app and on the lock screen, and removed on disconnect, permission loss or service cleanup. Enablement is not restored after process death. No GPS is collected from the navigation app.

Trip details use a Chart parameter dropdown rather than one chip per sensor; ECU selection remains separate. Uploads feedback in Trips refreshes from local durable acknowledgments, showing waiting, sending, failure or confirmed trip counts. Confirmation requires every sample and current vehicle/session metadata, including empty trips. Local copies remain available offline.

## Confirmed context

The user has a European-market 2004 Mazda RX-8 with 231 PS and a Samsung Galaxy A53 running Android 16.
User's adapter: iLC OBD2 Bluetooth, ASIN B0BDRHRR22.
Selected priority: live data, offline trip recording and completed-trip uploads. RX8-017 explicitly adds an anonymous MongoDB Atlas API in this repository; hosting on the owner's server is deferred.
The user reported a successful simulator app test on October 10, 2026. The paired-only Bluetooth build failed to list the adapter.
Car Scanner reportedly finds OBDII and its MAC address through in-app search with plain Bluetooth selected.
The user confirmed Bluetooth works and reported successful live readings on October 11, 2026; PR #14 is merged. The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed correct readings in 0.5.0-dev. Individual sensors, ECU identifiers, measured rates, sustained lifecycle behavior, VIN retrieval, and profile persistence remain unverified.
The user broadly confirmed that 0.6.0-dev worked on October 11, 2026, and PR #18 is merged. This does not establish individual sensor results, every favorite-persistence scenario, or measured session duration/battery use. The user subsequently confirmed that 0.7.0-dev worked perfectly, and PR #19 is merged. It provides local trip recording, history, summaries, charts, exports, and deletion; detailed provider, interruption, storage, and duration checks remain open. The user also confirmed 0.8.0-dev worked correctly, and PR #20 is merged with API uploads disabled. PR #22 adds capability reports in 0.9.0-dev and is merged with phone validation explicitly deferred by the user. The earlier 0.10.0-dev increment (version code 11) adds selectable recorded-sensor charts.

The user subsequently reported that 0.10.0-dev works well, and PR #25 is merged. This confirms the delivered build broadly; individual chart/ECU, rotation, and export checks remain unreported. RX8-011 CI maintenance is merged in PR #26. The 0.11.0-dev increment adds paged history and independent vehicle filtering, merged in PR #30. Version 0.12.1-dev (code 14) adds search to the sensor picker. Version 0.12.0-dev (code 13) adds the automatic session flow, expanded readings and real RPM dial requested by the user; journeys are telemetry only, without GPS.

## Current capability reports

After successful discovery, Live offers Vehicle capabilities. It captures the current real acquisition snapshot, including retained stopped/error data, without requiring a saved profile or recording a trip. Each ECU lists its advertised Mode 01 PIDs and the latest displayed states of the implemented scalar metrics. Scaling metadata PIDs 4F/50 are identified separately; they are used only when advertised. Other unimplemented PIDs remain visible without invented names/values and are not queried.

A report is frozen at capture time. Capture latest snapshot explicitly replaces the preview, while exporting retains the exact selected report across the document picker and Activity recreation. The view is temporary until exported; process death clears it. Export errors and cancellation do not become success messages.

Reports contain capture time, acquisition state, recognized protocol, duration/rate from the existing snapshot, ECU addresses, advertised PIDs, and latest sensor values/quality. They exclude VINs, Bluetooth names/MACs, profile/trip identifiers, free-form adapter/error text, and raw logs. This is a snapshot of displayed state, which may include initial no-data placeholders; it does not count request outcomes or establish sustained sampling, battery, or sensor accuracy. See [Capability reports](capability-report.md).

## Current live dashboard

The four tabs are Adapter, Live, Trips and Vehicles. Labels stay on one line; navigation adapts to available width. Old saved Simulator navigation resolves to Live. Production navigation no longer creates or activates a simulator.

Live uses the real per-ECU snapshot. Every selected parameter has a configurable digital, dial or bar card; RPM, speed and coolant are editable selections too. The scale is a display domain, not an inferred rev limit. Missing or failed readings do not move the dial to a fabricated zero. Stopped/stale values remain clearly historical. Each ECU retains its independent values, units, quality and age.

Choose sensors includes a search bar for case-insensitive names and hexadecimal PIDs (for example, intake temperature, 0F or PID 0x0F). Each word must match. Clear search restores the eligible list; a no-match message and result count explain the filter. Searching never removes hidden favorites. The query survives closing/reopening the picker and saved Activity state restoration. Keyboard completion returns to the results.

Favorites persist app-wide using stable metric IDs; unavailable saved choices remain visible and removable. Choosing a favorite affects presentation only: every implemented supported parameter is acquired and recorded. Saved-trip statistics and sensor charts remain in Trips. Synthetic fixtures remain available to tests without a production Simulator tab.

## Current vehicle profiles

The Vehicles tab lets the user explicitly create a named profile, select it, rename it, and add a VIN later. Room retains the UUID and selected profile across app restarts.
Names contain 1-60 characters after trimming. The optional VIN contains 17 letters/numbers excluding I, O, and Q; this validates its structure, not ownership or the car's identity.
Read VIN is available only after live acquisition starts. The user chooses a candidate in Live, then reviews it in Vehicles before attaching it to a VIN-less profile or creating a separate profile.
A matching VIN offers explicit selection of its existing profile. A different VIN cannot overwrite the selected profile. Adding a VIN or changing the name never changes the UUID.
VINs are masked by default and record manual entry or OBD/ECU provenance. A car can be used without a VIN; its Bluetooth adapter does not identify it.
Switching to Vehicles leaves an active Bluetooth/acquisition session running. Profiles, selected vehicle, dashboard favorites, recorded trips/samples, and pending work are saved locally. Completed-trip uploads are optional and require an explicitly configured HTTPS API URL.
Backups/device transfer are disabled. Finished trips can be exported, but importing/restoring the app is not implemented. Clearing app data or reinstalling loses saved profiles, UUIDs, and local trips.

## Current session behavior

An explicit favourite opts into 30-second retries while visible and eligible; Stop/Disconnect pauses them until Resume. Without a favourite, the visible app attempts the last successful Classic adapter once per process after Bluetooth and permission are available. Manual selection/retry remain in Adapter. No new attempt starts in the background and no killed journey is restored.

A successful socket connection automatically continues into read-only ELM initialization, protocol detection and capability discovery. The continuation belongs to that connection even if the user changes tabs or locks the screen while it opens. Bluetooth success is still distinct from ECU success. Discovery remains limited to the visible Adapter screen.

The selected persisted vehicle starts an automatic telemetry journey at connection time. A missing profile does not prevent live readings; select/create one to begin saving from then onward. The app never guesses identity from the adapter or silently saves a VIN. Stop recording completes the trip and prevents automatic restart in the same connection; the manual start control remains available. Disconnect and notification Stop complete the active trip after queued writes finish; connection/acquisition loss interrupts it.

Adapter and Live stay awake only during visible real acquisition. Background/lock/recreation retain the service-owned session. A bounded renewed partial wake lock supports acquisition; notification permission denial is handled visibly. No battery-optimization exemption is requested. Startup recovery retains committed samples and marks unfinished journeys interrupted at their last saved reading. A later new connection creates a separate journey.

These changes were explicitly requested for 0.12.0-dev. Earlier broad Bluetooth/live/background success does not establish the new automatic flow, measured battery behavior, or individual sensor support; phone validation remains deferred.

## Current trip recording and history

The app has four tabs: Adapter, Live, Trips and Vehicles. Select the intended vehicle once; connecting automatically starts reading and a telemetry journey. A trip freezes the vehicle UUID and alias at its start. Changing the selected profile or renaming it never relabels an existing trip.
Only newly observed real OBD samples are recorded, with sample/session UUIDs, sequence, UTC and monotonic time, units, ECU source, and quality. The app does not record the simulator. A missing, stale, or failed reading never becomes a fabricated valid value.
Automatic and manually resumed recording use the existing connected-device foreground service across tab changes, backgrounding, and screen lock. Stop recording saves the terminal state while leaving live readings active. Notification Stop ends both. Unexpected connection loss, acquisition failure, or storage failure ends the trip as interrupted; a successful new connection starts a new automatic journey.
The 0.11.0-dev increment shows one page of up to 50 saved sessions offline, plus total app-database storage usage. Newer and Older reach the rest of the history, ordered by start time and trip ID. All vehicles is the default; a vehicle filter uses its permanent UUID independently of the selected recording profile. Choices show current names, while cards retain recorded names. Refresh reloads the current browsing context; Newest returns to the latest matching page. History does not promise a frozen snapshot while new trips are created or deleted. The active card remains available for its original vehicle even when another vehicle's history is selected. It reports the frozen vehicle name and samples confirmed as saved. Storage errors stop recording and remain visible; queued or failed writes are not reported as saved.

Returning from details, tabs, the document picker, or Activity recreation retains the page/filter. A process restart starts with the newest page for all vehicles. Failed history loads have an explicit retry and cannot become an empty-history claim. An empty filtered result remains distinct from having no saved trips. Confirmed deletion refills the current page; if that page no longer exists, browsing returns to the newest matching page. The Room 3-to-4 upgrade adds history indexes without rewriting profiles, trips, samples, or pending work.
Finished trips expose sample counts, duration, valid recorded peaks, and bounded charts for recorded metrics. Choose from the metric/ECU combinations actually recorded, including combinations with only failed samples. Each chart uses one ECU and canonical unit, shows full-stream valid counts and observed min/max, and keeps gaps unjoined. Valid zero and negative Celsius remain visible; invalid values are never converted to zero. Choices do not depend on the current profile, live connection, or favorites. Loading failures have a chart-specific retry, and rapid changes cannot show an older selection's results.

The trip overview remains independent of chart selection: speed uses one identified ECU, and estimated distance integrates valid speed intervals only, alongside covered and uncovered duration; it is not the odometer. Peak recorded RPM may come from any valid RPM source.
JSON/CSV export opens Android's document picker so the user chooses the location. Success is shown only after writing, flushing, and closing the document. Exports contain the vehicle UUID/recorded alias, timestamps, readings, ECU sources, and quality; they exclude VINs and Bluetooth addresses. Exports cannot currently be imported to restore the app.
Deletion requires confirmation and removes the selected trip and its samples; externally exported files remain. Active trips cannot be analyzed, exported, or deleted until recording ends. GPS/maps and remote deletion are not included. Uploading does not remove local history; local deletion does not delete server copies.
Real A53 recording, interruption recovery, export-provider behavior, and storage-limit checks remain pending. Earlier live/dashboard success does not validate saved trips.

## Expected MVP outcome

After initial adapter and vehicle selection, the app attempts to connect and automatically reads and records supported telemetry.
The user can lock the screen during recording, stop the session from its notification, and view history without Internet access.

## Offline synchronization and API

Recording and pending work commit in one local transaction. Vehicle UUIDs, trip/sample IDs, quality and sources remain unchanged. The phone can record, review, export and delete local trips without an Internet connection or server.

The API is developed separately in `api/`, with independent dependencies, tests and CI. It stores vehicle profiles, trip sessions and readings in MongoDB Atlas. There is no login or user account; UUIDs identify records, not owners. Database credentials stay exclusively in ignored backend configuration. The Android app never receives them.

Configure the API's HTTPS base URL in Trips after deploying the server. Until then uploads remain unconfigured and everything waits locally. Completed/interrupted trips, including recovered and empty trips, become eligible after their final local commit. Active journeys never upload. Network-constrained background work survives process restarts and retries failed deliveries. Earlier unsent finished trips are included. Metadata is confirmed before samples, and only validated durable acknowledgments mark delivery.

Trips presents server configuration, pause/resume, retry controls and queue state independently of history. Once a server has been used, its identity is retained: pausing does not change which server owns existing acknowledgments. Conflicting or rejected data remains local for review. Synchronization does not delete trips; JSON/CSV export version 1 remains available regardless of upload state.

The server is prepared for later deployment on the owner's machine behind HTTPS. No deployment or phone upload validation is implied by isolated software tests.

## Screens

| Screen | Contents |
| --- | --- |
| Adapter (implemented) | Discovery, Bluetooth permissions/status, favourite selection, reconnect pause/resume and connection controls |
| Vehicle capabilities (inside Live) | Explicitly captured per-ECU capabilities, latest display states, and JSON report export |
| Vehicles (implemented) | Saved profiles, selection, permanent UUID, alias, optional VIN and source |
| Internal test fixtures | Synthetic values and failure scenarios, outside production navigation |
| Live (implemented) | Customisable sensor cards, overlay controls, per-ECU status, reading age, VIN actions and capabilities |
| Trips (implemented) | Automatic journeys and recording controls, history pages with an independent vehicle filter, storage usage, server settings and upload queue state |
| Trip details (inside Trips) | Sample counts, valid peaks, estimated distance, sensor/ECU charts with gaps, JSON/CSV export, and confirmed deletion |
| Settings (planned) | Units, sampling rate/priorities and storage policy; API settings are inside Trips |

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
- Keeps an active recording running with the A53 screen locked.
- Preserves saved data after an interruption and marks the gaps.
- Exports and deletes trips with clear scope.
- Keeps clearly identified synthetic test fixtures outside production navigation.
- Saves locally first and uploads only finished trips to the configured API, preserving identical retries and validated acknowledgments.

## Outside this first version

User login, actual server deployment, GPS maps, advanced diagnostics, fault clearing, ECU reprogramming, and actuator control.
Additional Mazda sensors require specific investigation and testing before inclusion.
See [RX8-009 research](mazda-parameters.md) for the maintained PCM/cluster candidate matrix, published read-only wire leads, conflicting definitions and work that can proceed before deferred car validation. No proprietary sensor is enabled by that research.

## References

Accessed on October 10, 2026. The Mazda manuals are reproduced on third-party sites.

- [Product, same ASIN on Amazon Italy](https://www.amazon.it/iLC-Bluetooth-Scansione-Collegare-Dispositivi/dp/B0BDRHRR22).
- [Mazda: diagnostic modes](https://foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE010200102T02.html).
- [Mazda: parameter monitor](https://www.foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE010200102T05.html).
- [Mazda: instrument cluster, ODOCOUNT, and OPSC](https://foxed.ca/rx7manual/2003mazdarx8/esicont/en/srvc/html/BHE092255430T06.html).
- [Mazda: compression inspection](https://rx8handbuch.de/esicont/en/srvc/html/BHE011002000W03.html).
- [ELM327: commands and VIN responses](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf).
