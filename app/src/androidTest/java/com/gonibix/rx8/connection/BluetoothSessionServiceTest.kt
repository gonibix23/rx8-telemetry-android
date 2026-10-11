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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
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

    @Before
    fun setUp() {
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
                                onSnapshot(ObdSessionSnapshot(phase = ObdPhase.LIVE, elapsedMillis = elapsed))
                            }
                        } finally {
                            acquisitionCancelled = true
                        }
                    }
                }
            })
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
            BluetoothSessionService.dependenciesFactory = originalFactory
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun dashboardKeepsTheSameSessionAndOnlyVisibleLiveScreensStayAwake() {
        val binding = bind()
        connect(binding)
        main { binding.binder.controller.startObd() }
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
        main { first.binder.controller.startObd() }
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
        main { binding.binder.controller.startObd() }
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
        main { assertFalse(binding.binder.hasAcquisitionWakeLock) }
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
        main { binding.binder.controller.startObd() }
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

    private inner class LifecycleGateway : BluetoothGateway {
        @Volatile var ready = BluetoothAvailability.READY
        val connections = CopyOnWriteArrayList<LifecycleConnection>()
        var onConnect: () -> Unit = {}
        override fun availability() = ready
        override fun pairedDevices() = listOf(device)
        override suspend fun connect(device: AdapterDevice): AdapterConnection {
            onConnect()
            return LifecycleConnection().also { connections += it }
        }
    }

    private class LifecycleConnection : AdapterConnection {
        @Volatile var closed = false
        override suspend fun read(timeoutMillis: Long) = error("Synthetic session must not read a real transport")
        override suspend fun write(bytes: ByteArray, timeoutMillis: Long) = error("Synthetic session must not write a real transport")
        override fun close() { closed = true }
    }
}


