import Fastify, { type FastifyError, type FastifyReply } from 'fastify';
import { randomUUID } from 'node:crypto';
import { ApiLog, RequestLogs } from './logging.js';
import { ApiError } from './models.js';
import { TelemetryStore } from './store.js';
import { vehicle, tripSession, batch, uuid, pageLimit, afterSequence } from './validation.js';

export const MAX_BODY_BYTES = 1024 * 1024;
export function createApp(store: TelemetryStore, log = new ApiLog()) {
  const requests = new RequestLogs(log);
  const rejectedSockets = new WeakSet<object>();
  // The default logger can serialize private URLs/errors. Only our allowlisted traces are enabled.
  const app = Fastify({ logger: false, requestIdHeader: false, genReqId: () => randomUUID(), bodyLimit: MAX_BODY_BYTES,
    requestTimeout: 15000, connectionTimeout: 15000, keepAliveTimeout: 5000, routerOptions: { maxParamLength: 100 }, trustProxy: false,
    frameworkErrors(error, request, reply) {
      requests.begin(request, reply);
      const status = error.code === 'FST_ERR_BAD_URL' || error.code === 'FST_ERR_MAX_PARAM_LENGTH' ? 400 : 500;
      requests.error(request, status);
      const response: FastifyReply = reply;
      void response.code(status).send({ code: status === 400 ? 'invalid_request' : 'server_error' });
    },
    clientErrorHandler(error, socket) {
      // Ending a malformed/incomplete request can emit another parser error for the same socket.
      if (rejectedSockets.has(socket)) return;
      rejectedSockets.add(socket);
      const timedOut = error.code === 'ERR_HTTP_REQUEST_TIMEOUT';
      const status = timedOut ? 408 : error.code === 'HPE_HEADER_OVERFLOW' ? 431 : 400;
      const errorClass = timedOut ? 'request_timeout' : 'http_parse_error';
      if (!requests.rejectIncomplete(socket, status, errorClass)) {
        log.request('request_rejected', randomUUID(), 'OTHER', undefined, status, 0, errorClass);
      }
      if (socket.destroyed || !socket.writable) return;
      // No raw packet, parser message, address or header is echoed or retained.
      const body = timedOut ? '{"code":"request_timeout"}' : '{"code":"invalid_request"}';
      const reason = timedOut ? 'Request Timeout' : status === 431 ? 'Request Header Fields Too Large' : 'Bad Request';
      socket.end(`HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
    },
  });
  app.addHook('onRequest', async (request, reply) => { requests.begin(request, reply); });
  app.addHook('onTimeout', async (request) => { requests.terminate(request, 'request_timeout'); });
  app.addHook('onRequestAbort', async (request) => { requests.terminate(request, 'request_aborted'); });
  app.addHook('onSend', async (_request, reply) => { reply.header('Cache-Control', 'no-store').header('X-Content-Type-Options', 'nosniff'); });
  app.setErrorHandler<FastifyError>((error, request, reply) => {
    const status = error instanceof ApiError ? error.statusCode : error.code === 'FST_ERR_CTP_BODY_TOO_LARGE' ? 413 :
      error.statusCode && error.statusCode >= 400 && error.statusCode < 500 ? error.statusCode : 503;
    requests.error(request, status, error instanceof ApiError ? error.code : null);
    if (error instanceof ApiError) return reply.code(error.statusCode).send({ code: error.code });
    if (error.code === 'FST_ERR_CTP_BODY_TOO_LARGE') return reply.code(413).send({ code: 'payload_too_large' });
    if (error.statusCode && error.statusCode >= 400 && error.statusCode < 500) return reply.code(error.statusCode).send({ code: 'invalid_request' });
    return reply.code(503).send({ code: 'storage_unavailable' });
  });
  app.setNotFoundHandler((request, reply) => { requests.error(request, 404); return reply.code(404).send({ code: 'not_found' }); });
  app.get('/health', async () => ({ status: 'ok' }));
  app.get('/ready', async () => { await store.ready(); return { status: 'ready' }; });
  app.put<{ Params: { vehicleId: string } }>('/v1/vehicles/:vehicleId', async (request) => {
    const payload = vehicle(request.body);
    if (uuid(request.params.vehicleId) !== payload.vehicleId) throw new ApiError(400, 'resource_id_mismatch');
    return store.putVehicle(payload);
  });
  app.put<{ Params: { sessionId: string } }>('/v1/sessions/:sessionId', async (request) => {
    const payload = tripSession(request.body);
    if (uuid(request.params.sessionId) !== payload.sessionId) throw new ApiError(400, 'resource_id_mismatch');
    return store.putSession(payload);
  });
  app.post('/v1/telemetry/batches', async (request) => store.putBatch(batch(request.body, request.headers['idempotency-key'])));
  app.get<{ Querystring: { limit?: string; cursor?: string } }>('/v1/vehicles', async (request) =>
    store.listVehicles(pageLimit(request.query.limit), request.query.cursor === undefined ? undefined : uuid(request.query.cursor)));
  app.get<{ Params: { vehicleId: string } }>('/v1/vehicles/:vehicleId', async (request) => {
    const result = await store.getVehicle(uuid(request.params.vehicleId));
    if (!result) throw new ApiError(404, 'not_found'); return result;
  });
  app.get<{ Querystring: { limit?: string; cursor?: string; vehicleId?: string } }>('/v1/sessions', async (request) =>
    store.listSessions(pageLimit(request.query.limit), request.query.cursor === undefined ? undefined : uuid(request.query.cursor),
      request.query.vehicleId === undefined ? undefined : uuid(request.query.vehicleId)));
  app.get<{ Params: { sessionId: string } }>('/v1/sessions/:sessionId', async (request) => {
    const result = await store.getSession(uuid(request.params.sessionId));
    if (!result) throw new ApiError(404, 'not_found'); return result;
  });
  app.get<{ Params: { sessionId: string }; Querystring: { limit?: string; cursor?: string } }>('/v1/sessions/:sessionId/samples', async (request) => {
    const id = uuid(request.params.sessionId);
    if (!await store.getSession(id)) throw new ApiError(404, 'not_found');
    return store.listSamples(id, pageLimit(request.query.limit), afterSequence(request.query.cursor));
  });
  return app;
}
