package com.newendian.retichat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    /**
     * Accumulated messages per chat for MessagingStyle history, each with its
     * own sender. Until 2026-09-27 a chat kept one sender name, and a stacked
     * group or channel notification credited every earlier message to the
     * latest sender.
     */
    private val history = NotificationHistory()

    /**
     * Create the notification channel (idempotent, safe to call multiple times).
     */
    fun createChannel(context: Context) {
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

    /**
     * Post / update a notification for an incoming message.
     * Messages from the same chat stack inside a single expandable notification
     * using MessagingStyle, and a summary groups all chats together.
     *
     * [senderName] is the resolved name of this message's sender (NameBook).
     * [conversationTitle] names a group or channel; a DM has none.
     */
    fun notify(
        context: Context,
        senderName: String,
        content: String,
        chatId: String,
        conversationTitle: String? = null,
    ) {
        Log.i(TAG, "notify: sender=$senderName, content='${content.take(30)}', chatId=$chatId")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!nm.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are disabled — skipping")
            return
        }

        // Accumulate messages for this chat
        val chatMsgs = history.add(chatId, senderName, content, System.currentTimeMillis())

        // Build MessagingStyle with conversation history, each message
        // under its own sender.
        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName("Me").build()
        ).setConversationTitle(NotificationHistory.title(conversationTitle, chatMsgs))
            .setGroupConversation(conversationTitle != null)

        for (entry in chatMsgs) {
            style.addMessage(entry.content, entry.timestamp, Person.Builder().setName(entry.sender).build())
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
            .setNumber(chatMsgs.size)
            .build()

        nm.notify(chatId, notifId, notification)

        // Summary notification (groups all chat notifications together)
        if (history.chatCount > 1) {
            val totalMessages = history.totalMessages
            val chatCount = history.chatCount
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
        history.clear(chatId)
    }

    /**
     * Clear all accumulated messages (call on full app open, etc.).
     */
    fun clearAll(context: Context) {
        history.clearAll()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
    }
}

/**
 * The messages a chat's notification stacks, each with its own sender
 * (pure, so the stacking rule is unit-tested).
 */
class NotificationHistory {
    data class Entry(val sender: String, val content: String, val timestamp: Long)

    private val chats = mutableMapOf<String, MutableList<Entry>>()

    /** Record one message; returns the chat's messages so far, oldest first. */
    @Synchronized
    fun add(chatId: String, sender: String, content: String, timestamp: Long): List<Entry> {
        val list = chats.getOrPut(chatId) { mutableListOf() }
        list.add(Entry(sender, content, timestamp))
        return list.toList()
    }

    @Synchronized fun clear(chatId: String) { chats.remove(chatId) }
    @Synchronized fun clearAll() { chats.clear() }

    val chatCount: Int @Synchronized get() = chats.size
    val totalMessages: Int @Synchronized get() = chats.values.sumOf { it.size }

    companion object {
        /**
         * A group or channel is titled by its name; a DM stack by its one
         * sender once it holds more than one message.
         */
        fun title(conversationTitle: String?, entries: List<Entry>): String? =
            conversationTitle ?: entries.takeIf { it.size > 1 }?.last()?.sender
    }
}
