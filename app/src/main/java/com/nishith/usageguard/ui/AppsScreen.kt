package com.nishith.usageguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.nishith.usageguard.AppGroup
import com.nishith.usageguard.GuardPrefs
import com.nishith.usageguard.LaunchableApp
import com.nishith.usageguard.UsageRepo
import com.nishith.usageguard.formatSeconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Sentinel for "create a new group" in the editor. */
private const val NEW_GROUP = "__new__"

@Composable
fun AppsScreen(refresh: Int) {
    val ctx = LocalContext.current
    val prefs = remember { GuardPrefs(ctx) }
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var editingApp by remember { mutableStateOf<LaunchableApp?>(null) }
    var editingGroup by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var recent by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    LaunchedEffect(refresh) {
        while (true) {
            recent = withContext(Dispatchers.IO) { UsageRepo.recentUsage(ctx) }
            kotlinx.coroutines.delay(5_000)
        }
    }

    LaunchedEffect(refresh) {
        apps = withContext(Dispatchers.IO) {
            val usage = UsageRepo.today(ctx, prefs.resetGapSec * 1000L).apps.associate { it.pkg to it.totalMs }
            // Apps you use most first, then A–Z.
            UsageRepo.launchableApps(ctx).sortedWith(
                compareByDescending<LaunchableApp> { usage[it.pkg] ?: 0L }.thenBy { it.label.lowercase() }
            )
        }
    }

    val all = apps
    if (editingGroup != null && all != null) {
        GroupEditor(
            groupId = editingGroup.takeIf { it != NEW_GROUP },
            apps = all,
            prefs = prefs,
            onClose = { editingGroup = null; version++ },
        )
        return
    }

    val groups = remember(version, refresh) { prefs.groups }
    val shown = all?.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenTitle("Apps & limits")
                Text(
                    "Only apps you switch on (or put in a group) are limited. Everything else, " +
                        "including system apps, is just counted on Today.",
                    fontSize = 13.sp, color = C.Muted,
                )
                VSpace(6.dp)
                SectionLabel("Groups · shared timer")
                groups.forEach { g ->
                    GroupCard(g, prefs, version, recent, onClick = { editingGroup = g.id })
                }
                OutlinedButton(
                    onClick = { editingGroup = NEW_GROUP },
                    enabled = all != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("+ New group", color = C.Ink, fontWeight = FontWeight.Medium) }
                VSpace(10.dp)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = searchColors(),
                )
                VSpace(4.dp)
            }
        }
        if (shown == null) {
            item { Text("Loading apps…", color = C.Muted) }
        } else {
            // Split once per change so a row doesn't jump sections mid-scroll except after a toggle.
            val (on, off) = shown.partition { prefs.isLimited(it.pkg) || version < 0 }
            val sections = listOf(
                "Limited · ${on.size}" to on,
                "Not limited · tap the switch to add" to off,
            )
            sections.forEach { (label, list) ->
                item(key = "label_$label") {
                    Column(Modifier.padding(top = 14.dp, bottom = 10.dp)) {
                        SectionLabel(label)
                        if (list.isEmpty() && label.startsWith("Limited")) {
                            Text(
                                "No apps yet. Switch on the ones you want to limit below.",
                                fontSize = 13.sp, color = C.Muted, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                itemsIndexed(list, key = { _, a -> a.pkg }) { i, app ->
                    val top = if (i == 0) 18.dp else 0.dp
                    val bottom = if (i == list.lastIndex) 18.dp else 0.dp
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(top, top, bottom, bottom))
                            .background(Color.White)
                            .clickable { editingApp = app }
                            .padding(start = 16.dp, end = 8.dp)
                    ) {
                        LimitRow(app, prefs, version, recent[app.pkg] ?: 0L, onToggle = { enabled ->
                            prefs.setLimited(app.pkg, enabled)
                            version++
                        })
                        if (i != list.lastIndex) Divider()
                    }
                }
            }
        }
    }

    editingApp?.let { app ->
        EditAppDialog(
            app, prefs,
            onOpenGroup = { id -> editingApp = null; editingGroup = id },
            onDone = { editingApp = null; version++ },
        )
    }
}

@Composable
fun searchColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    focusedBorderColor = C.Ink,
    unfocusedBorderColor = Color(0xFFE2E1DC),
)

@Composable
private fun GroupCard(
    g: AppGroup,
    prefs: GuardPrefs,
    version: Int,
    recent: Map<String, Long>,
    onClick: () -> Unit,
) {
    val key = "g:${g.id}"
    val lockedUntil = remember(version) { prefs.lockedUntil(key).takeIf { it > System.currentTimeMillis() } }
    val cds = remember(version) { prefs.cooldownsToday(key) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberIcons(g.members)
            Text(
                g.name, Modifier.weight(1f).padding(start = 10.dp),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Text(
                "${formatSeconds(g.limitSec)} · ${formatSeconds(g.cooldownSec)} lock",
                fontSize = 13.sp, color = C.Muted,
            )
        }
        Text(
            when {
                g.members.isEmpty() -> "No apps yet. Tap to add some"
                lockedUntil != null -> "Locked until " +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lockedUntil))
                else -> "${g.members.size} app${if (g.members.size == 1) "" else "s"} · " +
                    "$cds cooldown${if (cds == 1) "" else "s"} today"
            },
            fontSize = 12.sp,
            color = if (lockedUntil != null) C.AccentText else C.Muted,
            fontWeight = if (lockedUntil != null) FontWeight.SemiBold else FontWeight.Normal,
        )
        // The group shares one timer, so show its apps' time added together.
        if (g.members.isNotEmpty()) RecentLine(g.members.sumOf { recent[it] ?: 0L })
    }
}

