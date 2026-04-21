package com.joechrist.medtrack.data.local

import android.content.Context
import androidx.room.*
import com.joechrist.medtrack.data.local.dao.*
import com.joechrist.medtrack.data.local.entity.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        MedicationEntity::class,
        PrescriptionEntity::class,
        IntakeLogEntity::class,
        UserProfileEntity::class,
        SyncQueueEntity::class,
        ChatRoomEntity::class,
        ChatMessageEntity::class
    ],
    version      = 2,
    exportSchema = true
)
abstract class MedTrackDatabase : RoomDatabase() {
    abstract fun medicationDao(): MedicationDao
    abstract fun prescriptionDao(): PrescriptionDao
    abstract fun intakeLogDao(): IntakeLogDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun chatRoomDao(): ChatRoomDao
    abstract fun chatMessageDao(): ChatMessageDao
    companion object { const val DB_NAME = "medtrack.db" }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): MedTrackDatabase =
        Room.databaseBuilder(ctx, MedTrackDatabase::class.java, MedTrackDatabase.DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    @Provides fun provideMedicationDao(db: MedTrackDatabase)   = db.medicationDao()
    @Provides fun providePrescriptionDao(db: MedTrackDatabase) = db.prescriptionDao()
    @Provides fun provideIntakeLogDao(db: MedTrackDatabase)    = db.intakeLogDao()
    @Provides fun provideUserProfileDao(db: MedTrackDatabase)  = db.userProfileDao()
    @Provides fun provideSyncQueueDao(db: MedTrackDatabase)    = db.syncQueueDao()
    @Provides fun provideChatRoomDao(db: MedTrackDatabase)     = db.chatRoomDao()
    @Provides fun provideChatMessageDao(db: MedTrackDatabase)  = db.chatMessageDao()
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS chat_rooms (id TEXT NOT NULL PRIMARY KEY, doctorId TEXT NOT NULL, patientId TEXT NOT NULL, doctorName TEXT NOT NULL, patientAnonAlias TEXT NOT NULL, createdAtMs INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS chat_messages (id TEXT NOT NULL PRIMARY KEY, roomId TEXT NOT NULL, senderAlias TEXT NOT NULL, senderRole TEXT NOT NULL, content TEXT NOT NULL, isRead INTEGER NOT NULL DEFAULT 0, isOwn INTEGER NOT NULL DEFAULT 0, sentAtMs INTEGER NOT NULL, deliveryState TEXT NOT NULL DEFAULT 'SENT', FOREIGN KEY(roomId) REFERENCES chat_rooms(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_rooms_doctor ON chat_rooms(doctorId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_rooms_patient ON chat_rooms(patientId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_messages_room ON chat_messages(roomId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_messages_time ON chat_messages(sentAtMs)")
    }
}
