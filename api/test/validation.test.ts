import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { vehicle, tripSession, sample, batch, catalog, pageLimit, afterSequence, utc } from '../src/validation.js';
import { digest } from '../src/store.js';
import { ApiError } from '../src/models.js';
import { databaseConfig } from '../src/config.js';
import { fixtureVehicle, fixtureSession, fixtureSample, fixtureBatch } from './fixtures.js';

const rejects = (callback: () => unknown) => assert.throws(callback, (error: unknown) => error instanceof ApiError && error.statusCode === 400);
test('vehicle round-trip preserves UUID, optional metadata and VIN provenance without a check digit', () => {
  const payload = { ...fixtureVehicle(), vin: 'ABCDEFGHJKLMN1234', vinSource: 'manual' };
  // Exactly 17 allowed characters; European structural validity does not require a check digit.
  assert.deepEqual(vehicle(payload), payload);
  assert.deepEqual(vehicle(fixtureVehicle()).make, null);
  rejects(() => vehicle({ ...payload, vinSource: null }));
  rejects(() => vehicle({ ...payload, vin: 'ABCDEFGHIJKLM12345' }));
});
test('strict objects reject unknown/private fields, missing fields and Mongo operators', () => {
  const payload = fixtureVehicle();
  rejects(() => vehicle({ ...payload, bluetoothMac: '02:00:00:00:00:00' }));
  rejects(() => vehicle({ ...payload, alias: { $gt: '' } }));
  const { alias: _alias, ...missing } = payload; rejects(() => vehicle(missing));
  rejects(() => vehicle({ ...payload, alias: 'secret\nlog' }));
  rejects(() => vehicle({ ...payload, revision: Number.MAX_SAFE_INTEGER + 1 }));
  rejects(() => vehicle({ ...payload, vehicleId: 'AAAAAAAA-0000-4000-8000-000000000001' }));
});
test('all 120 metric/unit/PID combinations preserve endpoints, finite zero and component identities', () => {
  assert.equal(Object.keys(catalog).length, 120);
  for (const [metric, entry] of Object.entries(catalog)) {
    for (const value of [entry.minimum, entry.maximum]) {
      const reading = { ...fixtureSample(), metric, value, unit: entry.unit, pid: entry.pid };
      assert.deepEqual(sample(reading), reading);
    }
    rejects(() => sample({ ...fixtureSample(), metric, value: entry.maximum + 1, unit: entry.unit, pid: entry.pid }));
    rejects(() => sample({ ...fixtureSample(), metric, value: entry.minimum, unit: 'fabricated', pid: entry.pid }));
  }
  assert.equal(sample(fixtureSample()).value, 0);
  assert.equal(sample({ ...fixtureSample(), metric: 'coolant_temperature', pid: '05', unit: 'Cel', value: -5 }).value, -5);
});
test('error quality always requires null; no stale or simulated data is accepted', () => {
  for (const status of ['timeout', 'unsupported', 'parse_error', 'no_data', 'adapter_error']) {
    assert.equal(sample({ ...fixtureSample(), status, value: null }).value, null);
    rejects(() => sample({ ...fixtureSample(), status, value: 0 }));
  }
  for (const value of [null, Infinity, NaN]) rejects(() => sample({ ...fixtureSample(), value }));
  rejects(() => sample({ ...fixtureSample(), status: 'stale', value: null }));
  rejects(() => tripSession({ ...fixtureSession(randomUUID()), dataOrigin: 'simulated' }));
});
test('source/service/PID/ECU combinations are explicit, optional identifiers retain null', () => {
  for (const ecuAddress of ['10', '7E8', '18DAF110', null]) assert.equal(sample({ ...fixtureSample(), ecuAddress }).ecuAddress, ecuAddress);
  for (const bad of [{ pid: '0C' }, { source: 'adapter' }, { service: '09' }, { ecuAddress: '02:00:00:AB:CD:EF' }, { metric: 'toString' }]) rejects(() => sample({ ...fixtureSample(), ...bad }));
  const { pid: _pid, service: _service, ecuAddress: _ecu, ...optional } = fixtureSample();
  assert.equal(sample(optional).pid, null);
});
test('timestamps validate real calendar dates and keep precision; monotonic duration is independent of UTC', () => {
  assert.equal(utc('2026-10-10T09:00:01.123456789Z'), '2026-10-10T09:00:01.123456789Z');
  for (const invalid of ['2026-02-30T09:00:00Z', '2026-10-10T24:00:00Z', '2026-10-10T09:00:00+00:00', 'yesterday']) rejects(() => utc(invalid));
  const payload = fixtureSession(randomUUID());
  assert.equal(tripSession({ ...payload, endedAt: '2026-10-10T08:59:00Z' }).durationMs, 10000);
});
test('recording and terminal session shape is enforced and legacy app version remains unknown', () => {
  const payload = fixtureSession(randomUUID());
  assert.equal(tripSession(payload).appVersion, null);
  rejects(() => tripSession({ ...payload, status: 'recording' }));
  rejects(() => tripSession({ ...payload, endedAt: null }));
  assert.equal(tripSession({ ...payload, status: 'recording', endedAt: null, durationMs: null }).durationMs, null);
});
test('batch identity, ordered unique samples and hard sample limit are enforced', () => {
  const payload = fixtureBatch(fixtureSession(randomUUID()));
  assert.deepEqual(batch(payload, payload.batchId), payload);
  rejects(() => batch(payload, randomUUID()));
  for (const samples of [[], [payload.samples[0], payload.samples[0]], [fixtureSample(1), fixtureSample(0)], Array.from({ length: 501 }, (_, i) => fixtureSample(i))]) {
    rejects(() => batch({ ...payload, samples }, payload.batchId));
  }
});
test('paged reads reject unsafe limits and sequence cursors', () => {
  assert.equal(pageLimit(undefined), 50); assert.equal(pageLimit('100'), 100); assert.equal(afterSequence('0'), 0);
  for (const value of ['101', '0', '1.1', '-1', '1e2']) rejects(() => pageLimit(value));
  for (const value of ['-1', '01', '9007199254740992']) rejects(() => afterSequence(value));
});
test('canonical content fingerprints ignore object order but preserve values and sample order', () => {
  assert.equal(digest({ a: 1, b: 2 }), digest({ b: 2, a: 1 }));
  assert.notEqual(digest({ a: 0 }), digest({ a: null }));
  assert.notEqual(digest([1, 2]), digest([2, 1]));
});
test('configuration errors never include the database URI', () => {
  const secret = 'password-private-test';
  assert.throws(() => databaseConfig({ MONGODB_URI: secret }), { message: 'invalid_configuration' });
  assert.throws(() => databaseConfig({ MONGODB_URI: 'mongodb://localhost', MONGODB_DATABASE: 'bad/name' }), { message: 'invalid_configuration' });
});
