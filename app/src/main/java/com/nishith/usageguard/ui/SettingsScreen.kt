package com.nishith.usageguard.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nishith.usageguard.GuardPrefs
import com.nishith.usageguard.GuardService
import com.nishith.usageguard.UsageRepo
import com.nishith.usageguard.formatSeconds

@Composable
fun SettingsScreen(refresh: Int) {
    val ctx = LocalContext.current
    val prefs = remember { GuardPrefs(ctx) }
    var guardOn by remember { mutableStateOf(prefs.guardOn) }
    var limit by remember { mutableIntStateOf(prefs.defaultLimitSec) }
    var cooldown by remember { mutableIntStateOf(prefs.defaultCooldownSec) }
    var gap by remember { mutableIntStateOf(prefs.resetGapSec) }
    val batteryOk = remember(refresh) { UsageRepo.ignoresBatteryOptimizations(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenTitle("Settings")

        WhiteCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelBlock(
                    "Guard",
                    if (guardOn) "Tracking app time and enforcing cooldowns" else "Paused. Nothing is tracked or locked",
                    Modifier.weight(1f),
                )
                Switch(
                    checked = guardOn,
                    onCheckedChange = {
                        guardOn = it
                        prefs.guardOn = it
                        if (it) GuardService.start(ctx) else GuardService.stop(ctx)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = C.Ink),
                )
            }
        }

        WhiteCard {
            SectionLabel("Cooldown rules")
            Stepper(
                "Default session limit", "Used when you add an app", formatSeconds(limit),
                onMinus = { limit = stepIn(LIMIT_OPTIONS, limit, -1).also { prefs.defaultLimitSec = it } },
                onPlus = { limit = stepIn(LIMIT_OPTIONS, limit, +1).also { prefs.defaultLimitSec = it } },
            )
            Divider()
            Stepper(
                "Default cooldown", "Lock length when you add an app", formatSeconds(cooldown),
                onMinus = { cooldown = stepIn(COOLDOWN_OPTIONS, cooldown, -1).also { prefs.defaultCooldownSec = it } },
                onPlus = { cooldown = stepIn(COOLDOWN_OPTIONS, cooldown, +1).also { prefs.defaultCooldownSec = it } },
            )
            Divider()
            Stepper(
                "New session after", "Time away before the timer resets", formatSeconds(gap),
                onMinus = { gap = stepIn(GAP_OPTIONS, gap, -1).also { prefs.resetGapSec = it } },
                onPlus = { gap = stepIn(GAP_OPTIONS, gap, +1).also { prefs.resetGapSec = it } },
            )
        }

        VideoSettingsCard(prefs)

        WhiteCard {
            SectionLabel("Reliability")
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelBlock(
                    "Battery optimization",
                    if (batteryOk) "Off for Usage Guard, so the guard keeps running"
                    else "On. Some phones may stop the guard in the background",
                    Modifier.weight(1f),
                )
                if (!batteryOk) {
                    TextButton(onClick = {
                        @Suppress("BatteryLife")
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${ctx.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("Fix", color = C.Ink, fontWeight = FontWeight.Bold) }
                }
            }
        }

        Text(
            "Usage Guard v0.5 · all data stays on this phone",
            fontSize = 12.sp, color = C.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
