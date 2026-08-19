package dev.montb.basicsms.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// v2 added subId (which SIM); v3 added attachmentPath/attachmentMime (MMS images).
// Fresh personal app, so we allow a destructive upgrade instead of a migration.
@Database(entities = [MessageEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "basicsms.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
