package dev.montb.basicphone.data

/** One row of the system call log, projected for the UI. */
data class CallLogEntry(
    val id: Long,
    val number: String,
    val name: String?,     // cached contact name, if any
    val type: Int,         // CallLog.Calls.INCOMING_TYPE / OUTGOING_TYPE / MISSED_TYPE / ...
    val date: Long,        // epoch millis
    val durationSec: Long,
    val accountId: String?, // PHONE_ACCOUNT_ID -> maps to a SIM/carrier
    val presentation: Int = 1, // CallLog.Calls.NUMBER_PRESENTATION (1=ALLOWED, 2=RESTRICTED/withheld, 3=UNKNOWN, 4=PAYPHONE)
    val spamHint: SpamHint = SpamHint.NONE // offline heuristic spam signal
)

/** Offline spam signals we can derive without any online database. Ordered by severity. */
enum class SpamHint {
    NONE,
    WITHHELD,     // caller ID hidden / unknown / payphone (NUMBER_PRESENTATION != ALLOWED)
    REPEATED;     // unknown number that hit you several times recently (call-bomb pattern)

    val label: String? get() = when (this) {
        NONE -> null
        WITHHELD -> "⚠ Hidden caller"
        REPEATED -> "⚠ Possible spam"
    }
}
