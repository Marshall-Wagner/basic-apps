package dev.montb.basiccontacts.data

/** One row in the contact list (a single aggregated contact). */
data class ContactSummary(
    val contactId: Long,
    val lookupKey: String,
    val displayName: String,
    val primaryNumber: String?,    // shown as the list subtitle; null = no phone
    val photoUri: String?
)

/** A typed value (phone or email), with a human label like "Mobile" / "Home". */
data class LabeledValue(
    val value: String,
    val label: String
)

/** Full detail for one contact: all numbers + emails. Built from several data rows. */
data class ContactDetail(
    val contactId: Long,
    val lookupKey: String,
    val displayName: String,
    val photoUri: String?,
    val phones: List<LabeledValue>,
    val emails: List<LabeledValue>,
    val note: String?
)

/** The editable shape used by the add/edit screen. [rawContactId] is null for a new
 *  contact (insert), set when editing an existing one (update).
 *  [photoUri] is the existing contact photo (display only). [newPhoto] holds freshly
 *  picked JPEG/PNG bytes to write on save; [photoCleared] true = remove the photo. */
data class EditableContact(
    val rawContactId: Long? = null,
    val displayName: String = "",
    val phones: List<LabeledValue> = listOf(LabeledValue("", PHONE_MOBILE)),
    val emails: List<LabeledValue> = emptyList(),
    val note: String = "",
    val photoUri: String? = null,
    val newPhoto: ByteArray? = null,
    val photoCleared: Boolean = false
) {
    companion object {
        const val PHONE_MOBILE = "Mobile"
        const val PHONE_HOME = "Home"
        const val PHONE_WORK = "Work"
        const val EMAIL_HOME = "Home"
        const val EMAIL_WORK = "Work"
    }
}
