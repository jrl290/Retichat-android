package com.newendian.retichat.data.db

/**
 * Database 17 → 18: a channel's place in the chat list (James, 2026-11-03).
 * `channels` gains `lastActiveAt`: when a post was last sent from the channel
 * or came in while it was open. The chat list orders channels by it, so
 * opening one, or posts that come in while it is not open, leave it in its
 * place. Every channel already here starts where it stands now, at its newest
 * post's time (`lastMessageTime`; 0 for none, when `joinedAt` places it, as
 * before).
 *
 * Plain SQL, run in order, so the JVM test runs the same statements on SQLite.
 * The column is written exactly as Room generates it from the entity
 * (`ChannelActivityMigrationTest` compares it with the generated schema).
 */
object ChannelActivityMigration {
    const val FROM = 17
    const val TO = 18

    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `channels` ADD COLUMN `lastActiveAt` INTEGER NOT NULL DEFAULT 0",
        "UPDATE `channels` SET `lastActiveAt` = `lastMessageTime`",
    )
}
