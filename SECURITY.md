# Privacy and security

## Vehicle data

VINs, trips, and complete logs can identify a vehicle or its usage patterns.
Use synthetic data in examples, simulators, issues, and tests. Remove VINs, Bluetooth MAC addresses, and locations from shared logs.
The MVP does not request GPS access.

Databases will be stored in the app's private storage. Backup, export, and retention policies must be explicitly implemented before distribution.
Allow trips to be exported and deleted; show storage usage and do not delete records pending upload without informing the user.

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
