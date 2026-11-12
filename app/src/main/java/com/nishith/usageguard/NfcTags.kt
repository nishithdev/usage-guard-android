package com.nishith.usageguard

import android.content.Context
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A sticker you set up. [uid] is the sticker's own serial number, as hex. */
data class NfcTagInfo(
    val id: String,
    val name: String,
    val uid: String?,
    val createdAt: Long,
)

data class TagScan(
    val tagId: String?,
    val name: String,
    val uid: String?,
    val time: Long,
)

/** Your tags and recent scans, kept on the phone. Actions per tag come in a later release. */
class TagStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tags", Context.MODE_PRIVATE)

    val tags: List<NfcTagInfo>
        get() = runCatching {
            val a = JSONArray(sp.getString("tags", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                NfcTagInfo(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    uid = o.optString("uid").ifBlank { null },
                    createdAt = o.optLong("created"),
                )
            }
        }.getOrDefault(emptyList())

    fun byId(id: String) = tags.firstOrNull { it.id == id }

    fun byUid(uid: String) = tags.firstOrNull { it.uid.equals(uid, ignoreCase = true) }

    /** Adds or updates a tag. A physical sticker belongs to one tag, so others lose that serial. */
    fun save(tag: NfcTagInfo) {
        val current = tags
        val cleaned = current.map {
            if (it.id != tag.id && tag.uid != null && it.uid.equals(tag.uid, ignoreCase = true)) it.copy(uid = null) else it
        }
        val list = if (cleaned.any { it.id == tag.id }) cleaned.map { if (it.id == tag.id) tag else it } else cleaned + tag
        writeTags(list)
    }

    fun delete(id: String) {
        writeTags(tags.filter { it.id != id })
        sp.edit().remove("count_$id").apply()
    }

    val scans: List<TagScan>
        get() = runCatching {
            val a = JSONArray(sp.getString("scans", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                TagScan(
                    tagId = o.optString("tag").ifBlank { null },
                    name = o.getString("name"),
                    uid = o.optString("uid").ifBlank { null },
                    time = o.getLong("time"),
                )
            }
        }.getOrDefault(emptyList())

    fun recordScan(tag: NfcTagInfo?, uid: String?): TagScan {
        val scan = TagScan(tag?.id, tag?.name ?: "Unknown sticker", uid, System.currentTimeMillis())
        val list = (listOf(scan) + scans).take(50)
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("tag", s.tagId ?: "")
                put("name", s.name)
                put("uid", s.uid ?: "")
                put("time", s.time)
            })
        }
        val edit = sp.edit().putString("scans", arr.toString())
        if (tag != null) edit.putInt("count_${tag.id}", scanCount(tag.id) + 1)
        edit.apply()
        return scan
    }

    fun scanCount(id: String) = sp.getInt("count_$id", 0)

    fun lastScanOf(id: String) = scans.firstOrNull { it.tagId == id }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    private fun writeTags(list: List<NfcTagInfo>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("uid", t.uid ?: "")
                put("created", t.createdAt)
            })
        }
        sp.edit().putString("tags", arr.toString()).apply()
    }
}

enum class WriteResult(val message: String) {
    OK("Done"),
    READ_ONLY("This sticker is locked (read-only). Use a different one."),
    TOO_SMALL("This sticker is too small. Use an NTAG213, 215 or 216."),
    NOT_SUPPORTED("This isn't a writable NFC sticker."),
    FAILED("Couldn't write. Hold the phone still against the sticker and try again."),
}

/**
 * What goes on a sticker: a link "usageguard://tag/<id>" plus an Android app record,
 * so tapping it opens Usage Guard even when the app is closed.
 */
object NfcHelper {
    const val SCHEME = "usageguard"
    const val HOST = "tag"

    fun message(ctx: Context, id: String) = NdefMessage(
        arrayOf(
            NdefRecord.createUri("$SCHEME://$HOST/$id"),
            NdefRecord.createApplicationRecord(ctx.packageName),
        )
    )

    fun uidHex(tag: Tag): String = tag.id.joinToString("") { "%02X".format(it) }

    fun idFromUri(uri: Uri?): String? =
        uri?.takeIf { it.scheme == SCHEME && it.host == HOST }?.lastPathSegment

    /** Our tag id, if this sticker was set up by Usage Guard. */
    fun readId(tag: Tag): String? = runCatching {
        Ndef.get(tag)?.cachedNdefMessage?.records?.firstNotNullOfOrNull { idFromUri(it.toUri()) }
    }.getOrNull()

    /** Runs on a background thread (the NFC reader callback). */
    fun write(tag: Tag, msg: NdefMessage): WriteResult {
        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                when {
                    !ndef.isWritable -> WriteResult.READ_ONLY
                    ndef.maxSize < msg.byteArrayLength -> WriteResult.TOO_SMALL
                    else -> {
                        ndef.writeNdefMessage(msg)
                        WriteResult.OK
                    }
                }
            } catch (e: Exception) {
                WriteResult.FAILED
            } finally {
                runCatching { ndef.close() }
            }
        }
        // Brand-new stickers sometimes arrive unformatted.
        NdefFormatable.get(tag)?.let { f ->
            return try {
                f.connect()
                f.format(msg)
                WriteResult.OK
            } catch (e: Exception) {
                WriteResult.FAILED
            } finally {
                runCatching { f.close() }
            }
        }
        return WriteResult.NOT_SUPPORTED
    }
}
