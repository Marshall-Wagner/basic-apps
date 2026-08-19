package dev.montb.basiccontacts

import android.app.Application
import dev.montb.basiccontacts.data.ContactsRepository

/** Application singleton owning the repository so the UI shares one instance. */
class BasicContactsApp : Application() {
    val repository by lazy { ContactsRepository(this) }
}
