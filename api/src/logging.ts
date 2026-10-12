import { randomUUID } from 'node:crypto';
import { writeSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import type { FastifyReply, FastifyRequest } from 'fastify';

export type ErrorClass = 'invalid_request' | 'not_found' | 'conflict' | 'payload_too_large' |
  'storage_unavailable' | 'server_error' | 'request_timeout' | 'request_aborted' | 'http_parse_error' |
  'configuration_error' | 'database_unavailable' | 'listen_failed' | 'shutdown_failed' |
  'uncaught_exception' | 'unhandled_rejection';
type RequestEvent = 'request_received' | 'request_completed' | 'request_error' | 'request_terminated' | 'request_rejected';
type LifecycleEvent = 'startup_started' | 'server_listening' | 'startup_failed' | 'shutdown_started' | 'shutdown_complete' | 'shutdown_failed' | 'runtime_failed';
type Level = 'info' | 'warn' | 'error';
export type LogSink = (line: string, level: Level) => void;
const routes = new Set(['/health', '/ready', '/v1/vehicles', '/v1/vehicles/:vehicleId', '/v1/sessions',
  '/v1/sessions/:sessionId', '/v1/sessions/:sessionId/samples', '/v1/telemetry/batches']);
const apiErrorCodes = new Set(['invalid_payload', 'resource_id_mismatch', 'idempotency_key_mismatch', 'not_found',
  'replica_set_required', 'unique_identity_conflict', 'vin_reassignment', 'outdated_revision', 'revision_content_conflict',
  'vehicle_not_stored', 'session_identity_conflict', 'protocol_conflict', 'terminal_session_conflict', 'batch_content_conflict',
  'session_not_stored', 'session_not_terminal', 'sample_outside_duration', 'sample_content_conflict', 'sample_sequence_conflict']);
const methods = new Set(['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS', 'TRACE', 'CONNECT']);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** Allowlisted JSON lines only: never pass a request, error, URL or database object to a logger. */
export class ApiLog {
  constructor(private readonly sink: LogSink = (line, level) => { writeSync(level === 'info' ? 1 : 2, line); }) {}

  request(event: RequestEvent, id: string, method: string, route: string | undefined,
    statusCode: number | null, durationMs: number, errorClass: ErrorClass | null = null, errorCode: string | null = null): void {
    const status = Number.isInteger(statusCode) && statusCode! >= 100 && statusCode! <= 599 ? statusCode : null;
    const level = errorClass === null ? 'info' : status !== null && status < 500 ? 'warn' : 'error';
    this.emit(level, { event, requestId: uuid.test(id) ? id : randomUUID(),
      method: methods.has(method) ? method : 'OTHER', route: route !== undefined && routes.has(route) ? route : 'unmatched',
      statusCode: status, durationMs: Number.isFinite(durationMs) ? Math.min(86_400_000, Math.max(0, Math.round(durationMs * 1000) / 1000)) : 0,
      errorClass, errorCode: errorCode !== null && apiErrorCodes.has(errorCode) ? errorCode : null });
  }

  lifecycle(event: LifecycleEvent, errorClass: ErrorClass | null = null): void {
    this.emit(errorClass === null ? 'info' : 'error', { event, errorClass });
  }

  private emit(level: Level, fields: Record<string, unknown>): void {
    // Synchronous, small writes preserve fatal diagnostics before exit; logging failure never changes an ACK.
    try { this.sink(JSON.stringify({ timestamp: new Date().toISOString(), level, ...fields }) + '\n', level); }
    catch { /* A broken/closed collector must not corrupt the request or its durable database result. */ }
  }
}

export function classifyStatus(status: number): ErrorClass | null {
  if (status < 400) return null;
  if (status === 404) return 'not_found';
  if (status === 409) return 'conflict';
  if (status === 413) return 'payload_too_large';
  if (status === 408) return 'request_timeout';
  if (status === 503) return 'storage_unavailable';
  return status < 500 ? 'invalid_request' : 'server_error';
}

/** One trace per accepted HTTP request; completion and abort cannot both claim completion. */
export class RequestLogs {
  private readonly traces = new WeakMap<FastifyRequest, { started: number; finished: boolean; error: ErrorClass | null; errorCode: string | null }>();
  private readonly requestsBySocket = new WeakMap<object, FastifyRequest>();
  constructor(private readonly log: ApiLog) {}
  begin(request: FastifyRequest, reply: FastifyReply): void {
    if (this.traces.has(request)) return;
    this.traces.set(request, { started: performance.now(), finished: false, error: null, errorCode: null });
    this.requestsBySocket.set(request.raw.socket, request);
    reply.header('X-Request-ID', request.id);
    this.write(request, 'request_received', null);
    reply.raw.once('finish', () => this.finish(request, reply.raw.statusCode));
    reply.raw.once('close', () => { if (!reply.raw.writableFinished) this.terminate(request, 'request_aborted'); });
  }
  error(request: FastifyRequest, status: number, errorCode: string | null = null): void {
    const trace = this.traces.get(request);
    if (trace === undefined || trace.finished) return;
    trace.error = classifyStatus(status);
    trace.errorCode = errorCode;
    this.write(request, 'request_error', status, trace.error);
  }
  /** A parser/body timeout belongs to the already accepted request when its body is incomplete. */
  rejectIncomplete(socket: object, status: number, error: 'request_timeout' | 'http_parse_error'): boolean {
    const request = this.requestsBySocket.get(socket);
    if (request === undefined || request.raw.complete) return false;
    const trace = this.traces.get(request);
    if (trace === undefined || trace.finished) return false;
    trace.finished = true;
    this.write(request, 'request_rejected', status, error);
    return true;
  }
  terminate(request: FastifyRequest, reason: 'request_timeout' | 'request_aborted'): void {
    const trace = this.traces.get(request);
    if (trace === undefined || trace.finished) return;
    trace.finished = true;
    this.write(request, 'request_terminated', null, reason);
  }
  private finish(request: FastifyRequest, status: number): void {
    const trace = this.traces.get(request);
    if (trace === undefined || trace.finished) return;
    trace.finished = true;
    this.write(request, 'request_completed', status, trace.error ?? classifyStatus(status));
  }
  private write(request: FastifyRequest, event: RequestEvent, status: number | null, error: ErrorClass | null = null): void {
    const trace = this.traces.get(request);
    this.log.request(event, request.id, request.method, request.routeOptions.url, status,
      trace === undefined ? 0 : performance.now() - trace.started, error, trace?.errorCode);
  }
}

/** Fatal process errors are classified, flushed without raw error text, and never resume serving. */
export function installFatalErrorLogging(log: ApiLog): void {
  process.once('uncaughtException', () => { log.lifecycle('runtime_failed', 'uncaught_exception'); process.exit(1); });
  process.once('unhandledRejection', () => { log.lifecycle('runtime_failed', 'unhandled_rejection'); process.exit(1); });
}
