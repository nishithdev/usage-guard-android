package com.nishith.usageguard.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nishith.usageguard.NfcHelper
import com.nishith.usageguard.NfcTagInfo
import com.nishith.usageguard.R
import com.nishith.usageguard.TagScan
import com.nishith.usageguard.TagStore
import com.nishith.usageguard.WriteResult
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

/** A sticker waiting to be written: the next tap writes it instead of reading. */
private data class PendingWrite(val id: String, val name: String, val createdAt: Long)

private sealed class WriteUi {
    data class Naming(val existing: NfcTagInfo?) : WriteUi()
    data class Waiting(val name: String, val error: String? = null) : WriteUi()
    data class Done(val name: String) : WriteUi()
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun whenText(t: Long): String =
    if (DateUtils.isToday(t)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

@Composable
fun TagsScreen(refresh: Int) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val adapter = remember { NfcAdapter.getDefaultAdapter(ctx) }
    val store = remember { TagStore(ctx) }
    var version by remember { mutableIntStateOf(0) }
    val nfcOn = remember(refresh) { adapter?.isEnabled == true }

    val pending = remember { AtomicReference<PendingWrite?>(null) }
    var writeUi by remember { mutableStateOf<WriteUi?>(null) }
    var lastScan by remember { mutableStateOf<Pair<TagScan, NfcTagInfo?>?>(null) }
    var detail by remember { mutableStateOf<NfcTagInfo?>(null) }

    // While this tab is on screen, the phone reads stickers here instead of opening the scan screen.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, adapter, activity) {
        if (adapter == null || activity == null) return@DisposableEffect onDispose { }
        val callback = NfcAdapter.ReaderCallback { tag ->
            val uid = NfcHelper.uidHex(tag)
            val pw = pending.get()
            if (pw != null) {
                val res = NfcHelper.write(tag, NfcHelper.message(ctx, pw.id))
                activity.runOnUiThread {
                    if (res == WriteResult.OK) {
                        pending.set(null)
                        store.save(NfcTagInfo(pw.id, pw.name, uid, pw.createdAt))
                        writeUi = WriteUi.Done(pw.name)
                        version++
                    } else {
                        writeUi = WriteUi.Waiting(pw.name, res.message)
                    }
                }
            } else {
                val id = NfcHelper.readId(tag)
                activity.runOnUiThread {
                    var known = id?.let { store.byId(it) } ?: store.byUid(uid)
                    if (known != null && known.uid == null) {
                        known = known.copy(uid = uid)
                        store.save(known)
                    }
                    lastScan = store.recordScan(known, uid) to known
                    version++
                }
            }
        }
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        fun enable() = runCatching { adapter.enableReaderMode(activity, callback, flags, null) }
        fun disable() = runCatching { adapter.disableReaderMode(activity) }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> enable()
                Lifecycle.Event.ON_PAUSE -> disable()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) enable()
        onDispose {
            owner.lifecycle.removeObserver(obs)
            disable()
            pending.set(null)
        }
    }

    val tags = remember(version, refresh) { store.tags }
    val scans = remember(version, refresh) { store.scans }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("NFC tags")
        Text(
            "Stick tags around your home and tap them with your phone. " +
                "Choosing what each tag does comes in the next release. For now, set them up and see your scans.",
            fontSize = 13.sp, color = C.Muted,
        )

        // ---- NFC status ----
        when {
            adapter == null -> WhiteCard {
                LabelBlock("This phone doesn't have NFC", "Tags can't be used on this device.")
            }
            !nfcOn -> WhiteCard {
                LabelBlock("NFC is off", "Turn it on to set up and scan tags.")
                OutlinedButton(
                    onClick = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Open NFC settings", color = C.Ink, fontWeight = FontWeight.SemiBold) }
            }
            else -> Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(C.GreenBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF1E8A55)))
                Text("Ready. Hold a tag to the back of your phone", fontSize = 13.sp, color = C.Green, fontWeight = FontWeight.SemiBold)
            }
        }

        // ---- latest scan on this screen ----
        lastScan?.let { (scan, tag) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(C.Ink)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (tag != null) "${tag.name} scanned" else "Unknown sticker",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (tag != null) "${whenText(scan.time)} · No actions yet"
                    else "Serial ${scan.uid ?: "unknown"}. Set it up to use it.",
                    color = C.Soft, fontSize = 13.sp,
                )
                if (tag == null && nfcOn) {
                    Button(
                        onClick = { writeUi = WriteUi.Naming(null) },
                        modifier = Modifier.padding(top = 4.dp).height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.Ink),
                    ) { Text("Set it up", fontWeight = FontWeight.SemiBold) }
                }
            }
        }

        Button(
            onClick = { writeUi = WriteUi.Naming(null) },
            enabled = adapter != null && nfcOn,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.Ink, contentColor = Color.White),
        ) { Text("+ Add a tag", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

        // ---- your tags ----
        SectionLabel("Your tags · ${tags.size}")
        if (tags.isEmpty()) {
            WhiteCard {
                Text(
                    "No tags yet. Tap Add a tag, name it (like Desk or Bedside), then hold a blank sticker to your phone.",
                    fontSize = 13.sp, color = C.Muted,
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
            ) {
                tags.forEachIndexed { i, t ->
                    val last = remember(version, t.id) { store.lastScanOf(t.id) }
                    val count = remember(version, t.id) { store.scanCount(t.id) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { detail = t }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.Ink),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(R.drawable.ic_nfc), contentDescription = null, tint = C.Accent, modifier = Modifier.size(22.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(t.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (last != null) "Last scanned ${whenText(last.time)} · $count scan${if (count == 1) "" else "s"}"
                                else "Not scanned yet",
                                fontSize = 12.sp, color = C.Muted,
                            )
                        }
                    }
                    if (i != tags.lastIndex) Box(Modifier.padding(horizontal = 16.dp)) { Divider() }
                }
            }
        }

        // ---- history ----
        if (scans.isNotEmpty()) {
            SectionLabel("Recent scans")
            WhiteCard {
                scans.take(15).forEach { s ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            s.name, Modifier.weight(1f), fontSize = 14.sp,
                            color = if (s.tagId == null) C.Muted else C.Ink,
                        )
                        Text(whenText(s.time), fontSize = 13.sp, color = C.Muted)
                    }
                }
            }
        }
    }

    // ---------------- dialogs ----------------

    when (val ui = writeUi) {
        is WriteUi.Naming -> {
            var name by remember(ui) { mutableStateOf(ui.existing?.name ?: "") }
            AlertDialog(
                onDismissRequest = { writeUi = null },
                containerColor = Color.White,
                title = { Text(if (ui.existing == null) "Name this tag" else "Write ${ui.existing.name} to a sticker") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = { Text("e.g. Desk, Bedside, Front door") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = searchColors(),
                        )
                        Text("Next, you'll hold the sticker to your phone.", fontSize = 13.sp, color = C.Muted)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            val ex = ui.existing
                            pending.set(
                                PendingWrite(
                                    id = ex?.id ?: store.newId(),
                                    name = name.trim(),
                                    createdAt = ex?.createdAt ?: System.currentTimeMillis(),
                                )
                            )
                            writeUi = WriteUi.Waiting(name.trim())
                        },
                    ) { Text("Next", color = C.Ink, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { writeUi = null }) { Text("Cancel", color = C.Muted) } },
            )
        }
        is WriteUi.Waiting -> AlertDialog(
            onDismissRequest = { pending.set(null); writeUi = null },
            containerColor = Color.White,
            title = { Text("Hold your phone to the sticker") },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(color = C.Accent)
                    Text(
                        "Touch the back of your phone, near the camera, to the sticker and hold still for a second. " +
                            "This writes \"${ui.name}\" onto it.",
                        fontSize = 14.sp, color = C.Muted,
                    )
                    ui.error?.let { Text(it, fontSize = 13.sp, color = C.AccentText, fontWeight = FontWeight.SemiBold) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pending.set(null); writeUi = null }) { Text("Cancel", color = C.Muted) }
            },
        )
        is WriteUi.Done -> AlertDialog(
            onDismissRequest = { writeUi = null },
            containerColor = Color.White,
            title = { Text("${ui.name} is ready") },
            text = {
                Text(
                    "Tap it any time with your phone unlocked. Usage Guard opens and logs the scan, even if the app is closed.",
                    color = C.Muted,
                )
            },
            confirmButton = { TextButton(onClick = { writeUi = null }) { Text("OK", color = C.Ink, fontWeight = FontWeight.Bold) } },
        )
        null -> Unit
    }

    detail?.let { t ->
        var name by remember(t) { mutableStateOf(t.name) }
        var armDelete by remember(t) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { detail = null },
            containerColor = Color.White,
            title = { Text("Tag details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = searchColors(),
                    )
                    Text(
                        "Added ${whenText(t.createdAt)} · ${store.scanCount(t.id)} scans" +
                            (t.uid?.let { "\nSticker serial $it" } ?: ""),
                        fontSize = 13.sp, color = C.Muted,
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(C.Ground)
                            .padding(12.dp),
                    ) {
                        Text("Actions", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Coming in the next release: choose what happens at home when you tap this tag.", fontSize = 13.sp, color = C.Muted)
                    }
                    TextButton(onClick = { detail = null; writeUi = WriteUi.Naming(t.copy(name = name.trim().ifBlank { t.name })) }) {
                        Text("Write to a new sticker", color = C.Ink)
                    }
                    TextButton(onClick = {
                        if (armDelete) { store.delete(t.id); detail = null; version++ } else armDelete = true
                    }) {
                        Text(if (armDelete) "Tap again to delete" else "Delete tag", color = C.AccentText)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) store.save(t.copy(name = name.trim()))
                    detail = null
                    version++
                }) { Text("Save", color = C.Ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("Close", color = C.Muted) } },
        )
    }
}
