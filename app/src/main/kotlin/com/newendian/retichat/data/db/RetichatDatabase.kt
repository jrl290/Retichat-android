package com.newendian.retichat.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    ],
    version = 9,
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

        fun getInstance(context: Context): RetichatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RetichatDatabase::class.java,
                    "retichat.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
