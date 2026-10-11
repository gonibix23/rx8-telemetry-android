package com.gonibix.rx8.connection

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.MainActivity
import com.gonibix.rx8.bluetooth.AdapterConnection
import com.gonibix.rx8.bluetooth.AdapterDevice
import com.gonibix.rx8.bluetooth.BluetoothAvailability
import com.gonibix.rx8.bluetooth.BluetoothGateway
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdSession
import com.gonibix.rx8.obd.ObdSessionFactory
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.SystemObdClock
import com.gonibix.rx8.trips.TripRecorder
import com.gonibix.rx8.trips.TripRepository
import com.gonibix.rx8.trips.TripSession
import com.gonibix.rx8.trips.TripSample
import com.gonibix.rx8.trips.TripStatus
import com.gonibix.rx8.vehicle.VehicleProfile
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android service with an in-process synthetic transport, never a car. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class BluetoothSessionServiceTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val serviceIntent get() = Intent(context, BluetoothSessionService::class.java)
    private val device = AdapterDevice("02:00:00:00:00:05", "Synthetic lifecycle adapter")
    private val gateway = LifecycleGateway()
    private val bindings = mutableListOf<Binding>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val originalFactory = BluetoothSessionService.dependenciesFactory
    private var failAcquisition = false
    private val failSignal = CompletableDeferred<Unit>()
    @Volatile private var acquisitionCancelled = false
    private val tripRepository = LifecycleTripRepository()
    private val tripScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tripRecorder = TripRecorder(tripRepository, tripScope)
    private val rememberedAdapter = MemoryLastAdapterStore()
    private var openingPolicy = AutomaticConnectionPolicy()
    private var persistedVehicle: VehicleProfile? = null
    private var notificationRequestedBefore = false
    private val tripVehicle = VehicleProfile("00000000-0000-4000-8000-000000000007", "Synthetic trip vehicle", null, null, null, 0)

    @Before
    fun setUp() {
        persistedVehicle = tripVehicle
        notificationRequestedBefore = context.getSharedPreferences("session_ui", Context.MODE_PRIVATE).getBoolean("notifications_requested", false)
        // Adopt only Bluetooth permissions temporarily; notification permission remains untouched.
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
        )
        context.stopService(serviceIntent)
        instrumentation.waitForIdleSync()
        BluetoothSessionService.dependenciesFactory = {
            BluetoothServiceDependencies(gateway, ObdSessionFactory {
                object : ObdSession {
                    override suspend fun run(onSnapshot: (ObdSessionSnapshot) -> Unit) {
                        try {
                            onSnapshot(ObdSessionSnapshot(phase = ObdPhase.LIVE))
                            if (failAcquisition) {
                                failSignal.await()
                                throw IOException("Synthetic acquisition failure")
                            }
                            var elapsed = 0L
                            while (true) {
                                delay(100L)
                                elapsed += 100L
                                onSnapshot(ObdSessionSnapshot(phase = ObdPhase.LIVE, elapsedMillis = elapsed, readings = listOf(
                                    ObdReading(ObdMetric.SPEED, 36.0, ObdReadingStatus.VALID, "7E8",
                                        SystemObdClock.epochMillis(), elapsed, SystemObdClock.monotonicMillis()),
                                )))
                            }
                        } finally {
                            acquisitionCancelled = true
                        }
                    }
                }
            }, recorder = tripRecorder, selectedVehicleLoader = { persistedVehicle },
                lastAdapterStore = rememberedAdapter, automaticPolicy = openingPolicy)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        try {
            bindings.filter { it.bound }.forEach { binding ->
                main { binding.binder.controller.disconnect() }
            }
            scenario?.close()
            scenario = null
            bindings.filter { it.bound }.forEach { it.unbind() }
            context.stopService(serviceIntent)
            instrumentation.waitForIdleSync()
        } finally {
            context.getSharedPreferences("session_ui", Context.MODE_PRIVATE).edit().putBoolean("notifications_requested", notificationRequestedBefore).commit()
            BluetoothSessionService.dependenciesFactory = originalFactory
            tripScope.cancel()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun automaticOpeningWaitsForBluetoothAndAttemptsAnUnlistedRememberedAdapterOnlyOnce() {
        gateway.ready = BluetoothAvailability.OFF
        gateway.listed = emptyList()
        relaunchWithRememberedAdapter()
        val binding = bind()
        main { assertFalse(binding.binder.controller.uiState.value.automaticConnectionReady) }
        assertEquals(0, gateway.attempts)
        main {
            gateway.ready = BluetoothAvailability.READY
            binding.binder.controller.refresh()
        }
        awaitUi("The ready remembered adapter should connect and read automatically") {
            binding.binder.controller.uiState.value.obd.phase == ObdPhase.LIVE
        }
        assertEquals(1, gateway.attempts)
        assertEquals("Last connected adapter", binding.binder.controller.uiState.value.selectedDevice!!.name)
        assertFalse(binding.binder.controller.uiState.value.selectedDevice!!.isPaired)
        awaitMain { tripRecorder.state.value.savedSamples > 0 }
        main { binding.binder.disconnect() }
        awaitMain { !binding.binder.hasForegroundSession }
        scenario!!.recreate()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        main {
            binding.binder.tryAutomaticConnection()
            binding.binder.controller.refresh()
            binding.binder.tryAutomaticConnection()
        }
        assertEquals(1, gateway.attempts)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, binding.binder.controller.uiState.value.phase)
    }

    @Test
    fun failedAutomaticOpeningDoesNotRetryOnResumeAndDoesNotOverwriteLastSuccess() {
        gateway.failConnection = true
        relaunchWithRememberedAdapter()
        val binding = bind()
        awaitUi("A failed automatic attempt should stay available for manual retry") {
            gateway.attempts == 1 && binding.binder.controller.uiState.value.message?.contains("Connection failed") == true
        }
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        main { binding.binder.tryAutomaticConnection() }
        assertEquals(1, gateway.attempts)
        assertEquals(device.address, rememberedAdapter.address)
        assertTrue(gateway.connections.isEmpty())
        assertNull(tripRecorder.state.value.session)
    }

    @Test
    fun socketOpeningAfterBackgroundAutomaticallyStartsReadingsAndTrip() {
        val gate = CompletableDeferred<Unit>()
        gateway.connectionGate = gate
        val binding = bind()
        main {
            binding.binder.setAppForeground(true)
            binding.binder.controller.setForeground(true)
            binding.binder.controller.selectDevice(device)
            binding.binder.connect()
        }
        awaitMain { binding.binder.controller.uiState.value.phase == BluetoothConnectionPhase.CONNECTING }
        scenario!!.moveToState(Lifecycle.State.CREATED)
        gate.complete(Unit)
        awaitMain { tripRecorder.state.value.savedSamples >= 2 }
        assertEquals(ObdPhase.LIVE, binding.binder.controller.uiState.value.obd.phase)
        assertFalse(binding.binder.controller.uiState.value.isForeground)
        assertTrue(binding.binder.hasForegroundSession)
        assertTrue(binding.binder.hasAcquisitionWakeLock)
        assertEquals(tripVehicle.id, tripRecorder.state.value.session!!.vehicleId)
        assertEquals(device.address, rememberedAdapter.address)
    }

    @Test
    fun missingProfileCanBeSelectedOnceAndStopRecordingDoesNotAutomaticallyRestart() {
        persistedVehicle = null
        val binding = bind()
        connect(binding)
        awaitMain { binding.binder.controller.uiState.value.obd.phase == ObdPhase.LIVE }
        assertTrue(binding.binder.controller.uiState.value.isAwaitingVehicle)
        assertNull(tripRecorder.state.value.session)
        main {
            persistedVehicle = tripVehicle
            binding.binder.refreshSelectedVehicle()
        }
        awaitMain { tripRecorder.state.value.savedSamples >= 2 }
        assertFalse(binding.binder.controller.uiState.value.isAwaitingVehicle)
        main { binding.binder.stopTrip() }
        awaitMain { tripRecorder.state.value.session == null && !tripRecorder.state.value.isStopping }
        val count = tripRepository.saved.size
        val elapsed = binding.binder.controller.uiState.value.obd.elapsedMillis
        main { binding.binder.refreshSelectedVehicle() }
        awaitMain { binding.binder.controller.uiState.value.obd.elapsedMillis > elapsed + 200 }
        assertEquals(count, tripRepository.saved.size)
        assertNull(tripRecorder.state.value.session)
        assertEquals(1, gateway.connections.size)
    }

    private fun relaunchWithRememberedAdapter() {
        scenario!!.close()
        scenario = null
        context.stopService(serviceIntent)
        instrumentation.waitForIdleSync()
        rememberedAdapter.address = device.address
        openingPolicy = AutomaticConnectionPolicy() // Explicitly model a new process opening opportunity.
        context.getSharedPreferences("session_ui", Context.MODE_PRIVATE).edit().putBoolean("notifications_requested", true).commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @Test
    fun delayedPreferenceLoadFailureCannotClearActiveMissingVehicleGuidance() {
        persistedVehicle = null
        val load = CompletableDeferred<Unit>()
        rememberedAdapter.loadGate = load
        relaunchWithRememberedAdapter()
        val binding = bind()
        connect(binding)
        awaitMain { binding.binder.controller.uiState.value.obd.phase == ObdPhase.LIVE }
        assertTrue(binding.binder.controller.uiState.value.isAwaitingVehicle)
        val previousLoads = rememberedAdapter.completedLoads
        load.completeExceptionally(IOException("Synthetic preference read failure"))
        awaitMain { rememberedAdapter.completedLoads > previousLoads }
        assertTrue(binding.binder.controller.uiState.value.isAwaitingVehicle)
        assertTrue(binding.binder.controller.uiState.value.automaticMessage!!.contains("Select a vehicle"))
        assertEquals(1, gateway.attempts)
    }

    @Test
    fun tripRecordingContinuesWithoutAnActivityAndNotificationStopCommits() {
        val binding = bind()
        connect(binding)
        awaitMain { binding.binder.controller.uiState.value.obd.phase == ObdPhase.LIVE }
        awaitMain { tripRecorder.state.value.savedSamples >= 2 }
        val stop = stopAction(binding)
        scenario!!.close()
        scenario = null
        binding.unbind()
        val previous = tripRecorder.state.value.savedSamples
        awaitMain { tripRecorder.state.value.savedSamples > previous + 2 }
        stop.send()
        awaitMain { tripRepository.session?.status == TripStatus.COMPLETED && tripRecorder.state.value.session == null }
        assertEquals(tripVehicle.id, tripRepository.session!!.vehicleId)
        assertTrue(tripRepository.saved.size >= 5)
        assertTrue(gateway.connections.single().closed)
        assertEquals(tripRepository.saved.indices.map { it.toLong() }, tripRepository.saved.map { it.sequence })
    }

    @Test
    fun repeatedNotificationStopKeepsAnUnboundServiceUntilTripFinalizationCommits() {
        val finishGate = CompletableDeferred<Unit>()
        tripRepository.finishGate = finishGate
        try {
            val binding = bind()
            connect(binding)
            awaitMain { binding.binder.controller.uiState.value.obd.phase == ObdPhase.LIVE }
            awaitMain { tripRecorder.state.value.savedSamples >= 2 }
            val stop = stopAction(binding)
            scenario!!.close()
            scenario = null
            binding.unbind()

            stop.send()
            awaitMain {
                tripRepository.finishEntered.isCompleted && tripRecorder.state.value.isStopping
            }
            main {
                assertTrue(binding.binder.hasForegroundSession)
                assertFalse(binding.binder.hasAcquisitionWakeLock)
            }
            assertTrue(gateway.connections.single().closed)
            val savedCount = tripRepository.saved.size

            // The first Stop clears the connection token. A repeated intent must not stop
            // started ownership while the ordered writer is still committing its final marker.
            stop.send()
            val observationDeadline = SystemClock.elapsedRealtime() + 500L
            do {
                main {
                    assertTrue("Saving must retain foreground ownership without bound clients", binding.binder.hasForegroundSession)
                    assertNotNull(binding.binder.sessionNotification)
                    assertEquals(TripStatus.RECORDING, tripRepository.session!!.status)
                    assertTrue(tripRecorder.state.value.isStopping)
                }
                Thread.sleep(20L)
            } while (SystemClock.elapsedRealtime() < observationDeadline)

            finishGate.complete(Unit)
            awaitMain {
                tripRepository.session?.status == TripStatus.COMPLETED &&
                    tripRecorder.state.value.session == null && !binding.binder.hasForegroundSession
            }
            assertEquals(savedCount, tripRepository.saved.size)
            assertEquals(tripRepository.saved.indices.map { it.toLong() }, tripRepository.saved.map { it.sequence })
            main { assertNull(binding.binder.sessionNotification) }
        } finally {
            // Also release the fake writer when an assertion fails, before normal test cleanup.
            finishGate.complete(Unit)
        }
    }

    @Test
    fun stoppingOnlyTheTripLeavesLiveAcquisitionRunning() {
        val binding = bind()
        connect(binding)
        awaitMain { binding.binder.controller.uiState.value.obd.phase == ObdPhase.LIVE }
        awaitMain { tripRecorder.state.value.savedSamples > 0 }
        main { binding.binder.stopTrip() }
        awaitMain { tripRepository.session?.status == TripStatus.COMPLETED && tripRecorder.state.value.session == null }
        val count = tripRepository.saved.size
        val elapsed = binding.binder.controller.uiState.value.obd.elapsedMillis
        awaitMain { binding.binder.controller.uiState.value.obd.elapsedMillis > elapsed + 200 }
        assertEquals(count, tripRepository.saved.size)
        assertTrue(binding.binder.hasForegroundSession)
        assertTrue(binding.binder.hasAcquisitionWakeLock)
        assertFalse(gateway.connections.single().closed)
    }

    @Test
    fun bluetoothLossMarksTheTripInterruptedAndReleasesTheService() {
        val binding = bind()
        connect(binding)
        awaitMain { binding.binder.controller.uiState.value.obd.phase == ObdPhase.LIVE }
        awaitMain { tripRecorder.state.value.savedSamples > 0 }
        main { binding.binder.controller.onDeviceDisconnected(device.address) }
        awaitMain { tripRepository.session?.status == TripStatus.INTERRUPTED && !binding.binder.hasForegroundSession }
        assertTrue(tripRepository.saved.isNotEmpty())
        assertFalse(binding.binder.hasAcquisitionWakeLock)
        assertNotNull(tripRepository.session!!.endReason)
    }

    @Test
    fun dashboardKeepsTheSameSessionAndOnlyVisibleLiveScreensStayAwake() {
        val binding = bind()
        connect(binding)
        awaitUi("Adapter should stay awake during acquisition") { binding.binder.hasAcquisitionWakeLock && screenRequestsAwake() }

        compose.onNodeWithTag("dashboard_tab").performClick()
        compose.onNodeWithTag("dashboard_tab").assertIsSelected()
        awaitUi("Dashboard should retain screen awake after Adapter disposal") {
            screenRequestsAwake() && !binding.binder.controller.uiState.value.isForeground
        }
        val elapsed = binding.binder.controller.uiState.value.obd.elapsedMillis
        awaitMain { binding.binder.controller.uiState.value.obd.elapsedMillis > elapsed }
        scenario!!.recreate()
        compose.onNodeWithTag("dashboard_tab").assertIsSelected()
        awaitUi("Recreated Dashboard should stay awake") { screenRequestsAwake() }
        assertEquals(1, gateway.connections.size)

        compose.onNodeWithTag("vehicles_tab").performClick()
        awaitUi("Vehicles should release screen awake") { !screenRequestsAwake() }
        assertFalse(gateway.connections.single().closed)
        compose.onNodeWithTag("dashboard_tab").performClick()
        awaitUi("Returning to Dashboard should stay awake") { screenRequestsAwake() }
        scenario!!.moveToState(Lifecycle.State.CREATED)
        awaitUi("Background Dashboard should release screen awake") { !screenRequestsAwake() }
        assertFalse(gateway.connections.single().closed)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        awaitUi("Resumed Dashboard should stay awake") { screenRequestsAwake() }
        main { binding.binder.controller.disconnect() }
        awaitUi("Stopping acquisition should release both wake requests") {
            !screenRequestsAwake() && !binding.binder.hasAcquisitionWakeLock
        }
        assertTrue(gateway.connections.single().closed)
    }

    @Test
    fun acquisitionSurvivesActivityRecreationBackgroundAndAllClientsUnbinding() {
        val first = bind()
        connect(first)
        awaitMain { first.binder.hasAcquisitionWakeLock }
        val controller = first.binder.controller
        val initialElapsed = controller.uiState.value.obd.elapsedMillis

        scenario!!.recreate()
        assertSame(controller, first.binder.controller)
        assertEquals(1, gateway.connections.size)
        scenario!!.moveToState(Lifecycle.State.CREATED)
        first.unbind()
        awaitMain { controller.uiState.value.obd.elapsedMillis > initialElapsed + 200L }
        assertFalse(gateway.connections.single().closed)
        scenario!!.close()
        scenario = null

        // The started service survives with zero bound clients and without an Activity.
        val rebound = bind()
        assertSame(controller, rebound.binder.controller)
        main {
            assertTrue(rebound.binder.hasForegroundSession)
            assertTrue(rebound.binder.hasAcquisitionWakeLock)
            assertFalse(controller.uiState.value.isForeground)
        }
        stopAction(rebound).send()
        awaitMain { !rebound.binder.hasForegroundSession && !rebound.binder.hasAcquisitionWakeLock }
        assertTrue(gateway.connections.single().closed)
        assertTrue(acquisitionCancelled)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, controller.uiState.value.phase)
        assertEquals(ObdPhase.STOPPED, controller.uiState.value.obd.phase)
        main { assertNull(rebound.binder.sessionNotification) }
    }

    @Test
    fun obsoleteNotificationStopCannotCloseAReplacementConnection() {
        val binding = bind()
        connect(binding)
        val oldStop = stopAction(binding)
        main { binding.binder.controller.disconnect() }
        awaitMain { !binding.binder.hasForegroundSession }
        connect(binding)
        assertEquals(2, gateway.connections.size)
        assertTrue(gateway.connections.first().closed)
        oldStop.send()
        instrumentation.waitForIdleSync()
        main {
            assertEquals(BluetoothConnectionPhase.CONNECTED, binding.binder.controller.uiState.value.phase)
            assertTrue(binding.binder.hasForegroundSession)
        }
        assertFalse(gateway.connections.last().closed)
        stopAction(binding).send()
        awaitMain { binding.binder.controller.uiState.value.phase == BluetoothConnectionPhase.DISCONNECTED }
        assertTrue(gateway.connections.last().closed)
    }

    @Test
    fun acquisitionErrorReleasesForegroundOwnershipAndWakeLockWhileStillBound() {
        failAcquisition = true
        val binding = bind()
        connect(binding)
        awaitMain { binding.binder.hasAcquisitionWakeLock }
        failSignal.complete(Unit)
        awaitMain {
            binding.binder.controller.uiState.value.obd.phase == ObdPhase.ERROR &&
                !binding.binder.hasForegroundSession && !binding.binder.hasAcquisitionWakeLock
        }
        assertTrue(binding.bound)
        assertTrue(gateway.connections.single().closed)
        assertTrue(acquisitionCancelled)
        main { assertNull(binding.binder.sessionNotification) }
        assertEquals(1, gateway.connections.size)
    }

    @Test
    fun connectRequiresVisibleUiSelectedDeviceAndReadinessAndPromotesBeforeTransport() {
        val binding = bind()
        main {
            binding.binder.setAppForeground(false)
            binding.binder.controller.setForeground(false)
            binding.binder.controller.selectDevice(device)
            binding.binder.connect()
            assertFalse(binding.binder.hasForegroundSession)
        }
        assertTrue(gateway.connections.isEmpty())
        main {
            gateway.ready = BluetoothAvailability.PERMISSION_REQUIRED
            binding.binder.controller.setForeground(true)
            binding.binder.connect()
            assertFalse(binding.binder.hasForegroundSession)
            gateway.ready = BluetoothAvailability.READY
            binding.binder.controller.refresh()
            assertNull(binding.binder.controller.uiState.value.selectedDevice)
            binding.binder.connect()
            assertFalse(binding.binder.hasForegroundSession)
        }
        assertTrue(gateway.connections.isEmpty())
        gateway.onConnect = { assertTrue(binding.binder.hasForegroundSession) }
        connect(binding)
        awaitMain { binding.binder.hasAcquisitionWakeLock }
        val notification = notification(binding)
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains(device.address))
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(device.name))
        assertNotNull(notification.contentIntent)
        assertEquals(1, notification.actions.size)
    }

    @Test
    fun backgroundReadinessLossStopsAcquisitionWithoutAnActivityReceiver() {
        val binding = bind()
        connect(binding)
        awaitMain { binding.binder.hasAcquisitionWakeLock }
        scenario!!.moveToState(Lifecycle.State.CREATED)
        main { gateway.ready = BluetoothAvailability.OFF }
        awaitMain {
            binding.binder.controller.uiState.value.phase == BluetoothConnectionPhase.DISCONNECTED &&
                !binding.binder.hasForegroundSession && !binding.binder.hasAcquisitionWakeLock
        }
        assertTrue(gateway.connections.single().closed)
        assertTrue(acquisitionCancelled)
        assertEquals(BluetoothAvailability.OFF, binding.binder.controller.uiState.value.availability)
        main { gateway.ready = BluetoothAvailability.READY }
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, gateway.connections.size)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, binding.binder.controller.uiState.value.phase)
    }
    @Test
    fun destroyedServiceCreatesAFreshDisconnectedControllerWithoutRestoringASession() {
        val first = bind()
        connect(first)
        val previous = first.binder.controller
        main { previous.disconnect() }
        awaitMain { !first.binder.hasForegroundSession }
        scenario!!.close()
        scenario = null
        first.unbind()
        context.stopService(serviceIntent)
        instrumentation.waitForIdleSync()
        val fresh = bind()
        assertNotSame(previous, fresh.binder.controller)
        assertEquals(BluetoothConnectionPhase.DISCONNECTED, fresh.binder.controller.uiState.value.phase)
        assertNull(fresh.binder.controller.uiState.value.selectedDevice)
        assertEquals(1, gateway.connections.size)
        main {
            assertFalse(fresh.binder.hasForegroundSession)
            assertFalse(fresh.binder.hasAcquisitionWakeLock)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun notificationPermissionDenialDoesNotBlockExplicitConnectionOrInAppStop() {
        // A fresh install normally starts denied. Do not revoke a permission and kill the test process.
        assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        val binding = bind()
        connect(binding)
        main {
            assertTrue(binding.binder.hasForegroundSession)
            assertNotNull(binding.binder.sessionNotification)
            binding.binder.controller.disconnect()
        }
        awaitMain { !binding.binder.hasForegroundSession }
        assertTrue(gateway.connections.single().closed)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun connect(binding: Binding) {
        main {
            binding.binder.setAppForeground(true)
            binding.binder.controller.setForeground(true)
            binding.binder.controller.selectDevice(device)
            binding.binder.connect()
        }
        awaitMain { binding.binder.controller.uiState.value.phase == BluetoothConnectionPhase.CONNECTED }
    }

    private fun notification(binding: Binding): Notification {
        var notification: Notification? = null
        main { notification = binding.binder.sessionNotification }
        return checkNotNull(notification)
    }

    private fun stopAction(binding: Binding) = notification(binding).actions.single().actionIntent

    private fun bind(): Binding = Binding().also {
        bindings += it
        it.bind()
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private fun screenRequestsAwake(): Boolean {
        var awake = false
        scenario!!.onActivity { activity -> awake = activity.window.decorView.requestsAwake() }
        return awake
    }

    private fun View.requestsAwake(): Boolean = keepScreenOn ||
        (this is ViewGroup && (0 until childCount).any { getChildAt(it).requestsAwake() })

    private fun awaitUi(message: String, condition: () -> Boolean) {
        try {
            // Advance Compose's test clock as well as Android main so recomposition can apply the flag.
            compose.waitUntil(timeoutMillis = 5_000L) {
                var satisfied = false
                main { satisfied = condition() }
                satisfied
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(message, timeout)
        }
    }

    private fun awaitMain(message: String = "Timed out waiting for service lifecycle state", condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var satisfied = false
            main { satisfied = condition() }
            if (satisfied) return
            Thread.sleep(20L)
        }
        main { assertTrue(message, condition()) }
    }

    private inner class Binding : ServiceConnection {
        private val ready = CountDownLatch(1)
        lateinit var binder: BluetoothSessionService.LocalBinder
        var bound = false
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service as BluetoothSessionService.LocalBinder
            ready.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
        fun bind() {
            main { bound = context.bindService(serviceIntent, this, Context.BIND_AUTO_CREATE) }
            assertTrue(bound)
            assertTrue("Service did not bind", ready.await(5, TimeUnit.SECONDS))
        }
        fun unbind() {
            if (bound) main {
                context.unbindService(this)
                bound = false
            }
        }
    }

    private class LifecycleTripRepository : TripRepository {
        @Volatile var session: TripSession? = null
        val saved = CopyOnWriteArrayList<TripSample>()
        @Volatile var finishGate: CompletableDeferred<Unit>? = null
        val finishEntered = CompletableDeferred<Unit>()
        override suspend fun recoverInterrupted() = Unit
        override suspend fun create(session: TripSession) { this.session = session }
        override suspend fun append(tripId: String, samples: List<TripSample>) { saved.addAll(samples) }
        override suspend fun updateProtocol(tripId: String, protocolLabel: String) {
            session = requireNotNull(session).copy(protocolLabel = protocolLabel)
        }
        override suspend fun finish(tripId: String, endedAtEpochMillis: Long, durationMillis: Long, status: TripStatus, reason: String) {
            finishEntered.complete(Unit)
            finishGate?.await()
            session = requireNotNull(session).copy(endedAtEpochMillis = endedAtEpochMillis, durationMillis = durationMillis, status = status, endReason = reason)
        }
        override suspend fun list(limit: Int) = listOfNotNull(session)
        override suspend fun historyPage(request: com.gonibix.rx8.trips.TripHistoryRequest) =
            com.gonibix.rx8.trips.TripHistoryPage(list(request.limit))
        override suspend fun find(tripId: String) = session
        override suspend fun samples(tripId: String, afterSequence: Long, limit: Int) = saved.filter { it.sequence > afterSequence }.take(limit)
        override suspend fun delete(tripId: String) = Unit
        override suspend fun storageBytes() = 0L
    }

    private inner class LifecycleGateway : BluetoothGateway {
        @Volatile var ready = BluetoothAvailability.READY
        val connections = CopyOnWriteArrayList<LifecycleConnection>()
        var onConnect: () -> Unit = {}
        var connectionGate: CompletableDeferred<Unit>? = null
        var failConnection = false
        @Volatile var attempts = 0
        var listed = listOf(device)
        override fun availability() = ready
        override fun pairedDevices() = listed
        override suspend fun connect(device: AdapterDevice): AdapterConnection {
            attempts++
            onConnect()
            connectionGate?.await()
            if (failConnection) throw IOException("Synthetic connection failure")
            return LifecycleConnection().also { connections += it }
        }
    }

    private class MemoryLastAdapterStore : LastAdapterStore {
        @Volatile var address: String? = null
        var loadGate: CompletableDeferred<Unit>? = null
        @Volatile var completedLoads = 0
        override suspend fun loadAddress(): String? = try { loadGate?.await(); address } finally { completedLoads++ }
        override suspend fun saveAddress(address: String) { this.address = address }
    }

    private class LifecycleConnection : AdapterConnection {
        @Volatile var closed = false
        override suspend fun read(timeoutMillis: Long) = error("Synthetic session must not read a real transport")
        override suspend fun write(bytes: ByteArray, timeoutMillis: Long) = error("Synthetic session must not write a real transport")
        override fun close() { closed = true }
    }
}
