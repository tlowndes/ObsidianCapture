package uk.tomlowndes.capture

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The bottom bar, used by App in MainActivity.kt.

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun NavBar(selected: Tab?, hangarOpen: Boolean, needs: Int, onHangar: () -> Unit, onPeek: () -> Unit, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(NavBg)) {
        HorizontalDivider(color = Outline)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(66.dp),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.NAV.forEach { t ->
                val on = t == selected
                Column(Modifier.combinedClickable(onLongClick = if (t == Tab.TASKS) onPeek else null) { onSelect(t) }.padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(CircleShape).background(if (on) PrimaryTint else Color.Transparent).padding(horizontal = 12.dp, vertical = 3.dp)) {
                        Text(t.icon, fontSize = 18.sp, color = if (on) Primary else Muted)
                    }
                    Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Primary else Muted)
                }
            }
            Column(Modifier.clickable(onClick = onHangar).padding(horizontal = 2.dp).semantics { contentDescription = "Hangar" + if (needs > 0) ", $needs need you" else "" },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Box(Modifier.clip(CircleShape).background(if (hangarOpen) PrimaryTint else Color.Transparent).padding(horizontal = 12.dp, vertical = 3.dp)) {
                        Text("⌂", fontSize = 18.sp, color = if (hangarOpen) Primary else Muted)
                    }
                    // Badge clear of the icon, at the pill's top-right corner, with a ring in the bar's colour (four-fixes-v1 A).
                    if (needs > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp).defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                        .border(2.dp, NavBg, CircleShape).clip(CircleShape).background(Green).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        Text("$needs", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B1A12))
                    }
                }
                Text("Hangar", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (hangarOpen) Primary else Muted)
            }
        }
    }
}
