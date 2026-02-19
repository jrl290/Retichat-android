package com.retichat.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.retichat.app.data.db.dao.ChatDao
import com.retichat.app.data.db.dao.ContactDao
import com.retichat.app.data.db.dao.MessageDao
import com.retichat.app.data.db.entity.*

@Database(
    entities = [
        ContactEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        GroupMemberEntity::class,
        DeliveryTrackingEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class RetichatDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: RetichatDatabase? = null

        fun getInstance(context: Context): RetichatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RetichatDatabase::class.java,
                    "retichat.db"
                ).build().also { INSTANCE = it }
            }
    }
}
