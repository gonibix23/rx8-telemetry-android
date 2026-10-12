package com.gonibix.rx8.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncSettingsUiState(
    val settings: UploadSettings? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isRetrying: Boolean = false,
    val runtime: UploadRuntimeStatus = UploadRuntimeStatus(),
    val error: String? = null,
    val message: String? = null,
)

/** Settings failures never mutate the trip recorder or hide local history. */
class SyncSettingsViewModel(
    private val repository: UploadSettingsRepository,
    runtimeStatus: StateFlow<UploadRuntimeStatus>,
    private val retryUploads: suspend () -> Unit,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SyncSettingsUiState())
    val uiState = mutableState.asStateFlow()
    private var operation: Job? = null
    private var loaded = false

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                // The repository's initial value is not evidence that disk loading succeeded.
                if (loaded) mutableState.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            runtimeStatus.collect { status -> mutableState.update { it.copy(runtime = status) } }
        }
        load()
    }

    fun load() {
        if (operation?.isActive == true) return
        mutableState.update { it.copy(isLoading = true, error = null, message = null) }
        operation = viewModelScope.launch {
            try {
                repository.load()
                loaded = true
                mutableState.update { it.copy(settings = repository.settings.value) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "Upload settings could not be loaded. Local recording and saved trips remain available.") }
            } finally {
                mutableState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun save(rawUrl: String?, enabled: Boolean) {
        if (!loaded || operation?.isActive == true) return
        mutableState.update { it.copy(isSaving = true, error = null, message = null) }
        operation = viewModelScope.launch {
            try {
                repository.save(rawUrl, enabled)
                mutableState.update {
                    it.copy(
                        settings = repository.settings.value,
                        message = if (repository.settings.value.enabled) {
                            "Uploads enabled. Delivery requires confirmation from the server."
                        } else "Uploads paused. Recording and saved trips stay available offline.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                mutableState.update { it.copy(error = if (repository.settings.value.boundBaseUrl != null) {
                    "This device has already used a server. Resume uploads to that same address."
                } else "Enter an HTTPS server address without a username, password, query, or fragment.") }
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "Upload settings could not be saved. Check the current status and try again; local recording is unaffected.") }
            } finally {
                mutableState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun retry() {
        if (!loaded || operation?.isActive == true || repository.settings.value.enabled != true || mutableState.value.runtime.isRunning) return
        mutableState.update { it.copy(isRetrying = true, error = null, message = null) }
        operation = viewModelScope.launch {
            try {
                retryUploads()
                mutableState.update { it.copy(message = "Upload retry requested. Delivery still needs server confirmation.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "Uploads could not be retried. Your saved trips remain on this device.") }
            } finally {
                mutableState.update { it.copy(isRetrying = false) }
            }
        }
    }
}
