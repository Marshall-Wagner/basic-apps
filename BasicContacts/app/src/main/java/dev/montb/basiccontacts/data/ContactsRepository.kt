package dev.montb.basiccontacts.data

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName

/**
 * Reads and writes the system contacts provider (ContactsContract). These are the same
 * contacts BasicSms / BasicPhone resolve, so anything we add/edit here syncs everywhere.
 *
 * Writes use ContentProviderOperation batches (applyBatch), the standard, atomic way
 * to create/update a contact, which spans a RawContact row plus several Data rows.
 *
 * All methods touch the ContentResolver; call them off the main thread.
 */
class ContactsRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    // --- read: list + search ---

    /** All contacts that have a name, ordered by display name. [query] (optional) filters
     *  by name or number using the platform's dialer-style filter URI. */
    fun loadContacts(query: String = ""): List<ContactSummary> {
        val q = query.trim()
        val uri = if (q.isEmpty()) {
            ContactsContract.Contacts.CONTENT_URI
        } else {
            Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
        }
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI
        )
        // First numbers for ALL contacts in ONE query, then join in memory. This avoids
        // an N+1 query (one number lookup per row), which was the main list-load cost.
        val numbers = numbersByContact()

        val out = ArrayList<ContactSummary>()
        resolver.query(
            uri, projection,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} IS NOT NULL",
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val lookupIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val photoIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: continue
                out.add(
                    ContactSummary(
                        contactId = id,
                        lookupKey = c.getString(lookupIdx) ?: "",
                        displayName = name,
                        primaryNumber = numbers[id],
                        photoUri = c.getString(photoIdx)
                    )
                )
            }
        }
        return out
    }

    /** Map of contactId -> first phone number, fetched in a single query (vs. one lookup
     *  per row). Pulling all numbers once is far cheaper than N per-contact queries. */
    private fun numbersByContact(): Map<Long, String> {
        val map = HashMap<Long, String>()
        resolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.NUMBER),
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val numIdx = c.getColumnIndexOrThrow(Phone.NUMBER)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                // Keep the first number seen per contact.
                if (!map.containsKey(id)) {
                    c.getString(numIdx)?.takeIf { it.isNotBlank() }?.let { map[id] = it }
                }
            }
        }
        return map
    }

    // --- read: one contact's full detail ---

    fun loadDetail(contactId: Long): ContactDetail? {
        var name = ""
        var lookupKey = ""
        var photo: String? = null
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_URI
            ),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            if (!c.moveToFirst()) return null
            lookupKey = c.getString(0) ?: ""
            name = c.getString(1) ?: ""
            photo = c.getString(2)
        }

        val phones = ArrayList<LabeledValue>()
        resolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            "${Phone.CONTACT_ID} = ?", arrayOf(contactId.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                val label = Phone.getTypeLabel(
                    context.resources, c.getInt(1), c.getString(2)
                ).toString()
                phones.add(LabeledValue(number, label))
            }
        }

        val emails = ArrayList<LabeledValue>()
        resolver.query(
            Email.CONTENT_URI,
            arrayOf(Email.ADDRESS, Email.TYPE, Email.LABEL),
            "${Email.CONTACT_ID} = ?", arrayOf(contactId.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                val label = Email.getTypeLabel(
                    context.resources, c.getInt(1), c.getString(2)
                ).toString()
                emails.add(LabeledValue(addr, label))
            }
        }

        val note = queryNote(contactId)

        return ContactDetail(contactId, lookupKey, name, photo, phones, emails, note)
    }

    private fun queryNote(contactId: Long): String? {
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(Note.NOTE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), Note.CONTENT_ITEM_TYPE),
            null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0)?.takeIf { it.isNotBlank() } }
        return null
    }

    /** The raw-contact id for editing. A contact may aggregate several raw contacts
     *  (multiple accounts); we edit the first, which is the common single-account case. */
    fun firstRawContactId(contactId: Long): Long? {
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    /** Full-size photo bytes for a contact (for vCard export), or null if none. */
    fun readPhotoBytes(contactId: Long): ByteArray? {
        val contactUri = ContentUris.withAppendedId(
            ContactsContract.Contacts.CONTENT_URI, contactId
        )
        val photoUri = Uri.withAppendedPath(
            contactUri, ContactsContract.Contacts.Photo.DISPLAY_PHOTO
        )
        return runCatching {
            resolver.openInputStream(photoUri)?.use { it.readBytes() }
        }.getOrNull()
    }

    // --- write: insert ---

    /** Create a new contact. Returns true on success. Uses a local (account-less) raw
     *  contact so it always works even with no synced account. */
    fun insert(contact: EditableContact): Boolean {
        val ops = ArrayList<ContentProviderOperation>()
        // index 0 = the RawContact this batch creates; data rows back-reference it.
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, contact.displayName.trim())
                .build()
        )
        contact.phones.filter { it.value.isNotBlank() }.forEach { p ->
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, p.value.trim())
                    .withValue(Phone.TYPE, Phone.TYPE_CUSTOM)
                    .withValue(Phone.LABEL, p.label)
                    .build()
            )
        }
        contact.emails.filter { it.value.isNotBlank() }.forEach { e ->
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, e.value.trim())
                    .withValue(Email.TYPE, Email.TYPE_CUSTOM)
                    .withValue(Email.LABEL, e.label)
                    .build()
            )
        }
        if (contact.note.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                    .withValue(Note.NOTE, contact.note.trim())
                    .build()
            )
        }
        contact.newPhoto?.let { bytes ->
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    .withValue(Photo.PHOTO, bytes)
                    .build()
            )
        }
        return runCatching {
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }.isSuccess
    }

    // --- write: update ---

    /** Replace a contact's name/phones/emails/note. Simplest correct approach: delete
     *  the existing name/phone/email/note data rows on this raw contact, then re-insert
     *  from the edited model. The raw contact (and contact id) is preserved. */
    fun update(contact: EditableContact): Boolean {
        val rawId = contact.rawContactId ?: return false
        val ops = ArrayList<ContentProviderOperation>()
        val mimes = arrayOf(
            StructuredName.CONTENT_ITEM_TYPE,
            Phone.CONTENT_ITEM_TYPE,
            Email.CONTENT_ITEM_TYPE,
            Note.CONTENT_ITEM_TYPE
        )
        mimes.forEach { mime ->
            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(rawId.toString(), mime)
                    )
                    .build()
            )
        }
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, contact.displayName.trim())
                .build()
        )
        contact.phones.filter { it.value.isNotBlank() }.forEach { p ->
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, p.value.trim())
                    .withValue(Phone.TYPE, Phone.TYPE_CUSTOM)
                    .withValue(Phone.LABEL, p.label)
                    .build()
            )
        }
        contact.emails.filter { it.value.isNotBlank() }.forEach { e ->
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, e.value.trim())
                    .withValue(Email.TYPE, Email.TYPE_CUSTOM)
                    .withValue(Email.LABEL, e.label)
                    .build()
            )
        }
        if (contact.note.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                    .withValue(Note.NOTE, contact.note.trim())
                    .build()
            )
        }
        // Photo: only touch it when the user changed it. A new photo (or a clear)
        // deletes existing Photo rows; a new photo then re-inserts. Otherwise the
        // existing photo is left alone (we never deleted its row above).
        if (contact.newPhoto != null || contact.photoCleared) {
            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(rawId.toString(), Photo.CONTENT_ITEM_TYPE)
                    )
                    .build()
            )
        }
        contact.newPhoto?.let { bytes ->
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    .withValue(Photo.PHOTO, bytes)
                    .build()
            )
        }
        return runCatching {
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }.isSuccess
    }

    // --- write: delete ---

    /** Delete the whole aggregated contact via its lookup URI (removes all raw contacts). */
    fun delete(contactId: Long, lookupKey: String): Boolean {
        val uri = ContactsContract.Contacts.getLookupUri(contactId, lookupKey)
            ?: ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        return runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }
}
