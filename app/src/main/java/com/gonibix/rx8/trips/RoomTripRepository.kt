package com.gonibix.rx8.trips

import androidx.room.withTransaction
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.vehicle.VehicleDatabase
import com.gonibix.rx8.sync.SyncSampleEntity
import com.gonibix.rx8.sync.enqueueSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Commits session state and immutable samples together; opening history never performs recovery. */
class RoomTripRepository(private val database: VehicleDatabase) : TripRepository {
    private val dao get() = database.trips()

    override suspend fun create(session: TripSession) = database.withTransaction {
        requireUuid(session.id)
        requireUuid(session.vehicleId)
        require(session.vehicleAlias.isNotBlank() && session.vehicleAlias.length <= 60)
        require(session.startedAtEpochMillis >= 0L)
        require(session.status == TripStatus.RECORDING && session.endedAtEpochMillis == null)
        require(session.durationMillis == 0L && session.endReason == null)
        require(session.origin.isNotBlank())
        check(dao.recording().isEmpty()) { "A trip is already recording" }
        val entity = session.toEntity()
        dao.insertSession(entity)
        database.sync().enqueueSession(entity)
    }

    override suspend fun append(tripId: String, samples: List<TripSample>) = database.withTransaction {
        val session = requireNotNull(dao.find(tripId)) { "Trip not found" }
        check(session.status == TripStatus.RECORDING.name) { "Trip is not recording" }
        if (samples.isEmpty()) return@withTransaction
        val last = dao.lastSample(tripId)
        var expectedSequence = Math.addExact(last?.sequence ?: -1L, 1L)
        var elapsed = last?.elapsedMillis ?: 0L
        val rows = samples.map { sample ->
            requireUuid(sample.id)
            require(sample.tripId == tripId) { "Sample belongs to another trip" }
            require(sample.sequence == expectedSequence) { "Samples must have contiguous sequence numbers" }
            require(sample.observedAtEpochMillis >= 0L && sample.elapsedMillis >= elapsed)
            require(sample.unit.isNotBlank() && sample.ecuId.isNotBlank())
            require(sample.quality != ObdReadingStatus.VALID || sample.value?.isFinite() == true) {
                "A valid reading requires a finite value"
            }
            expectedSequence = Math.addExact(expectedSequence, 1L)
            elapsed = sample.elapsedMillis
            sample.toEntity()
        }
        dao.insertSamples(rows)
        database.sync().insertSamples(rows.map { SyncSampleEntity(it.id) })
        dao.updateSession(session.copy(durationMillis = elapsed))
    }

    override suspend fun finish(
        tripId: String,
        endedAtEpochMillis: Long,
        durationMillis: Long,
        status: TripStatus,
        reason: String,
    ) = database.withTransaction {
        require(status != TripStatus.RECORDING && endedAtEpochMillis >= 0L && reason.isNotBlank())
        val session = requireNotNull(dao.find(tripId)) { "Trip not found" }
        check(session.status == TripStatus.RECORDING.name) { "Trip is already finished" }
        require(durationMillis >= session.durationMillis) { "Duration cannot precede saved samples" }
        val updated = session.copy(
            endedAtEpochMillis = endedAtEpochMillis,
            durationMillis = durationMillis,
            status = status.name,
            endReason = reason,
            revision = Math.addExact(session.revision, 1L),
        )
        dao.updateSession(updated)
        database.sync().enqueueSession(updated)
    }

    override suspend fun recoverInterrupted() = database.withTransaction {
        // The caller owns startup recovery and must never call it against its live recorder.
        dao.recording().forEach { session ->
            val last = dao.lastSample(session.id)
            val updated = session.copy(
                endedAtEpochMillis = last?.observedAtEpochMillis ?: session.startedAtEpochMillis,
                durationMillis = last?.elapsedMillis ?: 0L,
                status = TripStatus.INTERRUPTED.name,
                endReason = PROCESS_INTERRUPTION_REASON,
                revision = Math.addExact(session.revision, 1L),
            )
            dao.updateSession(updated)
            database.sync().enqueueSession(updated)
        }
    }

    override suspend fun list(limit: Int): List<TripSession> {
        require(limit in 1..MAX_PAGE_SIZE)
        return dao.list(limit).map(TripSessionEntity::toModel)
    }

