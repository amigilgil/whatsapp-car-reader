package com.whatsappcarreader.manager

import androidx.lifecycle.LiveData
import androidx.room.*
import com.whatsappcarreader.model.Contact
import com.whatsappcarreader.model.VoiceSample

// ─── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun getAllContacts(): LiveData<List<Contact>>

    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    suspend fun getAllContactsSync(): List<Contact>

    @Query("SELECT * FROM contacts WHERE phoneOrName = :key LIMIT 1")
    suspend fun getContact(key: String): Contact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(contact: Contact)

    @Update
    suspend fun update(contact: Contact)

    @Delete
    suspend fun delete(contact: Contact)

    @Query("UPDATE contacts SET elevenLabsVoiceId = :voiceId, updatedAt = :now WHERE phoneOrName = :key")
    suspend fun setElevenLabsVoiceId(key: String, voiceId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE contacts SET voiceSamplesSeconds = voiceSamplesSeconds + :seconds WHERE phoneOrName = :key")
    suspend fun addVoiceSampleSeconds(key: String, seconds: Float)
}

@Dao
interface VoiceSampleDao {
    @Query("SELECT * FROM voice_samples WHERE contactKey = :key ORDER BY createdAt ASC")
    suspend fun getSamplesForContact(key: String): List<VoiceSample>

    @Query("SELECT SUM(durationSeconds) FROM voice_samples WHERE contactKey = :key")
    suspend fun getTotalDurationForContact(key: String): Float?

    @Query("SELECT * FROM voice_samples WHERE uploadedToElevenLabs = 0")
    suspend fun getPendingUploads(): List<VoiceSample>

    @Insert
    suspend fun insert(sample: VoiceSample): Long

    @Query("UPDATE voice_samples SET uploadedToElevenLabs = 1 WHERE id = :id")
    suspend fun markUploaded(id: Long)

    @Query("DELETE FROM voice_samples WHERE contactKey = :key")
    suspend fun deleteForContact(key: String)
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [Contact::class, VoiceSample::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun voiceSampleDao(): VoiceSampleDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "whatsapp_car_reader.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
