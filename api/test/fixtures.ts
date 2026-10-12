import { randomUUID } from 'node:crypto';
import type { Vehicle, TripSession, Sample, Batch } from '../src/models.js';

// All identities and measurements in this directory are synthetic fixtures.
export function fixtureVehicle(): Vehicle {
  return { schemaVersion: 1, vehicleId: randomUUID(), revision: 1, vin: null, vinSource: null,
    alias: 'Synthetic test vehicle', make: null, model: null, modelYear: null, market: null, powerCv: null };
}
export function fixtureSession(vehicleId: string): TripSession {
  return { schemaVersion: 1, sessionId: randomUUID(), vehicleId, revision: 1,
    startedAt: '2026-10-10T09:00:00Z', endedAt: '2026-10-10T09:00:10Z', durationMs: 10000,
    status: 'completed', appVersion: null, obdProtocol: null, dataOrigin: 'real' };
}
export function fixtureSample(sequence = 0): Sample {
  return { sampleId: randomUUID(), sequence, observedAt: '2026-10-10T09:00:01Z', elapsedMs: 1000,
    metric: 'speed', value: 0, unit: 'km/h', status: 'ok', source: 'obd_standard', service: '01', pid: '0D', ecuAddress: '7E8' };
}
export function fixtureBatch(session: TripSession, samples = [fixtureSample()]): Batch {
  return { schemaVersion: 1, batchId: randomUUID(), vehicleId: session.vehicleId, sessionId: session.sessionId, samples };
}
