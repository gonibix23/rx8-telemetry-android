package com.gonibix.rx8.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.gonibix.rx8.obd.ObdMetric
import kotlinx.coroutines.flow.StateFlow

/** Favorites are shared app preferences; clearing this observer does not stop pending storage work. */
class DashboardPreferencesViewModel internal constructor(
    application: Application,
    private val store: DashboardFavoritesStore,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, DashboardFavoritesProvider.open(application))

    val favorites: StateFlow<Set<ObdMetric>> = store.favorites
    val isLoading: StateFlow<Boolean> = store.isLoading
    val isSaving: StateFlow<Boolean> = store.isSaving
    val error: StateFlow<String?> = store.error

    fun toggleFavorite(metric: ObdMetric) = store.toggleFavorite(metric)
}
