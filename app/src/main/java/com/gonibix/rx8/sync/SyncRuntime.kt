package com.gonibix.rx8.sync

import android.content.Context
import android.annotation.SuppressLint
import android.app.Application
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gonibix.rx8.trips.TripRuntime
import com.gonibix.rx8.vehicle.VehicleDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Application-owned settings and serialized, bounded upload runs. No socket/acquisition ownership. */
class SyncRuntime private constructor(private val context: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val scheduleMutex = Mutex()
    private val store = RoomSyncStore(VehicleDatabase.open(context))
    private val mutableStatus = MutableStateFlow(UploadRuntimeStatus())
    val status = mutableStatus.asStateFlow()
    private val scheduler = UploadScheduler(context)
    private val persistentSettings: PersistentUploadSettings = PersistentUploadSettings(SharedPreferencesUploadStorage(context)) {
        schedule()
    }
    val settingsRepository: UploadSettingsRepository = persistentSettings

    init {
        scope.launch {
            try {
                persistentSettings.load()
                schedule()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableStatus.value = UploadRuntimeStatus(error = "Upload settings could not be opened. Trips remain saved locally.") }
        }
    }

    /** Called only after the terminal Room transaction has committed. Scheduling cannot undo it. */
    suspend fun onTerminalCommit() {
        try {
            persistentSettings.load()
            scheduleMutex.withLock { if (persistentSettings.settings.value.enabled) scheduler.enqueue() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableStatus.value = UploadRuntimeStatus(error = "Uploads could not be scheduled. Trips remain saved locally; use Retry uploads.") }
    }

    suspend fun retry() {
        persistentSettings.load()
        scheduleMutex.withLock {
            require(persistentSettings.settings.value.enabled) { "Uploads are paused" }
            store.retryExhausted()
            scheduler.enqueue(replace = true)
            mutableStatus.value = UploadRuntimeStatus(message = "Retry scheduled. Permanent rejections remain blocked.")
        }
    }

    private suspend fun schedule() = scheduleMutex.withLock {
        val settings = persistentSettings.settings.value
        try {
            if (settings.enabled) {
                scheduler.enable()
                mutableStatus.value = UploadRuntimeStatus(message = "Finished trips will upload when a network is available.")
            } else {
                scheduler.pause()
                mutableStatus.value = UploadRuntimeStatus(message = "Uploads are paused. Trips remain saved locally.")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableStatus.value = UploadRuntimeStatus(error = "Upload scheduling failed. Use Retry uploads after checking settings.") }
    }

    internal suspend fun runUploads(): Boolean = runMutex.withLock {
        persistentSettings.load()
        if (!persistentSettings.settings.value.enabled) return@withLock false
        // Worker-only process startup uses the same recorder recovery owner as the Activity/service.
        if (!TripRuntime.get(context).recorder.awaitInitialRecovery()) {
            mutableStatus.value = UploadRuntimeStatus(error = "Trip storage could not be recovered. Uploads will retry later.")
            return@withLock true
        }
        if (!store.hasReadyWork(System.currentTimeMillis())) {
            publishIdleStatus()
            return@withLock store.needsAnotherRun()
        }
        val settings = persistentSettings.bindForUpload() ?: return@withLock false
        val endpoint = requireNotNull(settings.baseUrl)
        val coordinator = SyncCoordinator(store, HttpsSyncTransport(endpoint))
        mutableStatus.value = UploadRuntimeStatus(isRunning = true, message = "Uploading finished trips.")
        try {
            for (attempt in 0 until MAX_REQUESTS_PER_RUN) {
                val current = persistentSettings.settings.value
                if (!current.enabled || current.baseUrl != endpoint) break
                val step = coordinator.runOnce()
                if (step == SyncStep.DISABLED || step == SyncStep.IDLE) break
            }
            publishIdleStatus()
            persistentSettings.settings.value.enabled && store.needsAnotherRun()
        } finally {
            if (mutableStatus.value.isRunning) mutableStatus.value = UploadRuntimeStatus(message = "Upload check stopped. Local trips are retained.")
        }
    }

    private suspend fun publishIdleStatus() {
        mutableStatus.value = if (store.hasBlocked()) UploadRuntimeStatus(error = "Some uploads need attention. Retry can reset exhausted attempts; permanent rejections stay blocked.")
        else UploadRuntimeStatus(message = "Upload check finished. Local trips are retained.")
    }

    internal fun reportFailure() {
        mutableStatus.value = UploadRuntimeStatus(error = "Uploads could not finish. Saved trips will be retried later.")
    }

    companion object {
        internal const val MAX_REQUESTS_PER_RUN = 25
        @Volatile private var instance: SyncRuntime? = null
        fun get(context: Context): SyncRuntime = instance ?: synchronized(this) {
            instance ?: SyncRuntime(context.applicationContext as Application).also { instance = it }
        }
    }
}

internal class SharedPreferencesUploadStorage(context: Context) : UploadSettingsStorage {
    private val preferences = context.getSharedPreferences("upload_settings_v1", Context.MODE_PRIVATE)
    override fun read() = UploadSettings(preferences.getString("base_url", null),
        preferences.getBoolean("enabled", false), preferences.getString("bound_base_url", null))
    @SuppressLint("UseKtx", "ApplySharedPref") // The checked synchronous commit establishes durable identity on IO.
    override fun write(settings: UploadSettings): Boolean = preferences.edit()
        .putString("base_url", settings.baseUrl).putBoolean("enabled", settings.enabled)
        .putString("bound_base_url", settings.boundBaseUrl).commit()
}

internal class UploadScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context)
    suspend fun enable() = withContext(Dispatchers.IO) {
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodicRequest()).result.get()
        enqueue()
    }
    suspend fun enqueue(replace: Boolean = false) = withContext(Dispatchers.IO) {
        manager.enqueueUniqueWork(IMMEDIATE, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            immediateRequest()).result.get()
        Unit
    }
    suspend fun pause() = withContext(Dispatchers.IO) { manager.cancelAllWorkByTag(TAG).result.get(); Unit }

    companion object {
        const val TAG = "terminal-trip-upload"
        const val IMMEDIATE = "terminal-trip-upload-now"
        const val PERIODIC = "terminal-trip-upload-catchup"
        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun immediateRequest() = OneTimeWorkRequestBuilder<TripUploadWorker>().setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).addTag(TAG).build()
        fun periodicRequest() = PeriodicWorkRequestBuilder<TripUploadWorker>(1, TimeUnit.HOURS).setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).addTag(TAG).build()
    }
}

class TripUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val runtime = SyncRuntime.get(applicationContext)
        return try { if (runtime.runUploads()) Result.retry() else Result.success() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { runtime.reportFailure(); Result.retry() }
    }
}
