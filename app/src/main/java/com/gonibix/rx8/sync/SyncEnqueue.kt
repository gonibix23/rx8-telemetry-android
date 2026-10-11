package com.gonibix.rx8.sync

import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.trips.TripSample
import com.gonibix.rx8.trips.TripSampleEntity
import com.gonibix.rx8.trips.TripSession
import com.gonibix.rx8.trips.TripSessionEntity
import com.gonibix.rx8.trips.TripStatus
import com.gonibix.rx8.vehicle.VehicleEntity
import com.gonibix.rx8.vehicle.VehicleProfile
import com.gonibix.rx8.vehicle.VinSource
import java.util.UUID

/** Invoked inside the source repository's transaction, never after that transaction commits. */
internal suspend fun SyncDao.enqueueVehicle(vehicle: VehicleEntity) = insertWork(vehicle.syncWork())
internal suspend fun SyncDao.enqueueSession(session: TripSessionEntity) = insertWork(session.syncWork())

internal fun VehicleEntity.syncWork() = SyncWorkEntity(
    id = UUID.randomUUID().toString(), kind = SyncRequestKind.VEHICLE.name,
    resourceId = id, vehicleId = id, sessionId = null, revision = revision,
    payloadJson = ApiV1Json.vehicle(ApiV1Mapper.vehicle(
        VehicleProfile(id, alias, vin, vinSource?.let(VinSource::valueOf), vinEcuId, createdAtEpochMillis), revision,
    )),
)

internal fun TripSessionEntity.syncWork() = SyncWorkEntity(
    id = UUID.randomUUID().toString(), kind = SyncRequestKind.SESSION.name,
    resourceId = id, vehicleId = vehicleId, sessionId = id, revision = revision,
    payloadJson = ApiV1Json.session(ApiV1Mapper.session(
        TripSession(id, vehicleId, vehicleAlias, startedAtEpochMillis, endedAtEpochMillis, durationMillis,
            TripStatus.valueOf(status), endReason, protocolLabel, origin, appVersion), revision,
    )),
)

internal fun TripSampleEntity.syncModel() = TripSample(
    id, tripId, sequence, observedAtEpochMillis, elapsedMillis, ObdMetric.valueOf(metric), value,
    unit, ecuId, ObdReadingStatus.valueOf(quality),
)