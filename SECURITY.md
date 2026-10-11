# Privacy and security

## Vehicle data

VINs, trips, and complete logs can identify a vehicle or its usage patterns.
Use synthetic data in examples, simulators, issues, and tests. Remove VINs, Bluetooth MAC addresses, and locations from shared logs.
The app does not collect GPS location. On Android 8-11, Bluetooth discovery requires foreground location permission; Android 12+ uses Nearby devices permissions instead.
Live OBD values remain in memory unless the user explicitly starts trip recording. Read VIN explicitly requests Mode 09 PID 02 after live acquisition starts; candidates remain temporary until the user reviews and saves one.
Vehicle profiles persist a UUID, alias, optional VIN, manual/OBD provenance, optional ECU source, and creation timestamp in the app's private Room database. The active profile selection is also saved. Dashboard favorites store only stable metric IDs in private app-wide SharedPreferences, without vehicle, ECU, VIN, adapter, or reading values.
VINs are masked by default, with an explicit show/hide action. Masking is a display control, not database encryption. The app has no Internet permission or upload implementation.

Explicit recording stores trip/vehicle/sample UUIDs, a snapshot of the vehicle name, UTC timestamps, monotonic duration, sequences, protocol, ECU sources, units, values, and quality in the private Room database. Retain trips until the user confirms deletion; no automatic retention cleanup or upload queue exists. The UI reports database disk usage, including profiles and SQLite sidecars; deleting rows can leave reusable space rather than shrink the file.
Android backup and device transfer are disabled/excluded for app data. Clearing app data or reinstalling removes profiles, UUIDs, and trips. There is no import/restore workflow. No profile deletion action is available in this increment.
JSON/CSV exports require an explicit action and Android document-picker destination. Exports omit stored VINs and Bluetooth addresses, but include vehicle IDs/names, times, sources, and readings. A name typed by the user can itself contain sensitive information. A chosen document provider may synchronize the file externally under its own settings. The app does not initiate an upload or retain the export URI. CSV text is escaped against spreadsheet formulas; JSON preserves original text. Deleting a trip does not delete exported copies. An export write failure is reported and can leave an incomplete chosen document.
Storage writes reserve at least 10 MiB of free space and stop recording visibly on failure or backlog overflow; already committed data is preserved. Process recovery marks unfinished trips interrupted at the last saved sample, without claiming an exact process-termination time. These controls do not guarantee survival of unwritten samples after a process kill.

## Active sessions and device power

Connect explicitly starts a non-exported connectedDevice foreground service; Read live data separately starts read-only acquisition. The service continues receiving data with another tab/app open or the screen locked. Saving history additionally requires Start recording for an explicitly selected profile.
The ongoing notification offers Stop and opens Adapter. It contains no VIN, MAC address, profile UUID, or raw readings. On Android 13+, notification permission is requested on the first Connect action; denial does not block the service, and the app provides guidance for notification settings and in-app disconnection.
A foreground-service notification is still created when permission is denied, although Android can withhold it from the notification drawer. See [Android notification permission behavior](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

The service uses foreground-service/connected-device and wake-lock permissions. A bounded partial wake lock is renewed only while acquisition is active, and the visible Adapter or real Dashboard screen stays awake during initialization/live acquisition. Simulator previews do not hold the display awake.
These behaviors consume battery; stop the session when finished. The app does not request a battery-optimization exemption. Stop, errors, and service cleanup release session resources and the wake lock even while the UI remains bound.
Process death does not restore a connection or in-memory readings; there is no automatic reconnect or sticky restart.
Stop recording leaves acquisition running. Notification Stop and Disconnect finish the recording and close the link. The service retains started ownership while final writes finish; abrupt process termination is handled on the next app start.

## Future integration

The API will remain disabled until a server, authentication, and consent to upload data are in place.
Use HTTPS and keep credentials outside the repository. Do not send real data to a test server by default.
A UUID or VIN is not a credential and does not authorize access to the vehicle on the server.

## Reporting a problem

Do not post secrets or vehicle data in an issue. Contact the owner privately through an already agreed channel.
When GitHub supports private security reports for this repository, they can be submitted from the Security tab.
No reporting email address has been configured.

## Dependencies and distribution

Review dependencies and permissions. Signing files, keystores, tokens, and local SDK configuration files must not be committed.
The initial version collects information through read requests. It does not modify the ECU.
