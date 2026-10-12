import { createHash } from 'node:crypto';
import { MongoClient, MongoServerError, ReadConcern, type ClientSession, type Collection, type Filter } from 'mongodb';
import { ApiError, type Vehicle, type TripSession, type Sample, type Batch, type BatchAck, type MetadataAck, type Page } from './models.js';

interface RecordRow<T> { _id: string; payload: T; digest: string }
interface SampleRow extends RecordRow<Sample> { sessionId: string; vehicleId: string; sequence: number }
interface BatchRow { _id: string; digest: string; sessionId: string; ack: BatchAck }
function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value !== null && typeof value === 'object') return '{' + Object.keys(value).sort().map((key) => JSON.stringify(key) + ':' + canonical((value as Record<string, unknown>)[key])).join(',') + '}';
  return JSON.stringify(value);
}
export function digest(value: unknown): string { return createHash('sha256').update(canonical(value)).digest('hex'); }
function conflict(code: string): never { throw new ApiError(409, code); }

export class TelemetryStore {
  private readonly vehicles: Collection<RecordRow<Vehicle>>;
  private readonly sessions: Collection<RecordRow<TripSession>>;
  private readonly samples: Collection<SampleRow>;
  private readonly batches: Collection<BatchRow>;
  constructor(private readonly client: MongoClient, private readonly database: string) {
    const db = client.db(database, { readConcern: new ReadConcern('majority'), writeConcern: { w: 'majority', journal: true, wtimeoutMS: 5000 } });
    this.vehicles = db.collection('vehicles'); this.sessions = db.collection('sessions');
    this.samples = db.collection('samples'); this.batches = db.collection('batches');
  }
  async initialize(): Promise<void> {
    const topology = await this.client.db(this.database).command({ hello: 1 });
    if (!topology.setName && topology.msg !== 'isdbgrid') throw new ApiError(503, 'replica_set_required');
    // Explicit indexes before serving requests; _id is the unique canonical resource UUID.
    // VIN is optional metadata, never global identity: reinstalling can legitimately create
    // another UUID for the same car. Existing UUIDs still cannot be reassigned to a different VIN.
    await this.vehicles.createIndex({ 'payload.vin': 1 }, { partialFilterExpression: { 'payload.vin': { $type: 'string' } }, name: 'optional_vin_lookup' });
    await this.sessions.createIndex({ 'payload.vehicleId': 1, _id: 1 }, { name: 'vehicle_sessions' });
    await this.samples.createIndex({ sessionId: 1, sequence: 1 }, { unique: true, name: 'session_sequence' });
    // Ensure the batch collection exists before the first transaction.
    await this.batches.createIndex({ sessionId: 1 }, { name: 'session_batches' });
  }
  async ready(): Promise<void> { await this.client.db(this.database).command({ ping: 1 }, { timeoutMS: 3000 }); }
  private async transaction<T>(work: (session: ClientSession) => Promise<T>): Promise<T> {
    // A competing insert can report duplicate-key without a transient label. A fresh snapshot
    // lets an identical retry deduplicate, or reports an actual content conflict after bounded retries.
    for (let attempt = 0; ; attempt++) {
      try {
        return await this.client.withSession((session) => session.withTransaction(() => work(session), {
          readConcern: { level: 'snapshot' }, writeConcern: { w: 'majority', journal: true, wtimeoutMS: 5000 },
          readPreference: 'primary', maxCommitTimeMS: 5000, timeoutMS: 8000,
        }));
      } catch (error) {
        if (error instanceof MongoServerError && error.code === 11000) {
          if (attempt < 2) continue;
          conflict('unique_identity_conflict');
        }
        throw error;
      }
    }
  }
  async putVehicle(payload: Vehicle): Promise<MetadataAck> {
    return this.transaction(async (session) => {
      const _id = payload.vehicleId.toLowerCase(); const fingerprint = digest(payload);
      const existing = await this.vehicles.findOne({ _id }, { session });
      if (existing) {
        this.revision(existing, payload.revision, fingerprint);
        if (existing.payload.vin !== null && (existing.payload.vin !== payload.vin || existing.payload.vinSource !== payload.vinSource)) conflict('vin_reassignment');
        if (existing.payload.revision === payload.revision) return { schemaVersion: 1, resourceId: payload.vehicleId, revision: payload.revision };
      }
      await this.vehicles.replaceOne({ _id }, { payload, digest: fingerprint }, { upsert: true, session });
      return { schemaVersion: 1, resourceId: payload.vehicleId, revision: payload.revision };
    });
  }
  private revision(existing: RecordRow<{ revision: number }>, revision: number, fingerprint: string): void {
    if (revision < existing.payload.revision) conflict('outdated_revision');
    if (revision === existing.payload.revision && existing.digest !== fingerprint) conflict('revision_content_conflict');
  }
  async putSession(payload: TripSession): Promise<MetadataAck> {
    return this.transaction(async (session) => {
      if (!await this.vehicles.findOne({ _id: payload.vehicleId.toLowerCase() }, { session })) conflict('vehicle_not_stored');
      const _id = payload.sessionId.toLowerCase(); const fingerprint = digest(payload);
      const existing = await this.sessions.findOne({ _id }, { session });
      if (existing) {
        this.revision(existing, payload.revision, fingerprint);
        const previous = existing.payload;
        if (previous.vehicleId !== payload.vehicleId || previous.startedAt !== payload.startedAt || previous.appVersion !== payload.appVersion || previous.dataOrigin !== payload.dataOrigin) conflict('session_identity_conflict');
        if (previous.obdProtocol !== null && previous.obdProtocol !== payload.obdProtocol) conflict('protocol_conflict');
        if (previous.status !== 'recording' && (payload.status !== previous.status || payload.endedAt !== previous.endedAt || payload.durationMs !== previous.durationMs)) conflict('terminal_session_conflict');
        if (previous.revision === payload.revision) return { schemaVersion: 1, resourceId: payload.sessionId, revision: payload.revision };
      }
      await this.sessions.replaceOne({ _id }, { payload, digest: fingerprint }, { upsert: true, session });
      return { schemaVersion: 1, resourceId: payload.sessionId, revision: payload.revision };
    });
  }
  async putBatch(payload: Batch): Promise<BatchAck> {
    return this.transaction(async (session) => {
      const _id = payload.batchId.toLowerCase(); const fingerprint = digest(payload);
      const previousBatch = await this.batches.findOne({ _id }, { session });
      if (previousBatch) {
        if (previousBatch.digest !== fingerprint) conflict('batch_content_conflict');
        return previousBatch.ack;
      }
      const trip = await this.sessions.findOne({ _id: payload.sessionId.toLowerCase() }, { session });
      if (!trip || trip.payload.vehicleId !== payload.vehicleId) conflict('session_not_stored');
      if (trip.payload.status === 'recording') conflict('session_not_terminal');
      const results: BatchAck['results'] = [];
      // Bounded bulk lookups avoid per-sample network round trips. Operations remain serial:
      // the driver does not support concurrent operations inside the same transaction.
      const previousSamples = await this.samples.find({ $or: [
        { _id: { $in: payload.samples.map((sample) => sample.sampleId) } },
        { sessionId: payload.sessionId, sequence: { $in: payload.samples.map((sample) => sample.sequence) } },
      ] }, { session }).toArray();
      const byId = new Map(previousSamples.map((row) => [row._id, row]));
      const bySequence = new Map(previousSamples.filter((row) => row.sessionId === payload.sessionId).map((row) => [row.sequence, row]));
      const inserts: SampleRow[] = [];
      for (const sample of payload.samples) {
        if (sample.elapsedMs > trip.payload.durationMs!) conflict('sample_outside_duration');
        const sampleId = sample.sampleId.toLowerCase();
        const sampleFingerprint = digest({ vehicleId: payload.vehicleId, sessionId: payload.sessionId, sample });
        const existing = byId.get(sampleId);
        if (existing) {
          if (existing.digest !== sampleFingerprint) conflict('sample_content_conflict');
          results.push({ sampleId: sample.sampleId, status: 'already_stored' });
        } else {
          if (bySequence.has(sample.sequence)) conflict('sample_sequence_conflict');
          inserts.push({ _id: sampleId, payload: sample, digest: sampleFingerprint,
            vehicleId: payload.vehicleId, sessionId: payload.sessionId, sequence: sample.sequence });
          results.push({ sampleId: sample.sampleId, status: 'stored' });
        }
      }
      if (inserts.length > 0) await this.samples.insertMany(inserts, { session, ordered: true });
      const ack: BatchAck = { schemaVersion: 1, batchId: payload.batchId, results };
      // The digest binds the immutable complete body; the receipt does not duplicate sample data.
      await this.batches.insertOne({ _id, digest: fingerprint, sessionId: payload.sessionId, ack }, { session });
      return ack;
    });
  }
  async getVehicle(id: string): Promise<Vehicle | null> { return (await this.vehicles.findOne({ _id: id.toLowerCase() }))?.payload ?? null; }
  async getSession(id: string): Promise<TripSession | null> { return (await this.sessions.findOne({ _id: id.toLowerCase() }))?.payload ?? null; }
  async listVehicles(limit: number, after?: string): Promise<Page<Vehicle>> {
    const rows = await this.vehicles.find(after ? { _id: { $gt: after.toLowerCase() } } : {}).sort({ _id: 1 }).limit(limit + 1).toArray();
    return this.page(rows, limit);
  }
  async listSessions(limit: number, after?: string, vehicleId?: string): Promise<Page<TripSession>> {
    const filter: Filter<RecordRow<TripSession>> = {};
    if (after) filter._id = { $gt: after.toLowerCase() };
    if (vehicleId) filter['payload.vehicleId'] = vehicleId;
    const rows = await this.sessions.find(filter).sort({ _id: 1 }).limit(limit + 1).toArray();
    return this.page(rows, limit);
  }
  private page<T>(rows: RecordRow<T>[], limit: number): Page<T> {
    return { schemaVersion: 1, items: rows.slice(0, limit).map((row) => row.payload), nextCursor: rows.length > limit ? rows[limit - 1]!._id : null };
  }
  async listSamples(sessionId: string, limit: number, after: number | null): Promise<Page<Sample>> {
    const rows = await this.samples.find({ sessionId, ...(after === null ? {} : { sequence: { $gt: after } }) }).sort({ sequence: 1 }).limit(limit + 1).toArray();
    return { schemaVersion: 1, items: rows.slice(0, limit).map((row) => row.payload), nextCursor: rows.length > limit ? String(rows[limit - 1]!.sequence) : null };
  }
}
