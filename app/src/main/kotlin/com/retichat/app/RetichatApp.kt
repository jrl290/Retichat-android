package com.retichat.app

import android.app.Application
import com.retichat.app.data.db.RetichatDatabase
import com.retichat.app.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class RetichatApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: RetichatDatabase by lazy { RetichatDatabase.getInstance(this) }

    val repository: ChatRepository by lazy {
        ChatRepository(
            chatDao = database.chatDao(),
            messageDao = database.messageDao(),
            contactDao = database.contactDao(),
            attachmentDir = File(filesDir, "attachments").also { it.mkdirs() },
            scope = applicationScope,
        )
    }
}
