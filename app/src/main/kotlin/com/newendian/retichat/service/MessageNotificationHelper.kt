package com.newendian.retichat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.newendian.retichat.MainActivity
import com.newendian.retichat.R

/**
 * Handles creating notification channels and posting message notifications.
 * Groups messages per chat using MessagingStyle so they stack like a chat app.
 */
object MessageNotificationHelper {

    private const val CHANNEL_ID = "retichat_messages"
    private const val GROUP_KEY = "com.newendian.retichat.MESSAGES"
    private const val SUMMARY_NOTIF_ID = 0
    const val EXTRA_CHAT_ID = "extra_chat_id"
    private const val TAG = "MsgNotifHelper"

    /**
     * Per-chat notification ID. We use the chatId hashCode so that
     * multiple messages in the same chat update the same notification.
     */
    private fun notifIdForChat(chatId: String): Int = chatId.hashCode() and 0x7FFFFFFF

    /** Tracks accumulated messages per chat for MessagingStyle history. */
    private data class ChatMessages(
        val senderName: String,
        val messages: MutableList<Pair<String, Long>>,   // content, timestamp
    )
    private val activeChatMessages = mutableMapOf<String, ChatMessages>()

    /**
     * Create the notification channel (idempotent, safe to call multiple times).
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_messages)
            val desc = context.getString(R.string.notification_channel_messages_desc)
            val channel = NotificationChannel(
                CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = desc
                enableVibration(true)
                setShowBadge(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Post / update a notification for an incoming message.
     * Messages from the same chat stack inside a single expandable notification
     * using MessagingStyle, and a summary groups all chats together.
     */
    fun notify(
        context: Context,
        senderName: String,
        content: String,
        chatId: String,
    ) {
        Log.i(TAG, "notify: sender=$senderName, content='${content.take(30)}', chatId=$chatId")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!nm.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are disabled — skipping")
            return
        }

        // Accumulate messages for this chat
        val now = System.currentTimeMillis()
        val chatMsgs = activeChatMessages.getOrPut(chatId) {
            ChatMessages(senderName, mutableListOf())
        }
        chatMsgs.messages.add(content to now)

        // Build MessagingStyle with conversation history
        val sender = Person.Builder().setName(senderName).build()
        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName("Me").build()
        ).setConversationTitle(if (chatMsgs.messages.size > 1) senderName else null)

        for ((msg, ts) in chatMsgs.messages) {
            style.addMessage(msg, ts, sender)
        }

        // PendingIntent opens the specific chat
        val notifId = notifIdForChat(chatId)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_ID, chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Per-chat notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setNumber(chatMsgs.messages.size)
            .build()

        nm.notify(chatId, notifId, notification)

        // Summary notification (groups all chat notifications together)
        if (activeChatMessages.size > 1) {
            val totalMessages = activeChatMessages.values.sumOf { it.messages.size }
            val chatCount = activeChatMessages.size
            val summaryText = "$totalMessages messages from $chatCount chats"

            val summaryIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val summaryPending = PendingIntent.getActivity(
                context, SUMMARY_NOTIF_ID, summaryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val summary = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Retichat")
                .setContentText(summaryText)
                .setStyle(NotificationCompat.InboxStyle().setSummaryText(summaryText))
                .setAutoCancel(true)
                .setContentIntent(summaryPending)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .build()

            nm.notify(SUMMARY_NOTIF_ID, summary)
        }
    }

    /**
     * Clear accumulated messages for a chat (call when user opens the conversation).
     */
    fun clearChat(chatId: String) {
        activeChatMessages.remove(chatId)
    }

    /**
     * Clear all accumulated messages (call on full app open, etc.).
     */
    fun clearAll(context: Context) {
        activeChatMessages.clear()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
    }
}
