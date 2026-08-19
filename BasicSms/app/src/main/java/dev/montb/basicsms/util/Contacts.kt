package dev.montb.basicsms.util

import android.content.Intent
import android.provider.ContactsContract

/** Intents for launching the system Contacts app. */
object Contacts {

    /**
     * Opens the Contacts app's "create contact" screen, pre-filling the phone number.
     * Uses ACTION_INSERT_OR_EDIT so the user can attach the number to an existing
     * contact or make a new one. FLAG_ACTIVITY_NEW_TASK lets it launch from anywhere.
     */
    fun insertContactIntent(number: String): Intent =
        Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
            type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
