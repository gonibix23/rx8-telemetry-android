# RX-8 telemetry API

Anonymous version-1 HTTP/JSON service for the Android app's real, locally saved telemetry. Node 22, TypeScript, Fastify 5 and the official MongoDB driver. MongoDB must be a replica set or sharded cluster supporting transactions; Atlas supplies this. The repository does not deploy a public endpoint.

There is deliberately **no login, API key, installation identity or resource ownership check**, as requested. Anyone who can reach the service can read all stored vehicles (including optional VINs), sessions and samples, and submit metadata and samples. UUIDs are identifiers, not credentials. No CORS policy turns this into access control. Keep the HTTP listener private until the intended anonymous HTTPS exposure is configured and accepted. Database credentials belong only on the API host, never in the APK.

## Run and verify

From `api/`:

```shell
npm ci --ignore-scripts
npm run check
npm run build
npm test
npm run test:integration
```

`check` type-checks production/tests and verifies all 120 metric definitions against Android's `ObdField` source. `catalog` regenerates the reviewed static runtime catalog after an intentional Android catalog change. Runtime deployment needs no Kotlin files.

Unit/HTTP tests use synthetic fixtures. Integration tests start their own disposable local MongoDB 8.0.17 replica set through `mongodb-memory-server`, including real commits, unique indexes, conflict rollback, concurrency and paged reads. They never consult `MONGODB_URI`, `.env` or Atlas. The first integration run downloads the MongoDB binary; Linux/Windows platform prerequisites must be installed. No Docker daemon is required for these tests. `npm ci --ignore-scripts` avoids a database download during dependency installation.

Put the URI and database name in an ignored `api/.env` using `.env.example`, or inject environment variables. `npm run check:database` loads that local file and only connects/pings/closes; it creates no indexes, collections or fixtures. Its output never includes the URI or a raw driver error. Then `npm start` runs compiled code, connects, verifies replica-set capability and creates indexes before listening. `npm run dev` runs source. The default listener is `127.0.0.1:3000`. Configuration: `MONGODB_URI`, `MONGODB_DATABASE` (default `rx8_telemetry`), `HOST`, `PORT`.

## Write contract

Payloads/ACKs match `docs/api-contract.md` and `ApiV1Models.kt` / `ApiV1Json.kt`:

| Route | Successful response |
| --- | --- |
| `PUT /v1/vehicles/{vehicleId}` | `{schemaVersion:1,resourceId,revision}` |
| `PUT /v1/sessions/{sessionId}` | `{schemaVersion:1,resourceId,revision}` |
| `POST /v1/telemetry/batches` | `{schemaVersion:1,batchId,results:[{sampleId,status}]}` |

The batch header `Idempotency-Key` must equal `batchId`. Vehicle, session, sample and batch UUIDs must use canonical **lowercase** representation, as the Android recorder generates them. Payload/path IDs must agree. Integer sequence/revision/time fields must be safe JSON integers. Unknown fields are rejected; text is bounded and control-free. Optional sample `service`, `pid` and `ecuAddress` may be omitted or null; when present they must match the implemented standard metric and actual parser address shapes (2, 3 or 8 uppercase hex digits). VIN accepts 17 uppercase characters excluding I/O/Q without enforcing a North American check digit.

Only `dataOrigin: real`, `source: obd_standard` and the 120 implemented standard metric/unit/PID combinations are accepted. Vendor, adapter, derived, simulated and unknown metric extensions are not implemented. The runtime catalog preserves Celsius `Cel`, torque `N.m`, dimensionless `1`, the remaining canonical units, per-field domains and separate components sharing a PID. `ok` requires a finite in-range value; failed qualities require null. Zero and negative temperatures are retained. No stale display value becomes a new sample.

Higher metadata revisions replace the latest metadata; equal revision/equal canonical content is idempotent; equal/different or lower revision conflicts. The service retains **latest metadata**, not a full revision history. It accepts earlier queued `recording` session revisions even though the client starts uploads only after local recording has ended. The later terminal revision must arrive before samples. Once terminal, a session cannot reopen or change its final status/time/duration. Its vehicle, start time, recorded app version and origin cannot change; an unknown protocol can become known once. Wall-clock rollback is permitted: monotonic duration is never inferred from UTC subtraction. An empty terminal trip needs only metadata ACKs, no empty batch.

VIN additions preserve the vehicle UUID; an existing UUID cannot acquire a different VIN or provenance. Different UUIDs may share a VIN after reinstall or on another phone; they are never merged automatically.

