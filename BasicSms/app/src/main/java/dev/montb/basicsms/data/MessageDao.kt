package dev.montb.basicsms.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    /** Used by import to skip messages we already have (same number + time + body). */
    @Query("SELECT COUNT(*) FROM messages WHERE address = :address AND timestamp = :timestamp AND body = :body")
    suspend fun countMatching(address: String, timestamp: Long, body: String): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun total(): Int

    /**
     * One row per conversation: the latest message per address plus an unread count.
     * Runs entirely in SQLite and is observed as a Flow, so the UI thread never
     * touches the database.
     */
    @Query(
        """
        SELECT m.address AS address,
               m.body AS lastBody,
               m.timestamp AS lastTimestamp,
               (SELECT COUNT(*) FROM messages u
                 WHERE u.address = m.address AND u.read = 0 AND u.incoming = 1) AS unreadCount
        FROM messages m
        WHERE m.timestamp = (SELECT MAX(timestamp) FROM messages x WHERE x.address = m.address)
        GROUP BY m.address
        ORDER BY lastTimestamp DESC
        """
    )
    fun conversations(): Flow<List<Conversation>>

    /** Paged thread: Paging 3 loads pages on demand instead of all history at once. */
    @Query("SELECT * FROM messages WHERE address = :address ORDER BY timestamp DESC")
    fun messagesFor(address: String): PagingSource<Int, MessageEntity>

    @Query("UPDATE messages SET read = 1 WHERE address = :address AND incoming = 1")
    suspend fun markRead(address: String)
}
