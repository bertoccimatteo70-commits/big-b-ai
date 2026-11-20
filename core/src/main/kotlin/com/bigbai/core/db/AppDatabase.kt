package com.bigbai.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.bigbai.core.model.Attachment
import com.bigbai.core.model.Source
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class Converters {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @TypeConverter
    fun attachmentsToString(value: List<Attachment>): String = json.encodeToString(value)

    @TypeConverter
    fun stringToAttachments(value: String): List<Attachment> =
        runCatching { json.decodeFromString<List<Attachment>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun sourcesToString(value: List<Source>): String = json.encodeToString(value)

    @TypeConverter
    fun stringToSources(value: String): List<Source> =
        runCatching { json.decodeFromString<List<Source>>(value) }.getOrDefault(emptyList())
}

@Database(
    entities = [
        FolderEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        MemoryCategoryEntity::class,
        MemoryEntity::class,
        ProjectEntity::class,
        ProjectMemoryEntity::class,
        ProjectMessageEntity::class,
        CodeChangeEntity::class,
        DownloadEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun folderDao(): FolderDao
    abstract fun memoryDao(): MemoryDao
    abstract fun projectDao(): ProjectDao
    abstract fun projectMemoryDao(): ProjectMemoryDao
    abstract fun projectMessageDao(): ProjectMessageDao
    abstract fun codeChangeDao(): CodeChangeDao
    abstract fun downloadDao(): DownloadDao
    abstract fun backupStateDao(): BackupStateDao

    companion object {
        const val FILE_NAME = "bigbai.db"

        /** Opens the database with full-file encryption (SQLCipher). The passphrase is a
         *  random 256-bit key generated on first launch and wrapped by Android Keystore. */
        fun open(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME)
                .openHelperFactory(factory)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}
