package com.gonibix.rx8.sync

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeSettings()
    private val runtime = MutableStateFlow(UploadRuntimeStatus())
    private var retries = 0
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun model(retry: suspend () -> Unit = { retries++ }) = SyncSettingsViewModel(repository, runtime, retry)

    @Test fun initialLoadingDoesNotClaimUnconfiguredOrAttemptUploads() = runTest(dispatcher) {
        repository.loadGate = CompletableDeferred()
        val model = model()
        runCurrent()
        assertTrue(model.uiState.value.isLoading)
        assertNull(model.uiState.value.settings)
        model.save(URL, true)
        model.retry()
        assertEquals(0, retries)
        assertEquals(0, repository.saves.size)
        repository.loadGate!!.complete(Unit)
        runCurrent()
        assertEquals(UploadSettings(), model.uiState.value.settings)
        assertFalse(model.uiState.value.isLoading)
    }

    @Test fun readFailureRemainsUnknownAndExplicitReloadRecovers() = runTest(dispatcher) {
        repository.loadFailure = IOException("private disk detail")
        val model = model()
        runCurrent()
        assertNull(model.uiState.value.settings)
        assertTrue(model.uiState.value.error!!.contains("could not be loaded"))
        assertFalse(model.uiState.value.error!!.contains("private"))
        repository.loadFailure = null
        repository.settings.value = UploadSettings(URL, false)
        model.load()
        runCurrent()
        assertEquals(URL, model.uiState.value.settings!!.baseUrl)
        assertNull(model.uiState.value.error)
    }

    @Test fun savingWaitsForPersistenceAndRejectsOverlappingActions() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        repository.saveGate = CompletableDeferred()
        model.save(URL, true)
        model.save("https://other.example", true)
        model.retry()
        runCurrent()
        assertTrue(model.uiState.value.isSaving)
        assertFalse(model.uiState.value.settings!!.enabled)
        assertNull(model.uiState.value.message)
        assertEquals(listOf(URL to true), repository.saves)
        assertEquals(0, retries)
        repository.saveGate!!.complete(Unit)
        runCurrent()
        assertTrue(model.uiState.value.settings!!.enabled)
        assertFalse(model.uiState.value.isSaving)
        assertTrue(model.uiState.value.message!!.contains("requires confirmation"))
    }

    @Test fun invalidAddressNeverEnablesUploadsAndRawErrorsStayPrivate() = runTest(dispatcher) {
        repository.saveFailure = IllegalArgumentException("secret URL token")
        val model = model()
        runCurrent()
        model.save("http://invalid.example/?secret", true)
        runCurrent()
        assertFalse(model.uiState.value.settings!!.enabled)
        assertNull(model.uiState.value.message)
        assertTrue(model.uiState.value.error!!.contains("HTTPS"))
        assertFalse(model.uiState.value.error!!.contains("secret"))
    }

    @Test fun failedWritePreservesConfirmedSettingsAndDoesNotReportSuccess() = runTest(dispatcher) {
        repository.settings.value = UploadSettings(URL, false, URL)
        val model = model()
        runCurrent()
        repository.saveFailure = IOException("disk full private detail")
        model.save(URL, true)
        runCurrent()
        assertEquals(UploadSettings(URL, false, URL), model.uiState.value.settings)
        assertNull(model.uiState.value.message)
        assertTrue(model.uiState.value.error!!.contains("could not be saved"))
    }

    @Test fun externalEndpointBindingRemainsVisibleAndPausePreservesIdentity() = runTest(dispatcher) {
        repository.settings.value = UploadSettings(URL, true)
        val model = model()
        runCurrent()
        repository.settings.value = UploadSettings(URL, true, URL)
        runtime.value = UploadRuntimeStatus(isRunning = true)
        runCurrent()
        assertEquals(URL, model.uiState.value.settings!!.boundBaseUrl)
        model.save(URL, false)
        runCurrent()
        assertEquals(UploadSettings(URL, false, URL), model.uiState.value.settings)
        assertTrue(model.uiState.value.message!!.contains("paused"))
    }

    @Test fun endpointChangeRejectionExplainsSameServerRecovery() = runTest(dispatcher) {
        repository.settings.value = UploadSettings(URL, false, URL)
        repository.saveFailure = IllegalArgumentException("bound destination")
        val model = model()
        runCurrent()
        model.save("https://different.example", true)
        runCurrent()
        assertTrue(model.uiState.value.error!!.contains("same address"))
        assertEquals(URL, model.uiState.value.settings!!.baseUrl)
    }

    @Test fun retryRequiresEnabledIdleRuntimeAndDoesNotClaimDelivery() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.retry()
        runCurrent()
        assertEquals(0, retries)
        repository.settings.value = UploadSettings(URL, true, URL)
        runtime.value = UploadRuntimeStatus(isRunning = true)
        runCurrent()
        model.retry()
        runCurrent()
        assertEquals(0, retries)
        runtime.value = UploadRuntimeStatus()
        runCurrent()
        model.retry()
        runCurrent()
        assertEquals(1, retries)
        assertTrue(model.uiState.value.message!!.contains("needs server confirmation"))
    }

    @Test fun retryFailureIsVisibleWithoutChangingSavedEndpoint() = runTest(dispatcher) {
        repository.settings.value = UploadSettings(URL, true, URL)
        val model = model { throw IOException("private remote detail") }
        runCurrent()
        model.retry()
        runCurrent()
        assertEquals(UploadSettings(URL, true, URL), model.uiState.value.settings)
        assertFalse(model.uiState.value.isRetrying)
        assertNull(model.uiState.value.message)
        assertTrue(model.uiState.value.error!!.contains("saved trips remain"))
    }

    private class FakeSettings : UploadSettingsRepository {
        override val settings = MutableStateFlow(UploadSettings())
        var loadGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var loadFailure: Exception? = null
        var saveFailure: Exception? = null
        val saves = mutableListOf<Pair<String?, Boolean>>()
        override suspend fun load() { loadGate?.await(); loadFailure?.let { throw it } }
        override suspend fun save(baseUrl: String?, enabled: Boolean) {
            saves += baseUrl to enabled
            saveGate?.await()
            saveFailure?.let { throw it }
            settings.value = settings.value.copy(baseUrl = baseUrl, enabled = enabled)
        }
    }

    private companion object { const val URL = "https://api.example.test/rx8" }
}
