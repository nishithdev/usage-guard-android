package com.nishith.usageguard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.content.ContextCompat
import com.nishith.usageguard.UsageRepo

@Composable
fun SetupScreen(refresh: Int, usageOk: Boolean, overlayOk: Boolean, onStart: () -> Unit) {
    val ctx = LocalContext.current
    var localTick by remember { mutableIntStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        localTick++
    }
    val key = refresh + localTick
    val notifOk = remember(key) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
    val batteryOk = remember(key) { UsageRepo.ignoresBatteryOptimizations(ctx) }

    fun open(intent: Intent) = runCatching {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    val pkgUri = Uri.parse("package:${ctx.packageName}")

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Ground)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(C.Ink),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(26.dp).border(4.dp, C.Accent, CircleShape))
        }
        Text(
            "Two permissions and you're set",
            fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Ink, lineHeight = 34.sp,
        )
        Text("Everything stays on your phone. Nothing is uploaded.", fontSize = 15.sp, color = C.Muted)
        VSpace(12.dp)

        val usageActive = !usageOk
        val overlayActive = usageOk && !overlayOk

        StepCard(
            number = 1, done = usageOk, active = usageActive,
            title = "Usage access",
            body = "Lets the app see which app is open and for how long. Find Usage Guard in the list and switch it on.",
            action = "Open usage access settings",
            onAction = { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
        )
        StepCard(
            number = 2, done = overlayOk, active = overlayActive,
            title = "Display over other apps",
            body = "Needed to put the cooldown lock on top of whatever you're using.",
            action = "Open Android settings",
            onAction = {
                if (!open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri))) {
                    open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            },
        )
        StepCard(
            number = 3, done = notifOk, active = false, optional = true,
            title = "Notifications",
            body = "Shows a small \"Guard on\" notice so Android keeps tracking in the background.",
            action = "Allow notifications",
            onAction = {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
        StepCard(
            number = 4, done = batteryOk, active = false, optional = true,
            title = "Ignore battery optimization",
            body = "Stops phones like Samsung or Xiaomi from killing the guard to save battery.",
            action = "Allow",
            onAction = {
                @Suppress("BatteryLife")
                if (!open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))) {
                    open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
        )

        if (!usageOk || !overlayOk) {
            WhiteCard {
                LabelBlock(
                    "Switch greyed out or \"App was denied access\"?",
                    "Android blocks this for apps not installed from the Play Store. Unlock it once:",
                )
                Text(
                    "1. Tap Open App info below\n" +
                        "2. Tap ⋮ in the top-right corner\n" +
                        "3. Choose \"Allow restricted settings\" and confirm\n" +
                        "4. Come back here and try the step again",
                    fontSize = 13.sp, color = C.Ink, lineHeight = 20.sp,
                )
                OutlinedButton(
                    onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Open App info", color = C.Ink, fontWeight = FontWeight.SemiBold) }
            }
        }
        VSpace(8.dp)
        Button(
            onClick = onStart,
            enabled = usageOk && overlayOk,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = C.Ink, contentColor = Color.White,
                disabledContainerColor = Color(0xFFD8D7D2), disabledContentColor = C.Muted,
            ),
        ) { Text("Start guarding", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun StepCard(
    number: Int,
    done: Boolean,
    active: Boolean,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    optional: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .then(if (active) Modifier.border(2.dp, C.Ink, RoundedCornerShape(18.dp)) else Modifier)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    when {
                        done -> Color(0xFF1E8A55)
                        active -> C.Ink
                        else -> C.Track
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(16.dp))
            } else {
                Text("$number", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (active) Color.White else C.Muted)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (optional) "$title (optional)" else title,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink,
            )
            Text(body, fontSize = 13.sp, color = C.Muted, lineHeight = 18.sp)
            if (done) {
                Text("Granted", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Green)
            } else {
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth().height(44.dp).padding(top = 2.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) C.Ink else C.Track,
                        contentColor = if (active) Color.White else C.Ink,
                    ),
                ) { Text(action, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
