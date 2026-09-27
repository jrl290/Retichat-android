package com.newendian.retichat.data.db

/**
 * Database 10 → 11: display names (LXMF-rust/DISPLAY_NAMES.md §5.1, §5.4) and
 * the app-side privacy filter (§7).
 *
 * - `contacts` is rebuilt with three name slots and `isAllowlisted` (SQLite
 *   on Android 12 cannot drop a column). A name the user typed
 *   (`isNameManual`) becomes `localName`; any other name becomes
 *   `messageName` unless it is a placeholder, which is dropped: a hash
 *   placeholder (the 8-hex name Android gave contacts it knew no name for),
 *   or one of the app placeholders "Retichat", "Retichat Web" and
 *   "Anonymous Peer" (§1: there is no placeholder name; the sender's own
 *   migration turns its "Retichat" into no name, and an unset sender never
 *   sends a clear, so a migrated placeholder would never be replaced).
 * - Never lose a name the user typed: a DM chat's `chats.name` can hold a
 *   rename the contact row lost. Until 2026-09-27 a rename wrote both, but
 *   re-adding the contact (QR code, link) reset the contact to its hash and
 *   left the chat, and renaming a DM with no contact row wrote only the chat.
 *   So a DM chat name that is a real name and differs from the contact's
 *   stored name becomes `localName` when the user had not renamed the
 *   contact, and a DM chat with no contact gets a contact row (not
 *   allowlisted, as a distro sent-copy creates it) carrying that name.
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

    /** [name] trimmed and lowercased is 8+ hex digits and a prefix of [hash]. */
    private fun hashPlaceholder(name: String, hash: String) =
        "(length(lower(trim($name))) >= 8 " +
            "AND NOT lower(trim($name)) GLOB '*[^0-9a-f]*' " +
            "AND substr(lower($hash), 1, length(trim($name))) = lower(trim($name)))"

    /** [name] is a name someone provided: not empty and no placeholder (hash or app). */
    private fun providedName(name: String, hash: String) =
        "(trim($name) != '' AND NOT ${hashPlaceholder(name, hash)} " +
            "AND lower(trim($name)) NOT IN ('retichat', 'retichat web', 'anonymous peer'))"

    /** A DM chat row: `dm_` and the peer's 32 lowercase hex. */
    private const val DM_CHAT = "isGroup = 0 AND id GLOB 'dm_*' AND length(id) = 35 " +
        "AND NOT substr(id, 4) GLOB '*[^0-9a-f]*'"

    val STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `contacts_new` (`destHashHex` TEXT NOT NULL, `localName` TEXT, " +
            "`messageName` TEXT, `announceName` TEXT, `publicKeyHex` TEXT, `addedAt` INTEGER NOT NULL, " +
            "`isAllowlisted` INTEGER NOT NULL, PRIMARY KEY(`destHashHex`))",
        "INSERT INTO `contacts_new` (destHashHex, localName, messageName, announceName, publicKeyHex, addedAt, isAllowlisted) " +
            "SELECT destHashHex, " +
            "CASE WHEN isNameManual != 0 AND trim(displayName) != '' THEN trim(displayName) " +
            "ELSE (SELECT trim(ch.name) FROM `chats` ch WHERE ch.id = 'dm_' || lower(contacts.destHashHex) AND ch.isGroup = 0 " +
            "AND ${providedName("ch.name", "contacts.destHashHex")} " +
            "AND trim(ch.name) != trim(contacts.displayName)) END, " +
            "CASE WHEN isNameManual = 0 AND ${providedName("displayName", "destHashHex")} THEN trim(displayName) END, " +
            "NULL, publicKeyHex, addedAt, 1 FROM `contacts`",
        "INSERT OR IGNORE INTO `contacts_new` (destHashHex, localName, messageName, announceName, publicKeyHex, addedAt, isAllowlisted) " +
            "SELECT substr(id, 4), CASE WHEN ${providedName("name", "substr(id, 4)")} THEN trim(name) END, " +
            "NULL, NULL, NULL, createdAt, 0 FROM `chats` WHERE $DM_CHAT " +
            "AND NOT EXISTS (SELECT 1 FROM `contacts_new` c WHERE lower(c.destHashHex) = substr(chats.id, 4))",
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
