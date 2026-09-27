package com.newendian.retichat.data.db

/**
 * Database 10 → 11: display names (LXMF-rust/DISPLAY_NAMES.md §5.1, §5.4) and
 * the app-side privacy filter (§7).
 *
 * - `contacts` is rebuilt with three name slots and `isAllowlisted` (SQLite
 *   on Android 12 cannot drop a column). A name the user typed
 *   (`isNameManual`) becomes `localName`; any other name becomes
 *   `messageName` unless it is a hash placeholder (the 8-hex name Android
 *   gave contacts it knew no name for), which is dropped. Never lose a name
 *   the user typed.
 * - Every existing contact is allowlisted: each belongs to a conversation or
 *   group the user already has, and the filter this replaces never let a
 *   router-delivered message through, so no row here was created by a
 *   stranger's message the user had filtered. Without this, turning the
 *   app-side filter on would silently drop the user's own contacts.
 * - `messages.systemKind` marks system lines that resolve a member's name
 *   when shown.
 * - `channel_senders` (per channel and poster: seen, and the poster's channel
 *   name) and `channel_name_state` (the channel send rule), seeded with the
 *   posters already stored. The names start empty; the next posts carry them.
 *
 * Plain SQL, run in order, so the JVM test runs the same statements on SQLite.
 */
object NamesMigration {
    const val FROM = 10
    const val TO = 11

    /** A trimmed, lowercased name that is 8+ hex digits and a prefix of the hash. */
    private const val PLACEHOLDER =
        "(length(lower(trim(displayName))) >= 8 " +
            "AND NOT lower(trim(displayName)) GLOB '*[^0-9a-f]*' " +
            "AND substr(lower(destHashHex), 1, length(trim(displayName))) = lower(trim(displayName)))"

    val STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `contacts_new` (`destHashHex` TEXT NOT NULL, `localName` TEXT, " +
            "`messageName` TEXT, `announceName` TEXT, `publicKeyHex` TEXT, `addedAt` INTEGER NOT NULL, " +
            "`isAllowlisted` INTEGER NOT NULL, PRIMARY KEY(`destHashHex`))",
        "INSERT INTO `contacts_new` (destHashHex, localName, messageName, announceName, publicKeyHex, addedAt, isAllowlisted) " +
            "SELECT destHashHex, " +
            "CASE WHEN isNameManual != 0 AND trim(displayName) != '' THEN trim(displayName) END, " +
            "CASE WHEN isNameManual = 0 AND trim(displayName) != '' AND NOT $PLACEHOLDER THEN trim(displayName) END, " +
            "NULL, publicKeyHex, addedAt, 1 FROM `contacts`",
        "DROP TABLE `contacts`",
        "ALTER TABLE `contacts_new` RENAME TO `contacts`",
        "ALTER TABLE `messages` ADD COLUMN `systemKind` TEXT",
        "CREATE TABLE IF NOT EXISTS `channel_senders` (`channelId` TEXT NOT NULL, `senderHex` TEXT NOT NULL, " +
            "`channelName` TEXT, `firstSeenAt` INTEGER NOT NULL, `nameAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`channelId`, `senderHex`))",
        "INSERT OR IGNORE INTO `channel_senders` (channelId, senderHex, channelName, firstSeenAt, nameAt) " +
            "SELECT channelId, lower(sourceHashHex), NULL, MIN(timestamp), 0 FROM `channel_messages` " +
            "WHERE isOutbound = 0 GROUP BY channelId, lower(sourceHashHex)",
        "CREATE TABLE IF NOT EXISTS `channel_name_state` (`channelId` TEXT NOT NULL, " +
            "`lastDigestHex` TEXT NOT NULL, `lastIncludedAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`))",
    )
}
