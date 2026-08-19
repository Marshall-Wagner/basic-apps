package dev.montb.basicsms.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One SMS message stored locally for fast, paged UI. */
@Entity(
    tableName = "messages",
    indices = [Index("address"), Index("timestamp")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,      // sender/recipient phone number
    val body: String,
    val timestamp: Long,      // epoch millis
    val incoming: Boolean,    // true = received, false = sent by us
    val read: Boolean = false,
    val subId: Int = -1,      // which SIM/subscription (-1 = unknown/default)
    val attachmentPath: String? = null, // local file path to an MMS image, if any
    val attachmentMime: String? = null  // mime type of the attachment, if any
)

/** Lightweight projection for the conversation list (one row per contact). */
data class Conversation(
    val address: String,
    val lastBody: String,
    val lastTimestamp: Long,
    val unreadCount: Int
)
