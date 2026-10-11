package com.gonibix.rx8.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeepScreenAwakeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun keepsOnlyAnActiveVisibleReadingScreenAwake() {
        val reading = mutableStateOf(false)
        val showScreen = mutableStateOf(true)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            if (showScreen.value) KeepScreenAwake(reading.value)
            Text("Screen-awake lifecycle fixture")
        }
        compose.runOnIdle { assertFalse(view.keepScreenOn); reading.value = true }
        compose.runOnIdle { assertTrue(view.keepScreenOn); reading.value = false }
        compose.runOnIdle { assertFalse(view.keepScreenOn); reading.value = true }
        compose.runOnIdle { assertTrue(view.keepScreenOn); showScreen.value = false }
        compose.runOnIdle { assertFalse(view.keepScreenOn) }
    }

    @Test fun backgroundingReleasesScreenAndReturningRestoresIt() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            KeepScreenAwake(true)
            Text("Screen-awake lifecycle fixture")
        }
        compose.runOnIdle { assertTrue(view.keepScreenOn) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity { assertFalse(view.keepScreenOn) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertTrue(view.keepScreenOn) }
    }
}
