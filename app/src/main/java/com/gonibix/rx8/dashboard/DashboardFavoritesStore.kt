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
    suspend fun readCards(): List<DashboardCardConfig>? = null
    suspend fun writeCards(cards: List<DashboardCardConfig>): Boolean = write(FavoriteMetricIds.encode(cards.map { it.metric }.toSet()))
    suspend fun readColumns(): DashboardColumns = DashboardColumns.ONE
    suspend fun writeColumns(columns: DashboardColumns): Boolean = false
}

internal object FavoriteMetricIds {
    // Existing IDs stay byte-for-byte identical; new fields append stable enum-name IDs.
    private val ids = ObdMetric.entries
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
    private sealed interface Change {
        data class Toggle(val metric: ObdMetric) : Change
        data class Update(val card: DashboardCardConfig) : Change
        data class Move(val metric: ObdMetric, val delta: Int) : Change
        data class Columns(val columns: DashboardColumns) : Change
    }
    private val changes = Channel<Change>(Channel.UNLIMITED)
    private val mutableCards = MutableStateFlow<List<DashboardCardConfig>>(emptyList())
    val cards: StateFlow<List<DashboardCardConfig>> = mutableCards.asStateFlow()
    private val mutableColumns = MutableStateFlow(DashboardColumns.ONE)
    val columns: StateFlow<DashboardColumns> = mutableColumns.asStateFlow()
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
            for (change in changes) {
                try {
                    if (confirmed == null) confirmed = load()
                    val previous = confirmed ?: continue
                    if (change is Change.Columns) {
                        if (storage.writeColumns(change.columns)) {
                            mutableColumns.value = change.columns
                            mutableError.value = null
                        } else mutableError.value = SAVE_ERROR
                        continue
                    }
                    val updated = when (change) {
                        is Change.Toggle -> if (previous.any { it.metric == change.metric }) previous.filterNot { it.metric == change.metric }
                            else previous + defaultCard(change.metric)
                        is Change.Update -> {
                            if (change.card.showInOverlay && previous.count { it.showInOverlay && it.metric != change.card.metric } >= DashboardLayoutCodec.MAX_OVERLAY_METRICS) {
                                mutableError.value = "Choose at most six floating sensors. Remove one before adding another."
                                continue
                            }
                            previous.map { if (it.metric == change.card.metric) change.card else it }
                        }
                        is Change.Move -> previous.toMutableList().apply {
                            val from = indexOfFirst { it.metric == change.metric }
                            if (from >= 0) add((from + change.delta.coerceIn(-1, 1)).coerceIn(0, lastIndex), removeAt(from))
                        }.toList()
                        is Change.Columns -> error("Column changes are handled separately")
                    }
                    if (storage.writeCards(updated)) {
                        confirmed = updated
                        publish(updated)
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

    fun toggleFavorite(metric: ObdMetric) = enqueue(Change.Toggle(metric))
    fun updateCard(card: DashboardCardConfig) = enqueue(Change.Update(card))
    fun moveCard(metric: ObdMetric, delta: Int) = enqueue(Change.Move(metric, delta))
    fun setColumns(columns: DashboardColumns) = enqueue(Change.Columns(columns))

    private fun enqueue(change: Change) {
        synchronized(pendingLock) {
            pendingChanges++
            mutableSaving.value = true
            if (changes.trySend(change).isFailure) {
                pendingChanges--
                mutableSaving.value = pendingChanges > 0
                mutableError.value = SAVE_ERROR
            }
        }
    }

    private fun publish(cards: List<DashboardCardConfig>) {
        mutableCards.value = cards.toList()
        mutableFavorites.value = cards.map { it.metric }.toSet()
    }

    private suspend fun load(): List<DashboardCardConfig>? {
        mutableLoading.value = true
        return try {
            val columns = storage.readColumns()
            (storage.readCards() ?: FavoriteMetricIds.decode(storage.read()).map(::defaultCard)).also {
                publish(it)
                mutableColumns.value = columns
                mutableError.value = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableError.value = "Dashboard preferences could not be loaded. Try editing the layout again."
            null
        } finally {
            mutableLoading.value = false
        }
    }

    private companion object {
        const val SAVE_ERROR = "Dashboard preferences could not be saved. Your last confirmed layout is shown. Try again."
    }
}

