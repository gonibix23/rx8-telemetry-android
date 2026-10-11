package com.gonibix.rx8.capabilities

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.IOException

/** Register at the Activity root, outside conditional tabs, with its Activity-scoped ViewModel. */
@Composable
fun rememberCapabilityReportExportAction(viewModel: CapabilityReportViewModel): () -> Unit {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            viewModel.completeExport(null)
        } else {
            viewModel.completeExport {
                resolver.openOutputStream(uri, "wt") ?: throw IOException("The document provider did not open the destination.")
            }
        }
    }
    return remember(viewModel, picker) {
        {
            val fileName = viewModel.beginExport()
            if (fileName != null) {
                try {
                    picker.launch(fileName)
                } catch (_: Exception) {
                    viewModel.destinationUnavailable()
                }
            }
        }
    }
}