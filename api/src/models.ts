export interface Vehicle {
  schemaVersion: 1; vehicleId: string; revision: number; vin: string | null;
  vinSource: 'manual' | 'obd' | null; alias: string; make: string | null;
  model: string | null; modelYear: number | null; market: string | null; powerCv: number | null;
}
export interface TripSession {
  schemaVersion: 1; sessionId: string; vehicleId: string; revision: number;
  startedAt: string; endedAt: string | null; durationMs: number | null;
  status: 'recording' | 'completed' | 'interrupted'; appVersion: string | null;
  obdProtocol: string | null; dataOrigin: 'real';
}
export interface Sample {
  sampleId: string; sequence: number; observedAt: string; elapsedMs: number;
  metric: string; value: number | null; unit: string;
  status: 'ok' | 'timeout' | 'unsupported' | 'parse_error' | 'no_data' | 'adapter_error';
  source: 'obd_standard'; service: string | null; pid: string | null; ecuAddress: string | null;
}
export interface Batch {
  schemaVersion: 1; batchId: string; vehicleId: string; sessionId: string; samples: Sample[];
}
export interface MetadataAck { schemaVersion: 1; resourceId: string; revision: number }
export interface BatchAck {
  schemaVersion: 1; batchId: string;
  results: { sampleId: string; status: 'stored' | 'already_stored' }[];
}
export interface Page<T> { schemaVersion: 1; items: T[]; nextCursor: string | null }
export class ApiError extends Error {
  constructor(public readonly statusCode: number, public readonly code: string) { super(code); }
}
