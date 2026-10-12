import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { MongoMemoryReplSet } from 'mongodb-memory-server';
import { MongoClient } from 'mongodb';
import { createApp } from '../../src/app.js';
import { TelemetryStore } from '../../src/store.js';
import { fixtureVehicle, fixtureSession, fixtureSample, fixtureBatch } from '../fixtures.js';
import type { Batch, TripSession, Vehicle } from '../../src/models.js';

let replica: MongoMemoryReplSet; let client: MongoClient; let store: TelemetryStore;
let app: ReturnType<typeof createApp>;
before(async () => {
  // No env URI or Atlas credentials are consulted. Only a disposable local replica set is used.
  replica = await MongoMemoryReplSet.create({ binary: { version: '8.0.17' }, replSet: { count: 1, storageEngine: 'wiredTiger' } });
  client = new MongoClient(replica.getUri(), { serverSelectionTimeoutMS: 5000 });
  await client.connect();
  store = new TelemetryStore(client, 'rx8_isolated_tests'); await store.initialize(); app = createApp(store);
}, { timeout: 300000 });
after(async () => { if (app) await app.close(); if (client) await client.close(); if (replica) await replica.stop(); });
const putVehicle = (payload: Vehicle) => app.inject({ method: 'PUT', url: '/v1/vehicles/' + payload.vehicleId, payload });
const putSession = (payload: TripSession) => app.inject({ method: 'PUT', url: '/v1/sessions/' + payload.sessionId, payload });
const putBatch = (payload: Batch) => app.inject({ method: 'POST', url: '/v1/telemetry/batches', headers: { 'idempotency-key': payload.batchId }, payload });
async function setup() {
  const vehicle = fixtureVehicle(); const trip = fixtureSession(vehicle.vehicleId);
  assert.equal((await putVehicle(vehicle)).statusCode, 200); assert.equal((await putSession(trip)).statusCode, 200);
  return { vehicle, trip };
}
test('majority committed metadata survives a separate client read and preserves nullable legacy fields', async () => {
  const { vehicle, trip } = await setup();
  const other = new MongoClient(replica.getUri(), { readConcern: { level: 'majority' } });
  try {
    await other.connect();
    assert.deepEqual((await other.db('rx8_isolated_tests').collection('vehicles').findOne({ _id: vehicle.vehicleId as never }))?.payload, vehicle);
    assert.deepEqual((await app.inject('/v1/sessions/' + trip.sessionId)).json(), trip);
  } finally { await other.close(); }
});
test('vehicle revisions are idempotent and reject lower/equal-conflicting content; VIN addition retains ID', async () => {
  const vehicle = fixtureVehicle();
  assert.equal((await putVehicle(vehicle)).statusCode, 200);
  assert.deepEqual((await putVehicle(vehicle)).json(), { schemaVersion: 1, resourceId: vehicle.vehicleId, revision: 1 });
  assert.equal((await putVehicle({ ...vehicle, alias: 'Changed without revision' })).statusCode, 409);
  const next: Vehicle = { ...vehicle, revision: 2, vin: 'ABCDEFGHJKLMN1234', vinSource: 'obd' };
  assert.equal((await putVehicle(next)).statusCode, 200);
  assert.equal((await putVehicle(vehicle)).statusCode, 409);
  assert.equal((await putVehicle({ ...next, revision: 3, vin: null, vinSource: null })).statusCode, 409);
  assert.equal((await app.inject('/v1/vehicles/' + vehicle.vehicleId)).json().vinSource, 'obd');
});
test('session dependencies, historical recording revisions, protocol revision and terminal state are enforced', async () => {
  const vehicle = fixtureVehicle(); const trip = fixtureSession(vehicle.vehicleId);
  assert.equal((await putSession(trip)).statusCode, 409);
  await putVehicle(vehicle);
  const recording: TripSession = { ...trip, status: 'recording', endedAt: null, durationMs: null };
  assert.equal((await putSession(recording)).statusCode, 200);
  assert.equal((await putBatch(fixtureBatch(trip))).json().code, 'session_not_terminal');
  const protocol = { ...recording, revision: 2, obdProtocol: 'ISO 15765-4 CAN (11 bit, 500 kbit/s)' };
  assert.equal((await putSession(protocol)).statusCode, 200);
  assert.equal((await putSession({ ...trip, revision: 3, obdProtocol: protocol.obdProtocol })).statusCode, 200);
  assert.equal((await putSession({ ...protocol, revision: 4 })).json().code, 'terminal_session_conflict');
  assert.equal((await putSession({ ...trip, revision: 4, vehicleId: randomUUID() })).statusCode, 409);
});
test('a fresh UUID after reinstall may share a VIN without merging or reassigning existing vehicles', async () => {
  const first: Vehicle = { ...fixtureVehicle(), vin: 'ABCDEFGHJKLMN1234', vinSource: 'manual' };
  const second = { ...first, vehicleId: randomUUID(), alias: 'Synthetic reinstalled profile' };
  assert.equal((await putVehicle(first)).statusCode, 200); assert.equal((await putVehicle(second)).statusCode, 200);
  assert.equal((await app.inject('/v1/vehicles/' + first.vehicleId)).json().alias, first.alias);
  assert.equal((await app.inject('/v1/vehicles/' + second.vehicleId)).json().alias, second.alias);
  assert.equal((await putVehicle({ ...first, revision: 2, vin: 'ABCDEFGHJKLMN5678' })).json().code, 'vin_reassignment');
});
test('empty terminal trip needs only metadata ACK and reads back an empty sample page', async () => {
  const { trip } = await setup();
  assert.deepEqual((await app.inject('/v1/sessions/' + trip.sessionId + '/samples')).json(), { schemaVersion: 1, items: [], nextCursor: null });
  assert.equal((await putBatch(fixtureBatch(trip, []))).statusCode, 400);
});
test('lost-response retry returns the original batch ACK and different batches deduplicate samples', async () => {
  const { trip } = await setup(); const batch = fixtureBatch(trip);
  const first = await putBatch(batch); assert.equal(first.statusCode, 200); assert.equal(first.json().results[0].status, 'stored');
  assert.deepEqual((await putBatch(batch)).json(), first.json());
  const duplicate = await putBatch({ ...batch, batchId: randomUUID() });
  assert.equal(duplicate.json().results[0].status, 'already_stored');
  assert.equal((await app.inject('/v1/sessions/' + trip.sessionId + '/samples')).json().items.length, 1);
});
test('sample content conflicts roll back preceding new samples and never persist a failed batch', async () => {
  const { trip } = await setup(); const original = fixtureBatch(trip, [fixtureSample(1)]); await putBatch(original);
  const atomic = fixtureBatch(trip, [fixtureSample(0), { ...original.samples[0]!, value: 12 }]);
  assert.equal((await putBatch(atomic)).json().code, 'sample_content_conflict');
  assert.equal((await app.inject('/v1/sessions/' + trip.sessionId + '/samples')).json().items.length, 1);
  atomic.samples[1] = original.samples[0]!;
  assert.equal((await putBatch(atomic)).statusCode, 200);
});
test('batch identity conflicts and session sequence conflicts cannot overwrite data', async () => {
  const { trip } = await setup(); const batch = fixtureBatch(trip); await putBatch(batch);
  assert.equal((await putBatch({ ...batch, samples: [fixtureSample(1)] })).json().code, 'batch_content_conflict');
  assert.equal((await putBatch(fixtureBatch(trip, [fixtureSample(0)]))).json().code, 'sample_sequence_conflict');
  assert.equal((await putBatch(fixtureBatch(trip, [{ ...fixtureSample(2), elapsedMs: 10001 }]))).json().code, 'sample_outside_duration');
});
test('concurrent identical batch requests have one durable result and no duplicate samples', async () => {
  const { trip } = await setup(); const batch = fixtureBatch(trip, [fixtureSample(0), fixtureSample(1)]);
  const results = await Promise.all(Array.from({ length: 4 }, () => putBatch(batch)));
  for (const result of results) { assert.equal(result.statusCode, 200, result.body); assert.deepEqual(result.json(), results[0]!.json()); }
  assert.equal((await app.inject('/v1/sessions/' + trip.sessionId + '/samples')).json().items.length, 2);
});
test('500-sample transaction uses bounded bulk operations and acknowledges the complete ordered batch', async () => {
  const { trip } = await setup(); const batch = fixtureBatch(trip, Array.from({ length: 500 }, (_, index) => fixtureSample(index)));
  const response = await putBatch(batch); assert.equal(response.statusCode, 200, response.body);
  assert.deepEqual(response.json().results.map((result: { sampleId: string }) => result.sampleId), batch.samples.map((sample) => sample.sampleId));
  assert.equal(await client.db('rx8_isolated_tests').collection('samples').countDocuments({ sessionId: trip.sessionId }), 500);
  const receipt = await client.db('rx8_isolated_tests').collection('batches').findOne({ _id: batch.batchId as never });
  assert.equal(Object.hasOwn(receipt!, 'payload'), false);
});
test('paged sample reads keep source, quality, zero and order without duplicates', async () => {
  const { trip } = await setup();
  const samples = [fixtureSample(0), { ...fixtureSample(1), status: 'timeout' as const, value: null, ecuAddress: '7E9' }, fixtureSample(2)];
  assert.equal((await putBatch(fixtureBatch(trip, samples))).statusCode, 200);
  const first = (await app.inject('/v1/sessions/' + trip.sessionId + '/samples?limit=2')).json();
  assert.deepEqual(first.items, samples.slice(0, 2)); assert.equal(first.nextCursor, '1');
  const last = (await app.inject('/v1/sessions/' + trip.sessionId + '/samples?limit=2&cursor=' + first.nextCursor)).json();
  assert.deepEqual(last.items, samples.slice(2)); assert.equal(last.nextCursor, null);
});
test('vehicle/session listing uses bounded UUID keyset pages and an independent vehicle filter', async () => {
  const { vehicle, trip } = await setup(); const secondTrip = { ...trip, sessionId: randomUUID() }; await putSession(secondTrip);
  const first = (await app.inject('/v1/sessions?limit=1&vehicleId=' + vehicle.vehicleId)).json();
  const last = (await app.inject('/v1/sessions?limit=1&vehicleId=' + vehicle.vehicleId + '&cursor=' + first.nextCursor)).json();
  assert.equal(first.items.length, 1); assert.equal(last.items.length, 1);
  assert.notEqual(first.items[0].sessionId, last.items[0].sessionId); assert.equal(last.nextCursor, null);
  const vehicles = (await app.inject('/v1/vehicles?limit=2')).json(); assert.equal(vehicles.items.length, 2); assert.ok(vehicles.nextCursor);
});
