package dev.montb.basiccontacts.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.BufferedReader

/**
 * Minimal self-contained vCard 3.0 reader/writer, no external library. Handles the
 * fields people actually keep in a phone book: FN/N (name), TEL (phones), EMAIL,
 * NOTE. Good enough to round-trip with other contacts apps' .vcf exports.
 *
 * Deliberately small: we don't parse photos, addresses, or every TYPE permutation.
 * Run off the main thread (does file + provider I/O).
 */
object VCard {

    data class ImportResult(val imported: Int, val failed: Int)

    // --- import ---

    fun import(context: Context, uri: Uri, repo: ContactsRepository): ImportResult {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().use(BufferedReader::readText)
        } ?: return ImportResult(0, 0)

        var imported = 0
        var failed = 0
        for (card in splitCards(unfold(text))) {
            val contact = parseCard(card) ?: continue
            // Skip empty cards (no name and no data).
            if (contact.displayName.isBlank() &&
                contact.phones.all { it.value.isBlank() } &&
                contact.emails.isEmpty()
            ) continue
            if (repo.insert(contact)) imported++ else failed++
        }
        return ImportResult(imported, failed)
    }

    /** vCard folds long lines by starting a continuation with a space/tab. Rejoin them. */
    private fun unfold(text: String): String =
        text.replace("\r\n", "\n").replace("\r", "\n")
            .replace(Regex("\n[ \t]"), "")

    private fun splitCards(text: String): List<String> {
        val cards = ArrayList<String>()
        val current = StringBuilder()
        var inside = false
        for (line in text.split("\n")) {
            when {
                line.startsWith("BEGIN:VCARD", ignoreCase = true) -> {
                    inside = true; current.clear()
                }
                line.startsWith("END:VCARD", ignoreCase = true) -> {
                    if (inside) cards.add(current.toString())
                    inside = false
                }
                inside -> current.append(line).append("\n")
            }
        }
        return cards
    }

    private fun parseCard(card: String): EditableContact? {
        var fn = ""
        var structuredName = ""
        val phones = ArrayList<LabeledValue>()
        val emails = ArrayList<LabeledValue>()
        var note = ""
        var photo: ByteArray? = null

        for (raw in card.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon)
            val rawValue = line.substring(colon + 1)
            val name = key.substringBefore(';').uppercase()
            when (name) {
                "FN" -> fn = decode(rawValue)
                "N" -> structuredName = decode(rawValue).split(';').filter { it.isNotBlank() }
                    .reversed().joinToString(" ").trim()
                "TEL" -> decode(rawValue).takeIf { it.isNotBlank() }
                    ?.let { phones.add(LabeledValue(it, labelFrom(key, "Mobile"))) }
                "EMAIL" -> decode(rawValue).takeIf { it.isNotBlank() }
                    ?.let { emails.add(LabeledValue(it, labelFrom(key, "Home"))) }
                "NOTE" -> note = decode(rawValue)
                // Embedded base64 photo (vCard 3.0 ENCODING=BASE64 / =b). We ignore
                // URI-valued photos (VALUE=uri), only inline data is portable offline.
                "PHOTO" -> if (key.contains("BASE64", true) || key.contains("ENCODING=b", true)) {
                    photo = runCatching {
                        Base64.decode(rawValue.replace(Regex("\\s"), ""), Base64.DEFAULT)
                    }.getOrNull()?.takeIf { it.isNotEmpty() }
                }
            }
        }
        val displayName = fn.ifBlank { structuredName }
        return EditableContact(
            displayName = displayName,
            phones = phones.ifEmpty { listOf(LabeledValue("", EditableContact.PHONE_MOBILE)) },
            emails = emails,
            note = note,
            newPhoto = photo
        )
    }

    /** Pull a friendly label out of the TYPE= parameter, falling back to [default]. */
    private fun labelFrom(key: String, default: String): String {
        val type = Regex("TYPE=([A-Za-z]+)", RegexOption.IGNORE_CASE)
            .find(key)?.groupValues?.get(1)
        return when (type?.uppercase()) {
            "CELL", "MOBILE" -> "Mobile"
            "HOME" -> "Home"
            "WORK" -> "Work"
            null -> default
            else -> type.replaceFirstChar { it.uppercase() }
        }
    }

    /** Handle the common QUOTED-PRINTABLE / escaped-comma cases minimally. */
    private fun decode(value: String): String =
        value.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").trim()

    // --- export ---

    /** [photoBytes] supplies the full-size photo for a contact id (or null = no photo);
     *  the caller wires it to ContactsRepository.readPhotoBytes. */
    fun export(
        context: Context,
        uri: Uri,
        contacts: List<ContactDetail>,
        photoBytes: (Long) -> ByteArray?
    ): Int {
        var written = 0
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.bufferedWriter().use { w ->
                for (c in contacts) {
                    w.write("BEGIN:VCARD\r\n")
                    w.write("VERSION:3.0\r\n")
                    w.write("FN:${escape(c.displayName)}\r\n")
                    w.write("N:${escape(c.displayName)};;;;\r\n")
                    c.phones.forEach { p ->
                        w.write("TEL;TYPE=${typeParam(p.label)}:${escape(p.value)}\r\n")
                    }
                    c.emails.forEach { e ->
                        w.write("EMAIL;TYPE=${typeParam(e.label)}:${escape(e.value)}\r\n")
                    }
                    c.note?.takeIf { it.isNotBlank() }?.let {
                        w.write("NOTE:${escape(it)}\r\n")
                    }
                    photoBytes(c.contactId)?.let { bytes ->
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        w.write("PHOTO;ENCODING=BASE64;TYPE=JPEG:$b64\r\n")
                    }
                    w.write("END:VCARD\r\n")
                    written++
                }
            }
        }
        return written
    }

    private fun typeParam(label: String): String = when (label.uppercase()) {
        "MOBILE" -> "CELL"
        "HOME" -> "HOME"
        "WORK" -> "WORK"
        else -> "VOICE"
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace(";", "\\;")
            .replace(",", "\\,").replace("\n", "\\n")
}
