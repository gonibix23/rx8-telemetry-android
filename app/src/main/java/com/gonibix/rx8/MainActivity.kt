package com.gonibix.rx8

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.gonibix.rx8.ui.SimulatorDashboard
import com.gonibix.rx8.ui.theme.Rx8Theme

class MainActivity : ComponentActivity() {
    private val simulatorViewModel by lazy {
        ViewModelProvider(this)[SimulatorViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            Rx8Theme {
                val state by simulatorViewModel.uiState.collectAsStateWithLifecycle()
                LifecycleStartEffect(simulatorViewModel) {
                    simulatorViewModel.setActive(true)
                    onStopOrDispose { simulatorViewModel.setActive(false) }
                }
                SimulatorDashboard(
                    snapshot = state,
                    onToggleSimulation = simulatorViewModel::toggleSimulation,
                    onReset = simulatorViewModel::resetSimulation,
                    onScenarioSelected = simulatorViewModel::selectScenario,
                )
            }
        }
    }
}
