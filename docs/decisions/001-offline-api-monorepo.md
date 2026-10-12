# ADR 001: Separate app and API development in one repository

## Context

The Android app already stores profiles and trips locally with stable UUIDs and an immutable outbox. The owner now requests a MongoDB Atlas API on a future user-managed server, without a login, and asks the app to work offline and upload trips at session end.

## Decision

Keep Android in `app/` and `core/`. Add a separately buildable TypeScript API in `api/` with its own package lock, tests, container and deployment guide. Keep the versioned wire contract in shared documentation and validate each component independently in CI. Use `area:android`, `area:api` and `area:sync` for tracking.

Room remains the phone's source of truth. Recording never waits for network access. Upload only after the completed/interrupted trip has committed locally. Persistent network-constrained work sends immutable metadata revisions then bounded sample batches, confirming delivery only from validated ACKs. Retain local history after upload.

The app's HTTPS endpoint starts absent and is configurable after deployment. Lock server identity before the first request so acknowledged work cannot silently change servers; pause/resume retains identity. Recover unfinished local sessions before worker eligibility without recovering a currently active recorder.

The API has anonymous endpoints as requested. UUIDs identify records; this design has no per-user ownership boundary. Keep MongoDB credentials only in ignored backend environment configuration. Use transactions, unique indexes and immutable content checks for retries and conflicts. Test against a disposable replica set; real Atlas connection checks and server deployment are separate evidence.

## Consequences

App and API can evolve independently while sharing one contract and repository. Existing exports and offline history remain usable. Changing server after an attempt requires a future explicit migration design, and multi-device revision reconciliation, remote deletion, importing and user access controls remain outside this increment. Actual server deployment is deferred.
