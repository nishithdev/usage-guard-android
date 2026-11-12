package com.nishith.usageguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nishith.usageguard.AppGroup
import com.nishith.usageguard.GuardPrefs
import com.nishith.usageguard.LaunchableApp
import com.nishith.usageguard.formatSeconds

/** Create or edit a group: name, shared limits, and which apps belong to it. */
@Composable
fun GroupEditor(groupId: String?, apps: List<LaunchableApp>, prefs: GuardPrefs, onClose: () -> Unit) {
    val existing = remember { groupId?.let { prefs.groupById(it) } }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var limit by remember { mutableIntStateOf(existing?.limitSec ?: prefs.defaultLimitSec) }
    var cooldown by remember { mutableIntStateOf(existing?.cooldownSec ?: prefs.defaultCooldownSec) }
    var members by remember { mutableStateOf(existing?.members?.toSet() ?: emptySet()) }
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    // Members first (as they were when the editor opened), then everything else.
    val ordered = remember {
        val start = existing?.members?.toSet() ?: emptySet()
        apps.sortedWith(compareByDescending<LaunchableApp> { it.pkg in start }.thenBy { it.label.lowercase() })
    }
    val shown = ordered.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }

    fun save() {
        prefs.saveGroup(
            AppGroup(
                id = existing?.id ?: prefs.newGroupId(),
                name = name.trim().ifBlank { "Group" },
                members = ordered.map { it.pkg }.filter { it in members },
                limitSec = limit,
                cooldownSec = cooldown,
            )
        )
        onClose()
    }

    BackHandler { onClose() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back without saving")
                    }
                    Text(
                        if (existing == null) "New group" else "Edit group",
                        Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = { save() }) {
                        Text("Save", color = C.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
                Text(
                    "Apps in a group share one timer and one cooldown. Hopping from one to another " +
                        "keeps the clock running, and when the group cools down, all of them lock.",
                    fontSize = 13.sp, color = C.Muted,
                )
                WhiteCard {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Group name") },
                        placeholder = { Text("e.g. Social, Video, Games") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = searchColors(),
                    )
                    Stepper(
                        "Shared session limit", "Total across these apps", formatSeconds(limit),
                        onMinus = { limit = stepIn(LIMIT_OPTIONS, limit, -1) },
                        onPlus = { limit = stepIn(LIMIT_OPTIONS, limit, +1) },
                    )
                    Divider()
                    Stepper(
                        "Shared cooldown", "All of them stay locked this long", formatSeconds(cooldown),
                        onMinus = { cooldown = stepIn(COOLDOWN_OPTIONS, cooldown, -1) },
                        onPlus = { cooldown = stepIn(COOLDOWN_OPTIONS, cooldown, +1) },
                    )
                }
                VSpace(4.dp)
                SectionLabel("Apps in this group · ${members.size}")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps to add") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = searchColors(),
                )
                VSpace(4.dp)
            }
        }

        itemsIndexed(shown, key = { _, a -> a.pkg }) { i, app ->
            val checked = app.pkg in members
            val otherGroup = remember(app.pkg) {
                prefs.groupOf(app.pkg)?.takeIf { it.id != existing?.id }
            }
            val note = when {
                otherGroup != null -> "Moves here from ${otherGroup.name}"
                app.pkg in prefs.limitedApps -> "Has its own limit now"
                else -> null
            }
            val top = if (i == 0) 18.dp else 0.dp
            val bottom = if (i == shown.lastIndex) 18.dp else 0.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(top, top, bottom, bottom))
                    .background(Color.White)
                    .clickable { members = if (checked) members - app.pkg else members + app.pkg }
                    .padding(start = 16.dp, end = 8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIcon(app.pkg, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontSize = 15.sp, maxLines = 1)
                        if (note != null && checked) Text(note, fontSize = 12.sp, color = C.AccentText)
                        else if (note != null) Text(note, fontSize = 12.sp, color = C.Muted)
                    }
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { members = if (it) members + app.pkg else members - app.pkg },
                        colors = CheckboxDefaults.colors(checkedColor = C.Ink),
                    )
                }
                if (i != shown.lastIndex) Divider()
            }
        }

        item {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { save() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.Ink, contentColor = Color.White),
                ) { Text("Save group", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                if (existing != null) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete group", color = C.AccentText) }
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Color.White,
            title = { Text("Delete ${existing.name}?") },
            text = { Text("Its apps go back to their own limits.", color = C.Muted) },
            confirmButton = {
                TextButton(onClick = { prefs.deleteGroup(existing.id); onClose() }) {
                    Text("Delete", color = C.AccentText, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = C.Ink) }
            },
        )
    }
}
