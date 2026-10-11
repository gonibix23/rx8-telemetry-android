package com.gonibix.rx8.trips

import android.content.Context
import android.os.StatFs
import com.gonibix.rx8.BuildConfig
import com.gonibix.rx8.sync.RoomSyncStore
import com.gonibix.rx8.vehicle.VehicleDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process lifetime allows final queued writes to finish after a service loses its last binding. */
class TripRuntime private constructor(context: Context) {
    private val database = VehicleDatabase.open(context)
    val repository: TripRepository = RoomTripRepository(database)
    val syncStore = RoomSyncStore(database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val recorder = TripRecorder(
        repository, scope,
        availableBytes = { StatFs(context.filesDir.absolutePath).availableBytes },
        appVersion = BuildConfig.VERSION_NAME,
    )

    companion object {
        @Volatile private var instance: TripRuntime? = null
        fun get(context: Context): TripRuntime = instance ?: synchronized(this) {
            instance ?: TripRuntime(context.applicationContext).also { instance = it }
        }
    }
}