Each batch is an all-or-nothing transaction. Global sample UUIDs and `(sessionId, sequence)` are unique. Reusing a sample ID with changed content/session or a batch ID with a different canonical body conflicts. A first write returns `stored`; a new batch containing identical saved samples returns `already_stored`; repeating the same batch returns its original committed receipt, including after a lost response. Batch receipts contain the canonical SHA-256 content digest and ACK, not a second copy of sample bodies. Object-key order/numeric spellings do not change canonical content; value, field and array-order changes do.

ACKs are returned only after transactions commit with snapshot reads, primary routing and majority/journaled durability. Bulk lookups/inserts bound network round trips independently of the sample count; driver transaction operations run serially. Commit uncertainty returns no false success and can be retried with the same body. Transactions and driver operations have timeouts. Hard limits: **1 MiB decoded body, 500 samples per batch**; app defaults remain 100 samples/256 KiB.

## Read endpoints and errors

| Route | Result |
| --- | --- |
| `GET /health` | Process health, no database query |
| `GET /ready` | Bounded database ping; 503 if unavailable |
| `GET /v1/vehicles?limit=50&cursor={uuid}` | UUID-ascending page |
| `GET /v1/vehicles/{uuid}` | Latest vehicle metadata |
| `GET /v1/sessions?vehicleId={uuid}&limit=50&cursor={uuid}` | UUID-ascending page, optional vehicle filter |
| `GET /v1/sessions/{uuid}` | Latest session metadata |
| `GET /v1/sessions/{uuid}/samples?limit=50&cursor={sequence}` | Sequence-ascending page |

Pages return `{schemaVersion:1,items:[...],nextCursor:string|null}`. Pass the returned exclusive cursor unchanged and keep the same filter. Limits are 1–100, default 50. Pages are bounded keyset queries, not an immutable whole-database snapshot; newly arriving lower UUIDs may require restarting a listing. No remote deletion or diagnostic/control route exists. Responses are `Cache-Control: no-store`.

Errors contain only a bounded machine-readable `{code}`: 400 validation/identity error, 404 missing resource, 409 revision/content/dependency conflict, 413 oversized body, 503 storage unavailable. Batch validation/conflict never partially ACKs or overwrites samples. Client retries 503 and preserves immutable work; 409/400 require review. Application request/body/header/URI logging is disabled, and startup/database errors are generic. Configure reverse proxies and hosting logs similarly; the service does not log raw VINs, connection strings or payloads.

## Later deployment

Production deployment: `.github/workflows/deploy.yml` runs on pushes to `main` touching `api/`. After the API checks pass it pushes `ghcr.io/gonibix23/rx8-telemetry-android-api` (`latest` and `sha-<commit>`), pins that tag in `deploy/portainer-compose.yml` and calls the `PORTAINER_WEBHOOK_URL` secret to redeploy the Portainer stack `gonibix`. That stack runs the API on host port 7600 with a single-node MongoDB 8.0 replica set stored in `/mnt/rx8/mongo`.

The production image includes the project's MIT license at `/app/LICENSE`. Third-party packages retain their own licenses.

Build with `docker build -t rx8-telemetry-api ./api` from the repository root, or `docker compose -f deploy/compose.yaml build` from `api/`. The image runs as the nonroot `node` user and excludes local credentials/tests. The Compose example binds only loopback, drops capabilities, makes the root filesystem read-only and enables init/signal forwarding. It is an example, not a deployment performed by these files. Runtime host environment supplies Atlas credentials.

For a public endpoint later: create DNS for the chosen domain, run an HTTPS reverse proxy (example `deploy/Caddyfile`), keep port 3000 private, allow only the API host's egress address in Atlas, and grant the database user only the intended database's required read/write/index privileges. Configure backups/retention and deployment monitoring explicitly. Rebuild the Node 22 image for security updates and pin an approved image digest in the deployment environment. No backend URL is inferred from an Atlas cluster hostname; the Android app talks to the HTTPS API, never directly to MongoDB.

Primary references checked during implementation: [Fastify v5 migration/Node support](https://fastify.dev/docs/latest/Guides/Migration-Guide-V5/), [Fastify server limits](https://fastify.dev/docs/latest/Reference/Server/), [Fastify validation](https://fastify.dev/docs/latest/Reference/Validation-and-Serialization/), [MongoDB driver transactions](https://www.mongodb.com/docs/drivers/node/current/crud/transactions/), [MongoDB unique indexes](https://www.mongodb.com/docs/manual/core/index-unique/), [mongodb-memory-server replica-set fixtures](https://typegoose.github.io/mongodb-memory-server/docs/guides/quick-start-guide/).

## License

Original API code and documentation are licensed under [MIT](LICENSE), matching the [repository license](../LICENSE). Dependency licenses and external reference materials remain separate.
