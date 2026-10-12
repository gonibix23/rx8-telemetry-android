package com.gonibix.rx8.sync

import android.annotation.SuppressLint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Inspect scheduling without initializing a worker, changing global WorkManager, or using a server. */
@RunWith(AndroidJUnit4::class)
@SuppressLint("RestrictedApi") // WorkSpec exposes the persisted request contract under test.
class UploadSchedulerTest {
    @Test fun terminalCommitRequestWaitsForConnectivityWithoutForegroundOrSensitiveInput() {
        val request = UploadScheduler.immediateRequest()
        assertOfflineSafeRequest(request)
        assertFalse(request.workSpec.isPeriodic)
        assertEquals(0L, request.workSpec.initialDelay)
    }

    @Test fun catchupRequestIsHourlyAndUsesTheSameBoundedNetworkPolicy() {
        val request = UploadScheduler.periodicRequest()
        assertOfflineSafeRequest(request)
        assertTrue(request.workSpec.isPeriodic)
        assertEquals(TimeUnit.HOURS.toMillis(1), request.workSpec.intervalDuration)
    }

    private fun assertOfflineSafeRequest(request: WorkRequest) {
        val spec = request.workSpec
        assertEquals(TripUploadWorker::class.java.name, spec.workerClassName)
        assertTrue(request.tags.contains(UploadScheduler.TAG))
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.LINEAR, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(30), spec.backoffDelayDuration)
        assertFalse(spec.expedited)
        // The worker reads current private settings and Room state, never stale payloads or URLs.
        assertTrue(spec.input.keyValueMap.isEmpty())
    }
}
