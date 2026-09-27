package com.newendian.retichat.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newendian.retichat.data.db.dao.ChannelDao
import com.newendian.retichat.data.db.dao.ChatDao
import com.newendian.retichat.data.db.dao.ContactDao
import com.newendian.retichat.data.db.dao.InterfaceConfigDao
import com.newendian.retichat.data.db.dao.MessageDao
import com.newendian.retichat.data.db.entity.*

@Database(
    entities = [
        ContactEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        GroupMemberEntity::class,
        DeliveryTrackingEntity::class,
        InterfaceConfigEntity::class,
        ChannelEntity::class,
        ChannelMessageEntity::class,
        ChannelSenderEntity::class,
        ChannelNameStateEntity::class,
    ],
    version = NamesMigration.TO,
    exportSchema = false,
)
abstract class RetichatDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun interfaceConfigDao(): InterfaceConfigDao
    abstract fun channelDao(): ChannelDao

    companion object {
        @Volatile private var INSTANCE: RetichatDatabase? = null

        /** Migration 9 → 10: add isNameManual column to contacts table. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE contacts ADD COLUMN isNameManual INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** Migration 10 → 11: display names and the app-side privacy filter ([NamesMigration]). */
        private val MIGRATION_10_11 = object : Migration(NamesMigration.FROM, NamesMigration.TO) {
            override fun migrate(db: SupportSQLiteDatabase) {
                NamesMigration.STATEMENTS.forEach(db::execSQL)
            }
        }

        fun getInstance(context: Context): RetichatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RetichatDatabase::class.java,
                    "retichat.db"
                )
                    .addMigrations(MIGRATION_9_10, MIGRATION_10_11)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
