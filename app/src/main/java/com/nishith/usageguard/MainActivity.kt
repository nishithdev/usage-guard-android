package com.nishith.usageguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nishith.usageguard.ui.AppsScreen
import com.nishith.usageguard.ui.C
import com.nishith.usageguard.ui.GuardTheme
import com.nishith.usageguard.ui.SettingsScreen
import com.nishith.usageguard.ui.SetupScreen
import com.nishith.usageguard.ui.TagsScreen
import androidx.compose.ui.res.painterResource
import com.nishith.usageguard.ui.TodayScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardPrefs(this).seedDefaults(this)
        setContent { GuardTheme { GuardApp() } }
    }
}

private enum class Tab(val label: String, val icon: ImageVector?) {
    Today("Today", Icons.Filled.DateRange),
    Apps("Apps", Icons.AutoMirrored.Filled.List),
    Tags("Tags", null),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
private fun GuardApp() {
    val ctx = LocalContext.current
    val prefs = remember { GuardPrefs(ctx) }

    // Re-check permissions and reload data every time the app comes back to the front.
    var refresh by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val usageOk = remember(refresh) { UsageRepo.hasUsageAccess(ctx) }
    val overlayOk = remember(refresh) { UsageRepo.canDrawOverlays(ctx) }
    var setupDone by remember { mutableStateOf(prefs.setupDone) }

    // If the guard should be on but isn't running in this process, Android shut it down
    // while the app was closed. Remember when it was last alive so Today can say so.
    var stoppedAt by remember { mutableStateOf<Long?>(null) }
    if (refresh >= 0 && prefs.guardOn && setupDone && !GuardService.running) {
        val hb = prefs.heartbeat
        if (hb > 0 && System.currentTimeMillis() - hb > 60_000) stoppedAt = hb
    }

    LaunchedEffect(refresh, usageOk, overlayOk, setupDone) {
        if (setupDone && usageOk && overlayOk && prefs.guardOn) GuardService.start(ctx)
    }

    if (!setupDone || !usageOk || !overlayOk) {
        SetupScreen(
            refresh = refresh,
            usageOk = usageOk,
            overlayOk = overlayOk,
            onStart = {
                prefs.setupDone = true
                prefs.guardOn = true
                setupDone = true
                GuardService.start(ctx)
            },
        )
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    Scaffold(
        containerColor = C.Ground,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            val v = t.icon
                            if (v != null) Icon(v, contentDescription = null)
                            else Icon(painterResource(R.drawable.ic_nfc), contentDescription = null)
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = C.Ink,
                            selectedTextColor = C.Ink,
                            indicatorColor = C.Track,
                            unselectedIconColor = C.Muted,
                            unselectedTextColor = C.Muted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.Today -> TodayScreen(refresh, stoppedAt, onEditLimits = { tab = Tab.Apps })
                Tab.Apps -> AppsScreen(refresh)
                Tab.Tags -> TagsScreen(refresh)
                Tab.Settings -> SettingsScreen(refresh)
            }
        }
    }
}
