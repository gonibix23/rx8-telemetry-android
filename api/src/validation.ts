import catalogJson from './catalog.json' with { type: 'json' };
import { ApiError, type Vehicle, type TripSession, type Sample, type Batch } from './models.js';

export const catalog: Readonly<Record<string, { pid: string; unit: string; minimum: number; maximum: number }>> = catalogJson;
const fail = (): never => { throw new ApiError(400, 'invalid_payload'); };
function object(value: unknown, keys: string[], optional: string[] = []): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return fail();
  const row = value as Record<string, unknown>;
  if (Object.keys(row).some((key) => !keys.includes(key) && !optional.includes(key)) || keys.some((key) => !Object.hasOwn(row, key))) return fail();
  return row;
}
function text(value: unknown, maximum: number): string {
  if (typeof value !== 'string' || !value.trim() || value.length > maximum || /[\x00-\x1f\x7f-\x9f]/u.test(value)) return fail();
  return value;
}
function nullableText(value: unknown, maximum: number): string | null { return value === null ? null : text(value, maximum); }
function integer(value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum || value > maximum) return fail();
  return value;
}
function choice<T extends string>(value: unknown, values: readonly T[]): T {
  if (typeof value !== 'string' || !values.includes(value as T)) return fail();
  return value as T;
}
export function uuid(value: unknown): string {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)) return fail();
  return value;
}
export function utc(value: unknown): string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) return fail();
  const date = new Date(value);
  // Date.parse alone normalizes impossible calendar dates. Keep original precision after validation.
  if (!Number.isFinite(date.getTime()) || date.getTime() < 0 || date.toISOString().slice(0, 19) !== value.slice(0, 19)) return fail();
  return value;
}
function schema(row: Record<string, unknown>): void { if (row.schemaVersion !== 1) fail(); }
export function vehicle(value: unknown): Vehicle {
  const row = object(value, ['schemaVersion', 'vehicleId', 'revision', 'vin', 'vinSource', 'alias', 'make', 'model', 'modelYear', 'market', 'powerCv']);
  schema(row);
  const vin = nullableText(row.vin, 17);
  if (vin !== null && !/^[A-HJ-NPR-Z0-9]{17}$/.test(vin)) fail();
  const vinSource = row.vinSource === null ? null : choice(row.vinSource, ['manual', 'obd'] as const);
  if ((vin === null) !== (vinSource === null)) fail();
  return { schemaVersion: 1, vehicleId: uuid(row.vehicleId), revision: integer(row.revision, 1), vin, vinSource,
    alias: text(row.alias, 60), make: nullableText(row.make, 64), model: nullableText(row.model, 64),
    modelYear: row.modelYear === null ? null : integer(row.modelYear, 1886, 9999),
    market: nullableText(row.market, 64), powerCv: row.powerCv === null ? null : integer(row.powerCv, 1, 2147483647) };
}
export function tripSession(value: unknown): TripSession {
  const row = object(value, ['schemaVersion', 'sessionId', 'vehicleId', 'revision', 'startedAt', 'endedAt', 'durationMs', 'status', 'appVersion', 'obdProtocol', 'dataOrigin']);
  schema(row);
  const status = choice(row.status, ['recording', 'completed', 'interrupted'] as const);
  const endedAt = row.endedAt === null ? null : utc(row.endedAt);
  const durationMs = row.durationMs === null ? null : integer(row.durationMs);
  if (status === 'recording' ? endedAt !== null || durationMs !== null : endedAt === null || durationMs === null) fail();
  return { schemaVersion: 1, sessionId: uuid(row.sessionId), vehicleId: uuid(row.vehicleId), revision: integer(row.revision, 1),
    startedAt: utc(row.startedAt), endedAt, durationMs, status, appVersion: nullableText(row.appVersion, 64),
    obdProtocol: nullableText(row.obdProtocol, 256), dataOrigin: choice(row.dataOrigin, ['real'] as const) };
}
export function sample(value: unknown): Sample {
  const row = object(value, ['sampleId', 'sequence', 'observedAt', 'elapsedMs', 'metric', 'value', 'unit', 'status', 'source'], ['service', 'pid', 'ecuAddress']);
  const metric = text(row.metric, 80);
  const definition = Object.hasOwn(catalog, metric) ? catalog[metric] : undefined;
  if (!definition || row.unit !== definition.unit) return fail();
  const status = choice(row.status, ['ok', 'timeout', 'unsupported', 'parse_error', 'no_data', 'adapter_error'] as const);
  if (status === 'ok' ? typeof row.value !== 'number' || !Number.isFinite(row.value) || row.value < definition.minimum || row.value > definition.maximum : row.value !== null) fail();
  const service = row.service === undefined || row.service === null ? null : choice(row.service, ['01'] as const);
  const pid = row.pid === undefined || row.pid === null ? null : choice(row.pid, [definition.pid]);
  const ecuAddress = row.ecuAddress === undefined || row.ecuAddress === null ? null : text(row.ecuAddress, 8);
  if (ecuAddress !== null && !/^(?:[0-9A-F]{2}|[0-9A-F]{3}|[0-9A-F]{8})$/.test(ecuAddress)) fail();
  return { sampleId: uuid(row.sampleId), sequence: integer(row.sequence), observedAt: utc(row.observedAt), elapsedMs: integer(row.elapsedMs),
    metric, value: row.value as number | null, unit: definition.unit, status,
    source: choice(row.source, ['obd_standard'] as const), service, pid, ecuAddress };
}
export function batch(value: unknown, idempotencyKey: unknown): Batch {
  const row = object(value, ['schemaVersion', 'batchId', 'vehicleId', 'sessionId', 'samples']);
  schema(row);
  const batchId = uuid(row.batchId);
  if (idempotencyKey !== batchId) throw new ApiError(400, 'idempotency_key_mismatch');
  if (!Array.isArray(row.samples) || row.samples.length < 1 || row.samples.length > 500) return fail();
  const samples = row.samples.map(sample);
  if (new Set(samples.map((item) => item.sampleId.toLowerCase())).size !== samples.length ||
      samples.some((item, index) => index > 0 && item.sequence <= samples[index - 1]!.sequence)) fail();
  return { schemaVersion: 1, batchId, vehicleId: uuid(row.vehicleId), sessionId: uuid(row.sessionId), samples };
}
export function pageLimit(value: unknown): number {
  if (value === undefined) return 50;
  if (typeof value !== 'string' || !/^[1-9]\d{0,2}$/.test(value)) return fail();
  return integer(Number(value), 1, 100);
}
export function afterSequence(value: unknown): number | null {
  if (value === undefined) return null;
  if (typeof value !== 'string' || !/^(?:0|[1-9]\d{0,15})$/.test(value)) return fail();
  return integer(Number(value));
}
