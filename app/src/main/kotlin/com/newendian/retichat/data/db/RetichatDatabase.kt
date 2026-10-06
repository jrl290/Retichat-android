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
import com.newendian.retichat.data.db.dao.DistroOutboxDao
import com.newendian.retichat.data.db.dao.GroupStatusDao
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
        ChannelMembershipEntity::class,
        DistroOutboxEntity::class,
        GroupStatusEntity::class,
        GroupHeldEntity::class,
    ],
    version = GroupStatusesMigration.TO,
    exportSchema = false,
)
abstract class RetichatDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun interfaceConfigDao(): InterfaceConfigDao
    abstract fun channelDao(): ChannelDao
    abstract fun distroOutboxDao(): DistroOutboxDao
    abstract fun groupStatusDao(): GroupStatusDao

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

        /** Migration 11 → 12: explicit contacts and the §5.4 names pass ([ContactsMigration]). */
        private val MIGRATION_11_12 = object : Migration(ContactsMigration.FROM, ContactsMigration.TO) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ContactsMigration.STATEMENTS.forEach(db::execSQL)
            }
        }

        /** Migration 12 → 13: channels and the distro, RFed SPEC §17.12 ([DistroChannelsMigration]). */
        private val MIGRATION_12_13 = object : Migration(DistroChannelsMigration.FROM, DistroChannelsMigration.TO) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DistroChannelsMigration.STATEMENTS.forEach(db::execSQL)
            }
        }

        /** Migration 13 → 14: member statuses and keys, RFed-spec Group.md ([GroupStatusesMigration]). */
        private val MIGRATION_13_14 = object : Migration(GroupStatusesMigration.FROM, GroupStatusesMigration.TO) {
            override fun migrate(db: SupportSQLiteDatabase) {
                GroupStatusesMigration.STATEMENTS.forEach(db::execSQL)
            }
        }

        fun getInstance(context: Context): RetichatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RetichatDatabase::class.java,
                    "retichat.db"
                )
                    .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
