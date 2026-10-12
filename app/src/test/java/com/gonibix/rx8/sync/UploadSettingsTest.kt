package com.gonibix.rx8.sync

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadSettingsTest {
    @Test fun canonicalHttpsPreservesBasePathButRejectsCredentialsAndAmbiguousUrls() {
        assertEquals("https://example.com/api", UploadEndpoint.normalize(" HTTPS://EXAMPLE.com:443/api/ "))
        assertEquals("https://example.com:8443/base", UploadEndpoint.normalize("https://example.com:8443/base"))
        assertNull(UploadEndpoint.normalize(" "))
        listOf("http://example.com", "mongodb+srv://example.com", "https://user:pass@example.com",
            "https://example.com?key=value", "https://example.com/#x", "https://example.com/a/../b",
            "https://example.com/a%2fb", "https://example.com:0", "https://example.com/a\\b",
            "https://example.com/a\nb").forEach { assertTrue(it, runCatching { UploadEndpoint.normalize(it) }.isFailure) }
    }

    @Test fun defaultIsDisabledAndSavingDoesNotBindUntilAnUploadIsReady() = runTest {
        val storage = MemorySettings()
        val repository = PersistentUploadSettings(storage, StandardTestDispatcher(testScheduler))
        repository.load()
        assertEquals(UploadSettings(), repository.settings.value)
        assertNull(repository.bindForUpload())
        repository.save("https://first.example/api", true)
        assertNull(repository.settings.value.boundBaseUrl)
        repository.save("https://second.example/api", true)
        val bound = repository.bindForUpload()!!
        assertEquals(bound.baseUrl, bound.boundBaseUrl)
        assertEquals(bound, storage.value)
    }

    @Test fun boundIdentitySurvivesPauseClearReopenAndRefusesAnotherServer() = runTest {
        val storage = MemorySettings()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PersistentUploadSettings(storage, dispatcher)
        repository.save("https://example.com/base/", true)
        repository.bindForUpload()
        repository.save(null, false)
        val reopened = PersistentUploadSettings(storage, dispatcher)
        reopened.load()
        assertEquals("https://example.com/base", reopened.settings.value.boundBaseUrl)
        assertFalse(reopened.settings.value.enabled)
        assertTrue(runCatching { reopened.save("https://other.example", true) }.exceptionOrNull() is IllegalArgumentException)
        reopened.save("https://EXAMPLE.COM:443/base/", true)
        assertEquals("https://example.com/base", reopened.bindForUpload()!!.baseUrl)
    }

    @Test fun failedPersistenceNeverPublishesOrSchedulesSuccess() = runTest {
        val storage = MemorySettings().apply { fail = true }
        var schedules = 0
        val repository = PersistentUploadSettings(storage, StandardTestDispatcher(testScheduler)) { schedules++ }
        assertTrue(runCatching { repository.save("https://example.com", true) }.exceptionOrNull() is IOException)
        assertEquals(UploadSettings(), repository.settings.value)
        assertEquals(0, schedules)
    }

    @Test fun cancellationAfterCommitCannotLosePublicationOrSchedulingAndWritesRemainOrdered() = runTest {
        val storage = MemorySettings()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduled = mutableListOf<UploadSettings>()
        val repository = PersistentUploadSettings(storage, StandardTestDispatcher(testScheduler)) {
            scheduled += it
            if (it.enabled) { entered.complete(Unit); release.await() }
        }
        val save = launch { repository.save("https://example.com", true) }
        entered.await()
        save.cancel()
        val pause = async { repository.save(null, false) }
        runCurrent()
        assertFalse(pause.isCompleted)
        release.complete(Unit)
        pause.await()
        assertEquals(listOf(true, false), scheduled.map { it.enabled })
        assertEquals(storage.value, repository.settings.value)
        assertFalse(repository.settings.value.enabled)
    }

    private class MemorySettings : UploadSettingsStorage {
        var value = UploadSettings()
        var fail = false
        override fun read() = value
        override fun write(settings: UploadSettings): Boolean {
            if (fail) return false
            value = settings
            return true
        }
    }
}
