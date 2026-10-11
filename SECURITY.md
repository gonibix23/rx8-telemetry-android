# Privacy and security

## Vehicle data

VINs, trips, and complete logs can identify a vehicle or its usage patterns.
Use synthetic data in examples, simulators, issues, and tests. Remove VINs, Bluetooth MAC addresses, and locations from shared logs.
The app does not collect GPS location. On Android 8-11, Bluetooth discovery requires foreground location permission; Android 12+ uses Nearby devices permissions instead.
Live OBD values remain in memory only. Read VIN explicitly requests Mode 09 PID 02 after live acquisition starts; candidates remain temporary until the user reviews and saves one.
Vehicle profiles persist a UUID, alias, optional VIN, manual/OBD provenance, optional ECU source, and creation timestamp in the app's private Room database. The active profile selection is also saved. Dashboard favorites store only stable metric IDs in private app-wide SharedPreferences, without vehicle, ECU, VIN, adapter, or reading values.
VINs are masked by default, with an explicit show/hide action. Masking is a display control, not database encryption. The app has no Internet permission, persistent telemetry log, trip history, or upload implementation.

Android backup and device transfer are disabled/excluded for app data. Clearing app data or reinstalling removes the profiles and UUIDs. No export or restore feature is implemented; recovery is not promised.
No profile deletion action is available in this increment. Future retention, backup, and export features need explicit handling before distribution.
When trip recording is implemented, allow trips to be exported and deleted, show storage usage, and do not delete records pending upload without informing the user.

## Active sessions and device power

Connect explicitly starts a non-exported connectedDevice foreground service; Read live data separately starts read-only acquisition. The service can continue receiving data with another tab/app open or the screen locked, but no telemetry history is saved.
The ongoing notification offers Stop and opens Adapter. It contains no VIN, MAC address, profile UUID, or raw readings. On Android 13+, notification permission is requested on the first Connect action; denial does not block the service, and the app provides guidance for notification settings and in-app disconnection.
A foreground-service notification is still created when permission is denied, although Android can withhold it from the notification drawer. See [Android notification permission behavior](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

The service uses foreground-service/connected-device and wake-lock permissions. A bounded partial wake lock is renewed only while acquisition is active, and the visible Adapter or real Dashboard screen stays awake during initialization/live acquisition. Simulator previews do not hold the display awake.
These behaviors consume battery; stop the session when finished. The app does not request a battery-optimization exemption. Stop, errors, and service cleanup release session resources and the wake lock even while the UI remains bound.
Process death does not restore a connection or in-memory readings; there is no automatic reconnect or sticky restart.

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
