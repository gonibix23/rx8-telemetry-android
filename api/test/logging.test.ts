import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createConnection } from 'node:net';
import { request as httpRequest } from 'node:http';
import { createApp, MAX_BODY_BYTES } from '../src/app.js';
import { ApiLog } from '../src/logging.js';
import { ApiError } from '../src/models.js';
import { TelemetryStore } from '../src/store.js';
import { fixtureVehicle } from './fixtures.js';

type RecordLine = { event: string; requestId: string; route: string; statusCode: number | null; durationMs: number; errorClass: string | null; [key: string]: unknown };
function capture() {
  const lines: string[] = [];
  const logger = new ApiLog((line) => { lines.push(line); });
  return { lines, logger, records: () => lines.map((line) => JSON.parse(line) as RecordLine) };
}
function store() { return Object.create(TelemetryStore.prototype) as TelemetryStore; }
function boundedRequest(record: RecordLine, line: string) {
  assert.deepEqual(Object.keys(record).sort(), ['timestamp', 'level', 'event', 'requestId', 'method', 'route', 'statusCode', 'durationMs', 'errorClass', 'errorCode'].sort());
  assert.match(record.requestId, /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
  assert.equal(Number.isFinite(record.durationMs), true); assert.ok(record.durationMs >= 0);
  assert.ok(Buffer.byteLength(line) < 512); assert.equal(line.split('\n').length, 2);
}

test('successful requests use generated IDs, fixed routes and no body/header/query/resource data', async () => {
  const output = capture(); const data = store(); const vehicle = fixtureVehicle();
  vehicle.alias = 'PRIVATE_BODY_MARKER'; vehicle.vin = 'JM1FE173X40000001'; vehicle.vinSource = 'manual';
  data.putVehicle = async (value) => ({ schemaVersion: 1, resourceId: value.vehicleId, revision: value.revision });
  const app = createApp(data, output.logger);
  try {
    const clientId = '00000000-0000-0000-0000-000000000001';
    const response = await app.inject({ method: 'PUT', url: `/v1/vehicles/${vehicle.vehicleId}?token=PRIVATE_QUERY_MARKER`, payload: vehicle,
      headers: { 'request-id': clientId, 'x-request-id': 'PRIVATE_HEADER_MARKER', authorization: 'PRIVATE_AUTH_MARKER' } });
    assert.equal(response.statusCode, 200);
    const records = output.records(); assert.deepEqual(records.map((record) => record.event), ['request_received', 'request_completed']);
    assert.equal(records[0]!.requestId, records[1]!.requestId); assert.notEqual(records[0]!.requestId, clientId);
    assert.equal(response.headers['x-request-id'], records[0]!.requestId);
    for (const [index, record] of records.entries()) { boundedRequest(record, output.lines[index]!); assert.equal(record.route, '/v1/vehicles/:vehicleId'); assert.equal(record.method, 'PUT'); }
    assert.equal(records[1]!.statusCode, 200); assert.equal(records[1]!.errorClass, null);
    const text = output.lines.join(''); for (const secret of [vehicle.vehicleId, vehicle.alias, vehicle.vin, clientId, 'PRIVATE_QUERY_MARKER', 'PRIVATE_HEADER_MARKER', 'PRIVATE_AUTH_MARKER']) assert.equal(text.includes(secret), false);
  } finally { await app.close(); }
});

test('validation, conflicts, oversized bodies, missing routes and database errors retain only safe classifications', async () => {
  const output = capture(); const data = store(); const vehicle = fixtureVehicle();
  data.ready = async () => { throw new Error('mongodb://PRIVATE_USER:PRIVATE_PASSWORD@PRIVATE_HOST/PRIVATE_VIN'); };
  data.putVehicle = async () => { throw new ApiError(409, 'PRIVATE_ERROR_CODE'); };
  const app = createApp(data, output.logger);
  try {
    const cases = [
      { method: 'GET' as const, url: '/ready', status: 503, classification: 'storage_unavailable' },
      { method: 'GET' as const, url: '/PRIVATE_PATH?password=PRIVATE_QUERY', status: 404, classification: 'not_found' },
      { method: 'PUT' as const, url: `/v1/vehicles/${vehicle.vehicleId}`, payload: vehicle, status: 409, classification: 'conflict' },
      { method: 'PUT' as const, url: `/v1/vehicles/${vehicle.vehicleId}`, payload: '{PRIVATE_MALFORMED', headers: { 'content-type': 'application/json' }, status: 400, classification: 'invalid_request' },
      { method: 'PUT' as const, url: `/v1/vehicles/${vehicle.vehicleId}`, payload: JSON.stringify({ ...vehicle, alias: 'PRIVATE_OVERSIZED'.repeat(MAX_BODY_BYTES / 10) }), headers: { 'content-type': 'application/json' }, status: 413, classification: 'payload_too_large' },
    ];
    for (const entry of cases) {
      const response = await app.inject(entry); assert.equal(response.statusCode, entry.status);
      const completed = output.records().filter((record) => record.event === 'request_completed').at(-1)!;
      assert.equal(completed.statusCode, entry.status); assert.equal(completed.errorClass, entry.classification);
    }
    const records = output.records(); const completed = records.filter((record) => record.event === 'request_completed');
    assert.equal(completed.length, cases.length); assert.equal(new Set(completed.map((record) => record.requestId)).size, cases.length);
    assert.equal(records.filter((record) => record.event === 'request_error').length, cases.length);
    for (const [index, record] of records.entries()) boundedRequest(record, output.lines[index]!);
    assert.equal(output.lines.join('').includes('PRIVATE_'), false); assert.equal(output.lines.join('').includes(vehicle.vehicleId), false);
  } finally { await app.close(); }
});

test('malformed URLs and excessive path IDs are logged without reflecting the rejected path', async () => {
  const output = capture(); const app = createApp(store(), output.logger);
  try {
    for (const url of ['/v1/vehicles/PRIVATE_%zz', '/v1/vehicles/' + 'PRIVATE_LONG_ID'.repeat(20)]) {
      const response = await app.inject(url); assert.equal(response.statusCode, 400);
      assert.equal(response.body.includes('PRIVATE_'), false);
    }
    const records = output.records(); assert.equal(records.filter((record) => record.event === 'request_completed').length, 2);
    assert.ok(records.every((record) => record.route === 'unmatched')); assert.equal(output.lines.join('').includes('PRIVATE_'), false);
  } finally { await app.close(); }
});

test('a failing log destination does not turn a committed successful response into an error', async () => {
  const data = store(); const vehicle = fixtureVehicle(); let committed = 0;
  data.putVehicle = async (value) => { committed++; return { schemaVersion: 1, resourceId: value.vehicleId, revision: value.revision }; };
  const app = createApp(data, new ApiLog(() => { throw new Error('PRIVATE_COLLECTOR_FAILURE'); }));
  try { assert.equal((await app.inject({ method: 'PUT', url: `/v1/vehicles/${vehicle.vehicleId}`, payload: vehicle })).statusCode, 200); assert.equal(committed, 1); }
  finally { await app.close(); }
});

test('raw HTTP parser failures emit a bounded rejection without the raw packet', { timeout: 5000 }, async () => {
  const output = capture(); const app = createApp(store(), output.logger);
  try {
    const address = new URL(await app.listen({ host: '127.0.0.1', port: 0 }));
    const response = await new Promise<string>((resolve, reject) => {
      const socket = createConnection({ host: '127.0.0.1', port: Number(address.port) }); let text = '';
      socket.setTimeout(2000, () => { socket.destroy(); reject(new Error('Synthetic socket timeout')); });
      socket.on('connect', () => socket.write('BAD PRIVATE_RAW_PACKET\r\n\r\n')); socket.on('data', (chunk) => { text += chunk.toString(); });
      socket.on('end', () => resolve(text)); socket.on('error', reject);
    });
    assert.match(response, /^HTTP\/1.1 400/);
    const rejected = output.records().find((record) => record.event === 'request_rejected')!;
    assert.equal(rejected.errorClass, 'http_parse_error'); assert.equal(rejected.route, 'unmatched'); assert.equal(rejected.method, 'OTHER');
    assert.equal(output.lines.join('').includes('PRIVATE_RAW_PACKET'), false);
  } finally { await app.close(); }
});

test('disconnect during a pending request is terminated once, never reported as completed', { timeout: 5000 }, async () => {
  const output = capture(); const data = store(); let enter!: () => void; let release!: () => void; let abort!: () => void;
  const entered = new Promise<void>((resolve) => { enter = resolve; }); const blocked = new Promise<void>((resolve) => { release = resolve; });
  const aborted = new Promise<void>((resolve) => { abort = resolve; });
  data.ready = async () => { enter(); await blocked; };
  const app = createApp(data, new ApiLog((line, level) => { output.lines.push(line); if (JSON.parse(line).event === 'request_terminated') abort(); }));
  try {
    const address = await app.listen({ host: '127.0.0.1', port: 0 });
    const request = httpRequest(address + '/ready'); request.on('error', () => {}); request.end(); await entered; request.destroy(); await aborted; release();
    await app.close();
    const records = output.records(); assert.equal(records.filter((record) => record.event === 'request_terminated').length, 1);
    assert.equal(records.filter((record) => record.event === 'request_completed').length, 0);
    assert.equal(records.at(-1)!.statusCode, null); assert.equal(records.at(-1)!.errorClass, 'request_aborted');
  } finally { release(); await app.close(); }
});

test('socket timeout is classified without inventing a completed HTTP response', { timeout: 5000 }, async () => {
  const output = capture(); const data = store(); let release!: () => void;
  const blocked = new Promise<void>((resolve) => { release = resolve; });
  data.ready = async () => { await blocked; };
  const app = createApp(data, output.logger);
  app.server.on('connection', (socket) => { socket.setTimeout(50); });
  try {
    const address = await app.listen({ host: '127.0.0.1', port: 0 });
    await new Promise<void>((resolve, reject) => {
      const request = httpRequest(address + '/ready'); request.on('error', () => resolve());
      request.setTimeout(2000, () => { request.destroy(); reject(new Error('Synthetic request did not time out at server')); }); request.end();
    });
    release(); await app.close();
    const records = output.records(); const terminated = records.filter((record) => record.event === 'request_terminated');
    assert.equal(terminated.length, 1); assert.equal(terminated[0]!.errorClass, 'request_timeout');
    assert.equal(terminated[0]!.statusCode, null); assert.equal(records.filter((record) => record.event === 'request_completed').length, 0);
  } finally { release(); await app.close(); }
});
test('startup configuration failure emits structured diagnostics without configuration values', () => {
  const result = spawnSync(process.execPath, ['--import', 'tsx', 'src/server.ts'], {
    cwd: process.cwd(), env: { ...process.env, MONGODB_URI: 'PRIVATE_CONFIGURATION', PORT: 'invalid' }, encoding: 'utf8', timeout: 8000,
  });
  assert.equal(result.status, 1); const text = result.stdout + result.stderr;
  const records = text.trim().split('\n').map((line) => JSON.parse(line));
  assert.equal(records.find((record) => record.event === 'startup_failed').errorClass, 'configuration_error');
  assert.equal(text.includes('PRIVATE_CONFIGURATION'), false); assert.equal(text.includes('Error:'), false);
});

for (const kind of ['uncaught_exception', 'unhandled_rejection'] as const) {
  test(`fatal ${kind} exits after a safe structured record without raw error text`, () => {
    const action = kind === 'uncaught_exception' ? "setImmediate(() => { throw new Error('PRIVATE_FATAL_PAYLOAD'); });" : "void Promise.reject(new Error('PRIVATE_FATAL_PAYLOAD'));";
    const result = spawnSync(process.execPath, ['--import', 'tsx', '--input-type=module', '-e',
      "import {ApiLog,installFatalErrorLogging} from './src/logging.ts'; installFatalErrorLogging(new ApiLog()); " + action], { encoding: 'utf8', timeout: 8000 });
    assert.equal(result.status, 1); const text = result.stdout + result.stderr;
    assert.equal(JSON.parse(text.trim()).errorClass, kind); assert.equal(text.includes('PRIVATE_FATAL_PAYLOAD'), false);
  });
}


test('known API codes distinguish conflicts while unknown free text is suppressed', async () => {
  const output = capture(); const data = store(); const vehicle = fixtureVehicle();
  let code = 'revision_content_conflict';
  data.putVehicle = async () => { throw new ApiError(409, code); };
  const app = createApp(data, output.logger);
  try {
    for (const expected of ['revision_content_conflict', null]) {
      const response = await app.inject({ method: 'PUT', url: '/v1/vehicles/' + vehicle.vehicleId, payload: vehicle });
      assert.equal(response.statusCode, 409);
      const completed = output.records().filter((record) => record.event === 'request_completed').at(-1)!;
      assert.equal(completed.errorClass, 'conflict'); assert.equal(completed.errorCode, expected);
      assert.equal(output.records().filter((record) => record.event === 'request_error').at(-1)!.errorCode, expected);
      code = 'PRIVATE_UNTRUSTED_ERROR_CODE';
    }
    assert.equal(output.lines.join('').includes('PRIVATE_'), false);
    for (const [index, record] of output.records().entries()) boundedRequest(record, output.lines[index]!);
  } finally { await app.close(); }
});

test('incomplete HTTP headers time out as a safe 408 rather than a parser error', { timeout: 5000 }, async () => {
  const output = capture(); const app = createApp(store(), output.logger);
  app.server.headersTimeout = 50;
  app.server.requestTimeout = 50;
  // Node reads this interval when listening; @types/node exposes it only as a server option.
  Object.assign(app.server, { connectionsCheckingInterval: 20 });
  try {
    const address = new URL(await app.listen({ host: '127.0.0.1', port: 0 }));
    const response = await new Promise<string>((resolve, reject) => {
      const socket = createConnection({ host: '127.0.0.1', port: Number(address.port) }); let text = '';
      socket.setTimeout(2000, () => { socket.destroy(); reject(new Error('Synthetic header timeout did not run')); });
      socket.on('connect', () => socket.write('GET /PRIVATE_PENDING_PATH HTTP/1.1\r\nHost: localhost\r\nX-Private: PRIVATE_HEADER'));
      socket.on('data', (chunk) => { text += chunk.toString(); }); socket.on('end', () => resolve(text)); socket.on('error', reject);
    });
    assert.match(response, /^HTTP\/1.1 408 Request Timeout/);
    const records = output.records(); assert.equal(records.length, 1);
    assert.equal(records[0]!.event, 'request_rejected'); assert.equal(records[0]!.errorClass, 'request_timeout');
    assert.equal(records[0]!.statusCode, 408); assert.equal(records[0]!.route, 'unmatched');
    boundedRequest(records[0]!, output.lines[0]!); assert.equal(output.lines.join('').includes('PRIVATE_'), false);
  } finally { await app.close(); }
});


test('incomplete body timeout retains the accepted request ID and ignores a second parser error', { timeout: 5000 }, async () => {
  const output = capture(); const app = createApp(store(), output.logger);
  Object.assign(app.server, { headersTimeout: 50, requestTimeout: 50, connectionsCheckingInterval: 20 });
  try {
    const address = new URL(await app.listen({ host: '127.0.0.1', port: 0 }));
    const response = await new Promise<string>((resolve, reject) => {
      const socket = createConnection({ host: '127.0.0.1', port: Number(address.port) }); let text = '';
      socket.setTimeout(2000, () => { socket.destroy(); reject(new Error('Synthetic body timeout did not run')); });
      socket.on('connect', () => socket.write('PUT /v1/vehicles/00000000-0000-0000-0000-000000000001 HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{"PRIVATE_PARTIAL":'));
      socket.on('data', (chunk) => { text += chunk.toString(); }); socket.on('end', () => resolve(text)); socket.on('error', reject);
    });
    assert.match(response, /^HTTP\/1.1 408 Request Timeout/);
    await app.close();
    const records = output.records(); assert.deepEqual(records.map((record) => record.event), ['request_received', 'request_rejected']);
    assert.equal(records[0]!.requestId, records[1]!.requestId);
    assert.equal(records[1]!.errorClass, 'request_timeout'); assert.equal(records[1]!.statusCode, 408);
    assert.equal(records[1]!.route, '/v1/vehicles/:vehicleId'); assert.equal(records[1]!.method, 'PUT');
    for (const [index, record] of records.entries()) boundedRequest(record, output.lines[index]!);
    assert.equal(output.lines.join('').includes('PRIVATE_'), false);
    assert.equal(output.lines.join('').includes('00000000-0000-0000-0000-000000000001'), false);
  } finally { await app.close(); }
});