    override suspend fun historyPage(request: TripHistoryRequest): TripHistoryPage {
        require(request.limit in 1..MAX_HISTORY_PAGE_SIZE)
        request.vehicleId?.let(::requireUuid)
        if (request.direction == TripHistoryDirection.NEWEST) {
            require(request.cursor == null) { "The newest page does not take a cursor" }
        } else {
            val cursor = requireNotNull(request.cursor) { "A history cursor is required" }
            require(cursor.startedAtEpochMillis >= 0L)
            requireUuid(cursor.tripId)
        }
        return database.withTransaction {
            val vehicleId = request.vehicleId
            val cursor = request.cursor
            val limit = request.limit
            val rows = when (request.direction) {
                TripHistoryDirection.NEWEST -> if (vehicleId == null) dao.list(limit)
                    else dao.latestForVehicle(vehicleId, limit)
                TripHistoryDirection.OLDER -> requireNotNull(cursor).let {
                    if (vehicleId == null) dao.older(it.startedAtEpochMillis, it.tripId, limit)
                    else dao.olderForVehicle(vehicleId, it.startedAtEpochMillis, it.tripId, limit)
                }
                TripHistoryDirection.NEWER -> requireNotNull(cursor).let {
                    if (vehicleId == null) dao.newer(it.startedAtEpochMillis, it.tripId, limit)
                    else dao.newerForVehicle(vehicleId, it.startedAtEpochMillis, it.tripId, limit)
                }.asReversed()
                TripHistoryDirection.AT_OR_OLDER -> requireNotNull(cursor).let {
                    if (vehicleId == null) dao.atOrOlder(it.startedAtEpochMillis, it.tripId, limit)
                    else dao.atOrOlderForVehicle(vehicleId, it.startedAtEpochMillis, it.tripId, limit)
                }
            }
            if (rows.isEmpty()) return@withTransaction TripHistoryPage(emptyList())
            val first = rows.first()
            val last = rows.last()
            // These indexed existence probes share the page's read transaction. They do not
            // count history, read telemetry samples, or infer flags from a possibly deleted cursor.
            val hasNewer = if (vehicleId == null) dao.hasNewer(first.startedAtEpochMillis, first.id)
                else dao.hasNewerForVehicle(vehicleId, first.startedAtEpochMillis, first.id)
            val hasOlder = if (vehicleId == null) dao.hasOlder(last.startedAtEpochMillis, last.id)
                else dao.hasOlderForVehicle(vehicleId, last.startedAtEpochMillis, last.id)
            TripHistoryPage(rows.map(TripSessionEntity::toModel), hasNewer, hasOlder)
        }
    }

    override suspend fun find(tripId: String): TripSession? = dao.find(tripId)?.toModel()

    override suspend fun samples(tripId: String, afterSequence: Long, limit: Int): List<TripSample> {
        require(afterSequence >= -1L && limit in 1..MAX_PAGE_SIZE)
        return dao.samples(tripId, afterSequence, limit).map(TripSampleEntity::toModel)
    }

    override suspend fun delete(tripId: String) = database.withTransaction {
        val session = dao.find(tripId) ?: return@withTransaction
        check(session.status != TripStatus.RECORDING.name) { "Stop recording before deleting a trip" }
        dao.deleteSession(tripId)
    }

    override suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        val path = requireNotNull(database.openHelper.readableDatabase.path) { "Database path unavailable" }
        listOf(path, "$path-wal", "$path-shm").sumOf { File(it).length() }
    }

    companion object {
        const val PROCESS_INTERRUPTION_REASON = "Process interrupted"
        private const val MAX_PAGE_SIZE = 1_000
        private const val MAX_HISTORY_PAGE_SIZE = 50
    }
}

private fun requireUuid(value: String) {
    require(runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)) {
        "A canonical UUID is required"
    }
}

private fun TripSession.toEntity() = TripSessionEntity(
    id, vehicleId, vehicleAlias, startedAtEpochMillis, endedAtEpochMillis, durationMillis,
    status.name, endReason, protocolLabel, origin, appVersion = appVersion,
)

private fun TripSessionEntity.toModel() = TripSession(
    id, vehicleId, vehicleAlias, startedAtEpochMillis, endedAtEpochMillis, durationMillis,
    TripStatus.valueOf(status), endReason, protocolLabel, origin, appVersion,
)

private fun TripSample.toEntity() = TripSampleEntity(
    id, tripId, sequence, observedAtEpochMillis, elapsedMillis, metric.name,
    value.takeIf { quality == ObdReadingStatus.VALID }, unit, ecuId, quality.name,
)

private fun TripSampleEntity.toModel() = TripSample(
    id, tripId, sequence, observedAtEpochMillis, elapsedMillis, ObdMetric.valueOf(metric),
    value, unit, ecuId, ObdReadingStatus.valueOf(quality),
)

