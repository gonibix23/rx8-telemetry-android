import test from 'node:test';
import assert from 'node:assert/strict';
import { createApp, MAX_BODY_BYTES } from '../src/app.js';
import { TelemetryStore } from '../src/store.js';
import { fixtureVehicle } from './fixtures.js';

test('health is independent of storage, readiness failures return no raw database errors', async () => {
  const store = Object.create(TelemetryStore.prototype) as TelemetryStore;
  store.ready = async () => { throw new Error('mongodb://private-user:private-password@private-host/?vin=PRIVATE'); };
  const app = createApp(store);
  try {
    assert.equal((await app.inject('/health')).statusCode, 200);
    const response = await app.inject('/ready');
    assert.equal(response.statusCode, 503); assert.deepEqual(response.json(), { code: 'storage_unavailable' });
    assert.equal(response.headers['cache-control'], 'no-store');
  } finally { await app.close(); }
});
test('oversized, malformed, unknown route and private validation errors have bounded safe responses', async () => {
  const app = createApp(Object.create(TelemetryStore.prototype) as TelemetryStore);
  try {
    const payload = fixtureVehicle(); const url = '/v1/vehicles/' + payload.vehicleId;
    const oversized = await app.inject({ method: 'PUT', url, payload: JSON.stringify({ ...payload, alias: 'x'.repeat(MAX_BODY_BYTES) }), headers: { 'content-type': 'application/json' } });
    assert.equal(oversized.statusCode, 413); assert.deepEqual(oversized.json(), { code: 'payload_too_large' });
    const invalid = await app.inject({ method: 'PUT', url, payload: { ...payload, vin: 'PRIVATE-VIN-SECRET' } });
    assert.equal(invalid.statusCode, 400); assert.deepEqual(invalid.json(), { code: 'invalid_payload' });
    const malformed = await app.inject({ method: 'PUT', url, payload: '{invalid', headers: { 'content-type': 'application/json' } });
    assert.equal(malformed.statusCode, 400); assert.equal(malformed.body.includes('invalid\''), false);
    const unknown = await app.inject('/unknown/private-query'); assert.deepEqual(unknown.json(), { code: 'not_found' });
  } finally { await app.close(); }
});
