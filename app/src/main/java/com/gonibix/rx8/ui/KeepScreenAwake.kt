package com.gonibix.rx8.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleStartEffect

/** Keeps only the visible live-data screen awake; the service handles screen-off acquisition. */
@Composable
internal fun KeepScreenAwake(enabled: Boolean) {
    val view = LocalView.current
    LifecycleStartEffect(view, enabled) {
        if (enabled) view.keepScreenOn = true
        onStopOrDispose {
            if (enabled) view.keepScreenOn = false
        }
    }
}
