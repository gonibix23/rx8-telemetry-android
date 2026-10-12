import Fastify, { type FastifyError } from 'fastify';
import { ApiError } from './models.js';
import { TelemetryStore } from './store.js';
import { vehicle, tripSession, batch, uuid, pageLimit, afterSequence } from './validation.js';

export const MAX_BODY_BYTES = 1024 * 1024;
export function createApp(store: TelemetryStore) {
  // No automatic URL, headers, payload, request identifiers or Mongo error logging.
  const app = Fastify({ logger: false, bodyLimit: MAX_BODY_BYTES,
    requestTimeout: 15000, connectionTimeout: 15000, keepAliveTimeout: 5000, routerOptions: { maxParamLength: 100 }, trustProxy: false });
  app.addHook('onSend', async (_request, reply) => { reply.header('Cache-Control', 'no-store').header('X-Content-Type-Options', 'nosniff'); });
  app.setErrorHandler<FastifyError>((error, _request, reply) => {
    if (error instanceof ApiError) return reply.code(error.statusCode).send({ code: error.code });
    if (error.code === 'FST_ERR_CTP_BODY_TOO_LARGE') return reply.code(413).send({ code: 'payload_too_large' });
    if (error.statusCode && error.statusCode >= 400 && error.statusCode < 500) return reply.code(error.statusCode).send({ code: 'invalid_request' });
    return reply.code(503).send({ code: 'storage_unavailable' });
  });
  app.setNotFoundHandler((_request, reply) => reply.code(404).send({ code: 'not_found' }));
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