@Composable
private fun MemberIcons(members: List<String>) {
    val show = members.take(4)
    if (show.isEmpty()) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(C.Track))
        return
    }
    Box(Modifier.height(32.dp)) {
        show.forEachIndexed { i, pkg ->
            Box(
                Modifier
                    .offset(x = (i * 22).dp)
                    .border(2.dp, Color.White, RoundedCornerShape(10.dp))
            ) { AppIcon(pkg, 32.dp) }
        }
        // Reserve width for the overlapping stack.
        Box(Modifier.size(width = (32 + (show.size - 1) * 22).dp, height = 32.dp))
    }
}

@Composable
private fun LimitRow(
    app: LaunchableApp,
    prefs: GuardPrefs,
    version: Int,
    recentMs: Long,
    onToggle: (Boolean) -> Unit,
) {
    val excluded = remember(app.pkg, version) { !prefs.isLimited(app.pkg) }
    val summary = remember(app.pkg, version) {
        val g = prefs.groupOf(app.pkg)
        when {
            excluded -> "Not limited"
            g != null -> "In ${g.name} · shared timer"
            else -> {
                val custom = prefs.customLimit(app.pkg) != null || prefs.customCooldown(app.pkg) != null
                "${formatSeconds(prefs.limitFor(app.pkg))} · then ${formatSeconds(prefs.cooldownFor(app.pkg))} lock" +
                    if (custom) "" else " (default)"
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app.pkg)
        Column(Modifier.weight(1f)) {
            Text(app.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                color = if (excluded) C.Muted else C.Ink)
            Text(summary, fontSize = 12.sp, color = C.Muted)
            if (!excluded) Box(Modifier.padding(top = 4.dp)) { RecentLine(recentMs) }
        }
        Switch(
            checked = !excluded,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = C.Ink),
        )
    }
}

@Composable
private fun EditAppDialog(
    app: LaunchableApp,
    prefs: GuardPrefs,
    onOpenGroup: (String) -> Unit,
    onDone: () -> Unit,
) {
    val group = remember { prefs.groupOf(app.pkg) }
    if (group != null) {
        AlertDialog(
            onDismissRequest = onDone,
            containerColor = Color.White,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(app.pkg)
                    Text(app.label, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    "${app.label} is in the ${group.name} group, so it shares one timer: " +
                        "${formatSeconds(group.limitSec)} across all ${group.name} apps, then a " +
                        "${formatSeconds(group.cooldownSec)} lock for all of them.",
                    color = C.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { onOpenGroup(group.id) }) {
                    Text("Edit ${group.name}", color = C.Ink, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.saveGroup(group.copy(members = group.members - app.pkg))
                    onDone()
                }) { Text("Remove from group", color = C.Muted) }
            },
        )
        return
    }

    // Tapping an app that isn't limited yet means you want to set it up, so start switched on.
    var excluded by remember { mutableStateOf(false) }
    var limit by remember { mutableIntStateOf(prefs.limitFor(app.pkg)) }
    var cooldown by remember { mutableIntStateOf(prefs.cooldownFor(app.pkg)) }

    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Color.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(app.pkg)
                Text(app.label, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Stepper(
                    "Session limit", "Continuous time before it locks", formatSeconds(limit),
                    onMinus = { limit = stepIn(LIMIT_OPTIONS, limit, -1) },
                    onPlus = { limit = stepIn(LIMIT_OPTIONS, limit, +1) },
                    enabled = !excluded,
                )
                Stepper(
                    "Then lock for", "Cooldown before it opens again", formatSeconds(cooldown),
                    onMinus = { cooldown = stepIn(COOLDOWN_OPTIONS, cooldown, -1) },
                    onPlus = { cooldown = stepIn(COOLDOWN_OPTIONS, cooldown, +1) },
                    enabled = !excluded,
                )
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabelBlock("Limit this app", "Off = no timer, no cooldown", Modifier.weight(1f))
                    Switch(
                        checked = !excluded, onCheckedChange = { excluded = !it },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.Ink),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.setLimited(app.pkg, !excluded)
                prefs.setCustomLimit(app.pkg, limit.takeIf { it != prefs.defaultLimitSec })
                prefs.setCustomCooldown(app.pkg, cooldown.takeIf { it != prefs.defaultCooldownSec })
                onDone()
            }) { Text("Save", color = C.Ink, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text("Cancel", color = C.Muted) }
        },
    )
}
