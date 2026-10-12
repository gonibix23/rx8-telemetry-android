# Privacy and security

## Vehicle data

VINs, trips, and complete logs can identify a vehicle or its usage patterns.
Use synthetic data in examples, simulators, issues, and tests. Remove VINs, Bluetooth MAC addresses, and locations from shared logs.
The app does not collect GPS location. On Android 8-11, Bluetooth discovery requires foreground location permission; Android 12+ uses Nearby devices permissions instead.
Live OBD values are recorded automatically for the persisted selected vehicle after connection, or after an explicit manual resume; without a selected profile they remain in memory. Capability snapshot export is explicit. Read VIN explicitly requests Mode 09 PID 02 after live acquisition starts; candidates remain temporary until the user reviews and saves one.
Vehicle profiles persist a UUID, alias, optional VIN, manual/OBD provenance, optional ECU source, and creation timestamp in the app's private Room database. The active profile selection is also saved. Dashboard preferences store stable metric IDs, order, display style/size and overlay selections in private app-wide SharedPreferences, without vehicle, ECU, VIN, adapter, or reading values.
VINs are masked by default, with an explicit show/hide action. Masking is a display control, not database encryption. Optional synchronization uses a configured HTTPS API. Without a server configured, records wait locally. No MongoDB credential is included in the app.

Automatic or explicitly resumed recording stores trip/vehicle/sample UUIDs, a snapshot of the vehicle name, UTC timestamps, monotonic duration, sequences, protocol, ECU sources, units, values, and quality in the private Room database. New recordings also preserve their app version; older recordings retain an unknown version. Retain trips until the user confirms deletion; no automatic retention cleanup exists. The UI reports database disk usage, including profiles, local pending work, and SQLite sidecars; deleting rows can leave reusable space rather than shrink the file.
Android backup and device transfer are disabled/excluded for app data. Clearing app data or reinstalling removes profiles, UUIDs, and trips. There is no import/restore workflow. No profile deletion action is available in this increment.
JSON/CSV exports require an explicit action and Android document-picker destination. Exports omit stored VINs and Bluetooth addresses, but include vehicle IDs/names, times, sources, and readings. A name typed by the user can itself contain sensitive information. A chosen document provider may synchronize the file externally under its own settings. Document export does not initiate synchronization or retain the export URI; configured trip uploads run separately. CSV text is escaped against spreadsheet formulas; JSON preserves original text. Deleting a trip does not delete exported copies. An export write failure is reported and can leave an incomplete chosen document.
Storage writes reserve at least 10 MiB of free space and stop recording visibly on failure or backlog overflow; already committed data is preserved. Process recovery marks unfinished trips interrupted at the last saved sample, without claiming an exact process-termination time. These controls do not guarantee survival of unwritten samples after a process kill.

## Capability snapshot export

The capability report uses a dedicated allowlist rather than serializing Bluetooth or OBD UI state. It excludes VINs/candidates, Bluetooth names/MACs, vehicle/profile/trip identifiers and aliases, raw command/response logs, adapter identity, and free-form error text. Only recognized protocol descriptions and validated ECU bus addresses are retained. Report IDs are newly generated and unrelated to saved vehicle or trip IDs.

Reports still contain a UTC capture time, latest sensor values/quality, elapsed acquisition time, response rate, and ECU capabilities. They are not guaranteed anonymous. Preview the report and choose the JSON destination explicitly. Document providers may synchronize files according to their own settings. No direct network request, automatic sharing, broad storage permission, persistent export URI, or upload is added.

The frozen preview and pending export body stay only in the Activity's ViewModel until process exit. A pending picker survives Activity recreation; a result after process death cannot recreate the report and must not produce an empty successful export. Success requires writing, flushing, and closing the destination. Errors can leave a partial file. Reports do not mutate saved trips or delivery state.

## Active sessions and device power

An authorized visible automatic or manual connection starts a non-exported connectedDevice foreground service, read-only acquisition and a journey for the persisted selected profile. The service continues receiving data with another tab/app open or the screen locked.
The ongoing notification offers Stop and opens Adapter. It contains no VIN, MAC address, profile UUID, or raw readings. On Android 13+, notification permission is requested on the first Connect action; denial does not block the service, and the app provides guidance for notification settings and in-app disconnection.
A foreground-service notification is still created when permission is denied, although Android can withhold it from the notification drawer. See [Android notification permission behavior](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

The service uses foreground-service/connected-device and wake-lock permissions. A bounded partial wake lock is renewed only while acquisition is active, and the visible Adapter or real Dashboard screen stays awake during initialization/live acquisition. Simulator previews do not hold the display awake.
These behaviors consume battery; stop the session when finished. The app does not request a battery-optimization exemption. Stop, errors, and service cleanup release session resources and the wake lock even while the UI remains bound.
Process death does not restore a connection or in-memory readings; there is no background reconnect or sticky restart. A visible new app process may attempt the last successful adapter once.
Stop recording leaves acquisition running. Notification Stop and Disconnect finish the recording and close the link. The service retains started ownership while final writes finish; abrupt process termination is handled on the next app start.

## API synchronization

The local outbox contains immutable metadata revisions and prepared sample bodies. Vehicle metadata can include the optional stored VIN. Treat the private phone database, server database and telemetry reports as sensitive. HTTPS uploads start only after a server URL is explicitly configured, and only for completed/interrupted journeys. Fake ACKs are restricted to isolated tests.

The API has no login or user accounts as requested. Its anonymous routes are accessible to anyone able to reach the server; UUIDs and VINs are identifiers, not access controls. Deploy behind HTTPS on the owner's server and choose network exposure deliberately. The API validates bounded requests and avoids logging bodies, credentials or database URIs. MongoDB credentials live only in backend environment configuration; `.env` and database dumps are excluded from source control and the container image.

Server identity is locked before the first upload attempt. Pausing retains that identity and stored acknowledgments. Trips remain on the phone after successful delivery, and local deletion removes related local work only. There is no remote deletion, retention cleanup or server-to-phone restore/import workflow. A server copy does not automatically restore the app after reinstalling.

Backend tests use disposable MongoDB replica sets and synthetic data. Do not configure test runs with production Atlas credentials. The actual deployment is deferred; see [API setup](api/README.md).

## Reporting a problem

Do not post secrets or vehicle data in an issue. Contact the owner privately through an already agreed channel.
When GitHub supports private security reports for this repository, they can be submitted from the Security tab.
No reporting email address has been configured.

## Dependencies and distribution

Review dependencies and permissions. Signing files, keystores, tokens, and local SDK configuration files must not be committed.
The initial version collects information through read requests. It does not modify the ECU.

## Request logging and floating readings

API diagnostics use generated request IDs and fixed route templates, method, status, elapsed time and safe error classifications. Telemetry bodies, headers, URL query strings, resource IDs, VINs, raw driver errors and database configuration are excluded. The operator manages container log retention.

The optional floating overlay displays selected readings over other apps after explicit Android permission and enablement for the current session. It does not read those apps or collect GPS. It is hidden in the app and on the lock screen and removed on disconnect, permission loss or cleanup. Favourite adapters are stored privately for visible-app retries; manual Stop/Disconnect pauses them. Neither adapter settings nor card preferences identify a vehicle.
