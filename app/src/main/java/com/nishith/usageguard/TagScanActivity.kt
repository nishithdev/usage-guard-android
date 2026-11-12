package com.nishith.usageguard

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import com.nishith.usageguard.ui.C
import com.nishith.usageguard.ui.GuardTheme
import java.text.DateFormat
import java.util.Date

/**
 * Opens when you tap a Usage Guard sticker while the app isn't on screen.
 * Logs the scan; this is where the tag's home actions will run in the next release.
 */
class TagScanActivity : ComponentActivity() {

    private var result by mutableStateOf<TagScan?>(null)
    private var known by mutableStateOf<NfcTagInfo?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            GuardTheme {
                val scan = result
                if (scan != null) ScanResult(scan, known, onDone = ::finish, onOpenApp = ::openApp)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        val store = TagStore(this)
        val tag = IntentCompat.getParcelableExtra(i, NfcAdapter.EXTRA_TAG, Tag::class.java)
        val uid = tag?.let { NfcHelper.uidHex(it) }
        var info = NfcHelper.idFromUri(i.data)?.let { store.byId(it) } ?: uid?.let { store.byUid(it) }
        if (info != null && info.uid == null && uid != null) {
            info = info.copy(uid = uid)
            store.save(info)
        }
        known = info
        result = store.recordScan(info, uid)
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun ScanResult(scan: TagScan, tag: NfcTagInfo?, onDone: () -> Unit, onOpenApp: () -> Unit) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(scan.time))
    Column(
        Modifier
            .fillMaxSize()
            .background(C.Ground)
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(if (tag != null) C.Ink else C.Track),
            contentAlignment = Alignment.Center,
        ) {
            if (tag != null) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = C.Accent, modifier = Modifier.size(48.dp))
            } else {
                Text("?", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = C.Muted)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (tag != null) "${tag.name} scanned" else "Unknown sticker",
            fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.Ink, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (tag != null) "$time · No actions yet. You'll choose what this tag does in the next release."
            else "This sticker isn't set up in Usage Guard anymore. Open the app's Tags tab to add it again.",
            fontSize = 15.sp, color = C.Muted, textAlign = TextAlign.Center, lineHeight = 21.sp,
        )
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.Ink, contentColor = Color.White),
            ) { Text("Done", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            OutlinedButton(
                onClick = onOpenApp,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Open Usage Guard", color = C.Ink) }
        }
    }
}
