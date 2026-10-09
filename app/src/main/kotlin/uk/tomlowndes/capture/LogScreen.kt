package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LogScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, slot: String?) {
    Column(Modifier.fillMaxSize().padding(20.dp)) { ScreenTitle("Daily log", "Coming in the next build") }
}
