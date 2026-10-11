package com.gonibix.rx8.dashboard

import com.gonibix.rx8.obd.ObdMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Small preference boundary, independent of vehicle identity, sensor samples and Android UI. */
internal interface DashboardFavoritesStorage {
    suspend fun read(): Set<String>
    suspend fun write(ids: Set<String>): Boolean
}

internal object FavoriteMetricIds {
    // Existing IDs stay byte-for-byte identical; new fields append stable enum-name IDs.
    private val ids = ObdMetric.entries
        .filterNot { it in setOf(ObdMetric.ENGINE_RPM, ObdMetric.SPEED, ObdMetric.COOLANT_TEMPERATURE) }
        .associateWith { it.name.lowercase(java.util.Locale.ROOT) }

    fun accepts(metric: ObdMetric): Boolean = metric in ids
    fun decode(saved: Set<String>): Set<ObdMetric> = ids.filterValues { it in saved }.keys.toSet()
    fun encode(metrics: Set<ObdMetric>): Set<String> = metrics.mapNotNull { ids[it] }.toSet()
}

/** One application-owned worker preserves toggle order, including requests made before loading. */
internal class DashboardFavoritesStore(
    private val storage: DashboardFavoritesStorage,
    scope: CoroutineScope,
) {
    private val changes = Channel<ObdMetric>(Channel.UNLIMITED)
    private val pendingLock = Any()
    private var pendingChanges = 0
    private val mutableFavorites = MutableStateFlow<Set<ObdMetric>>(emptySet())
    val favorites: StateFlow<Set<ObdMetric>> = mutableFavorites.asStateFlow()
    private val mutableLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = mutableLoading.asStateFlow()
    private val mutableSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = mutableSaving.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    init {
        scope.launch {
            var confirmed = load()
            for (metric in changes) {
                try {
                    if (confirmed == null) confirmed = load()
                    val previous = confirmed ?: continue
                    val updated = if (metric in previous) previous - metric else previous + metric
                    if (storage.write(FavoriteMetricIds.encode(updated))) {
                        confirmed = updated
                        mutableFavorites.value = updated
                        mutableError.value = null
                    } else {
                        mutableError.value = SAVE_ERROR
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableError.value = SAVE_ERROR
                } finally {
                    synchronized(pendingLock) {
                        pendingChanges--
                        mutableSaving.value = pendingChanges > 0
                    }
                }
            }
        }.invokeOnCompletion {
            changes.close()
            synchronized(pendingLock) {
                pendingChanges = 0
                mutableSaving.value = false
                mutableLoading.value = false
            }
        }
    }

    fun toggleFavorite(metric: ObdMetric) {
        if (!FavoriteMetricIds.accepts(metric)) return
        synchronized(pendingLock) {
            pendingChanges++
            mutableSaving.value = true
            if (changes.trySend(metric).isFailure) {
                pendingChanges--
                mutableSaving.value = pendingChanges > 0
                mutableError.value = SAVE_ERROR
            }
        }
    }

    private suspend fun load(): Set<ObdMetric>? {
        mutableLoading.value = true
        return try {
            FavoriteMetricIds.decode(storage.read()).also {
                mutableFavorites.value = it
                mutableError.value = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableError.value = "Saved favorites could not be loaded. Try selecting a favorite again."
            null
        } finally {
            mutableLoading.value = false
        }
    }

    private companion object {
        const val SAVE_ERROR = "Favorites could not be saved. Your last confirmed selection is shown. Try again."
    }
}

