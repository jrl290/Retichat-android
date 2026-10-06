package com.newendian.retichat.data.db

/**
 * Database 13 → 14: member statuses and keys (RFed-spec Group.md, "Member
 * statuses and keys (2026-10-06)").
 *
 * - `chats.creatorHex`: a group's creator, which a `status` counts from and
 *   which alone answers an accept with one. Nullable, and null for every row
 *   already here: a group held before this version has no creator recorded, so
 *   it neither answers an accept nor counts a status (the web does the same).
 *   Nothing is lost.
 * - `group_statuses`: the creator's copy of each member's newest accept or
 *   leave that counted, the packed message exactly as received. It starts
 *   empty: a creator that held none answers with the copies it gets from now on.
 * - `group_held`: accepts and leaves held until their source's key is here
 *   (from the switch, around 2026-10-26). It starts empty.
 *
 * Plain SQL, run in order, so the JVM test runs the same statements on SQLite.
 * The new tables and column are written exactly as Room generates them from
 * the entities (`GroupStatusesMigrationTest` compares them with the generated
 * schema).
 */
object GroupStatusesMigration {
    const val FROM = 13
    const val TO = 14

    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `chats` ADD COLUMN `creatorHex` TEXT",
        "CREATE TABLE IF NOT EXISTS `group_statuses` (`chatId` TEXT NOT NULL, `memberHex` TEXT NOT NULL, `action` TEXT NOT NULL, `packed` BLOB NOT NULL, PRIMARY KEY(`chatId`, `memberHex`))",
        "CREATE TABLE IF NOT EXISTS `group_held` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chatId` TEXT NOT NULL, `srcHex` TEXT NOT NULL, `action` TEXT NOT NULL, `msgHash` TEXT NOT NULL, `packed` BLOB NOT NULL)",
    )
}
