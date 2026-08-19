package dev.montb.basicsms.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Imports SMS *and* MMS from a backup .zip made by the F-Droid app
 * "SMS Import / Export" (package com.github.tmo1.sms_ie).
 *
 * Format inside the zip:
 *  - NDJSON file(s) (one JSON object per line). SMS and MMS are interleaved.
 *      SMS row:  { address, body, date (millis, string), type (1=inbox,2=sent),
 *                  read, sub_id }
 *      MMS row:  { date / date_sent, msg_box (1=inbox,2=sent), sub_id,
 *                  "__recipient_addresses__"/"addresses": [...],
 *                  "__parts__": [ { ct, text, _data, ... }, ... ] }
 *  - a "data/" folder with attachment files referenced by each part's "_data".
 *
 * Because we can't seek backwards in a ZipInputStream, we do TWO passes:
 *  pass 1, extract every "data/..." attachment file into the app's files dir,
 *  pass 2, parse the NDJSON, resolving image parts to the extracted files.
 *
 * Insertion is de-duplicated and batched off the main thread.
 */
object SmsImporter {

    data class Result(val imported: Int, val skipped: Int, val errors: Int)

    private const val BATCH = 300
    private const val ATTACH_DIR = "mms"

    suspend fun importFromZip(context: Context, zipUri: Uri, dao: MessageDao): Result {
        // ---- Pass 1: extract attachments -> map original zip path -> local File ----
        val attachments = extractAttachments(context, zipUri)

        // ---- Pass 2: parse NDJSON (SMS + MMS) ----
        var imported = 0
        var skipped = 0
        var errors = 0
        val batch = ArrayList<MessageEntity>(BATCH)

        suspend fun flush() {
            if (batch.isNotEmpty()) {
                dao.insertAll(batch)
                imported += batch.size
                batch.clear()
            }
        }

        context.contentResolver.openInputStream(zipUri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val lower = entry.name.lowercase()
                    if (!entry.isDirectory && lower.endsWith(".ndjson")) {
                        val reader = zip.bufferedReader(Charsets.UTF_8)
                        var line = reader.readLine()
                        while (line != null) {
                            val text = line.trim()
                            if (text.isNotEmpty()) {
                                val entity = runCatching { parseLine(text, attachments) }.getOrNull()
                                if (entity == null) {
                                    // Blank/garbage line, or a non-message object, count
                                    // only genuine parse failures as errors (best-effort).
                                    if (looksLikeMessage(text)) errors++
                                } else if (dao.countMatching(entity.address, entity.timestamp, entity.body) > 0
                                    && entity.attachmentPath == null
                                ) {
                                    skipped++
                                } else {
                                    batch += entity
                                    if (batch.size >= BATCH) flush()
                                }
                            }
                            line = reader.readLine()
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        flush()
        return Result(imported, skipped, errors)
    }

    /** Pass 1: copy each "data/..." zip entry into <files>/mms/, keyed by the entry name. */
    private fun extractAttachments(context: Context, zipUri: Uri): Map<String, File> {
        val out = HashMap<String, File>()
        val dir = File(context.filesDir, ATTACH_DIR).apply { mkdirs() }
        context.contentResolver.openInputStream(zipUri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val lower = name.lowercase()
                    val isAttachment = !entry.isDirectory &&
                        (lower.contains("data/") || lower.contains("attachments/")) &&
                        !lower.endsWith(".ndjson") && !lower.endsWith(".json")
                    if (isAttachment) {
                        val safe = name.substringAfterLast('/').ifBlank { "att_${out.size}" }
                        val dest = File(dir, "${out.size}_$safe")
                        dest.outputStream().use { o -> zip.copyTo(o) }
                        // Key by both full path and bare filename so "_data" can match either.
                        out[name] = dest
                        out[name.substringAfterLast('/')] = dest
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return out
    }

    private fun looksLikeMessage(line: String): Boolean =
        line.startsWith("{") && (line.contains("\"date\"") || line.contains("\"body\"") ||
            line.contains("\"msg_box\"") || line.contains("\"__parts__\""))

    /** Parse one NDJSON line (SMS or MMS) into a MessageEntity, or null if not usable. */
    private fun parseLine(line: String, attachments: Map<String, File>): MessageEntity? {
        val o = JSONObject(line)
        val parts = o.optJSONArray("__parts__") ?: o.optJSONArray("parts")
        return if (parts != null || o.has("msg_box")) {
            parseMms(o, parts, attachments)
        } else {
            parseSms(o)
        }
    }

    private fun parseSms(o: JSONObject): MessageEntity? {
        val body = o.optString("body").takeIf { it.isNotBlank() } ?: return null
        val address = o.optString("address").takeIf { it.isNotBlank() } ?: return null
        val timestamp = o.opt("date")?.toString()?.toLongOrNull() ?: return null
        val type = o.opt("type")?.toString()?.toIntOrNull() ?: 1
        val read = o.opt("read")?.toString()?.let { it == "1" || it.equals("true", true) } ?: true
        val subId = o.opt("sub_id")?.toString()?.toIntOrNull() ?: -1
        val incoming = type != 2
        return MessageEntity(
            address = address, body = body, timestamp = timestamp,
            incoming = incoming, read = read || !incoming, subId = subId
        )
    }

    private fun parseMms(
        o: JSONObject,
        parts: JSONArray?,
        attachments: Map<String, File>
    ): MessageEntity? {
        // msg_box: 1 = inbox (received), 2 = sent.
        val msgBox = o.opt("msg_box")?.toString()?.toIntOrNull()
            ?: o.opt("m_type")?.toString()?.toIntOrNull() ?: 1
        val incoming = msgBox != 2
        // MMS date is often in SECONDS; SMS is millis. Normalize to millis.
        val rawDate = (o.opt("date") ?: o.opt("date_sent"))?.toString()?.toLongOrNull() ?: return null
        val timestamp = if (rawDate < 100_000_000_000L) rawDate * 1000 else rawDate
        val subId = o.opt("sub_id")?.toString()?.toIntOrNull() ?: -1
        val address = pickAddress(o, incoming) ?: "MMS"

        var textBody = ""
        var attachPath: String? = null
        var attachMime: String? = null

        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val ct = p.optString("ct").lowercase()
                when {
                    ct.startsWith("text/") -> {
                        val t = p.optString("text")
                        if (t.isNotBlank() && t != "null") {
                            if (textBody.isNotEmpty()) textBody += "\n"
                            textBody += t
                        }
                    }
                    ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") -> {
                        // Resolve the saved attachment file via the part's "_data" path.
                        val dataPath = p.optString("_data").takeIf { it.isNotBlank() && it != "null" }
                        val file = dataPath?.let { dp ->
                            attachments[dp] ?: attachments[dp.substringAfterLast('/')]
                        }
                        if (file != null && attachPath == null) {
                            attachPath = file.absolutePath
                            attachMime = ct
                        }
                    }
                }
            }
        }

        // Skip the SMIL/presentation parts; if there's no text and no attachment, mark it.
        val body = when {
            textBody.isNotBlank() -> textBody
            attachPath != null -> ""          // image-only MMS
            else -> "[MMS]"
        }
        return MessageEntity(
            address = address, body = body, timestamp = timestamp,
            incoming = incoming, read = true, subId = subId,
            attachmentPath = attachPath, attachmentMime = attachMime
        )
    }

    /** Best-effort: the other party's number. Handles group MMS by taking the first
     *  address that isn't ourselves (type 137 = "from"/sender in the MMS spec). */
    private fun pickAddress(o: JSONObject, incoming: Boolean): String? {
        // Some exports flatten a single address.
        o.optString("address").takeIf { it.isNotBlank() && it != "null" }?.let { return it }

        val addrs = o.optJSONArray("__recipient_addresses__")
            ?: o.optJSONArray("addresses")
            ?: o.optJSONArray("__addresses__")
            ?: return null

        var firstOther: String? = null
        for (i in 0 until addrs.length()) {
            val a = addrs.optJSONObject(i) ?: continue
            val number = a.optString("address").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val type = a.opt("type")?.toString()?.toIntOrNull()
            // 137 = FROM (sender). For an incoming MMS that's the other party.
            if (incoming && type == 137) return number
            // 151 = TO. For a sent MMS the recipient is the other party.
            if (!incoming && type == 151) return number
            if (firstOther == null && number != "insert-address-token") firstOther = number
        }
        return firstOther
    }
}
